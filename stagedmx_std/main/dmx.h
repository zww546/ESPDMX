#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// DMX 输出：v6 起支持 **2 个宇宙**（2 个 UART 口，各自独立任务、并行输出）。
// universe 参数：0 → 全局通道 1..512，1 → 全局通道 513..1024
//
// 硬件（v7）：每个宇宙一套完整双向收发接口 —— DI/RO/EN 各一个 GPIO，
//   但**当前接收是关闭的**（dmx.c: DMX_RX_ENABLE = 0）：
//     · UART 不绑定 RX 引脚（dmx_set_pin 传 rx=-1）；
//     · EN 常驻发送态，帧间不切到接收。
//   与 v6 的差别仅在硬件接线（RO 已接、EN 独立不共用）与代码结构（方向控制函数化）。
//   原因与恢复方式见 dmx.c 里 DMX_RX_ENABLE 的注释。
//
// ⚠ 接收通路目前"只接线、未消费"：要做 DMX 输入或 RDM，需要
//   ① 把 DMX_RX_ENABLE 改成 1；② 加接收任务解析 break+512 通道；
//   ③ 换向交给 UART 硬件的 RTS（rts_pin 传 EN 脚），不要用任务里的 GPIO 切换 ——
//      RDM 的换向窗口是微秒级，任务里来不及。
//
// 帧率预算（改时序前先读这段）：
//   一帧 = 513 槽 × 11bit / 250k baud = 22.57ms，加 break(176µs) + MAB(12µs)
//   ≈ 22.76ms ≈ 43.9fps，已贴住 DMX512-A 的 44Hz 上限；
//   dmx.c 输出任务里的 vTaskDelay(1) 把它压到 ~42fps，是承重代码。
void dmx_start(void);

#ifdef __cplusplus
}
#endif
