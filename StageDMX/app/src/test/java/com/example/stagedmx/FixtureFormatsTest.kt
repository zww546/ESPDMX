package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灯库格式页签的判定。
 *
 * 现场实况（手机里真实存在的文件名）就是这三份同名不同格式：
 * `omarte_ares_fp3000_34ch.xml` / `.d4` / `_r20.r20` —— 列表不分开时用户分不清，
 * 而它们的通道语义确实不同（切割片 `BLADE1A/1B` vs `blade_1 + blade_1_rot`）。
 */
class FixtureFormatsTest {

    private fun def(id: String, name: String = id) = FixtureDef(
        id = id, name = name, manufacturer = "OMARTE", mode = "标准",
        channelCount = 34, channels = emptyList()
    )

    /** 三份同名不同格式 + 一个只有 JSON 的（ZIP 里第二台之后的灯型就是这种）。 */
    private val xml = def("ares34")
    private val d4 = def("ares34_d4")
    private val r20 = def("ares34_r20")
    private val onlyJson = def("from_zip_2")

    private val formats: (FixtureDef) -> Set<String> = { d ->
        when (d.id) {
            "ares34" -> setOf("xml")
            "ares34_d4" -> setOf("d4")
            "ares34_r20" -> setOf("r20")
            else -> emptySet()          // 没有原始文件
        }
    }
    private val all = listOf(xml, d4, r20, onlyJson)

    @Test
    fun `tabs are ordered with all first and other last`() {
        assertEquals(listOf("", "xml", "d4", "r20", "other"), FixtureFormats.TABS)
        assertEquals("全部", FixtureFormats.label(FixtureFormats.ALL))
        assertEquals("其他", FixtureFormats.label(FixtureFormats.OTHER))
        // 文案要带上是哪家的格式，免得用户记不住 D4/R20 是谁的
        assertTrue(FixtureFormats.label("d4").contains("老虎"))
        assertTrue(FixtureFormats.label("r20").contains("珍珠"))
        assertTrue(FixtureFormats.label("xml").contains("MA2"))
    }

    @Test
    fun `each format tab shows only that format`() {
        assertEquals(listOf(xml), FixtureFormats.filter("xml", all, formats))
        assertEquals(listOf(d4), FixtureFormats.filter("d4", all, formats))
        assertEquals(listOf(r20), FixtureFormats.filter("r20", all, formats))
    }

    /** 从 ZIP 一次导入多个灯型时，除第一个外都不留原始文件 —— 必须能在「其他」里找到，不能凭空消失。 */
    @Test
    fun `defs without a raw file land in other`() {
        assertEquals(listOf(onlyJson), FixtureFormats.filter(FixtureFormats.OTHER, all, formats))
        assertTrue("没有原始文件的必须归到「其他」", FixtureFormats.matches(FixtureFormats.OTHER, emptySet()))
        assertFalse("有原始文件的不能出现在「其他」", FixtureFormats.matches(FixtureFormats.OTHER, setOf("xml")))
    }

    /** 「全部」＝不筛，任何灯型都看得见（默认入口必须是它，否则用户会以为灯库丢了）。 */
    @Test
    fun `all tab keeps everything`() {
        assertEquals(all, FixtureFormats.filter(FixtureFormats.ALL, all, formats))
        all.forEach { assertTrue(FixtureFormats.matches(FixtureFormats.ALL, formats(it))) }
    }

    /** 一个灯型同时有两种原始文件（先后用不同格式导入过同名灯库）时，两个页签都该看得到它。 */
    @Test
    fun `a def with two raw files shows in both tabs`() {
        val both = { _: FixtureDef -> setOf("xml", "d4") }
        assertEquals(listOf(xml), FixtureFormats.filter("xml", listOf(xml), both))
        assertEquals(listOf(xml), FixtureFormats.filter("d4", listOf(xml), both))
        assertEquals(emptyList<FixtureDef>(), FixtureFormats.filter("r20", listOf(xml), both))
    }

    /**
     * RDM 自动匹配：选中的格式**优先**，但**不能把候选清空** ——
     * 换了页签就再也匹配不上任何灯，比不筛还糟。
     */
    @Test
    fun `rdm matching prefers the selected format but never empties the list`() {
        // 有该格式 → 只留该格式
        assertEquals(listOf(d4), FixtureFormats.prefer("d4", listOf(xml, d4), formats))
        // 没有该格式 → 原样返回（退回全部候选）
        assertEquals(listOf(xml, onlyJson), FixtureFormats.prefer("r20", listOf(xml, onlyJson), formats))
        // 「全部」→ 不做任何偏好
        assertEquals(all, FixtureFormats.prefer(FixtureFormats.ALL, all, formats))
    }
}
