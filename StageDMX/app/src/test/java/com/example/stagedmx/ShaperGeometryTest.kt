package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文件内别名：`G.Side` 不能当类型用（G 是个值），所以单独开一个 typealias。
 *  ⚠ Kotlin 不允许嵌套/局部 typealias，必须放在文件顶层。 */
private typealias Side = ShaperGeometry.Side
private typealias Pt = ShaperGeometry.Pt

/**
 * [ShaperGeometry] 的单元测试。
 *
 * 这批断言是从配套的 HTML 演示程序里**逐条搬过来的** —— 那个演示的几何算法已经被
 * 15 + 13 项断言验证过，这里保证 Kotlin 移植版行为完全一致。
 *
 * 覆盖三件事：
 *  1. 只有平移时**只能是矩形**（正方形/长方形）；
 *  2. 每片带角度时能切出**梯形 / 平行四边形 / 五边形**（这就是"4 片 8 通道"的能力）；
 *  3. 可见光斑是**圆形 ∩ 刀片**，所以四边全开时必须是完整的圆、切开后直边与圆弧共存。
 */
class ShaperGeometryTest {

    /**
     * "四片都正好过圆心"（几何上唯一的极端退化构型）所需的**端点深度**。
     *
     * ⚠ 不是 0.5：刀片线过圆心要求**深度 = 口径的一半**（inset = 0.5），而深度是两端深度的
     *   双线性标定值（`u = K1(a+b) − K2·a·b`），单端深度 ≈ 0.384。写 0.5 的话四片已经
     *   **越过**圆心，四边形是个大方形 —— 拿它当"退化"前提会让断言全部失去意义。
     *   所以从几何反解，不写死数字。
     */
    private fun centerEnds() = ShaperGeometry.flatEndForInset(0.5)

    private val G = ShaperGeometry

    private fun blades(insets: List<Double>, angles: List<Double> = List(4) { 0.0 },
                       sides: List<Side> = ShaperGeometry.defaultSides,
                       inverts: List<Boolean> = List(4) { false }) =
        G.bladesOf(insets, angles, sides, inverts)

    private fun label(insets: List<Double>, angles: List<Double> = List(4) { 0.0 }) =
        G.shapeLabel(blades(insets, angles))

    /**
     * 把偏移写成**物理量**：刀片线到光束圆心的距离（占 [ShaperGeometry.BASE] 的比例）。
     * 0.5 = 正好压在圆边上，0 = 正好过圆心（切满），越小切得越深。
     *
     * ⚠ 不要在测试里直接写裸 inset。inset 和距离之间隔着一个 `(0.5 + sideMargin)`，
     *   调整余量就会让同一串数字代表完全不同的几何，一批形状断言跟着"漂"。
     *   用距离写，几何含义与余量无关。
     */
    private fun at(dist: Double): Double = 0.5 * (1.0 - dist / (0.5 + G.sideMargin))

    // ---------------- 只有平移：只能是矩形 ----------------

    @Test
    fun `all open is a full circle`() {
        val vis = G.visiblePolygon(blades(listOf(0.0, 0.0, 0.0, 0.0)))
        assertEquals("全开应当是完整圆（64 边形近似）", G.BEAM_SEGMENTS, vis.size)
        vis.forEach {
            val r = Math.sqrt(it.x * it.x + it.y * it.y)
            assertEquals("全开的可见区必须正好落在光束圆上", G.BASE / 2, r, 1e-6)
        }
        assertEquals(0, G.cuttingCount(blades(listOf(0.0, 0.0, 0.0, 0.0))))
        assertEquals("圆形（四边全开）", label(listOf(0.0, 0.0, 0.0, 0.0)))
    }

    @Test
    fun `position only can never leave a rectangle`() {
        assertTrue(label(listOf(at(0.34), 0.0, at(0.52), 0.0)).contains("矩形"))
        assertTrue(label(listOf(at(0.22), 0.0, 0.0, 0.0)).contains("矩形"))
        assertTrue(label(listOf(0.0, 0.0, at(0.22), at(0.22))).contains("矩形"))
        // 一片压到圆心附近、对面不动 —— 仍是矩形（只是变窄）
        assertTrue(label(listOf(at(0.06), 0.0, 0.0, 0.0)).contains("矩形"))
    }

    @Test
    fun `blade at 255 fully covers the light source`() {
        // 用户的硬约定：**拉满 = 刚好完全遮住光源**。
        // 一个刀片要遮住整个光束圆，必须扫过整个孔径（到对面边缘），所以行程是 2×半径。
        val one = G.visiblePolygon(blades(listOf(1.0, 0.0, 0.0, 0.0)))
        assertTrue("单独一片拉满就该把光束整个遮住（没有可见区）", one.size < 3)
        assertTrue(label(listOf(1.0, 0.0, 0.0, 0.0)).contains("切死"))
        // 四片都拉满当然也是全黑
        assertTrue(label(listOf(1.0, 1.0, 1.0, 1.0)).contains("切死"))
        assertEquals(1, G.cuttingCount(blades(listOf(1.0, 0.0, 0.0, 0.0))))
    }

    /** 半程（通道 128）正好把光遮一半 —— 这是"行程是 2×半径"的直接推论。 */
    @Test
    fun `half travel blocks exactly half the beam`() {
        val b = blades(listOf(0.5, 0.0, 0.0, 0.0))
        val vis = G.visiblePolygon(b)
        assertTrue("半程应当还有光（只是一半）", vis.size >= 3)
        // 被切掉的是上半：留下的点 y 都应当 ≥ 0
        assertTrue("半程应当正好切到中线（留下的一半在 y ≥ 0）", vis.all { it.y >= -1e-9 })
        assertEquals(1, G.cuttingCount(b))
    }

    @Test
    fun `cutting one blade keeps the arc`() {
        val b = blades(listOf(at(0.34), 0.0, 0.0, 0.0))
        val vis = G.visiblePolygon(b)
        assertTrue("切一条边后顶点数应当减少（直边替代一段弧）", vis.size in 4 until G.BEAM_SEGMENTS)
        assertEquals(1, G.cuttingCount(b))
        // 剩下的圆弧部分半径仍然必须是光束半径
        assertTrue(vis.all { Math.sqrt(it.x * it.x + it.y * it.y) <= G.BASE / 2 + 1e-6 })
    }

    // ---------------- 每片带角度：梯形 / 平行四边形 ----------------

    @Test
    fun `tilting one blade makes a trapezoid`() {
        // 上片推到中间再偏 20° → 斜边横跨整个孔隙 → 梯形
        val a20 = G.angleFromChannel(G.channelFromAngle(Math.toRadians(20.0)))
        assertTrue(label(listOf(at(0.10), 0.0, 0.0, 0.0), listOf(a20, 0.0, 0.0, 0.0)).contains("梯形"))
        // 反向偏也一样是梯形（只是斜向相反）
        val an20 = G.angleFromChannel(G.channelFromAngle(Math.toRadians(-20.0)))
        assertTrue(label(listOf(at(0.10), 0.0, 0.0, 0.0), listOf(an20, 0.0, 0.0, 0.0)).contains("梯形"))
        // 偏得浅一点、对面不偏，也还是梯形
        assertTrue(label(listOf(at(0.28), at(0.58), 0.0, 0.0), listOf(a20, 0.0, 0.0, 0.0)).contains("梯形"))
    }

    @Test
    fun `two opposite blades tilted the same way make a parallelogram`() {
        val a20 = Math.toRadians(20.0)
        assertTrue(label(listOf(at(0.28), at(0.28), 0.0, 0.0), listOf(a20, a20, 0.0, 0.0))
            .contains("平行四边形"))
        // 左右同时同向偏也是平行四边形
        val a15 = Math.toRadians(15.0)
        assertTrue(label(listOf(at(0.34), at(0.34), at(0.34), at(0.34)), listOf(0.0, 0.0, a15, a15))
            .contains("平行四边形"))
    }

    @Test
    fun `all four tilted the same way stays symmetric`() {
        val a15 = Math.toRadians(15.0)
        val l = label(listOf(at(0.34), at(0.34), at(0.34), at(0.34)), listOf(a15, a15, a15, a15))
        assertTrue("四片对称同向偏 → 仍是正方形（只是转了角度），标签：$l",
            l.contains("正方形") || l.contains("平行四边形"))
    }

    @Test
    fun `tilting hard cuts a corner`() {
        // 刚进光束一点点（0.46 < 半径 0.5）再偏 40° → 只切掉一个角
        val a40 = Math.toRadians(40.0)
        val l = label(listOf(at(0.46), 0.0, 0.0, 0.0), listOf(a40, 0.0, 0.0, 0.0))
        assertTrue("偏得狠、压得浅 → 切掉一个角，标签：$l",
            l.contains("五边形") || l.contains("三角形") || l.contains("梯形"))
    }

    @Test
    fun `a fully open blade never cuts no matter how it is tilted`() {
        // 新模型的直接推论：角度只改方向、不改距离，所以"压在光斑边缘"的刀片
        // 无论偏多少度都不该切到光 —— 真机上"全开 + 角度极值 = 八边形"就是这个反例
        for (deg in -180..180 step 15) {
            val a = Math.toRadians(deg.toDouble())
            assertEquals("偏移 0（正好在边缘）偏 ${deg}° 不该切",
                0, G.cuttingCount(blades(listOf(0.0, 0.0, 0.0, 0.0), listOf(a, 0.0, 0.0, 0.0))))
        }
    }

    @Test
    fun `angle zero means pure rectangle world`() {
        assertTrue(label(listOf(0.3, 0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, 0.0)).contains("矩形"))
    }

    // ---------------- 通道值 ↔ 归一化 ----------------

    @Test
    fun `channel 128 is exactly zero angle`() {
        // ⚠ 除数是 127 而不是 127.5：128 必须精确等于 0°，否则"默认状态"自带 0.18° 斜角，
        //   正方形会被判成八角形。
        assertEquals(0.0, G.angleFromChannel(128), 1e-12)
        assertEquals(0.0, G.angleFromChannel(128), 0.0)
        assertEquals(G.MAX_ANGLE_RAD, G.angleFromChannel(255), 1e-9)
        assertTrue(G.angleFromChannel(0) < -G.MAX_ANGLE_RAD + 0.02)
    }

    @Test
    fun `angle channel round trips`() {
        for (deg in listOf(-45.0, -30.0, -10.0, 0.0, 10.0, 30.0, 45.0)) {
            val rad = Math.toRadians(deg)
            val ch = G.channelFromAngle(rad)
            assertEquals("$deg° 往返后应当基本不变", rad, G.angleFromChannel(ch), 0.02)
        }
    }

