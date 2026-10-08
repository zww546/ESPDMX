package com.example.stagedmx

/**
 * 切割（framing shutter / shaper）几何 —— **纯逻辑，无 Android 依赖，可单测**。
 *
 * ## 为什么单独抽一个 object
 * 这是把演示程序里那套"4 条刀片线围出光斑"的算法搬进工程。它是纯数学，
 * 但一点都不能错：形状判错了、方向反了，现场看到的就是"这盏灯切出来的角度不对"，
 * 而且极难判断是灯的问题还是 App 的问题。抽出来之后用普通 JUnit 就能把
 * 矩形/梯形/平行四边形/圆形/切死 全部锁住（见 [ShaperGeometryTest]）。
 *
 * ## 物理模型（重要）
 * 4 片刀片，每片 = **一条可以平移 + 偏转的直线**（这就是"4 片 8 通道"的含义：
 * 每片的两个通道 = 这条线的两个自由度：偏移 + 角度）。
 * 光斑是圆的，所以：
 *
 *     可见光斑 = 圆 ∩ 四条半平面
 *
 * 刀片切进去的地方是**直边**，没切到的部分是**圆弧**。
 *
 * ## 坐标与归一化
 * 孔隙边长 = [BASE]（100），中心为原点，y 轴向下（和屏幕一致）。
 * 所有输出坐标都在 [-BASE/2, +BASE/2] 附近；调用方按自己的 View 尺寸缩放即可。
 *
 * ## ⚠ 两个不能改的前提（改了就画错）
 * 1. 刀片的行程必须**能进到孔隙内部**（见 [sideMargin]）：刀片"全开"时在孔隙外面，
 *    "全关"时越过对面。早期版本把"全开"位置正好压在孔隙边上，于是刀片一偏转
 *    就只能去切角，**永远切不出梯形/平行四边形** —— 那是模型错了，不是灯做不到。
 * 2. 偏转是**绕这条线自己的中点**摆动的（只转法线、不动线上那个点）。
 *    如果连中点也绕原点转，等于把整条线平移走，四片同角度会得到"两个正方形相交"
 *    的八角形，物理上不成立。
 */
object ShaperGeometry {

    /** 孔隙边长（归一化基准）。 */
    const val BASE = 100.0

    /**
     * 刀片"全开"时相对光束圆边的余量（占 [BASE] 的比例）。
     *
     * **0 = 全开时刀片线正好压在光斑边缘**（用户明确要求："切割片起始位置应该在光斑边缘"）。
     * 留余量会让滑条开头一段白白空跑（拉半天刀片还在圆外面），所以这里不留。
     *
     * ⚠ 这里曾经是 0.20、而角度是"绕刀片线中点转法线"，于是全开 + 角度到极值时
     *   四条边各啃掉光束一丝，圆被切成八边形（真机上就是这样）。
     *   现在角度只改方向、不动距离（见 [bladeLine]），
     *   所以"全开 = 干净的圆"与角度无关，余量取 0 也安全。
     */
    const val sideMargin = 0.0

    /** 圆近似成多少边形（用于裁剪与绘制）。64 足够平滑，点数量也不吓人。 */
    const val BEAM_SEGMENTS = 64

    /** 角度通道的满量程偏转（±45°）。 */
    const val MAX_ANGLE_RAD = Math.PI / 4

    /**
     * **用户实测**（实灯）：一片的**某一端**拉满、另一端保持 0 时，遮住光源约 **80%**；
     * 而**另一端也要拉满才"刚好"切满**（不是提前闭死、更不是超出）。
     */
    const val MEASURED_ONE_END_COVERAGE = 0.80

    /**
     * 逐片自检把一片推多深（归一化端点深度）。
     *
     * ⚠ 必须跟着深度标定一起调：老行程模型下 0.35 会把光束压掉 8 成（实测 0.82），
     *   **自检当场把灯打黑**。0.1 现在约遮 9%：灯上看得清哪条边在动，又不会黑灯。
     */
    const val SELF_TEST_AMOUNT = 0.1

    /**
     * **单端拉满**时刀片线压到的深度（单位 = 口径；0 = 在全开边沿、1 = 正好在对面边沿）。
     *
     * 由实测反解：覆盖 80% ⇒ 线到圆心的有符号距离 t* ≈ −0.49 ⇒ 深度 = (1 + |t*|)/2 ≈ 0.745。
     * （覆盖率只取决于线到圆心的距离，见 [coverageAtSignedDistance]。）
     */
    val ONE_END_INSET: Double =
        (1.0 + Math.abs(signedDistanceForCoverage(MEASURED_ONE_END_COVERAGE))) / 2.0

    /**
     * 两端深度之积的**互斥项**：`2·ONE_END_INSET − 1` ≈ 0.49。
     *
     * 它不是独立的可调参数，而是被"**另一端拉满才刚好切满**"这条实测**逼出来**的：
     * 深度取对称双线性式 `u = K1·(a+b) − K2·a·b`（唯一同时满足 `u(0,0)=0`、
     * `u(1,0)=K1=0.745`、`u(1,1)=1` 的形式），于是 `K2 = 2K1 − 1`。
     *
     * 这个形式的两个好处，都是用户明确要过的：
     *  1. **不会超出**：`K2 > 0` ⇒ u 在 [0,1]² 上的最大值落在角点 (1,1)，恰好 = 1
     *     （线正好压在对面边沿），任何合法端点都不会让线跑到框外；
     *  2. **不提前闭死**：`u(a,b) = 1` 只有 (1,1) 一个解，所以"一端满 = 80%"之后，
     *     另一端必须**拉满**才刚好切满 —— 实测就是这么说的。
     *
     * ⚠ 用户原话："第一片切到 80% 后，另一边要拉满才刚好切满，而不是超出那么多。"
     *   上一版模型（一端行程 = 2.17 口径、两端取平均）在 (1, 0.5) 就已经全黑了 —— 闭得太早。
     */
    val BOTH_END_CROSS: Double = 2.0 * ONE_END_INSET - 1.0

    /** 单端**倾斜**满量程：两端深度差 = 1 时的偏角（±45°，和角度通道满量程一致）。 */
    const val MAX_BLADE_TILT_RAD = Math.PI / 4

    /** 刀片 → 窗口哪条边。 */
    enum class Side(val cn: String) { TOP("上"), BOTTOM("下"), LEFT("左"), RIGHT("右") }

    /** 二维点（Double：几何判定里的平行/直角容差要靠它）。 */
    data class Pt(val x: Double, val y: Double)

    /**
     * 一片刀片的当前状态。
     *
     * @param inset    0 = 全开（完全不挡光）；1 = 全关（越过对面）；0.5 ≈ 压在孔隙中线
     * @param angleRad 相对"垂直自己那条边"的偏转，0 = 不偏
     */
    data class Blade(val side: Side, val inset: Double, val angleRad: Double = 0.0)

    // ---------------- 通道值 ↔ 归一化 ----------------

    /** 偏移通道值（0..255）→ inset。 */
    fun insetFromChannel(v: Int): Double = v.coerceIn(0, 255) / 255.0

    /**
     * 角度通道值（0..255）→ 弧度。
     *
     * ⚠ 用 128 当 0°，且**除以 127**（不是 127.5）：这样 128 精确等于 0°。
     *   除以 127.5 的话 128 → 0.18°，一个"默认状态"就自带斜角，
     *   正方形会被判成八角形。
     *
     * @param maxAngleRad 半量程。不同灯 0..255 对应的总角度不一样（有的 ±45°、
     *   有的 0..90°），量程填错的表现是"滑条拖一点、刀片偏很多"，所以要按灯型可调。
     */
    fun angleFromChannel(v: Int, maxAngleRad: Double = MAX_ANGLE_RAD): Double =
        (v.coerceIn(0, 255) - 128) / 127.0 * maxAngleRad

    /**
     * 弧度 → 角度通道值（用于把当前角度写回通道 / 显示回滑条）。
     *
     * ⚠ 必须**四舍五入**，不能直接 `toInt()`：`angleFromChannel` 再乘回来时
     *   浮点会有 1e-14 级的误差（比如 11 变成 10.999999999999998），
     *   `toInt()` 一截就掉一格。表现是"手一碰滑条值自己跳一下"，
     *   而且只在某些值上出现、极难复现。往返精度由单测
     *   `angle channel round trips through channelFromAngle` 钉住。
     */
    fun channelFromAngle(rad: Double, maxAngleRad: Double = MAX_ANGLE_RAD): Int {
        val v = Math.round(rad / maxAngleRad * 127.0 + 128.0)
        return if (v < 0L) 0 else if (v > 255L) 255 else v.toInt()
    }

