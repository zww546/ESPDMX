#include "dmx.h"
#include "dmx_state.h"
#include "pins.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "driver/uart.h"     // uart_flush_input（RDM 进入前清 RX）
#include "esp_log.h"
#include "esp_dmx.h"
#include "esp_intr_alloc.h"   // esp_intr_dump（诊断用）

// 跨宇宙通道落位已在真机验证过（2026-09-14）：
//   写 ch513..520 + 横跨边界的 ch509..516 → U1 ch1..8 = 1..8、
//   U2 ch1..8 = 54 55 56 57 105 106 107 108，与预期逐字节一致。
// 同一逻辑另有宿主端测试覆盖（test_host/test_render.c 的 R6/R7）。

static const char *TAG = "dmx";

// 单帧等待上限：正常一帧 = 513 槽 @250k 8N2 ≈ 23ms。
// ⚠ 不要用驱动自带的 DMX_TIMEOUT_TICK（它的值其实是 **1250ms**）：一旦某帧没发完，
//   整个输出任务会被拖住 1.25 秒（≈50 帧），现场表现为明显卡顿。
//   60ms 足够正常一帧完成；真卡住时最多丢一帧，下一轮自动重试。
#define DMX_FRAME_WAIT_MS   60

// ==================== RS-485 接收开关 ====================
// 0 = **禁用接收**（当前默认）：纯发送端行为，与 v6 及以前一致。
// 1 = 启用接收：把 RO 绑到 UART RX，并在每帧之间把 EN 切到接收态。
//
// 为什么现在默认关闭（不是保守，是当前状态下的正确选择）：
//   RX 虽然接上了，但**固件里还没有任何代码去消费收到的数据**。开着接收会有三个副作用：
//     1) 我们自己的发送会被自己的接收端听回去（半双工回波），帧一多 RX FIFO 就溢出；
//     2) 每次 EN 从"接收"切回"发送"时，A/B 线上的电平跳变会被当成起始位，
//        esp_dmx 会把它登记为 RX break，进而搅动驱动的状态机；
//     3) 每帧结束后都有一次多余的 GPIO 写。
//   换句话说：接收通路"接线正确但没有消费者"，开着只有坏处。
//   等真正实现 DMX 输入 / RDM 时，把这里改成 1，并同时补上接收任务。
//
// 要恢复接收：把下面这行的 0 改成 1，并补上接收任务。
// ⚠ 注意：`idf.py -DDMX_RX_ENABLE=1` **不管用** —— 那只是设了个 CMake 变量，
//   不会变成 C 预处理宏（实测确认过）。改这一行，或者用
//   target_compile_definitions(${COMPONENT_LIB} PRIVATE DMX_RX_ENABLE=1)。
#ifndef DMX_RX_ENABLE
// 接收功能保持关闭：没有消费者，开着只有副作用（半双工回波 / 伪 break 搅动状态机）。
// ⚠ 实测复核（2026-09）：为验证 EN/RX 收回修复临时置 1，U2 立刻复现了
//   "fps 从 41 飙到 237、ok=1 但一帧不再耗时 22.6ms" 的故障形态 ——
//   说明当初关掉它的判断**现在依然成立**。要真正恢复接收，
//   除了打开这个宏，还必须先解决伪 break 问题并补上接收任务。
#define DMX_RX_ENABLE       0
#endif

// 编译期开关：RDM 重装后是否把被 UART 借走的 EN/RX 收回来（见 dmx_pins_reclaim）。
//   1 = 收回（生产值）
//   0 = 不收回 —— **仅用于 A/B 对照**，用来证明这个修复确实在起作用：
//       置 0 时 EN 仍由 UART RTS 驱动，gpio_set_level() 对它无效，
//       于是打开 DMX_RX_ENABLE 后 EN 也不会在收发之间翻转。
#ifndef DMX_RECLAIM_PINS
#define DMX_RECLAIM_PINS    1
#endif

