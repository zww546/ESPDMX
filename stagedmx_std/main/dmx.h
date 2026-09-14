#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// DMX 输出：v6 起支持 **2 个宇宙**（2 个 UART 口，各自独立任务、并行输出）。
// universe 参数：0 → 全局通道 1..512，1 → 全局通道 513..1024
//
// 帧率预算（改时序前先读这段）：
//   一帧 = 513 槽 × 11bit / 250k baud = 22.57ms，加 break(176µs) + MAB(12µs)
//   ≈ 22.76ms ≈ 43.9fps，已贴住 DMX512-A 的 44Hz 上限；
//   dmx.c 输出任务里的 vTaskDelay(1) 把它压到 ~42fps，是承重代码。
void dmx_start(void);

#ifdef __cplusplus
}
#endif