    // ---------------- 基础几何 ----------------

    /** 光束圆（近似成多边形）。半径 = base/2。 */
    fun beamPolygon(base: Double = BASE, segments: Int = BEAM_SEGMENTS): List<Pt> {
        val r = base / 2
        return (0 until segments).map {
            val a = it.toDouble() / segments * Math.PI * 2
            Pt(r * Math.cos(a), r * Math.sin(a))
        }
    }

    private fun rotate(p: Pt, a: Double): Pt {
        val c = Math.cos(a); val s = Math.sin(a)
        return Pt(p.x * c - p.y * s, p.x * s + p.y * c)
    }

    /** 某条边的**朝外**基准法线（未偏转）。 */
    fun baseNormal(side: Side): Pt = when (side) {
        Side.TOP -> Pt(0.0, -1.0)
        Side.BOTTOM -> Pt(0.0, 1.0)
        Side.LEFT -> Pt(-1.0, 0.0)
        Side.RIGHT -> Pt(1.0, 0.0)
    }

    /**
     * 相邻顺序（屏幕上顺时针）：上 → 右 → 下 → 左。
     *
     * 四条刀片线两两相交出的 4 个点就是"连起来的四边形"的 4 个角，
     * 所以角只跟**边**有关，跟用户把哪片映射到哪条边无关 —— 画图和拖动都按这个顺序走，
     * 这样"示意线条"和"实际窗口"永远是同一个形状。
     */
    val sideOrder = listOf(Side.TOP, Side.RIGHT, Side.BOTTOM, Side.LEFT)

    /** 两条直线（各用"过点 + 法线"表示）的交点；平行返回 null。 */
    fun intersect(p1: Pt, n1: Pt, p2: Pt, n2: Pt): Pt? {
        // q = p1 + t·dir1（dir1 ⊥ n1），代入 dot(q − p2, n2) = 0 解 t
        val dx = -n1.y; val dy = n1.x
        val den = dx * n2.x + dy * n2.y
        if (Math.abs(den) < 1e-12) return null
        val t = ((p2.x - p1.x) * n2.x + (p2.y - p1.y) * n2.y) / den
        return Pt(p1.x + t * dx, p1.y + t * dy)
    }

    /**
     * 四片刀片线两两相交出来的 4 个角（顺序见 [sideOrder]）。
     *
     * 元素为 null = 这一对角的两条边平行（某两片同向），此时画不出闭合四边形，
     * 调用方应当退回"画整条线"的画法。
     */
    fun corners(blades: List<Blade>, base: Double = BASE): List<Pt?> {
        val lines = sideOrder.map { s ->
            val b = blades.firstOrNull { it.side == s } ?: return List(4) { null }
            bladeLine(b, base)
        }
        return (0 until 4).map { i ->
            val (p1, n1) = lines[i]
            val (p2, n2) = lines[(i + 1) % 4]
            intersect(p1, n1, p2, n2)
        }
    }

    /**
     * 反过来：由一条直线（过两点）算出该片的 (到圆心的距离, 相对基准法线的转角)。
     *
     * 拖"角"时必须走这一步 —— 角动了两条相邻边跟着转，得把新的直线再翻译回
     * (偏移, 角度) 才能写进通道。
     *
     * @param a,b 直线上的两点
     * @param side 这片属于哪条边（决定法线朝哪边为正）
     * @return (d, angleRad)；两点重合定不出线时返回 null
     */
    fun lineParam(a: Pt, b: Pt, side: Side): Pair<Double, Double>? {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = Math.hypot(dx, dy)
        if (len < 1e-9) return null
        // 垂直于方向的两个法线取朝外的那个
        var nx = dy / len; var ny = -dx / len
        val n0 = baseNormal(side)
        if (nx * n0.x + ny * n0.y < 0) { nx = -nx; ny = -ny }
        val d = a.x * nx + a.y * ny
        // 从基准法线到 n 的带符号夹角
        val ang = Math.atan2(n0.x * ny - n0.y * nx, n0.x * nx + n0.y * ny)
        return d to ang
    }

    /** 刀片线到圆心的距离 → 该片的偏移量（0 = 在光斑边缘，1 = 正好过圆心）。 */
    fun insetFromDistance(d: Double, base: Double = BASE): Double {
        val d0 = base / 2 + sideMargin * base
        if (d0 <= 0.0) return 0.0
        // ⚠ 不夹上限：深度由 [bladeFromEnds] 标定在 [0,1]（双端满 = 线正好在对面边沿），
        //   这里保留原始值是为了让"端点深度往返"精确守恒 —— 夹取会把"这个姿态两个通道
        //   表达不了"悄悄变成"表达成别的形状"。通道域 0..1 由 endsFromBlade /
        //   quantizeEnds / 拖拽夹取那一层守，几何层不管。
        return 0.5 * (1.0 - d / d0)
    }

    /**
     * 一条刀片线在**有符号距离** t（单位 = 光斑半径，正 = 偏"全开"那一侧）时遮住的比例。
     *
     * 圆被一条弦切出的弓形面积公式（`acos(u) − u√(1−u²)`）换算而来：
     * t = +1（压在全开那条边沿）→ 0；t = 0（过圆心）→ 0.5；t = −1（压到对面）→ 1。
     */
    fun coverageAtSignedDistance(t: Double): Double {
        if (t >= 1.0) return 0.0
        if (t <= -1.0) return 1.0
        return 0.5 - (Math.asin(t) + t * Math.sqrt(1.0 - t * t)) / Math.PI
    }