// 历史说明（勿删，避免以后重复踩坑）：
//   这里曾经有一段“连续 80 帧失败 → dmx_driver_delete + 重装”的自愈机制。
//   故障形态是：UART TX_DONE 中断被 cache 停顿饿死 → 驱动卡在 DMX_STATUS_SENDING
//   → 之后 dmx_send() 恒返回 0（DMX 停发，但 BLE/App 一切正常，只能重启恢复）。
//   打开 CONFIG_DMX_ISR_IN_IRAM=y（DMX ISR 常驻 IRAM，躲开 cache 停顿）后该故障
//   彻底消失、实测再未触发，故移除该自愈机制。
//   若将来再次出现“灯定格、蓝牙正常”，按序检查：
//     ① CONFIG_DMX_ISR_IN_IRAM 是否仍为 y；
//     ② 是否新增了干扰源（射频共存、在发送窗口内写 flash / 跑 FATFS）。

// ---- 输出口表 ----
// 方向控制：用户的 485 模块只有**一个 EN 脚**（DE 与 /RE 在模块内部已处理），
// 所以下面的 en_pin / en_rx_level / en_tx_level 把"收发一对逻辑"
// （v7 早期版本是 de_pin + re_pin 两个物理脚）收敛成同一个 GPIO 上的两个电平。
// 保留"成对写 + 顺序"的写法，好处是将来换成 6 脚模块（DE/RE 分开）时
// 只要把两行改回两个引脚即可，逻辑不用动。
typedef struct {
    dmx_port_t   port;
    uint8_t      universe;    // 0 → ch 1..512, 1 → ch 513..1024
    int          tx_pin;      // → 模块 TXD (DI)
    int          rx_pin;      // ← 模块 RXD (RO)（v6 及以前悬空，v7 起接线并绑定）
    int          en_pin;      // 方向脚
    int          en_rx_level; // 接收态电平（本模块 = 0）
    int          en_tx_level; // 发送态电平（本模块 = 1）
    bool         ok;
    uint8_t      frame[513];  // 每口独立缓冲（不能共用 static！）
} dmx_out_t;

static dmx_out_t s_out[DMX_UNIVERSES] = {
    { .port = DMX_NUM_1, .universe = 0, .tx_pin = DMX_TX_PIN,  .rx_pin = DMX_RX_PIN,
      .en_pin = DMX_EN_PIN,  .en_rx_level = 0, .en_tx_level = 1 },
    { .port = DMX_NUM_2, .universe = 1, .tx_pin = DMX_TX2_PIN, .rx_pin = DMX_RX2_PIN,
      .en_pin = DMX_EN2_PIN, .en_rx_level = 0, .en_tx_level = 1 },
};

// 最近一次遥测算出的帧率（每 5s 更新一次）；状态帧上报用
static volatile uint8_t  s_fps[DMX_UNIVERSES];

static volatile uint32_t s_frames[DMX_UNIVERSES];
static volatile uint32_t s_fails[DMX_UNIVERSES];
static volatile bool     s_last_ok[DMX_UNIVERSES];

// 方向控制函数在后面定义，这里先声明（RDM 的方向切换要用）
static inline void dmx_transceiver_tx(const dmx_out_t *o);
static inline void dmx_transceiver_rx(const dmx_out_t *o);
static inline void dmx_transceiver_idle(const dmx_out_t *o);
static bool dmx_install_driver(dmx_out_t *o);   // RDM 结束后整口重装要用

// ---- RDM 用的输出暂停/恢复 ----
// RDM 要独占总线：控制器发请求、灯具应答，期间不能再有 DMX 帧插进来。
// 用"标志 + 应答信号量"让输出任务自己停到安全点（而不是直接删驱动）。
static volatile bool s_paused[DMX_UNIVERSES];
static SemaphoreHandle_t s_pause_ack[DMX_UNIVERSES];

// RDM 结束后的"整口重装"握手。
// 请求方（rdm_task，跑在 core 0）只置标志，真正执行重装的是 **core 1 的 dmx_task**。
// 原因见 dmx_rdm_mode()：esp_intr_alloc() 把中断路由到调用核，谁 install
// 中断就落在谁身上 —— 在 core 0 重装会把 DMX 中断永久搬到射频核。
static volatile bool s_reinstall_req[DMX_UNIVERSES];
static SemaphoreHandle_t s_reinstall_ack[DMX_UNIVERSES];

