package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShaperStore] 映射字符串编解码的单元测试。
 *
 * 为什么值得单独测：这是**存在 SharedPreferences 里的用户数据**（每灯型一条）。
 * 它可能被手改、被旧版本写坏、或者只写了一半；而解析代码会在打开切割面板时被调用 ——
 * **面板打不开**比"映射被重置回默认值"严重得多。所以 [ShaperStore.decode] 的契约是
 * "任何坏数据都退回默认值，绝不抛异常"，这里把这个契约钉住。
 *
 * 只测纯函数（encode/decode），不碰 SharedPreferences，所以不用 Robolectric。
 */
class ShaperStoreCodecTest {

    private val defaultSides = ShaperGeometry.defaultSides

    private fun decode(raw: String?) = ShaperStore.decode(raw)

    // ---------------- 往返 ----------------

    @Test
    fun `round trip keeps everything`() {
        val sides = listOf(ShaperGeometry.Side.LEFT, ShaperGeometry.Side.TOP, ShaperGeometry.Side.RIGHT, ShaperGeometry.Side.BOTTOM)
        val inv = listOf(true, false, true, false)
        val raw = ShaperStore.encode(sides, inv, 120)
        val (s, i, deg) = decode(raw)
        assertEquals(sides, s)
        assertEquals(inv, i)
        assertEquals(120, deg)
    }

    @Test
    fun `encoded format is the documented one`() {
        // 锁住格式：以后要改就得同时改这条测试，避免"悄悄改了格式、
        // 老存档读不出来却在界面上看不出异常"。
        val raw = ShaperStore.encode(
            listOf(ShaperGeometry.Side.TOP, ShaperGeometry.Side.BOTTOM, ShaperGeometry.Side.LEFT, ShaperGeometry.Side.RIGHT), listOf(false, true, false, false), 90)
        assertEquals("TOP,BOTTOM,LEFT,RIGHT|0,1,0,0|90", raw)
    }

    // ---------------- 坏数据必须退回默认值 ----------------

    @Test
    fun `null and blank fall back to defaults`() {
        for (raw in listOf(null, "", "   ")) {
            val (s, i, deg) = decode(raw)
            assertEquals("空存档应当用默认边序", defaultSides, s)
            assertEquals(List(4) { false }, i)
            assertEquals(ShaperStore.DEFAULT_MAX_ANGLE_DEG, deg)
        }
    }

    @Test
    fun `garbage does not throw and falls back`() {
        for (raw in listOf("!!!", "|||", "T", "T,B,L,R", "靠靠靠")) {
            val (s, i, deg) = decode(raw)
            assertEquals("坏数据也要给出 4 条边", 4, s.size)
            assertEquals(4, i.size)
            assertTrue("角度必须落在合法范围", deg in 10..180)
        }
    }

    @Test
    fun `unknown side names fall back per element`() {
        // 第 2、4 个边名不认识 → 只有它们退回默认，另两个要保住
        val (s, _, _) = decode("LEFT,XYZ,RIGHT,QQQ|0,0,0,0|90")
        assertEquals(ShaperGeometry.Side.LEFT, s[0])
        assertEquals(defaultSides[1], s[1])
        assertEquals(ShaperGeometry.Side.RIGHT, s[2])
        assertEquals(defaultSides[3], s[3])
    }

    @Test
    fun `short side list keeps defaults for the rest`() {
        val (s, _, _) = decode("LEFT,TOP|0|90")
        assertEquals(ShaperGeometry.Side.LEFT, s[0])
        assertEquals(ShaperGeometry.Side.TOP, s[1])
        assertEquals(defaultSides[2], s[2])
        assertEquals(defaultSides[3], s[3])
    }

    @Test
    fun `invert bits default to false when missing`() {
        assertEquals(List(4) { false }, decode("T,B,L,R||90").second)
        assertEquals(List(4) { false }, decode("T,B,L,R").second)
        // 只给了一位 → 只有第一片是反向
        assertEquals(listOf(true, false, false, false), decode("T,B,L,R|1|90").second)
    }

    @Test
    fun `invert bits are strict about the literal one but tolerate spaces`() {
        // 手改存档很容易多打空格 —— 为此把"反向"整个丢掉不值得，所以 trim 后比较
        assertEquals(listOf(false, false, true, false),
            decode("T,B,L,R|0, 0 , 1 ,0|90").second)
        // 但只认字面 "1"：true/yes 一律当 false（认它们就属于猜了，不猜）
        assertEquals(listOf(false, false, false, false),
            decode("T,B,L,R|true,yes,on,x|90").second)
        assertTrue(decode("T,B,L,R|1,1,1,1|90").second.all { it })
    }

    @Test
    fun `angle range is clamped`() {
        // 第 3 个字段是**半量程**（度），合法区间 5..180
        assertEquals(ShaperStore.MIN_ANGLE_DEG,
            decode("T,B,L,R|0,0,0,0|0").third)                    // 太小 → 夹到下限
        assertEquals(ShaperStore.MAX_ANGLE_DEG,
            decode("T,B,L,R|0,0,0,0|999").third)                  // 太大 → 夹到上限
        assertEquals(90, decode("T,B,L,R|0,0,0,0|90").third)      // 三档之一要原样保留
        assertEquals(ShaperStore.DEFAULT_MAX_ANGLE_DEG, decode("T,B,L,R|0,0,0,0|abc").third)
        assertEquals(ShaperStore.DEFAULT_MAX_ANGLE_DEG, decode("T,B,L,R|0,0,0,0").third)
        // 默认就是 45（±45°），这是用户要求的默认档
        assertEquals(45, ShaperStore.DEFAULT_MAX_ANGLE_DEG)
    }

    @Test
    fun `extra fields are ignored`() {
        // 以后加字段时，老版本读新存档不该崩，也不该读错前三个字段
        val (s, i, deg) = decode("LEFT,TOP,RIGHT,BOTTOM|1,0,1,0|60|未来字段|x")
        assertEquals(listOf(ShaperGeometry.Side.LEFT, ShaperGeometry.Side.TOP, ShaperGeometry.Side.RIGHT, ShaperGeometry.Side.BOTTOM), s)
        assertEquals(listOf(true, false, true, false), i)
        assertEquals(60, deg)
    }

    // ---------------- 与几何对得上 ----------------

    @Test
    fun `decoded mapping actually drives the geometry`() {
        // 片1 映射到"左"且反向：给满偏移应当等于"全关"
        val (sides, inv, _) = decode("LEFT,BOTTOM,TOP,RIGHT|1,0,0,0|90")
        assertFalse("反向位没读出来", inv[0] == false)
        val blades = ShaperGeometry.bladesOf(listOf(1.0, 0.0, 0.0, 0.0),
            listOf(0.0, 0.0, 0.0, 0.0), sides, inv)
        // 反向 + 偏移给满 = 完全打开 → 光束圆一点没被切
        assertEquals(0, ShaperGeometry.cuttingCount(blades))
        assertEquals("圆形（四边全开）", ShaperGeometry.shapeLabel(blades))
    }
}
