package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RdmRows] 行模型的单元测试。
 *
 * 为什么值得测：这张表是**拖动排序直接按下标改**的对象 —— 行模型的长度或顺序错了，
 * 表现是"拖整组时挪错位""折叠的组被当成展开了""拖完顺序乱了"，
 * 而这些在 Activity 里只能靠手点复现。抽出来之后就能逐条断言。
 */
class RdmRowsTest {

    private fun dev(uid: String, model: String, ch: Int = 20) =
        RdmDevice(uid = uid, manufacturer = "OMAR", model = model,
            address = 1, channelCount = ch)

    private val a1 = dev("A1", "M20")
    private val a2 = dev("A2", "M20")
    private val b1 = dev("B1", "M12", 12)
    private val all = listOf(a1, a2, b1)

    private fun keyOf(d: RdmDevice) = d.model

    private fun groupKeys(rows: List<Any>) =
        rows.filterIsInstance<RdmRows.Group>().map { it.key }

    private fun uids(rows: List<Any>) = rows.filterIsInstance<RdmDevice>().map { it.uid }

    // ---------------- 平铺 ----------------

    @Test
    fun `flat mode is one row per device`() {
        val rows = RdmRows.build(all, grouped = false, groupKeyOf = ::keyOf)
        assertEquals(3, rows.size)
        assertEquals(listOf("A1", "A2", "B1"), uids(rows))
        assertTrue("平铺模式不该有组头", groupKeys(rows).isEmpty())
    }

    // ---------------- 分组 ----------------

    @Test
    fun `grouped mode emits a header then its members`() {
        val rows = RdmRows.build(all, grouped = true, groupKeyOf = ::keyOf)
        // M20: 组头 + 2 台；M12: 组头 + 1 台
        assertEquals(5, rows.size)
        assertEquals(listOf("M20", "M12"), groupKeys(rows))
        assertEquals(0, rows.indexOfFirst { RdmRows.isGroup(it) })
        assertTrue(RdmRows.isGroup(rows[0]))
        assertFalse("组头下面第一行应当是成员", RdmRows.isGroup(rows[1]))
        assertEquals(listOf("A1", "A2", "B1"), uids(rows))
    }

    @Test
    fun `group order follows first appearance in the device list`() {
        // 组顺序必须跟配地址用的组顺序同源（首次出现次序），否则"组起始地址"会对不上
        val rows = RdmRows.build(listOf(b1, a1, a2), grouped = true, groupKeyOf = ::keyOf)
        assertEquals(listOf("M12", "M20"), groupKeys(rows))
    }

    // ---------------- 折叠 ----------------

    @Test
    fun `a collapsed group keeps only its header`() {
        val rows = RdmRows.build(all, grouped = true, groupKeyOf = ::keyOf,
            collapsed = setOf("M20"))
        assertEquals("M20 只剩组头，M12 照旧", 3, rows.size)
        assertEquals(listOf("M20", "M12"), groupKeys(rows))
        assertEquals("M20 的成员不该出现", listOf("B1"), uids(rows))
        // 而且组头后面直接就是下一个组头
        assertTrue(RdmRows.isGroup(rows[1]))
    }

    @Test
    fun `all groups collapsed leaves only headers`() {
        val rows = RdmRows.build(all, grouped = true, groupKeyOf = ::keyOf,
            collapsed = setOf("M20", "M12"))
        assertEquals(2, rows.size)
        assertTrue(uids(rows).isEmpty())
    }

    // ---------------- 组占几行（拖动整组搬家要用）----------------

    @Test
    fun `group row span matches the emitted rows`() {
        // 折叠 = 1 行（只有组头）；没折叠 = 1 + 台数
        assertEquals(1, RdmRows.groupRowSpan(collapsed = true, deviceCount = 3))
        assertEquals(4, RdmRows.groupRowSpan(collapsed = false, deviceCount = 3))
        // 必须和实际铺出来的行数一致，否则拖整组会搬多/搬少
        for (collapsed in listOf(true, false)) {
            val rows = RdmRows.build(all, grouped = true, groupKeyOf = ::keyOf,
                collapsed = if (collapsed) setOf("M20") else emptySet())
            val headerAt = RdmRows.indexOfGroup(rows, "M20")
            val span = RdmRows.groupRowSpan(collapsed, 2)
            assertTrue("组头下标必须找得到", headerAt >= 0)
            // 这一段行里，属于 M20 的行数（组头 + 成员）应当正好等于 span
            // ⚠ 要按**行**判，不是按下标判（这里踩过一次：拿 index 去 is Group，永远是 false）
            val inGroup = (headerAt until minOf(headerAt + span, rows.size)).count { i ->
                val r = rows[i]
                (r is RdmRows.Group && r.key == "M20") ||
                    (r is RdmDevice && r.model == "M20")
            }
            assertEquals("collapsed=$collapsed 时 M20 这一段应当正好占 $span 行", span, inGroup)
            // 段外的第一行不该再属于 M20（否则说明 span 算小了）
            val next = headerAt + span
            if (next < rows.size) {
                val r = rows[next]
                assertTrue("第 $next 行不该还属于 M20（span 算小了）",
                    !(r is RdmRows.Group && r.key == "M20") &&
                        !(r is RdmDevice && r.model == "M20"))
            }
        }
    }

    @Test
    fun `index of group finds the header`() {
        val rows = RdmRows.build(all, grouped = true, groupKeyOf = ::keyOf)
        assertEquals(0, RdmRows.indexOfGroup(rows, "M20"))
        assertEquals(3, RdmRows.indexOfGroup(rows, "M12"))
        assertEquals(-1, RdmRows.indexOfGroup(rows, "没有这个组"))
    }

    // ---------------- 辅助 ----------------

    @Test
    fun `devices of a row works for both kinds`() {
        val rows = RdmRows.build(all, grouped = true, groupKeyOf = ::keyOf)
        assertEquals(listOf("A1", "A2"), RdmRows.devicesOf(rows[0]).map { it.uid })
        assertEquals(listOf("A1"), RdmRows.devicesOf(rows[1]).map { it.uid })
        assertEquals(emptyList<RdmDevice>(), RdmRows.devicesOf(null))
    }

    @Test
    fun `build returns a mutable list the caller can drag in place`() {
        // 拖动排序是**就地**改这张表的（不能换实例，否则手势断掉）
        val rows = RdmRows.build(all, grouped = false, groupKeyOf = ::keyOf)
        rows[0] = rows[2]
        assertEquals("B1", uids(rows).first())
        val grouped = RdmRows.build(all, grouped = true, groupKeyOf = ::keyOf)
        grouped.clear()
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun `empty device list yields empty rows`() {
        assertTrue(RdmRows.build(emptyList(), grouped = false, groupKeyOf = ::keyOf).isEmpty())
        assertTrue(RdmRows.build(emptyList(), grouped = true, groupKeyOf = ::keyOf).isEmpty())
    }
}
