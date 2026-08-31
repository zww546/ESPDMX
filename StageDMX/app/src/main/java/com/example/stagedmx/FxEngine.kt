package com.example.stagedmx

/**
 * App 端效果引擎 — 板载模式 v3（多实例并行）。
 *
 * 效果下发到 ESP32 板载效果引擎（协议 0x20-0x22），由板子实时生成波形。
 * **多实例并行**：每个实例分配固定槽位（slot 0..7，按实例在列表中的顺序），
 * 各实例效果独立启停/参数，切换实例不停止其他实例的效果。
 *
 * 幅度用 0-255 通用数值，内部映射 16bit 偏移；支持 fine 通道。
 */
object FxEngine {

    data class FxDef(val id: Int, val name: String, val params: List<String>)

    val presets = listOf(
        FxDef(1, "圆形摇动", listOf("幅度", "速度")),
        FxDef(2, "水平摇动", listOf("幅度", "速度")),
        FxDef(3, "垂直摇动", listOf("幅度", "速度")),
        FxDef(4, "频闪",      listOf("速度")),
        FxDef(5, "RGB变色",   listOf("速度")),
        FxDef(6, "放大摆动",  listOf("幅度", "速度")),
        FxDef(7, "调焦摆动",  listOf("幅度", "速度")),
        FxDef(8, "色盘摆动",  listOf("幅度", "速度")),
        FxDef(9, "图案盘摆动", listOf("幅度", "速度")),
        FxDef(10, "图案盘自转", listOf("幅度", "速度")),
        FxDef(11, "固定图案摇动", listOf("幅度", "速度")),
    )

    // 速度（0..65535，越大越快）：8.8 定点，等效周期(ms) = 655360 / speed。
    // 33 ≈ 19.9s/圈，3277 ≈ 200ms/圈。
    const val SPEED_MIN = 33
    const val SPEED_MAX = 3277
    const val SPEED_DEFAULT = 512   // ≈1.28s/圈

    /** 板载效果槽总数（固件 FX_MAX_COUNT=8，跟随实例数量）。 */
    const val SLOT_COUNT = 8

    /** 每实例效果状态。 */
    data class FxState(
        val fxId: Int,
        val amplitude: Int,
        val speed: Int,
        val slot: Int,
        val ptSpeedCh: Int? = null,   // 该实例的 PT Speed 通道（真实地址），停止时恢复
        val ptSpeedReal: Int = 0      // PT Speed 真实通道号
    )

    // ---- 由 MainActivity 在应用灯具后更新（当前实例配置）----
    var panCh = 28; var panFineCh = 0; var tiltCh = 30; var tiltFineCh = 0
    var dimCh = 1; var dimFineCh = 0
    var rCh = 4; var gCh = 5; var bCh = 6
    var zoomCh = 0; var zoomFineCh = 0     // 放大
    var focusCh = 0; var focusFineCh = 0   // 调焦
    var colorCh = 0                        // 色盘
    var goboCh = 0                         // 图案盘
    var goboRotCh = 0                      // 图案盘旋转
    var ptSpeedCh: Int? = null       // PT Speed 通道号
    var startAddr = 1                // 当前实例 DMX 起始地址（效果通道偏移基准）

    private var engine: DmxEngine? = null
    // 按实例 id 记录效果状态（key = 实例 id；"global" = 无实例）
    private val states = mutableMapOf<String, FxState>()

    private fun key(instanceId: String?): String = instanceId ?: "global"

    fun isActive(instanceId: String? = null): Boolean =
        states[key(instanceId)]?.fxId != null

    fun activeFxId(instanceId: String? = null): Int =
        states[key(instanceId)]?.fxId ?: 0

    fun getAmplitude(instanceId: String? = null): Int =
        states[key(instanceId)]?.amplitude ?: 128

    fun getSpeed(instanceId: String? = null): Int =
        states[key(instanceId)]?.speed ?: SPEED_DEFAULT

    fun getSlot(instanceId: String? = null): Int =
        states[key(instanceId)]?.slot ?: 0

    /** 计算实例应占用的槽位（按实例在列表中的顺序，0..7 循环）。 */
    fun slotFor(instanceId: String?): Int {
        if (instanceId == null) return 0
        // MainActivity 通过 setSlotAllocator 提供顺序
        val idx = slotAllocator?.invoke(instanceId) ?: 0
        return (idx % SLOT_COUNT).coerceIn(0, SLOT_COUNT - 1)
    }

    /** 由 MainActivity 设置：instanceId -> 列表序号。 */
    var slotAllocator: ((String) -> Int)? = null