bool dmx_output_pause(uint8_t universe)
{
    if (universe >= DMX_UNIVERSES || !s_out[universe].ok) return false;
    if (s_paused[universe]) return true;
    if (!s_pause_ack[universe]) {
        s_pause_ack[universe] = xSemaphoreCreateBinary();
        if (!s_pause_ack[universe]) return false;
    }
    if (!s_reinstall_ack[universe]) {
        s_reinstall_ack[universe] = xSemaphoreCreateBinary();
        if (!s_reinstall_ack[universe]) return false;
    }
    xSemaphoreTake(s_pause_ack[universe], 0);      // 清掉旧信号
    s_paused[universe] = true;
    // 输出任务一帧最长 ~23ms（+60ms 等待），给 200ms 足够它停到安全点
    if (xSemaphoreTake(s_pause_ack[universe], pdMS_TO_TICKS(200)) != pdTRUE) {
        // ⚠ 超时必须**回滚** s_paused。否则这个宇宙从此一帧 DMX 都不发，
        //   只能重启固件；而调用方（rdm.c）只看到 dmx_output_pause 返回 false、
        //   记一句"无法暂停 DMX 输出"，App 那边看到的是"扫描结束" ——
        //   现场表现就是"扫过一次 RDM 之后这个宇宙的灯全不动了"，极难查。
        s_paused[universe] = false;
        return false;
    }
    return true;
}

void dmx_output_resume(uint8_t universe)
{
    if (universe >= DMX_UNIVERSES) return;
    s_paused[universe] = false;
}

bool dmx_rdm_mode(uint8_t universe, bool on)
{
    if (universe >= DMX_UNIVERSES) return false;
    dmx_out_t *o = &s_out[universe];
    if (on) {
        if (!o->ok) return false;
        // 绑定 RX 引脚；EN 作为 RTS 交给驱动，收发之间由硬件自动换向
        if (!dmx_set_pin(o->port, o->tx_pin, o->rx_pin, o->en_pin)) {
            ESP_LOGE(TAG, "U%d RDM: dmx_set_pin(EN→RTS) 失败", universe + 1);
            return false;
        }
        // 先置接收态，把上电/上一次发送的残留数据清掉
        gpio_set_level((gpio_num_t)o->en_pin, o->en_rx_level);
        uart_flush_input(o->port);
    } else {
        // ⚠ 退出 RDM 这一支**不能**因为 !o->ok 就早退（上面的 on 分支才需要）：
        //   重装失败恰恰会把 o->ok 置 false，而恢复它的唯一手段就是**再重装一次**。
        //   早退等于把"一次失败的安装"变成永久 brick，只能重启固件。
        //
        // ⚠ 重装**不能在这里做**。本函数由 rdm_scan() → rdm_task 调进来，
        //   而 rdm_task 钉在 core 0（ble_dmx.c）。dmx_driver_install() 内部
        //   的 esp_intr_alloc() 会把中断路由到「调用它的那个核」，于是在 core 0
        //   重装 = 把 DMX 的 UART/GPTimer 中断从 core 1 永久搬到 core 0，
        //   让它去和 BLE/Wi-Fi 抢 → dmx_wait_sent() 恒超时 → dmx_send() 恒返回 0
        //   → fail 以帧率增长、灯收不到 DMX，**只能重启恢复**。
        //
        //   所以这里只发请求，实际重装交给 core 1 的 dmx_task 执行
        //   （它此刻正停在 s_paused 分支里，没有在用驱动，是安全点）。
        if (!s_reinstall_ack[universe]) {
            ESP_LOGE(TAG, "U%d RDM: 重装信号量未创建（dmx_output_pause 没跑过？）",
                     universe + 1);
            return false;
        }
        // 最多试两次：core 1 的 dmx_task 偶尔会晚一拍（正好在做长操作），
        // 一次超时不代表它真的不响应。
        for (int attempt = 0; attempt < 2; attempt++) {
            xSemaphoreTake(s_reinstall_ack[universe], 0);   // 清掉旧信号
            s_reinstall_req[universe] = true;
            if (xSemaphoreTake(s_reinstall_ack[universe], pdMS_TO_TICKS(500)) != pdTRUE) {
                s_reinstall_req[universe] = false;
                ESP_LOGW(TAG, "U%d RDM: 驱动重装超时（第 %d 次）", universe + 1, attempt + 1);
                continue;
            }
            // ⚠ 收到应答还不够，要看**安装到底成没成**：dmx_task 把
            //   dmx_install_driver() 的结果写在 s_out[].ok 上。
            //   以前这里无条件 return true —— 于是"重装成功"和"重装失败"在调用方
            //   看来一模一样，调用方（rdm.c）又忽略了返回值，最终表现成
            //   "扫过一次 RDM 之后这个宇宙的灯永远不动"，而且一条错误都看不到。
            if (s_out[universe].ok) {
                ESP_LOGI(TAG, "U%d RDM: 驱动重装完成，DMX 输出恢复正常", universe + 1);
                return true;
            }
            ESP_LOGE(TAG, "U%d RDM: 驱动重装失败（dmx_install_driver 返回错误）",
                     universe + 1);
            vTaskDelay(pdMS_TO_TICKS(50));
        }
        ESP_LOGE(TAG, "U%d RDM: 驱动重装最终失败，本宇宙 DMX 输出已停", universe + 1);
        return false;
    }
    return true;
}

