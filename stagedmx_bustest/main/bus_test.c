/**
 * 总线自测固件 v2 —— **自发自收**（不需要第二块板）
 * ============================================================================
 *
 * ## 为什么能自检
 *
 * 两个宇宙的 A/B 已经**并到同一条总线**上，而每片 SP3485 的 `/RE` 与 `DE` 短接
 * （DE=0 ⇒ `/RE`=0 ⇒ 接收器开）。于是：
 *
 *     某宇宙 DE=0（放开总线）时，它自己是**接收器**，能听见另一个宇宙发的帧。
 *
 * 所以阶段循环里天然形成"一发一收"，两块 485 的**发送 + 接收**四个方向都能验到：
 *
 *   P0  U1 驱动、U2 放开   → 期望 **U2 收到** U1 的图案 11 22 33 44
 *   P1  U2 驱动、U1 放开   → 期望 **U1 收到** U2 的图案 AA BB CC DD
 *   P2  两个都驱动         → 互打（双方都在发，谁都不听）
 *   P3  两个都放开         → 安静（对照"没人驱动"的样子）
 *
 * ## 判读（看串口）
 *
 *   P0 的 `RX U2: n` 一直为 0  ⇒ U1 这片发出不去，或 U2 这片收不回来
 *   P1 的 `RX U1: n` 一直为 0  ⇒ U2 这片发出不去，或 U1 这片收不回来
 *   两侧都收到、且图案对上 ⇒ **两片 485 + A/B 全线正常**
 *
 * ## 测量行刻意写成 ASCII
 *   `RX U1: n=123 pat=AA BB CC DD` —— 中文注释只给人看，数值行不带中文，
 *   免得日志在别的终端里变乱码、数不出来。
 */

#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_dmx.h"

#define U1_PORT     DMX_NUM_1
#define U1_TX_PIN   17
#define U1_RX_PIN   18
#define U1_EN_PIN   4

#define U2_PORT     DMX_NUM_2
#define U2_TX_PIN   16
#define U2_RX_PIN   15
#define U2_EN_PIN   5

#define PHASE_MS    8000              // 每阶段时长（8 秒：足够采到 300+ 帧）
#define FRAME_WAIT  60

static const char *TAG = "bustest";

static const uint8_t PAT_U1[4] = { 0x11, 0x22, 0x33, 0x44 };
static const uint8_t PAT_U2[4] = { 0xAA, 0xBB, 0xCC, 0xDD };

typedef struct {
    const char    *name;              // "U1" / "U2"（ASCII，便于日志判读）
    dmx_port_t     port;
    int            tx_pin, rx_pin, en_pin;
    const uint8_t *pattern;
    uint8_t        frame[513];
    uint32_t       sent;
    uint32_t       rx_frames;         // 收到的有效 DMX 帧数
    uint8_t        rx_pat[4];         // 最近一帧的前 4 个通道
    bool           ok;
} uni_t;

static uni_t s_u[2] = {
    { "U1", U1_PORT, U1_TX_PIN, U1_RX_PIN, U1_EN_PIN, PAT_U1, { 0 }, 0, 0, { 0 }, false },
    { "U2", U2_PORT, U2_TX_PIN, U2_RX_PIN, U2_EN_PIN, PAT_U2, { 0 }, 0, 0, { 0 }, false },
};

static bool install_one(uni_t *u)
{
    dmx_config_t cfg = DMX_CONFIG_DEFAULT;
    dmx_personality_t pers[] = { { 512, "DMX512" } };

    if (!dmx_driver_install(u->port, &cfg, pers, 1)) {
        ESP_LOGE(TAG, "install FAILED %s", u->name);
        return false;
    }
    // RX 也绑上（要收对端）；RTS 不绑 —— 方向自己用 GPIO 拨，这样才能"彻底放开"
    if (!dmx_set_pin(u->port, u->tx_pin, u->rx_pin, -1)) {
        ESP_LOGE(TAG, "set_pin FAILED %s", u->name);
        return false;
    }
    gpio_set_direction((gpio_num_t)u->en_pin, GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)u->en_pin, 0);          // 初始放开
    u->frame[0] = 0x00;
    memcpy(&u->frame[1], u->pattern, 4);
    return true;
}

/** DE=1 驱动总线 / DE=0 放开（此时 `/RE`=0 ⇒ 本片是接收器） */
static inline void drive(uni_t *u, bool on)
{
    gpio_set_level((gpio_num_t)u->en_pin, on ? 1 : 0);
}

static void send_one(uni_t *u)
{
    dmx_write(u->port, u->frame, sizeof(u->frame));
    if (dmx_send(u->port) > 0) {
        dmx_wait_sent(u->port, pdMS_TO_TICKS(FRAME_WAIT));
        u->sent++;
    }
}

/** 非阻塞收一帧；收到就记下前 4 个通道（用于认"这帧是谁发的"）。 */
static void poll_rx(uni_t *u)
{
    dmx_packet_t pkt;
    if (dmx_receive(u->port, &pkt, 0) > 0 && !pkt.err) {
        if (!pkt.err) {
            u->rx_frames++;
            for (int i = 0; i < 4; i++) u->rx_pat[i] = (uint8_t)dmx_read_slot(u->port, i + 1);
        }
    }
}

static const char *PHASE_DESC[] = {
    "P0 U1 drives / U2 listens", "P1 U2 drives / U1 listens",
    "P2 both drive (collide)",   "P3 both released (quiet)",
};

