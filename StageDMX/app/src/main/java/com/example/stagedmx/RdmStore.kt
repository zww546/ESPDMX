package com.example.stagedmx

import android.content.Context
import android.content.SharedPreferences

/**
 * RDM 设备（Remote Device Management，ANSI E1.20）。
 *
 * 这些字段全部来自固件实际 GET 到的 RDM 参数（见 ble_dmx.c 的 rdm_send_device）。
 * 设备不支持的参数会返回空串/0，属正常情况。
 */
data class RdmDevice(
    val uid: String,              // "厂商ID:设备序号"，如 "OMAR:0012A4"
    val uidBytes: ByteArray = ByteArray(6),
    val manufacturer: String,
    val model: String,
    val address: Int,             // 当前 DMX 起始地址（本宇宙内 1..512）
    val channelCount: Int,        // 当前模式下占用的通道数（footprint）
    val universe: Int = 1,        // 1 = A 通道，2 = B 通道
    val personality: String = "", // 当前模式名
    val personalityNum: Int = 0,  // 当前模式号（1-based）
    val personalityCount: Int = 0,// 模式总数
    val modelId: Int = 0,
    val productCategory: Int = 0,
    val softwareVersionId: Long = 0,
    val softwareLabel: String = "",
    val modelDesc: String = "",
    val deviceLabel: String = "",
    val subDeviceCount: Int = 0,
    val sensorCount: Int = 0,
) {
    /** "A@1" / "B@128" */
    fun addrLabel(): String = "${DmxProtocol.bandLabel(universe)}@$address"

    fun footprint(): String = "$address ~ ${address + channelCount - 1}"

    /** 产品类别码 → 名称（E1.20 附录 B 常用项）。 */
    fun categoryName(): String = when (productCategory) {
        0x0101 -> "固定光斑"
        0x0102 -> "摇头灯"
        0x0103 -> "移动光束"
        0x0104 -> "染色灯"
        0x0105 -> "效果灯"
        0x0106 -> "频闪灯"
        0x0107 -> "矩阵灯"
        0x0201 -> "调光器"
        0x0301 -> "媒体服务器"
        0x0401 -> "烟雾机"
        0x0501 -> "其他"
        else -> "0x%04X".format(productCategory)
    }

    /** 供界面逐行展示"全部参数"用。 */
    fun detailLines(): List<Pair<String, String>> = listOf(
        "UID" to uid,
        "厂商" to manufacturer,
        "型号描述" to modelDesc,
        "设备标签" to deviceLabel,
        "型号 ID" to "0x%04X".format(modelId),
        "产品类别" to "${categoryName()} (0x%04X)".format(productCategory),
        "起始地址" to addrLabel(),
        "占用通道" to "$channelCount CH（${footprint()}）",
        "当前模式" to if (personality.isEmpty()) "模式 $personalityNum / $personalityCount"
                        else "$personality（$personalityNum / $personalityCount）",
        "软件版本" to if (softwareLabel.isEmpty()) "ID 0x%08X".format(softwareVersionId)
                        else "$softwareLabel（ID 0x%08X）".format(softwareVersionId),
        "子设备数" to subDeviceCount.toString(),
        "传感器数" to sensorCount.toString(),
    )
}

/**
 * RDM 开关的持久化。**扫描本身走 BLE**（见 MainActivity.doRdmScan）：
 * 固件用 esp_dmx 的 RDM 控制器 API 做设备发现并逐台 GET 参数，
 * 结果通过 0x89/0x8A 通知帧回传，App 只负责解析与展示。
 */
class RdmStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("rdm", Context.MODE_PRIVATE)

    /** 是否已启用 RDM（设置页开关）。 */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    /** 记住一台设备改过的新地址（固件已同步更新，这里用于界面刷新）。 */
    fun setCachedAddress(uid: String, universe: Int, addr: Int) {
        prefs.edit().putInt("addr_${universe}_$uid", addr).apply()
    }

    fun cachedAddress(uid: String, universe: Int): Int? =
        prefs.getInt("addr_${universe}_$uid", -1).takeIf { it > 0 }

    // ---- 用户拖出来的灯具顺序（UID 列表）----
    // 用 UID 而不是索引：灯换口、改地址之后仍然是同一台，顺序还能保住。

    /** 保存顺序（逗号分隔的 UID）。 */
    fun saveOrder(uids: List<String>) {
        prefs.edit().putString("order", uids.joinToString(",")).apply()
    }

    /** 读取保存的顺序；没有则返回空表。 */
    fun savedOrder(): List<String> =
        prefs.getString("order", "").orEmpty().split(",").filter { it.isNotEmpty() }
}

