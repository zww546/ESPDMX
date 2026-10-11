package com.example.stagedmx

/**
 * 灯库「原始格式」页签的判定逻辑（纯逻辑，可单测）。
 *
 * ## 为什么需要它
 *
 * 同一款灯在灯库目录里常常**同时存在三份**不同格式的灯库，文件名还往往一样：
 *
 * ```
 * omarte_ares_fp3000_34ch.xml   ← MA2（attribute = BLADE1A / SHAPER ROT）
 * omarte_ares_fp3000_34ch.d4    ← Avolites Titan（老虎）
 * omarte_ares_fp3000_34ch_r20.r20 ← Avolites Pearl（珍珠）
 * ```
 *
 * 全塞进一个列表时，用户**分不清哪一条是哪份**，而这三份的通道语义可能并不相同
 * （例如切割片：MA2 是 `BLADE1A/1B` 两端模型，珍珠/老虎那两份是 `blade_1 + blade_1_rot`
 * 的"偏移+角度"）。选错格式，推子推得动、灯却不听 —— 这类问题现场极难判断。
 *
 * 所以列表按格式分页签，并且**每个入口（灯库页 / 建实例 / RDM 建实例）共用同一份选择**，
 * 免得用户在三个地方各选一次。
 *
 * ## 页签语义
 *
 *  - [ALL]（空串）：不筛，全部列出（默认入口，避免"灯看不见"）
 *  - `xml` / `d4` / `r20`：**手上真有该格式原始文件**的灯型
 *  - [OTHER]：没有任何原始文件的灯型 —— 从 ZIP 一次导入多个灯型时，
 *    只有第一个会留下原始文件，其余只存 App 自己的 JSON（见 `FixtureStore.importFromZip`）
 */
object FixtureFormats {

    /** 不筛选（"全部"）。 */
    const val ALL = ""

    /** 没有原始文件的那些（只有 App JSON）。 */
    const val OTHER = "other"

    /** 真正的原始格式（顺序 = 页签顺序）。 */
    val KNOWN = listOf("xml", "d4", "r20")

    /** 页签顺序：全部在前，其他在最后。 */
    val TABS = listOf(ALL) + KNOWN + listOf(OTHER)

    /** 页签文案。带上是哪家的格式，免得用户记不住 D4/R20 是谁的。 */
    fun label(tab: String): String = when (tab) {
        ALL -> "全部"
        "xml" -> "XML·MA2"
        "d4" -> "D4·老虎"
        "r20" -> "R20·珍珠"
        else -> "其他"
    }

    /**
     * 该灯型是否属于这个页签。
     *
     * @param formats 该灯型**实际拥有**的原始格式集合（由调用方查文件系统给出）；
     *                空集合 = 只有 App JSON ⇒ 归到 [OTHER]
     */
    fun matches(tab: String, formats: Set<String>): Boolean = when (tab) {
        ALL -> true
        OTHER -> formats.isEmpty()
        else -> tab in formats
    }

    /**
     * 按页签过滤。
     *
     * @param formatsOf 取"该灯型有哪些格式"的函数（真实实现要读文件系统，所以由调用方注入，
     *                  这样本对象保持纯逻辑、可单测）
     */
    fun filter(
        tab: String,
        defs: List<FixtureDef>,
        formatsOf: (FixtureDef) -> Set<String>,
    ): List<FixtureDef> = if (tab == ALL) defs else defs.filter { matches(tab, formatsOf(it)) }

    /**
     * 在候选灯型里**优先**挑指定格式的那一份（RDM 自动匹配用）。
     *
     * ⚠ 是"优先"不是"只认"：选中的格式若没有对应灯型（或选的是"全部"），
     *   就原样返回 —— 否则用户换个页签就再也匹配不上任何灯，比不筛还糟。
     */
    fun prefer(
        tab: String,
        defs: List<FixtureDef>,
        formatsOf: (FixtureDef) -> Set<String>,
    ): List<FixtureDef> {
        if (tab == ALL || tab == OTHER) return defs
        val hit = defs.filter { tab in formatsOf(it) }
        return hit.ifEmpty { defs }
    }
}
