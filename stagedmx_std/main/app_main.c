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
    program_start_task();
    fx_start_task();
    ble_dmx_init();

    ESP_LOGI(TAG, "StageDMX Std ready.");
    ESP_LOGI(TAG, "BLE: StageDMX-01  |  DMX: TX=%d DE=%d", DMX_TX_PIN, DMX_DE_PIN);
}
