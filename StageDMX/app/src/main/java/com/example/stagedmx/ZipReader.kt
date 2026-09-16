package com.example.stagedmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * 一个能正确读取 **GBK/非 UTF-8 文件名** 的最小 ZIP 读取器。
 *
 * ## 为什么不能用 `java.util.zip.ZipInputStream`
 *
 * 中文 Windows / WinRAR 打包的 zip，目录名按 **GBK** 编码，但中央目录里的
 * "UTF-8 标志位"（general purpose bit 11）是 **0**。按 ZIP 规范，标志位为 0 时
 * 文件名应视为 CP437，于是 JDK 用 CP437 去解码 GBK 字节 → 直接在
 * `nextEntry` 抛异常：
 *
 * ```
 * java.lang.IllegalArgumentException: malformed input off : 9, length : 1
 * ```
 *
 * 这个异常会让**整个压缩包**被判为损坏，而包本身完全合法（7-Zip / WinRAR /
 * 资源管理器都能正常打开）。实测样本 `gbk-filename-fixture.zip`：
 * 目录名 `ARES-P7II灯库/` 的原始字节是 `... 49 B5 C6 BF E2 2F ...`（`B5C6BFE2` = "灯库"）。
 *
 * 所以这里不用 JDK 的文件名解码，改为自己解析中央目录：
 *  - 文件名按 [decodeName] 的多级回退解码（UTF-8 → GBK/GB18030 → Latin-1）；
 *  - 数据用 [Inflater] 自己解压；
 *  - 中央目录拿不到的字段一律以中央目录为准（比本地头更可靠：
 *    本地头在 bit 3 置位时 size 字段是 0）。
 *
 * 只支持 ZIP 实际会用到的两种压缩方法：0（store）和 8（deflate）。
 */
object ZipReader {

    private const val SIG_EOCD = 0x06054b50        // End of central directory
    private const val SIG_EOCD64 = 0x06064b50      // Zip64 EOCD
    private const val SIG_EOCD64_LOC = 0x07064b50  // Zip64 EOCD locator
    private const val SIG_CEN = 0x02014b50         // Central directory file header
    private const val SIG_LOC = 0x04034b50         // Local file header

    private const val METHOD_STORE = 0
    private const val METHOD_DEFLATE = 8

    /** 单个条目解压后的上限，防解压炸弹（灯库文件不可能这么大）。 */
    const val MAX_ENTRY_SIZE = 64L * 1024 * 1024

    /** 一个条目：名字已解码好，[data] 是解压后的内容。 */
    class Entry(
        val name: String,
        val data: ByteArray,
        val isDirectory: Boolean
    )

    /** 找不到 EOCD / 结构不可解析时抛出（调用方据此报告"压缩包损坏"）。 */
    class CorruptZipException(message: String) : Exception(message)

    // ---------------- 文件名解码 ----------------

    private val gbk: Charset? = runCatching { Charset.forName("GB18030") }.getOrNull()

    /**
     * 解码文件名。多级回退，因为标志位**不可信** —— 中文工具链经常把 GBK 名字
     * 配上"非 UTF-8"的标志位，也有反过来把 UTF-8 名字不置标志位的。
     *
     * @param raw 中央目录里的原始名字字节
     * @param utf8Flag 标志位 bit 11
     */
    internal fun decodeName(raw: ByteArray, utf8Flag: Boolean): String {
        // 1) 标志位说 UTF-8 → 尊重它
        if (utf8Flag) {
            return String(raw, Charsets.UTF_8)
        }
        // 2) 标志位没说，但字节本身是合法 UTF-8 → 按 UTF-8（很多工具不置位却写 UTF-8）
        if (isValidUtf8(raw)) {
            return String(raw, Charsets.UTF_8)
        }
        // 3) 回退 GBK/GB18030（中文 Windows / WinRAR 的默认行为）
        gbk?.let { return String(raw, it) }
        // 4) 最后的兜底：Latin-1（字节一一映射，绝不抛异常）
        return String(raw, Charsets.ISO_8859_1)
    }

