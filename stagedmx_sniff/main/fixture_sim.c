/**
 * StageDMX 灯具模拟器（Fixture Simulator）
 * ============================================================================
 * 把第二块 ESP32-S3 当成**一台真的 DMX 灯具**，挂在控台（stagedmx_std）的
 * UART 线上，用来做联合调试：
 *
 *   1. **收帧**：像灯具一样按"起始地址 + 占用通道数"解析自己那一段 DMX，
 *      把通道值打出来 —— 验证 App 推子 / 效果 / 程序到底有没有正确落到总线上。
 *   2. **RDM 应答**：作为 RDM Responder 被控台发现（DISC_UNIQUE_BRANCH），
 *      支持 GET DEVICE_INFO / 各种 LABEL / DMX_START_ADDRESS / DMX_PERSONALITY，
 *      支持 SET DMX_START_ADDRESS（App 里改址）与 SET IDENTIFY_DEVICE（识别）。
 *      —— 没有真实灯具时也能把 App 的 RDM 扫描 / 改址 / 识别整条链路跑通。
 *
 * ## 与"分析仪"的关系
 * 原 sniff.c 是**纯旁听**（TX/RTS 都传 -1，只听不说）。本文件是它的超集：
 * 仍然统计真实帧率 / start code / 错帧（写 DMX 时最该看的三个数），
 * 同时把"说"的能力打开，变成一台会应答的灯。
 *
 * ## 接线（TTL 直连，不走差分收发器）
 *
 *     控台 stagedmx_std            本板 stagedmx_sniff
 *     U1 TX  GPIO17  ────────────→  GPIO2  (SIM_RX_PIN)
 *     U1 RX  GPIO18  ←────────────  GPIO1  (SIM_TX_PIN)
 *     GND            ─────────────  GND      ← **必须共地**
 *
 *   · 两边都是 TTL 电平，无收发器 ⇒ **不需要方向脚**，SIM_RTS_PIN = -1。
 *   · 控台侧 U1 的 17/18 同时也接着它自己的 SP3485（DI / RO）。发送时
 *     RO 是高阻（不影响）；但控台切到 RDM 接收态时 RO 会驱动 GPIO18，
 *     与本板 GPIO1 形成**推挽争用**。ESP32 GPIO 驱动（~20Ω）明显强于
 *     SP3485 的 RO（~100Ω 以上），实测能压过去，但请知悉：
 *       - 若 RDM 应答不稳定，优先怀疑这里；
 *       - 最干净的做法是给本板也配一片 SP3485，走真正的 A/B 差分总线
 *         （那时把 RTS 指向 EN 脚即可，驱动会自动换向）。
 *
 *   · ⚠ 不要用 GPIO0 / GPIO3 / GPIO45 / GPIO46 / GPIO19 / GPIO20（strapping
 *     或原生 USB）。GPIO1/GPIO2 是安全的。
 *
 * ## 控台侧要做什么
 * 控台默认 `DMX_RX_ENABLE=0`（不绑 RX 引脚）。RDM 扫描时 `dmx_rdm_mode()`
 * 会临时把 RX 绑上、并把 EN 交给 RTS 自动换向 —— 与直连的 TTL 拓扑兼容。
 * 也就是说：**App 上点一次"RDM 扫描"就能看见这台模拟灯**，无需改控台固件。
 */
#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"

#include "esp_dmx.h"
#include "rdm/include/driver.h"   // rdm_uid_get() —— esp_dmx.h 只带 types.h
#include "rdm/responder.h"

static const char *TAG = "fixture";

// ============================================================================
//                                配置
// ============================================================================

// ---- 引脚 ----
//   SIM_TX_PIN → 控台 U1 RX (GPIO18)
//   SIM_RX_PIN ← 控台 U1 TX (GPIO17)
#define SIM_TX_PIN          1
#define SIM_RX_PIN          2
// TTL 直连没有收发器 ⇒ 不需要方向脚。
// 若改用 A/B 差分（本板加一片 SP3485），把这里改成该模块的 EN/DE 脚。
#define SIM_RTS_PIN         -1

#define SIM_PORT            DMX_NUM_1

// 1 = 灯具模拟器（收帧 + RDM 应答）
// 0 = 纯旁听（等同旧 sniff.c 的分析仪行为：只听不说）
#define SIM_RDM_ENABLE      1

