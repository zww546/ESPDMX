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
    /** 按属性名查找通道号（1-based），找不到返 null。 */
    fun findCh(attribute: String): Int? {
        val key = attribute.lowercase()
        return channels.find { it.originalName.lowercase().contains(key) ||
            it.name.lowercase().contains(key) }?.number
    }

    /** 按属性名查找带 fine 的通道对（coarse, fine），无 fine 则 fine=null。 */
    fun findChFine(attribute: String): Pair<Int, Int?>? {
        val key = attribute.lowercase()
        val ch = channels.find { it.originalName.lowercase().contains(key) ||
            it.name.lowercase().contains(key) } ?: return null
        return ch.number to (if (ch.hasFine) ch.fineNumber else null)
    }
}

/**
 * 灯具实例（Patch）——同型号多台 = 多个实例不同起始地址。
 * 每个实例有独立 DMX 起始地址，控制时写入 startAddr + ch - 1。
 */
data class FixtureInstance(
    val id: String,          // 实例唯一 ID
    val fixtureId: String,   // 所属灯型 FixtureDef.id
    val name: String,        // 实例名，如 "EOS-1"
    val startAddr: Int,      // DMX 起始地址 1..512
    val slot: Int = 0        // 板载槽位 0..3（效果/程序），创建时分配，删除不重排
)

/** 板载槽位总数（固件 FX_MAX_COUNT / PROG_MAX_COUNT = 8，跟随实例数量）。 */
const val SLOT_COUNT = 8

class FixtureStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fixtures", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "fixtures").also { it.mkdirs() }

    // ---------- 灯具实例（Patch）----------
    private val keyInstances = "instances"

    /** 全部灯具实例，按起始地址排序。 */
    fun instances(): List<FixtureInstance> {
        val raw = prefs.getString(keyInstances, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FixtureInstance(
                    id = o.getString("id"),
                    fixtureId = o.getString("fixtureId"),
                    name = o.getString("name"),
                    startAddr = o.getInt("startAddr"),
                    // 旧数据无 slot 字段：按索引分配（仅迁移用）
                    slot = if (o.has("slot")) o.getInt("slot") else i % SLOT_COUNT
                )
            }.sortedBy { it.startAddr }
        } catch (_: Exception) { emptyList() }
    }

    private fun persistInstances(list: List<FixtureInstance>) {
        val arr = JSONArray()
        list.forEach { i ->
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("fixtureId", i.fixtureId)
                put("name", i.name)
                put("startAddr", i.startAddr)
                put("slot", i.slot)
            })
        }
        prefs.edit().putString(keyInstances, arr.toString()).apply()
    }

    /** 分配最小空闲槽位（删除的实例释放后新实例可复用该槽）。 */
    private fun nextFreeSlot(list: List<FixtureInstance>): Int? {
        val used = list.map { it.slot }.toSet()
        for (s in 0 until SLOT_COUNT) if (s !in used) return s
        return null  // 槽位全满
    }

    /** 添加实例。返回 null 表示成功，否则返回错误提示。 */
    fun addInstance(fixtureId: String, name: String, startAddr: Int): String? {
        val addr = startAddr.coerceIn(1, 512)
        val def = fixtures.find { it.id == fixtureId } ?: return "灯型不存在"
        // 校验范围：起始地址 + 通道数 - 1 不超过 512
        if (addr + def.channelCount - 1 > 512) {
            return "地址 ${addr} 超出范围（${def.channelCount}CH 需要 ${addr}~${addr + def.channelCount - 1}）"
        }
        // 校验与已有实例重叠
        val list = instances().toMutableList()
        for (it in list) {
            val itDef = fixtures.find { f -> f.id == it.fixtureId } ?: continue
            val a1 = it.startAddr
            val a2 = it.startAddr + itDef.channelCount - 1
            val b1 = addr
            val b2 = addr + def.channelCount - 1
            if (b1 <= a2 && a1 <= b2) {
                return "与 ${it.name}（@${a1}~${a2}）地址重叠"
            }
        }
        val id = "inst_${System.currentTimeMillis()}"
        val slot = nextFreeSlot(list) ?: return "实例数已达上限（$SLOT_COUNT 个），请先删除部分实例"
        list.add(FixtureInstance(id, fixtureId, name, addr, slot))
        persistInstances(list)
        return null
    }

    fun updateInstance(inst: FixtureInstance) {
        val list = instances().map { if (it.id == inst.id) inst else it }
        persistInstances(list)
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

    /** 导入灯库文件（自动识别 ZIP 或单文件 XML/D4/R20）。同时保存原始文件用于导出/上传。 */
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

    private fun importFromZip(data: ByteArray): List<FixtureDef> {
        val result = mutableListOf<FixtureDef>()
        java.io.ByteArrayInputStream(data).use { bis ->
            ZipInputStream(bis).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val raw = zip.readBytes()
                    val ln = entry.name.lowercase()
                    when {
                        ln.endsWith(".xml") -> importFromXml(raw, entry.name).let { result.addAll(it) }
                        ln.endsWith(".d4") -> importFromD4(raw, entry.name).let { result.addAll(it) }
                        ln.endsWith(".r20") -> importFromR20(raw, entry.name).let { result.addAll(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        return result
    }

    private fun importFromXml(raw: ByteArray, fileName: String): List<FixtureDef> {
        val xml = stripBom(raw)
        val def = FixtureParser.parseMa2Xml(xml) ?: return emptyList()
        saveRaw(def, ".xml", raw)
        return listOf(def)
    }

    private fun importFromD4(raw: ByteArray, fileName: String): List<FixtureDef> {
        val txt = stripBom(raw)
        val def = FixtureParser.parseD4(txt) ?: return emptyList()
        saveRaw(def, ".d4", raw)
        return listOf(def)
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
     * 当 versionCode 变化时，用所有已保存的 XML 重新生成 JSON，
     * 确保 FixtureParser 的改进自动生效。
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
        dir.listFiles()?.filter { it.extension == "xml" }?.forEach { xmlFile ->
            try {
                val raw = xmlFile.readBytes()
                val xml = stripBom(raw)
                val def = FixtureParser.parseMa2Xml(xml)
                if (def != null) {
                    File(dir, "${def.id}.json").writeText(fixtureToJson(def).toString(2))
                    count++
                }
            } catch (_: Exception) {}
        }
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