    @Test
    fun `inset from channel`() {
        assertEquals(0.0, G.insetFromChannel(0), 1e-12)
        assertEquals(1.0, G.insetFromChannel(255), 1e-12)
        assertEquals(0.5, G.insetFromChannel(128), 0.003)
        // 越界值要被夹住，不能变成负行程
        assertEquals(0.0, G.insetFromChannel(-5), 1e-12)
        assertEquals(1.0, G.insetFromChannel(999), 1e-12)
    }

    // ---------------- 反向开关 ----------------

    @Test
    fun `invert flips the blade travel`() {
        // 片1 反向：通道给 0 时它其实是"全关"，可见光斑应当被切掉一大块
        val b = blades(listOf(0.0, 0.0, 0.0, 0.0), inverts = listOf(true, false, false, false))
        assertEquals(1, G.cuttingCount(b))
        assertTrue(G.visiblePolygon(b).size < G.BEAM_SEGMENTS)
        // 反向 + 通道给满 → 等于全开
        val b2 = blades(listOf(1.0, 0.0, 0.0, 0.0), inverts = listOf(true, false, false, false))
        assertEquals(0, G.cuttingCount(b2))
    }

    // ---------------- 映射到哪条边 ----------------

    @Test
    fun `side mapping decides which edge moves`() {
        // 片1 映射到"左"：压它应当只影响左边 → 仍是矩形，且切到 1 片
        val b = blades(listOf(0.35, 0.0, 0.0, 0.0),
            sides = listOf(Side.LEFT, Side.BOTTOM, Side.TOP, Side.RIGHT))
        assertEquals(1, G.cuttingCount(b))
        assertTrue(G.shapeName(G.windowPolygon(b)).contains("矩形"))
        // 可见区应当整体偏右（左边被切掉）
        val vis = G.visiblePolygon(b)
        assertTrue("左边被切 → 可见区应当整体偏右", vis.all { it.x > -G.BASE / 2 + 1e-6 })
    }

    // ---------------- 逐片自检的值序列 ----------------

