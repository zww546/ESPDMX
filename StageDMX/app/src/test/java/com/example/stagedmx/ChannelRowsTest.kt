package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChannelRows] 的单元测试。
 *
 * 切割面板是**内嵌在推子页里**的（不弹窗），所以行模型要负责把它铺成一串行：
 * 标题 + 圆形预览 + 8 条滑块（4 片 × 偏移/角度）+ 旋转 + 三个按钮。
 *
 * 这里锁住的是**踩过的坑**，不是把实现照抄一遍：
 *  1. 内嵌块不能同时出现两个「切割」标题（块自己发标题，外层就不能再发）；
 *  2. 不按功能分组时也要能内嵌（否则关掉分组就没入口了）；
 *  3. 自定义排列下"行下标"≠"灯内通道号"，判断哪些行该被替掉必须用通道号；
 *  4. 只替掉该替的行：别的组、以及落在集合外的切割组通道，一行都不能少。
 */
class ChannelRowsTest {

    /** 10 通道：1=调光 2=水平 3..9=切割片 10=切割旋转。 */
    private val names = listOf(
        "DIM", "PAN", "BLADE", "BLADE", "BLADE", "BLADE",
        "BLADE", "BLADE", "BLADE", "SHAPE-ROT"
    )

    private val allCut = setOf(3, 4, 5, 6, 7, 8, 9, 10)   // 8 片 + 旋转