int dmx_port_of(uint8_t universe)
{
    if (universe >= DMX_UNIVERSES || !s_out[universe].ok) return -1;
    return (int)s_out[universe].port;
}

bool dmx_port_ready(uint8_t universe)
{
    return universe < DMX_UNIVERSES && s_out[universe].ok;
}

void dmx_get_stats(uint8_t universe, uint32_t *frames, uint32_t *fails,
                   uint8_t *ok, uint8_t *fps)
{
    if (universe >= DMX_UNIVERSES) {
        if (frames) *frames = 0;
        if (fails)  *fails = 0;
        if (ok)     *ok = 0;
        if (fps)    *fps = 0;
        return;
    }
    if (frames) *frames = s_frames[universe];
    if (fails)  *fails  = s_fails[universe];
    if (ok)     *ok     = s_last_ok[universe] ? 1 : 0;
    if (fps)    *fps    = s_fps[universe];
}

// ==================== 收发方向控制（交给 esp_dmx 的 RTS）====================
// RS-485 半双工：同一时刻只能有一端驱动总线，DE/RE 必须成对切换。
//
// **本版起方向由 esp_dmx 管**（关键在 dmx_install_driver 里 `rts = o->en_pin`）：
// 组件在它自己管的收发路径上写 UART 的 RTS 寄存器（`conf0.sw_rts`），而 RTS
// 信号已经被路由到 DE 脚，于是换向由组件完成：
//
//   io.c:389  dmx_send_num()      → set_rts(0)  发送（"Turn the DMX bus around"）
//   io.c:150  dmx_receive_num()   → set_rts(1)  接收
//   io.c:87   dmx_write_offset()  → set_rts(0)  发送
//   driver.c:241 dmx_driver_install() → set_rts(1)  空闲/接收
//   uart.c:319 RDM 等应答          → set_rts(1)  接收
//
// ⚠ 四个必须记住的点（都是踩过的）：
//  1) **必须把 rts 引脚绑上去**（dmx_set_pin 的第 4 个参数）。不绑的话上面那些
//     set_rts() 只是写寄存器、引脚纹丝不动 —— 老版本传 `rts = -1` 再自己拨 GPIO,
//     这条路径整个是空的。
//  2) 组件**从不反转 RTS**（全文只有 TXD 反转，用于 inverted-DMX），也**从不调用
//     `uart_set_mode(UART_MODE_RS485_HALF_DUPLEX)`** ⇒ ESP-IDF 不会按字节自动翻转，
//     换向完全靠上面那几个显式 set_rts()。
//  3) **发完一帧组件不会主动放开总线**：只有"要收"的路径才切回接收。对本工程
//     （两个宇宙各一片 485、各自独占一条总线）没问题；但**两个宇宙若共用一条总线，
//     就是两个驱动源长期互打** —— 那是接线问题，软件救不了，必须各自一条线。
//  4) 驱动装上之后，`gpio_set_level()` 对 en_pin **不再起作用**
//     （它只写 GPIO_OUT_REG，改不了 GPIO_FUNCx_OUT_SEL_CFG 里的输出源选择）。
//     所以下面三个函数只在**驱动装上之前**的那个窗口里有用。
//
// 下面三个函数就是给那个窗口用的：复位后到驱动装好之间，DI 还是浮空的，
// 若此时 DE 已经为高，SP3485 就会拿随机电平驱动 A/B 线（接收端可能锁存乱码帧）。
// 所以 dmx_start() 先把方向脚摆成"不驱动"。