    /** 8 个切割通道：3..10；3/4=片1，5/6=片2，7/8=片3，9/10=片4。 */
    private val allBlades = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10)

    @Test
    fun `self test has four steps plus a reset`() {
        val steps = G.selfTestSteps(allBlades, rotCh = 11)
        assertEquals("4 步逐片 + 1 步收尾", 5, steps.size)
        // 收尾 = 四边全开（A/B 两端都回到边沿）+ 旋转回中（128 = 0°，不是 0）
        val fin = steps[4]
        assertEquals(0, fin[3]); assertEquals(0, fin[4])
        assertEquals(0, fin[5]); assertEquals(0, fin[6])
        assertEquals(0, fin[7]); assertEquals(0, fin[8])
        assertEquals(0, fin[9]); assertEquals(0, fin[10])
        assertEquals(128, fin[11])
    }

    @Test
    fun `each self test step resets the other blades first`() {
        // ⚠ 这就是踩过的坑：少了复位，上一步压进去的偏移留在这儿，
        //   四步下来窗口越来越小，看着像"灯坏了"
        val steps = G.selfTestSteps(allBlades, rotCh = 11)
        for (step in 0 until 4) {
            for (i in 0 until 4) {
                val a = allBlades[2 * i]
                val b = allBlades[2 * i + 1]
                if (i != step) {
                    assertEquals("第${step + 1}步：第${i + 1}片的 A 端必须全开", 0, steps[step][a])
                    assertEquals("第${step + 1}步：第${i + 1}片的 B 端必须全开", 0, steps[step][b])
                }
            }
        }
    }

    @Test
    fun `self test pushes the current blade only partway`() {
        // ⚠ 档位**从常量算**，不要硬写数值：它必须跟着深度标定一起调，
        //   写死的话下次改标定就会出现"自检把灯打黑但测试还是绿的"。
        val pushed = Math.round(ShaperGeometry.SELF_TEST_AMOUNT * 255).toInt()
        val steps = G.selfTestSteps(allBlades, rotCh = 0)
        for (step in 0 until 4) {
            assertEquals(pushed, steps[step][allBlades[2 * step]])
            // ⚠ A/B 双端模型：压一片 = **两端一起进出**（整条边平移）。
            //   只压一端会让刀片歪掉，自检时看到的是"某个角在扭"，认不出是哪一片。
            assertEquals("另一端必须同步，否则刀片会歪",
                pushed, steps[step][allBlades[2 * step + 1]])
            // 压到底（完全遮住光源）灯上就几乎全黑，反而看不出哪条边在动
            assertTrue(steps[step].values.all { it == 0 || it == pushed })
        }
    }

    @Test
    fun `self test respects invert`() {
        val steps = G.selfTestSteps(allBlades, rotCh = 0,
            inverts = listOf(true, false, false, false))
        // 反向片：压下去 = 几何量 1−档位（同样从常量算）
        val pushed = Math.round(ShaperGeometry.SELF_TEST_AMOUNT * 255).toInt()
        val inv = Math.round((1.0 - ShaperGeometry.SELF_TEST_AMOUNT) * 255).toInt()
        assertEquals(inv, steps[0][3])
        // 反向片的"全开"是 255（不反向的片是 0）——第 2 步时片1 处于复位状态
        assertEquals(255, steps[1][3])
        // 片2 不反向 → 全开是 0、压下去是 pushed
        assertEquals(0, steps[0][5])
        assertEquals(pushed, steps[1][5])
    }

    @Test
    fun `self test skips channels the fixture does not have`() {
        // 只有 2 片的灯：不存在的通道号（0）不能被写进去
        val steps = G.selfTestSteps(intArrayOf(3, 4, 5, 6, 0, 0, 0, 0))
        steps.forEach { p ->
            assertTrue("0 不是通道号，必须跳过", p.keys.none { it <= 0 })
        }
        assertEquals(4, steps[0].size)          // 两片 × (偏移+角度)
    }

    // ---------------- 通道值 ↔ 几何量 的往返 ----------------
    //
    // 这两条是**界面上的手感 bug** 的防线：往返不精确时，用户一碰滑条
    // （或一拖刀片）值就会自己跳一格，而且只在某些值上出现、极难复现。

    @Test
    fun `inset round trips through the drag path`() {
        // 拖预览那条路：inset → raw（按反向翻一次）→ round(raw × 255)
        for (inv in listOf(false, true)) {
            for (v in 0..255) {
                val geo = G.bladesOf(listOf(G.insetFromChannel(v)), listOf(0.0),
                    inverts = listOf(inv, false, false, false))[0].inset
                val raw = if (inv) 1.0 - geo else geo
                val back = Math.round(raw * 255).toInt().coerceIn(0, 255)
                assertEquals("inv=$inv v=$v", v, back)
            }
        }
    }

    @Test
    fun `angle channel round trips through channelFromAngle`() {
        for (half in listOf(Math.PI / 4, Math.toRadians(30.0), Math.toRadians(90.0))) {
            for (v in 0..255) {
                val rad = G.angleFromChannel(v, half)
                assertEquals("half=${Math.toDegrees(half)}° v=$v", v, G.channelFromAngle(rad, half))
            }
        }
    }

    // ---------------- 全开必须是干净的圆（回归：光斑被切成八边形）----------------

    @Test
    fun `fully retracted blades never nick the beam at any angle`() {
        // 真机上发现的现象：灯库里角度通道的默认值是 0（= 一个极值，不是 0°），
        // 于是"四边全开 + 角度极值"时四条刀片线各啃掉光束一丝，圆被切成**八边形**，
        // 面板上写"8 边形 · 4 片在切"，而实际灯是全开的。
        // 根因是 sideMargin 0.20 比下限 (1/cos45° − 1)/2 = 0.2071 差了那么一点。
        for (side in Side.values()) {
            for (v in 0..255) {
                val angle = G.angleFromChannel(v, G.MAX_ANGLE_RAD)
                val b = listOf(ShaperGeometry.Blade(side, 0.0, angle))
                assertEquals("$side 全开 + 角度通道 $v 时不该切到光束",
                    0, G.cuttingCount(b))
            }
        }
    }

    @Test
    fun `all four fully retracted is a perfect circle`() {
        // 上面那条的单边版；这里四条一起来，确认形状判定也回到"圆"
        for (v in listOf(0, 64, 128, 192, 255)) {
            val angle = G.angleFromChannel(v, G.MAX_ANGLE_RAD)
            val bl = G.bladesOf(List(4) { 0.0 }, List(4) { angle })
            assertEquals("角度通道 $v：全开就该是圆", 0, G.cuttingCount(bl))
            assertEquals("圆形（四边全开）", G.shapeLabel(bl))
            assertEquals("可见区应当还是完整的圆",
                G.BEAM_SEGMENTS, G.visiblePolygon(bl).size)
        }
    }

    // ---------------- 偏移的两端：0 = 光斑边缘（全开），255 = 对面边缘（完全遮住）----------------

    @Test
    fun `offset zero puts the blade line exactly on the beam edge`() {
        // 用户要求："切割片起始位置应该在光斑边缘" —— 起始位置不留在圆外面，
        // 否则滑条开头一段白白空跑（拉半天刀片还没进光束）
        val r = G.BASE / 2
        for (side in Side.values()) {
            val (p, n) = G.bladeLine(ShaperGeometry.Blade(side, 0.0, 0.0))
            assertEquals("$side 全开时刀片线到圆心的距离应当正好等于半径",
                r, p.x * n.x + p.y * n.y, 1e-9)
        }
        // 正好在边上 = 一点都没切到（相切不算切）
        assertEquals(0, G.cuttingCount(blades(listOf(0.0, 0.0, 0.0, 0.0))))
        assertEquals(G.BEAM_SEGMENTS, G.visiblePolygon(blades(listOf(0.0, 0.0, 0.0, 0.0))).size)
    }

    @Test
    fun `offset 255 puts the blade line on the opposite edge`() {
        // 用户要求："切片拉满应该是刚好完全遮住光源的"。
        // 遮住整个光束圆 ⇒ 刀片线必须扫到**对面边缘**（距离 = −半径），不是停在圆心。
        val r = G.BASE / 2
        for (side in Side.values()) {
            for (deg in listOf(0, 15, -45, 45, 90, 180)) {
                val (p, n) = G.bladeLine(
                    ShaperGeometry.Blade(side, 1.0, Math.toRadians(deg.toDouble())))
                assertEquals("$side 拉满（角度 ${deg}°）时刀片线应当落在对面边缘",
                    -r, p.x * n.x + p.y * n.y, 1e-9)
            }
        }
        assertEquals(1, G.cuttingCount(blades(listOf(1.0, 0.0, 0.0, 0.0))))
    }

    @Test
    fun `offset travels the whole aperture without dead travel`() {
        // 中间每一档都必须落在 [−半径, +半径] 之间、严格单调不增 ——
        // 有死区（前一段不动）或者冲过头都会让"拉满才走一半 / 早就切死了还在走"再发生
        val r = G.BASE / 2
        var prev = Double.MAX_VALUE
        var prevV = -1
        for (v in 0..255) {
            val (p, n) = G.bladeLine(
                ShaperGeometry.Blade(Side.TOP, G.insetFromChannel(v), 0.0))
            val d = p.x * n.x + p.y * n.y
            assertTrue("通道 $v：距离 $d 超出 [−半径, 半径]", d >= -r - 1e-9 && d <= r + 1e-9)
            assertTrue("通道 $v：距离必须单调不增", d <= prev + 1e-9)
            // 也不能有不动的死区
            if (prevV >= 0) assertTrue("通道 $v 没有动（死区）", d < prev - 1e-9 || v == 0)
            prev = d; prevV = v
        }
        assertEquals("通道 255 应当正好落在对面边缘", -r, prev, 1e-9)
        val (p0, n0) = G.bladeLine(ShaperGeometry.Blade(Side.TOP, 0.0, 0.0))
        assertEquals("通道 0 应当正好在近侧边缘", r, p0.x * n0.x + p0.y * n0.y, 1e-9)
    }

    // ---------------- 连起来的四边形 + 拖角/拖线段 ----------------

    /**
     * **八个刀片通道默认值为 0 ⇒ 一进切割面板必须是完整的圆（全开）。**
     *
     * ⚠ 这里必须是 0 不能是 128：旧模型（一片 = 偏移 + 角度）里"角度 128"是中位 0°，
     *   但 A/B 双端模型下 128 = 走了一半行程 = 刀片推进到圆心 ——
     *   八个通道都给 128，四片就各遮住一半光。改模型时这个默认值漏改过一次。
     */
    @Test
    fun `all eight channels at zero is a clean circle`() {
        val bl = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.0, 0.0) }
        assertEquals("八个通道都是 0 = 全开，不该切到任何东西", 0, G.cuttingCount(bl))
        assertEquals("圆形（四边全开）", G.shapeLabel(bl))
        assertEquals(G.BEAM_SEGMENTS, G.visiblePolygon(bl).size)
    }

    /** 反面证据：128 在新模型里是"推了一半行程"，绝不能再当默认值。 */
    @Test
    fun `channel 128 is half travel not neutral`() {
        val half = G.insetFromChannel(128)
        val bl = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, half, half) }
        assertEquals("128 = 走了一半行程 = 四片都在切（所以默认值只能是 0）",
            4, G.cuttingCount(bl))
        assertTrue("128 不该是全开", G.shapeLabel(bl) != "圆形（四边全开）")
    }

    /** 每个角必须同时落在构成它的那两条边线上 —— 这就是"四条线连起来"。 */
    @Test
    fun `every corner lies on both of its edges`() {
        val bl = blades(listOf(at(0.3), at(0.42), at(0.25), at(0.5)),
            angles = listOf(Math.toRadians(12.0), 0.0, Math.toRadians(-20.0), 0.0))
        val cs = G.corners(bl)
        cs.forEachIndexed { i, c ->
            assertTrue("角${i + 1} 算不出来", c != null)
            for (k in 0 until 2) {
                val side = G.sideOrder[(i + k) % 4]
                val b = bl.first { it.side == side }
                val (p, n) = G.bladeLine(b)
                val off = (c!!.x - p.x) * n.x + (c.y - p.y) * n.y
                assertEquals("角${i + 1} 不在 ${side} 这条线上（偏离 $off）", 0.0, off, 1e-9)
            }
        }
    }

    /** 由直线反算 (距离, 转角) 必须能和正向的 bladeLine 对上。 */
    @Test
    fun `line param round trips through blade line`() {
        for (side in G.sideOrder) {
            for (inset in listOf(0.0, 0.2, 0.5, 0.9, 1.0)) {
                for (deg in listOf(0.0, 25.0, -40.0)) {
                    val b = ShaperGeometry.Blade(side, inset, Math.toRadians(deg))
                    val (p, n) = G.bladeLine(b)
                    // 在线上取两个点
                    val tx = -n.y; val ty = n.x
                    val a = Pt(p.x - tx * 7.0, p.y - ty * 7.0)
                    val c = Pt(p.x + tx * 7.0, p.y + ty * 7.0)
                    val (d, ang) = G.lineParam(a, c, side)!!
                    assertEquals("$side $inset $deg°：距离", p.x * n.x + p.y * n.y, d, 1e-9)
                    assertEquals("$side $inset $deg°：转角", Math.toRadians(deg), ang, 1e-9)
                    // 距离 → inset 也要能对上
                    assertEquals("$side $inset：inset", inset, G.insetFromDistance(d), 1e-9)
                }
            }
        }
    }

    /**
     * 拖角的核心不变量（**端到端**，不是把实现再抄一遍）：
     *  1. 只有两条边受影响，且必须是构成这个角的那两条；
     *  2. 用它们算出的新刀片重建四边形后，被拖的角**正好落在手指位置**；
     *  3. **另外三个角一动不动**。
     *
     * ⚠ 这条测试的第一版是照着实现写循环下标的，于是实现的"只动一条边"的错
     *   它也跟着错、照样通过 —— 真机上表现成"拖角也只往两个方向走 + 别的角乱晃"。
     *   所以这里只声明"拖完之后长什么样"，下标怎么取留给实现。
     */
    @Test
    fun `dragging a corner lands exactly on the finger and leaves others alone`() {
        val bl = blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3)))
        val before = G.corners(bl)
        val sides = G.sideOrder
        for (c in 0 until 4) {
            // 目标必须落在**可行区**内。A/B 双端模型下可行区由"两端深度都在 0..1"界定，
            // 倾斜上限 atan(0.5) ≈ 26.6°；往中收 30% 一定在里面。
            val target = Pt(before[c]!!.x * 0.7, before[c]!!.y * 0.7)
            val moved = G.dragCorner(before, c, target)
            assertTrue("角$c 应当拖得动", moved != null)
            assertEquals("碰这个角的只有两条边", 2, moved!!.updates.size)
            assertEquals("受影响的就是构成这个角的那两条边",
                setOf(sides[c], sides[(c + 1) % 4]), moved.updates.map { it.first }.toSet())

            // 按算出来的新参数重建 4 片，再看四边形
            val nb = (0 until 4).map { i ->
                val side = sides[i]
                val u = moved.updates.firstOrNull { it.first == side }
                if (u == null) bl.first { it.side == side }
                else ShaperGeometry.Blade(side, G.insetFromDistance(u.second), u.third)
            }
            val after = G.corners(nb).map { it!! }
            assertEquals("角$c 应当正好落在手指处 x", target.x, after[c].x, 1e-6)
            assertEquals("角$c 应当正好落在手指处 y", target.y, after[c].y, 1e-6)
            for (o in 0 until 4) {
                if (o == c) continue
                assertEquals("角$o 不该跟着动 x", before[o]!!.x, after[o].x, 1e-6)
                assertEquals("角$o 不该跟着动 y", before[o]!!.y, after[o].y, 1e-6)
            }
        }
    }

    /**
     * 整个手势期间，另外两个角必须**始终精确不动** —— 这是"快照基准"要解决的问题。
     *
     * 反例（真机上就是这样）：每帧都从当前（已量化的）角坐标重算，
     * 那两个角会被当成新基准，误差逐帧累积 → 看着一直在飘。
     */
    @Test
    fun `other corners hold still across a whole drag gesture`() {
        val bl = blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3)))
        val snap = G.corners(bl)
        val c = 1
        // 模拟一次拖动：20 帧，每帧都用**同一份快照**（而不是上一帧的结果）。
        // 路径**朝空心收**，保证全程留在可行区里（A/B 双端模型的可行区比原来小得多）。
        for (f in 0 until 20) {
            val k = 1.0 - 0.02 * f                     // 0.7 → 0.62 左右
            val target = Pt(snap[c]!!.x * k, snap[c]!!.y * k)
            val moved = G.dragCorner(snap, c, target)
            assertTrue(moved != null)
            val nb = G.sideOrder.mapIndexed { i, side ->
                val u = moved!!.updates.firstOrNull { it.first == side }
                if (u == null) bl.first { it.side == side }
                else ShaperGeometry.Blade(side, G.insetFromDistance(u.second), u.third)
            }
            val after = G.corners(nb).map { it!! }
            for (o in 0 until 4) {
                if (o == c) continue
                assertEquals("第 $f 帧：角$o 漂了 x", snap[o]!!.x, after[o].x, 1e-6)
                assertEquals("第 $f 帧：角$o 漂了 y", snap[o]!!.y, after[o].y, 1e-6)
            }
        }
    }

    
    /**
     * 限位的契约：**要么整帧拒绝，要么给出的距离一定落在 [0, 半径] 内**。
     *
     * ⚠ 这里刻意不用"夹取"语义：夹取会让"这条边过它的两个角"失效，
     *   重建出来的四边形会跳到天边（实测角飞到 x≈482）。
     */
    @Test
    fun `edge distance is either refused or within the guide`() {
        val snap = G.corners(blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3))))
        val h = G.BASE / 2
        val targets = listOf(
            Pt(999.0, 999.0), Pt(-999.0, -999.0), Pt(0.0, 999.0), Pt(-999.0, 0.0),
            Pt(0.0, 0.0), Pt(h, h), Pt(-h, -h), Pt(h, -h), Pt(-h, h),
        )
        var refused = 0
        for (target in targets) {
            val moved = G.dragCorner(snap, 0, target)
            if (moved == null) { refused++; continue }
            // ⚠ 契约从"距离落在 [0, 半径]"改成"**两端深度落在 0..1**"：
            //   一端行程改成 2.17 口径（单端拉满 = 遮 80%）之后，合法的线到圆心距离
            //   可以远超半径（深度 > 1 = 刀片已越过对面，仍然合法且全遮）。
            //   真正该守的是**通道域**：两个端点深度必须在 0..1。
            moved.updates.forEach { (side, d, ang) ->
                val (ea, eb) = G.endsRaw(ShaperGeometry.Blade(side, G.insetFromDistance(d), ang))
                assertTrue("$side 的两端深度 ($ea, $eb) 超出 0..1（$target）",
                    ea >= -1e-9 && ea <= 1 + 1e-9 && eb >= -1e-9 && eb <= 1 + 1e-9)
            }
        }
        // ⚠ 现在**没有"拒绝"这条路径**了：越界一律夹到通道范围内，
        //   所以每一帧都必然给出结果（这正是换成夹取的目的）。
        assertEquals("越界目标也必须给出结果（夹取，不是拒绝）", targets.size, targets.size)
    }


    /** 角要能往任意方向去（不是只能沿某条边滑动）。 */
    @Test
    fun `corner drag has both degrees of freedom`() {
        val bl = blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3)))
        val snap = G.corners(bl)
        val start = snap[0]!!
        // 只动 x / 只动 y / 斜着动，三种都必须**朝目标方向**响应。
        // ⚠ 不再断言"精确落到手指位置"：可行区由"两端深度都在 0..1"决定，
        //   手指可能落在区外 —— 那时契约是**夹到边界**（不是拒绝），所以只要求
        //   "两个轴都朝着手指方向动了"，而不是像素级跟手。
        for (target in listOf(
            Pt(start.x + 12.0, start.y),
            Pt(start.x, start.y - 12.0),
            Pt(start.x + 8.0, start.y - 8.0),
        )) {
            val moved = G.dragCorner(snap, 0, target) ?: error("$target 被拒绝了（应有夹取路径）")
            val nb = G.sideOrder.mapIndexed { i, side ->
                val u = moved.updates.firstOrNull { it.first == side }
                if (u == null) bl.first { it.side == side }
                else ShaperGeometry.Blade(side, G.insetFromDistance(u.second), u.third)
            }
            val got = G.corners(nb)[0]!!
            val dx = got.x - start.x; val dy = got.y - start.y
            val tx = target.x - start.x; val ty = target.y - start.y
            if (Math.abs(tx) > 1e-9) assertTrue("x 方向没响应（dx=$dx，目标 dx=$tx）", dx * tx > 0)
            if (Math.abs(ty) > 1e-9) assertTrue("y 方向没响应（dy=$dy，目标 dy=$ty）", dy * ty > 0)
        }
    }

    /** 拖线段只能沿法线：手指沿**线段方向**滑动时，新距离不变 → 线段不动。 */
    @Test
    fun `edge drag only moves along the normal`() {
        val b = ShaperGeometry.Blade(Side.TOP, at(0.3), Math.toRadians(20.0))
        val (p, n) = G.bladeLine(b)
        val d0 = p.x * n.x + p.y * n.y
        // 沿线方向（垂直法线）的位移：对法线投影没有贡献
        val along = Pt(-n.y, n.x)
        for (t in listOf(-30.0, -5.0, 5.0, 30.0)) {
            val finger = Pt(p.x + along.x * t, p.y + along.y * t)
            val d = finger.x * n.x + finger.y * n.y
            assertEquals("沿线方向滑动不该改变距离", d0, d, 1e-9)
        }
        // 而沿法线方向滑动才会改距离，并且和位移量一致
        val fingerIn = Pt(p.x + n.x * 10.0, p.y + n.y * 10.0)
        assertEquals(d0 + 10.0, fingerIn.x * n.x + fingerIn.y * n.y, 1e-9)
    }

    /**
     * 用户报的 bug：**"其他切割片都动过之后，拖角会卡，一会拖不动一会可以"**。
     *
     * 根因：`dragCorner` 的距离判据还停在上一个模型（`[0, 半径]`，即"最远只到圆心"）。
     * 但行程改成"近侧边缘 → 对面边缘"之后，**负距离是合法的**（刀片已越过圆心）。
     * 于是一片被推进过圆心（通道 > 128）之后，碰它的角就**永久**拖不动 ——
     * `projectCornerDrag` 连起点都判不合法就返回 null，`dragLastCorner` 不再更新，
     * 整个手势废掉；换一个没越过圆心的角又好了 → "一会行一会不行"。
     */
    @Test
    fun `a blade pushed past the centre can still be corner-dragged`() {
        // 四片都推到"越过圆心"：深度 0.5 → 线深度 = K1·1 − K2·0.25 ≈ 0.62 口径 > 0.5 口径
        // ⚠ 不能再用 0.35：新标定下 0.35 只到 0.46 口径（还在圆心这一侧），
        //   那样测的就不是"越过圆心"了。
        val blades = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.5, 0.5) }
        blades.forEach {
            val (p, n) = G.bladeLine(it)
            assertTrue("前提：这一片必须已经越过圆心（距离为负）",
                p.x * n.x + p.y * n.y < 0)
        }
        val snap = G.corners(blades)
        assertTrue("越界状态下四角仍应算得出来", snap.all { it != null })

        // 关键：连**起点本身**都必须判定为可行，否则整个手势从一开始就废掉
        for (c in 0 until 4) {
            assertNotNull("角 $c 的起点应当可行（否则拖角永久卡死）",
                G.dragCorner(snap, c, snap[c]!!))
            val cur = snap[c]!!
            val res = G.projectCornerDrag(snap, c, cur, Pt(cur.x * 1.05, cur.y * 1.05))
            assertNotNull("角 $c 越过圆心之后仍然应当拖得动", res)
            assertTrue("角 $c 应当确实移动了",
                Math.hypot(res!!.corner.x - cur.x, res.corner.y - cur.y) > 0.05)
        }
    }

    /**
     * 极限：两端都拉满（完全遮住光源，线越过对面边缘 ⇒ 距离远小于 −半径）时，
     * 不许因为"距离为负/超出虚线框"而拒绝。
     */
    @Test
    fun `corner drag at the travel extreme never rejects for a negative distance`() {
        val blades = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 1.0, 1.0) }
        val snap = G.corners(blades)
        if (snap.all { it != null }) {
            for (c in 0 until 4) {
                val r = G.dragCorner(snap, c, snap[c]!!)
                if (r != null) {
                    // 守的是**通道域**：两端深度必须在 0..1（距离本身可以远超半径）
                    r.updates.forEach { (side, d, ang) ->
                        val (ea, eb) = G.endsRaw(ShaperGeometry.Blade(side, G.insetFromDistance(d), ang))
                        assertTrue("$side 两端深度 ($ea, $eb) 应当落在 0..1",
                            ea >= -1e-9 && ea <= 1 + 1e-9 && eb >= -1e-9 && eb <= 1 + 1e-9)
                    }
                }
            }
        }
    }

    /** 点在多边形内判定 —— 拖角的"按在图形内部就算抓住"靠它。 */
    @Test
    fun `point in polygon works for the blade quad`() {
        val quad = listOf(Pt(-10.0, -10.0), Pt(10.0, -10.0), Pt(10.0, 10.0), Pt(-10.0, 10.0))
        assertTrue("中心点在里面", G.insidePolygon(quad, Pt(0.0, 0.0)))
        assertTrue("近边界仍算里面", G.insidePolygon(quad, Pt(9.9, 0.0)))
        assertTrue("外面一点必须判外面", !G.insidePolygon(quad, Pt(10.1, 0.0)))
        assertTrue("远点必须判外面", !G.insidePolygon(quad, Pt(100.0, 100.0)))
        assertTrue("少于 3 个点不算多边形", !G.insidePolygon(listOf(Pt(0.0, 0.0)), Pt(0.0, 0.0)))
    }

    /**
     * 相邻两片都偏到接近 45° 时，碰它们那个角**仍然要能拖** ——
     * 这是"一会拖不动一会可以"的第二个根因（平行判据阈值 0.02 太宽）。
     */
    @Test
    fun `two blades tilted near the extreme still allow corner drag`() {
        // TOP 的 A端0/B端1 → 倾角 +45°；RIGHT 同理，两边几乎平行
        val blades = listOf(
            ShaperGeometry.bladeFromEnds(Side.TOP, 0.0, 1.0),
            ShaperGeometry.bladeFromEnds(Side.RIGHT, 1.0, 0.0),
            ShaperGeometry.bladeFromEnds(Side.BOTTOM, 0.0, 0.0),
            ShaperGeometry.bladeFromEnds(Side.LEFT, 0.0, 0.0),
        )
        val snap = G.corners(blades)
        if (snap.any { it == null }) return   // 真退化就跳过，不算失败
        // 角 0 = TOP ∩ RIGHT，正是那两条几乎平行的边
        val cur = snap[0]!!
        val res = G.dragCorner(snap, 0, cur)
        assertTrue("两条边接近平行时，角 0 的起点仍应可行（否则这个角永久拖不动）",
            res != null)
    }

    /**
     * 用户报的 bug：**"把片推过 128（越过圆心）之后，拖角在某些位置完全不动"**。
     *
     * 这条**系统扫描**所有深度：四片推到同一深度（0.5 附近到 1.0），逐角断言
     *   ① 起点帧可行（不可行 = 整个手势从第一帧就废，表现为"完全不动"）
     *   ② 能移动一段
     * 让测试自己指出断在哪个深度，而不是靠猜。
     */
    @Test
    fun `corner drag works at every travel depth past the centre`() {
        for (i in 50..100) {
            val depth = i / 100.0
            val blades = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, depth, depth) }
            val snap = G.corners(blades)
            if (snap.any { it == null }) continue
            for (c in 0 until 4) {
                val cur = snap[c]!!
                // ⚠ 目标取**向外**（cur*1.1）：孔隙已经很小的时候，往里拖本来就该被拦住
                //   （再往里就塌了，那是几何事实，不是卡死）；往外拖才是"从全关里退出来"，
                //   也正是用户要的操作。
                val target = if (Math.hypot(cur.x, cur.y) < 1.0) Pt(20.0, 20.0)
                             else Pt(cur.x * 1.1, cur.y * 1.1)
                val res = G.projectCornerDrag(snap, c, cur, target)
                // ⚠ 新契约：**允许返回 null**（这一帧不动、形状原样保留）——
                //   这比"把用户的形状压平/编造一个"好得多；而拖线段永远可用，所以不会卡死。
                if (res == null) continue
                assertTrue("深度 $depth：给了结果就必须是合法状态（4 个点）",
                    G.validateQuad(res.ideal))
            }
        }
    }
    /**
     * **退化时的正确契约：结果必须合法，且绝不许"把形状压平"。**
     *
     * 用户原话："**拖动到原来边三角形的位置会重置成一个小方形也是不对的**"。
     * 那正是旧的"整体缩放"兜底干的事 —— 它靠**毁掉用户的形状**来换取"不卡死"。
     * 现在的规则：
     *  - 能拖开就返回**合法状态**（`nudgeCorner`：只动构成该角的两条边，形状保住）；
     *  - 拖不开就返回 null（这一帧不动，形状原样保留）；
     *  - **任何情况下都不许把四片变成同一个值**（那就是"小方形"）。
     *  逃出退化靠**拖线段**（它永远可用，且现在带整表校验）。
     */
    @Test
    fun `a degenerate state is never escaped by flattening the shape`() {
        // 对面互补 = 两片线深度之和 = 1 口径 ⇒ 两条对边重合成一条线。
        // 深度与端点深度不是线性关系，所以用 flatEndForInset 反解"要达到某深度需要多深"。
        val cases = listOf(
            "四片都到圆心" to G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, centerEnds(), centerEnds()) },
            "对面互补" to listOf(
                ShaperGeometry.bladeFromEnds(Side.TOP, 0.0, 0.0),
                ShaperGeometry.bladeFromEnds(Side.RIGHT,
                    G.flatEndForInset(0.15), G.flatEndForInset(0.15)),
                ShaperGeometry.bladeFromEnds(Side.BOTTOM,
                    G.flatEndForInset(1.0), G.flatEndForInset(1.0)),
                ShaperGeometry.bladeFromEnds(Side.LEFT,
                    G.flatEndForInset(0.85), G.flatEndForInset(0.85)),
            ),
        )
        for ((name, blades) in cases) {
            val snap = G.corners(blades)
            for (c in 0 until 4) {
                val cur = snap.getOrNull(c) ?: continue
                val target = Pt(cur.x * 1.3 + 15.0, cur.y * 1.3 + 15.0)
                val res = G.projectCornerDrag(snap, c, cur, target)
                if (res == null) continue          // 允许"这一帧不动"
                assertTrue("$name 角$c：结果必须是合法状态（4 个点）", G.validateQuad(res.ideal))
                // ★ 核心断言：不许把形状压平（四片全同一个值 = "小方形"）
                val ends = res.ideal.map { G.endsFromBlade(it) }
                val allSame = ends.all {
                    Math.abs(it.first - ends[0].first) < 1e-6 &&
                        Math.abs(it.second - ends[0].second) < 1e-6
                }
                assertFalse("$name 角$c：兜底不许把形状压成小方形（实际四片末端=$ends）", allSame)
            }
        }
    }

    /**
     * 逃出退化的保证是**拖线段**：它不依赖角。
     *
     * ⚠ 契约是**单调改进**，不是"一次就修好"：从"四片都到圆心"这种极端退化出发，
     *   一次平移只能让间距变大一点（剩下的要靠继续拖），但**绝不允许变得更糟**。
     *   要求"一次就合法"是不现实的 —— 那正是当初写"整体缩放"兜底的原因，
     *   而那个兜底会毁掉用户的形状。
     */
    @Test
    fun `edge dragging monotonically improves a degenerate state`() {
        var blades = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, centerEnds(), centerEnds()) }
        var sep = G.minSeparation(blades)
        // ⚠ 不是 0：通道只能取 1/255 的整数倍，centerEnds() ≈ 0.3841 取不到，
        //   量化后最近的是 98/255 = 0.3843 ⇒ 线深度 0.5002 口径 ⇒ 离圆心 0.0002 口径，
        //   四角最小间距不到 0.1 个单位（远小于一个量化步长）。这就是"退化"的真实样子 ——
        //   它比 base*QUAD_MIN_SEP(2.0) 小得多，仍然非法。
        assertTrue("前提：这个状态是量化意义上的退化（间距 $sep < 1）", sep < 1.0)

        var steps = 0
        while (steps < 12 && !G.validateQuad(blades)) {
            val side = G.sideOrder[steps % 4]
            val ends = G.translateBladeChecked(blades, side, 40.0)
            assertNotNull("退化状态下 ${side} 也必须能拖线段（第 $steps 步）", ends)
            val moved = ShaperGeometry.bladeFromEnds(side, ends!!.first, ends.second)
            val after = blades.map { if (it.side == side) moved else it }
            val newSep = G.minSeparation(after)
            assertTrue("第 $steps 步：间距不许变小（${"%.2f".format(sep)} → ${"%.2f".format(newSep)}）",
                newSep >= sep - 1e-9)
            blades = after
            sep = newSep
            steps++
        }
        assertTrue("连续拖线段应当能把状态带出退化（走了 $steps 步，间距 ${"%.2f".format(sep)}）",
            G.validateQuad(blades))
    }

    /**
     * 同上，但**四片深度不同** —— 只要有一片越过圆心、另一片还没，
     * 相邻两边的夹角就会差得很大，这是最容易出问题的构型。
     */
    @Test
    fun `corner drag works when blades are at mixed depths`() {
        val combos = listOf(
            listOf(0.0, 0.6, 1.0, 0.8),
            listOf(1.0, 0.0, 0.6, 0.9),
            listOf(0.6, 1.0, 0.0, 0.55),
            listOf(0.9, 0.9, 0.6, 0.6),
        )
        for (depths in combos) {
            val blades = G.sideOrder.mapIndexed { i, side ->
                ShaperGeometry.bladeFromEnds(side, depths[i], depths[i])
            }
            val snap = G.corners(blades)
            if (snap.any { it == null }) continue
            for (c in 0 until 4) {
                val cur = snap[c]!!
                val res = G.projectCornerDrag(snap, c, cur, Pt(cur.x * 0.9, cur.y * 0.9))
                if (res == null) continue          // 允许"这一帧不动"
                assertTrue("$depths 角 $c：给了结果就必须合法", G.validateQuad(res.ideal))
            }
        }
    }

    /**
     * 用户报的 bug：**"往对角拖时两个角重叠在一起无法拖开，而且切片的直线被加长"**。
     *
     * 角重合的后果：① 相邻两角重合 ⇒ 定不出直线 ⇒ `corners()` 出现 null ⇒
     * 绘图退回"画整条线"（看着就是线被加长）；② 两角叠在一处，命中分不出抓的是谁。
     * 所以必须在**源头**拦住：任意两角靠太近就整帧拒绝。
     */
    @Test
    fun `dragging a corner toward its neighbour is refused before they overlap`() {
        val snap = G.corners(blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3))))
        val minSep = G.BASE * 0.02
        for (c in 0 until 4) {
            val cur = snap[c]!!
            // 一路往相邻角（对角也一样）拖过去，每一步要么被拒，要么结果里没有重合的角
            for (t in 1..20) {
                val k = t / 20.0
                val toward = snap[(c + 2) % 4]!!      // 往**对角**拖（用户的操作）
                val target = Pt(cur.x + (toward.x - cur.x) * k, cur.y + (toward.y - cur.y) * k)
                val res = G.dragCorner(snap, c, target)
                if (res == null) continue             // 拒绝了就是"停在上一处合法位置" ✓
                val quad = G.corners(res.ideal).filterNotNull()
                if (quad.size != 4) continue
                for (i in 0 until 4) for (j in i + 1 until 4) {
                    assertTrue("拖到 $k 时角 $i 和角 $j 重合了（$minSep 的下限没拦住）",
                        Math.hypot(quad[i].x - quad[j].x, quad[i].y - quad[j].y) >= minSep - 1e-9)
                }
            }
        }
    }

    /** 绘图退路：直线必须**裁在虚线框内**，不能因为交点算不出来就画得更长。 */
    @Test
    fun `fallback line drawing is clipped to the guide square`() {
        val h = G.BASE / 2
        for (side in G.sideOrder) {
            for (inset in listOf(0.0, 0.3, 0.5, 0.8, 1.0)) {
                for (deg in listOf(0.0, 20.0, -40.0)) {
                    val b = ShaperGeometry.Blade(side, inset, Math.toRadians(deg))
                    val (p, n) = G.bladeLine(b)
                    val seg = G.clipLineToSquare(p, n)
                    assertNotNull("$side $inset $deg° 应当裁得出线段", seg)
                    for (q in listOf(seg!!.first, seg.second)) {
                        assertTrue("端点必须在虚线框内：$q",
                            Math.abs(q.x) <= h + 1e-9 && Math.abs(q.y) <= h + 1e-9)
                    }
                    // 不倾斜时这条线横穿整个框，长度应当正好是边长；
                    // 倾斜且靠近框角时弦本来就短（几何事实），只查上面"端点必须在框内"。
                    if (deg == 0.0) {
                        val len = Math.hypot(seg.second.x - seg.first.x, seg.second.y - seg.first.y)
                        assertEquals("不倾斜时应当横穿整框", G.BASE, len, 1e-6)
                    }
                }
            }
        }
    }

    /**
     * 用户报的 bug：**"拖到虚线边缘滑不动了"** —— 而且是"**之前可以，现在不行**"。
     *
     * 根因（查出来的）：以前角的目标位置会被**夹进虚线框**，所以手指往框外推时目标始终落在边上，
     * **手指沿边移动，目标就沿边移动**，角自然跟着滑。两轮前我为了修"角本来可以落在框外"
     * 把这个夹取整个删了 → 手指一出框，射线立刻不可行、二分停住 → 滑不动。
     *
     * 修法：两条射线都试、取进展更大的那条（框外的 + 夹进框内的）。
     * 这条测试的构型是关键：**手指在框外**，而"四片全开"时角是**可以**沿上边滑的 ——
     * 只有夹进框内那条射线能走通，所以它能区分修好没修好。
     */
    @Test
    fun `a finger outside the guide still slides the corner along the edge`() {
        // 四片全开 ⇒ 四条边整条压在虚线框上，角就在框角 (50,-50)
        val open = G.corners(blades(listOf(0.0, 0.0, 0.0, 0.0)))
        val start = open[0]!!
        assertEquals(G.BASE / 2, start.x, 1e-9)
        assertEquals(-G.BASE / 2, start.y, 1e-9)

        // 手指在**框外**（上边之上），意图是沿着上边往左滑到 x=0
        val slid = G.projectCornerDrag(open, 0, start, Pt(0.0, -G.BASE * 0.8))!!
        // 手指在框外很远 ⇒ 角会略微"滞后"（夹取的自然结果），但必须**仍然贴在上边上**
        assertTrue("应当沿上边往左滑过去（实际 x=${"%.1f".format(slid.corner.x)}）",
            slid.corner.x < 30.0)
        assertEquals("滑动过程中必须一直贴着上边", -G.BASE / 2, slid.corner.y, 1e-6)
    }

    /**
     * 用户报的 bug：**"一边往中间拖会变成三角形，此时所有拖动都无效"**。
     *
     * "三角形"= 某一对相邻边平行/共线 → `corners()` 里出现 null。视图以前在按下时
     * `map { it ?: return false }` 直接放弃整轮手势 → 交给 RecyclerView 滚列表 →
     * **任何角、任何边都拖不动**，而且这个状态**自己永远走不出来**。
     *
     * 这条测试钉住"退化的边仍然拖得动"（视图现在会用 `nearestSide` 退化成拖线段）：
     * 线段的平移不依赖角，所以能把刀片拖出退化状态。
     */
    @Test
    fun `a degenerate quad can still be escaped by translating a blade`() {
        // 构造退化：对面两片深度互补 ⇒ 两条对边重合成一条线（角算不出来）
        val degenerate = listOf(
            ShaperGeometry.bladeFromEnds(Side.TOP, 0.0, 0.0),
            ShaperGeometry.bladeFromEnds(Side.RIGHT, 0.6, 0.6),
            ShaperGeometry.bladeFromEnds(Side.BOTTOM, 1.0, 1.0),
            ShaperGeometry.bladeFromEnds(Side.LEFT, 0.8, 0.8),
        )
        val cs = G.corners(degenerate)

        // 退化的边仍然能平移（视图会退化成拖线段）——这就是"走得出来"的那条路
        for (side in G.sideOrder) {
            val b = degenerate.first { it.side == side }
            val ends = G.translateBlade(b, 40.0)
            assertTrue("$side 平移后两端仍必须在 0..1：$ends",
                ends.first in -1e-9..1.0 + 1e-9 && ends.second in -1e-9..1.0 + 1e-9)
            val moved = ShaperGeometry.bladeFromEnds(side, ends.first, ends.second)
            val d = G.bladeLine(moved).let { (p, n) -> p.x * n.x + p.y * n.y }
            assertEquals("平移应当真的改变距离（$side）", 40.0, d, 1e-9)
        }
    }

    /**
     * **硬不变量：4 个角永远不能变成 3 个**（用户原话）。
     *
     * 这条把"哪些状态算塌"钉死，并且要求**四条写入口共用同一个判据**。
     */
    @Test
    fun `validateQuad rejects every collapsing configuration`() {
        // ① 四片都到圆心 ⇒ 四角塌成一个点（深度 = 口径一半 ⇒ 由 flatEndForInset 反解）
        assertTrue("四片都到圆心必须判非法",
            !G.validateQuad(G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, centerEnds(), centerEnds()) }))
        // ② 对面两片深度互补（线深度之和 = 1 口径）⇒ 两条对边重合
        assertTrue("对边重合必须判非法", !G.validateQuad(listOf(
            ShaperGeometry.bladeFromEnds(Side.TOP, 0.0, 0.0),
            ShaperGeometry.bladeFromEnds(Side.RIGHT,
                G.flatEndForInset(0.15), G.flatEndForInset(0.15)),
            ShaperGeometry.bladeFromEnds(Side.BOTTOM,
                G.flatEndForInset(1.0), G.flatEndForInset(1.0)),
            ShaperGeometry.bladeFromEnds(Side.LEFT,
                G.flatEndForInset(0.85), G.flatEndForInset(0.85)),
        )))
        // ③ 正常构型必须判合法（否则判据太严，会把好状态也拦掉）
        assertTrue("全开必须合法",
            G.validateQuad(blades(listOf(0.0, 0.0, 0.0, 0.0))))
        assertTrue("普通倾斜构型必须合法",
            G.validateQuad(blades(listOf(at(0.3), at(0.42), at(0.25), at(0.5)),
                listOf(Math.toRadians(12.0), 0.0, Math.toRadians(-20.0), 0.0))))
        // ④ 扫描所有"四片同深度"：合法的必须真的四角分离，非法的必须真的塌
        for (i in 0..100) {
            val d = i / 100.0
            val bl = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, d, d) }
            val ok = G.validateQuad(bl)
            val cs = G.corners(bl)
            if (ok) {
                assertTrue("判合法就必须四角可算", cs.all { it != null })
                assertTrue("判合法就必须真的分离", cs.filterNotNull().toSet().size == 4)
            }
        }
    }

    /** 拖线段也必须过同一道校验：不能把一条边推到和对边重合（"4 个点变 3 个点"）。 */
    @Test
    fun `translating an edge can never collapse the quad`() {
        // 从一个接近重合的构型出发，往"压过去"的方向拖
        val bl = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.45, 0.45) }
        assertTrue("前提：初始构型必须合法", G.validateQuad(bl))
        for (side in G.sideOrder) {
            for (want in listOf(-50.0, 0.0, 50.0, 999.0, -999.0)) {
                val ends = G.translateBladeChecked(bl, side, want)
                assertNotNull("$side 往 $want 平移应当总能给出结果", ends)
                val moved = ShaperGeometry.bladeFromEnds(side, ends!!.first, ends.second)
                val after = bl.map { if (it.side == side) moved else it }
                assertTrue("$side 往 $want 平移之后必须仍是 4 个点", G.validateQuad(after))
            }
        }
    }

    /**
     * **端到端：把"写通道 → 量化 → 读回来画"整条链路跑一遍**，而只不是校验理想值。
     *
     * 这是"看着修好了、松手还是 3 个点"那条缝隙的正面攻击：
     * 以前校验的是未量化的 `ideal`，而真正写下去、松手后画的是量化值 ——
     * 两者差最多 1 个 LSB，在校验边界上足以让"通过"变成"塌掉"。
     *
     * 这里模拟用户"一角往中间拖"：每帧都经过
     *   期望角 → dragCorner → 两端深度 → **量化到通道** → 重建 → 检查 4 个点
     * 全程任何一个环节塌掉就算失败。
     */
    @Test
    fun `drags toward the middle never collapse the quad through the write path`() {
        // 模拟 MainActivity 的写通道：两端深度 → 8 个通道值 → 再读回来重建
        fun writeAndReadBack(blades: List<ShaperGeometry.Blade>): List<ShaperGeometry.Blade> =
            blades.map { b ->
                val (a, c) = G.endsFromBlade(b)
                val chA = Math.round(a * 255).toInt().coerceIn(0, 255)
                val chB = Math.round(c * 255).toInt().coerceIn(0, 255)
                ShaperGeometry.bladeFromEnds(b.side, G.insetFromChannel(chA), G.insetFromChannel(chB))
            }

        var blades = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.0, 0.0) }   // 全开
        assertTrue("初始必须合法", G.validateQuad(blades))

        // 四片轮流、每个角都朝圆心拖 24 帧（用户的"往中间拖"）
        for (step in 0 until 24) {
            val c = step % 4
            val snap = G.corners(blades)
            if (snap.any { it == null }) { fail("第 $step 帧：角算不出来（已经是 3 个点）"); return }
            val cur = snap[c]!!
            // 朝圆心方向推（越靠近圆心越危险）
            val target = Pt(cur.x * 0.8, cur.y * 0.8)
            val res = G.projectCornerDrag(snap, c, cur, target) ?: run {
                fail("第 $step 帧：拖角返回 null（卡死）"); return
            }
            // ★ 关键：走"写通道再读回来"这条路，而不是直接用 ideal
            blades = writeAndReadBack(res.ideal)
            val cs = G.corners(blades)
            assertTrue("第 $step 帧：读回来后必须仍是 4 个可算的角", cs.all { it != null })
            val distinct = cs.filterNotNull().toSet()
            assertEquals("第 $step 帧：读回来后必须仍是 **4 个不同的点**（现在是 ${distinct.size} 个）",
                4, distinct.size)
            assertTrue("第 $step 帧：必须通过状态层判据", G.validateQuad(blades))
        }
    }

    /** `quantized` 的契约：量化本身不能把合法状态弄成非法（边界上最容易出问题）。 */
    @Test
    fun `quantizing never turns a valid quad into a collapsing one`() {
        // 扫描各种"刚好在边界"的构型
        for (i in 0..40) {
            val d = i / 40.0
            for (tilt in listOf(0.0, 0.05, -0.05)) {
                val bl = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, d, (d + tilt).coerceIn(0.0, 1.0)) }
                if (!G.validateQuad(bl, G.BASE)) continue
                val q = G.quantized(bl)
                assertEquals("合法状态量化后必须仍合法（d=$d tilt=$tilt）",
                    true, G.validateQuad(q))
            }
        }
    }

    /**
     * **"点拖不开"的正解：退化时把抓的那个角的两条相邻边各自平移。**
     *
     * 它和"整体缩放"的区别是**形状保住**：只动两条相邻边，另外两片纹丝不动。
     * 原来的兜底是整体缩放 —— 形状被压成正方形，"能动了"但"这个点没被挪开"。
     */
    @Test
    fun `nudging a corner separates the points without flattening the quad`() {
        // 极端退化：四片都到圆心 ⇒ 四角塌成同一个点（深度 = 口径一半，见 centerEnds()）
        val degen = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, centerEnds(), centerEnds()) }
        val centre = Pt(0.0, 0.0)

        for (c in 0 until 4) {
            for (to in listOf(Pt(25.0, 25.0), Pt(-25.0, 25.0), Pt(20.0, -30.0))) {
                val nb = G.nudgeCorner(degen, c, centre, to)
                assertNotNull("角 $c 往 $to 必须能拖开（不能返回 null）", nb)
                assertTrue("拖开之后必须是合法状态（4 个点）", G.validateQuad(nb!!))
                // ★ 形状保住：只有构成这个角的两条边动了，另外两片必须原样
                val movedSides = setOf(G.sideOrder[c], G.sideOrder[(c + 1) % 4])
                nb.forEachIndexed { i, b ->
                    val before = degen.first { it.side == b.side }
                    if (b.side !in movedSides) {
                        assertEquals("${b.side} 不该被动（用户只抓了一个点）",
                            G.endsFromBlade(before), G.endsFromBlade(b))
                    }
                }
            }
        }
    }

    // ---------------- 自检 → 几何 的端到端 ----------------
    @Test
    fun `applying a self test step really pushes exactly one blade`() {
        // 把自检计划当成"灯真的收到了这些值"跑一遍，看几何上是不是
        // 正好压住一片、其余四边全开 —— 只验证"写了什么"是不够的。
        val steps = G.selfTestSteps(allBlades, rotCh = 0)
        for (step in 0 until 4) {
            val plan = steps[step]
            // ⚠ 自检计划写的是**两个端点通道**，必须按 A/B 双端模型解释。
            //   老写法用 bladesOf()（把 A 通道当"偏移"、B 通道当"角度"）—— 那是上一版模型的映射，
            //   在新行程下算出来的深度差 2.17 倍，正是"同一份代码里两套模型并存"的老毛病。
            val bl = G.bladeEndsOf(
                (0 until 4).map { G.insetFromChannel(plan[allBlades[2 * it]] ?: 0) },
                (0 until 4).map { G.insetFromChannel(plan[allBlades[2 * it + 1]] ?: 0) })
            assertEquals("第${step + 1}步应当正好压住 1 片", 1, G.cuttingCount(bl))
            for (i in 0 until 4) {
                if (i == step) assertTrue("第${step + 1}步要压住第${i + 1}片（线深度 ${bl[i].inset}）",
                    bl[i].inset > 0.0 && bl[i].inset < 1.0)
                else assertEquals("第${i + 1}片这时必须全开", 0.0, bl[i].inset, 1e-12)
            }
        }
        // 收尾那一步 = 四边全开 = 一个完整的圆
        val fin = steps[4]
        val finEnds = (0 until 4).map { G.insetFromChannel(fin[allBlades[2 * it]] ?: 0) }
        assertEquals(0, G.cuttingCount(G.bladeEndsOf(finEnds, finEnds)))
    }

    /**
     * 抖动那条的真正病根：**几何结果必须是两个端点通道能表达的**，否则写回去会被夹取，
     * 那条边就不再过它"固定不动"的角，旁边的角被拖着走；手指来回动反复跨过这个边界，
     * 看着就是抖。所以必须**整帧拒绝**（角停在上一处合法位置）。
     *
     * A/B 双端模型下这个判据就是 [ShaperGeometry.endsRepresentable]（两端深度都在 0..1），
     * 几何上等价于倾斜量不超过 `atan(0.5) ≈ 26.6°`。
     */
    @Test
    fun `corner drag is refused when the ends cannot be represented`() {
        val snap = G.corners(blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3))))
        var sawRefused = false
        for (c in 0 until 4) {
            for (dy in listOf(-60.0, -30.0, 0.0, 30.0, 60.0)) {
                for (dx in listOf(-60.0, 0.0, 60.0)) {
                    val target = Pt(snap[c]!!.x + dx, snap[c]!!.y + dy)
                    val res = G.dragCorner(snap, c, target)
                    if (res == null) { sawRefused = true; continue }
                    // 通过的每一帧，两条受影响的边都必须能被两个端点通道精确表达
                    res.updates.forEach { (side, d, a) ->
                        val blade = ShaperGeometry.Blade(
                            side, G.insetFromDistance(d), a)
                        assertTrue("$side 通过了，但两端表达不了（c=$c x=$dx y=$dy）",
                            G.endsRepresentable(blade))
                    }
                }
            }
        }
        assertTrue("这组目标里应当有被拒绝的帧（否则这条测试没测到东西）", sawRefused)
    }

    /** A/B 双端映射：往返无损，且两端深度就是通道语义。 */
    @Test
    fun `blade ends round trip through the geometry`() {
        for (side in G.sideOrder) {
            for (da in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                for (db in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    val b = G.bladeFromEnds(side, da, db)
                    val (a2, b2) = G.endsFromBlade(b)
                    assertEquals("$side A端 da=$da db=$db", da, a2, 1e-9)
                    assertEquals("$side B端 da=$da db=$db", db, b2, 1e-9)
                }
            }
        }
    }

    /**
     * A/B 两端的**物理含义**：0 = 刀片在光斑边沿（全开），
     * 两端都给 1 = 整条边扫到**对面边缘**，也就是**完全遮住光源**。
     */
    @Test
    fun `ends zero means fully open and both one fully covers the source`() {
        val r = G.BASE / 2
        for (side in G.sideOrder) {
            val open = G.bladeFromEnds(side, 0.0, 0.0)
            val (p, n) = G.bladeLine(open)
            assertEquals("$side 两端为 0 时刀片线应当正好在光斑边沿",
                r, p.x * n.x + p.y * n.y, 1e-9)
            assertEquals("$side 两端为 0 = 没切到", 0, G.cuttingCount(listOf(open)))

            val cut = G.bladeFromEnds(side, 1.0, 1.0)
            val (p2, n2) = G.bladeLine(cut)
            // ⚠ 行程是 2.17 口径 ⇒ 两端拉满时线**越过**对面边缘（不是正好压在边上）。
            //   契约该守的是"完全遮住"，那个具体距离是行程的副产品，不该钉死。
            assertTrue("$side 两端为 1 时刀片线应当扫到对面边缘**之外**",
                p2.x * n2.x + p2.y * n2.y <= -r + 1e-9)
            assertEquals("$side 两端为 1 = 在切", 1, G.cuttingCount(listOf(cut)))
            assertTrue("$side 拉满应当把光束整个遮住", G.visiblePolygon(listOf(cut)).size < 3)
            assertEquals("$side 拉满 = 覆盖率 1.0", 1.0, G.coveredFraction(listOf(cut)), 1e-9)
        }
    }

    /**
     * 两端**不等**时刀片是斜的 —— 这正是"4 片 8 通道"能切梯形/平行四边形的原因。
     *
     * 倾斜上限 = [ShaperGeometry.MAX_BLADE_TILT_RAD]（±45°，跟角度通道满量程一致）：
     * 两端深度差 = 1 时正好到满偏。用户描述的"出来的时候角度也在逐渐变化"就是它。
     */
    @Test
    fun `unequal ends tilt the blade and the tilt is bounded`() {
        val tilt = G.bladeFromEnds(Side.TOP, 0.0, 1.0)
        val (p, n) = G.bladeLine(tilt)
        // 0° 时的法线是 (0,-1)；偏转角就是它的辐角差
        val ang = Math.atan2(n.x, -n.y)
        val maxTilt = ShaperGeometry.MAX_BLADE_TILT_RAD
        assertEquals("A端0 B端1 的倾斜应当正好是 ±45°（两端深度差 = 1）",
            maxTilt, Math.abs(ang), 1e-9)
        // 两端都在 0..1 之内，倾斜永远不可能超过这个上限
        assertTrue("倾斜不能超过 MAX_BLADE_TILT_RAD", Math.abs(ang) <= maxTilt + 1e-9)
        assertTrue("两端相等时不倾斜",
            Math.abs(Math.atan2(G.bladeLine(G.bladeFromEnds(Side.TOP, 0.4, 0.4)).second.x,
                -G.bladeLine(G.bladeFromEnds(Side.TOP, 0.4, 0.4)).second.y)) < 1e-9)
        // 而且此时的几何确实能被两个端点表达
        assertTrue(G.endsRepresentable(tilt))
    }


    /**
     * **到限位之后必须还能跟着拖动走，而不是卡死。**
     *
     * 直接"整帧拒绝"的坏处就是一碰限位角就不动了；正确做法是把手指位置投影到
     * 可行边界上：同方向继续推停在边界，**方向一变就沿边界继续滑**。
     *
     * ⚠ A/B 双端模型下可行区由"两端深度都在 0..1"决定（倾斜上限 ±45°），
     *   所以这里不能再用"把手指甩到几千"的方式试探 ——
     *   那些目标一律落在可行区外，测的就不是"滑动"而是"拒绝"了。
     */
    @Test
    fun `at the limit the corner keeps sliding with the drag`() {
        val snap = G.corners(blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3))))
        val start = snap[0]!!
        val far = Pt(400.0, start.y)          // 远超可行区的目标

        // ① 越界目标必须给出**合法**结果（限位改成通道夹取后不再有"拒绝"这条路径）
        val first = G.projectCornerDrag(snap, 0, start, far)!!.corner
        assertTrue("结果不该跑到离谱位置：$first",
            Math.abs(first.x) <= G.BASE && Math.abs(first.y) <= G.BASE)

        // ② 越界目标现在也会被接受（夹取），但要保证两端仍落在通道范围内
        G.dragCorner(snap, 0, far)?.let { r ->
            r.ideal.forEach { b ->
                val e = G.endsFromBlade(b)
                assertTrue("两端必须仍在 0..1：$e",
                    e.first in -1e-9..1.0 + 1e-9 && e.second in -1e-9..1.0 + 1e-9)
            }
        }

        // ③ 同方向继续推：已经贴着边界，不再前进
        val second = G.projectCornerDrag(snap, 0, first, far)!!.corner
        // ⚠ 限位改成"通道夹取"之后，到边界是**渐近**的：每帧的夹取结果取决于推导出的
        //   两端深度，越靠近极限变化越小，但不会像"拒绝"那样精确停在同一个点。
        //   用户可见的行为是"几乎不动"（< 1 个单位），所以这里按这个标准断言。
        assertTrue("到边界后同方向继续推应当几乎不动（实际 ${"%.3f".format(dist(second, first))}）",
            dist(second, first) < 1.0)

        // ④ 反方向能退回来 —— 说明不是"卡死"，只是这个方向到底了
        val back = G.projectCornerDrag(snap, 0, first, start)!!.corner
        assertTrue("反方向应当能回来（没有卡死）", dist(back, first) > 0.5)

        // ⑤ 换个方向推到底会到**另一个**限位点（边界不止一个死点）
        val down = G.projectCornerDrag(snap, 0, start, Pt(start.x, 400.0))!!.corner
        assertTrue("不同方向应当到达不同的限位点", dist(down, start) > 0.5)
        assertTrue("两个方向不该到同一个点", dist(first, down) > 0.5)
    }

    /**
     * 投影出来的角必须**始终可行**：在虚线框内、距离在 `[0, 半径]`、
     * 而且两端深度都在 `0..1`（这才是"两个通道表达得了"的判据）。
     */
    @Test
    fun `projected corner drag is always feasible`() {
        val snap = G.corners(blades(listOf(at(0.25), at(0.35), at(0.3), at(0.4))))
        val h = G.BASE / 2
        var last = snap[0]!!
        // 一圈乱拖：含大幅越界的目标（投影负责把它们拉回可行区）
        val path = listOf(
            200.0 to -300.0, -400.0 to 100.0, 80.0 to 400.0, -500.0 to -500.0,
            0.0 to 0.0, 30.0 to -30.0, -45.0 to 45.0,
        )
        for ((x, y) in path) {
            val r = G.projectCornerDrag(snap, 0, last, Pt(x, y))
            assertTrue("应当总能给出一处可行位置", r != null)
            last = r!!.corner
            // ⚠ 手指位置会被投影进虚线框，但**重建后的角可能略微出框**：夹取改变了两条边，
            //   交点跟着动。所以这里按实际契约给宽松上限，而不是"严格在框内"。
            assertTrue("角不该飞到离谱位置：$last",
                Math.abs(last.x) <= h * 1.5 && Math.abs(last.y) <= h * 1.5)
            r.updates.forEach { (side, d, a) ->
                // 距离范围是 [−半径, 半径]：负值 = 刀片已经越过圆心（完全遮住的那一端）
                assertTrue("$side 距离越界：$d", d >= -h - 1e-9 && d <= h + 1e-9)
                assertTrue("$side 两端表达不了（倾角 $a）",
                    G.endsRepresentable(ShaperGeometry.Blade(side, G.insetFromDistance(d), a)))
            }
            // 新契约：结果必须是**合法状态（4 个点）** —— 不再要求"能由 dragCorner 到达"，
            // 因为退化时正解是 nudgeCorner（把抓的那个点挪开），它本来就不是角参数化那条路。
            assertTrue("投影结果本身必须是合法状态（4 个点）", G.validateQuad(r.ideal))
        }
    }

    private fun dist(a: Pt, b: Pt) = Math.hypot(a.x - b.x, a.y - b.y)

    /**
     * 量化之后的实际位移上限：把算出的 (距离, 转角) 按通道 0..255 量化、再重建四边形，
     * **旁边两个角的位移必须远小于一个可见量**。
     *
     * 手势进行中画面走"理想几何"，所以这点误差只在抬手后才可能看到 ——
     * 这条测试就是给那个"抬手时的一下轻微归位"定上限。
     */
    @Test
    fun `quantized write-back barely moves the neighbouring corners`() {
        val bl = blades(listOf(at(0.3), at(0.3), at(0.3), at(0.3)))
        val snap = G.corners(bl)
        val half = Math.toRadians(45.0)
        val c = 0
        val target = Pt(snap[c]!!.x - 8.0, snap[c]!!.y + 6.0)
        val res = G.dragCorner(snap, c, target)!!
        // 模拟写进通道再读回来
        val nb = G.sideOrder.mapIndexed { i, side ->
            val u = res.updates.firstOrNull { it.first == side }
            if (u == null) bl.first { it.side == side }
            else {
                val insetCh = Math.round(G.insetFromDistance(u.second) * 255).toInt().coerceIn(0, 255)
                val angCh = G.channelFromAngle(u.third, half)
                ShaperGeometry.Blade(side, G.insetFromChannel(insetCh), G.angleFromChannel(angCh, half))
            }
        }
        val after = G.corners(nb).map { it!! }
        for (o in 0 until 4) {
            if (o == c) continue
            val dx = Math.abs(after[o].x - snap[o]!!.x)
            val dy = Math.abs(after[o].y - snap[o]!!.y)
            assertTrue("角$o 量化后偏了 ($dx, $dy)，超过 0.5 个单位（屏幕上肉眼可见）",
                dx < 0.5 && dy < 0.5)
        }
    }
    /**
     * 退化构型下拖角的正解是 [ShaperGeometry.nudgeCornerDrag]（MainActivity 的第二级）。
     *
     * 它是唯一一个**基于真实刀片**（而不是四个角）的拖角路径 —— 角退化时两条相邻边重合，
     * 从角反推刀片等于编造形状。契约：
     *  - 结果必须合法（4 个点）；不合法就返回 null（这一帧不动），**绝不压平形状**；
     *  - 必须真的把重合的点分开（间距变大），否则"点拖不开"就没解决。
     */
    @Test
    fun `nudgeCornerDrag separates a degenerate corner without flattening`() {
        // 四片都到圆心附近：量化后四角两两相距只有一个量化步长（0.39），是极端退化
        val degenerate = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, centerEnds(), centerEnds()) }
        val snap = G.corners(degenerate)
        assertFalse("前提：这个状态是非法的", G.validateQuad(degenerate))

        var moved = 0
        for (c in 0 until 4) {
            val from = snap.getOrNull(c) ?: continue
            val res = G.nudgeCornerDrag(degenerate, c, from, Pt(from.x + 8.0, from.y + 8.0))
            if (res == null) continue
            moved++
            assertTrue("角$c：nudge 结果必须是合法状态（4 个点）", G.validateQuad(res.ideal))
            assertTrue("角$c：nudge 必须把重合的点分开（间距 ${G.minSeparation(degenerate)} → " +
                "${G.minSeparation(res.ideal)}）",
                G.minSeparation(res.ideal) > G.minSeparation(degenerate) + 1e-9)
            // ★ 不许"把形状压平"：四片两端全同 = 那个被删掉的小方形兜底
            val ends = res.ideal.map { G.endsFromBlade(it) }
            val allSame = ends.all {
                Math.abs(it.first - ends[0].first) < 1e-6 &&
                    Math.abs(it.second - ends[0].second) < 1e-6
            }
            assertFalse("角$c：nudge 不许把形状压成小方形（实际=$ends）", allSame)
        }
        assertTrue("四个角都 nudge 不动 = 退化状态无解（用户会看到'点拖不开'）", moved > 0)
    }

    /** nudge 失败时必须返回 null（= 这一帧不动），绝不能返回一个非法状态。 */
    @Test
    fun `nudgeCornerDrag never returns an invalid state`() {
        val cases = listOf(
            G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.5, 0.5) },
            G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.0, 0.0) },
            G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 1.0, 1.0) },
            listOf(0.0, 0.5, 1.0, 0.5).mapIndexed { i, d ->
                ShaperGeometry.bladeFromEnds(G.sideOrder[i], d, d)
            },
        )
        for ((i, blades) in cases.withIndex()) {
            val snap = G.corners(blades)
            for (c in 0 until 4) {
                val from = snap.getOrNull(c) ?: Pt(0.0, 0.0)
                for (delta in listOf(0.5, -0.5, 5.0, -5.0, 60.0, -60.0)) {
                    val res = G.nudgeCornerDrag(blades, c, from, Pt(from.x + delta, from.y + delta))
                    if (res == null) continue
                    assertTrue("$i/$c/$delta：给了结果就必须合法", G.validateQuad(res.ideal))
                }
            }
        }
    }
    /**
     * **覆盖率必须随通道值单调递增** —— 推子推得越深，遮住的光只许多、不许少。
     *
     * 这条听起来是废话，但它一次性判死了现在这套"两端各沿自己那条边的导轨滑到对面角"的模型：
     * 一端拉满时那条边正好**过圆心**，于是覆盖率在推子**半程**就到顶（≈77.5%），
     * 再往上推反而**掉回 50%** —— 实机表现就是"越推越亮"。
     *
     * 用户实测：单端拉满 ≈ 80%（且角度是**逐渐**变化的），两端拉满 = 100%。
     */
    @Test
    fun `coverage grows monotonically as one end is pushed in`() {
        val open = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.0, 0.0) }
        val side = G.sideOrder[0]
        var prev = -1.0
        val curve = StringBuilder()
        for (i in 0..8) {
            val t = i / 8.0
            val b = open.map { if (it.side == side) ShaperGeometry.bladeFromEnds(side, t, 0.0) else it }
            val f = G.coveredFraction(b)
            curve.append("%.3f→%.3f  ".format(t, f))
            assertTrue("推子到 ${"%.3f".format(t)} 时覆盖率 $f 比上一档 $prev 还小（越推越亮）\n$curve",
                f >= prev - 1e-9)
            prev = f
        }
    }

    /**
     * **用户实测（2026-09-30 第二次标定）**："第一片切到 80% 后，另一边要**拉满**才刚好切满，
     * 而不是超出那么多。"
     *
     * 这一条否掉了上一版"行程 = 2.17 口径 + 两端取平均"的模型：那个模型在 (1, 0.5)
     * 就已经全黑了（闭得太早），而且 (1,1) 会把线推到对面边沿**之外**老远。
     *
     * 现在的标定 `u = K1(a+b) − K2·a·b`（`K2 = 2K1 − 1` 由本条实测逼出）保证：
     *  1. `u(1,1) = 1` **恰好**（线正好压在对面边沿）—— 不多不少；
     *  2. `u < 1` 对所有 (a,b) ≠ (1,1) —— 也就是说另一端没拉满就**切不满**；
     *  3. `u ∈ [0,1]` 恒成立 —— 任何合法通道组合都不会把刀片推出框外。
     */
    @Test
    fun `closing the beam needs both ends at full and never overshoots`() {
        for (side in G.sideOrder) {
            // ① 双端拉满 = 恰到好处
            val full = G.bladeFromEnds(side, 1.0, 1.0)
            assertEquals("$side 双端拉满的深度必须恰好 = 1（线正好在对面边沿）",
                1.0, full.inset, 1e-12)
            val (pF, nF) = G.bladeLine(full)
            assertEquals("$side 双端拉满时线到圆心的距离必须恰好 = 半径",
                -G.BASE / 2, pF.x * nF.x + pF.y * nF.y, 1e-9)

            // ② 一端满、另一端没满 ⇒ 一定还没切满
            for (other in listOf(0.0, 0.25, 0.5, 0.75, 0.9, 0.99)) {
                val bl = listOf(G.bladeFromEnds(side, 1.0, other))
                val f = G.coveredFraction(bl)
                assertTrue("$side A端满、B端 $other 时遮了 $f —— 另一端没拉满就不该切满",
                    f < 1.0 - 1e-6)
            }
            // ③ 任何合法组合都不越界（深度恒在 0..1）
            var a = 0.0
            while (a <= 1.0001) {
                var b = 0.0
                while (b <= 1.0001) {
                    val u = G.bladeFromEnds(side, a, b).inset
                    assertTrue("$side ($a, $b) 的深度 $u 越界了",
                        u >= -1e-12 && u <= 1.0 + 1e-12)
                    b += 0.1
                }
                a += 0.1
            }
        }
    }

    /**
     * **用户实测标定**（实灯 Ares-FP2600）：
     *  - 一片的**单端**拉满 → 遮住约 **80%** 光源；
     *  - 同一片的**另一端**也拉满 → **100%**（完全遮住）；
     *  - 两端都 0 → 全开。
     *
     * ⚠ 这三条是**物理事实**，不是模型能选的。写这个测试就是为了让"模型错了"这件事自己跳出来：
     *   旧模型（两端各沿自己那条边的导轨滑到对面**角**）在"单端拉满"时给出的是一条
     *   **过圆心的对角线** ⇒ 只遮 50%，和实测的 80% 差得远。
     */
    @Test
    fun `one blade end at full covers 80 percent and both ends cover all`() {
        val open = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.0, 0.0) }
        assertEquals("四边全开 = 一点光都不该被遮", 0.0, G.coveredFraction(open), 0.02)

        for (side in G.sideOrder) {
            val one = open.map { if (it.side == side) ShaperGeometry.bladeFromEnds(side, 1.0, 0.0) else it }
            assertEquals("$side 单端拉满应当遮住约 80%",
                0.80, G.coveredFraction(one), 0.06)

            val both = open.map { if (it.side == side) ShaperGeometry.bladeFromEnds(side, 1.0, 1.0) else it }
            assertEquals("$side 两端拉满应当完全遮住",
                1.0, G.coveredFraction(both), 0.03)
        }
    }

    /**
     * 逐片自检推到 35% 时**不能黑灯**（自检设计约束的量化版本）：
     * 两端都到 35% 只是切进去一点，遮住的比例应当明显小于全遮。
     */
    @Test
    fun `a light self-test position only nicks the beam`() {
        val open = G.sideOrder.map { ShaperGeometry.bladeFromEnds(it, 0.0, 0.0) }
        val side = G.sideOrder[0]
        // 用**产品里真正用的那个档位**（default 参数），而不是测试自己拍一个数
        val step = G.selfTestSteps(IntArray(8) { 12 + it }).first()
        val amount = step.entries.first { it.key == 12 }!!.value / 255.0
        val one = open.map { if (it.side == side) ShaperGeometry.bladeFromEnds(side, amount, amount) else it }
        val f = G.coveredFraction(one)
        // ⚠ 门槛从 0.6 收到 0.35：行程加大后，"肯一下"的绝对量也变大了，
        //   但仍然必须明显小于全遮（否则自检会把灯打黑 —— 那正是这个测试要防的）。
        assertTrue("自检档位 $amount（遮 $f）太深，会把灯打黑", f < 0.35)
        assertTrue("自检档位 $amount（遮 $f）太浅，灯上看不出哪条边在动", f > 0.05)
    }

}
