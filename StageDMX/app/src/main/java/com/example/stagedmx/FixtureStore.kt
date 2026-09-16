package com.example.stagedmx

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 灯具通道定义。
 */
data class FixtureChannel(
    val number: Int,         // 1-based DMX 通道
    val name: String,        // 中文名
    val originalName: String,// 原始英文名（用于关闭翻译时显示）
    val attribute: String = "", // MA 标准 attribute（COLOR1/GOBO1/PAN...），用于上下文翻译
    val defaultValue: Int,   // 0..255
    val highlightValue: Int, // 0..255
    val hasFine: Boolean = false,
    val fineNumber: Int? = null,
    val physFrom: Float = 0f,  // 物理起始值（度）
    val physTo: Float = 0f     // 物理终止值（度）
)

data class FixtureDef(
    val id: String,
    val name: String,
    val manufacturer: String,
    val mode: String,
    val channelCount: Int,
    val channels: List<FixtureChannel>,
    val panRange: Float = 0f,       // PAN 行程（度），如 540.0
    val tiltRange: Float = 0f,      // TILT 行程（度），如 270.0
    val ptSpeedCh: Int? = null      // Pan/Tilt Speed 通道号，没有则为 null
) {
    /**
     * 按属性名查找通道号（1-based），找不到返 null。
     *
     * 匹配优先级（重要）：
     *   1) 标准 attribute 字段精确匹配（MA2 XML / D4 的 attribute 如 BLADE1A、SHAPER ROT）
     *   2) attribute 包含匹配
     *   3) 用户通道名/原始名精确匹配
     *   4) 用户通道名/原始名包含匹配
     *
     * 之所以必须先看 attribute：MA2 灯库把切割片命名成 "1A"/"1B"…、切割旋转命名成 "Index"，
     * 而 attribute 才是唯一可靠的英文标识（BLADE1A / SHAPER ROT）。早期只按名字做包含匹配，
     * 导致 blade、shaper_rot 一律找不到通道 → 切割循环下发全 0 通道 → 效果“无法使用”。
     */
    fun findCh(attribute: String): Int? = findChFine(attribute)?.first

    /** 按属性名查找带 fine 的通道对（coarse, fine），无 fine 则 fine=null。 */
    fun findChFine(attribute: String): Pair<Int, Int?>? {
        val key = attribute.lowercase()
        val norm = normalizeKey(key)
        fun attrNorm(c: FixtureChannel) = normalizeKey(c.attribute)
        fun nameNorm(c: FixtureChannel) = normalizeKey(c.originalName)
        fun zhNorm(c: FixtureChannel) = normalizeKey(c.name)

        val ch = channels.firstOrNull { attrNorm(it) == norm }
            ?: channels.firstOrNull { attrNorm(it).contains(norm) }
            ?: channels.firstOrNull { nameNorm(it) == norm || zhNorm(it) == norm }
            ?: channels.firstOrNull { nameNorm(it).contains(key) || zhNorm(it).contains(key) }
            ?: return null
        return ch.number to (if (ch.hasFine) ch.fineNumber else null)
    }

    companion object {
        /** 归一化：小写 + 去掉空格/下划线/连字符，便于 BLADE1A == blade1a == "Blade 1A"。 */
        fun normalizeKey(s: String): String =
            s.lowercase().replace(" ", "").replace("_", "").replace("-", "")
    }
}

/**
 * 灯具实例（Patch）——同型号多台 = 多个实例不同起始地址。
 *
 * **双宇宙按 A/B 两条通道分，各自从 1 编号到 512**（与 MA2/Titan 的 universe 模型一致）：
 *   A = 宇宙 1（UART1 / GPIO17），地址 1~512
 *   B = 宇宙 2（UART2 / GPIO18），地址 1~512
 *
 * 对外统一换算成**全局通道 1~1024**（协议与效果计算都用它）：
 *   `globalAddr() = (universe-1)×512 + addr`
 *
 * 好处：一台灯天然不可能跨宇宙 —— 每个宇宙只有 512 个地址，放不下就是放不下，
 * 不会出现"灯的通道一半在 A 线一半在 B 线"这种物理上接不出来的 patch。
 */
