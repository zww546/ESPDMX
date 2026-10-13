/**
 * StageDMX 标准版 — 纯 DMX 发送端
 * 适用: ESP32-S3 标准开发板, 无屏幕
 * BLE→DMX512, 支持板载多程序 HTP 播放
 */

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "pins.h"
#include "dmx_state.h"
#include "dmx.h"
#include "ble_dmx.h"
#include "program.h"
#include "fx.h"
#include "render.h"
#include "rdm.h"
#include "usb_msc.h"

static const char *TAG = "stagedmx_std";


/**
 * 状态灯任务（板载 LED，GPIO1）
 * ============================================================================
 *
 * 单独一个**低优先级任务跑在 core 0**，只读状态、绝不阻塞 DMX 输出任务
 * （与 dmx_telemetry_task 同样的思路：控制台打印和状态灯都不许进 DMX 任务）。
 *
 * ## 灯语（优先级从高到低）
 *
 *   DMX 输出**失败**（驱动没装成、或这一秒内出现过发送失败） → 5Hz 快闪   ← 一眼看出出事了
 *   BLE **还没连手机**                                     → 1Hz 慢闪   ← 等待中
 *   BLE 已连接 + 两个宇宙都在正常发                        → 常亮
 *
 * 为什么不用"每帧翻转"来表示帧率：DMX 是 ~41fps，人眼看不出来，也没有信息量。
 * 状态灯要回答的是"现在能不能用"，所以只分这几档。
 *
 * ## 判定细节
 *
 *   · `dmx_port_ready(u)`：驱动是否装成（装不上 = 这个宇宙彻底不发）
 *   · `dmx_get_stats(u, &f, &fails, &ok, &fps)`：fails 与上一秒比较，
 *     **只要变大就点亮报警**（哪怕只是一帧），下一秒自动恢复 —— 偶发错误不该
 *     被"几秒才判一次"的平均值吃掉。
 */
static void status_led_task(void *arg)
{
    (void)arg;
    gpio_set_direction((gpio_num_t)STATUS_LED_PIN, GPIO_MODE_OUTPUT);

    uint32_t last_fails[DMX_UNIVERSES] = { 0, 0 };

    for (;;) {
        bool ready = true, fault = false;
        for (int u = 0; u < DMX_UNIVERSES; u++) {
            uint32_t frames = 0, fails = 0; uint8_t ok = 0, fps = 0;
            dmx_get_stats((uint8_t)u, &frames, &fails, &ok, &fps);
            if (!dmx_port_ready((uint8_t)u)) ready = false;
            if (fails > last_fails[u]) fault = true;      // 这一周期内出现过失败
            last_fails[u] = fails;
        }
        const bool ble = ble_dmx_is_connected();

        // 灯态：用绝对时间算相位，避免任务被抢占时节奏漂移
        const uint32_t ms = (uint32_t)(esp_timer_get_time() / 1000);
        bool lit;
        if (fault)         lit = ((ms / 100) & 1) == 0;   // 5Hz  故障
        else if (!ready)   lit = ((ms / 250) & 1) == 0;   // 2Hz  驱动没装成
        else if (!ble)     lit = ((ms / 500) & 1) == 0;   // 1Hz  等手机
        else               lit = ((ms / 1500) % 2) == 0;  // 慢心跳 正常工作

        gpio_set_level((gpio_num_t)STATUS_LED_PIN, STATUS_LED_ACTIVE_LOW ? !lit : lit);
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void app_main(void)
{
    // NVS
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    }

    dmx_state_init();
    dmx_start();
    render_start_task();   // v6：程序层 + 效果层 合成在同一个渲染管线里
    rdm_init();            // v8：RDM 控制器（设备表放 PSRAM）
    ble_dmx_init();

    // status LED task (core 0, lowest prio; reads state only, never blocks DMX)
    xTaskCreatePinnedToCore(status_led_task, "status_led", 3072, NULL, 2, NULL, 0);

    ESP_LOGI(TAG, "StageDMX Std ready.");
    // v7：每个宇宙是一套完整双向接口（TX/RX/EN），不再共用一根 EN。
    ESP_LOGI(TAG, "BLE: StageDMX-01  |  U1: TX=%d RX=%d EN=%d  |  U2: TX=%d RX=%d EN=%d  |  %d universes",
             DMX_TX_PIN, DMX_RX_PIN, DMX_EN_PIN,
             DMX_TX2_PIN, DMX_RX2_PIN, DMX_EN2_PIN, DMX_UNIVERSES);
}
