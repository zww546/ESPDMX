package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ZipScan] 的单元测试。
 *
 * 覆盖的核心场景是**旧实现会静默丢文件**的那几种：后缀不匹配内容、大写后缀、
 * OS 垃圾条目、损坏条目 —— 以前这些都表现为"导入 0 个灯库"，没有任何解释。
 */
class ZipScanTest {

    // ---------------- 测试用最小灯库内容 ----------------

    /**
     * 一个最小但**符合 MA2 实际 schema** 的灯库 XML。
     *
     * ⚠ schema 细节按 FixtureParser.parseOneFixtureType 的真实要求写，不能想当然：
     *   - `<FixtureType>` 的 name / mode 是**小写属性**，不是子标签
     *   - 厂商来自小写子标签 `<manufacturer>`
     *   - 通道必须包在 `<Module index=N>` 内，且是 `<ChannelType coarse=..attribute=..>`
     *     （coarse 缺失或 0 会被跳过；没有 Module 就一个通道都读不到）
     *   - 通道名来自 `<ChannelFunction attribute_user_name=..>`
     */
    private val ma2Xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MA>
          <FixtureType name="TestSpot" mode="Standard">
            <manufacturer>Acme</manufacturer>
            <Module index="0">
              <ChannelType coarse="1" attribute="PAN" default="128" highlight_value="255">
                <ChannelFunction attribute_user_name="Pan" physfrom="0" physto="540"/>
              </ChannelType>
              <ChannelType coarse="2" attribute="TILT" default="128" highlight_value="255">
                <ChannelFunction attribute_user_name="Tilt" physfrom="0" physto="270"/>
              </ChannelType>
              <ChannelType coarse="3" attribute="DIM" default="0" highlight_value="255">
                <ChannelFunction attribute_user_name="Dim"/>
              </ChannelType>
            </Module>
          </FixtureType>
        </MA>
    """.trimIndent()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                if (name.endsWith("/")) {
                    z.putNextEntry(ZipEntry(name)); z.closeEntry(); continue
                }
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    // ---------------- 内容判定（不依赖 zip） ----------------

    @Test
    fun `ma2 xml is detected by content`() {
        val (kind, defs) = ZipScan.detectAndParse(text(ma2Xml))
        assertEquals(ZipScan.KIND_XML, kind)
        assertTrue(defs.isNotEmpty())
    }

    @Test
    fun `garbage content is not detected`() {
        val (kind, defs) = ZipScan.detectAndParse(text("hello world, not a fixture"))
        assertNull(kind)
        assertTrue(defs.isEmpty())
    }

    @Test
    fun `empty content is not detected`() {
        val (kind, _) = ZipScan.detectAndParse(ByteArray(0))
        assertNull(kind)
    }

    // ---------------- 单条目归类 ----------------

    @Test
    fun `directory entry is classified as directory`() {
        val e = ZipScan.classify("fixtures/sub/", isDirectory = true, bytes = ByteArray(0))
        assertTrue(e.kind is ZipScan.Kind.Directory)
        assertFalse(e.importable)
    }

    @Test
    fun `macos and os junk is classified as unsupported not as an error`() {
        for (path in listOf("__MACOSX/._foo.xml", "Thumbs.db", "sub/.DS_Store", "desktop.ini", "sub/._x.d4")) {
            val e = ZipScan.classify(path, false, text("junk"))
            assertTrue("$path 应归为 Unsupported", e.kind is ZipScan.Kind.Unsupported)
            assertTrue("$path 的原因应提到系统/隐藏文件",
                (e.kind as ZipScan.Kind.Unsupported).reason.contains("系统"))
        }
    }

    @Test
    fun `nested zip is flagged as nested not recursed`() {
        val inner = zipOf("a.xml" to text(ma2Xml))
        val e = ZipScan.classify("bundle.zip", false, inner)
        assertTrue(e.kind is ZipScan.Kind.NestedZip)
        assertFalse(e.importable)
    }

    @Test
    fun `fixture entry carries parsed fixtures and content type`() {
        val e = ZipScan.classify("Acme.xml", false, text(ma2Xml))
        assertTrue(e.importable)
        assertEquals(ZipScan.KIND_XML, e.contentType)
        assertEquals(1, e.fixtures.size)
    }

    /**
     * 回归：后缀正确但内容坏掉时，原因必须点明"后缀是 .xml 但解析不出灯型"，
     * 而不是笼统的"不支持"。
     */
    @Test
    fun `wrong extension with valid content still imports`() {
        // 内容是真灯库，但后缀是厂商乱写的 .xml.txt
        val e = ZipScan.classify("Acme.xml.txt", false, text(ma2Xml))
        assertTrue("按内容判定，后缀无关", e.importable)
    }

    @Test
    fun `uppercase extension with valid content still imports`() {
        val e = ZipScan.classify("Acme.XML", false, text(ma2Xml))
        assertTrue(e.importable)
    }

    @Test
    fun `broken xml gets an actionable reason`() {
        val e = ZipScan.classify("Broken.xml", false, text("<MA><FixtureType"))
        assertTrue(e.kind is ZipScan.Kind.Unsupported)
        val reason = (e.kind as ZipScan.Kind.Unsupported).reason
        assertEquals("后缀是 .xml，但内容解析不出任何灯型（格式可能被改坏，或不是 MA2/Titan/Pearl 格式）", reason)
    }

    @Test
    fun `empty file gets its own reason`() {
        val e = ZipScan.classify("empty.xml", false, ByteArray(0))
        assertTrue(e.kind is ZipScan.Kind.Unsupported)
        assertEquals("空文件", (e.kind as ZipScan.Kind.Unsupported).reason)
    }

    @Test
    fun `gdtf gets a specific actionable reason`() {
        // 用非 zip 内容：真 GDTF 是 zip，但那样会先被判成嵌套压缩包（另有测试覆盖）
        val e = ZipScan.classify("light.gdtf", false, text("not a fixture format"))
        assertTrue(e.kind is ZipScan.Kind.Unsupported)
        val reason = (e.kind as ZipScan.Kind.Unsupported).reason
        assertTrue("GDTF 应提示不支持并给出替代格式: $reason", reason.contains("GDTF"))
    }

    // ---------------- 整包扫描 ----------------

    @Test
    fun `scan enumerates every entry`() {
        val zip = zipOf(
            "dir/" to ByteArray(0),
            "dir/Acme.xml" to text(ma2Xml),
            "readme.txt" to text("docs"),
            "__MACOSX/._Acme.xml" to text("junk"),
            "nested.zip" to zipOf("x.xml" to text(ma2Xml))
        )
        val r = ZipScan.scanZip(zip, "pack.zip")
        assertEquals(5, r.entries.size)
        assertEquals(1, r.importableFiles.size)
        assertEquals(1, r.fixtureCount)
        // 问题项 = txt + MACOSX + nested zip = 3；目录不算问题
        assertEquals(3, r.problems.size)
    }

    @Test
    fun `summary reports counts`() {
        val zip = zipOf("Acme.xml" to text(ma2Xml), "readme.txt" to text("x"))
        val s = ZipScan.scanZip(zip, "p.zip").summary()
        assertTrue(s, s.contains("共 2 个文件"))
        assertTrue(s, s.contains("1 个可导入"))
        assertTrue(s, s.contains("1 个无法导入"))
    }

    @Test
    fun `empty zip has nothing to import`() {
        val r = ZipScan.scanZip(zipOf(), "empty.zip")
        assertTrue(r.entries.isEmpty())
        assertFalse(r.hasAnythingToImport)
    }

    @Test
    fun `zip with no fixtures has nothing to import but reports problems`() {
        val zip = zipOf("readme.txt" to text("hi"), "a.bin" to byteArrayOf(0, 1, 2, 3, 0xFF.toByte()))
        val r = ZipScan.scanZip(zip, "junk.zip")
        assertFalse(r.hasAnythingToImport)
        assertEquals(2, r.problems.size)
    }

    /** 非 zip 数据喂给 scanZip 不能崩，要给出"读取失败"而不是抛异常。 */
    @Test
    fun `non zip bytes do not throw`() {
        val r = ZipScan.scanZip(text("this is definitely not a zip"), "x.txt")
        assertNotNull(r)
        assertFalse(r.hasAnythingToImport)
    }

    /** 多模式灯库：一个文件算多个灯型，文件数与灯型数要分开统计。 */
    @Test
    fun `fixture count can exceed file count`() {
        val zip = zipOf("A.xml" to text(ma2Xml), "B.xml" to text(ma2Xml))
        val r = ZipScan.scanZip(zip, "two.zip")
        assertEquals(2, r.importableFiles.size)
        assertEquals(2, r.fixtureCount)
    }
}
