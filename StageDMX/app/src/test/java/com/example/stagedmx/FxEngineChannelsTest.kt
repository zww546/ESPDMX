package com.example.stagedmx

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FxEngine] 通道映射的单元测试。
 *
 * 为什么要测：`applyTargets` 里的探测全是 `chFine(...)?.let { ... }` ——
 * **找不到就保留旧值**。于是"换灯""取消选中"这类操作如果不显式清一遍，
 * 上一次那台灯的通道映射会原样留着：
 *  - 推子页的「切割」分组不消失（bladeCh 还是上一台灯的）；
 *  - 效果会继续往上一台灯的通道上写。
 *
 * 这两条都是真机上出现过的 bug，而这段代码是**纯 Kotlin**（FxEngine 没有任何 import），
 * 所以完全可以钉死，不用靠手点。
 */
class FxEngineChannelsTest {

    private fun ch(number: Int, attr: String, name: String = attr) =
        FixtureChannel(number = number, name = name, originalName = name,
            attribute = attr, defaultValue = 0, highlightValue = 255)

    /** 一台有切割片的灯：切割片 12..19 + 切割旋转 20。 */
    private val withBlades = FixtureDef(
        id = "with_blades", name = "有切割", manufacturer = "M", mode = "std",
        channelCount = 20,
        channels = listOf(ch(1, "PAN"), ch(2, "TILT"), ch(3, "DIM")) +
            (0 until 8).map { ch(12 + it, "BLADE${it + 1}") } +
            listOf(ch(20, "SHAPER ROT")),
    )

    /** 一台没有切割片、也没有 pan/tilt 的灯。 */
    private val withoutBlades = FixtureDef(
        id = "plain", name = "普通", manufacturer = "M", mode = "std",
        channelCount = 4, channels = listOf(ch(1, "DIM")),
    )

    @After
    fun tearDown() {
        // 单例对象，测试之间必须互相清干净，否则顺序会互相污染
        FxEngine.clearChannels()
    }

    @Test
    fun `detects blade channels and shaper rotation`() {
        FxEngine.applyFixture(withBlades, 1)
        assertTrue("应当认出 8 个切割片通道", FxEngine.bladeCh.all { it != 0 })
        assertEquals(12, FxEngine.bladeCh[0])
        assertEquals(20, FxEngine.shaperRotCh)
    }

    /**
     * 用户报的 bug：**取消选中灯具后「切割」分组不消失**。
     * 根因就是这条 —— 没有灯型时不探也不清，bladeCh 留着上一台灯的值。
     */
    @Test
    fun `clearChannels wipes the previous fixture mapping`() {
        FxEngine.applyFixture(withBlades, 1)
        assertTrue(FxEngine.bladeCh.all { it != 0 })

        FxEngine.clearChannels()

        assertTrue("bladeCh 必须被清掉，否则切割分组不会消失",
            FxEngine.bladeCh.all { it == 0 })
        assertEquals(0, FxEngine.shaperRotCh)
        // 其它映射也一并清掉：留旧值会让效果继续往上一台灯的通道上写
        assertEquals(0, FxEngine.panCh)
        assertEquals(0, FxEngine.dimCh)
        assertEquals(0, FxEngine.colorCh)
        assertTrue(FxEngine.ptSpeedCh == null)
    }

    /** 换到一台**没有切割片**的灯时，上一次的 bladeCh 不能留。 */
    @Test
    fun `switching to a fixture without blades clears them`() {
        FxEngine.applyFixture(withBlades, 1)
        assertTrue(FxEngine.bladeCh.all { it != 0 })

        FxEngine.applyFixture(withoutBlades, 1)

        assertTrue("换灯后 bladeCh 必须清空（探测本身就带清零）",
            FxEngine.bladeCh.all { it == 0 })
        assertEquals(0, FxEngine.shaperRotCh)
        assertEquals("新灯有 DIM 通道，应当认出来", 1, FxEngine.dimCh)
        assertEquals("新灯没有 PAN，不能留着上一台灯的 1 号通道当 PAN",
            0, FxEngine.panCh)
    }

    /** 真实地址 = 起始地址 + 灯内通道号 − 1；通道号 0 表示"不存在"，必须是 0。 */
    @Test
    fun `real channel of zero stays zero`() {
        FxEngine.applyFixture(withoutBlades, 100)
        assertEquals("DIM 在灯内 1 号 → 全局 100", 1, FxEngine.dimCh)
        // 通过 effect set 才能看到 real() 的结果，这里只断言"不存在"的语义在字段上成立
        FxEngine.clearChannels()
        assertEquals("清掉之后通道号是 0（= 不写这个通道）", 0, FxEngine.dimCh)
    }

    @Test
    fun `applyFixture keeps detecting on repeated calls`() {
        FxEngine.applyFixture(withBlades, 1)
        val first = FxEngine.bladeCh.copyOf()
        FxEngine.applyFixture(withBlades, 1)
        assertEquals("重复应用同一台灯应当得到相同结果（探测是幂等的）",
            first.toList(), FxEngine.bladeCh.toList())
        assertNotNull(FxEngine.bladeCh)
    }
}
