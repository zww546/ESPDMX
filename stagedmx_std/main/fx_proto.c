#include "fx_proto.h"
#include <string.h>

// 0x20 帧布局（大端，与 App 的 DmxProtocol.encodeFxSet 一一对应）：
//   [0]  0x20        [1] slot        [2] fx_id
//   [3..34]   16 × u16：pan, panF, tilt, tiltF, dim, dimF, r, g, b,
//                       zoom, zoomF, focus, focusF, color, gobo, goboRot
//   [35..38]  2 × u16：amp16, speed
//   [39..54]  8 × u16：blade[0..7]
//   [55..56]  1 × u16：shaperRot
//   --- 到这里 57 字节（v5）---
//   [57..58]  u16 stride
//   [59]      count
//   [60]      spread
//   [61]      shape
//   [62]      direction
//   [63]      phase
//   [64]      envelope        （共 65 字节，v6）
bool fx_cfg_parse(const uint8_t *d, uint16_t len, uint8_t *slot_out, fx_cfg_t *out)
{
    if (!d || !out || len < 57) return false;

    uint8_t slot = d[1];
    if (slot >= FX_MAX_COUNT) return false;

    uint8_t fx_id = d[2];
    if (fx_id < 1 || fx_id > 13) return false;

    fx_cfg_t c;
    memset(&c, 0, sizeof(c));
    c.fx_id = fx_id;

    int i = 3;
    #define RD16() (((uint16_t)d[i] << 8) | d[i + 1]); i += 2
    c.pan_ch        = RD16(); c.pan_fine_ch   = RD16();
    c.tilt_ch       = RD16(); c.tilt_fine_ch  = RD16();
    c.dim_ch        = RD16(); c.dim_fine_ch   = RD16();
    c.r_ch          = RD16(); c.g_ch          = RD16();
    c.b_ch          = RD16();
    c.zoom_ch       = RD16(); c.zoom_fine_ch  = RD16();
    c.focus_ch      = RD16(); c.focus_fine_ch = RD16();
    c.color_ch      = RD16();
    c.gobo_ch       = RD16();
    c.gobo_rot_ch   = RD16();
    c.amp16         = RD16();
    c.speed         = RD16();
    // ⚠ 必须加花括号：RD16() 展开成两条语句（读值 + i += 2），
    //   写成 `for (...) x = RD16();` 会让 i += 2 落在循环外，
    //   导致 8 个切割片全读到同一个通道、后续字段整体错位。
    for (int b = 0; b < FX_BLADE_COUNT; b++) {
        c.blade_ch[b] = RD16();
    }
    c.shaper_rot_ch = RD16();
    #undef RD16

    // v6 阵列参数；旧 App(57B) 走默认值 → 与 v5 行为完全一致
    if (len >= 65) {
        c.stride    = (uint16_t)((d[57] << 8) | d[58]);
        c.count     = d[59];
        c.spread    = d[60];
        c.shape     = d[61];
        c.direction = d[62];
        c.phase     = d[63];
        c.envelope  = d[64];
        // 越界值一律夹到合法范围，避免把非法参数带进渲染层
        if (c.shape > FX_SHAPE_RAMP)   c.shape = FX_SHAPE_SIN;
        if (c.direction > FX_DIR_BOUNCE) c.direction = FX_DIR_FWD;
        if (c.envelope > FX_ENV_EDGE)  c.envelope = FX_ENV_NONE;
    } else {
        c.stride    = 0;
        c.count     = 1;
        c.spread    = 0;
        c.shape     = FX_SHAPE_SIN;
        c.direction = FX_DIR_FWD;
        c.phase     = 0;
        c.envelope  = FX_ENV_NONE;
    }
    if (c.count < 1) c.count = 1;
    if (c.count > FX_MAX_TARGETS) c.count = FX_MAX_TARGETS;

    *slot_out = slot;
    *out = c;
    return true;
}