    private fun build(
        grouped: Boolean,
        skip: Set<Int>?,
        collapsed: Set<String> = emptySet(),
        order: IntArray? = null,
        mode: Int = ShaperStore.MODE_PANEL,
    ): List<ChannelRows.Row> {
        fun idx(pos: Int) = order?.getOrNull(pos) ?: pos
        return ChannelRows.build(
            count = names.size,
            grouped = grouped,
            groupOf = { ChannelGroups.of("", names[it], names[it]) },
            chNumberOf = { idx(it) + 1 },
            collapsedGroups = collapsed,
            shaperSkip = skip,
            shaperMode = mode,
            shaperBlades = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10),
            shaperRotCh = 10,
        )
    }

    private fun channels(rows: List<ChannelRows.Row>) =
        rows.filterIsInstance<ChannelRows.Row.Channel>().map { it.position }

    private fun cutHeaders(rows: List<ChannelRows.Row>) =
        rows.filterIsInstance<ChannelRows.Row.Group>().count { it.name == ChannelRows.CUT_GROUP }

    /** 内嵌块齐备：预览 1 + 滑块 8 + 旋转 1。 */
    private fun assertBlockIncomplete(rows: List<ChannelRows.Row>, expectPresent: Boolean) {
        val n = if (expectPresent) 1 else 0
        assertEquals("预览", n, rows.count { it is ChannelRows.Row.ShaperPreview })
        assertEquals("滑块", if (expectPresent) 8 else 0, rows.count { it is ChannelRows.Row.ShaperSlider })
        assertEquals("旋转", n, rows.count { it is ChannelRows.Row.ShaperRot })
    }

    // ---------------- 模式 0：完全是老样子 ----------------
    @Test
    fun `without shaper mode every channel gets its own row`() {
        val flat = build(grouped = false, skip = null)
        assertEquals(names.size, flat.size)
        assertEquals((0 until names.size).toList(), channels(flat))
        assertBlockIncomplete(flat, expectPresent = false)
        assertTrue("不折叠时不该有切割块标题", cutHeaders(flat) == 0)
    }

    @Test
    fun `grouped mode still emits group headers and all channels`() {
        val rows = build(grouped = true, skip = null)
        val groups = rows.filterIsInstance<ChannelRows.Row.Group>().map { it.name }
        assertTrue(groups.contains(ChannelRows.CUT_GROUP))
        assertTrue(groups.contains("亮度"))
        assertEquals("所有通道都应当出现", names.size, channels(rows).size)
        assertBlockIncomplete(rows, expectPresent = false)
    }

    // ---------------- 模式 1/2：内嵌切割块 ----------------

    @Test
    fun `inline block replaces all cut channels in flat mode`() {
        val rows = build(grouped = false, skip = allCut)
        assertBlockIncomplete(rows, expectPresent = true)
        assertEquals("只有调光/水平还作为通道行", listOf(0, 1), channels(rows))
        assertEquals("切割块标题只应当有一个", 1, cutHeaders(rows))
    }

    @Test
    fun `inline block replaces the cut group header exactly once in grouped mode`() {
        val rows = build(grouped = true, skip = allCut)
        assertBlockIncomplete(rows, expectPresent = true)
        // ⚠ 这里就是踩过的坑：块自己发标题，外层再发一个 = 两个「切割」标题
        assertEquals("「切割」标题必须只出现一次", 1, cutHeaders(rows))
        assertEquals(listOf(0, 1), channels(rows))
    }

    @Test
    fun `cut channels outside the replace set stay as normal rows`() {
        // 只折叠 8 片，漏掉旋转（10）→ 旋转应当照旧作为一条普通通道行出现
        val rows = build(grouped = true, skip = setOf(3, 4, 5, 6, 7, 8, 9))
        assertBlockIncomplete(rows, expectPresent = true)
        assertEquals(1, cutHeaders(rows))
        assertTrue("落在外面的旋转通道不能丢", channels(rows).contains(9))
        assertEquals("但被替掉的 7 片不该再出现", listOf(0, 1, 9), channels(rows))
    }

    @Test
    fun `other groups are untouched`() {
        val rows = build(grouped = true, skip = allCut)
        val groups = rows.filterIsInstance<ChannelRows.Row.Group>().map { it.name }
        assertTrue(groups.contains("亮度"))
        assertTrue(groups.contains("位置"))
    }

    @Test
    fun `collapsed cut group shows only its header`() {
        val rows = build(grouped = true, skip = allCut, collapsed = setOf(ChannelRows.CUT_GROUP))
        assertEquals("折叠时应当只剩标题", 1, cutHeaders(rows))
        assertBlockIncomplete(rows, expectPresent = false)
        assertEquals(listOf(0, 1), channels(rows))
    }

    @Test
    fun `preview interactivity follows the mode`() {
        val panel = build(grouped = false, skip = allCut, mode = ShaperStore.MODE_PANEL)
            .filterIsInstance<ChannelRows.Row.ShaperPreview>().single()
        assertFalse("方案 A 的预览不该接收触摸", panel.interactive)
        val canvas = build(grouped = false, skip = allCut, mode = ShaperStore.MODE_CANVAS)
            .filterIsInstance<ChannelRows.Row.ShaperPreview>().single()
        assertTrue("方案 B 的预览要能拖", canvas.interactive)
    }

    @Test
    fun `slider rows carry the right channel numbers and pairing`() {
        val rows = build(grouped = false, skip = allCut)
        val sliders = rows.filterIsInstance<ChannelRows.Row.ShaperSlider>()
        // 片 i 的偏移/角度 = 通道 (2i+3, 2i+4)（交错配对）
        for (i in 0 until 4) {
            val off = sliders.first { it.blade == i && !it.isAngle }
            val ang = sliders.first { it.blade == i && it.isAngle }
            assertEquals("片${i + 1} 偏移通道", 3 + 2 * i, off.chInFixture)
            assertEquals("片${i + 1} 角度通道", 4 + 2 * i, ang.chInFixture)
        }
        assertEquals("旋转通道", 10,
            rows.filterIsInstance<ChannelRows.Row.ShaperRot>().single().chInFixture)
    }

    // ---------------- 自定义排列 ----------------

    @Test
    fun `custom order uses channel numbers not row positions`() {
        // 显示顺序反过来：position 0 显示通道 10
        val order = IntArray(names.size) { names.size - 1 - it }
        val rows = build(grouped = false, skip = allCut, order = order)
        assertBlockIncomplete(rows, expectPresent = true)
        // 通道号 3..10 被替掉；剩下的是通道 1、2 现在所在的位置
        val remainCh = channels(rows).map { order[it] + 1 }.sorted()
        assertEquals(listOf(1, 2), remainCh)
    }

    // ---------------- 边界 ----------------

    @Test
    fun `empty or non-matching skip set inserts nothing`() {
        val empty = build(grouped = false, skip = emptySet())
        assertEquals(names.size, empty.size)
        assertBlockIncomplete(empty, expectPresent = false)

        val bogus = build(grouped = false, skip = setOf(99, 100))
        assertEquals(names.size, bogus.size)
        assertBlockIncomplete(bogus, expectPresent = false)
    }

    @Test
    fun `missing blade channels produce disabled slider rows`() {
        // 只有 4 个切割通道的灯（bladeCh[4..7] = 0）→ 后两片的滑块通道号为 0
        val rows = ChannelRows.build(
            count = names.size,
            grouped = false,
            groupOf = { ChannelGroups.of("", names[it], names[it]) },
            chNumberOf = { it + 1 },
            shaperSkip = setOf(3, 4, 5, 6),
            shaperMode = ShaperStore.MODE_PANEL,
            shaperBlades = intArrayOf(3, 4, 5, 6, 0, 0, 0, 0),
            shaperRotCh = 0,
        )
        val sliders = rows.filterIsInstance<ChannelRows.Row.ShaperSlider>()
        assertEquals("滑块还是 8 条（缺的显示为不可用）", 8, sliders.size)
        // 片3/片4 各两条（偏移+角度）都不存在 → 4 条禁用行
        assertEquals(4, sliders.count { it.chInFixture == 0 })
        assertEquals("前两片仍是真通道", listOf(3, 4, 5, 6),
            sliders.filter { it.chInFixture > 0 }.map { it.chInFixture })
        assertEquals("没有旋转通道时传 0", 0,
            rows.filterIsInstance<ChannelRows.Row.ShaperRot>().single().chInFixture)
    }

    // ---------------- 窗口模式：只保留旋转滑块 ----------------

    @Test
    fun `canvas mode keeps only the rotation slider`() {
        // 用户要求："窗口模式只保留切割旋转的拖动条" —— 偏移/角度直接在图上拖，
        // 再摆 8 条滑块既重复又占地方
        val rows = build(grouped = true, skip = allCut, mode = ShaperStore.MODE_CANVAS)
        assertEquals("预览还在", 1, rows.count { it is ChannelRows.Row.ShaperPreview })
        assertEquals("偏移/角度滑块应当全部收掉", 0,
            rows.count { it is ChannelRows.Row.ShaperSlider })
        assertEquals("旋转滑块必须留着", 1, rows.count { it is ChannelRows.Row.ShaperRot })
        // 预览必须是**可拖**的（窗口模式的输入就靠它）
        assertTrue("窗口模式的预览必须 interactive",
            (rows.first { it is ChannelRows.Row.ShaperPreview } as ChannelRows.Row.ShaperPreview)
                .interactive)
    }

    @Test
    fun `panel mode is draw only and keeps all sliders`() {
        val rows = build(grouped = true, skip = allCut, mode = ShaperStore.MODE_PANEL)
        assertEquals(8, rows.count { it is ChannelRows.Row.ShaperSlider })
        assertEquals(1, rows.count { it is ChannelRows.Row.ShaperRot })
        assertFalse("面板模式靠滑块输入，预览不该可拖",
            (rows.first { it is ChannelRows.Row.ShaperPreview } as ChannelRows.Row.ShaperPreview)
                .interactive)
    }

    @Test
    fun `cutting panel can collapse when groups are off`() {
        // 不分组时标题行长得和分组标题一样，点下去必须有用 ——
        // 否则用户点了没反应，会以为这块坏了
        val open = build(grouped = false, skip = allCut)
        assertBlockIncomplete(open, expectPresent = true)

        val shut = build(grouped = false, skip = allCut,
            collapsed = setOf(ChannelRows.CUT_GROUP))
        assertEquals("只剩一个标题", 1, cutHeaders(shut))
        assertBlockIncomplete(shut, expectPresent = false)
        assertTrue("折叠状态的标题要标成 collapsed",
            shut.filterIsInstance<ChannelRows.Row.Group>()
                .single { it.name == ChannelRows.CUT_GROUP }.collapsed)
        // 被替掉的 8 个通道仍然不出行（它们就在面板里）
        assertTrue(channels(shut).none { it + 1 in allCut })
    }
}
