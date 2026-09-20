package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推子页分组规则的单元测试。
 *
 * 重点锁住两件事：
 *  1. **规则顺序**：切割/棱镜必须赢过图案（`when` 短路，顺序错了分支永远不生效）
 *  2. **雾化归棱镜**：FROST / 雾化 / 柔光 都算棱镜组，不再落进图案
 *
 * 这两点都是实际踩过的坑：加"切割/棱镜"分支时忘了从图案规则里摘掉 BLADE/PRISM，
 * 结果分支形同虚设，现场看起来"分组规则没生效"。
 */
class ChannelGroupsTest {

    private fun g(attr: String = "", name: String = "", orig: String = "") =
        ChannelGroups.of(attr, name, orig)

    // ---------------- 顺序是承重设计 ----------------

    @Test
    fun `切割赢过图案`() {
        // BLADE 同时也能被图案的宽泛词命中，必须归切割
        assertEquals("切割", g(attr = "BLADE1"))
        assertEquals("切割", g(attr = "BLADE1A"))
        assertEquals("切割", g(orig = "Framing Shaper"))
        assertEquals("切割", g(name = "4.切割2"))
    }

    @Test
    fun `棱镜赢过图案`() {
        // PRISM 必须归棱镜，不能被图案吃掉
        assertEquals("棱镜", g(attr = "PRISM1"))
        assertEquals("棱镜", g(name = "22.棱镜1"))
        assertEquals("棱镜", g(orig = "Prism Rotation"))
    }

    @Test
    fun `雾化归棱镜而不是图案`() {
        // 用户明确要求：雾化算棱镜组
        assertEquals("棱镜", g(attr = "FROST"))
        assertEquals("棱镜", g(name = "24.雾化"))
        assertEquals("棱镜", g(name = "柔光"))
    }

    @Test
    fun `所有分组都在 ORDER 里`() {
        // 规则返回的组名必须是 ORDER 的成员，否则 rebuildRows 会把它漏掉（不显示）
        val samples = listOf(
            g(attr = "DIM"), g(attr = "PAN"), g(attr = "COLOR1"),
            g(attr = "GOBO1"), g(attr = "BLADE1"), g(attr = "PRISM1"),
            g(name = "完全看不懂的东西")
        )
        samples.forEach {
            assertTrue("组名 $it 不在 ORDER 里", ChannelGroups.ORDER.contains(it))
        }
    }

    // ---------------- 各组的正常识别 ----------------

    @Test
    fun `亮度组`() {
        assertEquals("亮度", g(attr = "DIM"))
        assertEquals("亮度", g(attr = "DIM_FINE"))
        assertEquals("亮度", g(orig = "Shutter"))
        assertEquals("亮度", g(orig = "Strobe"))
    }

    @Test
    fun `位置组`() {
        assertEquals("位置", g(attr = "PAN"))
        assertEquals("位置", g(attr = "TILT_FINE"))
        assertEquals("位置", g(orig = "PT Speed"))
        assertEquals("位置", g(orig = "PTSpeed"))
    }

    @Test
    fun `颜色组`() {
        assertEquals("颜色", g(attr = "COLOR1"))
        assertEquals("颜色", g(orig = "CTO"))
        assertEquals("颜色", g(orig = "RGB"))
        assertEquals("颜色", g(name = "3.色盘"))
    }

    @Test
    fun `图案组`() {
        assertEquals("图案", g(attr = "GOBO1"))
        assertEquals("图案", g(orig = "Focus"))
        assertEquals("图案", g(orig = "Zoom"))
        assertEquals("图案", g(orig = "Iris"))
    }

    @Test
    fun `其他组兜底`() {
        assertEquals("其他", g())
        assertEquals("其他", g(attr = "SOMETHING_ELSE"))
        assertEquals("其他", g(name = "CH 1"))
    }

    // ---------------- 属性优先于名字 ----------------

    @Test
    fun `attribute 比通道名更可信`() {
        // 通道名是中文（翻译过），attribute 才是可靠的英文标识 —— 两者都提供时用 attribute
        assertEquals("位置", g(attr = "PAN", name = "28.水平"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals("亮度", g(attr = "dim"))
        assertEquals("图案", g(orig = "gobo"))
        assertEquals("棱镜", g(orig = "prism"))
    }
}
