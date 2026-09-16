/**
 * StageDMX 标准版 — 纯 DMX 发送端
 * 适用: ESP32-S3 标准开发板, 无屏幕
 * BLE→DMX512, 支持板载多程序 HTP 播放
 */
#include "esp_log.h"
#include "nvs_flash.h"
#include "pins.h"
#include "dmx_state.h"
#include "dmx.h"
#include "ble_dmx.h"
#include "program.h"
#include "fx.h"
#include "render.h"
#include "usb_msc.h"

static const char *TAG = "stagedmx_std";

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
    ble_dmx_init();

    ESP_LOGI(TAG, "StageDMX Std ready.");
    // v7：每个宇宙是一套完整双向接口（TX/RX/EN），不再共用一根 EN。
    ESP_LOGI(TAG, "BLE: StageDMX-01  |  U1: TX=%d RX=%d EN=%d  |  U2: TX=%d RX=%d EN=%d  |  %d universes",
             DMX_TX_PIN, DMX_RX_PIN, DMX_EN_PIN,
             DMX_TX2_PIN, DMX_RX2_PIN, DMX_EN2_PIN, DMX_UNIVERSES);
}