data class FixtureInstance(
    val id: String,          // 实例唯一 ID
    val fixtureId: String,   // 所属灯型 FixtureDef.id
    val name: String,        // 实例名，如 "EOS-1"
    val addr: Int,           // **本宇宙内**起始地址 1..512
    val slot: Int = 0,       // 板载槽位 0..7（效果/程序），创建时分配，删除不重排
    val universe: Int = 1    // 1 = A 通道，2 = B 通道
) {
    /** 全局通道号（1..1024）。 */
    fun globalAddr(): Int =
        (universe.coerceIn(1, DmxProtocol.UNIVERSES) - 1) * DmxProtocol.UNIVERSE_SIZE +
            addr.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)

    /** 通道字母：A / B */
    val band: String get() = if (universe <= 1) "A" else "B"

    /** 显示用："A@128" */
    fun label(): String = "$band@$addr"
}

/**
 * 板载**程序槽**总数（固件 PROG_MAX_COUNT = 8）。
 *
 * ⚠ 这**不是**实例数上限：一台灯可以有很多实例（一个宇宙 512 地址，
 * 20ch 灯能放 25 台），而"同时上传到板子运行的独立程序"才有 8 个槽。
 * 早期版本把两者混用，导致最多只能建 8 个实例 —— 那是 bug。
 */
const val PROG_SLOT_COUNT = 8

/**
 * 单次批量添加的台数上限（只是 UI 一次操作的上限，**不是实例总数上限**）。
 * 实例总数由地址空间决定：一个宇宙 512 地址，20ch 灯能放 25 台、18ch 能放 28 台，
 * A+B 两个宇宙合计约 50 台。
 */
const val MAX_BATCH_ADD = 64

class FixtureStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fixtures", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "fixtures").also { it.mkdirs() }

    // ---------- 灯具实例（Patch）----------
    private val keyInstances = "instances"

    /** 全部灯具实例，按全局起始地址排序。 */
    fun instances(): List<FixtureInstance> {
        val raw = prefs.getString(keyInstances, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                // 兼容两种旧格式：
                //   v24「universe + startAddr(1..512)」→ 直接就是 A/B + 地址
                //   v25「startAddr = 全局通道 1..1024」→ 拆回 A/B + 地址
                val uniRaw = if (o.has("universe")) o.getInt("universe").coerceIn(1, DmxProtocol.UNIVERSES) else 0
                val stored = o.getInt("startAddr")
                val uni: Int
                val addr: Int
                if (uniRaw > 0) {
                    uni = uniRaw
                    addr = stored.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
                } else {
                    uni = if (stored > DmxProtocol.UNIVERSE_SIZE) 2 else 1
                    addr = (if (uni > 1) stored - DmxProtocol.UNIVERSE_SIZE else stored)
                        .coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
                }
                FixtureInstance(
                    id = o.getString("id"),
                    fixtureId = o.getString("fixtureId"),
                    name = o.getString("name"),
                    addr = addr,
                    universe = uni,
                    // 程序槽：只用于上传板载程序，超出 8 个时取值 0（不影响实例本身）
                    slot = (if (o.has("slot")) o.getInt("slot") else i) % PROG_SLOT_COUNT
                )
            }.sortedBy { it.globalAddr() }
        } catch (_: Exception) { emptyList() }
    }

    private fun persistInstances(list: List<FixtureInstance>) {
        val arr = JSONArray()
        list.forEach { i ->
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("fixtureId", i.fixtureId)
                put("name", i.name)
                put("startAddr", i.addr)        // 本宇宙内地址 1..512
                put("universe", i.universe)     // 1=A, 2=B
                put("slot", i.slot)
            })
        }
        prefs.edit().putString(keyInstances, arr.toString()).apply()
    }

    /**
     * 分配一个板载**程序槽**（0..7）。
     *
     * 槽位是给"上传到板子运行的独立程序"用的，**不限制实例数量**：
     * 槽位用满时返回 0（多个实例共用一个槽，后上传的覆盖先前的）。
     * 这样实例可以照常建几百台，只是同时能跑的独立程序仍受固件 8 槽限制。
     */
    private fun nextFreeSlot(list: List<FixtureInstance>): Int {
        val used = list.map { it.slot }.toSet()
        for (s in 0 until PROG_SLOT_COUNT) if (s !in used) return s
        return 0
    }

    /** 添加单台（兼容旧调用）。 */
    fun addInstance(fixtureId: String, name: String, addr: Int, universe: Int = 1): String? =
        addInstances(fixtureId, name, addr, 1, universe)

    /**
     * 批量添加实例：在**同一个宇宙（A 或 B）内**按灯型通道数等距铺开。
     *
     * 第 k 台的地址 = `起始地址 + k × 灯型通道数`，全部落在本宇宙 1..512 内。
     * 因为一台灯的通道不可能跨宇宙（现场每个宇宙是一条独立的 DMX 线，
     * 一台灯只有一组 DMX 输入口），放不下就直接报错，不做自动挪动。
     *
     * @return null 表示成功；否则是错误提示（越界 / 重叠）。
     *         先整体校验再落盘，避免"建了一半失败"。
     */
    fun addInstances(fixtureId: String, namePrefix: String,
                     startAddr: Int, count: Int, universe: Int = 1): String? {
        val uni = universe.coerceIn(1, DmxProtocol.UNIVERSES)
        val band = if (uni == 1) "A" else "B"
        val def = fixtures.find { it.id == fixtureId } ?: return "灯型不存在"
        // 单次最多建 64 台（受本宇宙地址空间自然限制，20ch 灯上限 25 台）
        val n = count.coerceIn(1, MAX_BATCH_ADD)
        val pitch = def.channelCount.coerceAtLeast(1)
        if (pitch > DmxProtocol.UNIVERSE_SIZE) {
            return "该灯型有 $pitch 个通道，超过单个宇宙的 ${DmxProtocol.UNIVERSE_SIZE} 通道，无法 patch"
        }
        val addr0 = startAddr.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)

        // 先整体校验（都在本宇宙内 + 不与已有实例重叠），再落盘
        val list = instances().toMutableList()
        val occupied = list.filter { it.universe == uni }.mapNotNull { it ->
            val d = fixtures.find { f -> f.id == it.fixtureId } ?: return@mapNotNull null
            it.addr to (it.addr + d.channelCount - 1)
        }
        for (k in 0 until n) {
            val b1 = addr0 + k * pitch
            val b2 = b1 + pitch - 1
            if (b2 > DmxProtocol.UNIVERSE_SIZE) {
                return "$band 通道地址不足：第 ${k + 1} 台需要 $b1~$b2，" +
                       "本宇宙上限 ${DmxProtocol.UNIVERSE_SIZE}（可减少数量或把起始地址提前）"
            }
            for ((a1, a2) in occupied) {
                if (b1 <= a2 && a1 <= b2) return "$band 通道 $b1~$b2 与已有实例（$a1~$a2）重叠"
            }
        }

        for (k in 0 until n) {
            list.add(FixtureInstance("inst_${System.currentTimeMillis()}_$k", fixtureId,
                                     "$namePrefix-${k + 1}", addr0 + k * pitch,
                                     nextFreeSlot(list), uni))
        }
        persistInstances(list)
        return null
    }

    /** 删除多个实例（批量删除）。 */
    fun removeInstances(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        persistInstances(instances().filterNot { it.id in set })
    }

    /** 清空全部实例。 */
    fun removeAllInstances() = persistInstances(emptyList())

    // 注：原先这里有一个 planAddInstances()（算预览地址），已删除 ——
    // 该算法统一到 InstanceForm（纯逻辑 + 单测），避免预览与落盘两处算术漂移。
    // 落盘仍走下面的 addInstances()，它自己会做一次完整的边界与重叠校验。

    fun updateInstance(inst: FixtureInstance) {
        val def = fixtures.find { it.id == inst.fixtureId } ?: return
        val pitch = def.channelCount.coerceAtLeast(1)
        val uni = inst.universe.coerceIn(1, DmxProtocol.UNIVERSES)
        val addr = inst.addr.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
        if (addr + pitch - 1 > DmxProtocol.UNIVERSE_SIZE) return   // 越界，忽略
        val fixed = inst.copy(addr = addr, universe = uni)
        val list = instances().toMutableList()
        // 只与**同一个宇宙内**的实例比对重叠（A 和 B 是两条独立的线，可以重复）
        for (it in list) {
            if (it.id == fixed.id || it.universe != uni) continue
            val itDef = fixtures.find { f -> f.id == it.fixtureId } ?: continue
            val a1 = it.addr; val a2 = a1 + itDef.channelCount - 1
            if (addr <= a2 && a1 <= addr + pitch - 1) return  // 重叠，拒绝更新
        }
        persistInstances(list.map { if (it.id == fixed.id) fixed else it })
    }

    fun deleteInstance(id: String) {
        persistInstances(instances().filter { it.id != id })
    }

    /** 按实例 ID 取灯型定义（找不到返 null）。 */
    fun fixtureOf(inst: FixtureInstance): FixtureDef? =
        fixtures.find { it.id == inst.fixtureId }

    val fixtures: List<FixtureDef>
        get() {
            val list = mutableListOf<FixtureDef>()
            dir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
                try { list.add(parseFixtureJson(f.readText())) } catch (_: Exception) {}
            }
            return list.sortedBy { it.name }
        }

    /** 指定原始格式（"xml"/"d4"/"r20"）的灯库列表（按是否保存了该格式原始文件过滤）。 */
    fun fixturesOfFormat(ext: String): List<FixtureDef> =
        fixtures.filter { File(dir, "${it.id}.$ext").exists() }

    var currentFixtureId: String?
        get() = prefs.getString("current_fixture", null)
        set(value) = prefs.edit().putString("current_fixture", value).apply()

    val currentFixture: FixtureDef?
        get() {
            val id = currentFixtureId ?: return null
            return fixtures.find { it.id == id }
        }

    /**
     * 导入灯库文件（自动识别 ZIP 或单文件 XML/D4/R20）。同时保存原始文件用于导出/上传。
     *
     * ⚠ 这是「直接导」，不做预检。需要先让用户看清压缩包里每个文件的结果时，
     *   用 [inspect] 拿到 [ZipScan.Report]，确认后再调 [importScanned]。
     */
    fun importFile(input: java.io.InputStream, fileName: String = ""): List<FixtureDef> {
        val raw = input.readBytes()
        // 检测文件类型：ZIP 以 PK 开头
        val isZip = raw.size >= 2 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()
        if (isZip) {
            return importFromZip(raw)
        }
        val ln = fileName.lowercase()
        return when {
            ln.endsWith(".d4") -> importFromD4(raw, fileName)
            ln.endsWith(".r20") -> importFromR20(raw, fileName)
            else -> importFromXml(raw, fileName)
        }
    }

    /**
     * 导入前体检：枚举压缩包内**每一个**条目并判定，不做任何写盘。
     *
     * 单文件（非 zip）也会返回一份单条目报告，界面可以统一处理。
     */
    fun inspect(input: java.io.InputStream, fileName: String = ""): ZipScan.Report {
        val raw = input.readBytes()
        val isZip = raw.size >= 2 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()
        if (isZip) return ZipScan.scanZip(raw, fileName)

        // 单文件：沿用与 importFile 相同的兜底判定
        val entry = ZipScan.classify(fileName.ifEmpty { "fixture" }, isDirectory = false, bytes = raw)
        return ZipScan.Report(fileName, listOf(entry))
    }

    /**
     * 按体检报告执行导入（用户确认后调用）。
     *
     * 只处理报告里标记为可导入的条目，并**按内容**分派 —— 不再看扩展名，
     * 这样"后缀写错但内容正确"的文件也能进得来。
     *
     * @param data 压缩包的原始字节（与 [scanZip] 用的是同一份）
     * @return 成功导入的灯具；解析失败的条目会出现在 [ImportResult.failed] 里
     */
    fun importScanned(data: ByteArray, report: ZipScan.Report): ImportResult {
        val isZip = data.size >= 2 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()
        if (!isZip) {
            // 单文件路径
            val name = report.entries.firstOrNull()?.path ?: ""
            val defs = try {
                val ln = name.lowercase()
                when {
                    ln.endsWith(".d4") -> importFromD4(data, name)
                    ln.endsWith(".r20") -> importFromR20(data, name)
                    else -> importFromXml(data, name)
                }
            } catch (e: Exception) {
                return ImportResult(emptyList(), listOf(name to e.describe()))
            }
            return ImportResult(defs, if (defs.isEmpty()) listOf(name to "解析不出任何灯型") else emptyList())
        }

        val result = mutableListOf<FixtureDef>()
        val failed = mutableListOf<Pair<String, String>>()
        // 只挑可导入的条目名，避免把整包重新解析一遍
        val wanted = report.importableFiles.map { it.path }.toHashSet()
        if (wanted.isEmpty()) return ImportResult(emptyList(), emptyList())

        java.io.ByteArrayInputStream(data).use { bis ->
            ZipInputStream(bis).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name in wanted) {
                        val raw = zip.readBytes()
                        try {
                            // 按内容分派（detectAndParse 在体检阶段已确认过格式）
                            val (kind, _) = ZipScan.detectAndParse(raw)
                            when (kind) {
                                ZipScan.KIND_XML -> result.addAll(importFromXml(raw, entry.name))
                                ZipScan.KIND_D4 -> result.addAll(importFromD4(raw, entry.name))
                                ZipScan.KIND_R20 -> result.addAll(importFromR20(raw, entry.name))
                                else -> failed.add(entry.name to "内容格式已无法识别")
                            }
                        } catch (e: Exception) {
                            failed.add(entry.name to e.describe())
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return ImportResult(result, failed)
    }

    /** 导入结果：成功的灯具 + 逐条失败原因（失败不再是"悄悄没了"）。 */
    data class ImportResult(
        val fixtures: List<FixtureDef>,
        val failed: List<Pair<String, String>>   // 文件路径 → 原因
    )

    private fun Exception.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun importFromZip(data: ByteArray): List<FixtureDef> {
        val result = mutableListOf<FixtureDef>()
        java.io.ByteArrayInputStream(data).use { bis ->
            ZipInputStream(bis).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val raw = zip.readBytes()
                    // v7：按**内容**分派，不再只看扩展名 —— 后缀写错但内容正确的
                    // 文件以前会被静默丢掉（表现为"压缩包里明明有灯库却导入 0 个"）。
                    val (kind, _) = ZipScan.detectAndParse(raw)
                    when (kind) {
                        ZipScan.KIND_XML -> result.addAll(importFromXml(raw, entry.name))
                        ZipScan.KIND_D4 -> result.addAll(importFromD4(raw, entry.name))
                        ZipScan.KIND_R20 -> result.addAll(importFromR20(raw, entry.name))
                    }
                    zip.closeEntry()
                }
            }
        }
        return result
    }

    private fun importFromXml(raw: ByteArray, fileName: String): List<FixtureDef> {
        val xml = stripBom(raw)
        val defs = FixtureParser.parseMa2XmlAll(xml)
        if (defs.isEmpty()) return emptyList()
        // 原始文件按“首个灯型”的 id 保存一份（导出/上传时按灯型 id 查回）
        saveRaw(defs.first(), ".xml", raw)
        // 同一文件里的其它灯型（多模式）只写 json，原始文件共用同一份
        for (d in defs.drop(1)) File(dir, "${d.id}.json").writeText(fixtureToJson(d).toString(2))
        return defs
    }

    private fun importFromD4(raw: ByteArray, fileName: String): List<FixtureDef> {
        val txt = stripBom(raw)
        val defs = FixtureParser.parseD4All(txt)
        if (defs.isEmpty()) return emptyList()
        saveRaw(defs.first(), ".d4", raw)
        for (d in defs.drop(1)) File(dir, "${d.id}.json").writeText(fixtureToJson(d).toString(2))
        return defs
    }

    private fun importFromR20(raw: ByteArray, fileName: String): List<FixtureDef> {
        val txt = stripBom(raw)
        val def = FixtureParser.parseR20(txt) ?: return emptyList()
        saveRaw(def, ".r20", raw)
        return listOf(def)
    }

    private fun stripBom(raw: ByteArray): String {
        return if (raw.size >= 3 && raw[0] == 0xEF.toByte()
            && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte())
            raw.copyOfRange(3, raw.size).toString(Charsets.UTF_8)
        else raw.toString(Charsets.UTF_8)
    }

    /** 该灯具保存的原始文件（.xml / .d4 / .r20，存在哪些返回哪些）。 */
    fun rawFiles(def: FixtureDef): List<File> =
        listOf("xml", "d4", "r20").mapNotNull { ext ->
            val f = File(dir, "${def.id}.$ext")
            if (f.exists()) f else null
        }

    /** 导出所有灯具为 ZIP（原格式 XML/D4/R20）。 */
    fun exportZip(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            dir.listFiles()?.filter { it.extension in listOf("xml", "d4", "r20") }?.forEach { f ->
                val jsonFile = File(dir, "${f.nameWithoutExtension}.json")
                if (jsonFile.exists()) {
                    zip.putNextEntry(ZipEntry(f.name))
                    zip.write(f.readBytes())
                    zip.closeEntry()
                }
            }
        }
    }

    /** 删除灯具（json + xml/d4/r20）。 */
    fun delete(id: String): Boolean {
        if (currentFixtureId == id) currentFixtureId = null
        // 同时删除该灯型的所有实例
        val insts = instances().filter { it.fixtureId != id }
        if (insts.size != instances().size) persistInstances(insts)
        var ok = false
        for (ext in listOf("json", "xml", "d4", "r20")) {
            if (File(dir, "$id.$ext").delete()) ok = true
        }
        return ok
    }

    /**
     * 检查是否需要因 App 升级而重新导入灯库。
     * 当 versionCode 变化时，用已保存的原始文件（xml/d4/r20）重新生成 JSON，
     * 确保 FixtureParser 的改进自动生效（尤其是历史版本漏读通道的 D4/R20 灯库）。
     * @return 重新导入的数量，-1 表示首次运行无需操作
     */
    fun checkAndReimport(context: Context): Int {
        val curVer = try {
            val pi: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionCode
        } catch (_: Exception) { 0 }
        val savedVer = prefs.getInt("last_version", 0)
        // 首次运行：直接记版本号，不做重导
        if (savedVer == 0) {
            prefs.edit().putInt("last_version", curVer).apply()
            return -1
        }
        if (savedVer >= curVer) return 0

        var count = 0
        // 用原始文件重新解析。若解析出的灯型 id 与原始文件名不再一致（解析规则修正后常见），
        // 说明旧 json 是错误解析的残留 → 删掉，避免灯库里出现重复/错位的灯型。
        fun reparse(ext: String, parse: (String) -> List<FixtureDef>) {
            dir.listFiles()?.filter { it.extension == ext }?.forEach { f ->
                try {
                    val defs = parse(stripBom(f.readBytes()))
                    if (defs.isEmpty()) return@forEach
                    defs.forEach { def ->
                        File(dir, "${def.id}.json").writeText(fixtureToJson(def).toString(2))
                        count++
                    }
                    val ids = defs.map { it.id }.toSet()
                    if (!ids.contains(f.nameWithoutExtension)) {
                        File(dir, "${f.nameWithoutExtension}.json").delete()
                    }
                } catch (_: Exception) {}
            }
        }
        reparse("xml") { FixtureParser.parseMa2XmlAll(it) }
        reparse("d4") { FixtureParser.parseD4All(it) }
        reparse("r20") { listOfNotNull(FixtureParser.parseR20(it)) }
        prefs.edit().putInt("last_version", curVer).apply()
        return count
    }

    private fun saveFixture(def: FixtureDef, rawXml: ByteArray) = saveRaw(def, ".xml", rawXml)

    private fun saveRaw(def: FixtureDef, ext: String, raw: ByteArray) {
        File(dir, "${def.id}.json").writeText(fixtureToJson(def).toString(2))
        File(dir, "${def.id}$ext").writeBytes(raw)
    }

    companion object {
        fun fixtureToJson(def: FixtureDef): JSONObject = JSONObject().apply {
            put("id", def.id)
            put("name", def.name)
            put("manufacturer", def.manufacturer)
            put("mode", def.mode)
            put("channelCount", def.channelCount)
            if (def.panRange > 0) put("panRange", def.panRange.toDouble())
            if (def.tiltRange > 0) put("tiltRange", def.tiltRange.toDouble())
            def.ptSpeedCh?.let { put("ptSpeedCh", it) }
            put("channels", JSONArray().apply {
                def.channels.forEach { ch ->
                    put(JSONObject().apply {
                        put("number", ch.number)
                        put("name", ch.name)
                        put("originalName", ch.originalName)
                        if (ch.attribute.isNotEmpty()) put("attribute", ch.attribute)
                        put("defaultValue", ch.defaultValue)
                        put("highlightValue", ch.highlightValue)
                        if (ch.hasFine) put("hasFine", true)
                        ch.fineNumber?.let { put("fineNumber", it) }
                        if (ch.physFrom != 0f || ch.physTo != 0f) {
                            put("physFrom", ch.physFrom.toDouble())
                            put("physTo", ch.physTo.toDouble())
                        }
                    })
                }
            })
        }

        fun parseFixtureJson(json: String): FixtureDef {
            val obj = JSONObject(json)
            val chArr = obj.getJSONArray("channels")
            val channels = (0 until chArr.length()).map { i ->
                val c = chArr.getJSONObject(i)
                FixtureChannel(
                    number = c.getInt("number"),
                    name = c.getString("name"),
                    originalName = c.optString("originalName", c.getString("name")),
                    attribute = c.optString("attribute", ""),
                    defaultValue = c.optInt("defaultValue", 0),
                    highlightValue = c.optInt("highlightValue", 255),
                    hasFine = c.optBoolean("hasFine", false),
                    fineNumber = if (c.has("fineNumber")) c.getInt("fineNumber") else null,
                    physFrom = c.optDouble("physFrom", 0.0).toFloat(),
                    physTo = c.optDouble("physTo", 0.0).toFloat()
                )
            }
            return FixtureDef(
                id = obj.getString("id"),
                name = obj.getString("name"),
                manufacturer = obj.optString("manufacturer", ""),
                mode = obj.optString("mode", ""),
                channelCount = obj.getInt("channelCount"),
                channels = channels,
                panRange = obj.optDouble("panRange", 0.0).toFloat(),
                tiltRange = obj.optDouble("tiltRange", 0.0).toFloat(),
                ptSpeedCh = if (obj.has("ptSpeedCh")) obj.getInt("ptSpeedCh") else null
            )
        }
    }
}
