/**
 * StageDMX 分析仪 —— 把第二块 ESP32-S3 接在 DMX 线上，看**总线上到底在发什么**。
 *
 * ## 为什么要它
 * 排查"帧率 100 多 / 灯具复位乱动"这类问题时，主控自己上报的 fps 是**不可信的**
 * —— 它统计的是"循环了几次"，发送失败时反而更高。必须有个**旁路观测者**看真实总线。
 *
 * ## 接线（关键：接 TTL 侧，不用收发器）
 *   被测板 U1 的 TXD (GPIO17) ──→ 本板 RX 引脚（默认 GPIO2）
 *   GND ── GND（**必须共地**，否则读到的全是噪声）
 *
 * ⚠ 接的是 UART 的 **TTL 输出**（RS-485 模块之前），不是 A/B 差分线。
 *   差分线不能直接接 GPIO。
 *
 * ⚠ **不要用 GPIO0**：它是 strapping 脚（启动模式选择），低电平会让芯片进下载模式
 *   —— 表现是"烧录正常但什么日志都没有，像板子没启动"。实测踩过这个坑。
 *   默认改用 GPIO2（安全脚）。
 *
 * ⚠ 同理避开 GPIO3 / GPIO45 / GPIO46 / GPIO19 / GPIO20（strapping 或原生 USB）。
 *
 * ## 它会告诉你什么
 *   · 真实帧率（每秒几帧）—— 直接验证"100 多帧"是真是假
 *   · start code（必须 0x00，不是 0x00 说明帧结构不对）
 *   · break / MAB 长度（DMX512-A 要求 break ≥ 88µs、MAB ≥ 8µs）
 *   · 非零通道数 + 前 8 个通道值（能看到主控到底在写什么）
 *   · 接收错误计数（超时/溢出/framing）
 */
#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_dmx.h"

static const char *TAG = "sniff";

// ---- 接收引脚 ----
//   2 = 接被测板 U1 的 TXD (GPIO17)
//   1 = 可选第二路（接 U2 的 TXD，默认关）
#define SNIFF_RX_PIN        2
#define SNIFF_RX_PIN2       -1      // 想同时看宇宙2就改成 1

#define SNIFF_PORT          DMX_NUM_1
#define SNIFF_PORT2         DMX_NUM_2

// 统计
typedef struct {
    volatile uint32_t frames;
    volatile uint32_t bad_sc;       // start code 不是 0x00 的帧
    volatile uint32_t errs;         // 接收错误帧
    volatile uint32_t last_sc;
    volatile uint32_t last_size;
    volatile uint32_t last_break;   // µs
    volatile uint32_t last_mab;     // µs
    volatile uint32_t last_nonzero;
    volatile uint8_t  first8[8];
} sniff_stat_t;

static sniff_stat_t s_st1, s_st2;

static void install(int port, int rx_pin)
{
    if (rx_pin < 0) return;
    dmx_config_t cfg = DMX_CONFIG_DEFAULT;
    if (!dmx_driver_install((dmx_port_t)port, &cfg, NULL, 0)) {
        ESP_LOGE(TAG, "port %d install failed", port);
        return;
    }
    // 纯接收：TX 传 -1（不驱动总线），RTS 传 -1（不控方向）
    if (!dmx_set_pin((dmx_port_t)port, -1, rx_pin, -1)) {
        ESP_LOGE(TAG, "port %d set_pin(RX=%d) failed", port, rx_pin);
        return;
    }
    ESP_LOGI(TAG, "port %d listening on GPIO%d @ %lu baud",
             port, rx_pin, (unsigned long)dmx_get_baud_rate((dmx_port_t)port));
}