// ---- 模拟灯具的身份 ----
#define SIM_START_ADDR      1       // 出厂默认地址（仅当 NVS 里没存过时才生效；
                                    // 控台改过址后以 NVS 为准，重启不丢）
#define SIM_FOOTPRINT       16      // 占用通道数（模拟一台 16 通道灯）
#define SIM_MODEL_ID        0x0001
#define SIM_MODEL_NAME      "StageDMX Sim 16ch"
#define SIM_DEVICE_LABEL    "StageDMX-SIM"
#define SIM_MANUFACTURER    "StageDMX"
#define SIM_SW_LABEL        "StageDMX Fixture Sim v1.0"

// ---- 识别（IDENTIFY）时点亮的 LED ----
// 多数 S3 开发板没有普通 GPIO LED，默认关闭，只打日志。
// 有灯的话填引脚号（例如板载普通 LED），识别时它会闪烁。
#define SIM_LED_PIN         -1

// ---- 可选：监视一个引脚的实际电平（用来验证控台的方向脚 EN）----
// 把控台 U1 的 EN（GPIO4）接到本板这个脚上，状态行就会多出一段
//   「GPIOw(监视): 跳变=N 高=M%」
// 这是在本测试台上**唯一**能观察到 EN 的办法 —— TTL 直连时收发器不在回路里，
// 光看 DMX 收帧是看不出方向脚死活的。
// 填 -1 关闭监视。
//
// ⚠ 注意：光看 EN 的静态电平**不足以**验证"EN 收回"那个修复。
//   DMX_RX_ENABLE=0 时 EN 恒为发送态，修与不修读数一样（原因见 dmx.c 的
//   dmx_pins_reclaim 说明：那片区域的 periph_module_reset 恰好把 sw_rts 清零）。
//   要真正区分，必须让控台在 RDM 之后**主动把 EN 拉低**（例如临时打开
//   DMX_RX_ENABLE，dmx_transceiver_rx() 就会在每帧后拉低 EN）—— 那时
//   修好的版本会看到 ~41Hz 的跳变，没修的版本则纹丝不动。
// ⚠ GPIO3 在 ESP32-S3 上是 strapping 脚（JTAG 源选择），复位瞬间会被采样。
//   这里只在**启动之后**才把它配成输入+下拉，所以正常上电不受影响；
//   但每次复位时那个下拉也会参与 strapping 采样 —— 若出现复位后行为异常，
//   优先怀疑这里，换回 GPIO4/GPIO5 等非 strapping 脚。
#define SIM_WATCH_PIN       3

// ---- 上报 ----
#define SIM_REPORT_MS       1000    // 状态行间隔
#define SIM_LINK_TIMEOUT_MS 1500    // 超过这么久没帧就判定"信号丢失"
#define SIM_LINK_WARN_S     5       // 无信号时每隔多少秒重报一次（别只报一次）

// ============================================================================
//                                状态
// ============================================================================

typedef struct {
    volatile uint32_t frames;       // 收到的 DMX 帧总数
    volatile uint32_t bad_sc;       // start code != 0x00 的帧
    volatile uint32_t errs;         // 接收错误帧
    // 按错误类型细分 —— 这是区分"没接线/悬空拾噪"和"接了但格式不对"的关键
    volatile uint32_t err_overflow; // UART 溢出（收不过来 / 噪声刷屏）
    volatile uint32_t err_slot;     // 帧格式错 framing（波特率不对 / 电平不像样）
    volatile uint32_t err_short;    // 槽位不足（是真帧但比预期短）
    volatile uint32_t err_other;
    volatile uint32_t last_sc;
    volatile uint32_t last_size;
    volatile uint32_t last_nonzero;
    volatile int64_t  last_rx_us;   // 最后一帧的时间戳
} sim_stat_t;

static sim_stat_t s_st;

static volatile uint16_t s_start_addr = SIM_START_ADDR;   // RDM 可改
static volatile bool     s_identify   = false;

static uint8_t s_channels[SIM_FOOTPRINT];   // 本灯"看到"的通道值

// RDM 事件计数，用于状态行（能看到控台到底问了些什么）
static volatile uint32_t s_rdm_requests;
static volatile uint32_t s_rdm_sets;

