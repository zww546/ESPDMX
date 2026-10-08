package com.example.stagedmx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 切割窗口视图 —— 本工程第一块自绘控件。
 *
 * 它同时承担两种模式的"画"这一半：
 *  - 面板模式：只画（[interactive] = false），输入靠面板里的滑块；
 *  - 窗口模式：再额外接受触摸（[interactive] = true）。
 *
 * 两种模式的几何、配色完全一致 —— 都走 [ShaperGeometry]，
 * 差别只在"能不能拖"，这样切换模式不会看到两套不一样的画面。
 *
 * ## 画的是"连起来的四边形"，不是四条散线
 * 四条刀片线两两相交出 4 个角，把它们依次连起来就是实际的光斑窗口。
 * 只画线、不按交点收尾的话，线会伸到方框外面拖出一片蜘蛛网，
 * 而且**看着和实际切出来的窗口对不上**（用户原话："示意线条和 UI 位置对应不上"）。
 * 所以这里画的是 [ShaperGeometry.corners] 算出来的那个闭合四边形，
 * 每个角上再加一个小圆点当拖拽把手。
 *
 * ## 两种拖法
 *  - **拖角**（[onCornerDrag]）：角可以在画面里**随意移动**。角动了两条相邻边要跟着走，
 *    规则是"这条边仍然过它另一头的角" —— 也就是边绕对面的角转，四条边始终连在一起。
 *  - **拖线段**（[onEdgeDrag]）：线段**只能沿自己的法线方向**进/出（两个方向），
 *    不许左右平移、不许转。
 *
 * ⚠ 旋转**不在这里拖**：用户要求"窗口模式…只能通过拖动条旋转"，
 *   所以外圈只当指示环画，不参与命中。
 *
 * ## 触摸与滚动的冲突（放进 RecyclerView 时必须处理）
 * 一旦手指抓住了角或线段，就调 `parent.requestDisallowInterceptTouchEvent(true)`，
 * 让父容器（推子页的 RecyclerView）在这一轮手势里不要抢去滚列表；抬手时再放开。
 * 不处理的话表现是"拖到一半列表开始滚，图形乱跳"。
 */
class ShaperWindowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 四条刀片（顺序固定：0..3）。由外部设置。 */
    var blades: List<ShaperGeometry.Blade> = ShaperGeometry.bladesOf(
        listOf(0.0, 0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, 0.0)
    )
        set(v) { field = v; invalidate() }

    /** 整体旋转（弧度）。只由旋转滑条驱动。 */
    var rotationRad: Double = 0.0
        set(v) { field = v; invalidate() }

    /** 是否接受拖动（窗口模式才开）。 */
    var interactive: Boolean = false

    /** 拖线段：该片所属的边 + 新的 inset(0..1)。只能沿法线进出两个方向。 */
    var onEdgeDrag: ((side: ShaperGeometry.Side, inset: Double) -> Unit)? = null

    /**
     * 拖角：角的下标（见 [ShaperGeometry.sideOrder] 的相邻顺序）
     * + **手势按下时快照的四个角** + 该角在未旋转坐标里的新位置。
     *
     * ⚠ 快照必须由这里给，不能让外面每帧从刀片现算：通道只有 0..255，
     *   每帧重算会把量化误差当成新基准，旁边的角就会一直飘。
     */
    var onCornerDrag: ((cornerIndex: Int, fromCorners: List<ShaperGeometry.Pt>,
                        newPoint: ShaperGeometry.Pt) -> Unit)? = null

    /** 手势结束（抬手/取消）回调。 */
    var onDragEnd: (() -> Unit)? = null

    // ---- 画笔（构造一次，onDraw 里不 new）----
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(3.5f); strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f)
        color = Color.parseColor("#22FFFFFF")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(5f)), 0f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = Path()
    private val quadPath = Path()

    private val accent = Color.parseColor("#7B8CFF")
    private val lineNormal = Color.parseColor("#CCFFFFFF")
    private val ringIdle = Color.parseColor("#1EFFFFFF")
    private val beamBg = Color.parseColor("#12121C")
    private val handleIdle = Color.parseColor("#7B8CFF")
    private val handleHot = Color.parseColor("#FFFFFF")

    private var dragCorner = -1
    private var dragSide: ShaperGeometry.Side? = null

    /** 手势按下那一刻的四个角；整个拖动过程都以它为基准（见 [onCornerDrag]）。 */
    private var dragFromCorners: List<ShaperGeometry.Pt>? = null

    /**
     * 上一次被接受的角位置。到限位之后靠它把手指位置**投影到边界上**，
     * 这样角会沿限位继续跟着滑，而不是一碰限位就卡死。
     */
    private var dragLastCorner: ShaperGeometry.Pt? = null

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 画布 → 几何 的映射。**onDraw 和触摸必须共用同一份**，否则"看到的图形"
     * 和"点得中的地方"会错位。
     */
    private class Metrics(
        val cx: Float, val cy: Float, val scale: Float,
        /** 光束圆半径（屏幕像素） */
        val r: Float,
        /** 最外圈旋转环半径 */
        val ring: Float,
    )

    private fun metrics(): Metrics {
        // ⚠ 按宽高的**较小者**算半径。预览行是横向铺满的（宽 ≈ 360dp、高 260dp），
        //   按宽度算的话直径会超出画布高度，圆和旋转环上下都被切掉。
        val w = width.toFloat()
        val h = height.toFloat()
        val side = Math.min(w, h)
        val scale = (side * 0.78f) / ShaperGeometry.BASE.toFloat()
        val r = ShaperGeometry.BASE.toFloat() / 2f * scale
        return Metrics(w / 2f, h / 2f, scale, r, side * 0.465f)
    }

    /** 归一化坐标（未旋转）→ 屏幕坐标。 */
    private fun toScreen(p: ShaperGeometry.Pt, m: Metrics): Pair<Float, Float> {
        val a = rotationRad
        val c = Math.cos(a); val s = Math.sin(a)
        val x = p.x * c - p.y * s
        val y = p.x * s + p.y * c
        return (m.cx + (x * m.scale).toFloat()) to (m.cy + (y * m.scale).toFloat())
    }

    /** 屏幕坐标 → 归一化坐标（去掉整体旋转）。 */
    private fun toLocal(px: Float, py: Float, m: Metrics): ShaperGeometry.Pt {
        val a = -rotationRad
        val dx = (px - m.cx).toDouble() / m.scale
        val dy = (py - m.cy).toDouble() / m.scale
        return ShaperGeometry.Pt(dx * Math.cos(a) - dy * Math.sin(a),
            dx * Math.sin(a) + dy * Math.cos(a))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val m = metrics()
        val r = m.r

        // 1) 光束底（暗圆）
        beamPaint.color = beamBg
        canvas.drawCircle(m.cx, m.cy, r, beamPaint)

        // 2) 可见光斑 = 圆 ∩ 四条刀片线（直边来自刀片、圆弧来自光束）
        val vis = ShaperGeometry.visiblePolygon(blades)
        canvas.save()
        canvas.translate(m.cx, m.cy)
        canvas.rotate((rotationRad * 180 / Math.PI).toFloat())
        if (vis.size >= 3) {
            clipPath.reset()
            clipPath.moveTo((vis[0].x * m.scale).toFloat(), (vis[0].y * m.scale).toFloat())
            for (i in 1 until vis.size) {
                clipPath.lineTo((vis[i].x * m.scale).toFloat(), (vis[i].y * m.scale).toFloat())
            }
            clipPath.close()
            canvas.save()
            canvas.clipPath(clipPath)
            lightPaint.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(Color.WHITE, Color.parseColor("#E8ECFF"), Color.parseColor("#9AA4FF")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(0f, 0f, r, lightPaint)
            canvas.restore()
        }

        // 3) 基准框（虚线，参考用）
        val half = r
        canvas.drawRect(-half, -half, half, half, guidePaint)

        // 4) 刀片：优先画"连起来的四边形"；某两片同向（平行）画不出闭合四边形时
        //    退回"画整条线"，至少还能看出是哪一片。
        val cs = ShaperGeometry.corners(blades)
        if (cs.all { it != null }) {
            quadPath.reset()
            cs.forEachIndexed { i, p ->
                val (sx, sy) = toScreenLocal(p!!, m)
                if (i == 0) quadPath.moveTo(sx, sy) else quadPath.lineTo(sx, sy)
            }
            quadPath.close()
            linePaint.color = lineNormal
            linePaint.strokeWidth = dp(3.5f)
            canvas.drawPath(quadPath, linePaint)
        } else {
            // 某两片近乎平行、交点算不出来时的退路。
            // ⚠ **不能**按固定长度画整条线 —— 那看起来就是"切片的直线被加长了"（用户报的
            //   现象）。改成把这条线**裁到虚线框内**，长度就稳定了。
            blades.forEach { b ->
                val (p, n) = ShaperGeometry.bladeLine(b)
                val seg = ShaperGeometry.clipLineToSquare(p, n) ?: return@forEach
                val (x1, y1) = toScreenLocal(seg.first, m)
                val (x2, y2) = toScreenLocal(seg.second, m)
                linePaint.color = lineNormal
                linePaint.strokeWidth = dp(3.5f)
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }
        }

        // 5) 角上的拖拽把手（只有可交互时才画，免得看着像能拖却拖不动）
        if (interactive) {
            cs.forEachIndexed { i, p ->
                if (p == null) return@forEachIndexed
                val (sx, sy) = toScreenLocal(p, m)
                handlePaint.color = if (i == dragCorner) handleHot else handleIdle
                canvas.drawCircle(sx, sy, dp(if (i == dragCorner) 11f else 8f), handlePaint)
            }
        }
        canvas.restore()

        // 6) 最外圈旋转指示环（**不参与命中**：旋转只能用滑条）
        ringPaint.color = ringIdle
        canvas.drawCircle(m.cx, m.cy, m.ring, ringPaint)
    }

    /** 归一化坐标 → 已经 translate 到圆心的画布坐标（不再重复旋转）。 */
    private fun toScreenLocal(p: ShaperGeometry.Pt, m: Metrics): Pair<Float, Float> =
        (p.x * m.scale).toFloat() to (p.y * m.scale).toFloat()

    // ---------------- 触摸（只有 interactive 时生效）----------------

    private data class Hit(val corner: Int, val side: ShaperGeometry.Side?)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return super.onTouchEvent(event)
        if (width <= 0 || height <= 0) return super.onTouchEvent(event)
        val m = metrics()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = pick(event.x, event.y, m)
                if (hit == null) return false
                dragCorner = hit.corner
                dragSide = hit.side
                // ⚠ **四个角算不出来时不能让整轮手势作废**。
                //   退化状态（某一对相邻边平行/共线 → corners() 里出现 null，"拖成三角形"就是它）
                //   以前会在这里直接 `return false`，于是这一下交给 RecyclerView 去滚列表 ——
                //   表现成"**所有拖动都无效**"；更糟的是这个状态**自己永远走不出来**：
                //   拖不动就没法改通道，改不了通道就还是退化。
                //   现在退化成"只能拖线段"：线段拖动不依赖角（保持当前倾角只改距离），
                //   正好能把刀片拖出退化状态。
                val cs = ShaperGeometry.corners(blades)
                if (hit.corner >= 0 && cs.all { it != null }) {
                    dragFromCorners = cs.map { it!! }      // 按下时快照，拖动全程以它为准
                    dragLastCorner = dragFromCorners?.getOrNull(hit.corner)
                } else {
                    // 角不可用（或没抓到角）→ 改抓离按点最近的那条边
                    dragCorner = -1
                    dragFromCorners = null
                    dragLastCorner = null
                    if (dragSide == null) dragSide = nearestSide(event.x, event.y, m)
                    if (dragSide == null) return false
                }
                // 抓住之后，这一轮手势不许父容器（RecyclerView）拿去滚列表
                parent?.requestDisallowInterceptTouchEvent(true)
                applyDrag(event.x, event.y, m)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCorner < 0 && dragSide == null) return false
                applyDrag(event.x, event.y, m)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragCorner = -1; dragSide = null; dragFromCorners = null; dragLastCorner = null
                invalidate()
                // 抬手：外面会把画面从"理想几何"交回引擎里的真实值（差 ≤1 个通道步长）
                onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 离按点最近的那条边（角不可用时用来退化成"拖线段"）。 */
    private fun nearestSide(px: Float, py: Float, m: Metrics): ShaperGeometry.Side? {
        val local = toLocal(px, py, m)
        var best: ShaperGeometry.Side? = null
        var bestD = Double.MAX_VALUE
        blades.forEach { b ->
            val (p, n) = ShaperGeometry.bladeLine(b)
            val d = Math.abs((local.x - p.x) * n.x + (local.y - p.y) * n.y)
            if (d < bestD) { bestD = d; best = b.side }
        }
        return best
    }

    /**
     * 命中测试：**角优先于线段**，最后兜底"按在四边形内部"。
     *
     * ⚠ 角必须判在线段之前：角就压在两条线段的端点上，先判线段的话角永远选不中，
     *   "拖角随意移动"这个交互就等于不存在。
     *
     * ⚠ 最后一层兜底（按在四边形内部 → 抓最近的那个角）是修"一会拖不动一会可以"的：
     *   以前按歪一点就 `return null`，于是这一下**交给 RecyclerView 去滚列表**了 ——
     *   用户感觉是"拖不动"，其实是手指根本没抓住任何东西，而且列表还在底下滚，
     *   预览跟着移动，下一次按的位置就更没准。现在只要按在图形**内部**就一定能抓住角；
     *   按在图形外面仍然让列表滚（否则预览那一大块会把列表滚动全挡住）。
     */
    private fun pick(px: Float, py: Float, m: Metrics): Hit? {
        val cs = ShaperGeometry.corners(blades)
        // 角：屏幕距离在 22dp 之内
        val cornerTol = dp(22f)
        var bestC = -1
        var bestCd = Float.MAX_VALUE
        cs.forEachIndexed { i, p ->
            if (p == null) return@forEachIndexed
            val (sx, sy) = toScreen(p, m)
            val d = Math.hypot((px - sx).toDouble(), (py - sy).toDouble()).toFloat()
            if (d < bestCd) { bestCd = d; bestC = i }
        }
        if (bestC >= 0 && bestCd < cornerTol) return Hit(bestC, null)

        // 线段：点到线的距离在 18dp 之内
        val local = toLocal(px, py, m)
        var bestSide: ShaperGeometry.Side? = null
        var bestD = dp(18f).toDouble() / m.scale      // 容差换算到归一化坐标
        blades.forEach { b ->
            val (p, n) = ShaperGeometry.bladeLine(b)
            val d = Math.abs((local.x - p.x) * n.x + (local.y - p.y) * n.y)
            if (d < bestD) { bestD = d; bestSide = b.side }
        }
        if (bestSide != null) return Hit(-1, bestSide)

        // 兜底：按在四边形内部就抓最近的那个角（哪怕离得远）
        val quad = cs.filterNotNull()
        if (bestC >= 0 && quad.size == 4 && ShaperGeometry.insidePolygon(quad, local)) {
            return Hit(bestC, null)
        }
        return null
    }

    private fun applyDrag(px: Float, py: Float, m: Metrics) {
        val local = toLocal(px, py, m)
        if (dragCorner >= 0) {
            val from = dragFromCorners ?: return
            val last = dragLastCorner ?: return
            // ⚠ 手指位置**不做虚线框夹取**：角本来就可能落在框外（刀片越过圆心 + 大倾角时，
            //   相邻两边交点能到 (−25, −60.4)）。夹了之后角永远到不了它真实的位置，
            //   表现为"某些位置完全拖不动"。真正的限位在 dragCorner 里
            //   （每条边到圆心的距离 ∈[−半径, 半径]），那才是"不许超过虚线"的正确对象。
            val proj = ShaperGeometry.projectCornerDrag(
                from, dragCorner, last, local) ?: return
            dragLastCorner = proj.corner
            onCornerDrag?.invoke(dragCorner, from, proj.corner)
            invalidate()
            return
        }
        val side = dragSide ?: return
        val b = blades.firstOrNull { it.side == side } ?: return
        val (_, n) = ShaperGeometry.bladeLine(b)
        // ⚠ 只取**沿法线**的分量：线段只能进出两个方向，手指横向的位移一律丢掉。
        //   新线的距离 = 手指点在法线上的投影（线段保持平行，所以这一步就够了）。
        //   传出去的是**距离**（不是"等效插入量"）：外面要靠它 + 当前倾角反算两端深度，
        //   这样拖线段不会把角度清零。
        onEdgeDrag?.invoke(side, local.x * n.x + local.y * n.y)
        invalidate()
    }
}
