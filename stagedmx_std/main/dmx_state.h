#pragma once
#include <stdint.h>
#include <stdbool.h>

#define DMX_CHANNELS 512

#ifdef __cplusplus
extern "C" {
#endif

// 512 通道共享状态。内部保存 [0]=起始码(0x00) + [1..512]=通道值。
void dmx_state_init(void);

// 设置连续通道段 (start 为 1-based)。越界自动裁剪。返回是否有变化。
bool dmx_state_set_range(uint16_t start, const uint8_t *values, uint16_t count);

// 全部通道置为同一值（全黑=0 / 全亮=255）。
void dmx_state_set_all(uint8_t v);

// 整帧写入 512 通道（程序播放逐步应用）。vals 长度 512。
void dmx_state_set_frame(const uint8_t *vals);

// 取一份完整 DMX 帧拷贝：out[0]=0x00 起始码, out[1..512]=通道。out 至少 513 字节。
void dmx_state_copy_frame(uint8_t *out /*>=513*/);

// 读单通道 (1-based)。
uint8_t dmx_state_get(uint16_t ch);

// 写单通道 (1-based)。
void dmx_state_set(uint16_t ch, uint8_t v);

// 自上次清零以来是否有过改动（供 UI 判断是否重绘）。
bool dmx_state_take_dirty(void);

#ifdef __cplusplus
}
#endif
