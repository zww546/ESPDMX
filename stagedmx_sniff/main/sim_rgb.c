/**
 * 板载 RGB（WS2812，GPIO48）—— 把收到的 DMX 通道直接变成颜色
 * ============================================================================
 *
 * ## 用途：让第二块板当"真灯"，一眼看出控台的 DMX 通不通
 *
 * 这台模拟器本来就能收 DMX 并打印通道值，但要连电脑才看得到。给它加上板载 RGB 后：
 *
 *     RGB = 收到的 DMX 通道 1 / 2 / 3（即灯具起始地址起的头三个通道）
 *
 *   · **有数据**：颜色直接跟着通道值变 —— 推子/图案一动，颜色就动 ✓
 *   · **没数据**：暗蓝（很暗，一眼能区分"没信号"和"信号值为 0"）
 *
 * 这比看日志直观得多，也顺便验证了"通道映射对不对"（比如你推 ch1，它就该变红）。
 *
 * ## 为什么用 RMT 而不是 bit-bang
 *
 * WS2812 的位宽只有 0.3~0.9µs，bit-bang 必须**关中断**几十微秒 —— 那会打断
 * 本板正在收的 DMX（一个位才 4µs），甚至影响 RDM 应答。RMT 是硬件定时器，
 * 不关中断、不占 CPU，发完由硬件保证时序。
 *
 * ## 实现要点
 *
 *   · `rmt_new_bytes_encoder` 的 bit0/bit1 直接配置成 WS2812 的电平+时长，
 *     于是"发一个字节"就等于"发 8 个 WS2812 位"，**不需要自己写编码器**。
 *   · 分辨率 10MHz（0.1µs/格）：bit0 = 高3格/低9格（0.3/0.9µs），
 *     bit1 = 高9格/低3格（0.9/0.3µs）—— 即 WS2812B 的标准时序。
 *   · WS2812 **MSB 优先**，所以 `msb_first = 1`。
 *   · 字节顺序是 **GRB**（不是 RGB！WS2812 内部就这么排）。
 *   · 复位靠帧间空隙：发完等 `rmt_tx_wait_all_done`，线保持低电平 ≥50µs 即可；
 *     本函数最快也就 10Hz 级别调用，天然满足。
 *   · 亮度**不做 gamma/缩放**：通道值多少就是多少，好让"收到的数据"忠实可见。
 */

#include <string.h>

#include "driver/rmt_tx.h"
#include "driver/rmt_encoder.h"
#include "esp_log.h"

#define SIM_RGB_GPIO        48          // 板载 WS2812 数据脚（据原理图）
#define SIM_RGB_RES_HZ      (10 * 1000 * 1000)   // 0.1µs/格

static const char *TAG = "sim_rgb";
static rmt_channel_handle_t s_tx = NULL;
static rmt_encoder_handle_t s_enc = NULL;
static bool s_init_done = false;

void sim_rgb_init(void)
{
    rmt_tx_channel_config_t tcfg = {
        .gpio_num = SIM_RGB_GPIO,
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .resolution_hz = SIM_RGB_RES_HZ,
        .mem_block_symbols = 64,
        .trans_queue_depth = 4,
    };
    if (rmt_new_tx_channel(&tcfg, &s_tx) != ESP_OK) {
        ESP_LOGW(TAG, "RMT 通道创建失败，RGB 不可用");
        s_tx = NULL;
        return;
    }

    rmt_bytes_encoder_config_t bcfg = {
        .bit0 = { .level0 = 1, .duration0 = 3, .level1 = 0, .duration1 = 9 },   // 0.3/0.9µs
        .bit1 = { .level0 = 1, .duration0 = 9, .level1 = 0, .duration1 = 3 },   // 0.9/0.3µs
        .flags = { .msb_first = 1 },                                            // WS2812 MSB 优先
    };
    if (rmt_new_bytes_encoder(&bcfg, &s_enc) != ESP_OK) {
        ESP_LOGW(TAG, "RMT 编码器创建失败，RGB 不可用");
        s_tx = NULL;
        return;
    }
    rmt_enable(s_tx);
    ESP_LOGI(TAG, "板载 RGB 就绪：WS2812 @ GPIO%d（收到的 ch1/ch2/ch3 → R/G/B）", SIM_RGB_GPIO);
}

/** 设置颜色（0..255）。未初始化时静默返回，不影响收帧主流程。 */
void sim_rgb_show(uint8_t r, uint8_t g, uint8_t b)
{
    if (!s_tx) { if (!s_init_done) { s_init_done = true; sim_rgb_init(); } if (!s_tx) return; }
    const uint8_t grb[3] = { g, r, b };                 // WS2812 是 GRB 顺序
    rmt_transmit_config_t xcfg = { .loop_count = 0 };
    if (rmt_transmit(s_tx, s_enc, grb, sizeof(grb), &xcfg) == ESP_OK) {
        // 等硬件发完：既保证下一帧不会挤掉本帧，也顺带产生了 ≥50µs 的复位间隙
        rmt_tx_wait_all_done(s_tx, 20);
    }
}

/** 没有收到 DMX 时的默认色：很暗的蓝，和"收到但全 0（黑）"区分开。 */
void sim_rgb_show_idle(void)
{
    sim_rgb_show(0, 0, 24);
}
