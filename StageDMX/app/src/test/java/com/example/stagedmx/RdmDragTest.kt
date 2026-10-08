package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RdmDrag] 拖动排序决策的单元测试。
 *
 * 这段以前是三套分支挤在一个 90 行的 `onMove` 回调里，其中"整块搬家"那串
 * `notifyItemMoved` 是**拿小例子手推出来的**（原注释里还留着推导过程）。
 * 手推的东西最需要的是把它钉死，而不是再推一遍。
 */
class RdmDragTest {

    private fun dev(uid: String, model: String) =
        RdmDevice(uid = uid, manufacturer = "OMAR", model = model,
            address = 1, channelCount = 20)

    private fun keyOf(d: RdmDevice) = d.model

    /** 顺序表的内容（短标记，方便断言）。 */
    private fun order(order: List<RdmDevice>) = order.map { it.uid }

    private fun rowsOf(
        order: List<RdmDevice>, grouped: Boolean, collapsed: Set<String> = emptySet()
    ) = RdmRows.build(order, grouped, ::keyOf, collapsed)

    // ================= 平铺模式：逐格交换 =================

    @Test
    fun `flat drag swaps step by step`() {
        val order = mutableListOf(dev("a", "M"), dev("b", "M"), dev("c", "M"))
        val rows = rowsOf(order, grouped = false)
        val res = RdmDrag.onMove(rows, order, a = 0, b = 2, grouped = false,
            collapsed = emptySet(), groupKeyOf = ::keyOf)
        assertEquals(listOf("b", "c", "a"), order(order))
        assertEquals(listOf(0 to 2), res!!.moves)
        assertFalse(res.groupBlock)
    }

    @Test
    fun `flat drag backwards also works`() {
        val order = mutableListOf(dev("a", "M"), dev("b", "M"), dev("c", "M"))
        val rows = rowsOf(order, grouped = false)
        RdmDrag.onMove(rows, order, a = 2, b = 0, grouped = false,
            collapsed = emptySet(), groupKeyOf = ::keyOf)
        assertEquals(listOf("c", "a", "b"), order(order))
    }

    @Test
    fun `out of range indices are rejected`() {
        val order = mutableListOf(dev("a", "M"))
        val rows = rowsOf(order, grouped = false)
        assertNull(RdmDrag.onMove(rows, order, 0, 5, false, emptySet(), ::keyOf))
        assertNull(RdmDrag.onMove(rows, order, -1, 0, false, emptySet(), ::keyOf))
        assertNull(RdmDrag.onMove(rows, order, 0, 0, false, emptySet(), ::keyOf))
        assertEquals(listOf("a"), order(order))
    }

    // ================= 分组模式：只允许同组内换位 =================

    /** 两台 M20 分居 order 两端，中间夹着一台 M12 —— 这种情况最能暴露"交换 vs 真移动"。 */
    private fun interleaved(): MutableList<RdmDevice> =
        mutableListOf(dev("a1", "M20"), dev("m1", "M12"), dev("a2", "M20"))

    @Test
    fun `within group move is a real move not a swap`() {
        val order = interleaved()
        val rows = rowsOf(order, grouped = true)
        // 行模型：M12(a) M20? 按首次出现：M20 先 → [H20,a1, H12,m1, H20? ...]
        // 实际顺序：a1(M20) 先出现 → [H(M20), a1, H(M12), m1, a2]
        assertEquals(5, rows.size)
        val a = rows.indexOfFirst { it is RdmDevice && it.uid == "a1" }
        val b = rows.indexOfFirst { it is RdmDevice && it.uid == "a2" }
        val res = RdmDrag.onMove(rows, order, a, b, grouped = true,
            collapsed = emptySet(), groupKeyOf = ::keyOf)
        assertEquals(listOf(a to b), res!!.moves)
        // ⚠ 真移动：a1 摘出来插到 a2 原来的位置 → a1 跑到 m1 后面
        assertEquals(listOf("m1", "a2", "a1"), order(order))
    }

    @Test
    fun `cross group move is refused`() {
        val order = interleaved()
        val rows = rowsOf(order, grouped = true)
        val a = rows.indexOfFirst { it is RdmDevice && it.uid == "a1" }
        val b = rows.indexOfFirst { it is RdmDevice && it.uid == "m1" }
        assertNull("跨组换位必须拒绝（要换整组请拖组头）",
            RdmDrag.onMove(rows, order, a, b, grouped = true,
                collapsed = emptySet(), groupKeyOf = ::keyOf))
        assertEquals(listOf("a1", "m1", "a2"), order(order))
    }

    @Test
    fun `device onto group header is refused`() {
        val order = interleaved()
        val rows = rowsOf(order, grouped = true)
        val a = rows.indexOfFirst { it is RdmDevice && it.uid == "a1" }
        val h = rows.indexOfFirst { it is RdmRows.Group }
        assertNull(RdmDrag.onMove(rows, order, a, h, grouped = true,
            collapsed = emptySet(), groupKeyOf = ::keyOf))
    }

    // ================= 拖组头 = 整组搬家 =================

