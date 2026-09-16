package com.example.stagedmx

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 压缩包「导入前体检」—— 纯函数、无 Android 依赖，可单测。
 *
 * ## 为什么需要它
 *
 * 原来的导入路径（`FixtureStore.importFromZip`）**只按扩展名分派**：
 *
 * ```
 * ln.endsWith(".xml") -> parseMa2XmlAll(raw, entry.name)   // 解析失败返回 emptyList
 * ln.endsWith(".d4")  -> parseD4All(...)                   // 同上
 * ln.endsWith(".r20") -> parseR20(...)                     // 同上
 * // 其它扩展名：直接当没看见
 * ```
 *
 * 于是只要压缩包里有一个文件对不上，用户看到的就是一句笼统的
 * 「未找到可识别的灯库文件」，既不知道是**哪个**文件出的问题，也不知道为什么 ——
 * 而这些灯库包动辄几百个文件、还常带 `__MACOSX/`、`Thumbs.db`、以及
 * 厂商自己改过后缀（`.XML`、`.xml.txt`、`.gdtf`）的条目。
 *
 * 现在把「体检」和「导入」拆成两步：
 *   1. [scanZip] 枚举压缩包内**每一个**条目，逐条判定归入 4 类：
 *      可导入 / 不支持(含原因) / 嵌套压缩包 / 读取失败(含原因)；
 *   2. 界面把这份报告摊给用户看（"共 N 个文件，其中 X 个可导入、Y 个跳过"），
 *      用户确认后再执行导入。
 *
 * ## 关键点：按**内容**判定，不按扩展名
 *
 * 这是本次修复的核心。实测灯库包里的常见情况：
 *   - `.xml` 实际是 Titan `.d4`（都是 XML，厂商改后缀很常见）
 *   - `.xml` 实际是 Pearl `.R20`
 *   - 大写后缀 `.XML` / `.D4`
 *   - 内容正确但后缀写成 `.xml.txt`
 *
 * 只认扩展名会让这些文件**静默消失**。所以这里对每个条目都按内容试解析，
 * 判定顺序：[KIND_XML] → [KIND_D4] → [KIND_R20]，与 `importFile` 的兜底行为一致。
 */
object ZipScan {

    /** zip 魔数 `PK`。 */
    private const val ZIP_MAGIC_0 = 0x50
    private const val ZIP_MAGIC_1 = 0x4B

    /** 单条目体积说明的展示阈值（字节）。 */
    private const val BIG_ENTRY = 2L * 1024 * 1024

    /** 内容类型判定结果。 */
    const val KIND_XML = "MA2 XML"
    const val KIND_D4 = "Titan .d4"
    const val KIND_R20 = "Pearl .R20"

    /** 归档里一个条目的归档方式。 */
    sealed interface Kind {
        /** 可导入：解析出了灯具定义（此时 [Entry.fixtures] 非空）。 */
        data object Fixtures : Kind
        /** 目录条目（zip 里的目录项），仅记录，不导入。 */
        data object Directory : Kind
        /** 内容看起来是另一个压缩包（模块化灯库包常见），不会递归展开 —— 明确告知。 */
        data object NestedZip : Kind
        /** 不支持的文件（含原因），例如 README.txt、厂商私有格式。 */
        data class Unsupported(val reason: String) : Kind
        /** 读取失败（含原因），例如解压出错、条目损坏。 */
        data class Unreadable(val reason: String) : Kind
    }

    /**
     * 一个条目的体检结果。
     *
     * @param path zip 内的完整路径（含目录），可直接用于展示
     * @param size 解压后的字节数；读取失败时为 0
     * @param contentType 识别出的格式名（[KIND_XML]/[KIND_D4]/[KIND_R20]），未识别为 null
     * @param fixtures 解析出的灯具（可能多个：一个文件含多种模式）
     * @param warning 非致命提醒（如"文件很大"），可空
     */
    data class Entry(
        val path: String,
        val size: Int,
        val kind: Kind,
        val contentType: String? = null,
        val fixtures: List<FixtureDef> = emptyList(),
        val warning: String? = null
    ) {
        /** 这个条目能被导入吗。 */
        val importable: Boolean get() = kind is Kind.Fixtures
    }

    /**
     * 整包体检报告。
     *
     * [importableFiles] 与 [fixtureCount] 的区别很重要：一个文件可能含多个灯型
     * （例如 .d4 里 34CH / 39CH 两个模式），所以要分别统计。
     */
    data class Report(
        val sourceName: String,
        val entries: List<Entry>
    ) {
        val importableFiles: List<Entry> get() = entries.filter { it.importable }
        val skipped: List<Entry> get() = entries.filter { !it.importable }

        /** 可导入的灯型总数（≠ 文件数）。 */
        val fixtureCount: Int get() = importableFiles.sumOf { it.fixtures.size }

        /** 非常规跳过项（真问题）：不支持 / 读取失败 / 嵌套包。目录项不算。 */
        val problems: List<Entry> get() = skipped.filter { it.kind !is Kind.Directory }

        val hasAnythingToImport: Boolean get() = importableFiles.isNotEmpty()

        /** 一行摘要，例如「共 12 个文件：8 个可导入(9 个灯型)，3 个跳过」。 */
        fun summary(): String {
            val files = entries.count { it.kind !is Kind.Directory }
            val dirs = entries.size - files
            val sb = StringBuilder()
            sb.append("共 $files 个文件")
            if (dirs > 0) sb.append("、$dirs 个目录")
            sb.append("：${importableFiles.size} 个可导入")
            if (fixtureCount > 0) sb.append("（${fixtureCount} 个灯型）")
            if (problems.isNotEmpty()) sb.append("，${problems.size} 个无法导入")
            return sb.toString()
        }
    }