/** 切到发送（**仅驱动装上之前有效**）：EN = 发送态电平 → 驱动总线。 */
static inline void dmx_transceiver_tx(const dmx_out_t *o)
{
    if (o->en_pin >= 0) gpio_set_level((gpio_num_t)o->en_pin, o->en_tx_level);
}

/**
 * 切到接收 = **放开总线**（**仅驱动装上之前有效**）。
 *
 * 驱动装上之后由 esp_dmx 通过 RTS 管；这里保留是因为上电窗口内 en_pin 还是
 * 普通 GPIO，必须显式摆成"不驱动"，否则见上面第 4 条。
 */
static inline void dmx_transceiver_rx(const dmx_out_t *o)
{
    if (o->en_pin >= 0) gpio_set_level((gpio_num_t)o->en_pin, o->en_rx_level);
}

/** 上电窗口的默认态：不驱动总线。（装上驱动后由 RTS 决定，见文件头说明。） */
static inline void dmx_transceiver_idle(const dmx_out_t *o)
{
    dmx_transceiver_rx(o);
}

/**
 * RDM 结束后，把被 UART 外设"借走"的 **RX** 引脚收回来。
 *
 * ⚠ 为什么 RX 仍然必须收回：dmx_rdm_mode(true) 用 dmx_set_pin(port, tx, rx, en_pin)
 *   把 RX 绑成了 UART RX；而回程的重装走 dmx_install_driver()，在 DMX_RX_ENABLE=0
 *   时它传 rx = -1 —— 而 IDF 的 uart_set_pin() 只在「新引脚 >= 0」时才释放旧引脚
 *   （uart.c: `uart_release_pin(uart, tx>=0, rx>=0, rts>=0, cts>=0)`），于是那一路
 *   release=false、**根本不会被释放**；dmx_uart_deinit() 也只做
 *   periph_module_disable()，不碰引脚。
 *
 * ⚠ **EN 不再需要收回**：本版起 dmx_install_driver() 恒传 `rts = en_pin`，
 *   每次重装都会重新绑定（≥ 0 就会被 release + 重新 route），方向继续由组件的
 *   set_rts() 管。以前"把 EN 强行改回普通 GPIO"的做法，在组件管方向之后反而会
 *   把 RTS 路由拆掉（gpio_set_direction 会把输出源改回 SIG_GPIO_OUT_IDX），
 *   所以删掉了。
 */
static void dmx_pins_reclaim(const dmx_out_t *o)
{
#if !DMX_RX_ENABLE
    if (o->rx_pin >= 0) {
        // 本意是"接收通路整个不存在"（见 dmx_install_driver 的说明）。
        // gpio_reset_pin() 内部会 gpio_input_disable()，即切断焊盘到 GPIO 矩阵的
        // 输入通路 —— 这正是 IDF 自己在 uart_release_pin() 里释放 RX 引脚所做的
        // 同一件事。顺带使能上拉，避免 SP3485 的 RO 高阻时悬空拾噪
        // （否则 RX 溢出 / 伪 break 中断又会回来）。
        gpio_reset_pin((gpio_num_t)o->rx_pin);
    }
#else
    (void)o;
#endif
}

static bool dmx_install_driver(dmx_out_t *o)
{
    dmx_config_t cfg = DMX_CONFIG_DEFAULT;
    dmx_personality_t pers[] = { {512, "DMX512"} };
    if (!dmx_driver_install(o->port, &cfg, pers, 1)) {
        ESP_LOGE(TAG, "install FAILED (port %d, TX=%d RX=%d)",
                 (int)o->port, o->tx_pin, o->rx_pin);
        return false;
    }
    // 引脚绑定：TX → UART TX，RO → UART RX（可选），**DE → UART RTS**。
    //
    // ⚠ 第 4 个参数（rts）是**方向的接管点**，本版起恒传 en_pin：
    //   esp_dmx 内部用 dmx_uart_set_rts() 写 `conf0.sw_rts` 来换向
    //   （send→0 发送 / receive→1 接收，见文件头），而这条 RTS 信号必须
    //   先被 route 到 DE 脚才会起作用。传 -1 时那几个 set_rts() 只是写寄存器，
    //   引脚纹丝不动 —— 老版本"自己拨 GPIO"就是被这一点绕过去的。
    //
    // ⚠ DMX_RX_ENABLE=0 时 rx 传 -1：**不绑定 RX 引脚**，这样 UART 的接收通路
    //   整个不存在，也就不可能产生 RX 溢出/伪 break 中断（RO 的物理连接不受影响，
    //   只是固件平时不去听；RDM 扫描期间由 dmx_rdm_mode 临时绑上）。
    const int rx = DMX_RX_ENABLE ? o->rx_pin : -1;
    if (!dmx_set_pin(o->port, o->tx_pin, rx, o->en_pin)) {
        ESP_LOGE(TAG, "dmx_set_pin FAILED (port %d, TX=%d RX=%d RTS=%d)",
                 (int)o->port, o->tx_pin, rx, o->en_pin);
        return false;
    }
    return true;
}

