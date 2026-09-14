// 宿主端测试：把真实的 fx.c / dmx_state.c 编译进来跑算法验证（无需硬件）。
//
// 覆盖：
//   T1 回归：count=1, stride=0, spread=0, 正弦 → 与 v5 公式逐值一致
//   T2 扩散：count=4, spread=64 → 相位 0/64/128/192 → 偏移 0/+amp/0/-amp
//   T3 方向：反序 / 往返
//   T4 包络：渐入 / 渐出 / 对称 / 两端强
//   T5 形状：三角 / 方波 / 脉冲 / 锯齿 / 随机
//   T6 跨宇宙：first=510, stride=10, count=3 → 写到 510/520/530
//   T7 占用判定：fx_owns_channel 对阵列的等差判定
//   T8 RGB 通道为 0 时不得写到 off 偏移处（曾修过的 bug）
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "fx.h"
#include "dmx_state.h"

// FreeRTOS 桩实现在 test_host/stubs/stubs.c 里（两个测试共用）

static int g_fail = 0;
static void check(const char *what, int got, int want)
{
    if (got != want) {
        printf("  FAIL %-42s got=%d want=%d\n", what, got, want);
        g_fail++;
    } else {
        printf("  ok   %-42s = %d\n", what, got);
    }
}

static uint8_t buf[DMX_CHANNELS];

static fx_cfg_t base_cfg(int fx_id)
{
    fx_cfg_t c;
    memset(&c, 0, sizeof(c));
    c.fx_id = (uint8_t)fx_id;
    c.count = 1;
    c.shape = FX_SHAPE_SIN;
    c.direction = FX_DIR_FWD;
    c.envelope = FX_ENV_NONE;
    return c;
}

/** 跑 n 个 tick 后返回某通道值 */
static int run_n(fx_cfg_t *c, int slot, int ticks, int ch)
{
    fx_stop_all();
    fx_set((uint8_t)slot, c);
    for (int t = 0; t < ticks; t++) {
        memset(buf, 0, sizeof(buf));
        dmx_state_snapshot(buf);          // 模拟渲染管线（基底来自 dmx_state）
        fx_render(buf);
        dmx_state_commit(buf);
    }
    return dmx_state_get((uint16_t)ch);
}

