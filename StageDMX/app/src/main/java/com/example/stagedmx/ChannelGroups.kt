package com.example.stagedmx

/**
 * 推子页"按功能分组"的分组规则。
 *
 * ## 为什么单独抽一个 object
 * 原来这套 `when` 内联在 [ChannelAdapter] 里 —— 而 ChannelAdapter 是
 * `RecyclerView.Adapter` 的子类，单测它需要 Robolectric/仪器测试。
 * 分组本身是**纯字符串判断**，抽出来后用普通 JUnit 就能覆盖。
 *
 * ## ⚠ 顺序是承重设计，别随手调
 * `when` 是**短路求值**，规则顺序决定归属。历史上踩过：
 * "图案" 的规则里含 `GOBO|FOCUS…` 这类宽泛词，如果放在前面，
 * `BLADE1` / `PRISM1` 会先被它吃掉 —— 加了"切割/棱镜"分支却永远匹配不到。
 * 所以：**切割、棱镜必须排在图案之前**，[ChannelGroupsTest] 锁住了这一点。
 */
object ChannelGroups {

    /** 组的固定顺序（同功能的通道始终挨在一起）。 */
    val ORDER = listOf("亮度", "位置", "颜色", "图案", "切割", "棱镜", "其他")

    /**
     * 判断某个通道属于哪个功能组。
     *
     * @param attr MA attribute（最可靠，优先匹配）
     * @param name 中文通道名
     * @param orig 原始英文通道名
     * @return [ORDER] 中的某一项；都不匹配时返回 "其他"
     */
    fun of(attr: String, name: String, orig: String): String {
        val s = "${attr.uppercase()} ${name.uppercase()} ${orig.uppercase()}"
        // ⚠ 用 contains 而不是 Regex：
        // ⚠ 关键词表里**必须同时有 ASCII 和中文**：
        //   真实灯库里有些通道只有中文名、没有 MA attribute（实测 "4.切割2" / "色盘" /
        //   "22.棱镜1"），只匹配英文关键词会把它们全丢进"其他"。
        //   顺序仍然承重：切割/棱镜必须在图案之前（见类注释）。
        return when {
            // 顺序承重：切割/棱镜必须在图案之前
            listOf("DIM", "SHUTTER", "STROBE", "MASTER", "INTENSITY", "调光", "频闪").any { s.contains(it) } -> "亮度"
            listOf("PAN", "TILT", "PTSPEED", "PT SPEED", "MOVE", "水平", "垂直").any { s.contains(it) } -> "位置"
            listOf("COLOR", "COLOUR", "CTO", "CTB", "RED", "GREEN", "BLUE", "WHITE",
                   "AMBER", "CYAN", "MAGENTA", "RGB", "色盘", "色温", "色片").any { s.contains(it) } -> "颜色"
            // 这两条必须在"图案"之前（见类注释）
            listOf("BLADE", "FRAMING", "SHAPE", "CUT", "切割").any { s.contains(it) } -> "切割"
            listOf("PRISM", "FROST", "棱镜", "雾化", "柔光").any { s.contains(it) } -> "棱镜"
            listOf("GOBO", "FOCUS", "ZOOM", "IRIS", "EFFECT", "图案", "调焦", "放大", "光圈").any { s.contains(it) } -> "图案"
            else -> "其他"
        }
    }
}
