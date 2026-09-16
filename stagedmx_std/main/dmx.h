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
//   ③ RDM 的换向窗口是微秒级，必须挪到 ISR/硬件层，任务里切 GPIO 来不及。
//   接线是否正常可用 dmx_loopback_selftest()（BLE `0xA0 0x40 <u>`）自检。
//
// 帧率预算（改时序前先读这段）：
//   一帧 = 513 槽 × 11bit / 250k baud = 22.57ms，加 break(176µs) + MAB(12µs)
//   ≈ 22.76ms ≈ 43.9fps，已贴住 DMX512-A 的 44Hz 上限；
//   dmx.c 输出任务里的 vTaskDelay(1) 把它压到 ~42fps，是承重代码。
void dmx_start(void);

/**
 * 环回自检：暂停 DMX 输出，把收发器切到接收态，往总线补发 8 个已知字节，
 * 再数 RX FIFO 里回来了多少、内容是否一致。
 *
 * 用途：判定单 EN 的 485 模块到底是"EN=DE(常驻接收)"还是"EN 并联 DE+/RE"，
 * 以及 RO→RX 是否接对。测法：把 A/B 短接（可经 120Ω）后调用，全部 8 字节回来即通过。
 *
 * 不阻塞其他任务（内部会先让输出任务让路），但期间本宇宙无 DMX 输出，
 * 所以**不要**在演出中途调用。返回是否通过。
 */
bool dmx_loopback_selftest(uint8_t universe);

#ifdef __cplusplus
}
#endif