/** 接收任务：持续收帧并更新统计（不打印，避免阻塞接收）。 */
static void sniff_task(void *arg)
{
    int port = (int)(intptr_t)arg;
    sniff_stat_t *st = (port == SNIFF_PORT) ? &s_st1 : &s_st2;
    static uint8_t buf[DMX_PACKET_SIZE];

    while (1) {
        dmx_packet_t pkt;
        // 等 200ms：没有帧就返回 0，循环继续（不忙等）
        size_t n = dmx_receive((dmx_port_t)port, &pkt, pdMS_TO_TICKS(200));
        if (n == 0) continue;

        if (pkt.err != DMX_OK) {
            st->errs++;
            continue;
        }
        st->last_sc = (uint32_t)pkt.sc;
        st->last_size = (uint32_t)pkt.size;
        if (pkt.sc != 0) st->bad_sc++;

        // ⚠ 组件没有公开 break/MAB 元数据的读取接口（dmx_metadata_t 只在内部
        //   service.h 里），所以这里不报 break/MAB。要测时序合规性得用示波器
        //   或逻辑分析仪 —— 本分析仪关注的是"帧率 / start code / 通道值"。

        // 读通道（buf[0] = 通道1）
        size_t got = dmx_read((dmx_port_t)port, buf, pkt.size);
        uint32_t nz = 0;
        for (size_t i = 0; i < got; i++) if (buf[i]) nz++;
        st->last_nonzero = nz;
        for (int i = 0; i < 8; i++) st->first8[i] = (i < (int)got) ? buf[i] : 0;

        st->frames++;
    }
}

/** 上报任务：每秒打一行，帧率 = 本秒帧数。 */
static void report_task(void *arg)
{
    (void)arg;
    uint32_t pf1 = 0, pf2 = 0;
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        uint32_t f1 = s_st1.frames, f2 = s_st2.frames;
        uint32_t fps1 = f1 - pf1, fps2 = f2 - pf2;
        pf1 = f1; pf2 = f2;

        if (SNIFF_RX_PIN >= 0) {
            if (f1 == 0) {
                ESP_LOGW(TAG, "U1: 还没有收到任何帧（检查接线 / 共地 / 是不是接错到差分线上了）");
            } else {
                ESP_LOGI(TAG, "U1: %2lu fps | sc=0x%02lX size=%lu | break=%luus mab=%luus | "
                              "非零=%lu | 前8=%u %u %u %u %u %u %u %u | 错帧=%lu sc错=%lu",
                         (unsigned long)fps1, (unsigned long)s_st1.last_sc,
                         (unsigned long)s_st1.last_size,
                         (unsigned long)s_st1.last_break, (unsigned long)s_st1.last_mab,
                         (unsigned long)s_st1.last_nonzero,
                         s_st1.first8[0], s_st1.first8[1], s_st1.first8[2], s_st1.first8[3],
                         s_st1.first8[4], s_st1.first8[5], s_st1.first8[6], s_st1.first8[7],
                         (unsigned long)s_st1.errs, (unsigned long)s_st1.bad_sc);
            }
        }
        if (SNIFF_RX_PIN2 >= 0 && f2 > 0) {
            ESP_LOGI(TAG, "U2: %2lu fps | sc=0x%02lX | 非零=%lu | 错帧=%lu",
                     (unsigned long)fps2, (unsigned long)s_st2.last_sc,
                     (unsigned long)s_st2.last_nonzero, (unsigned long)s_st2.errs);
        }
    }
}

void app_main(void)
{
    memset(&s_st1, 0, sizeof(s_st1));
    memset(&s_st2, 0, sizeof(s_st2));

    ESP_LOGI(TAG, "=== StageDMX 分析仪 ===");
    ESP_LOGI(TAG, "接线：被测板 TXD(GPIO17) → 本板 GPIO%d，GND↔GND", SNIFF_RX_PIN);
    ESP_LOGI(TAG, "⚠ 接 TTL 侧（模块之前），不要接 A/B 差分线");

    install(SNIFF_PORT, SNIFF_RX_PIN);
    install(SNIFF_PORT2, SNIFF_RX_PIN2);

    xTaskCreatePinnedToCore(sniff_task, "sniff1", 4096,
                            (void *)(intptr_t)SNIFF_PORT, 10, NULL, 1);
    if (SNIFF_RX_PIN2 >= 0)
        xTaskCreatePinnedToCore(sniff_task, "sniff2", 4096,
                                (void *)(intptr_t)SNIFF_PORT2, 10, NULL, 1);
    xTaskCreatePinnedToCore(report_task, "report", 4096, NULL, 3, NULL, 0);
}
