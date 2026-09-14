#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "fx.h"

#ifdef __cplusplus
extern "C" {
#endif

// 解析 0x20「配置并启动效果」帧（App → 固件）。
//
// 帧长：
//   v5 = 57 字节
//   v6 = 65 字节（在 57 之后追加 stride/count/spread/shape/direction/phase/envelope）
//
// 独立成函数（而不是写在 ble_dmx.c 的 switch 里）是为了能在宿主端测试里
// 直接验证**协议契约**：新旧 App 双向兼容、每个字段的字节偏移是否正确。
// 这类错误一旦发生是"效果参数全乱"，但不会崩，最难查。
//
// @return 帧是否有效（长度、slot、fx_id 合法）。合法时写出 slot 与 cfg。
bool fx_cfg_parse(const uint8_t *d, uint16_t len, uint8_t *slot_out, fx_cfg_t *out);

#ifdef __cplusplus
}
#endif