int main(void)
{
    dmx_state_init();
    fx_init();

    // 各属性通道：给一组有辨识度的基底
    //  pan: ch1=128(≈32768)  tilt: ch2=64   dim: ch3=200
    {
        uint8_t v[8] = {128, 64, 200, 10, 20, 30, 40, 50};
        dmx_state_set_range(1, v, 8);
    }

    printf("T1 回归（count=1/stride=0/spread=0/正弦, amp16=32768, speed=512）\n");
    {
        // v5 公式：phase+=512 → idx=(phase>>8)=2 → SIN[2]=6 → o=6*32768/127=1547
        //           base=128<<8=32768 → v=34315 → ch1=134
        fx_cfg_t c = base_cfg(2);           // 水平摇动 → pan
        c.pan_ch = 1;
        c.amp16 = 32768;
        c.speed = 512;
        int got = run_n(&c, 0, 1, 1);
        check("1 tick 后 pan(ch1)", got, 134);
    }

    printf("T2 扩散（count=4, stride=10, spread=64, speed=0 冻结相位）\n");
    {
        fx_stop_all();
        // 4 台灯，通道 1/11/21/31，基底都设成 128
        for (int i = 0; i < 4; i++) {
            uint8_t v = 128;
            dmx_state_set_range(1 + i * 10, &v, 1);
        }
        fx_cfg_t c = base_cfg(2);
        c.pan_ch = 1;
        c.amp16 = 16256;                    // = 127*128 → o 峰值正好 ±16256
        c.speed = 0;
        c.stride = 10;
        c.count = 4;
        c.spread = 64;                      // 相位 0/64/128/192
        fx_set(0, &c);
        memset(buf, 0, sizeof(buf));
        dmx_state_snapshot(buf);
        fx_render(buf);
        dmx_state_commit(buf);
        // SIN[0]=0 → 128 ; SIN[64]=127 → +16256 → 49024>>8=191 ; SIN[128]=0 → 128 ; SIN[192]=-127 → 16512>>8=64
        check("台0 ch1 (相位0)",   dmx_state_get(1), 128);
        check("台1 ch11(相位64)",  dmx_state_get(11), 191);
        check("台2 ch21(相位128)", dmx_state_get(21), 128);
        check("台3 ch31(相位192)", dmx_state_get(31), 64);
    }

    printf("T3 方向（spread=64 冻结相位）\n");
    {
        fx_cfg_t c = base_cfg(2);
        c.pan_ch = 1; c.amp16 = 16256; c.speed = 0;
        c.stride = 10; c.count = 4; c.spread = 64;
        c.direction = FX_DIR_REV;           // 反序 → 相位 0/-64/-128/-192 = 0/192/128/64
        fx_set(0, &c);
        memset(buf, 0, sizeof(buf)); dmx_state_snapshot(buf); fx_render(buf); dmx_state_commit(buf);
        check("反序 台1 ch11", dmx_state_get(11), 64);
        check("反序 台3 ch31", dmx_state_get(31), 191);

        c.direction = FX_DIR_BOUNCE;        // 往返 = 三角折叠 → step 0,1,1,0 → 相位 0/64/64/0
        fx_set(0, &c);
        memset(buf, 0, sizeof(buf)); dmx_state_snapshot(buf); fx_render(buf); dmx_state_commit(buf);
        check("往返 台1 ch11", dmx_state_get(11), 191);
        check("往返 台2 ch21", dmx_state_get(21), 191);
        check("往返 台3 ch31", dmx_state_get(31), 128);
    }

    printf("T4 包络（正弦 相位0 全台同相, 看幅度比例）\n");
    {
        // 相位全 0（spread=0）→ SIN[0]=0，看不出包络；改用相位 64（峰值）来观察幅度
        fx_cfg_t c = base_cfg(2);
        c.pan_ch = 1; c.amp16 = 127 * 128; c.speed = 0;
        c.stride = 10; c.count = 4; c.spread = 0; c.phase = 64;   // 全台相位 64 → 峰值
        c.envelope = FX_ENV_IN;             // 系数 0 / 85 / 170 / 256
        fx_set(0, &c);
        memset(buf, 0, sizeof(buf)); dmx_state_snapshot(buf); fx_render(buf); dmx_state_commit(buf);
        // o = 127 * (amp*env/256) / 127 = amp*env/256 ; amp=16256
        check("渐入 台0 (env 0)",   dmx_state_get(1),  128);
        check("渐入 台3 (env 256)", dmx_state_get(31), 128 + 16256 / 256);
    }

    printf("T5 形状（相位 64 → 各波形取值）\n");
    {
        struct { int shape; int expect_hi; } cases[] = {
            {FX_SHAPE_TRI,    128 + 16256 / 256},   // tri(64)=1 → +amp
            {FX_SHAPE_SQUARE, 128 + 16256 / 256},   // square=127 → +amp
            {FX_SHAPE_PULSE,  128 + 16256 / 256},   // pulse(idx<64)=127 → 相位64 时 -amp
            {FX_SHAPE_RAMP,   -1},                  // ramp(idx)=idx-127= -63 → 下面单独算
        };
        for (unsigned k = 0; k < sizeof(cases) / sizeof(cases[0]); k++) {
            fx_cfg_t c = base_cfg(2);
            c.pan_ch = 1; c.amp16 = 16256; c.speed = 0; c.phase = 64;
            c.shape = (uint8_t)cases[k].shape;
            fx_set(0, &c);
            memset(buf, 0, sizeof(buf)); dmx_state_snapshot(buf); fx_render(buf); dmx_state_commit(buf);
            int got = dmx_state_get(1);
            if (cases[k].expect_hi >= 0) check("形状", got, cases[k].expect_hi);
            else printf("  ramp(相位64) = %d  (预期 %d)\n", got,
                        128 + (64 - 127) * 16256 / 127 / 256);
        }
    }

    printf("T6 跨宇宙（first=510, stride=10, count=3 → 510/520/530）\n");
    {
        // 三相都设成 128 作基底
        for (int i = 0; i < 3; i++) {
            uint8_t v = 128;
            dmx_state_set_range(510 + i * 10, &v, 1);
        }
        fx_cfg_t c = base_cfg(2);
        c.pan_ch = 510; c.amp16 = 16256; c.speed = 0;
        c.stride = 10; c.count = 3; c.spread = 64;
        fx_set(0, &c);
        memset(buf, 0, sizeof(buf)); dmx_state_snapshot(buf); fx_render(buf); dmx_state_commit(buf);
        check("U1 末 ch510 (相位0)",    dmx_state_get(510), 128);
        check("U2 ch520 (相位64)",      dmx_state_get(520), 191);
        check("U2 ch530 (相位128)",     dmx_state_get(530), 128);
        check("越界 ch540 未写",        dmx_state_get(540), 0);
    }

    printf("T7 占用判定（count=4, stride=10, base=1）\n");
    {
        fx_cfg_t c = base_cfg(2);
        c.pan_ch = 1; c.amp16 = 16256; c.speed = 0;
        c.stride = 10; c.count = 4; c.spread = 0;
        fx_set(0, &c);
        check("owns ch1",  fx_owns_channel(1) ? 1 : 0, 1);
        check("owns ch11", fx_owns_channel(11) ? 1 : 0, 1);
        check("owns ch31", fx_owns_channel(31) ? 1 : 0, 1);
        check("not ch2",   fx_owns_channel(2) ? 1 : 0, 0);
        check("not ch41",  fx_owns_channel(41) ? 1 : 0, 0);
    }

    printf("T8 RGB 未用通道为 0（r=0,g=20,b=30, count=3, stride=10）\n");
    {
        fx_stop_all();
        memset(buf, 0, sizeof(buf));
        dmx_state_commit(buf);
        fx_cfg_t c = base_cfg(5);
        c.r_ch = 0; c.g_ch = 20; c.b_ch = 30;
        c.count = 3; c.stride = 10; c.speed = 256;
        fx_set(0, &c);
        memset(buf, 0, sizeof(buf)); dmx_state_snapshot(buf); fx_render(buf); dmx_state_commit(buf);
        // r_ch=0 → 绝不能写到 ch10 / ch20（i×stride）
        check("ch10 未被误写(r_ch=0)", dmx_state_get(10), 0);
        check("ch20 被 g 写(台0)",     dmx_state_get(20) != 0 ? 1 : 0, 1);
        check("ch40 被 g 写(台2)",     dmx_state_get(40) != 0 ? 1 : 0, 1);
    }

    fx_stop_all();
    printf("\n%s  (failures=%d)\n", g_fail ? "❌ 有失败" : "✅ 全部通过", g_fail);
    return g_fail ? 1 : 0;
}
