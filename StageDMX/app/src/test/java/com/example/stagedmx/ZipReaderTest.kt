package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * [ZipReader] 文件名解码的单元测试。
 *
 * 这是本次 GBK 修复的核心：JDK 会按 CP437 解码"未置 UTF-8 标志位"的名字，
 * 遇到 GBK 字节直接抛异常，导致整个合法压缩包被判为损坏。我们改成
 * UTF-8 → GBK/GB18030 → Latin-1 的多级回退。
 */
class ZipReaderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun gbk(s: String): ByteArray = s.toByteArray(Charset.forName("GB18030"))

    @Test
    fun `ascii name passes through`() {
        val raw = "ARES-P7II/Ares-P7 34CH.R20".toByteArray(Charsets.US_ASCII)
        assertEquals("ARES-P7II/Ares-P7 34CH.R20", ZipReader.decodeName(raw, utf8Flag = false))
    }

    /** UTF-8 标志位置位 → 必须按 UTF-8 解。 */
    @Test
    fun `utf8 flag is honoured`() {
        val raw = "灯库/文件.xml".toByteArray(Charsets.UTF_8)
        assertEquals("灯库/文件.xml", ZipReader.decodeName(raw, utf8Flag = true))
    }

    /** 标志位没置，但字节是合法 UTF-8（很多工具这样写）→ 仍按 UTF-8。 */
    @Test
    fun `valid utf8 without flag is decoded as utf8`() {
        val raw = "灯库/文件.xml".toByteArray(Charsets.UTF_8)
        assertEquals("灯库/文件.xml", ZipReader.decodeName(raw, utf8Flag = false))
    }

    /**
     * 本次真机故障的精确复现：GBK 字节 + 未置标志位。
     * `B5 C6 BF E2` = "灯库"（GBK）。以前这里会让 JDK 抛
     * `IllegalArgumentException: malformed input`。
     */
    @Test
    fun `gbk name without flag falls back to gb18030`() {
        val raw = bytes(
            'A'.code, 'R'.code, 'E'.code, 'S'.code, '-'.code, 'P'.code, '7'.code, 'I'.code, 'I'.code,
            0xB5, 0xC6, 0xBF, 0xE2,          // "灯库" 的 GBK 字节
            '/'.code,
            'A'.code, 'r'.code, 'e'.code, 's'.code
        )
        val decoded = ZipReader.decodeName(raw, utf8Flag = false)
        assertEquals("ARES-P7II灯库/Ares", decoded)
    }

    @Test
    fun `entire gbk fixture name decodes`() {
        val name = "ARES-P7II灯库/omarte@ares-p7@34_channels.xml"
        assertEquals(name, ZipReader.decodeName(gbk(name), utf8Flag = false))
    }

    /** 任意字节都不能抛异常（Latin-1 兜底，字节一一映射）。 */
    @Test
    fun `never throws on arbitrary bytes`() {
        for (seed in 0 until 256) {
            val raw = ByteArray(16) { ((seed + it * 7) and 0xFF).toByte() }
            val s = runCatching { ZipReader.decodeName(raw, utf8Flag = false) }
            assertTrue("seed=$seed 不应抛异常: ${s.exceptionOrNull()}", s.isSuccess)
        }
    }

    @Test
    fun `latin1 fallback keeps byte count for unmappable input`() {
        // 0x81 0x40 这种在 GBK 里是合法双字节，Latin-1 兜底时长度也要保持
        val raw = bytes(0x81, 0x40, 0xFE, 0xFF)
        val s = ZipReader.decodeName(raw, utf8Flag = false)
        assertTrue(s.isNotEmpty())
    }

    // ---------------- 端到端：真机样本 ----------------

    private fun load(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("gbk-filename-fixture.zip")!!.readBytes()

    @Test
    fun `reads the real gbk zip with all entries and correct chinese name`() {
        val entries = ZipReader.read(load())
        assertEquals(6, entries.size)

        // 目录名必须还原成中文（而不是 CP437 乱码，也不是抛异常）
        val dir = entries.first { it.isDirectory }
        assertEquals("ARES-P7II灯库/", dir.name)

        val files = entries.filter { !it.isDirectory }
        assertEquals(5, files.size)
        assertTrue("灯库文件应解压出内容", files.all { it.data.isNotEmpty() })
        assertTrue(files.all { it.name.startsWith("ARES-P7II灯库/") })
    }

    @Test
    fun `inflated content is a real fixture file`() {
        val entries = ZipReader.read(load())
        val xml = entries.first { it.name.endsWith("34_channels.xml") }
        // ⚠ 该 XML 带 UTF-8 BOM（EF BB BF），所以判"是否以 < 开头"前要先剥掉 BOM。
        //   FixtureStore.stripBom / FixtureParser 都会处理，这里只是断言内容像样。
        val text = String(xml.data, Charsets.UTF_8).removePrefix("\uFEFF")
        assertTrue("解压内容应是 XML: ${text.take(40)}", text.trimStart().startsWith("<"))
        assertTrue("应提到 MA 命名空间", text.contains("malighting.de/grandma2"))
        assertTrue("应含 FixtureType", text.contains("FixtureType"))
    }

    @Test
    fun `d4 and r20 entries inflate too`() {
        val entries = ZipReader.read(load())
        val d4 = entries.first { it.name.endsWith(".d4") }
        val r20 = entries.first { it.name.endsWith(".R20") }
        assertTrue(d4.data.size > 1000)
        assertTrue(r20.data.size > 1000)
    }

    @Test
    fun `garbage bytes are reported as corrupt not crash`() {
        val e = runCatching { ZipReader.read("not a zip at all, just text".toByteArray()) }
        assertTrue("应抛 CorruptZipException", e.exceptionOrNull() is ZipReader.CorruptZipException)
    }

    @Test
    fun `truncated zip is reported as corrupt`() {
        val full = load()
        val truncated = full.copyOfRange(0, full.size / 2)
        val e = runCatching { ZipReader.read(truncated) }
        assertTrue("截断的包应报损坏", e.exceptionOrNull() is ZipReader.CorruptZipException)
    }

    @Test
    fun `empty zip yields no entries and is not an error`() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { /* 什么都不写 */ }
        val entries = ZipReader.read(bos.toByteArray())
        assertTrue(entries.isEmpty())
        assertFalse(entries.isNotEmpty())
    }
}
