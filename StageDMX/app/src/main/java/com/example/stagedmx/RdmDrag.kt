package com.example.stagedmx

/**
 * RDM 列表**拖动排序**的决策逻辑 —— 纯逻辑，无 Android 依赖，可单测。
 *
 * 从 `MainActivity.refreshRdmList()` 里那段 `ItemTouchHelper.onMove` 抽出来的。
 * 抽它的理由：那里面有**三套分支**（拖组头整块搬家 / 分组内换位 / 平铺逐格交换），
 * 而且每一套都要同时做两件很容易搞错的事：
 *
 *  1. **就地改 `rdmOrder`** —— 改法三套都不一样（整段摘出插入 / 真移动 / 逐格交换）；
 *  2. **算出一串 `notifyItemMoved(from, to)`** —— 尤其是整块搬家那串，
 *     原本是拿小例子手推出来的（注释里还留着 `[HA,x,HB,y] --move(0→3) 两次--> ...`）。
 *
 * 这两个东西以前和 `notifyItemMoved`、`relayoutLocalAddresses`、`updateRdmPreviews`
 * 混在一个 90 行的回调里，只能靠手点验证。现在决策（改表 + 出移动序列）在这里，
 * 界面的三行调用留在 Activity。
 *
 * ⚠ 为什么必须**就地在同一个 MutableList 上改**：拖到列表边缘时 ItemTouchHelper 会
 *   自动滚动页面，`onMove` 被连续调用几十次。若每次都"拷贝 → 重排 → 换新表"，
 *   后一次拷贝会基于动画中的旧位置，索引越滚越偏，松手后顺序就错了。
 */
object RdmDrag {

    /** 一次拖动**被接受**时的结果。 */
    data class Result(
        /** 要依次发给 adapter 的 `notifyItemMoved(from, to)`。 */
        val moves: List<Pair<Int, Int>>,
        /** 是不是"整组搬家"（调用方据此决定要不要提示地址重排）。 */
        val groupBlock: Boolean,
    )

    /**
     * 处理一次拖动。**会就地在 [order] 上改顺序**（所以必须传可变表）。
     *
     * @param rows     行模型（组头 / 设备行）
     * @param order    拖动模式下的顺序表；本函数就地改它
     * @param a,b      被拖的行下标 / 目标行下标
     * @param grouped  是否"按型号分组"（分组与排序可以同时开）
     * @param collapsed 已折叠的组键
     * @param groupKeyOf 设备 → 组键
     * @return null = 这次拖动**不接受**（行不动）；否则见 [Result]
     */
    fun onMove(
        rows: List<Any>,
        order: MutableList<RdmDevice>,
        a: Int,
        b: Int,
        grouped: Boolean,
        collapsed: Set<String>,
        groupKeyOf: (RdmDevice) -> String,
    ): Result? {
        if (a < 0 || b < 0 || a == b) return null

        if (grouped) {
            blockMove(rows, order, a, b, collapsed, groupKeyOf)?.let { return it }
        }
        if (grouped) {
            withinGroupMove(rows, order, a, b, groupKeyOf)?.let { return it }
        } else {
            flatSwap(order, a, b)?.let { return it }
        }
        return null
    }

    // ---------------- 拖组头 = 整组挪位置 ----------------

    /**
     * 只认"组头拖到另一个组头"：拖到别组的灯具行上不动 ——
     * 组头是明确的落点，用户知道往哪拖。
     */
    private fun blockMove(
        rows: List<Any>,
        order: MutableList<RdmDevice>,
        a: Int,
        b: Int,
        collapsed: Set<String>,
        groupKeyOf: (RdmDevice) -> String,
    ): Result? {
        val ha = rows.getOrNull(a)
        val hb = rows.getOrNull(b)
        if (ha !is RdmRows.Group || hb !is RdmRows.Group || ha.key == hb.key) return null

        val devsA = order.filter { groupKeyOf(it) == ha.key }
        if (devsA.isEmpty()) return null

        // 两个组各占几行（折叠的只有组头那行）。用 RdmRows 算，别再手写 "+1"。
        val rowsA = RdmRows.groupRowSpan(ha.key in collapsed, devsA.size) - 1
        val rowsB = RdmRows.groupRowSpan(
            hb.key in collapsed, order.count { groupKeyOf(it) == hb.key }) - 1

        order.removeAll { groupKeyOf(it) == ha.key }
        if (a < b) {
            val lastB = order.indexOfLast { groupKeyOf(it) == hb.key }
            order.addAll(if (lastB < 0) order.size else lastB + 1, devsA)
        } else {
            val firstB = order.indexOfFirst { groupKeyOf(it) == hb.key }
            order.addAll(if (firstB < 0) 0 else firstB, devsA)
        }

        // ⚠ 整块搬 = 反复把块首（或块尾）那一行移到目标位置。
        //   不能图省事用 notifyDataSetChanged()：拖动中的 ViewHolder 会被回收，
        //   ItemTouchHelper 的手势当场断掉。
        //   两种方向的移动序列都用小例子验算过：
        //     A 在 B 前 → [HA,x,HB,y] --move(0→3) 两次--> [HB,y,HA,x]
        //     A 在 B 后 → [HB,y,HA,x] --move(3→0) 两次--> [HA,x,HB,y]
        val blockRows = rowsA + 1
        val moves = if (a < b) {
            List(blockRows) { a to (a + rowsA + rowsB + 1) }
        } else {
            List(blockRows) { (a + rowsA) to b }
        }
        return Result(moves, groupBlock = true)
    }

    // ---------------- 分组模式：只允许同组内换位 ----------------

    /**
     * 跨组换位一律拒绝 —— 否则"组内递增配地址"的顺序就没意义了
     * （要换整组位置请拖组头）。
     */
    private fun withinGroupMove(
        rows: List<Any>,
        order: MutableList<RdmDevice>,
        a: Int,
        b: Int,
        groupKeyOf: (RdmDevice) -> String,
    ): Result? {
        val ra = rows.getOrNull(a)
        val rb = rows.getOrNull(b)
        if (ra !is RdmDevice || rb !is RdmDevice) return null
        if (groupKeyOf(ra) != groupKeyOf(rb)) return null
        val ia = order.indexOfFirst { it.uid == ra.uid }
        val ib = order.indexOfFirst { it.uid == rb.uid }
        if (ia < 0 || ib < 0 || ia == ib) return null
        // ⚠ 必须是**真移动**（摘出来再插进去），不能像平铺那样逐格交换：
        //   order 里同组成员之间可能夹着别组的设备，而分组列表只把同组成员排在一起 ——
        //   交换会让"夹在中间的同组成员"静止不动，与 notifyItemMoved 的语义对不上。
        val moved = order.removeAt(ia)
        order.add(ib, moved)
        return Result(listOf(a to b), groupBlock = false)
    }

    // ---------------- 平铺模式：就地逐格交换 ----------------

    private fun flatSwap(order: MutableList<RdmDevice>, a: Int, b: Int): Result? {
        if (a >= order.size || b >= order.size) return null
        // 就地逐格交换：中间任何一次回调被丢弃都不会累积错位
        if (a < b) for (i in a until b) java.util.Collections.swap(order, i, i + 1)
        else for (i in a downTo b + 1) java.util.Collections.swap(order, i, i - 1)
        return Result(listOf(a to b), groupBlock = false)
    }
}