static const rdm_uid_t *s_uid;

// ============================================================================
//                             RDM 应答回调
// ============================================================================

/**
 * 通用：只记一笔，方便在串口上看"控台问了哪个 PID"。
 * 调试 RDM 时这一行比什么都直接。
 */
static void cb_log(dmx_port_t dmx_num, rdm_header_t *req, rdm_header_t *resp,
                   void *context)
{
    (void)dmx_num;
    (void)resp;
    s_rdm_requests++;
    ESP_LOGI(TAG, "[RDM] %s  cc=0x%02X pid=0x%04X  <- %04X:%08lX",
             (const char *)context, req->cc, req->pid,
             req->src_uid.man_id, (unsigned long)req->src_uid.dev_id);
}

/**
 * DMX_START_ADDRESS：GET 只回显，SET 才改本灯的解析地址。
 *
 * ⚠ 回调是在**参数已经更新之后**才被调用的（见 responder.c:141），
 *   所以这里 GET 到的一定是本次请求生效后的最新值。
 */
static void cb_start_addr(dmx_port_t dmx_num, rdm_header_t *req,
                          rdm_header_t *resp, void *context)
{
    (void)resp;
    (void)context;
    s_rdm_requests++;

    uint16_t addr = 0;
    if (rdm_get_dmx_start_address(dmx_num, &addr) != sizeof(addr)) return;

    if (req->cc == RDM_CC_SET_COMMAND) {
        s_rdm_sets++;
        s_start_addr = addr;
        ESP_LOGW(TAG, "[RDM] ★ 控台改址：起始地址 %u（占用 %u 通道 → %u..%u）",
                 addr, SIM_FOOTPRINT, addr, addr + SIM_FOOTPRINT - 1);
    } else {
        s_start_addr = addr;    // 保持同步（NVS 里可能是旧值）
        ESP_LOGI(TAG, "[RDM] GET DMX_START_ADDRESS → %u", addr);
    }
}

/** IDENTIFY_DEVICE：真实灯具会闪灯，这里打日志 + 可选驱动 LED。 */
static void cb_identify(dmx_port_t dmx_num, rdm_header_t *req,
                        rdm_header_t *resp, void *context)
{
    (void)resp;
    (void)context;
    s_rdm_requests++;

    bool on = false;
    if (rdm_get_identify_device(dmx_num, &on) != sizeof(on)) return;
    s_identify = on;

    if (req->cc == RDM_CC_SET_COMMAND)
        ESP_LOGW(TAG, "[RDM] ★ IDENTIFY %s", on ? "ON（我该闪了）" : "OFF");
    else
        ESP_LOGI(TAG, "[RDM] GET IDENTIFY_DEVICE → %s", on ? "on" : "off");
}

/** DMX 通道数（personality）被改：占用通道跟着变。 */
static void cb_personality(dmx_port_t dmx_num, rdm_header_t *req,
                           rdm_header_t *resp, void *context)
{
    (void)resp;
    (void)context;
    s_rdm_requests++;

    rdm_dmx_personality_t pers;
    if (rdm_get_dmx_personality(dmx_num, &pers) == sizeof(pers)) {
        ESP_LOGI(TAG, "[RDM] DMX_PERSONALITY → %u/%u", pers.current, pers.count);
    }
    (void)req;
}

// ============================================================================
//                            初始化
// ============================================================================