// ============================================================================
// 与固件交换的 RDM 帧
// ============================================================================

/** 请求扫描某宇宙的 RDM 总线。 */
fun encodeRdmScan(universe: Int): ByteArray =
    byteArrayOf(DmxProtocol.CMD_RDM_SCAN.toByte(), universe.toByte())

/** 识别（闪烁）：uid 6 字节。 */
fun encodeRdmIdentify(universe: Int, uid: ByteArray, on: Boolean): ByteArray {
    val out = ByteArray(9)
    out[0] = DmxProtocol.CMD_RDM_IDENTIFY.toByte()
    out[1] = universe.toByte()
    for (i in 0 until 6) out[2 + i] = uid.getOrElse(i) { 0 }
    out[8] = if (on) 1 else 0
    return out
}

/** 远程改地址。 */
fun encodeRdmSetAddress(universe: Int, uid: ByteArray, addr: Int): ByteArray {
    val out = ByteArray(10)
    out[0] = DmxProtocol.CMD_RDM_SET_ADDR.toByte()
    out[1] = universe.toByte()
    for (i in 0 until 6) out[2 + i] = uid.getOrElse(i) { 0 }
    out[8] = ((addr ushr 8) and 0xFF).toByte()
    out[9] = (addr and 0xFF).toByte()
    return out
}

/**
 * 批量改地址（`0x44`）：拖动排序后按顺序一次性写完整批。
 *
 * 帧: 0x44 universe count (uid(6) addrHi addrLo)*
 * 相比逐台发单条，**固件只暂停一次 DMX**，现场不会看到多次闪断。
 */
fun encodeRdmSetAddresses(universe: Int, list: List<Pair<ByteArray, Int>>): ByteArray {
    val n = list.size.coerceAtMost(32)
    val out = ByteArray(3 + n * 8)
    out[0] = DmxProtocol.CMD_RDM_SET_ADDRS.toByte()
    out[1] = universe.toByte()
    out[2] = n.toByte()
    for (i in 0 until n) {
        val (uid, addr) = list[i]
        for (k in 0 until 6) out[3 + i * 8 + k] = uid.getOrElse(k) { 0 }
        out[3 + i * 8 + 6] = ((addr ushr 8) and 0xFF).toByte()
        out[3 + i * 8 + 7] = (addr and 0xFF).toByte()
    }
    return out
}

/**
 * 按当前顺序把地址依次分配下去（自动排地址）。
 *
 * 规则：第 1 台从 [startAddr] 开始，每台占自己的 footprint，
 * 下一台紧接着上一台的末尾。
 *
 * 例（20ch / 20ch / 39ch，起始 1）：1~20、21~40、41~79
 *
 * @return 每台的 (uid, 新地址)；若任一台越界（超出 512）返回 null
 */
fun assignAddresses(devices: List<RdmDevice>, startAddr: Int): List<Pair<ByteArray, Int>>? {
    var cursor = startAddr.coerceAtLeast(1)
    val out = ArrayList<Pair<ByteArray, Int>>(devices.size)
    for (d in devices) {
        val ch = d.channelCount.coerceAtLeast(1)
        if (cursor + ch - 1 > DmxProtocol.UNIVERSE_SIZE) return null   // 本宇宙放不下
        out.add(d.uidBytes to cursor)
        cursor += ch
    }
    return out
}

/**
 * 按**分组**分配地址：每组有自己的起始地址。
 *
 * 组的先后 = 在 [devices] 里首次出现的次序（也就是排序后的顺序）；
 * 每组从自己的起始地址开始、按各自 footprint 顺延。
 * [starts] 里没有的组 = "自动接上一组的末尾"，第一组因此自然从 1 开始。
 *
 * 例：A 组 2 台 20ch（起始 1）、B 组 1 台 12ch（未设起始）
 *     → A: 1~20、21~40；B: 41~52
 *
 * @param groupOf 设备 → 组键
 * @param starts  组键 → 起始地址（缺省表示接上一组）
 * @param footprintOf 设备 → 实际占用通道数。
 *        ⚠ 默认是 RDM 报的占用通道数，但**配上灯库后调用方要传灯库的通道数**
 *          （见 MainActivity.rdmFootprint）——否则配出来的地址和按灯库加的实例会错位。
 * @param sameOf  组键 → 是否"全组同一地址"。true 时整组都指向起始地址、
 *        整组只占**一台**的宽度（后面那组才能紧跟着排）。
 * @return 每台的 (设备, 新地址)；任一台越界（超出 512）返回 null。
 *         ⚠ 占用通道数 ≤ 0 的设备（RDM DEVICE_INFO 没读到 → 参数未知）**不会**出现在
 *           结果里：它不占地址空间、也不会把后面各组的起始地址顶偏。调用方按
 *           "uid 不在表里" 处理（界面显示参数未知、跳过写入）。
 */
