#include "dmx.h"
#include "dmx_state.h"
#include "pins.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_dmx.h"

static const char *TAG = "dmx";
#define DMX_PORT DMX_NUM_1

// 自愈：连续失败阈值（约 2s @ 40fps）——若 UART TX_DONE 中断丢失导致驱动
// 卡在 SENDING，之后每帧 wait_sent 都会超时，DMX 停发但 BLE 正常（正是
// “灯具断联、蓝牙正常、RST 恢复”的症状）。达到阈值后重建驱动自动恢复。
#define DMX_HEAL_THRESHOLD  80

static volatile uint32_t s_frames = 0;
static volatile bool     s_last_ok = false;
static volatile uint32_t s_heals = 0;   // 自愈次数（诊断用）

uint32_t dmx_tx_frames(void)   { return s_frames; }
bool     dmx_tx_ok(void)       { return s_last_ok; }
uint32_t dmx_tx_heals(void)    { return s_heals; }
bool     dmx_tx_installed(void){ return dmx_driver_is_installed(DMX_PORT); }
bool     dmx_tx_enabled(void)  { return dmx_driver_is_enabled(DMX_PORT); }
uint32_t dmx_tx_baud(void)     { return dmx_get_baud_rate(DMX_PORT); }

static bool dmx_install_driver(void)
{
    dmx_config_t cfg = DMX_CONFIG_DEFAULT;
    dmx_personality_t pers[] = { {512, "DMX512"} };
    if (!dmx_driver_install(DMX_PORT, &cfg, pers, 1)) {
        ESP_LOGE(TAG, "install FAILED"); return false;
    }
    // RTS=-1（不用硬件 RTS），EN 由 GPIO 输出拉高维持 HIGH
    dmx_set_pin(DMX_PORT, DMX_TX_PIN, -1, -1);
    return true;
}

static void dmx_task(void *arg)
{
    static uint8_t frame[DMX_PACKET_SIZE];
    ESP_LOGI(TAG, "DMX TX start: TX=%d baud=%lu", DMX_TX_PIN, dmx_get_baud_rate(DMX_PORT));
    int cnt=0, fail=0;
    while (1) {
        dmx_state_copy_frame(frame);
        dmx_write(DMX_PORT, frame, DMX_PACKET_SIZE);
        size_t n = dmx_send(DMX_PORT);
        bool w = dmx_wait_sent(DMX_PORT, DMX_TIMEOUT_TICK);
        bool ok = (n > 0) && w;
        s_last_ok = ok;
        s_frames++;
        if (ok) {
            fail = 0;
        } else if (++fail >= DMX_HEAL_THRESHOLD) {
            // 驱动卡死：重建（delete 会释放 UART/定时器并置 NULL，install 全新初始化）
            fail = 0;
            s_heals++;
            ESP_LOGW(TAG, "DMX stuck %u frames, reinstalling driver (heal #%lu)",
                     (unsigned)s_frames, (unsigned long)s_heals);
            dmx_driver_delete(DMX_PORT);
            if (!dmx_install_driver()) {
                // 重建失败，等 1s 再试，避免忙循环
                vTaskDelay(pdMS_TO_TICKS(1000));
                continue;
            }
            ESP_LOGW(TAG, "driver reinstalled OK");
        }
        // 每200帧打印一次校验
        if (++cnt % 200 == 0) {
            int nz=0; for(int i=1;i<20;i++) if(frame[i]!=0) nz++;
            printf("TX frm=%u nz=%d heal=%lu CH1-10:",(unsigned)s_frames,nz,(unsigned long)s_heals);
            for(int i=1;i<=10;i++) printf(" %d",(int)frame[i]);
            printf("\n");
        }
        vTaskDelay(1);
    }
}

void dmx_start(void)
{
    // SP3485 EN: 由代码输出拉高，常驻发送使能（不用 RTS 自动方向）
    gpio_set_direction(DMX_DE_PIN, GPIO_MODE_OUTPUT);
    gpio_set_level(DMX_DE_PIN, 1);

    if (!dmx_install_driver()) return;
    xTaskCreatePinnedToCore(dmx_task, "dmx", 4096, NULL, 6, NULL, 1);
    ESP_LOGI(TAG, "started: TX=GPIO%d EN=GPIO%d(pull-up)", DMX_TX_PIN, DMX_DE_PIN);
}

void dmx_force_sync(void) {}
