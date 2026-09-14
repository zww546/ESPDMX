#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 通道状态：v6 起为 **1024 通道（2 个宇宙 × 512）**。
// 统一用"全局通道号"寻址：ch 1..512 = 宇宙1，ch 513..1024 = 宇宙2。
// 内部只保存纯通道数据（不再保存起始码），起始码由 dmx.c 取口帧时补上。
//
// 并发模型（v6，配合渲染管线）：
//   生产者A = 渲染任务（core1，10ms）：snapshot → 应用程序层/效果层 → commit
//   生产者B = BLE 任务（core0）：直接写推子值（dmx_state_set_range）
//   消费者 = 每个 DMX 口的输出任务：copy_port_frame
// 全部通过同一把自旋锁保护，snapshot/commit 各只加锁一次。
#define DMX_CHANNELS        1024
#define DMX_UNIVERSES       2
#define DMX_UNIVERSE_SIZE   512

void dmx_state_init(void);

// 设置连续通道段 (start 为 1-based 全局通道 1..1024)。越界自动裁剪。返回是否有变化。
bool dmx_state_set_range(uint16_t start, const uint8_t *values, uint16_t count);

// 全部通道置为同一值（全黑=0 / 全亮=255）。
void dmx_state_set_all(uint8_t v);

// 读/写单通道 (1-based 全局通道)。
uint8_t dmx_state_get(uint16_t ch);
void dmx_state_set(uint16_t ch, uint8_t v);

// ---- 整帧提交（渲染管线用：各加锁一次，避免逐通道进出临界区）----
// out/in 至少 DMX_CHANNELS(1024) 字节
void dmx_state_snapshot(uint8_t *out);
void dmx_state_commit(const uint8_t *in);

// 外部写入版本号：任何 set_range/set_all/set 都会 +1。
// 渲染管线用它做乐观并发控制：snapshot 前记下版本，commit 时若版本已变，
// 说明这期间来了推子写入，本次合成结果已过期 → 放弃提交并重算，
// 否则会把刚到的推子值覆盖掉（表现为"松手后值不对"）。
uint32_t dmx_state_write_version(void);
// 仅当版本仍为 expect 时提交；返回是否提交成功。
bool dmx_state_commit_expect(const uint8_t *in, uint32_t expect);

// ---- 给某个 DMX 口取一帧：out[0]=起始码(0x00), out[1..512]=该宇宙的通道 ----
// out 至少 513 字节；universe: 0 = ch1..512, 1 = ch513..1024
void dmx_state_copy_port_frame(uint8_t *out, uint8_t universe);

#ifdef __cplusplus
}
#endif
