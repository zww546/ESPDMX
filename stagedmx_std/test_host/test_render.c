// 宿主端测试（渲染管线 / 双宇宙 / 程序层）：把真实的 program.c + dmx_state.c + fx.c 编译进来。
//
// 覆盖目标里的验收项：
//   R1 整帧提交的乐观并发：合成期间来了推子写入 → 必须拒绝提交，否则会覆盖掉刚到的值
//   R2 程序层 HTP 合并 + **off-by-one 修复**（通道 ch 必须落在 buf[ch-1]）
//   R3 多程序并行 HTP 取大
//   R4 步进按 time_ms 推进
//   R5 效果优先：被效果占用的通道，程序不覆盖
//   R6 双宇宙输出：U1 取 ch1..512、U2 取 ch513..1024，起始码为 0
//   R7 跨宇宙写入：start=500 count=24 必须跨过 512/513 边界正确落位
#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "dmx_state.h"
#include "program.h"
#include "fx.h"

static int g_fail = 0;
static void check(const char *what, int got, int want)
{
    if (got != want) {
        printf("  FAIL %-44s got=%d want=%d\n", what, got, want);
        g_fail++;
    } else {
        printf("  ok   %-44s = %d\n", what, got);
    }
}

static uint8_t buf[DMX_CHANNELS];