    /**
     * 应用灯具配置（当前实例）。可在效果运行中调用。
     * @param startAddress 实例 DMX 起始地址
     */
    fun applyFixture(def: FixtureDef, startAddress: Int = 1) {
        startAddr = startAddress.coerceIn(1, 512)
        // 优先按 attribute（MA2 标准，如 COLOR1/GOBO1/PAN）精确匹配，其次按通道名模糊匹配
        fun byAttr(key: String): Pair<Int, Int?>? {
            val k = key.lowercase()
            val ch = def.channels.find { it.attribute.lowercase() == k }
                ?: def.channels.find { it.attribute.lowercase().contains(k) }
            return ch?.let { it.number to (if (it.hasFine) it.fineNumber else null) }
        }
        fun chFine(vararg keys: String): Pair<Int, Int?>? {
            for (k in keys) {
                byAttr(k)?.let { return it }
                def.findChFine(k)?.let { return it }
            }
            return null
        }
        chFine("pan")?.let { (c, f) -> panCh = c; panFineCh = f ?: 0 }
        chFine("tilt")?.let { (c, f) -> tiltCh = c; tiltFineCh = f ?: 0 }
        chFine("dim")?.let { (c, f) -> dimCh = c; dimFineCh = f ?: 0 }
        chFine("colorrgb1", "red", "r")?.let { (c, _) -> rCh = c }
        chFine("colorrgb2", "green", "g")?.let { (c, _) -> gCh = c }
        chFine("colorrgb3", "blue", "b")?.let { (c, _) -> bCh = c }
        // v4 属性通道
        chFine("zoom")?.let { (c, f) -> zoomCh = c; zoomFineCh = f ?: 0 }
        chFine("focus")?.let { (c, f) -> focusCh = c; focusFineCh = f ?: 0 }
        chFine("color1", "color")?.let { (c, _) -> colorCh = c }
        chFine("gobo1", "gobo")?.let { (c, _) -> goboCh = c }
        chFine("gobo1_pos", "gobo_pos", "goborotation")?.let { (c, _) -> goboRotCh = c }
        ptSpeedCh = def.ptSpeedCh
    }

    /** 灯内通道号 → 真实 DMX 通道（1-based）。 */
    private fun real(ch: Int): Int =
        if (ch >= 1) startAddr + ch - 1 else 0

    /**
     * 启动指定实例的效果（下发到该实例槽位）。
     * 不停止其他实例的效果——多实例可并行。
     */
    fun start(eng: DmxEngine, fxId: Int, instanceId: String?,
              amp: Int = 128, speed: Int = SPEED_DEFAULT) {
        engine = eng
        val k = key(instanceId)
        val slot = slotFor(instanceId)
        // 若该实例已有其他效果在跑，先停掉同槽旧效果（避免同槽覆盖冲突）
        val old = states[k]
        if (old != null && old.fxId != 0 && old.slot == slot && old.fxId != fxId) {
            eng.sendFxStop(slot)
        }
        val amplitude = amp.coerceIn(0, 255)
        val spd = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        val ptReal = ptSpeedCh?.let { real(it) } ?: 0
        states[k] = FxState(fxId, amplitude, spd, slot, ptSpeedCh, ptReal)
        // PT Speed 通道：0 = 最快跟踪（真实地址）
        if (ptReal != 0) eng.set(ptReal, 0)
        // 幅度 0..255 → 16bit 偏移 0..32768（系数 128）：
        // 半行程偏移 = 从中心到一端 = 全行程摆动；255 才是最大（128 约为一半）
        val amp16 = amplitude * 128
        eng.sendFxSet(slot, fxId,
            real(panCh), real(panFineCh), real(tiltCh), real(tiltFineCh),
            real(dimCh), real(dimFineCh), real(rCh), real(gCh), real(bCh),
            real(zoomCh), real(zoomFineCh), real(focusCh), real(focusFineCh),
            real(colorCh), real(goboCh), real(goboRotCh),
            amp16, spd)
    }

    /** 停止指定实例的效果。 */
    fun stop(instanceId: String?) {
        val k = key(instanceId)
        val old = states.remove(k) ?: return
        if (old.fxId != 0) {
            engine?.sendFxStop(old.slot)
            // 恢复该实例自己的 PT Speed 到中等值（真实地址）
            if (old.ptSpeedReal != 0) engine?.set(old.ptSpeedReal, 128)
        }
    }

    /** 停止所有实例的效果（全停）。 */
    fun stopAll() {
        for ((k, st) in states) {
            if (st.fxId != 0) engine?.sendFxStop(st.slot)
            if (st.ptSpeedReal != 0) engine?.set(st.ptSpeedReal, 128)
        }
        states.clear()
    }

    fun setAmplitude(v: Int, instanceId: String? = null) {
        val k = key(instanceId)
        states[k]?.let {
            val amp = v.coerceIn(0, 255)
            states[k] = it.copy(amplitude = amp)
            resendConfig()
        }
    }

    fun setSpeed(v: Int, instanceId: String? = null) {
        val k = key(instanceId)
        states[k]?.let {
            val spd = v.coerceIn(SPEED_MIN, SPEED_MAX)
            states[k] = it.copy(speed = spd)
            resendConfig()
        }
    }

    /** 把当前实例的效果配置重新发给 ESP32（滑条实时生效）。 */
    private fun resendConfig() {
        val eng = engine ?: return
        val k = key(currentInstanceId?.invoke())
        val st = states[k] ?: return
        if (st.fxId == 0) return
        val amp16 = st.amplitude * 128
        eng.sendFxSet(st.slot, st.fxId,
            real(panCh), real(panFineCh), real(tiltCh), real(tiltFineCh),
            real(dimCh), real(dimFineCh), real(rCh), real(gCh), real(bCh),
            real(zoomCh), real(zoomFineCh), real(focusCh), real(focusFineCh),
            real(colorCh), real(goboCh), real(goboRotCh),
            amp16, st.speed)
    }

    /** 由 FxEngine.setAmplitude/setSpeed 调用，取当前实例 id。 */
    var currentInstanceId: (() -> String?)? = null
}
