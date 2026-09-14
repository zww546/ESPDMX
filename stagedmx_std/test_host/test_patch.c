// 宿主端测试：双宇宙 patch 规则（A/B 两条通道，各自编号 1-512）。
//
// 规则：
//   A = 宇宙1（UART1 口），地址 1~512
//   B = 宇宙2（UART2 口），地址 1~512
//   一台灯必须**完整落在所选通道内**：起始 + 通道数 - 1 <= 512，否则报错（不自动挪）
//   重叠检查**只在同一个通道内**做（A 和 B 是两条独立的线，地址可以重复）
//
// 为什么单独测：这是纯边界逻辑，错一位就会把灯 patch 到"半条线"上，
// 现场表现是"灯只动一半属性"，极难排查。
#include <stdio.h>

#define U_SIZE 512

/** 判定某台灯能否放下；返回 0 = 放不下 */
static int fits(int addr, int ch)
{
    if (ch < 1 || ch > U_SIZE) return 0;
    if (addr < 1 || addr > U_SIZE) return 0;
    return (addr + ch - 1 <= U_SIZE) ? 1 : 0;
}

/** 全局通道换算：A(1) → 1..512，B(2) → 513..1024 */
static int global_addr(int universe, int addr) { return (universe - 1) * U_SIZE + addr; }

static int g_fail = 0;
static void ck(const char *what, int got, int want)
{
    if (got != want) { printf("  FAIL %-46s got=%d want=%d\n", what, got, want); g_fail++; }
    else             { printf("  ok   %-46s = %d\n", what, got); }
}

int main(void)
{
    printf("P1 放得下（不动）\n");
    ck("A addr 1,   ch 20",  fits(1, 20), 1);
    ck("A addr 493, ch 20",  fits(493, 20), 1);     // 493+19 = 512，刚好
    ck("A addr 500, ch 13",  fits(500, 13), 1);     // 500+12 = 512，刚好（你之前的例子）
    ck("B addr 1,   ch 39",  fits(1, 39), 1);       // B 也是从 1 开始
    ck("B addr 474, ch 39",  fits(474, 39), 1);     // 474+38 = 512

    printf("P2 放不下 → 必须报错（不再自动吸附）\n");
    ck("A addr 500, ch 40",  fits(500, 40), 0);     // 500+39 = 539 > 512
    ck("A addr 493, ch 21",  fits(493, 21), 0);     // 493+20 = 513 > 512
    ck("B addr 500, ch 40",  fits(500, 40), 0);     // B 同样只有 512 个地址
    ck("A addr 513, ch 1",   fits(513, 1), 0);      // 地址本身就越界（A 只到 512）

    printf("P3 全局通道换算\n");
    ck("A(1) @1   → 1",     global_addr(1, 1), 1);
    ck("A(1) @512 → 512",   global_addr(1, 512), 512);
    ck("B(2) @1   → 513",   global_addr(2, 1), 513);
    ck("B(2) @512 → 1024",  global_addr(2, 512), 1024);
    ck("A@500 与 B@1 不连续", global_addr(2, 1) - global_addr(1, 500), 13);

    printf("P4 同一灯型在 A / B 上互不冲突（两条独立的线）\n");
    {
        // A@1 占 1~20；B@1 也占 1~20 —— 不重叠（因为在不同通道）
        int a1 = 1, a2 = 20, b1 = 1, b2 = 20;
        int overlapSameUniverse = (b1 <= a2 && a1 <= b2);
        ck("同通道内会判重叠（用于同宇宙比较）", overlapSameUniverse, 1);
        ck("换算到全局后不重叠", (global_addr(1, 1) <= global_addr(2, 20) &&
                                  global_addr(2, 1) <= global_addr(1, 20)) ? 1 : 0, 0);
    }

    printf("P5 极端参数\n");
    ck("ch 512, addr 1",  fits(1, 512), 1);         // 整条通道
    ck("ch 513, addr 1",  fits(1, 513), 0);         // 超单宇宙，任何情况都放不下
    ck("ch 0",            fits(100, 0), 0);
    ck("addr 0",          fits(0, 20), 0);

    printf("\n%s  (failures=%d)\n", g_fail ? "❌ 有失败" : "✅ 全部通过", g_fail);
    return g_fail ? 1 : 0;
}