int main(void)
{
    dmx_state_init();
    fx_init();
    program_init();

    printf("R1 乐观并发提交（snapshot→commit 期间来了推子写入）\n");
    {
        uint8_t f[DMX_CHANNELS];
        dmx_state_set(1, 0);
        dmx_state_set(3, 0);

        // 第 1 轮：snapshot 之后、commit 之前来了推子写入 → 必须拒绝
        uint32_t ver = dmx_state_write_version();
        dmx_state_snapshot(f);
        dmx_state_set(3, 99);                       // 合成期间的推子写入
        f[0] = 77;                                  // 本次合成想写 通道1=77
        check("版本变化 → 拒绝提交", dmx_state_commit_expect(f, ver) ? 1 : 0, 0);
        check("被拒绝后 通道1 未被写", dmx_state_get(1), 0);

        // 第 2 轮：按渲染管线的做法重算（重新 snapshot，带上刚到的推子值）→ 提交
        ver = dmx_state_write_version();
        dmx_state_snapshot(f);
        f[0] = 77;
        check("版本一致 → 提交成功", dmx_state_commit_expect(f, ver) ? 1 : 0, 1);
        check("提交后 通道1 = 77", dmx_state_get(1), 77);
        check("推子写入的 通道3 被保留", dmx_state_get(3), 99);
    }

    printf("R2 程序层 HTP 合并 + off-by-one 回归（item.ch=5 → buf[4]）\n");
    {
        dmx_state_set_all(0);
        program_clear(0);
        prog_item_t it[1] = { {5, 200} };
        program_append(0, 1000, it, 1);
        program_play(0, true);
        memset(buf, 0, sizeof(buf));
        program_render(buf);
        check("ch5 落在 buf[4]", buf[4], 200);
        check("没有写早一格 buf[3]", buf[3], 0);
        check("没有写到 buf[5]", buf[5], 0);
    }

    printf("R3 多程序并行 HTP（同通道取大）\n");
    {
        program_clear(1);
        prog_item_t it[1] = { {5, 150} };
        program_append(1, 1000, it, 1);
        program_play(1, true);
        memset(buf, 0, sizeof(buf));
        program_render(buf);
        check("两个程序(200/150) → 200", buf[4], 200);

        program_clear(1);
        prog_item_t it2[1] = { {5, 250} };
        program_append(1, 1000, it2, 1);
        program_play(1, true);                      // ← clear 会停播，必须重新 play
        memset(buf, 0, sizeof(buf));
        program_render(buf);
        check("两个程序(200/250) → 250", buf[4], 250);
        program_clear(1);
    }

    printf("R4 步进推进（每步 100ms = 10 tick）\n");
    {
        program_clear(0);
        prog_item_t a[1] = { {5, 200} };
        prog_item_t b[1] = { {6, 210} };
        program_append(0, 100, a, 1);
        program_append(0, 100, b, 1);
        program_play(0, true);
        memset(buf, 0, sizeof(buf));
        program_render(buf);
        check("第1步：ch5 有值", buf[4], 200);
        check("第1步：ch6 为空", buf[5], 0);
        for (int i = 0; i < 12; i++) {              // 12 × 10ms > 100ms → 切到第2步
            memset(buf, 0, sizeof(buf));
            program_render(buf);
        }
        check("切到第2步：ch6 有值", buf[5], 210);
    }

    printf("R5 效果优先：被效果占用的通道程序不覆盖\n");
    {
        fx_stop_all();
        program_stop_all();
        dmx_state_set_all(0);
        fx_cfg_t c;
        memset(&c, 0, sizeof(c));
        c.fx_id = 2;        // 水平摇动 → 占 pan
        c.pan_ch = 5;
        c.amp16 = 0;        // 幅度 0 → 输出恒等于基底
        c.speed = 0;
        c.count = 1;
        fx_set(0, &c);
        check("fx 占用 ch5", fx_owns_channel(5) ? 1 : 0, 1);
        check("fx 不占用 ch6", fx_owns_channel(6) ? 1 : 0, 0);

        program_clear(0);
        prog_item_t it[1] = { {5, 250} };
        program_append(0, 1000, it, 1);
        program_play(0, true);
        memset(buf, 0, sizeof(buf));
        program_render(buf);
        check("程序没覆盖被效果占用的 ch5", buf[4], 0);
        fx_stop_all();
        program_stop_all();
    }

    printf("R6 双宇宙输出（U1 = ch1..512, U2 = ch513..1024）\n");
    {
        dmx_state_set_all(0);
        dmx_state_set(1, 10);
        dmx_state_set(512, 20);
        dmx_state_set(513, 30);
        dmx_state_set(1024, 40);
        uint8_t frame[513];
        dmx_state_copy_port_frame(frame, 0);
        check("U1 起始码 = 0", frame[0], 0);
        check("U1 frame[1]  = ch1", frame[1], 10);
        check("U1 frame[512]= ch512", frame[512], 20);
        dmx_state_copy_port_frame(frame, 1);
        check("U2 起始码 = 0", frame[0], 0);
        check("U2 frame[1]  = ch513", frame[1], 30);
        check("U2 frame[512]= ch1024", frame[512], 40);
        check("U2 不含 U1 的值(ch1)", frame[1] == 10 ? 1 : 0, 0);
    }

    printf("R7 跨宇宙写入（start=500, count=24 → 500..512 + 513..523）\n");
    {
        dmx_state_set_all(0);
        uint8_t v[24];
        for (int i = 0; i < 24; i++) v[i] = (uint8_t)(100 + i);
        dmx_state_set_range(500, v, 24);
        check("ch500 = 100", dmx_state_get(500), 100);
        check("ch512 = 112 (U1 末)", dmx_state_get(512), 112);
        check("ch513 = 113 (U2 首)", dmx_state_get(513), 113);
        check("ch523 = 123", dmx_state_get(523), 123);
        check("ch524 未被写", dmx_state_get(524), 0);
    }

    printf("R8 每步通道容量（原来 64 条静默截断 → 现在 %d 条）\n", PROG_MAX_ITEMS_STEP);
    {
        program_stop_all();
        program_clear(0);
        // 造 200 条变化（模拟"多台灯一起录"的一步）
        prog_item_t items[200];
        for (int i = 0; i < 200; i++) {
            items[i].ch = (uint16_t)(100 + i);   // 通道 100..299
            items[i].val = (uint8_t)(i % 200 + 1);
        }
        program_append(0, 1000, items, 200);
        check("整步 200 条被接受", program_step_count(0), 1);
        program_play(0, true);
        memset(buf, 0, sizeof(buf));
        program_render(buf);
        check("第 1 条生效 (ch100)", buf[99], 1);
        check("第 64 条之后仍生效 (ch163)", buf[162], 64);
        check("最后一条生效 (ch299)", buf[298], 200);
        program_stop_all();
        program_clear(0);
    }

    printf("\n%s  (failures=%d)\n", g_fail ? "❌ 有失败" : "✅ 全部通过", g_fail);
    return g_fail ? 1 : 0;
}
