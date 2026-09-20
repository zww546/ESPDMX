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

// ---------------------------------------------------------------------------
// RDM 支持（v8）
//
// RDM 是**双向**协议：控制器发请求、灯具回应答，两者共用同一条 A/B 线。
// 硬件上每个宇宙的 RO 已接回 UART RX（见 pins.h），模块只有一个 EN 脚
// （DE 与 /RE 内部处理：EN 高 = 驱动总线，EN 低 = 接收）。
//
// ⚠ 方向必须交给 UART 硬件的 RTS 来翻：RDM 的换向窗口是**微秒级**，
//   任务里用 gpio_set_level 根本来不及（而且会和驱动的收发时序打架）。
//   实测极性正好匹配，无需反相：
//       IDF: sw_rts=0 → RTS 引脚高 ; sw_rts=1 → 引脚低
//       组件: set_rts(0)=发送态      ; set_rts(1)=接收态
//       模块: EN 高 = 发送           ; EN 低 = 接收
//
// 用法（见 rdm.c）：
//   dmx_output_pause(u);                 // 停掉该口的 DMX 流（RDM 要独占总线）
//   dmx_rdm_mode(u, true);               // 绑定 RX，并把 EN 交给驱动当 RTS
//   ... 跑 RDM 收发 ...
//   dmx_rdm_mode(u, false);              // 解绑 RX，EN 收回自己控制
//   dmx_output_resume(u);
// ---------------------------------------------------------------------------

/** 暂停某宇宙的 DMX 输出（阻塞直到输出任务停在安全点）。universe: 0/1 */
bool dmx_output_pause(uint8_t universe);
/** 恢复某宇宙的 DMX 输出。 */
void dmx_output_resume(uint8_t universe);

/**
 * 切换某宇宙到/离开 RDM 模式。
 * @param on true = 绑定 RX 引脚并把 EN 脚交给驱动（RTS 自动换向）；
 *           false = 解绑 RX，EN 收回由 dmx_transceiver_* 控制。
 */
bool dmx_rdm_mode(uint8_t universe, bool on);

/** 取某宇宙的 DMX 端口号（失败返回 -1）。 */
int dmx_port_of(uint8_t universe);

/** 该宇宙的驱动是否可用。 */
bool dmx_port_ready(uint8_t universe);

/**
 * 取某宇宙的发送统计（供 BLE 状态帧上报，App 状态总览条用）。
 * 任一输出参数可为 NULL。
 */
void dmx_get_stats(uint8_t universe, uint32_t *frames, uint32_t *fails,
                   uint8_t *ok, uint8_t *fps);

#ifdef __cplusplus
}
#endif