static void sim_install(void)
{
    dmx_config_t cfg = DMX_CONFIG_DEFAULT;

    // 灯具模式（personality）—— 决定 DEVICE_INFO 里上报的 footprint
    dmx_personality_t pers[] = { { SIM_FOOTPRINT, SIM_MODEL_NAME } };

    if (!dmx_driver_install(SIM_PORT, &cfg, pers, 1)) {
        ESP_LOGE(TAG, "dmx_driver_install 失败");
        return;
    }

    // TX / RX / RTS。RTS = -1 ⇒ 不控方向（TTL 直连的正确姿势）
    if (!dmx_set_pin(SIM_PORT, SIM_TX_PIN, SIM_RX_PIN, SIM_RTS_PIN)) {
        ESP_LOGE(TAG, "dmx_set_pin 失败 (TX=%d RX=%d RTS=%d)",
                 SIM_TX_PIN, SIM_RX_PIN, SIM_RTS_PIN);
        return;
    }

    // ---- 注册 RDM 参数（responder）----
    //
    // ⚠ DEVICE_INFO / DEVICE_LABEL / SOFTWARE_VERSION_LABEL / IDENTIFY_DEVICE
    //   在 dmx_driver_install 时已默认注册过，这里再调是用**自定义值覆盖**，
    //   并把回调挂上去（esp_dmx 允许覆盖同一 PID 的注册）。
#if SIM_RDM_ENABLE
    rdm_register_device_info(SIM_PORT, SIM_MODEL_ID, RDM_PRODUCT_CATEGORY_FIXTURE,
                             ESP_DMX_VERSION_ID, cb_log, "DEVICE_INFO");
    rdm_register_device_label(SIM_PORT, SIM_DEVICE_LABEL, cb_log, "DEVICE_LABEL");
    rdm_register_software_version_label(SIM_PORT, SIM_SW_LABEL, cb_log,
                                        "SOFTWARE_VERSION_LABEL");
    rdm_register_manufacturer_label(SIM_PORT, SIM_MANUFACTURER, cb_log,
                                    "MANUFACTURER_LABEL");
    rdm_register_device_model_description(SIM_PORT, SIM_MODEL_NAME, cb_log,
                                          "MODEL_DESCRIPTION");
    rdm_register_dmx_personality(SIM_PORT, 1, cb_personality, NULL);
    rdm_register_dmx_start_address(SIM_PORT, cb_start_addr, NULL);
    rdm_register_identify_device(SIM_PORT, cb_identify, NULL);

    // ---- 起始地址交给驱动自己管 ----
    // rdm_register_dmx_start_address() 会从 NVS 读上次存的值，读不到才用默认 1。
    // ⚠ 这里**不要**再调 rdm_set_dmx_start_address() 强行写回 —— 那会把控台刚改的
    //   地址在下次上电时冲掉，"灯具记住地址"这条就永远测不出来了。
    //   想把地址复位成出厂值：idf.py erase-flash 擦掉 NVS 再烧。
    uint16_t addr = SIM_START_ADDR;
    if (rdm_get_dmx_start_address(SIM_PORT, &addr) != sizeof(addr)) {
        addr = SIM_START_ADDR;
    }
    s_start_addr = addr;

    s_uid = rdm_uid_get(SIM_PORT);

    ESP_LOGI(TAG, "灯就绪：UID=%04X:%08lX  地址=%u  占用=%u  模式=%s",
             s_uid ? s_uid->man_id : 0,
             s_uid ? (unsigned long)s_uid->dev_id : 0UL,
             addr, SIM_FOOTPRINT, SIM_MODEL_NAME);
#else
    ESP_LOGW(TAG, "SIM_RDM_ENABLE=0：纯旁听模式，不应答任何 RDM 请求");
#endif
}

// ============================================================================
//                            接收 / 应答任务
// ============================================================================

/**
 * 一个任务同时干两件事：收 DMX 帧 + 回 RDM 请求。
 *
 * 为什么必须同一个任务：esp_dmx 的 `dmx_receive()` 用**任务通知**阻塞，
 * 而 `rdm_send_response()` 必须在收到请求后尽早调用（RDM 应答窗口只有
 * 176µs~2ms）。分成两个任务会互相抢通知，也让应答变慢。
 */