    /** 严格校验：用 CharsetDecoder 的 REPORT 模式，遇到非法序列返回 false。 */
    private fun isValidUtf8(raw: ByteArray): Boolean {
        // 纯 ASCII 直接通过（绝大多数条目名都是这种）
        if (raw.all { it >= 0 && it < 0x80 }) return true
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(raw))
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------------- 解析 ----------------

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toInt() and 0xFF).toLong()) or
        ((b[i + 1].toInt() and 0xFF).toLong() shl 8) or
        ((b[i + 2].toInt() and 0xFF).toLong() shl 16) or
        ((b[i + 3].toInt() and 0xFF).toLong() shl 24)

    private fun sig(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    /** 从末尾往前找 EOCD（注释最长 65535 字节，所以最多回退这么多）。 */
    private fun findEocd(b: ByteArray): Int {
        val minPos = maxOf(0, b.size - 22 - 65535)
        for (i in b.size - 22 downTo minPos) {
            if (sig(b, i) == SIG_EOCD) return i
        }
        return -1
    }

    /**
     * 读取压缩包内**所有**条目（含目录）。
     *
     * @throws CorruptZipException 结构不可解析时（调用方展示原因）
     */
    fun read(data: ByteArray): List<Entry> {
        if (data.size < 22) throw CorruptZipException("文件太小，不是 zip（${data.size} 字节）")

        val eocd = findEocd(data)
        if (eocd < 0) throw CorruptZipException("找不到 zip 结束记录（EOCD），文件可能被截断")

        var count = u16(data, eocd + 10)
        var cenOffset = u32(data, eocd + 16)

        // Zip64：EOCD 里的字段可能是 0xFFFF/0xFFFFFFFF，真值在 Zip64 EOCD 里
        val needZip64 = count == 0xFFFF || cenOffset == 0xFFFFFFFFL
        if (needZip64) {
            val locPos = eocd - 20
            if (locPos >= 0 && sig(data, locPos) == SIG_EOCD64_LOC) {
                val z64 = u32(data, locPos + 8).toInt()
                if (z64 >= 0 && z64 + 56 <= data.size && sig(data, z64) == SIG_EOCD64) {
                    count = u32(data, z64 + 32).toInt()
                    cenOffset = u32(data, z64 + 48)
                }
            }
        }

        val cenStart = cenOffset.toInt()
        if (cenStart < 0 || cenStart >= data.size) {
            throw CorruptZipException("中央目录偏移越界（$cenStart / ${data.size}）")
        }

        val out = ArrayList<Entry>(count.coerceAtLeast(0))
        var p = cenStart
        var read = 0
        while (read < count && p + 46 <= data.size && sig(data, p) == SIG_CEN) {
            val flags = u16(data, p + 8)
            val method = u16(data, p + 10)
            val compSize = u32(data, p + 20)
            val uncompSize = u32(data, p + 24)
            val nameLen = u16(data, p + 28)
            val extraLen = u16(data, p + 30)
            val commentLen = u16(data, p + 32)
            val localOffset = u32(data, p + 42)

            if (p + 46 + nameLen > data.size) throw CorruptZipException("中央目录条目越界")
            val rawName = data.copyOfRange(p + 46, p + 46 + nameLen)
            val utf8Flag = (flags and 0x800) != 0
            val name = decodeName(rawName, utf8Flag)

            // Zip64 扩展字段：size/offset 为占位值时去 extra 里取
            var cSize = compSize
            var uSize = uncompSize
            var lOff = localOffset
            if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                val z = parseZip64Extra(data, p + 46 + nameLen, extraLen)
                z?.let { (us, cs, lo) ->
                    if (uSize == 0xFFFFFFFFL) uSize = us
                    if (cSize == 0xFFFFFFFFL) cSize = cs
                    if (lOff == 0xFFFFFFFFL) lOff = lo
                }
            }

            val isDir = name.endsWith("/") || name.endsWith("\\")
            val payload = if (isDir) ByteArray(0) else readEntry(data, lOff.toInt(), method, cSize, uSize)
            out.add(Entry(name, payload, isDir))

            p += 46 + nameLen + extraLen + commentLen
            read++
        }

        // ⚠ 空压缩包（0 个条目）是合法的：ZipOutputStream 什么都不写就 close 会产生它。
        //   不要当成损坏 —— 返回空列表，由上层报告"没有可导入的内容"。
        return out
    }

    /** 解析 Zip64 扩展字段（id=0x0001），返回 (uncompressed, compressed, localOffset)。 */
    private fun parseZip64Extra(b: ByteArray, off: Int, len: Int): Triple<Long, Long, Long>? {
        var i = off
        val end = off + len
        while (i + 4 <= end && i + 4 <= b.size) {
            val id = u16(b, i)
            val sz = u16(b, i + 2)
            if (id == 0x0001) {
                var q = i + 4
                var us = -1L; var cs = -1L; var lo = -1L
                if (q + 8 <= b.size) { us = u32(b, q) or (u32(b, q + 4) shl 32); q += 8 }
                if (q + 8 <= b.size) { cs = u32(b, q) or (u32(b, q + 4) shl 32); q += 8 }
                if (q + 8 <= b.size) { lo = u32(b, q) or (u32(b, q + 4) shl 32) }
                return Triple(us, cs, lo)
            }
            i += 4 + sz
        }
        return null
    }

    /** 跳到本地头之后的数据区，按 method 解压。 */
    private fun readEntry(
        b: ByteArray, localOffset: Int, method: Int, compSize: Long, uncompSize: Long
    ): ByteArray {
        if (localOffset < 0 || localOffset + 30 > b.size) {
            throw CorruptZipException("本地头偏移越界（$localOffset）")
        }
        if (sig(b, localOffset) != SIG_LOC) {
            throw CorruptZipException("本地头签名不匹配（偏移 $localOffset）")
        }
        val nameLen = u16(b, localOffset + 26)
        val extraLen = u16(b, localOffset + 28)
        val dataStart = localOffset + 30 + nameLen + extraLen
        if (dataStart > b.size) throw CorruptZipException("数据区偏移越界")

        // ⚠ 以中央目录的 compSize 为准：本地头在 bit 3（数据描述符）置位时是 0
        var cSize = compSize
        if (cSize <= 0 || cSize > (b.size - dataStart)) {
            cSize = (b.size - dataStart).toLong()   // 兜底：读到文件尾
        }
        val dataEnd = (dataStart + cSize).coerceAtMost(b.size.toLong()).toInt()
        val raw = b.copyOfRange(dataStart, dataEnd)

        if (uncompSize > MAX_ENTRY_SIZE) {
            throw CorruptZipException("条目解压后 ${uncompSize / 1024 / 1024} MB，超过上限")
        }

        return when (method) {
            METHOD_STORE -> raw
            METHOD_DEFLATE -> inflate(raw, uncompSize)
            else -> throw CorruptZipException("不支持的压缩方法 $method（仅支持 store/deflate）")
        }
    }

    private fun inflate(raw: ByteArray, expected: Long): ByteArray {
        val inf = Inflater(true)   // nowrap = true：zip 是无 zlib 头的裸 deflate
        try {
            inf.setInput(raw)
            val bos = ByteArrayOutputStream(
                if (expected in 1..MAX_ENTRY_SIZE) expected.toInt() else 8192
            )
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                }
                bos.write(buf, 0, n)
                if (bos.size() > MAX_ENTRY_SIZE) throw CorruptZipException("解压结果超过上限")
            }
            return bos.toByteArray()
        } finally {
            inf.end()
        }
    }
}
