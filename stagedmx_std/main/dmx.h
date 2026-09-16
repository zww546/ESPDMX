#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// DMX 输出：v6 起支持 **2 个宇宙**（2 个 UART 口，各自独立任务、并行输出）。
// universe 参数：0 → 全局通道 1..512，1 → 全局通道 513..1024
//
// 硬件（v7）：每个宇宙一套完整双向收发接口 —— DI/RO/DE/RE 各一个 GPIO，
//   RX 已绑定到 UART（v6 及以前 rx=-1、RO 悬空、单个共用 EN 常驻拉高 = 纯发送端）。
//   RS-485 半双工：发送任务在驱动总线前切 DE=1/RE=1，发完立刻切回接收态。
//   方向由 dmx.c 的 dmx_transceiver_{tx,rx}() 成对驱动，**不用** esp_dmx 的
//   rts_pin（该组件不会自动换向，详见 dmx.c 里的说明）。引脚定义见 pins.h。
//
// ⚠ 接收路径当前"只接线、未消费"：RX 会进 UART FIFO，但固件还没有解析收到的
//   DMX/RDM 的代码。要做 DMX 输入或 RDM，下一步是加一个接收任务 + 把方向控制
//   挪到 ISR/硬件层（换向窗口是微秒级，任务里切不够快）。
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
