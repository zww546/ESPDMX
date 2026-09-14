// 宿主端测试（协议契约）：直接验证 0x20 效果帧的解析。
//
// 为什么单独测这个：帧的字段偏移一旦写错，不会崩、不会报错，只会让"效果参数全乱"，
// 是最难查的一类问题。而且这里还承载着一个对外承诺 —— **旧 App(57 字节) 必须能继续用
// 新固件**（用户只刷固件不升级 App 是常态）。
//
//   P1 v5 帧（57B）：16 个通道字段 / amp / speed / 8 切割片 / 切割旋转 全部落位正确，
//                    且阵列参数取默认值（= v5 行为）
//   P2 v6 帧（65B）：追加的 8 字节阵列参数落位正确
//   P3 兼容矩阵：len=56 拒绝；57 接受；64 接受(走默认)；65 接受(带阵列)
//   P4 非法帧：slot 越界 / fx_id=0 / fx_id=14 一律拒绝
//   P5 越界参数被夹紧：count=0→1、count=200→64、shape/direction/envelope 越界→默认
#include <stdio.h>
#include <string.h>
#include "fx.h"
#include "fx_proto.h"

static int g_fail = 0;
static void check(const char *what, int got, int want)
{
    if (got != want) {
        printf("  FAIL %-46s got=%d want=%d\n", what, got, want);
        g_fail++;
    } else {
        printf("  ok   %-46s = %d\n", what, got);
    }
}

static void put16(uint8_t *d, int off, int v)
{
    d[off] = (uint8_t)((v >> 8) & 0xFF);
    d[off + 1] = (uint8_t)(v & 0xFF);
}

/** 按 App 的 encodeFxSet 字节顺序造一个 v5(57B) 帧 */
static uint16_t build_v5(uint8_t *d, uint8_t slot, uint8_t fx_id)
{
    memset(d, 0, 65);
    d[0] = 0x20; d[1] = slot; d[2] = fx_id;
    int i = 3;
    const int chans[16] = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
    for (int k = 0; k < 16; k++) { put16(d, i, chans[k]); i += 2; }
    put16(d, i, 32768); i += 2;          // amp16
    put16(d, i, 512);   i += 2;          // speed
    for (int b = 0; b < 8; b++) { put16(d, i, 100 + b); i += 2; }   // blade[0..7]
    put16(d, i, 21); i += 2;             // shaperRot
    return 57;
}

