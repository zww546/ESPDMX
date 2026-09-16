#include "dmx.h"
#include "dmx_state.h"
#include "pins.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "driver/uart.h"     // 环回自检 / 接收计数（诊断用，不接管驱动的收发）
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

static volatile uint32_t s_frames[DMX_UNIVERSES];
static volatile uint32_t s_fails[DMX_UNIVERSES];
static volatile bool     s_last_ok[DMX_UNIVERSES];
// 接收诊断：UART RX FIFO 里当前堆积的字节数（只读，不消费 —— 见下方说明）。
static volatile int      s_rx_pending[DMX_UNIVERSES];
// 环回自检期间暂停发送（避免自检数据与 DMX 帧在总线上打架）。
static volatile bool     s_tx_paused = false;

// ==================== 收发方向控制 ====================
// RS-485 半双工：同一时刻只能有一端驱动总线，DE/RE 必须成对切换。
//
// ⚠ 为什么不用 esp_dmx 的 rts_pin 来自动控制方向：
//   1) 该组件只在 RDM「等待响应」那一个分支里调过 dmx_uart_set_rts()（见
//      esp_dmx/src/dmx/hal/uart.c:319），发送/接收路径上并不翻转；
//   2) ESP-IDF 只在 UART_MODE_RS485_HALF_DUPLEX 下才自动翻转 RTS，
//      而 esp_dmx 从未调用 uart_set_mode()。
//   所以 rts_pin 传下去**不会**自动换向，方向必须由我们自己驱动。
//   现在两个脚各一个 GPIO，严格互斥成对写（见下面的 tx/rx 两个函数）。
//
// 方向控制说明：
//   本工程用的 485 模块只有**一个 EN 脚**（DE 与 /RE 在模块内部处理），
//   所以 dmx_transceiver_{tx,rx}() 写的是同一个 GPIO 的两个电平。
//   为什么不用 esp_dmx 的 rts_pin 自动换向：
//   1) 该组件只在 RDM「等待响应」那一个分支里调过 dmx_uart_set_rts()（见
//      esp_dmx/src/dmx/hal/uart.c:319），发送/接收路径上并不翻转；
//   2) ESP-IDF 只在 UART_MODE_RS485_HALF_DUPLEX 下才自动翻转 RTS，
//      而 esp_dmx 从未调用 uart_set_mode()。
//   所以 rts_pin 传下去**不会**自动换向，方向必须由我们自己驱动。
//   将来若换成 DE/RE 分开的 6 脚模块，把结构体里 en_pin 拆成两个并各写一行即可。

/** 切到发送：EN = 发送态电平 → 模块驱动总线（并关掉自己的接收，避免自收回波）。 */
static inline void dmx_transceiver_tx(const dmx_out_t *o)
{
    if (o->en_pin >= 0) gpio_set_level((gpio_num_t)o->en_pin, o->en_tx_level);
}

/** 切到接收：EN = 接收态电平 → 模块不驱动总线、使能接收。 */
static inline void dmx_transceiver_rx(const dmx_out_t *o)
{
    if (o->en_pin >= 0) gpio_set_level((gpio_num_t)o->en_pin, o->en_rx_level);
}

