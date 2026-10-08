package com.example.stagedmx

/**
 * RDM 列表的**行模型** —— 纯逻辑，无 Android 依赖，可单测。
 *
 * 从 `MainActivity.refreshRdmList()` 里抽出来（那个函数原本 312 行，行模型、适配器、
 * 拖动排序三件事全糊在一起）。抽它的理由和当初抽 [ChannelRows] 一样：
 *
 *  1. 这段逻辑是"列表上一行是组头还是灯具"的唯一判据，而**拖动排序会直接按下标改这张表**
 *     —— 行模型的长度/顺序错了，表现是"拖动时整组挪错位""折叠的组被算成展开了"，
 *     这类 bug 在 Activity 里只能靠手点复现，抽出来就能断言。
 *  2. `MainActivity` 里 `groupedRows` 是**在变**的可变表（拖动时就地 swap），所以
 *     这里的 [build] 必须返回可变表，调用方后续就地改。
 *
 * ⚠ 组顺序 = 设备在 `devices` 里**首次出现**的次序（用 LinkedHashMap 保序），
 *   这跟配地址时用的组顺序必须同源，否则"组起始地址"那一栏会对不上号。
 */
object RdmRows {

    /** 组头行。 */
    data class Group(val key: String, val devices: List<RdmDevice>)

    /** 一行是组头吗（设备行不是）。 */
    fun isGroup(row: Any?): Boolean = row is Group

    /** 行是组头时取出它的设备表；是设备行时返回单元素表。 */
    fun devicesOf(row: Any?): List<RdmDevice> = when (row) {
        is Group -> row.devices
        is RdmDevice -> listOf(row)
        else -> emptyList()
    }

    /**
     * 铺行模型。
     *
     * @param devices   当前顺序下的设备表（排序模式下就是 rdmOrder）
     * @param grouped   false = 平铺，每台设备一行
     * @param groupKeyOf 设备 → 组键
     * @param collapsed 已折叠的组键集合（折叠的组只有组头一行，没有成员行）
     */
    fun build(
        devices: List<RdmDevice>,
        grouped: Boolean,
        groupKeyOf: (RdmDevice) -> String,
        collapsed: Set<String> = emptySet(),
    ): MutableList<Any> {
        val out = ArrayList<Any>(devices.size + 4)
        if (!grouped) {
            out.addAll(devices)
            return out
        }
        for ((key, devs) in devices.groupBy { groupKeyOf(it) }) {
            out.add(Group(key, devs))
            if (key !in collapsed) out.addAll(devs)
        }
        return out
    }

    /**
     * 一个组在行模型里占几行：折叠的只占组头那一行，没折叠的是 `1 + 台数`。
     *
     * 拖整组时要把这一段**连续行**整体搬走，行数算错就会搬多/搬少（表现成"挪完顺序乱"）。
     */
    fun groupRowSpan(collapsed: Boolean, deviceCount: Int): Int =
        1 + if (collapsed) 0 else deviceCount

    /** 组头在行模型里的下标；找不到返回 -1。 */
    fun indexOfGroup(rows: List<Any>, key: String): Int =
        rows.indexOfFirst { it is Group && it.key == key }
}