int main(void)
{
    uint8_t d[65];
    fx_cfg_t c;
    uint8_t slot;

    printf("P1 v5 帧（57B）：字段落位 + 阵列参数取默认\n");
    {
        uint16_t len = build_v5(d, 2, 1);
        check("解析成功", fx_cfg_parse(d, len, &slot, &c) ? 1 : 0, 1);
        check("slot", slot, 2);
        check("fx_id", c.fx_id, 1);
        check("pan_ch",   c.pan_ch, 1);
        check("pan_fine", c.pan_fine_ch, 2);
        check("tilt_ch",  c.tilt_ch, 3);
        check("tilt_fine",c.tilt_fine_ch, 4);
        check("dim_ch",   c.dim_ch, 5);
        check("dim_fine", c.dim_fine_ch, 6);
        check("r,g,b",    c.r_ch * 10000 + c.g_ch * 100 + c.b_ch, 7 * 10000 + 8 * 100 + 9);
        check("zoom/focus", c.zoom_ch * 100 + c.focus_ch, 10 * 100 + 12);
        check("color/gobo/goboRot", c.color_ch * 10000 + c.gobo_ch * 100 + c.gobo_rot_ch,
              14 * 10000 + 15 * 100 + 16);
        check("amp16", c.amp16, 32768);
        check("speed", c.speed, 512);
        check("blade[0]", c.blade_ch[0], 100);
        check("blade[7]", c.blade_ch[7], 107);
        check("shaperRot", c.shaper_rot_ch, 21);
        // 阵列参数必须是默认（= v5 行为）
        check("stride 默认 0", c.stride, 0);
        check("count  默认 1", c.count, 1);
        check("spread 默认 0", c.spread, 0);
        check("shape  默认 正弦", c.shape, FX_SHAPE_SIN);
        check("direction 默认 正序", c.direction, FX_DIR_FWD);
        check("phase  默认 0", c.phase, 0);
        check("envelope 默认 无", c.envelope, FX_ENV_NONE);
    }

    printf("P2 v6 帧（65B）：阵列参数落位\n");
    {
        uint16_t len = build_v5(d, 5, 13);      // 用切割循环（fx_id=13）
        (void)len;                              // 下面统一用长度 65 显式传入
        d[57] = 0x00; d[58] = 18;               // stride = 18
        d[59] = 12;                             // count
        d[60] = 64;                             // spread
        d[61] = FX_SHAPE_RAMP;                  // shape
        d[62] = FX_DIR_BOUNCE;                  // direction
        d[63] = 128;                            // phase
        d[64] = FX_ENV_MID;                     // envelope
        check("解析成功", fx_cfg_parse(d, 65, &slot, &c) ? 1 : 0, 1);
        check("slot", slot, 5);
        check("fx_id=13", c.fx_id, 13);
        check("stride=18", c.stride, 18);
        check("count=12", c.count, 12);
        check("spread=64", c.spread, 64);
        check("shape=锯齿", c.shape, FX_SHAPE_RAMP);
        check("direction=往返", c.direction, FX_DIR_BOUNCE);
        check("phase=128", c.phase, 128);
        check("envelope=对称", c.envelope, FX_ENV_MID);
        check("基础字段不受影响(pan)", c.pan_ch, 1);
    }

    printf("P3 兼容矩阵（长度分派）\n");
    {
        build_v5(d, 0, 2);        check("len=56 → 拒绝（不完整帧）", fx_cfg_parse(d, 56, &slot, &c) ? 1 : 0, 0);
        check("len=57 → 接受（v5）",        fx_cfg_parse(d, 57, &slot, &c) ? 1 : 0, 1);
        d[59] = 9;
        check("len=64 → 接受但用默认",      fx_cfg_parse(d, 64, &slot, &c) ? 1 : 0, 1);
        check("  count 仍为默认 1",         c.count, 1);
        check("len=65 → 接受且带阵列",      fx_cfg_parse(d, 65, &slot, &c) ? 1 : 0, 1);
        check("  count = 9",                c.count, 9);
    }

    printf("P4 非法帧一律拒绝\n");
    {
        build_v5(d, FX_MAX_COUNT, 1);
        check("slot = FX_MAX_COUNT → 拒绝", fx_cfg_parse(d, 57, &slot, &c) ? 1 : 0, 0);
        build_v5(d, 0, 0);
        check("fx_id = 0 → 拒绝",           fx_cfg_parse(d, 57, &slot, &c) ? 1 : 0, 0);
        build_v5(d, 0, 14);
        check("fx_id = 14 → 拒绝",          fx_cfg_parse(d, 57, &slot, &c) ? 1 : 0, 0);
        build_v5(d, 0, 13);
        check("fx_id = 13 → 接受",          fx_cfg_parse(d, 57, &slot, &c) ? 1 : 0, 1);
    }

    printf("P5 越界参数被夹紧\n");
    {
        build_v5(d, 0, 2);
        d[59] = 0;                 // count = 0
        fx_cfg_parse(d, 65, &slot, &c);
        check("count=0 → 1", c.count, 1);

        d[59] = 200;               // count 超上限
        fx_cfg_parse(d, 65, &slot, &c);
        check("count=200 → FX_MAX_TARGETS", c.count, FX_MAX_TARGETS);

        d[59] = 4;
        d[61] = 99; d[62] = 99; d[64] = 99;   // shape/direction/envelope 越界
        d[60] = 255; d[63] = 255;             // spread/phase 合法上限
        fx_cfg_parse(d, 65, &slot, &c);
        check("shape 越界 → 正弦", c.shape, FX_SHAPE_SIN);
        check("direction 越界 → 正序", c.direction, FX_DIR_FWD);
        check("envelope 越界 → 无", c.envelope, FX_ENV_NONE);
        check("spread=255 保留", c.spread, 255);
        check("phase=255 保留", c.phase, 255);
    }

    printf("\n%s  (failures=%d)\n", g_fail ? "❌ 有失败" : "✅ 全部通过", g_fail);
    return g_fail ? 1 : 0;
}