    /**
     * ⚠ 这条是核心：整块搬家时发给 adapter 的 `notifyItemMoved` 序列，
     *   原来是拿 `[HA,x,HB,y] --move(0→3) 两次--> [HB,y,HA,x]` 这样的小例子手推的。
     *   手推的东西必须钉死，否则下次改行模型时没人知道它已经错了。
     */
    @Test
    fun `dragging a group header forward moves the whole block`() {
        // M20: a1,a2 / M12: m1  → 行模型 [H20,a1,a2, H12,m1]
        val order = mutableListOf(dev("a1", "M20"), dev("a2", "M20"), dev("m1", "M12"))
        val rows = rowsOf(order, grouped = true)
        assertEquals(5, rows.size)
        val a = RdmRows.indexOfGroup(rows, "M20")     // 0
        val b = RdmRows.indexOfGroup(rows, "M12")     // 3
        val res = RdmDrag.onMove(rows, order, a, b, grouped = true,
            collapsed = emptySet(), groupKeyOf = ::keyOf)!!
        assertTrue("整组搬家要标出来", res.groupBlock)
        // 顺序：M20 整块挪到 M12 后面
        assertEquals(listOf("m1", "a1", "a2"), order(order))
        // 移动序列：rowsA=2（M20 两台），rowsB=1（M12 一台），blockRows=3
        // a<b → 三次 move(a, a + rowsA + rowsB + 1) = move(0, 4)
        assertEquals(List(3) { 0 to 4 }, res.moves)
    }

    @Test
    fun `dragging a group header backward moves the whole block`() {
        val order = mutableListOf(dev("m1", "M12"), dev("a1", "M20"), dev("a2", "M20"))
        val rows = rowsOf(order, grouped = true)
        // 组顺序按首次出现：M12 先 → [H12,m1, H20,a1,a2]
        val a = RdmRows.indexOfGroup(rows, "M20")     // 2
        val b = RdmRows.indexOfGroup(rows, "M12")     // 0
        val res = RdmDrag.onMove(rows, order, a, b, grouped = true,
            collapsed = emptySet(), groupKeyOf = ::keyOf)!!
        assertTrue(res.groupBlock)
        assertEquals(listOf("a1", "a2", "m1"), order(order))
        // a>b → 两次 move(a + rowsA, b) = move(2 + 2, 0) = move(4, 0)
        assertEquals(List(3) { 4 to 0 }, res.moves)
    }

    @Test
    fun `dragging a collapsed group moves only its header row`() {
        val order = mutableListOf(dev("a1", "M20"), dev("a2", "M20"), dev("m1", "M12"))
        val rows = rowsOf(order, grouped = true, collapsed = setOf("M20"))
        // 行模型：[H20, H12, m1]
        assertEquals(3, rows.size)
        val a = RdmRows.indexOfGroup(rows, "M20")
        val b = RdmRows.indexOfGroup(rows, "M12")
        val res = RdmDrag.onMove(rows, order, a, b, grouped = true,
            collapsed = setOf("M20"), groupKeyOf = ::keyOf)!!
        assertEquals(listOf("m1", "a1", "a2"), order(order))
        // 折叠时 M20 只占 1 行 → blockRows = rowsA + 1 = 0 + 1 = 1
        assertEquals(1, res.moves.size)
    }

    @Test
    fun `dropping a group header onto its own member does nothing`() {
        val order = mutableListOf(dev("a1", "M20"), dev("a2", "M20"))
        val rows = rowsOf(order, grouped = true)
        val h = RdmRows.indexOfGroup(rows, "M20")
        val ownMember = rows.indexOfFirst { it is RdmDevice && it.uid == "a1" }
        assertNull("组头拖到自己组员上不该有动作",
            RdmDrag.onMove(rows, order, h, ownMember, grouped = true,
                collapsed = emptySet(), groupKeyOf = ::keyOf))
        assertEquals(listOf("a1", "a2"), order(order))
    }

    // ================= 行模型与顺序表必须自洽 =================

    /**
     * 拖动之后 `order` 与 `rows` 必须仍然自洽 —— 调用方会立刻用 `RdmRows.build`
     * 重铺行模型，所以这里断言"重铺出来的行模型里，组顺序等于 order 里的首次出现顺序"。
     */
    @Test
    fun `after a group block move the rebuilt rows agree with the order`() {
        val order = mutableListOf(dev("a1", "M20"), dev("a2", "M20"), dev("m1", "M12"))
        val rows = rowsOf(order, grouped = true)
        RdmDrag.onMove(rows, order, RdmRows.indexOfGroup(rows, "M20"),
            RdmRows.indexOfGroup(rows, "M12"), grouped = true,
            collapsed = emptySet(), groupKeyOf = ::keyOf)
        val rebuilt = RdmRows.build(order, grouped = true, groupKeyOf = ::keyOf)
        assertEquals("组顺序应当跟着 order 走",
            order.map { it.model }.distinct(),
            rebuilt.filterIsInstance<RdmRows.Group>().map { it.key })
    }
}