    /**
     * 只做内容类型判定，**不写盘**。与 [FixtureStore.importFile] 的兜底规则一致：
     * 先试 MA2 XML，再试 Titan .d4，最后试 Pearl .R20。
     *
     * @return 类型名 + 解析出的灯型；都失败返回 (null, emptyList())
     */
    internal fun detectAndParse(raw: ByteArray): Pair<String?, List<FixtureDef>> {
        if (raw.isEmpty()) return null to emptyList()

        // MA2 XML 要求根标签/命名空间匹配，先试它
        run {
            val defs = FixtureParser.parseMa2XmlAll(stripBom(raw))
            if (defs.isNotEmpty()) return KIND_XML to defs
        }
        run {
            val defs = FixtureParser.parseD4All(stripBom(raw))
            if (defs.isNotEmpty()) return KIND_D4 to defs
        }
        run {
            val def = FixtureParser.parseR20(stripBom(raw))
            if (def != null) return KIND_R20 to listOf(def)
        }
        return null to emptyList()
    }

    private fun stripBom(raw: ByteArray): String =
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte())
            raw.copyOfRange(3, raw.size).toString(Charsets.UTF_8)
        else raw.toString(Charsets.UTF_8)

    /**
     * 枚举压缩包内每一个条目并逐条判定。
     *
     * 注意：**顺序读取**（[ZipInputStream] 而非 [java.util.zip.ZipFile] 随机访问），
     * 因为输入是内存里的 ByteArray（来自 SAF `contentResolver` 流），不是文件路径。
     *
     * @param data 整个压缩包的字节
     * @param sourceName 展示用文件名
     */
    fun scanZip(data: ByteArray, sourceName: String = ""): Report {
        val entries = mutableListOf<Entry>()

        val zip = try {
            ZipInputStream(ByteArrayInputStream(data))
        } catch (e: Exception) {
            // 连流都建不起来：整包不可读
            return Report(sourceName, listOf(
                Entry(sourceName.ifEmpty { "(整个压缩包)" }, data.size, Kind.Unreadable(e.describe()))
            ))
        }

        try {
            zip.use { z ->
                while (true) {
                    val ze = try {
                        z.nextEntry ?: break
                    } catch (e: Exception) {
                        entries.add(Entry("(后续条目)", 0, Kind.Unreadable("压缩包结构损坏：${e.describe()}")))
                        break
                    }
                    val bytes = try {
                        z.readBytes()
                    } catch (e: Exception) {
                        entries.add(Entry(ze.name, 0, Kind.Unreadable("解压失败：${e.describe()}")))
                        z.closeEntry()
                        continue
                    }
                    entries.add(classify(ze.name, ze.isDirectory, bytes))
                    z.closeEntry()
                }
            }
        } catch (e: Exception) {
            entries.add(Entry("(读包过程)", 0, Kind.Unreadable("读取压缩包失败：${e.describe()}")))
        }

        return Report(sourceName, entries)
    }

    /** 判定单个条目。独立出来便于单测（不必真造 zip）。 */
    internal fun classify(path: String, isDirectory: Boolean, bytes: ByteArray): Entry {
        if (isDirectory) return Entry(path, 0, Kind.Directory)

        // 常见的 OS 垃圾条目：明确归类，避免用户以为"漏了文件"
        val base = path.substringAfterLast('/')
        if (path.startsWith("__MACOSX/") || base == ".DS_Store" || base == "Thumbs.db" ||
            base.startsWith("._") || base == "desktop.ini") {
            return Entry(path, bytes.size, Kind.Unsupported("系统/隐藏文件，不是灯库"))
        }

        // 嵌套压缩包：不递归展开（避免解压炸弹），但要让用户看见
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == ZIP_MAGIC_0 &&
            (bytes[1].toInt() and 0xFF) == ZIP_MAGIC_1) {
            return Entry(path, bytes.size, Kind.NestedZip)
        }

        val (kind, defs) = detectAndParse(bytes)
        val warning = if (bytes.size > BIG_ENTRY) {
            "文件较大（${bytes.size / 1024 / 1024} MB），解析可能较慢"
        } else null

        if (kind != null && defs.isNotEmpty()) {
            return Entry(path, bytes.size, Kind.Fixtures, contentType = kind, fixtures = defs, warning = warning)
        }

        // 走到这里 = 内容不是三种灯库格式之一。给出**可操作**的原因，
        // 而不是原样返回一句"无法识别"。
        //
        // ⚠ 注意 isNotEmpty()：空的 ByteArray 调 all{} 会**返回 true**（空集合全部满足），
        //   所以必须先判空，否则空文件会被误判成"文本文件"而落不到"空文件"分支。
        val sample = bytes.take(512)
        val looksText = sample.isNotEmpty() && sample.all { b ->
            val v = b.toInt() and 0xFF
            v == 9 || v == 10 || v == 13 || v in 32..126 || v >= 0x80
        }
        val ext = base.substringAfterLast('.', "").lowercase()
        val reason = when {
            bytes.isEmpty() -> "空文件"
            !looksText -> "二进制文件，非灯库格式"
            ext in setOf("xml", "d4", "r20") ->
                "后缀是 .$ext，但内容解析不出任何灯型（格式可能被改坏，或不是 MA2/Titan/Pearl 格式）"
            ext == "gdtf" -> "GDTF 格式，本 App 不支持（请导出为 MA2 XML / Titan .d4 / Pearl .R20）"
            ext == "zip" -> "压缩包后缀但内容不是标准 zip"
            else -> "不支持的文件类型（支持 MA2 XML / Titan .d4 / Pearl .R20）"
        }
        return Entry(path, bytes.size, Kind.Unsupported(reason), warning = warning)
    }

    private fun Exception.describe(): String =
        (message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName)
}