static void sim_task(void *arg)
{
    (void)arg;

    while (1) {
        dmx_packet_t pkt;
        // ⚠ 必须用 dmx_receive_num(..., DMX_PACKET_SIZE, ...) **显式指定期望长度**，
        //   不能用 dmx_receive()。原因（esp_dmx io.c:285）：
        //     dmx_receive() 会把「驱动当前的 dmx.size」原样当作期望长度传进去。
        //   而本任务同时在做 RDM 应答 —— rdm_send_response() 内部调
        //   dmx_send_num(应答长度) 会把 driver->dmx.size 改成 RDM 应答的长度
        //   （实测是 26 或 43）。于是紧接着的 DMX 接收就以那个长度为完成判据，
        //   **每一帧都在 26 槽处被截断**（现象：size 从 513 变成 26，其余正常）。
        //   上游的 RDMResponder 示例也这么混用，但它从不读 packet.size / DMX 数据，
        //   所以不会暴露；我们要读通道值，就必须显式钉死 513。
        size_t n = dmx_receive_num(SIM_PORT, &pkt, DMX_PACKET_SIZE,
                                   DMX_TIMEOUT_TICK);
        if (n == 0) continue;           // 超时，继续等

        if (pkt.err != DMX_OK) {
            s_st.errs++;
            switch (pkt.err) {
                case DMX_ERR_UART_OVERFLOW:    s_st.err_overflow++; break;
                case DMX_ERR_IMPROPER_SLOT:    s_st.err_slot++;     break;
                case DMX_ERR_NOT_ENOUGH_SLOTS: s_st.err_short++;    break;
                default:                       s_st.err_other++;    break;
            }
            continue;
        }

        if (pkt.is_rdm) {
            // ---- RDM 包（发现 / GET / SET）----
            // 所有"是不是发给我的""要不要应答"的判断都在库内部完成；
            // 不是发给本机的请求它会安静地返回 false。
#if SIM_RDM_ENABLE
            rdm_send_response(SIM_PORT);
#endif
        } else if (pkt.sc == DMX_SC) {
            // ---- 普通 DMX 帧：按"起始地址 + 占用通道"取自己那一段 ----
            s_st.last_sc     = (uint32_t)pkt.sc;
            s_st.last_size   = (uint32_t)pkt.size;
            s_st.last_rx_us  = esp_timer_get_time();

            uint16_t addr = s_start_addr;          // 快照，避免被 RDM 回调中途改
            // ⚠ offset 是**驱动缓冲区的原始下标**，而 data[0] 是 start code，
            //   通道 1 在 data[1]！所以"地址 A"对应 offset = A，**不是 A-1**。
            //   （esp_dmx io.c:31 是裸 memcpy(dest, driver->dmx.data + offset, size)；
            //     dmx_read() 就等价于 offset=0，即从 start code 开始读整个包。）
            //   踩过的坑：写成 addr-1 时，报出来的第 1 格永远是 start code(0x00)，
            //   看起来就像"通道 1 收不到值 / App 偏移了一位"，实为本函数读偏了一格。
            //   上限由 dmx_read_offset 自己 clamp 到 DMX_PACKET_SIZE_MAX（513），
            //   高地址处只会读回 1 个通道，其余由下面补零。
            size_t offset = (size_t)addr;
            size_t got = dmx_read_offset(SIM_PORT, offset, s_channels,
                                         SIM_FOOTPRINT);
            for (size_t i = got; i < SIM_FOOTPRINT; i++) s_channels[i] = 0;

            uint32_t nz = 0;
            for (int i = 0; i < SIM_FOOTPRINT; i++) if (s_channels[i]) nz++;
            s_st.last_nonzero = nz;
            s_st.frames++;
        } else {
            // 既不是 RDM 也不是 sc=0x00 的 DMX（厂商自定义协议等）
            s_st.bad_sc++;
            s_st.last_sc = (uint32_t)pkt.sc;
        }
    }
}

// ============================================================================
//                            上报任务
// ============================================================================

static void fmt_channels(char *out, size_t out_sz)
{
    size_t w = 0;
    for (int i = 0; i < SIM_FOOTPRINT && w + 5 < out_sz; i++) {
        w += snprintf(out + w, out_sz - w, "%s%u", (i ? " " : ""), s_channels[i]);
    }
}

/**
 * 直接采样 RX 引脚的电平 —— 绕过整个 DMX 协议栈，看线上到底有没有在动。
 *
 * 这是排查"收不到帧"最底层的事实：即使 GPIO2 被路由给了 UART1，
 * 焊盘的输入通路仍然可读，所以 gpio_get_level() 拿到的是真实线电平。
 *
 * 怎么读结果：
 *   跳变=0        → 线上完全静止：没接上 / 接了个恒定电平（GND、RO 的空闲高等）
 *   跳变 很大     → 信号确实在动，问题在帧格式/波特率，不在接线
 *   高电平占比~100% → 空闲高，说明没有数据在跑
 *   高电平占比~50~90% → 有数据在跑（DMX 帧大部分时间是 mark=高）
 */
