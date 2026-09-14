#include "render.h"
#include "dmx_state.h"
#include "fx.h"
#include "program.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"

static const char *TAG = "render";

#define RENDER_TICK_MS 10

static void render_task(void *arg)
{
    // static：1024 字节不占任务栈
    static uint8_t frame[DMX_CHANNELS];
    ESP_LOGI(TAG, "render pipeline started on CPU%d (%dms tick, %d channels)",
             xPortGetCoreID(), RENDER_TICK_MS, DMX_CHANNELS);
    while (1) {
        // 乐观并发：若合成期间来了推子写入（版本号变了），放弃本次结果重算，
        // 否则会把刚到的推子值覆盖掉（表现为"松手后值不对"）。
        // 重算会让程序步进/效果相位多走一拍 —— 只在"推子写入恰好落在合成的微秒级窗口内"
        // 时发生，概率极低，且只影响一拍节拍，可接受。
        uint32_t ver;
        do {
            ver = dmx_state_write_version();
            dmx_state_snapshot(frame);   // 1) 当前帧（推子值 / 上一次合成结果）
            program_render(frame);       // 2) 程序层：稀疏 HTP 合并 + 步进推进
            fx_render(frame);            // 3) 效果层：阵列波形叠加
        } while (!dmx_state_commit_expect(frame, ver));   // 4) 整帧提交（版本一致才提交）
        vTaskDelay(pdMS_TO_TICKS(RENDER_TICK_MS));
    }
}

void render_start_task(void)
{
    fx_init();
    program_init();
    // core 1：与 DMX 输出任务、DMX 中断同核，远离 core 0 的射频
    xTaskCreatePinnedToCore(render_task, "render", 4096, NULL, 5, NULL, 1);
}
