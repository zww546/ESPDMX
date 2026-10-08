package com.example.stagedmx

import android.content.Context
import android.content.SharedPreferences

/**
 * 切割面板的**用户偏好**持久化。
 *
 * 存两类东西：
 *  1. **全局**：切割 UI 用哪种模式（8 条推杆 / 面板滑块 / 自绘窗口）。
 *     这是"人"的偏好，不是灯的属性，所以全局一份。
 *  2. **每灯型**：四片各自映射到窗口哪条边、要不要反向、角度半量程多少。
 *     这是"灯"的属性（不同灯的通道含义、安装方向都不一样），所以按 [FixtureDef.id] 存。
 *
 * ⚠ 通道号**不存这里**：哪 8 个通道是切割片由 `FxEngine.bladeCh` 从灯库现算
 *   （它同时认 MA2 的 BLADE1A、老虎 D4 的 BLADE1..8、珍珠的 BLADE1..8 三种命名），
 *   再存一份就会两边漂移。
 *
 * 存储格式：每灯型一条字符串 `"T,B,L,R|0,1,0,0|45"`（边序 | 反向位 | 角度半量程）。
 * 故意用紧凑字符串而不是 JSON：字段少、以后加字段也好加默认值。
 *
 * ⚠ 本文件的中文注释只能用支持 UTF-8 的编辑工具改。用 PowerShell 的
 *   `Get-Content -Raw` + `Set-Content -Encoding utf8` 处理会**双重编码**中文
 *   （读成 ANSI、再按 UTF-8 写），把注释变成乱码、还会吃掉换行。
 */
class ShaperStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shaper", Context.MODE_PRIVATE)

    /** 切割 UI 模式：0 = 8 条推杆（现状）、1 = 面板滑块（方案 A）、2 = 自绘窗口（方案 B）。 */
    var uiMode: Int
        get() = prefs.getInt("ui_mode", MODE_FADERS).coerceIn(MODE_FADERS, MODE_CANVAS)
        set(v) { prefs.edit().putInt("ui_mode", v.coerceIn(MODE_FADERS, MODE_CANVAS)).apply() }

    /** 四片的边映射（默认 上/下/左/右）。解析与容错都在 [decode] 里。 */
    fun sides(fixtureId: String?): List<ShaperGeometry.Side> = decode(entry(fixtureId)).first

    fun setSide(fixtureId: String?, index: Int, side: ShaperGeometry.Side) {
        val s = sides(fixtureId).toMutableList()
        if (index !in s.indices) return
        s[index] = side
        write(fixtureId, s, inverted(fixtureId), maxAngleDeg(fixtureId))
    }

    /** 四片各自是否反向（0 和 255 哪个是"收进来"）。 */
    fun inverted(fixtureId: String?): List<Boolean> = decode(entry(fixtureId)).second

    fun setInverted(fixtureId: String?, index: Int, inv: Boolean) {
        val v = inverted(fixtureId).toMutableList()
        if (index !in v.indices) return
        v[index] = inv
        write(fixtureId, sides(fixtureId), v, maxAngleDeg(fixtureId))
    }

    /**
     * **切割旋转**通道的半量程（度）：旋转通道 0..255 对应 −max..+max，128 = 0°。
     *
     * 默认 45（即 ±45°），设置页给 45 / 90 / 180 三档。
     *
     * ⚠ 只作用于**旋转**（灯库里的 SHAPER ROT）。切割片本身的倾斜不在这里设 ——
     *   每片的两个通道是这片刀片的 **A/B 两个端点**：两端一起进出 = 平移，
     *   两端不等 = 倾斜，最大倾斜由几何决定（`atan(0.5) ≈ 26.6°`，见
     *   [ShaperGeometry.bladeFromEnds]）。
     *
     * ⚠ 灯库写了旋转通道的物理量程时**不用这个值** —— 例如 Ares-FP2600 的 SHAPER ROT
     *   通道 phys 就是 −45..45，直接采信灯库（见 `MainActivity.libraryMaxAngleDeg`），
     *   设置页那一行会显示成只读。
     */
    fun maxAngleDeg(fixtureId: String?): Int = decode(entry(fixtureId)).third

    fun setMaxAngleDeg(fixtureId: String?, deg: Int) {
        write(fixtureId, sides(fixtureId), inverted(fixtureId),
            deg.coerceIn(MIN_ANGLE_DEG, MAX_ANGLE_DEG))
    }

    /** 角度半量程（弧度）—— 给 [ShaperGeometry.angleFromChannel] 用。 */
    fun halfAngleRad(fixtureId: String?): Double =
        Math.toRadians(maxAngleDeg(fixtureId).toDouble())

    /** 该灯型是否已被用户改过映射（面板上用来决定要不要提示"先做一次自检"）。 */
    fun hasCustomMapping(fixtureId: String?): Boolean = entry(fixtureId) != null

    // ---------------- 内部 ----------------

    private fun key(id: String?) = "map_${id ?: "default"}"

    private fun entry(fixtureId: String?): String? = prefs.getString(key(fixtureId), null)

    private fun write(fixtureId: String?, sides: List<ShaperGeometry.Side>,
                      inv: List<Boolean>, maxAngleDeg: Int) {
        prefs.edit().putString(key(fixtureId), encode(sides, inv, maxAngleDeg)).apply()
    }

    companion object {
        const val MODE_FADERS = 0
        const val MODE_PANEL = 1
        const val MODE_CANVAS = 2

        /** 角度半量程的默认值与可选档位（设置页的三颗按钮）。 */
        const val DEFAULT_MAX_ANGLE_DEG = 45
        val MAX_ANGLE_CHOICES = listOf(45, 90, 180)
        const val MIN_ANGLE_DEG = 5
        const val MAX_ANGLE_DEG = 180

        /** 模式名（设置页与提示文案共用）。 */
        fun modeName(mode: Int): String = when (mode) {
            MODE_FADERS -> "8 条推杆"
            MODE_PANEL -> "切割面板（滑块）"
            MODE_CANVAS -> "切割窗口（可拖动）"
            else -> "未知"
        }

        /**
         * 编码成一条紧凑字符串：`边序 | 反向位 | 角度半量程`，
         * 例如 `TOP,BOTTOM,LEFT,RIGHT|0,0,0,0|45`（边名用枚举全名，便于人读和排查）。
         *
         * 用字符串而不是 JSON：字段少、以后加字段也好加默认值。
         *
         * ⚠ 第 3 个字段的语义变过一次：旧版本存的是**全量程**（默认 90 = ±45°），
         *   现在存的是**半量程**（默认 45 = ±45°）。老存档里若被手改过 90，
         *   现在会读成 ±90° —— 只能靠用户重设一次；默认存档（没有这一条）不受影响。
         */
        fun encode(sides: List<ShaperGeometry.Side>, inv: List<Boolean>, maxAngleDeg: Int): String =
            sides.joinToString(",") { it.name } + "|" +
                inv.joinToString(",") { if (it) "1" else "0" } + "|" + maxAngleDeg

        /**
         * 解码。**任何坏数据都退回默认值，绝不抛异常** ——
         * 这是存在 SharedPreferences 里的用户数据，可能被手改、被旧版本写坏、
         * 或者只写了一半；面板打不开比"映射被重置"严重得多。
         *
         * 容忍：null / 空串 / 段数不足 / 边名不认识 / 位数不足 / 角度越界或非数字。
         */
        fun decode(raw: String?): Triple<List<ShaperGeometry.Side>, List<Boolean>, Int> {
            val defaults = ShaperGeometry.defaultSides
            if (raw.isNullOrBlank())
                return Triple(defaults, List(4) { false }, DEFAULT_MAX_ANGLE_DEG)

            val parts = raw.split('|')
            val sideNames = parts.getOrNull(0)?.split(',') ?: emptyList()
            val bits = parts.getOrNull(1)?.split(',') ?: emptyList()

            val sides = (0 until 4).map { i ->
                ShaperGeometry.Side.values()
                    .firstOrNull { it.name == sideNames.getOrNull(i)?.trim() }
                    ?: defaults[i]
            }
            // 只认字面 "1"。顺手 trim：手改存档很容易多打一个空格，
            // 为此把"反向"整个丢掉不值得；而认 "true"/"yes" 就属于猜了，不猜。
            val inv = (0 until 4).map { bits.getOrNull(it)?.trim() == "1" }
            val deg = parts.getOrNull(2)?.trim()?.toIntOrNull()
                ?.coerceIn(MIN_ANGLE_DEG, MAX_ANGLE_DEG)
                ?: DEFAULT_MAX_ANGLE_DEG
            return Triple(sides, inv, deg)
        }
    }
}
