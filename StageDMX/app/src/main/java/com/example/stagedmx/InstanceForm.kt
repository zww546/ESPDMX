package com.example.stagedmx

/**
 * 「添加灯具实例」表单的**纯逻辑**：地址/数量的夹取与预览文案。
 *
 * 抽出来的原因（God Activity 重构的一部分）：
 *  - 这段"这个灯型在本宇宙放不放得下、共几台、落在哪个地址段"的算术与文案
 *    原先内联在 MainActivity 的对话框构建里（约 60 行 lambda 套 lambda），
 *    与 Spinner/EditText/按钮样式混在一起，既无法单测也无法复用。
 *  - 它在 MainActivity 里**写了两份**（添加对话框 / 实例管理页），容易改一处漏一处。
 *
 * 这里只做纯计算 + 生成用户可读文案（[plan]/[summaryText]），不碰任何 View；
 * 真正的落盘仍由 FixtureStore.addInstances 负责（它自己会再整体校验一次）。
 *
 * @param fixtureChannelCount 该灯型占用的通道数（pitch）
 * @param startAddr 本宇宙内的起始地址（1..512）
 * @param count 台数
 * @param universe 1 = A 口（宇宙1），2 = B 口（宇宙2）
 */
class InstanceForm(
    private val fixtureChannelCount: Int,
    startAddr: Int,
    count: Int,
    universe: Int
) {
    val band: String = DmxProtocol.bandLabel(universe.coerceIn(1, DmxProtocol.UNIVERSES))

    /** 灯型通道数（至少 1）。 */
    val pitch: Int = fixtureChannelCount.coerceAtLeast(1)

    /** 夹取后的起始地址（本宇宙内 1..512）。 */
    val addr: Int = startAddr.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)

    /** 夹取后的台数（1..MAX_BATCH_ADD）。 */
    val numInstances: Int = count.coerceIn(1, MAX_BATCH_ADD)

    /** 灯型本身超过一个宇宙 → 无论怎么调都放不下。 */
    val fixtureTooWide: Boolean = pitch > DmxProtocol.UNIVERSE_SIZE

    /**
     * 每台的起始地址（本宇宙内）。算法与 FixtureStore.addInstances 的落盘布局一致：
     * 第 k 台 = addr + k × pitch（两边都用这个公式，改任一处必须同步）。
     */
    val plan: List<Int> =
        if (fixtureTooWide) emptyList()
        else (0 until numInstances).map { addr + it * pitch }

    /** 末台的结束地址（含）；plan 为空时为 0。 */
    val lastChannel: Int = if (plan.isEmpty()) 0 else plan.last() + pitch - 1

    /** 是否所有台都落在本宇宙内。 */
    val fits: Boolean = plan.isNotEmpty() && lastChannel <= DmxProtocol.UNIVERSE_SIZE

    /**
     * 预览文案。**与旧版逐字一致**（包括警告符号与括号措辞），只是搬到了这里。
     */
    fun summaryText(): String {
        if (fixtureTooWide) {
            return "⚠ 该灯型有 $pitch 个通道，超过单个宇宙的 ${DmxProtocol.UNIVERSE_SIZE}，无法 patch"
        }
        if (!fits) {
            return "⚠ $band 通道放不下：共 ${plan.size} 台需要 ${plan.first()}~$lastChannel，" +
                   "本宇宙上限 ${DmxProtocol.UNIVERSE_SIZE}"
        }
        return if (plan.size <= 1) {
            "$band 通道 ${plan[0]} ~ $lastChannel"
        } else {
            "共 ${plan.size} 台：$band 通道 ${plan.first()} ~ $lastChannel" +
            "（第 ${plan.size} 台起于 ${plan.last()}）"
        }
    }

    /** "该灯型占 N 个通道" 的说明行。 */
    fun fixtureHintText(): String =
        "该灯型占 $pitch 个通道（第 N 台的起始 = 起始地址 + (N-1) × $pitch）"

    /** 添加成功后的 toast 文案；未添加任何台时为 null。 */
    fun addedToast(fixtureName: String): String? = when {
        plan.isEmpty() -> null
        plan.size <= 1 -> "已添加 $fixtureName-1 $band@$addr"
        else -> "已添加 ${plan.size} 台：$band 通道 ${plan.first()}~$lastChannel"
    }

    companion object {
        /** 从灯型定义构造（UI 常用入口）。 */
        fun of(def: FixtureDef, startAddr: Int, count: Int, universe: Int): InstanceForm =
            InstanceForm(def.channelCount, startAddr, count, universe)
    }
}