static void dmx_task(void *arg);
static void dmx_telemetry_task(void *arg);

/**
 * 在 **core 1** 上安装所有口的驱动 —— 关键点：esp_intr_alloc() 把中断路由到
 * 「调用它的那个核」（esp_hw_support/intr_alloc.c: cpu = esp_cpu_get_core_id()），
 * 所以谁 install，DMX 的 UART/GPTimer 中断就落在谁身上。
 * 以前 dmx_start() 由 app_main 调用（core 0）→ DMX 中断跟 BLE/Wi-Fi 抢同一个核，
 * 而 DMX 最怕的恰恰是 Break/MAB 那段的中断延迟。放到 core 1 后与输出任务同核，
 * 彻底远离射频。ISR 放 IRAM(CONFIG_DMX_ISR_IN_IRAM) 与钉核是互补的两件事：
 * 前者保证 cache 停顿时还能执行，后者保证不被射频排队。
 */
static void dmx_init_task(void *arg)
{
    int installed = 0;
    for (int u = 0; u < DMX_UNIVERSES; u++) {
        s_out[u].ok = dmx_install_driver(&s_out[u]);
        if (s_out[u].ok) installed++;
    }
    ESP_LOGI(TAG, "drivers installed: %d/%d on CPU%d (ISR follows this core)",
             installed, DMX_UNIVERSES, xPortGetCoreID());
    esp_intr_dump(stdout);   // 启动时打印一次中断表，确认 UART1/UART2 都在 CPU1

    for (int u = 0; u < DMX_UNIVERSES; u++) {        if (!s_out[u].ok) continue;
        char name[8];
        snprintf(name, sizeof(name), "dmx%d", u);
        xTaskCreatePinnedToCore(dmx_task, name, 4096, &s_out[u], 6, NULL, 1);
    }
    BaseType_t tr = xTaskCreatePinnedToCore(dmx_telemetry_task, "dmx_stat", 4096, NULL, 3, NULL, 0);
    ESP_LOGI(TAG, "task create: telemetry=%s heap=%u",
             tr == pdPASS ? "ok" : "FAILED", (unsigned)esp_get_free_heap_size());
    vTaskDelete(NULL);
}

