package com.example.stagedmx

/**
 * 推子页的**行模型** —— 纯逻辑，无 Android 依赖，可单测。
 *
 * 从 [ChannelAdapter] 里抽出来的原因和当初抽 [ChannelGroups] 一样：
 * 这段逻辑原来长在 `RecyclerView.Adapter` 里，单测要拉 Robolectric；而它恰恰是最容易
 * 出错的地方 —— 下面这几条都踩过：
 *
 *  1. 折叠「切割」时如果只折叠了 8 个切割片通道、漏了「切割旋转」，切割组的成员就
 *     没有全部落在折叠集合里，于是**组头和入口行同时出现**（两个都能点的入口）。
 *     所以这里的规则是：**只要在折叠状态，「切割」组一律由入口行代表，不显示组头**，
 *     落在集合外的成员照旧列出来（不丢行）。
 *  2. 不分组时也必须支持折叠（模式 1/2 下不分组也要有入口行），所以行模型不能再
 *     像原来那样"不分组就直接返回 0..count-1"。
 *  3. 自定义排列（`order`）下，"行下标"和"灯内通道号"是两个不同的东西，折叠判定
 *     必须用**灯内通道号**，否则折叠的是错的那些行。
 */
object ChannelRows {

    /** 一行。 */
    sealed interface Row {
        /** 通道滑条：position = 显示位置（配合自定义排列用）。 */
        data class Channel(val position: Int) : Row

        /** 功能分组标题。 */
        data class Group(val name: String, val members: Int, val collapsed: Boolean) : Row

        // ---- 切割面板**内嵌**在推子页里（不弹窗），下面是它的几种行 ----

        /** 圆形光斑预览（自绘 [ShaperWindowView]）。 */
        data class ShaperPreview(val interactive: Boolean) : Row

        /**
         * 一片的一条滑块。
         * @param blade 0..3
         * @param isAngle false = 偏移通道，true = 角度通道
         * @param chInFixture 灯内通道号（0 = 该通道不存在，行内显示"—"并禁用）
         */
        data class ShaperSlider(val blade: Int, val isAngle: Boolean, val chInFixture: Int) : Row

        /** 整体旋转滑块（chInFixture = 0 表示该灯没有旋转通道）。 */
        data class ShaperRot(val chInFixture: Int) : Row
    }

    /** 分组标题行的固定顺序（与 [ChannelGroups.ORDER] 一致，由调用方传入以便测试）。 */
    val DEFAULT_GROUP_ORDER = ChannelGroups.ORDER

    /**
     * 生成行模型。
     *
     * @param count            通道数
     * @param grouped          是否按功能分组
     * @param groupOf          通道下标 → 功能组名（[ChannelGroups.of]）
     * @param chNumberOf       通道下标 → **灯内通道号**（1-based）
     * @param collapsedGroups  已折叠的功能组名
     * @param shaperSkip       切割片通道（灯内号）→ 改成内嵌面板；null = 不折叠（模式 0）
     * @param shaperMode       当前切割 UI 模式（1 面板 / 2 窗口）
     * @param shaperBlades     8 个切割通道号（[FxEngine.bladeCh] 的拷贝；0 = 不存在）
     * @param shaperRotCh      切割旋转通道号（0 = 没有）
     * @param groupOrder       分组顺序
     */
    fun build(
        count: Int,
        grouped: Boolean,
        groupOf: (Int) -> String,
        chNumberOf: (Int) -> Int,
        collapsedGroups: Set<String> = emptySet(),
        shaperSkip: Set<Int>? = null,
        shaperMode: Int = ShaperStore.MODE_FADERS,
        shaperBlades: IntArray = IntArray(8),
        shaperRotCh: Int = 0,
        groupOrder: List<String> = DEFAULT_GROUP_ORDER,
    ): List<Row> {
        val out = ArrayList<Row>()
        var shaperAdded = false

        /**
         * 插入「切割」内嵌面板：标题 + 预览 + 滑块。
         *
         * 两种模式的**行组成不同**（用户要求"窗口模式只保留切割旋转的拖动条"）：
         *  - 面板模式：预览 + 8 条偏移/角度滑块 + 旋转滑块（输入靠滑块）；
         *  - 窗口模式：预览（可拖）+ **只有旋转滑块** ——
         *    偏移/角度直接在图上的角和线段上拖，再摆 8 条滑条既重复又占地方。
         *
         * 底部那排按钮（四边全开 / 四边全闭 / 逐片自检 / 映射）没有：
         * 映射与自检挪到了设置页。
         */
        fun addShaperBlock(collapsed: Boolean) {
            if (shaperAdded) return
            shaperAdded = true
            // 无论分组开关，切割这一块都要有自己的标题，否则用户不知道这是干什么的
            out.add(Row.Group(CUT_GROUP, count, collapsed))
            if (collapsed) return
            out.add(Row.ShaperPreview(interactive = shaperMode == ShaperStore.MODE_CANVAS))
            if (shaperMode != ShaperStore.MODE_CANVAS) {
                for (i in 0 until 4) {
                    out.add(Row.ShaperSlider(i, isAngle = false, chInFixture = shaperBlades.getOrElse(2 * i) { 0 }))
                    out.add(Row.ShaperSlider(i, isAngle = true, chInFixture = shaperBlades.getOrElse(2 * i + 1) { 0 }))
                }
            }
            out.add(Row.ShaperRot(shaperRotCh))
        }

        /** 该通道属于切割折叠集合 → 由内嵌面板代表，本身不再单独出行。 */
        fun isShaperCh(ch: Int) = shaperSkip != null && ch in shaperSkip

        if (!grouped) {
            for (i in 0 until count) {
                val ch = chNumberOf(i)
                // 不分组时也要能折叠：标题行长得一样，点下去什么都不发生会显得像坏了
                if (isShaperCh(ch)) { addShaperBlock(CUT_GROUP in collapsedGroups); continue }
                out.add(Row.Channel(i))
            }
            return out
        }

        for (g in groupOrder) {
            val members = (0 until count).filter { groupOf(it) == g }
            if (members.isEmpty() && !(g == CUT_GROUP && shaperSkip != null)) continue
            val collapsed = g in collapsedGroups
            // 「切割」这一组整组换成内嵌面板：标题由 addShaperBlock 自己发，
            // 所以这里**不能**再发一遍 Group，否则又是一行标题 + 一行标题。
            if (g == CUT_GROUP && shaperSkip != null) {
                addShaperBlock(collapsed)
                if (!collapsed) for (m in members) {
                    if (isShaperCh(chNumberOf(m))) continue
                    out.add(Row.Channel(m))
                }
                continue
            }
            out.add(Row.Group(g, members.size, collapsed))
            if (!collapsed) for (m in members) {
                out.add(Row.Channel(m))
            }
        }
        return out
    }

    /** 切割所属的功能组名（和 [ChannelGroups.ORDER] 里的一致）。 */
    const val CUT_GROUP = "切割"
}