static void sample_one(int pin, uint32_t *transitions, uint32_t *high_pct)
{
    const uint32_t N = 100000;
    uint32_t hi = 0, tr = 0;
    int prev = gpio_get_level((gpio_num_t)pin);
    for (uint32_t i = 0; i < N; i++) {
        int v = gpio_get_level((gpio_num_t)pin);
        if (v) hi++;
        if (v != prev) tr++;
        prev = v;
    }
    *transitions = tr;
    *high_pct = hi * 100 / N;
}

/**
 * 直接采样 RX 引脚的电平 —— 绕过整个 DMX 协议栈，看线上到底有没有在动。
 *
 * ⚠ 必须**同时采样 TX 引脚作参照**：GPIO 一旦被路由给 UART 外设，
 *   gpio_get_level() 是否还能读到真实焊盘电平取决于输入缓冲是否使能。
 *   本板的 UART1 TX 不发送时恒为 mark=高，所以：
 *     TX 读到 ~100% 高  → 采样方法可信，RX 的读数可以当真
 *     TX 也读到 0%      → 采样方法不可信，RX 的读数无意义（别误判成"线接地了"）
 *
 * 怎么读 RX 的结果：
 *   跳变=0 且 高电平=0%   → 线恒定低：接错到 GND / 接了个被拉低的脚
 *   跳变=0 且 高电平=100% → 线恒定高：没数据在跑（或接了个恒高的脚）
 *   跳变 很大             → 信号确实在动，问题在帧格式/波特率，不在接线
 */
static void sample_rx_pin(uint32_t *tr_rx, uint32_t *hi_rx,
                          uint32_t *tr_tx, uint32_t *hi_tx)
{
    sample_one(SIM_RX_PIN, tr_rx, hi_rx);
    sample_one(SIM_TX_PIN, tr_tx, hi_tx);
}

static void sim_report_task(void *arg)
{
    (void)arg;

    uint32_t prev_frames = 0;
    int no_link_ticks = 0;

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(SIM_REPORT_MS));

        uint32_t f = s_st.frames;
        uint32_t fps = f - prev_frames;
        prev_frames = f;

        int64_t now = esp_timer_get_time();
        bool link = (s_st.last_rx_us != 0) &&
                    ((now - s_st.last_rx_us) < (int64_t)SIM_LINK_TIMEOUT_MS * 1000);

        if (!link) {
            // ⚠ 必须**周期性**重报，不能只报一次：
            //   只报一次的话，串口上「什么都没有」就同时对应三种情况
            //   —— 板子没启动 / 启动了但没信号 / 警告在你接串口之前就过去了，
            //   现场根本没法区分。每 SIM_LINK_WARN_S 秒重报一次，看见它就说明板子活着。
            if (++no_link_ticks >= SIM_LINK_WARN_S) {
                no_link_ticks = 0;
                // 无信号时顺便量一下引脚实际电平 —— 区分"线没通"和"通了但帧不对"
                uint32_t tr_rx = 0, hi_rx = 0, tr_tx = 0, hi_tx = 0;
                sample_rx_pin(&tr_rx, &hi_rx, &tr_tx, &hi_tx);
                ESP_LOGW(TAG,
                         "无 DMX 信号 %d 秒了（收帧=%lu 错帧=%lu[溢出%lu 帧格式%lu 槽不足%lu]）"
                         "｜GPIO%d(RX): 跳变=%lu 高=%lu%% ｜GPIO%d(TX参照): 跳变=%lu 高=%lu%%",
                         SIM_LINK_WARN_S,
                         (unsigned long)s_st.frames,
                         (unsigned long)s_st.errs,
                         (unsigned long)s_st.err_overflow,
                         (unsigned long)s_st.err_slot,
                         (unsigned long)s_st.err_short,
                         SIM_RX_PIN, (unsigned long)tr_rx, (unsigned long)hi_rx,
                         SIM_TX_PIN, (unsigned long)tr_tx, (unsigned long)hi_tx);
            }
            continue;
        }
        no_link_ticks = 0;

        uint16_t addr = s_start_addr;
        char chbuf[5 * SIM_FOOTPRINT + 4];
        fmt_channels(chbuf, sizeof(chbuf));

        if (SIM_LED_PIN >= 0) {
            // 识别时快闪；平时灭
            gpio_set_level((gpio_num_t)SIM_LED_PIN,
                           s_identify ? (int)((now / 100000) & 1) : 0);
        }

        // 可选：把被监视引脚（例如控台的 EN/方向脚）的实测电平拼到状态行尾部
        char watchbuf[64] = "";