fun assignAddressesByGroup(
    devices: List<RdmDevice>,
    groupOf: (RdmDevice) -> String,
    starts: Map<String, Int>,
    footprintOf: (RdmDevice) -> Int = { it.channelCount },
    sameOf: (String) -> Boolean = { false },
): List<Pair<RdmDevice, Int>>? {
    // 0) 先把"参数未知"的剔掉
    val known = devices.filter { footprintOf(it) > 0 }
    if (known.isEmpty()) return emptyList()

    // 1) 组顺序 + 每组占用的通道跨度（"相同"模式下整组只占一台的宽度）
    val span = LinkedHashMap<String, Int>()
    val widest = HashMap<String, Int>()
    for (d in known) {
        val g = groupOf(d)
        val fp = footprintOf(d)
        span[g] = (span[g] ?: 0) + fp
        widest[g] = maxOf(widest[g] ?: 0, fp)
    }
    for (g in span.keys) if (sameOf(g)) span[g] = widest[g] ?: 1

    // 2) 每组落一个起始地址：设过用设的，没设的接上一组末尾
    val cursorOf = LinkedHashMap<String, Int>()
    var auto = 1
    for ((g, ch) in span) {
        val s = starts[g]?.coerceIn(1, DmxProtocol.UNIVERSE_SIZE) ?: auto
        cursorOf[g] = s
        auto = maxOf(auto, s + ch)
    }

    // 3) 逐台在**自己组内**顺延；"相同"模式的组全部指向同一个地址
    val out = ArrayList<Pair<RdmDevice, Int>>(known.size)
    for (d in known) {
        val g = groupOf(d)
        val a = cursorOf.getValue(g)
        val ch = footprintOf(d)
        if (a + ch - 1 > DmxProtocol.UNIVERSE_SIZE) return null   // 本宇宙放不下
        out.add(d to a)
        if (!sameOf(g)) cursorOf[g] = a + ch
    }
    return out
}

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
private fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
private fun ByteArray.u32(i: Int) =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or
    (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

/**
 * 解析固件回的 0x8A 设备帧（**全部** RDM GET 到的参数）。
 *
 * 布局（与 ble_dmx.c 的 rdm_send_device 一一对应）：
 *   [0] 0x8A  [1] idx  [2] universe
 *   [3..8]  uid(6)
 *   [9..10] 起始地址   [11..12] 占用通道数
 *   [13..14] 型号 ID   [15..16] 产品类别
 *   [17..20] 软件版本 ID
 *   [21] 当前模式号   [22] 模式总数
 *   [23..24] 子设备数 [25] 传感器数
 *   之后 5 段字符串：每段 len(1) + 内容
 *     = 厂商 / 型号描述 / 软件版本标签 / 设备标签 / 当前模式名
 */
fun parseRdmDevice(d: ByteArray): RdmDevice? {
    if (d.size < 26) return null
    val uid = ByteArray(6) { d[3 + it] }
    val uidStr = "%02X%02X:%02X%02X%02X%02X".format(
        uid[0], uid[1], uid[2], uid[3], uid[4], uid[5])
    var i = 26
    fun nextStr(): String {
        if (i >= d.size) return ""
        val n = d.u8(i); i++
        val end = (i + n).coerceAtMost(d.size)
        val s = String(d, i, end - i, Charsets.UTF_8)
        i = end
        return s.trim()
    }
    val manufacturer = nextStr()
    val modelDesc = nextStr()
    val swLabel = nextStr()
    val deviceLabel = nextStr()
    val personalityDesc = nextStr()
    return RdmDevice(
        uid = uidStr,
        uidBytes = uid,
        manufacturer = manufacturer.ifEmpty { "未知厂商" },
        model = deviceLabel.ifEmpty { modelDesc.ifEmpty { "未知型号" } },
        address = d.u16(9),
        channelCount = d.u16(11),
        universe = if (d.u8(2) == 0) 1 else 2,
        personality = personalityDesc,
        personalityNum = d.u8(21),
        personalityCount = d.u8(22),
        modelId = d.u16(13),
        productCategory = d.u16(15),
        softwareVersionId = d.u32(17),
        softwareLabel = swLabel,
        modelDesc = modelDesc,
        deviceLabel = deviceLabel,
        subDeviceCount = d.u16(23),
        sensorCount = d.u8(25),
    )
}