static void bus_test_task(void *arg)
{
    (void)arg;
    for (;;) {
        for (int ph = 0; ph < 4; ph++) {
            const bool u1_on = (ph == 0 || ph == 2);
            const bool u2_on = (ph == 1 || ph == 2);
            drive(&s_u[0], u1_on);
            drive(&s_u[1], u2_on);
            s_u[0].rx_frames = s_u[1].rx_frames = 0;      // 每阶段清零，判读干净

            ESP_LOGI(TAG, "==== %s ==== U1_DE=%d U2_DE=%d | U1_PAT=11 22 33 44 U2_PAT=AA BB CC DD",
                     PHASE_DESC[ph], (int)u1_on, (int)u2_on);

            const TickType_t t0 = xTaskGetTickCount();
            uint32_t last_s = 0xFFFFFFFF;
            const TickType_t phase_ticks = pdMS_TO_TICKS(PHASE_MS);
            while ((xTaskGetTickCount() - t0) < phase_ticks) {
                if (u1_on) send_one(&s_u[0]);
                if (u2_on) send_one(&s_u[1]);
                if (!u1_on) poll_rx(&s_u[0]);             // 没在发的那个在听
                if (!u2_on) poll_rx(&s_u[1]);
                vTaskDelay(1);

                const uint32_t s = (xTaskGetTickCount() - t0) * portTICK_PERIOD_MS / 1000;
                if (s != last_s) {
                    last_s = s;

                    // ---- 软件示波器：直接量 MCU 焊盘的真实电平/活动量 ----
                    // 不依赖任何外部仪器：RX 焊盘上有没有跳变，直接说明芯片的 RO
                    // 到底有没有把总线上的数据送到 MCU。
                    //   RX_lv=1 且 act=0 → 焊盘恒高（失效保护或没人驱动）⇒ 收不到东西
                    //   act>0            → 焊盘上有数据 ⇒ 芯片/接线这一段是通的
                    // ⚠ 引脚已被 UART 外设占用，读之前必须打开输入通路（gpio_input_enable），
                    //   否则 GPIO 矩阵输入是关的、gpio_get_level 恒 0，会误判成"没信号"。
                    uint32_t rx_act[2] = { 0, 0 };
                    int rx_lv[2] = { 0, 0 }, en_lv[2] = { 0, 0 };
                    for (int u = 0; u < 2; u++) {
                        const gpio_num_t rp = (gpio_num_t)s_u[u].rx_pin;
                        gpio_input_enable(rp);
                        int prev = gpio_get_level(rp);
                        rx_lv[u] = prev;
                        for (int k = 0; k < 400; k++) {          // µs 级连采 400 次
                            const int cur = gpio_get_level(rp);
                            if (cur != prev) { rx_act[u]++; prev = cur; }
                        }
                        en_lv[u] = gpio_get_level((gpio_num_t)s_u[u].en_pin);
                    }

                    ESP_LOGI(TAG, "  t=%lus SENT U1=%lu U2=%lu | RX U1: n=%lu pat=%02X %02X %02X %02X | "
                                  "RX U2: n=%lu pat=%02X %02X %02X %02X",
                             (unsigned long)s,
                             (unsigned long)s_u[0].sent, (unsigned long)s_u[1].sent,
                             (unsigned long)s_u[0].rx_frames,
                             s_u[0].rx_pat[0], s_u[0].rx_pat[1], s_u[0].rx_pat[2], s_u[0].rx_pat[3],
                             (unsigned long)s_u[1].rx_frames,
                             s_u[1].rx_pat[0], s_u[1].rx_pat[1], s_u[1].rx_pat[2], s_u[1].rx_pat[3]);
                    ESP_LOGI(TAG, "  PAD U1(RX=%d): lv=%d act=%lu EN=%d | U2(RX=%d): lv=%d act=%lu EN=%d",
                             U1_RX_PIN, rx_lv[0], (unsigned long)rx_act[0], en_lv[0],
                             U2_RX_PIN, rx_lv[1], (unsigned long)rx_act[1], en_lv[1]);
                }
            }
            // 阶段小结：直接给出"该不该收到、实际收到没有"
            const uni_t *exp = u1_on ? &s_u[1] : &s_u[0];   // 谁在听
            const uni_t *src = u1_on ? &s_u[0] : &s_u[1];   // 谁在发
            if (ph < 2) {
                ESP_LOGI(TAG, "RESULT %s: %s should receive %s -> n=%lu frames, pat=%02X %02X %02X %02X (%s)",
                         PHASE_DESC[ph], exp->name, src->name, (unsigned long)exp->rx_frames,
                         exp->rx_pat[0], exp->rx_pat[1], exp->rx_pat[2], exp->rx_pat[3],
                         (exp->rx_frames > 0) ? "OK" : "FAIL");
            }
        }
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "=== BUS SELF TEST v2 (self send / self receive) ===");
    ESP_LOGI(TAG, "U1 TX=%d RX=%d DE=%d | U2 TX=%d RX=%d DE=%d",
             U1_TX_PIN, U1_RX_PIN, U1_EN_PIN, U2_TX_PIN, U2_RX_PIN, U2_EN_PIN);
    ESP_LOGI(TAG, "U1_PAT=11 22 33 44 | U2_PAT=AA BB CC DD | phase=%d ms", PHASE_MS);

    s_u[0].ok = install_one(&s_u[0]);
    s_u[1].ok = install_one(&s_u[1]);
    ESP_LOGI(TAG, "drivers: U1=%s U2=%s", s_u[0].ok ? "OK" : "FAIL", s_u[1].ok ? "OK" : "FAIL");

    xTaskCreate(bus_test_task, "bustest", 4096, NULL, 5, NULL);
}