/** 上电/停机默认态：不驱动总线，接收器待命。 */
static inline void dmx_transceiver_idle(const dmx_out_t *o)
{
    dmx_transceiver_rx(o);
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
    // v7：把 RO 也绑定到 UART RX（v6 传 -1，接收端是关的）。
    // rts 仍传 -1 —— 方向不用硬件 RTS，由 dmx_transceiver_* 直接驱动 GPIO（原因见上）。
    if (!dmx_set_pin(o->port, o->tx_pin, o->rx_pin, -1)) {
        ESP_LOGE(TAG, "dmx_set_pin FAILED (port %d, TX=%d RX=%d)",
                 (int)o->port, o->tx_pin, o->rx_pin);
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
        if (s_tx_paused) { vTaskDelay(pdMS_TO_TICKS(5)); continue; }  // 环回自检期间让路
        // 半双工：驱动总线前先切到发送态（DE=1, /RE=1）。
        dmx_transceiver_tx(o);
        dmx_state_copy_port_frame(o->frame, u);
        dmx_write(o->port, o->frame, DMX_PACKET_SIZE);
        size_t n = dmx_send(o->port);
        // 发送被拒(n==0)时不等待，直接进入下一轮；正常帧最多等 60ms
        bool ok = (n > 0) && dmx_wait_sent(o->port, pdMS_TO_TICKS(DMX_FRAME_WAIT_MS));
        // 发完立刻释放总线，否则会一直占着 A/B 线，别的控台/设备无法通信。
        dmx_transceiver_rx(o);
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
            // 接收诊断：只读 FIFO 水位，**不消费数据**。
            // esp_dmx 从不读 RX FIFO（只在收 RDM 响应时 reset 它），所以这里读水位
            // 不会与驱动抢数据。rx=0 一直不变 ⇒ 接收通路（RO→RX 或模块 EN）有问题；
            // 若总线对端在发 DMX 而这里 rx 持续增长 ⇒ 接收通路是活的。
            size_t pending = 0;
            if (uart_get_buffered_data_len(s_out[u].port, &pending) == ESP_OK) {
                s_rx_pending[u] = (int)pending;
            }
            uint32_t f = s_frames[u], bad = s_fails[u];
            uint32_t fps = (f - last[u]) / 5;
            last[u] = f;
            uint16_t off = (uint16_t)u * DMX_UNIVERSE_SIZE;
            // 取该宇宙末端的最后 16 个通道（避免超出 1024 缓冲：u=1 时 off=1024）
            uint16_t base = (uint16_t)(off + DMX_UNIVERSE_SIZE);
            if (base > DMX_CHANNELS) base = DMX_CHANNELS;
            uint16_t start16 = (uint16_t)(base - 16);
            int nz = 0; for (int i = 0; i < 16; i++) if (ch[start16 + i]) nz++;
            ESP_LOGI(TAG, "U%d frm=%u fps=%u ok=%d fail=%u rx=%d nz16=%d ch%d..%d=%d %d %d %d %d %d %d %d",
                     u + 1, (unsigned)f, (unsigned)fps, (int)s_last_ok[u], (unsigned)bad,
                     s_rx_pending[u], nz,
                     start16 + 1, start16 + 16,
                     ch[start16], ch[start16+1], ch[start16+2], ch[start16+3],
                     ch[start16+4], ch[start16+5], ch[start16+6], ch[start16+7]);
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

    // DE/RE：每个宇宙一对，初始为"接收态"（DE=0 释放总线，/RE=0 使能接收）。
    // v6 及以前这里是把唯一一个共用 EN 常驻拉高 —— 收发器永远在发送态，
    // 既收不到东西，也一直占着总线。现在两个宇宙各自独立控制。
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

/**
 * 环回自检（单次尝试，指定发送态电平）。
 *
 * 把 EN 设为 tx_level 后往总线补发 8 个已知字节，再数 RX FIFO 回读了多少、
 * 内容是否一致。用 IDF 的 uart_write_bytes/uart_read_bytes 直连 UART ——
 * 只做字节收发，不碰 esp_dmx 的帧状态机。
 *
 * @param tx_level 本次尝试把 EN 当作"发送态"的电平
 * @param out_got 回读到的字节（至少 8 字节），便于调用方打印
 * @return 8 字节全部正确回来
 */
static bool loopback_try(dmx_out_t *o, int tx_level, uint8_t *out_got, int *out_n, int *out_match)
{
    static const uint8_t pattern[] = { 0xAA, 0x55, 0x00, 0xFF, 0x12, 0x34, 0x56, 0x78 };
    memset(out_got, 0, sizeof(pattern));

    // 清 FIFO，切到"接收"（与本次尝试的 tx_level 相反）
    uart_flush_input(o->port);
    gpio_set_level((gpio_num_t)o->en_pin, tx_level ? 0 : 1);
    vTaskDelay(pdMS_TO_TICKS(2));

    // 用本次假设的发送态把数据送出去
    gpio_set_level((gpio_num_t)o->en_pin, tx_level);
    uart_write_bytes(o->port, pattern, sizeof(pattern));
    uart_wait_tx_done(o->port, pdMS_TO_TICKS(100));
    // 立刻切回接收等回波
    gpio_set_level((gpio_num_t)o->en_pin, tx_level ? 0 : 1);
    vTaskDelay(pdMS_TO_TICKS(30));   // 8 字节 @250k 8N2 ≈ 0.35ms，给足余量

    size_t pending = 0;
    uart_get_buffered_data_len(o->port, &pending);
    int n = 0;
    if (pending > 0) n = (int)uart_read_bytes(o->port, out_got, sizeof(pattern), pdMS_TO_TICKS(50));

    int match = 0;
    for (int i = 0; i < n && i < (int)sizeof(pattern); i++) {
        if (out_got[i] == pattern[i]) match++;
    }
    *out_n = n;
    *out_match = match;
    return (n == (int)sizeof(pattern)) && (match == (int)sizeof(pattern));
}

/**
 * 环回自检 —— 确认接收通路通不通，并**自动判定 EN 的极性**。
 *
 * 背景：单 EN 的 485 模块有多种内部接法，肉眼看不出来：
 *   (a) EN = /RE（接收使能，低有效），驱动使能内部常开
 *   (b) EN = DE（驱动使能，高有效），接收内部常开 → EN 低时常驻接收
 *   (c) EN 同时并到 DE 与 /RE
 *   (d) 模块内部自动方向（靠 TXD 活动检测），EN 是别的用途
 * 极性反了的表现是"完全收不到"，所以这里**两种极性都试**，并报告哪一种通过。
 *
 * ⚠ 这是**离线自检**：期间暂停 DMX 发送（s_tx_paused），需要 A/B 回路。
 *   测法一（最简单）：把本模块的 A/B 短接（可经 120Ω 电阻），应回收到全部字节。
 *   测法二：接上真实控台总线，此时只判字节数（内容取决于总线上其他设备）。
 */
bool dmx_loopback_selftest(uint8_t universe)
{
    if (universe >= DMX_UNIVERSES || !s_out[universe].ok) {
        ESP_LOGE(TAG, "loopback: universe %u 未就绪", universe);
        return false;
    }
    dmx_out_t *o = &s_out[universe];

    s_tx_paused = true;
    vTaskDelay(pdMS_TO_TICKS(60));   // 等输出任务让路（它在 5ms 一轮地轮询这个标志）

    uint8_t got[8];
    int n = 0, match = 0;
    bool pass = false;
    int used_tx_level = o->en_tx_level;

    // 先按当前配置的极性试；失败则试反极性，并报告哪一种对
    if (!loopback_try(o, o->en_tx_level, got, &n, &match)) {
        int n2 = 0, m2 = 0;
        uint8_t got2[8];
        int alt = o->en_tx_level ? 0 : 1;
        if (loopback_try(o, alt, got2, &n2, &m2)) {
            pass = true;
            used_tx_level = alt;
            for (int i = 0; i < 8; i++) got[i] = got2[i];
            n = n2; match = m2;
            ESP_LOGW(TAG, "loopback U%u: 当前 EN 极性反了！应把 en_tx_level 改成 %d（en_rx_level 改成 %d）",
                     universe + 1, alt, alt ? 0 : 1);
        }
    } else {
        pass = true;
    }

    ESP_LOGI(TAG, "loopback U%u: tx_level=%d got=%d match=%d/8 -> %s  (hex: %02X %02X %02X %02X %02X %02X %02X %02X)",
             universe + 1, used_tx_level, n, match, pass ? "PASS" : "FAIL",
             got[0], got[1], got[2], got[3], got[4], got[5], got[6], got[7]);
    if (!pass) {
        ESP_LOGW(TAG, "loopback U%u 未收到回波（两种极性都试过）：检查 A/B 是否短接回路、"
                      "RO 是否接到 GPIO%d、EN 是否接到 GPIO%d、以及 A/B 有没有接反",
                 universe + 1, o->rx_pin, o->en_pin);
    }

    // 恢复：切回配置的发送态并放行输出任务
    dmx_transceiver_tx(o);
    s_tx_paused = false;
    return pass;
}