static void dmx_task(void *arg)
{
    dmx_out_t *o = (dmx_out_t *)arg;
    const uint8_t u = o->universe;
    ESP_LOGI(TAG, "DMX TX start: U%d port=%d TX=%d RX=%d EN=%d baud=%lu core=%d",
             u + 1, (int)o->port, o->tx_pin, o->rx_pin, o->en_pin,
             dmx_get_baud_rate(o->port), xPortGetCoreID());
    while (1) {
        // RDM 独占期：停发 DMX，并回一个应答让 dmx_output_pause() 能继续。
        // 必须停在这里（一帧的边界）而不是发送中途 —— 中途停会把驱动留在
        // DMX_STATUS_SENDING，之后的 dmx_send 会一直返回 0。
        if (s_paused[u]) {
            // 方向交给 esp_dmx/RDM 路径了：暂停期间 dmx_rdm_mode() 会把 DE 绑成
            // UART RTS 并按收发自动切换（见文件头"收发方向控制"）。这里手动拨
            // gpio_set_level() 是**无效**的，所以不拨。
            if (s_pause_ack[u]) xSemaphoreGive(s_pause_ack[u]);
            while (s_paused[u]) {
                // RDM 结束后的整口重装 —— **必须在本任务（core 1）里做**。
                // ⚠ 这就是"RDM 扫描后 DMX 永久不工作、只能重启恢复"的根因：
                //   原先这段重装在 rdm_task(core 0) 里执行，而 esp_intr_alloc()
                //   把中断路由到调用核 → DMX 的 UART/GPTimer 中断被搬到 core 0
                //   去和 BLE/Wi-Fi 抢 → dmx_wait_sent() 永远超时 →
                //   dmx_send() 永远返回 0 → fail 以帧率持续增长。
                if (s_reinstall_req[u]) {
                    if (dmx_driver_is_installed(o->port)) {
                        dmx_driver_delete(o->port);
                    }
                    bool okr = dmx_install_driver(o);
#if DMX_RECLAIM_PINS
                    dmx_pins_reclaim(o);   // ← 收回被 UART 借走的 RX，见函数说明
#else
                    ESP_LOGW(TAG, "U%d RDM: 【A/B 对照】跳过引脚收回", u + 1);
#endif
                    // 方向**不用**在这里拨：重装走 dmx_install_driver()，它把 DE
                    // 重新绑成 UART RTS，而组件在 install 内部已经 set_rts(1)
                    // （接收/放开总线）—— 见文件头"收发方向控制"。
                    s_out[u].ok = okr;
                    s_reinstall_req[u] = false;
                    ESP_LOGW(TAG, "U%d RDM: 驱动已在 CPU%d 重装 ok=%d",
                             u + 1, xPortGetCoreID(), (int)okr);
                    if (s_reinstall_ack[u]) xSemaphoreGive(s_reinstall_ack[u]);
                }
                vTaskDelay(pdMS_TO_TICKS(5));
            }
            continue;
        }
        // ⚠ 方向不在这里拨了：DE 已绑成 UART RTS，换向由 esp_dmx 在它自己的
        //   收发路径里 set_rts() 完成（dmx_send → 发送）。手动 gpio_set_level()
        //   此时对 en_pin 是**无效**的（只写 GPIO_OUT_REG，改不了输出源选择）。
        dmx_state_copy_port_frame(o->frame, u);
        dmx_write(o->port, o->frame, DMX_PACKET_SIZE);
        size_t n = dmx_send(o->port);
        // 发送被拒(n==0)时不等待，直接进入下一轮；正常帧最多等 60ms
        bool ok = (n > 0) && dmx_wait_sent(o->port, pdMS_TO_TICKS(DMX_FRAME_WAIT_MS));
        // ⚠ 发完**不主动放开总线**：esp_dmx 只在"要收"的路径里才切回接收
        //   （见文件头第 3 条）。所以空闲时 DE 保持发送态 —— 对"每个宇宙独占
        //   一条总线"是对的；若两个宇宙的 A/B 接在同一条线上，就是两个驱动源
        //   长期互打，**必须改接线**，软件救不了。
        s_last_ok[u] = ok;
        s_frames[u]++;
        if (!ok) s_fails[u]++;
        // ⚠ 这里绝对不能 printf：控制台在 USB-Serial/JTAG 上，主机不读时会阻塞，
        //   一旦阻塞就把 DMX 输出任务卡住。所有打印移交 dmx_telemetry_task。
        //
        // ⚠ vTaskDelay(1) 不是可有可无的：一帧 = 513×11bit/250k = 22.57ms，
        //   加 break(176µs)+MAB(12µs) ≈ 22.76ms ≈ 43.9fps，已经贴住 DMX512-A 的
        //   44Hz 上限。这 1ms（1000Hz tick）把实际刷新率压到 ~42fps —— **删掉它
        //   会让刷新率越过上限**。要改必须先算清时序预算。
        vTaskDelay(1);
    }
}