#if SIM_WATCH_PIN >= 0
        {
            uint32_t wtr = 0, whi = 0;
            sample_one(SIM_WATCH_PIN, &wtr, &whi);
            snprintf(watchbuf, sizeof(watchbuf),
                     " | GPIO%d(监视): 跳变=%lu 高=%lu%%",
                     SIM_WATCH_PIN, (unsigned long)wtr, (unsigned long)whi);
        }
#endif

        ESP_LOGI(TAG,
                 "[灯] %2lu fps | sc=%02lX size=%lu | 地址=%u..%u | %s%s | "
                 "非零=%lu 错帧=%lu sc错=%lu | RDM req=%lu set=%lu%s%s",
                 (unsigned long)fps, (unsigned long)s_st.last_sc,
                 (unsigned long)s_st.last_size,
                 addr, addr + SIM_FOOTPRINT - 1,
                 s_identify ? "IDENTIFY! " : "",
                 (s_st.last_nonzero == 0) ? "（全黑）" : chbuf,
                 (unsigned long)s_st.last_nonzero,
                 (unsigned long)s_st.errs, (unsigned long)s_st.bad_sc,
                 (unsigned long)s_rdm_requests, (unsigned long)s_rdm_sets,
                 (SIM_RTS_PIN < 0) ? " | TTL直连" : " | 差分(有方向脚)",
                 watchbuf);
    }
}

// ============================================================================
//                                app_main
// ============================================================================

void app_main(void)
{
    memset(&s_st, 0, sizeof(s_st));
    memset(s_channels, 0, sizeof(s_channels));

    // RDM 的非易失参数（起始地址等）走 NVS，必须先初始化
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    }

    ESP_LOGI(TAG, "=== StageDMX 灯具模拟器 ===");
    ESP_LOGI(TAG, "接线: 控台 U1 TX(GPIO17) -> 本板 GPIO%d | "
                  "控台 U1 RX(GPIO18) <- 本板 GPIO%d | GND-GND",
             SIM_RX_PIN, SIM_TX_PIN);
    if (SIM_RTS_PIN < 0)
        ESP_LOGI(TAG, "TTL 直连模式：无方向脚（不用收发器）");
    else
        ESP_LOGI(TAG, "差分模式：方向脚 GPIO%d", SIM_RTS_PIN);

#if SIM_LED_PIN >= 0
    gpio_reset_pin((gpio_num_t)SIM_LED_PIN);
    gpio_set_direction((gpio_num_t)SIM_LED_PIN, GPIO_MODE_OUTPUT);
#endif

#if SIM_WATCH_PIN >= 0
    // 监视脚：配成输入并**使能下拉**。下拉的作用是让"没接线"可辨认：
    //   跳变=0 高=0%   → 要么没接上，要么被监视的那根线确实是低电平
    //   跳变=0 高=100% → 接上了且恒为高
    //   跳变 很大      → 接上了并且在翻转（例如 EN 每帧都在收发之间切换）
    gpio_reset_pin((gpio_num_t)SIM_WATCH_PIN);
    gpio_set_direction((gpio_num_t)SIM_WATCH_PIN, GPIO_MODE_INPUT);
    gpio_pulldown_en((gpio_num_t)SIM_WATCH_PIN);
    gpio_pullup_dis((gpio_num_t)SIM_WATCH_PIN);
    ESP_LOGI(TAG, "监视引脚已启用：GPIO%d（接被测信号，例如控台的 EN）",
             SIM_WATCH_PIN);
#endif

    sim_install();

    // DMX 驱动必须装在本任务所在核上（ISR 跟随调用核）。
    // 与控台固件保持一致：都钉在 core 1。
    xTaskCreatePinnedToCore(sim_task, "fixture", 4096, NULL, 10, NULL, 1);
    xTaskCreatePinnedToCore(sim_report_task, "report", 4096, NULL, 3, NULL, 0);

    ESP_LOGI(TAG, "等控台发 DMX…（App 里点『RDM 扫描』应当能发现本灯）");
}