    /** [coverageAtSignedDistance] 的反函数。 */
    fun signedDistanceForCoverage(covered: Double): Double {
        var lo = -1.0
        var hi = 1.0
        repeat(60) {
            val mid = (lo + hi) / 2
            // ⚠ 覆盖率对 t 是**单调递减**的（t 越大越接近"全开"，遮得越少）：
            //   算出来的比目标小 ⇒ mid 太靠 +t 侧 ⇒ 答案在左边 ⇒ 收 hi。
            //   写反了会收敛到 t=+1（覆盖率 0），后面反解出行程 = +∞，整套几何变 NaN。
            if (coverageAtSignedDistance(mid) < covered) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }

    /** 两端深度 → 那条线应有的有符号距离 t（单位 = 半径）。单测和自检都用它做标定。 */
    fun signedDistanceOf(travel: Double, depthA: Double, depthB: Double): Double {
        val s = depthA + depthB
        val d = depthA - depthB
        return (1.0 - travel * s) / Math.sqrt(1.0 + travel * travel * d * d)
    }

    /** 点 [q] 是否落在多边形 [poly] 内（射线法）。用于"按在图形内部就算抓住了"。 */
    fun insidePolygon(poly: List<Pt>, q: Pt): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > q.y) != (b.y > q.y)) {
                val x = (b.x - a.x) * (q.y - a.y) / (b.y - a.y) + a.x
                if (q.x < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 两端深度 → **写进通道再折算回来**的值（0..255 的整数步）。
     *
     * 这就是真正下发到灯里、松手后读回来画的那个数。任何"这个状态合不合法"的判断
     * 都必须拿它做对象，否则会出现"未量化的理想值刚好通过、量化后已经塌了"——
     * 也就是用户看到的"拖的时候好像没事，一松手变成 3 个点"。
     */
    fun quantizeEnds(depth: Double): Double =
        Math.round(depth.coerceIn(0.0, 1.0) * 255).toDouble() / 255.0

    /**
     * 把整套刀片按"通道量化"重建 —— **真正写进灯里、读回来画的那套几何**。
     *
     * 校验、绘图、以及"下一帧的基准"都应该用它，不该用未量化的理想值：
     * 理想值和量化值是两套几何，差着最多 1 个 LSB（角上约 1.5 个单位），
     * 在校验边界上足以让"通过"变成"塌掉"。
     */
    fun quantized(blades: List<Blade>, base: Double = BASE): List<Blade> = blades.map { b ->
        val (a, c) = endsRaw(b, base)
        bladeFromEnds(b.side, quantizeEnds(a), quantizeEnds(c), base)
    }

    /**
     * 由四个角反推四片（退化状态下 [corners] 已经算不出来，只能从这里还原）。
     * 第 L 条边 = 过 `角(L−1)` 和 `角L` 的直线。
     */
    private fun bladesOf(corners: List<Pt?>, base: Double = BASE): List<Blade> =
        sideOrder.mapIndexed { li, side ->
            val a = corners[(li + 3) % 4]
            val b = corners[li]
            if (a == null || b == null) Blade(side, 0.0, 0.0)
            else lineParam(a, b, side)?.let {
                Blade(side, insetFromDistance(it.first, base), it.second)
            } ?: Blade(side, 0.0, 0.0)
        }
    /** 相邻（含对角）角的最小间距，占 [BASE] 的比例。 */
    const val QUAD_MIN_SEP = 0.02

    /**
     * **硬不变量：四边形永远必须有 4 个互不重合的角。**
     *
     * 用户原话："**4 个点就不应该变成 3 个点**"（一个角重合到另一个上，看起来就是 3 个）。
     * 角一重合就全盘皆输：
     *  - 相邻两角重合 ⇒ "过两点定一条直线"失效 ⇒ [corners] 出现 null ⇒
     *    绘图退回"画整条线"（看起来就是线被加长）；
     *  - 两角叠在一处 ⇒ 命中测试分不出抓的是谁，**拖角再也分不开**。
     *
     * ⚠ 两个要点，缺一个就会"看着修好了、实际没修"：
     *  1. 判据必须收在**状态层**、由**每一个写入口**共用 —— 以前只在拖角那条路上拦，
     *     于是拖线段 / 退化兜底 / 直接写通道都能把它弄塌，每修一条另一条又塌。
     *  2. 判据的对象必须是**量化后的状态**（[quantized]），不是未量化的理想值 ——
     *     理想值通过、量化后塌掉，正是"松手才变成 3 个点"的成因。
     */
    fun validateQuad(blades: List<Blade>, base: Double = BASE): Boolean {
        if (blades.size != 4) return false
        val q = quantized(blades, base)          // ← 校验的是真正会写下去的那套
        if (corners(q, base).any { it == null }) return false
        return minSeparation(blades, base) >= base * QUAD_MIN_SEP
    }

    /**
     * 四角之间**最小**的那对距离（量化后算）。
     *
     * 它是"退化程度"的度量：0 = 有角重合；越大越健康。
     * 拖线段在非法状态下靠它做**单调改进**（只接受不让它变小的平移）。
     * 角算不出来（相邻边平行）时返回 0（= 最退化）。
     */
    fun minSeparation(blades: List<Blade>, base: Double = BASE): Double {
        val cs = corners(quantized(blades, base), base)
        if (cs.any { it == null }) return 0.0
        var minSep = Double.MAX_VALUE
        for (i in 0 until 4) for (j in i + 1 until 4) {
            val a = cs[i]!!; val b = cs[j]!!
            minSep = minOf(minSep, Math.hypot(a.x - b.x, a.y - b.y))
        }
        return minSep
    }

    /**
     * 拖线段（平移）时**带上整表校验**：把距离夹到"这个四边形仍然合法"的最远处。
     *
     * 平移只看这一片的两端，**看不到对边**，所以它能把一条边推到和对边重合 ——
     * 那正是"4 个点变 3 个点"的另一条来源（用户就是"一角往中间拖"触发的）。
     * 目标合法就用目标，否则二分找最远的合法距离（到极限是整条线停下，而不是把四边形压塌）。
     */
    fun translateBladeChecked(
        blades: List<Blade>, side: Side, wantDistance: Double, base: Double = BASE
    ): Pair<Double, Double>? {
        val cur = blades.firstOrNull { it.side == side } ?: return null
        val (cp, cn) = bladeLine(cur, base)
        val curD = cp.x * cn.x + cp.y * cn.y
        fun validAt(d: Double): Boolean {
            val ends = translateBlade(cur, d, base)
            val moved = bladeFromEnds(side, ends.first, ends.second, base)
            return validateQuad(blades.map { if (it.side == side) moved else it }, base)
        }
        if (validAt(wantDistance)) return translateBlade(cur, wantDistance, base)

        // ⚠ 当前状态**可能本来就是非法的**（退化：四片都到圆心之类）。
        //   这时"从 curD 往目标二分"永远找不到合法点（lo 一直停在 curD），
        //   于是原样返回一个非法状态 —— 那就**永远逃不出退化**了。
        //   所以先在整段可用距离里搜"最接近目标的合法距离"，用它把状态**修回来**。
        //   这是"拖线段是逃出退化的那条路"能成立的前提。
        if (!validAt(curD)) {
            // 当前非法：用**单调改进**规则 —— 接受这次平移，只要它不让"最小角间距"变小。
            // 这样一次次拖线段能把状态一步步带出退化（从"四片都到圆心"出发，一次平移
            // 修不好全部，但一定能让间距变大）。**不许变得更糟**是这条规则的底线。
            val now = minSeparation(blades, base)
            val want = translateBlade(cur, wantDistance, base)
            val wantBlades = blades.map {
                if (it.side == side) bladeFromEnds(side, want.first, want.second, base) else it
            }
            if (minSeparation(wantBlades, base) >= now - 1e-9) return want
            // 目标反而更糟 → 在整段距离里找"间距最大"的那个（至少改善一点）
            val r = base / 2
            var bestD = Double.NaN
            var bestSep = now
            val n = 200
            for (i in 0..n) {
                val d = -r + 2.0 * r * i / n
                val e = translateBlade(cur, d, base)
                val nb = blades.map {
                    if (it.side == side) bladeFromEnds(side, e.first, e.second, base) else it
                }
                val sep = minSeparation(nb, base)
                if (sep > bestSep + 1e-9) { bestSep = sep; bestD = d }
            }
            if (bestD.isNaN()) return null          // 这一片怎么平移都改善不了
            return translateBlade(cur, bestD, base)
        }

        var lo = curD
        var hi = wantDistance
        repeat(24) {
            val mid = (lo + hi) / 2
            if (validAt(mid)) lo = mid else hi = mid
        }
        return translateBlade(cur, lo, base)
    }

    /**
     * 把一条直线裁到虚线框（±base/2）内，返回两个端点。
     *
     * 用于绘图：某两片近乎平行、交点算不出来时，**不能**退回"按固定长度画整条线" ——
     * 那看起来就是"切片的直线被加长了"（用户报的现象）。裁到框内长度就稳定了。
     */
    fun clipLineToSquare(p: Pt, n: Pt, base: Double = BASE): Pair<Pt, Pt>? {
        val h = base / 2
        val dx = -n.y; val dy = n.x           // 线方向
        if (Math.abs(dx) < 1e-12 && Math.abs(dy) < 1e-12) return null
        var lo = Double.NEGATIVE_INFINITY
        var hi = Double.POSITIVE_INFINITY
        // 对 x、y 两个维度各求一次 t 的可行区间，取交集
        for (k in 0 until 2) {
            val p0 = if (k == 0) p.x else p.y
            val d0 = if (k == 0) dx else dy
            if (Math.abs(d0) < 1e-12) {
                if (Math.abs(p0) > h) return null      // 平行于该轴且在框外 → 无交
            } else {
                val t1 = (-h - p0) / d0
                val t2 = (h - p0) / d0
                lo = maxOf(lo, minOf(t1, t2))
                hi = minOf(hi, maxOf(t1, t2))
            }
        }
        if (lo > hi) return null
        return Pt(p.x + dx * lo, p.y + dy * lo) to Pt(p.x + dx * hi, p.y + dy * hi)
    }

    /** [insetFromDistance] 的反函数（画图和测试用）。 */
    fun distanceFromInset(inset: Double, base: Double = BASE): Double =
        (base / 2 + sideMargin * base) * (1.0 - 2.0 * inset)

    /**
     * 两端**同步**推进（a = b = x）时，要达到深度 [u] 所需的单端深度 x。
     *
     * 由 `u = 2·K1·x − K2·x²` 反解（取 [0,1] 里那个根）。
     * 用途：几何测试要构造"四片刚好都过圆心"（u = 0.5）这类精确构型时，
     * 不能再拍 0.5（那是端点的中点，不是深度的中点）。
     */
    fun flatEndForInset(u: Double): Double {
        val disc = ONE_END_INSET * ONE_END_INSET - BOTH_END_CROSS * u
        if (disc < 0.0) return Double.NaN
        return (ONE_END_INSET - Math.sqrt(disc)) / BOTH_END_CROSS
    }

    /**
     * 由四片的**两个端点深度**（顺序同 [sideOrder]）构造四片刀片。
     *
     * ⚠ 这是**通道层**的构造器（自检、通道回放用）。别用 [bladesOf] 那套
     *   "一片 = 偏移 + 角度"的老映射去解释同一批通道值 —— 那是上一版模型。
     */
    fun bladeEndsOf(endsA: List<Double>, endsB: List<Double>, base: Double = BASE): List<Blade> =
        (0 until 4).map { bladeFromEnds(sideOrder[it], endsA[it], endsB[it], base) }

    // ---------------- A/B 双端模型 ----------------
    //
    // 灯库里的 BLADE1A / BLADE1B 是**同一片刀片的两个端点**（两端各自进出，合成
    // 平移 + 倾斜），不是"一片的偏移 + 一片的角度"。实测依据：A、B 两个通道的
    // 物理量程**完全相同**（Ares-FP2600 都是 0..0.45）——如果是偏移+角度，量程
    // 不会一样。把它们当"偏移+角度"处理时，**画面看着等价，但发给灯的值是错的**：
    // 写个"角度 128"到 B 通道，等于让 B 端伸进去 50%。
    //
    // ## 深度不是几何推出来的，是**按实测标定**的
    //
    // 一度想用"两端各沿自己那条导轨滑动、线过两端点"来推深度：那条路在
    // `T = 1`（行程 = 口径）时**单端拉满恒为 50%**（线正好过圆心），到不了实测的 80%；
    // 把行程放大到 2.17 口径能凑出 80%，但 (1, 0.5) 就已经闭死 —— 用户实测明确是
    // "另一端**拉满**才刚好切满"。两条约束在这个几何族里**无法同时满足**，所以深度
    // 改成直接标定：`u = K1(a+b) − K2·a·b`（见 [ONE_END_INSET] / [BOTH_END_CROSS]）。
    //
    // 倾角仍然按"两端之差"给：[MAX_BLADE_TILT_RAD]，这就是用户描述的
    // "出来的时候角度也在逐渐变化"。

    /** 某条边的 A 端所在的那个方框角，以及 A→B 的单位切向（现在只用于约定方向）。 */
    private fun endBase(side: Side, base: Double): Pair<Pt, Pt> {
        val r = base / 2
        // 顺时针绕一圈：上(左→右)、右(上→下)、下(右→左)、左(下→上)
        return when (side) {
            Side.TOP -> Pt(-r, -r) to Pt(1.0, 0.0)
            Side.RIGHT -> Pt(r, -r) to Pt(0.0, 1.0)
            Side.BOTTOM -> Pt(r, r) to Pt(-1.0, 0.0)
            Side.LEFT -> Pt(-r, r) to Pt(0.0, -1.0)
        }
    }

    /**
     * 由两端的**归一化压入深度**构造刀片（通道层唯一的映射）。
     *
     * - 深度：`u = K1·(a+b) − K2·a·b`，`u ∈ [0,1]` 天然成立（见 [BOTH_END_CROSS]）；
     * - 倾角：`(b − a) · MAX_BLADE_TILT_RAD`（B 端更深 = 正）。
     *
     * 于是四条实测行为同时成立：全开 0%、单端满 80%、**双端满才刚好 100%**、角度渐变。
     */
    fun bladeFromEnds(side: Side, depthA: Double, depthB: Double, base: Double = BASE): Blade {
        val u = ONE_END_INSET * (depthA + depthB) - BOTH_END_CROSS * depthA * depthB
        val tilt = (depthB - depthA) * MAX_BLADE_TILT_RAD
        return Blade(side, u, tilt)
    }

    /**
     * 反过来：刀片 → 两端的归一化压入深度，**不夹取**。
     *
     * 不夹取是故意的：判断"这个几何能不能被两个通道表达"必须看原始值 ——
     * 夹取会把"表达不了"悄悄变成"表达成别的形状"，正是之前"旁边的角被拖走"的成因。
     *
     * 反解：记 `s = a+b`、`diff = b−a`（= angleRad / MAX_BLADE_TILT_RAD），
     * `u = K1·s − (K2/4)(s² − diff²)` ⇒ `(K2/4)s² − K1·s + (u − K2·diff²/4) = 0`。
     * 取**小根**（另一个根对应 s ≫ 2、端点必然越界）；再拆出 a、b。
     * 越界的状态就让返回值落在 0..1 之外，交给 [endsRepresentable] 去拒绝。
     */
    fun endsRaw(b: Blade, base: Double = BASE): Pair<Double, Double> {
        val diff = b.angleRad / MAX_BLADE_TILT_RAD
        val qa = BOTH_END_CROSS / 4.0
        val qb = -ONE_END_INSET
        val qc = b.inset - BOTH_END_CROSS * diff * diff / 4.0
        if (Math.abs(qa) < 1e-12) return 0.0 to 0.0
        val disc = qb * qb - 4.0 * qa * qc
        if (disc < 0.0) return Double.NaN to Double.NaN     // 这个姿态两个通道表达不了
        // 数值稳定的小根：s = 2c / (−qb + √disc)
        val sq = Math.sqrt(disc)
        val s = 2.0 * qc / (-qb + sq)
        return (s - diff) / 2.0 to (s + diff) / 2.0
    }

    /** 刀片 → 两端深度（夹进 0..1，写通道用）。 */
    fun endsFromBlade(b: Blade, base: Double = BASE): Pair<Double, Double> {
        val (a, b2) = endsRaw(b, base)
        return a.coerceIn(0.0, 1.0) to b2.coerceIn(0.0, 1.0)
    }

    /** 给定倾角（两端深度差）时，**两个端点都落在 0..1** 所允许的深度区间。 */
    fun insetRangeFor(diff: Double): Pair<Double, Double> {
        // a = (s−diff)/2、b = (s+diff)/2 都要落在 0..1 ⇒ s ∈ [|diff|, 2 − |diff|]
        // ⚠ 上界是 **2 − |diff|**，不是 2：s 取到 2 时有一端会 = (2+|diff|)/2 > 1（越界）。
        //   深度 u 在 s 的可行区间上单调递增（顶点在 s = 2K1/K2 ≈ 3.04，在区间之外）。
        val d = Math.abs(diff)
        val sMin = d
        val sMax = (2.0 - d).coerceAtLeast(d)
        fun uAt(s: Double) = ONE_END_INSET * s - BOTH_END_CROSS * (s * s - diff * diff) / 4.0
        return uAt(sMin) to uAt(sMax)
    }

    /**
     * 把一片刀片**整条平移**到新的距离（拖线段用），返回它两端的新深度。
     *
     * ⚠ 平移 = **两端同样地进/退**，也就是"两端深度之差不变"（倾角保住）。
     *   曾经这里被写成"把两端设成同一个值" —— 那等于**把倾角清零**：
     *   先拖角把某片扭出一个角度，再拖这条线，角度就没了（用户报的 bug）。
     *
     * ⚠ 越界时夹的是**距离**，不是两端：夹两端会让某一端先撞到滑轨尽头，
     *   另一端还在动 → 倾角照样被改。"拖线段只平移"这条性质必须无条件成立。
     *   可行距离区间由 [insetRangeFor]（两端都 ∈ 0..1）反解出来。
     */
    fun translateBlade(b: Blade, newDistance: Double, base: Double = BASE): Pair<Double, Double> {
        val r = base / 2
        val diff = b.angleRad / MAX_BLADE_TILT_RAD
        val (uLo, uHi) = insetRangeFor(diff)
        val dLo = distanceFromInset(uHi, base)      // 深度越大 → 距离越小
        val dHi = distanceFromInset(uLo, base)
        val d = newDistance.coerceIn(maxOf(dLo, -r), minOf(dHi, r))
        return endsFromBlade(Blade(b.side, insetFromDistance(d, base), b.angleRad), base)
    }

    /** 这片刀的几何能不能被"两个 0..1 的端点通道"精确表达。 */
    fun endsRepresentable(b: Blade, base: Double = BASE, eps: Double = 1e-9): Boolean {
        val (a, c) = endsRaw(b, base)
        return a >= -eps && a <= 1 + eps && c >= -eps && c <= 1 + eps
    }

    /**
     * 拖角的结果。
     *
     * @param updates 要写进通道的两条边：(边, 到圆心距离, 相对基准法线的转角)
     * @param ideal   **理想几何**（未量化）的四片，用来立刻重绘。
     *   为什么要它：通道只有 0..255，写回去必然带 ≤1 个步长的误差；而相邻两条边是
     *   **绕对面的角**转的，转角差 1 个步长（±45° 量程下 0.354°）会让对面的角移动
     *   `d·δ` ≈ 0.3 个单位（屏幕上约 2px）。每帧都带着这点误差重绘，看上去就是
     *   "旁边的角在抖"。手势进行中画理想值、松手后再回到读回值，视觉上完全跟手。
     */
    data class CornerDrag(
        val updates: List<Triple<Side, Double, Double>>,
        val ideal: List<Blade>,
        /** 这次实际用到的角位置（已按虚线框夹过）。调用方拿它当下一帧的"起点"。 */
        val corner: Pt,
    )

    /**
     * 拖角：把第 [cornerIndex] 个角挪到 [newPoint]，算出**受影响的边**各自的新 (距离, 转角)。
     *
     * ## 基准必须用"手势开始时快照的四个角"，不要每帧从刀片现算
     *
     * 通道只有 0..255，每次写回都带量化误差。如果每一帧都拿**当前**（已量化的）角坐标
     * 当基准，那两个"本该不动的角"每帧都会被重新当成基准，误差逐帧累积 ——
     * 真机上就表现成"拖一个角，旁边的角一直在飘"。
     * 所以调用方在 ACTION_DOWN 时快照一次 [fromCorners]，整个拖动过程都用它。
     *
     * ## 哪两条边会动（这里踩过坑）
     *
     * [corners] 的定义是"角 i = 第 i 条边 ∩ 第 i+1 条边"，所以：
     *  - 碰上角 i 的只有第 **i** 条和第 **i+1** 条边；
     *  - 第 L 条边的两个角是 `角(L−1)` 和 `角L`。
     *
     * ⚠ 第一版把受影响的下标写成了 `i−1` 和 `i`：结果**只有一条边真的跟着动**，
     *   另一条不动 —— 一个角被夹在"动了的边"和"没动的边"之间，只能沿那条不动的边
     *   滑动（表现成"拖角也只往两个方向走"）。
     *
     * ## 限位：**表达不了就整帧拒绝**，绝不夹取
     *
     * 三条都必须满足，否则这一帧返回 null（角稳稳停在上一处合法位置）：
     *  1. 手指位置夹进虚线框（±base/2）；
     *  2. 限位改成**按通道值夹取**：两端深度各自夹进 0..1（= 通道 0..255），再用夹取后的两端重建这条边；
     *  3. 两条边必须能被**它们各自那两个端点通道**精确表达（见 [endsRepresentable]）。
     *
     * ⚠ 第 3 条漏过一次：当时只夹距离、不管角度，转角被 `channelFromAngle` 截断到量程端点，
     *   写进灯里的那条边**不再经过"固定不动"的那个角**，旁边的角就被拖着走；
     *   手指来回动会反复跨过这个边界 —— 表现就是"拖动时旁边的角在抖动"。
     *   改成 A/B 双端模型后，"能不能表达"的判据就是**两端深度是否都落在 0..1** ——
     *   几何上它等价于倾斜量不超过 `atan(0.5) ≈ 26.6°`（方框的对角约束），
     *   不再是那个拍脑袋定的 ±45°。
     *
     * ⚠ 这些一律用**拒绝**而不是夹取：夹取之后"这条边同时过它的两个角"这个前提就失效了，
     *   重建出来的四边形会跳到不知道哪里去（实测角会飞到 x≈482）。
     *
     * @param fromCorners 手势开始时快照的四个角（顺序同 [corners]）
     * @return 见 [CornerDrag]；越界、退化或表达不了时返回 null
     */
    fun dragCorner(
        fromCorners: List<Pt?>, cornerIndex: Int, newPoint: Pt, base: Double = BASE
    ): CornerDrag? {
        if (fromCorners.size != 4 || fromCorners.any { it == null }) return null
        if (cornerIndex !in 0 until 4) return null
        val h = base / 2
        val pts = fromCorners.map { it!! }.toMutableList()
        // ⚠ **不要**把角夹进虚线框。角本来就可能落在框外：
        //   刀片越过圆心（通道 >128）且倾角一大，相邻两边的交点能跑到 `(−25, −60.4)`
        //   这种位置（|y| > 半径）。夹了之后推导出的两条边就不是原来那两条，
        //   于是**连"原地不动"那一帧都被判不合法** → 拖角完全不动。
        //   而且"不许超过虚线"约束的是**刀片线**（下面限位 2 已保证），不是角：
        //   每条边距离 ∈[−半径,半径] ⇒ 窗口 ⊆ 光束圆 ⊆ 虚线框，自动成立。
        pts[cornerIndex] = newPoint

        // 限位 0：**任意两个角都不能靠得太近**。
        //
        // ⚠ 用户报的现象："往对角拖时两个角重叠在一起无法拖开，而且表示切片的直线被加长"。
        //   角一旦重合：① 相邻两角重合 → "过两点定一条直线"失效 → corners() 出现 null，
        //   绘图退回"画整条线"（看起来就是线被加长），拖角参数化也整个失效；
        //   ② 两个角叠在同一处，命中测试分不出抓的是哪个，也就分不开。
        //   所以在**源头**拦住：靠太近就整帧拒绝，角停在上一处合法位置，永远能拖回来。
        //   同一对角的"太近"可能来自相邻（四边形塌成一条线）也可能来自对角
        //   （四边形拧成领结），所以 6 对全查。
        val minSep = base * 0.02          // 2% 孔径，屏幕上约 5~7px，看得出也点得中
        for (i in 0 until 4) for (j in i + 1 until 4) {
            val a = pts[i]; val b = pts[j]
            if (Math.hypot(a.x - b.x, a.y - b.y) < minSep) return null
        }

        val updates = ArrayList<Triple<Side, Double, Double>>(2)
        val replaced = HashMap<Side, Blade>(2)
        for (k in 0 until 2) {
            val li = (cornerIndex + k) % 4          // ← 就是这个下标，别写成 i−1
            val side = sideOrder[li]
            val a = pts[(li + 3) % 4]               // 这条边另一头的角（不动的那头）
            val b = pts[li]
            val pr = lineParam(a, b, side) ?: return null

            // ---- 限位：**按通道值夹取**，不再整帧拒绝 ----
            //
            // 先算出这条边两端的期望深度，各自夹进 0..1（= 通道 0..255），
            // 再用夹取后的两端**重建**这条边。
            //
            // ⚠ 为什么比"拒绝"好：写通道那一步本来就是无条件夹取的
            //   （`setShaperBladeEnds` 里 `coerceIn(0,1) * 255`），所以几何层拒绝 ≠ 值没被夹，
            //   只是 UI 假装没动 —— 两套语义打架，还派生出 `endsRepresentable`、
            //   相邻边平行判定、二分投影、两条射线取大、退化兜底这一整套。
            //   改成夹取之后：**每一帧都必然是合法状态**，手感变成"滑条推到尽头"，
            //   限位单位也回到用户自己设的那个（通道 0..255）。实测那个"角飞到 x≈482"
            //   是当年"夹距离但用角反推直线"的写法造成的，用两端深度重建不会有这个问题。
            val want = Blade(side, insetFromDistance(pr.first, base), pr.second)
            val wantEnds = endsRaw(want, base)
            val e0 = wantEnds.first.coerceIn(0.0, 1.0)
            val e1 = wantEnds.second.coerceIn(0.0, 1.0)
            val got = bladeFromEnds(side, e0, e1, base)
            val (gp, gn) = bladeLine(got, base)
            updates.add(Triple(side, gp.x * gn.x + gp.y * gn.y, got.angleRad))
            replaced[side] = got
        }

        // 理想四片：只把受影响的两条边换成重建后的，其余按快照原样还原
        val ideal = sideOrder.map { side ->
            replaced[side] ?: run {
                val li = sideOrder.indexOf(side)
                val pr = lineParam(pts[(li + 3) % 4], pts[li], side) ?: return null
                Blade(side, insetFromDistance(pr.first, base), pr.second)
            }
        }
        // ⚠ 防重合必须查**夹取重建后的真实角**，不能只查手指请求的那几个点。
        //   夹取会改变那两条边 ⇒ 重建后的角可能远比请求点更近，甚至重合（"三角形重合"）。
        //   角一重合：相邻两角定不出直线 ⇒ corners() 出 null ⇒ 绘图退回"画整条线"
        //   （看起来就是线被加长），而且命中分不出抓的是哪个角，也就分不开。
        // 走**同一个**状态层判据（validateQuad），别在这里再写一套阈值。
        //   注意 validateQuad 内部会先量化，所以这里判的是"真正会写下去的那套"。
        if (!validateQuad(ideal, base)) return null

        // ⚠ 返回给外面的必须是**量化后**的那套（= 真正写进通道、松手读回来画的），
        //   不能是未量化的理想值：两者差最多 1 个 LSB，在校验边界上正好是
        //   "拖的时候看着没事、松手变成 3 个点"的缝隙。绘图和下一帧基准都用它。
        val out = quantized(ideal, base)
        if (!validateQuad(out, base)) return null

        // ⚠ realCorner 也要算在**同一套（量化后）**几何上：它是下一帧的射线起点，
        //   拿理想值当基准会让每帧都有微小漂移。
        val b0 = out.first { it.side == sideOrder[cornerIndex] }
        val b1 = out.first { it.side == sideOrder[(cornerIndex + 1) % 4] }
        val (lp0, ln0) = bladeLine(b0, base)
        val (lp1, ln1) = bladeLine(b1, base)
        val realCorner = intersect(lp0, ln0, lp1, ln1) ?: pts[cornerIndex]
        return CornerDrag(updates, out, realCorner)
    }

    /**
     * **退化时的正确兜底：把"你抓的那个角"的两条相邻边各自沿自己的法线平移。**
     *
     * 这等价于"同时拖这两条线段" —— 形状保住，而重合的两个点会被**分开**。
     *
     * ⚠ 原来这一级是"四片按手指半径整体缩放"，它会把形状
     *   **压平成一个正方形**：状态确实变了，但用户要的动作（把这个点挪开）没发生 ——
     *   表现就是"**点拖不开**"（用户原话："拖动到原来边三角形的位置会重置成一个小方形
     *   也是不对的"）。这是"能动了就当修好了"的典型误判，那段代码已经删掉。
     *
     * 只动两条相邻边：角就是这两条边的交点，把它们各自推开，角自然分开，
     * 其余两片纹丝不动（用户只抓了一个点，不该动别处）。
     *
     * ⚠ 必须基于**真实刀片**调用，不能从四个角反推 —— 角退化时反推会编造形状。
     *   拖角链路里能同时拿到刀片的只有调用方，所以这里只收 `blades`。
     *
     * @return 新的四片；新状态必须通过 [validateQuad]，否则返回 null（这一帧不动，形状原样保留）
     */
    fun nudgeCorner(
        blades: List<Blade>, cornerIndex: Int, from: Pt, to: Pt, base: Double = BASE
    ): List<Blade>? {
        if (blades.size != 4) return null
        val dx = to.x - from.x
        val dy = to.y - from.y
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return null
        var out = blades
        for (k in 0 until 2) {
            val side = sideOrder[(cornerIndex + k) % 4]
            val cur = out.firstOrNull { it.side == side } ?: return null
            val (p, n) = bladeLine(cur, base)
            val curD = p.x * n.x + p.y * n.y
            // 只取手指位移在这条边**法线方向**上的分量（和拖线段同一个语义）
            val delta = dx * n.x + dy * n.y
            val moved = translateBlade(cur, curD + delta, base)
            val nb = bladeFromEnds(side, moved.first, moved.second, base)
            out = out.map { if (it.side == side) nb else it }
        }
        return if (validateQuad(out, base)) out else null
    }

    /**
     * 退化构型下"拖角"的可用版本：基于**真实刀片**（不是四个角）把抓的那个点挪开。
     *
     * ⚠ 为什么必须由外面把刀片传进来：角退化时（两条相邻边重合/平行）根本定不出直线，
     *   从"四个角"反推刀片等于**编造一个形状** —— 实测会把用户的梯形变成全开的大方形。
     *   所以 [projectCornerDrag] 那一层**故意不做**这件事（它手里只有角）。
     *
     * 语义：两条相邻边各自沿自己的法线平移（和拖线段同一个语义），形状与倾角都保住，
     * 只把重合/贴太近的角分开。结果照旧**按量化后的状态**校验（[validateQuad]）。
     *
     * @return 校验通过才返回；否则 null = 这一帧不动（形状原样保留），拖线段仍然可用
     */
    fun nudgeCornerDrag(
        blades: List<Blade>, cornerIndex: Int, from: Pt, to: Pt, base: Double = BASE
    ): CornerDrag? = nudgeCorner(blades, cornerIndex, from, to, base)?.let { dragOf(it, to, base) }

    /**
     * 拖角的总入口：把手指位置换算成"这一帧应该变成什么样"。
     *
     * 三级降级，**每一级都只依赖 (fromCorners, cornerIndex, lastValid, target)** ——
     * 这是"幂等"的要求：同一个目标永远得到同一个结果。早期版本用"两个候选取进展更大的"，
     * 结果选哪个依赖上一帧的位置 → 同一个目标在不同起点下跳到不同候选 →
     * 表现成"同方向一直推，角每帧又挪一段"（实测每帧爬 14 个单位）。
     *
     *  1. 手指位置**先夹进虚线框**（唯一规则：虚线框就是可达范围的可视边界）——
     *     这一步同时保证了"到限位后继续拖"能沿着边界滑，而不是卡死；
     *  2. [dragCorner]：正常的角参数化（"边绕对面的角转"）；
     *  3. 沿 `lastValid → 目标` 二分找**最远**的可用点（到限位后手指改方向能沿边界滑）。
     *
     * 都用不上就返回 null = **这一帧不动**，形状原样保留。
     * 退化构型（角定不出直线）由调用方用 [nudgeCornerDrag] 处理 —— 见它的注释说明为什么
     * 这一层不能代劳。**拖线段永远可用**，所以任何状态都不会卡死。
     *
     * @param lastValid 上一次被接受的角位置（手势开始时就是按下那一刻的角）
     */
    fun projectCornerDrag(
        fromCorners: List<Pt?>, cornerIndex: Int, lastValid: Pt, target: Pt,
        base: Double = BASE, steps: Int = 24
    ): CornerDrag? {
        if (fromCorners.size != 4 || fromCorners.any { it == null }) return null
        val h = base / 2
        val t0 = Pt(target.x.coerceIn(-h, h), target.y.coerceIn(-h, h))

        // ② 正常角参数化
        dragCorner(fromCorners, cornerIndex, t0, base)?.let { return it }

        // ⚠ 退化时**不在这里**做 nudge：这一层只有"四个角"，而角已经退化得定不出直线了，
        //   从它反推刀片等于**编造一个形状**（实测会把用户的梯形变成全开的大方形）。
        //   真正的 nudge 由调用方用**真实刀片**来做，见 [nudgeCornerDrag]。

        // ③ 起点夹取后仍然不可行 → 沿 lastValid → target 二分找最远可用点
        //    （手指继续往同方向推就停在边界；改方向就能沿边界滑到新位置）
        var bestCorner: CornerDrag? = null
        var lo = 0.0
        var hi = 1.0
        repeat(steps) {
            val mid = (lo + hi) / 2
            val p = Pt(lastValid.x + (t0.x - lastValid.x) * mid,
                lastValid.y + (t0.y - lastValid.y) * mid)
            val byCorner = dragCorner(fromCorners, cornerIndex, p, base)
            if (byCorner != null) { lo = mid; bestCorner = byCorner } else hi = mid
        }
        bestCorner?.let { return it }

        // ⛔ 这里**故意不再有"整体缩放"兜底**。
        //   它会按手指半径把四片一起缩，形状被压成一个小正方形 —— 用户原话：
        //   "拖动到原来边三角形的位置会重置成一个小方形也是不对的"。
        //   兜底的职责是"别卡死"，但**不能靠毁掉用户的形状**来达成：
        //   返回 null = 这一帧不动（形状原样保留），而**拖线段永远可用** ——
        //   用户始终能靠拖线把自己挪出任何状态。宁可"这个点暂时不动"，也不动别人的形状。
        return null
    }

    /** 由"四片"组装成一次拖角结果（顺便把量化后的角回给外面）。 */
    private fun dragOf(blades: List<Blade>, corner: Pt, base: Double): CornerDrag? {
        val out = quantized(blades, base)
        if (!validateQuad(out, base)) return null
        val updates = sideOrder.map { s ->
            val b = out.first { it.side == s }
            val (p, n) = bladeLine(b, base)
            Triple(s, p.x * n.x + p.y * n.y, b.angleRad)
        }
        return CornerDrag(updates, out, corner)
    }

    /**
     * 一片刀片的那条边线：过点 [Pt]、外法线 [Pt]。
     * 保留 dot(q - 线上点, 法线) <= 0 的一侧（也就是"有光"的一侧）。
     *
     * ## 两条硬约定（都是用户明确提的）
     *
     * 1. **偏移 0 = 刀片线正好压在光斑边沿**（全开），
     *    **偏移 1（通道 255）= 刀片线正好压在对面边沿** —— 也就是**完全遮住光源**。
     *    所以线到圆心的距离从 `+半径` 走到 `−半径`：
     *    `d = (BASE/2 + 余量) × (1 − 2·inset)`。
     *
     *    ⚠ 这里改过两次，两次都是想当然：
     *      · 最早 `d = h + 余量 − inset×(BASE + 余量)`：inset=1 时 d = −50，冲过圆心，
     *        而且滑条最后一大段在"已经切死"之后空跑；
     *      · 后来把 inset=1 定成"正好过圆心"（d=0）：但那只遮住**一半**，
     *        用户要的是"拉满刚好完全遮住光源"。一个刀片要遮住整个光束圆，
     *        它必须**扫过整个孔径**（到对面边缘，d = −半径），所以行程是 2×半径。
     *
     * 2. **角度只改方向，不改距离**：法线转 θ，同时 `p = d·n`。
     *    这样无论刀片转到哪，它到圆心的距离都还是 d ——
     *    "四边全开"就永远是干净的圆，跟角度无关。
     *    ⚠ 旧写法是"法线绕自己的中点 p 转"（p 固定、n 转），距离会变成 d·cosθ：
     *      全开时偏 45° 距离只剩 49.5 < 半径 50，四条边各啃掉一丝，
     *      光斑被切成八边形。物理上对应的就是"旋转模块绕光轴转刀片、不改进给量"。
     */
    fun bladeLine(b: Blade, base: Double = BASE): Pair<Pt, Pt> {
        val h = base / 2
        val inset = b.inset
        val d = (h + sideMargin * base) * (1.0 - 2.0 * inset)
        val n0 = when (b.side) {
            Side.TOP -> Pt(0.0, -1.0)
            Side.BOTTOM -> Pt(0.0, 1.0)
            Side.LEFT -> Pt(-1.0, 0.0)
            Side.RIGHT -> Pt(1.0, 0.0)
        }
        val n = if (b.angleRad != 0.0) rotate(n0, b.angleRad) else n0
        // p = d·n ⇒ dot(p, n) = d，即"线到圆心的距离恒等于 d"
        return Pt(d * n.x, d * n.y) to n
    }

    /** Sutherland–Hodgman：用一条半平面裁多边形。 */
    fun clipHalf(poly: List<Pt>, p: Pt, n: Pt): List<Pt> {
        if (poly.isEmpty()) return poly
        fun inside(q: Pt) = (q.x - p.x) * n.x + (q.y - p.y) * n.y <= 0
        val out = ArrayList<Pt>(poly.size + 2)
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val aIn = inside(a); val bIn = inside(b)
            if (aIn) out.add(a)
            if (aIn != bIn) {
                val dx = b.x - a.x; val dy = b.y - a.y
                val den = dx * n.x + dy * n.y
                if (Math.abs(den) > 1e-12) {
                    val t = -(((a.x - p.x) * n.x + (a.y - p.y) * n.y) / den)
                    out.add(Pt(a.x + t * dx, a.y + t * dy))
                }
            }
        }
        return out
    }

    /** 去掉相邻重复点（刀片线正好压在边上时裁剪会产生重复顶点）。 */
    fun dedupe(poly: List<Pt>, eps: Double = 1e-6): List<Pt> {
        if (poly.isEmpty()) return poly
        val out = ArrayList<Pt>(poly.size)
        for (q in poly) {
            val last = out.lastOrNull()
            if (last != null && Math.abs(last.x - q.x) < eps && Math.abs(last.y - q.y) < eps) continue
            out.add(q)
        }
        while (out.size > 1) {
            val a = out.first(); val z = out.last()
            if (Math.abs(a.x - z.x) < eps && Math.abs(a.y - z.y) < eps) out.removeAt(out.size - 1) else break
        }
        return out
    }

    /** 去掉共线的"假顶点"（否则边数会被数多）。 */
    fun dropCollinear(poly: List<Pt>, eps: Double = 1e-6): List<Pt> {
        if (poly.size < 3) return poly
        val out = ArrayList<Pt>(poly.size)
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[(i - 1 + n) % n]; val b = poly[i]; val c = poly[(i + 1) % n]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (Math.abs(cross) > eps) out.add(b)
        }
        return out
    }

    /**
     * **刀片窗口**：四条刀片线围出的多边形（不含光束的圆弧）。
     *
     * 形状判定用它 —— 这样"梯形/平行四边形"说的是**刀片围出来的形状**，
     * 不受"光束是圆的、边角是弧"的影响。
     */
    fun windowPolygon(blades: List<Blade>, base: Double = BASE): List<Pt> {
        val h = base / 2
        var poly = listOf(Pt(-h, -h), Pt(h, -h), Pt(h, h), Pt(-h, h))
        for (b in blades) {
            val (p, n) = bladeLine(b, base)
            poly = clipHalf(poly, p, n)
            if (poly.size < 3) return poly
        }
        return dropCollinear(dedupe(poly))
    }

    /**
     * **实际可见的光斑** = 圆 ∩ 四条刀片半平面。
     *
     * 这才是要画出来的东西：直边来自刀片，圆弧来自光束本身。
     */
    fun visiblePolygon(blades: List<Blade>, base: Double = BASE,
                       segments: Int = BEAM_SEGMENTS): List<Pt> {
        var poly = beamPolygon(base, segments)
        for (b in blades) {
            val (p, n) = bladeLine(b, base)
            poly = clipHalf(poly, p, n)
            if (poly.size < 3) return poly
        }
        return dedupe(poly)
    }

    /** 多边形面积（shoelace；顶点顺序无所谓，取绝对值）。 */
    fun polygonArea(poly: List<Pt>): Double {
        if (poly.size < 3) return 0.0
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return Math.abs(s) / 2.0
    }

    /**
     * **光斑被遮住的比例**（0 = 全开、1 = 全遮）。用户实测标定用的就是它。
     *
     * 定义：`1 − 可见面积 / 光斑面积`，可见区 = 光斑圆 ∩ 四条刀片半平面（[visiblePolygon]）。
     *
     * 为什么要有一个数：模型对不对，光看图形看不出来（"看着像"最容易漏）。
     * 用户实测给出的硬指标是：
     *  - 一片的**单端拉满** ≈ 遮住 80%；
     *  - **两端都拉满** = 100%（完全遮住）;
     *  - 两端都 0 = 0%。
     * 这三点锁死了"一端拉满时那条边落在哪"，比任何猜测都可靠（见
     * `ShaperGeometryTest.one blade end at full covers 80 percent`）。
     */
    fun coveredFraction(blades: List<Blade>, base: Double = BASE,
                        segments: Int = BEAM_SEGMENTS): Double {
        val beam = polygonArea(beamPolygon(base, segments))
        if (beam <= 0.0) return 0.0
        val vis = polygonArea(visiblePolygon(blades, base, segments))
        return (1.0 - vis / beam).coerceIn(0.0, 1.0)
    }

    /**
     * 有几片真的在切光束（用来区分"圆形"和"被切过"）。
     *
     * 判据：**整个光束圆都还在保留侧**才算"没在切"。圆的圆心在原点、半径 r，
     * 保留侧是 dot(q - p, n) <= 0，圆上取到最大值的点给出 `-dot(p,n) + r`，
     * 于是"没在切" ⟺ `dot(p, n) >= r`。
     *
     * ⚠ 不能用 `|dot(p,n)| < r` 那种写法：刀片**越过对面**时 dot 很负（比如 -74），
     *   它其实是把整个孔隙都挡住了，显然"在切"，但取绝对值会把它误判成"全开"，
     *   于是界面显示"圆形（四边全开）"—— 而灯是全黑的。
     */
    fun cuttingCount(blades: List<Blade>, base: Double = BASE): Int {
        val r = base / 2
        return blades.count { b ->
            val (p, n) = bladeLine(b, base)
            (p.x * n.x + p.y * n.y) < r - 1e-9
        }
    }

    // ---------------- 形状判定 ----------------

    /** 刀片窗口的形状名。 */
    fun shapeName(poly: List<Pt>): String {
        if (poly.size < 3) return "已完全切死（无光）"
        if (poly.size == 3) return "三角形"
        if (poly.size == 4) {
            val d = ArrayList<Pt>(4); val len = ArrayList<Double>(4)
            for (i in 0 until 4) {
                val a = poly[i]; val b = poly[(i + 1) % 4]
                val v = Pt(b.x - a.x, b.y - a.y)
                d.add(v); len.add(Math.sqrt(v.x * v.x + v.y * v.y))
            }
            fun par(u: Pt, v: Pt): Boolean {
                val cross = Math.abs(u.x * v.y - u.y * v.x)
                val scale = Math.sqrt(u.x * u.x + u.y * u.y) * Math.sqrt(v.x * v.x + v.y * v.y)
                return cross < 0.02 * scale
            }
            val p02 = par(d[0], d[2]); val p13 = par(d[1], d[3])
            val dot = d[0].x * d[1].x + d[0].y * d[1].y
            val right = Math.abs(dot) < 0.02 * len[0] * len[1]
            if (p02 && p13) {
                if (!right) return "平行四边形"
                return if (Math.abs(len[0] - len[1]) < 0.5) "正方形" else "矩形（长方形）"
            }
            if (p02 || p13) return "梯形"
            return "四边形（无平行边）"
        }
        if (poly.size == 5) return "五边形（切掉一个角）"
        return "${poly.size} 边形"
    }

    /**
     * 面板上显示的一行结论：形状 + 有几片在切。
     *
     * 分成两段是因为可见光斑是"直边 + 圆弧"的混合形状，一个词概括不了 ——
     * 比如"矩形 · 1 片在切"意思是刀片窗口是矩形，但只切了一片，
     * 实际光斑是一边直、三边弧。
     */
    fun shapeLabel(blades: List<Blade>, base: Double = BASE): String {
        val cut = cuttingCount(blades, base)
        if (cut == 0) return "圆形（四边全开）"
        return "${shapeName(windowPolygon(blades, base))}　·　$cut 片在切"
    }

    // ---------------- 默认映射 ----------------

    /** 默认：片1→上、片2→下、片3→左、片4→右。 */
    val defaultSides = listOf(Side.TOP, Side.BOTTOM, Side.LEFT, Side.RIGHT)

    /**
     * 按"每片偏移/角度已归一化"的状态构造 4 片。
     *
     * ⚠ 这是**表示层**的构造器（直接给 [Blade] 的 inset/angleRad），**不是**通道层：
     *   灯里的通道对是"同一片的两个端点"，要用 [bladeFromEnds] / [bladeEndsOf] 解释。
     *   两者混用会算出完全不同的深度 —— 这正是"同一份代码里两套模型并存"的老毛病。
     */
    fun bladesOf(insets: List<Double>, angles: List<Double>,
                 sides: List<Side> = defaultSides,
                 inverts: List<Boolean> = listOf(false, false, false, false)): List<Blade> =
        (0 until 4).map { i ->
            val inv = inverts.getOrElse(i) { false }
            val raw = insets.getOrElse(i) { 0.0 }.coerceIn(0.0, 1.0)
            Blade(sides.getOrElse(i) { defaultSides[i] },
                  if (inv) 1.0 - raw else raw,
                  angles.getOrElse(i) { 0.0 })
        }

    // ---------------- 逐片自检的值序列 ----------------

    /**
     * 逐片自检每一步要写的「通道 → 值」。
     *
     * 抽出来是因为这段有一个**只能靠测试发现的坑**：自检要一片一片地压，
     * 每一步必须**先把四片全部复位**再压当前这一片 —— 少了复位的话，
     * 上一步压进去的偏移留在那儿，四步下来窗口越来越小，越看越像"灯坏了"。
     *
     * ⚠ 每步只压 [amount]（默认 35%），不压到底：偏移 1 是"扫到对面边缘、完全遮住光源"，
     *   压太多灯上就几乎全黑了，反而看不出是哪条边在动。
     *   行程是 2×半径，35% 对应"刀片进到半径的 30% 处"，切得清楚又还看得见。
     *
     * ⚠ A/B 双端模型下，"压一片"= **这片刀片的两个端点一起进出**（整条边平移），
     *   而不是只动其中一个通道。只动 A 端会让刀片**歪掉**，
     *   自检时看到的就不是"某条边在动"而是"某个角在扭"，认不出是哪一片。
     *
     * @param blades 8 个切割通道的**灯内通道号**（0 = 不存在）
     * @param rotCh  切割旋转通道号（0 = 没有）；收尾步骤会把它归到中位
     * @param inverts 每片是否反向（"全开"的值随之是 255 还是 0）
     * @return 5 步：4 步逐片 + 1 步四边全开
     */
    fun selfTestSteps(
        blades: IntArray,
        rotCh: Int = 0,
        inverts: List<Boolean> = listOf(false, false, false, false),
        // ⚠ 档位跟着**深度标定**走：0.35 会把整条光束压掉 8 成（老行程模型下实测 0.82），
        //   自检当场把灯打黑；0.1 约遮 9%（见 [SELF_TEST_AMOUNT]）。
        //   0.06 约遮 2 成 —— 看得见哪条边在动，又不会黑灯。
        amount: Double = SELF_TEST_AMOUNT,
    ): List<Map<Int, Int>> {
        fun inv(i: Int) = inverts.getOrElse(i) { false }
        fun openVal(i: Int) = if (inv(i)) 255 else 0
        /** 归一化压入深度 → 该通道的值（反向片要翻过来）。 */
        fun insetVal(i: Int, raw: Double): Int {
            val r = if (inv(i)) 1.0 - raw else raw
            val v = Math.round(r.coerceIn(0.0, 1.0) * 255).toInt()
            return if (v < 0) 0 else if (v > 255) 255 else v
        }
        /** 把四片复位成"四边全开"（两端都回到边沿）。 */
        fun reset(): LinkedHashMap<Int, Int> {
            val m = LinkedHashMap<Int, Int>()
            for (i in 0 until 4) {
                val a = blades.getOrElse(2 * i) { 0 }
                val b = blades.getOrElse(2 * i + 1) { 0 }
                if (a > 0) m[a] = openVal(i)
                if (b > 0) m[b] = openVal(i)
            }
            return m
        }

        val out = ArrayList<Map<Int, Int>>(5)
        for (step in 0 until 4) {
            val m = reset()
            // 两端一起压 = 整条边平移（只压一端会歪，认不出是哪一片）
            val a = blades.getOrElse(2 * step) { 0 }
            val b = blades.getOrElse(2 * step + 1) { 0 }
            if (a > 0) m[a] = insetVal(step, amount)
            if (b > 0) m[b] = insetVal(step, amount)
            out.add(m)
        }
        val fin = reset()
        if (rotCh > 0) fin[rotCh] = 128      // 旋转回中 = 0°，不是 0
        out.add(fin)
        return out
    }
}
