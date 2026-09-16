package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * 复现并锁住真机上报的一个失败：**GBK 文件名的 zip 被判成"压缩包损坏"**。
 *
 * 样本是用户提供的真实灯库包 `gbk-filename-fixture.zip`（13 KB，6 个条目）。
 * 它的中央目录里，目录名 `ARES-P7II灯库/` 的 UTF-8 标志位（general purpose
 * bit 11）是 **0**，但文件名实际按 **GBK** 编码：
 *
 * ```
 * flags=0x0000 [非 UTF-8]  nameLen=30
 *   raw: 41 52 45 53 2D 50 37 49 49 B5 C6 BF E2 2F 41 72 65 73 ...
 *        A  R  E  S  -  P  7  I  I  <灯库 的 GBK 字节>  /  A  r  e  s
 * ```
 *
 * 这是中文 Windows / WinRAR 打包的典型产物。`java.util.zip.ZipInputStream`
 * 在标志位为 0 时按 CP437 解码，而这些字节在 ZIP 的 CP437 表里没有映射 →
 * 抛异常 → 整个导入被中止，且用户看到的是"压缩包结构损坏"这种**误导性**提示
 * （包本身完全合法，解压软件都能正常打开）。
 *
 * 这组测试做两件事：
 *  1. 断言这个包能被正常扫描（回归保护 —— 修好之后不许再退化）；
 *  2. 断言 6 个条目一个不少、4 个灯库文件全部可导入。
 */
class GbkFilenameZipTest {

    private fun load(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("gbk-filename-fixture.zip")!!.readBytes()

    @Test
    fun `raw ZipInputStream chokes or mangles the gbk name`() {
        // 记录底层行为，避免以后有人以为"换个流读取就好"。
        // 不管它是抛异常还是给出乱码名，都说明不能依赖默认解码。
        val names = mutableListOf<String>()
        var threw: String? = null
        try {
            ZipInputStream(load().inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    names.add(e.name)
                    z.closeEntry()
                }
            }
        } catch (ex: Exception) {
            threw = ex.describe()
        }
        // 要么抛异常，要么名字里的中文部分不是"灯库"
        val nameOk = names.any { it.contains("灯库") }
        assertTrue(
            "预期底层 ZipInputStream 无法正确还原 GBK 目录名（实际 names=$names threw=$threw）",
            threw != null || !nameOk
        )
    }

    @Test
    fun `scanZip reads every entry of the gbk named zip`() {
        val report = ZipScan.scanZip(load(), "af64f8307dc35af7c52f7409c9145cc7.zip")

        // 6 个条目：1 个目录 + 4 个灯库文件 + ...（实际为 5 文件 + 1 目录）
        assertEquals("条目数", 6, report.entries.size)
        assertFalse("不该出现整包不可读", report.entries.any {
            it.kind is ZipScan.Kind.Unreadable
        })
    }

    @Test
    fun `all four fixture files are importable`() {
        val report = ZipScan.scanZip(load(), "ares.zip")

        // 2 个 .R20 + 1 个 .d4 + 2 个 .xml = 5 个灯库文件
        assertEquals("可导入文件数", 5, report.importableFiles.size)
        assertTrue("灯型总数应 >= 5", report.fixtureCount >= 5)

        val names = report.importableFiles.map { it.path.substringAfterLast('/') }
        assertTrue("应含 34CH R20: $names", names.any { it.contains("34CH") && it.endsWith(".R20") })
        assertTrue("应含 39CH R20: $names", names.any { it.contains("39CH") && it.endsWith(".R20") })
        assertTrue("应含 .d4: $names", names.any { it.endsWith(".d4") })
        assertTrue("应含 34ch xml: $names", names.any { it.contains("34_channels.xml") })
        assertTrue("应含 39ch xml: $names", names.any { it.contains("39_channels.xml") })
    }

    @Test
    fun `entry paths keep the directory part`() {
        val report = ZipScan.scanZip(load(), "ares.zip")
        val first = report.importableFiles.first()
        assertTrue("路径应保留目录结构: ${first.path}", first.path.contains("/"))
    }

    @Test
    fun `directory entry is recognised as a directory`() {
        val report = ZipScan.scanZip(load(), "ares.zip")
        val dirs = report.entries.filter { it.kind is ZipScan.Kind.Directory }
        assertEquals("应有 1 个目录条目", 1, dirs.size)
    }

    private fun Exception.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