/** 遥测：独立低优先级任务跑在 core 0，每 5 秒打一行，绝不阻塞 DMX 输出。 */
static void dmx_telemetry_task(void *arg)
{
    static uint8_t ch[DMX_CHANNELS];
    uint32_t last[DMX_UNIVERSES] = {0};
    ESP_LOGI(TAG, "telemetry task started on CPU%d", xPortGetCoreID());
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(5000));
        dmx_state_snapshot(ch);
        for (int u = 0; u < DMX_UNIVERSES; u++) {
            if (!s_out[u].ok) continue;
            uint32_t f = s_frames[u], bad = s_fails[u];
            uint32_t fps = (f - last[u]) / 5;
            last[u] = f;
            s_fps[u] = (uint8_t)(fps > 255 ? 255 : fps);
            uint16_t off = (uint16_t)u * DMX_UNIVERSE_SIZE;

            // ---- 数据窗口 ----
            // 以前这里只看"该宇宙末端 16 个通道"（U1 = ch497..512、U2 = ch1009..1024），
            // 而灯具**绝大多数配在低地址** —— 于是这块遥测在回答"推子到底有没有写进去"
            // 时永远是 0，只会误导（实测踩过：模拟器报 非零=10，这里却写 nz16=0）。
            // 现在改成看整个宇宙：非零总数 + 第一个非零通道号 + **开头 8 个通道的值**。
            // 开销可忽略：512 次比较，每 5 秒一次，而且跑在 core 0 的遥测任务里，
            // 不在 DMX 输出任务上。
            int nz = 0;
            int first_nz = 0;                       // 0 = 全零（用 1-based 全局通道号）
            for (int i = 0; i < DMX_UNIVERSE_SIZE; i++) {
                if (ch[off + i]) {
                    nz++;
                    if (first_nz == 0) first_nz = off + i + 1;
                }
            }
            char firstbuf[10];
            if (first_nz) snprintf(firstbuf, sizeof(firstbuf), "ch%d", first_nz);
            else          snprintf(firstbuf, sizeof(firstbuf), "--");

            ESP_LOGI(TAG,
                     "U%d frm=%u fps=%u ok=%d fail=%u | 非零=%d 首个=%s | ch%d..%d=%d %d %d %d %d %d %d %d",
                     u + 1, (unsigned)f, (unsigned)fps, (int)s_last_ok[u], (unsigned)bad,
                     nz, firstbuf, off + 1, off + 8,
                     ch[off], ch[off+1], ch[off+2], ch[off+3],
                     ch[off+4], ch[off+5], ch[off+6], ch[off+7]);
        }
    }
}

void dmx_start(void)
{
    // ⚠ 顺序要紧。上电到 dmx_set_pin() 生效之间的毫秒级窗口内，DI 是浮空的；
    //   如果这时 DE 已经为高，SP3485 就会用随机电平驱动 A/B 线，接收端可能锁存
    //   乱码帧。所以：
    //     1) 先把两个 TX 引脚按普通 GPIO 驱动到空闲高电平（DMX 空闲态），
    //     2) 再把 DE/RE 配成输出并置于**不驱动总线**的接收态，
    //     3) 最后才让 dmx_init_task 去装驱动、绑引脚。
    //   这样从复位开始总线就没被本机驱动过，直到真正开始发第一帧。
    //   （dmx_set_pin() 随后会把 TX/RX 引脚切给 UART 外设，与这里的 GPIO 设置不冲突。）
    gpio_set_direction((gpio_num_t)DMX_TX_PIN,   GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)DMX_TX_PIN,  1);
    gpio_set_direction((gpio_num_t)DMX_TX2_PIN,  GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)DMX_TX2_PIN, 1);

    // DE/RE：每个宇宙一对，**在驱动装上之前**先摆成"接收态"（不驱动总线）。
    // 驱动装上之后这两个脚由 UART RTS 接管，方向交给 esp_dmx（见文件头）。
    // v6 及以前这里是把唯一一个共用 EN 常驻拉高 —— 收发器永远在发送态，
    // 既收不到东西，也一直占着总线。现在两个宇宙各自独立、且装上后不再手动拨。
    for (int u = 0; u < DMX_UNIVERSES; u++) {
        const dmx_out_t *o = &s_out[u];
        if (o->en_pin >= 0) {
            gpio_set_direction((gpio_num_t)o->en_pin, GPIO_MODE_OUTPUT);
        }
        dmx_transceiver_idle(o);
    }

    // 用一次性任务在 core 1 上完成安装，让所有 DMX 中断都落在 core 1
    xTaskCreatePinnedToCore(dmx_init_task, "dmx_init", 4096, NULL, 5, NULL, 1);
}


