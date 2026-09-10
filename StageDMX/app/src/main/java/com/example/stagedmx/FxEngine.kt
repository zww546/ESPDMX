package com.example.stagedmx

/**
 * App 端效果引擎 — 板载模式 v4（多 slot 叠加 + 多实例并行）。
 *
 * 效果下发到 ESP32 板载效果引擎（协议 0x20-0x22），由板子实时生成波形。
 * - **多实例并行**：不同实例（不同 startAddr/灯型）可各自跑效果，互不干扰。
 * - **同实例叠加**：同一实例可叠加多个效果（如 圆形摇动 + 频闪 + RGB），
 *   每个效果分配一个独立的固件 slot（slot 0..7 全局共享）。
 * - **通道冲突拦截**：同实例叠加时若新效果要占的通道已被本实例其它效果占用，
 *   则拒绝启动（避免固件写 dmx_state 混乱）。跨实例因通道互相独立，不冲突。
 * - **全局槽预算**：固件共 8 槽，所有实例的并行效果总数不得超过 8，超出拒绝。
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
        FxDef(13, "切割循环", listOf("每步时长", "循环间隔")),
    )

    // 速度（0..65535，越大越快）：8.8 定点，等效周期(ms) = 655360 / speed。
    // 33 ≈ 19.9s/圈，3277 ≈ 200ms/圈。
    const val SPEED_MIN = 33
    const val SPEED_MAX = 3277
    const val SPEED_DEFAULT = 512   // ≈1.28s/圈

    /** 板载效果槽总数（固件 FX_MAX_COUNT=8，全局共享）。 */
    const val SLOT_COUNT = 8

    /** 每个效果状态（占用一个固件 slot）。 */
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
    // v5：切割片（blade1a..4b，0=未用）+ 切割旋转
    val bladeCh: IntArray = IntArray(8)     // 切割片通道（灯内号，0=该片不存在）
    var shaperRotCh = 0                     // 切割旋转
    var ptSpeedCh: Int? = null       // PT Speed 通道号
    var startAddr = 1                // 当前实例 DMX 起始地址（效果通道偏移基准）
    // 未激活时的预览参数（点击预设项后，滑块可先调，开关启动才生效）。
    // 按预设 id 分别记忆：否则“切割循环”会继承上一个效果的速度，默认 512(=12.8s/步)，
    // 启动后长时间没有任何动作，看起来像“效果无法使用”。
    private val previewAmpById = mutableMapOf<Int, Int>()
    private val previewSpeedById = mutableMapOf<Int, Int>()
    private const val FX_CUT_LOOP = 13

    /** 切割循环默认每步时长 500ms（speed = 65536/500 ≈ 131，在固件 33..3277 合法区间内）。 */
    private const val CUT_DEFAULT_STEP_MS = 500
    private const val CUT_DEFAULT_GAP_MS = 2000

    /** 各效果的默认预览速度（8.8 定点）。 */
    private fun defaultPreviewSpeed(fxId: Int): Int = when (fxId) {
        FX_CUT_LOOP -> cutStepMsToSpeed(CUT_DEFAULT_STEP_MS)
        else -> SPEED_DEFAULT
    }

    /** 各效果的默认预览幅度。切割循环的幅度字段 = 循环间隔，按 128ms/档换算。 */
    private fun defaultPreviewAmp(fxId: Int): Int = when (fxId) {
        FX_CUT_LOOP -> cutGapMsToAmp(CUT_DEFAULT_GAP_MS)
        else -> 128
    }

    // ---- 切割循环（fx_id=13）参数换算 ----
    // 固件把 amp16/speed 复用为时间参数（见 fx.c case 13），App 端在这里做物理时间换算，
    // 使滑条显示的是“秒”而不是无意义的原始值，且启动即用合理默认值：
    //   每步时长: speed = 65536 / 步时长(ms)      （固件 step_ticks = 655360/speed）
    //   循环间隔: amp16 = 间隔(ms)                （固件 gap_ticks = amp16/10）
    const val CUT_STEP_MS_MIN = 100
    const val CUT_STEP_MS_MAX = 5000
    const val CUT_GAP_MS_MIN = 0
    const val CUT_GAP_MS_MAX = 20000

    fun cutStepMsToSpeed(ms: Int): Int =
        (65536 / ms.coerceIn(CUT_STEP_MS_MIN, CUT_STEP_MS_MAX)).coerceIn(SPEED_MIN, SPEED_MAX)

    fun cutSpeedToStepMs(speed: Int): Int =
        (65536 / speed.coerceAtLeast(1)).coerceIn(CUT_STEP_MS_MIN, CUT_STEP_MS_MAX)

    fun cutGapMsToAmp(ms: Int): Int {
        val m = ms.coerceIn(CUT_GAP_MS_MIN, CUT_GAP_MS_MAX)
        if (m <= 0) return 0                        // 0 = 无间隔
        return ((m / 128) + 1).coerceIn(1, 255)
    }

    fun cutAmpToGapMs(amp: Int): Int =
        if (amp <= 0) 0 else amp.coerceIn(1, 255) * 128

    private var engine: DmxEngine? = null
    // 每个实例 → 效果列表（按添加顺序）。可叠加多个，各占一个固件 slot。
    private val states = linkedMapOf<String, MutableList<FxState>>()
    // 每个实例当前聚焦的效果 slot（滑条/参数编辑对象）；缺省 = 最后添加的。
    private val focusedSlotByKey = mutableMapOf<String, Int>()
    // 每个实例当前“选中但不一定启动”的预设 id（点击即选中，开关才启动/停止）。
    private val selectedPresetByKey = mutableMapOf<String, Int>()

    private fun key(instanceId: String?): String = instanceId ?: "global"

    // ---------- 查询 ----------

    fun isActive(instanceId: String? = null): Boolean =
        states[key(instanceId)]?.isNotEmpty() == true

    /** 该实例所有激活效果 id（顺序 = 添加顺序）。 */
    fun activeFxIds(instanceId: String? = null): List<Int> =
        states[key(instanceId)]?.map { it.fxId } ?: emptyList()

    /** 该实例中某个效果当前占用的 slot；未激活则 null。 */
    fun slotOf(instanceId: String? = null, fxId: Int): Int? =
        states[key(instanceId)]?.find { it.fxId == fxId }?.slot

    /** 该实例已激活的效果是否包含指定 id。 */
    fun containsFx(instanceId: String? = null, fxId: Int): Boolean =
        states[key(instanceId)]?.any { it.fxId == fxId } == true

    /** 全局是否已用满 8 槽。 */
    fun slotsFull(): Boolean = totalActiveCount() >= SLOT_COUNT

    /** 兼容：返回该实例最后激活的效果 id（无则 0）。 */
    fun activeFxId(instanceId: String? = null): Int =
        states[key(instanceId)]?.lastOrNull()?.fxId ?: 0

    fun getAmplitude(instanceId: String? = null): Int =
        focused(instanceId)?.amplitude ?: previewAmp(instanceId)

    fun getSpeed(instanceId: String? = null): Int =
        focused(instanceId)?.speed ?: previewSpeed(instanceId)

    /** 预览幅度：按当前选中预设记忆；未选过则用该预设的默认值。 */
    private fun previewAmp(instanceId: String?): Int {
        val sel = selectedPresetByKey[key(instanceId)] ?: 0
        return previewAmpById[sel] ?: defaultPreviewAmp(sel)
    }

    /** 预览速度：按当前选中预设记忆；未选过则用该预设的默认值。 */
    private fun previewSpeed(instanceId: String?): Int {
        val sel = selectedPresetByKey[key(instanceId)] ?: 0
        return previewSpeedById[sel] ?: defaultPreviewSpeed(sel)
    }

    /** 当前聚焦效果的 slot（已激活时才有意义）；无则 0。 */
    fun getSlot(instanceId: String? = null): Int =
        focused(instanceId)?.slot ?: 0

    /** 当前聚焦效果的 fxId（= 选中预设；未选中时 0）。 */
    fun getFocusedFxId(instanceId: String? = null): Int =
        selectedPresetByKey[key(instanceId)] ?: 0

    /** 全局并行效果总数（跨所有实例），用于检查是否超出固件 8 槽。 */
    fun totalActiveCount(): Int = states.values.sumOf { it.size }

    // ---------- 聚焦 / 选中（滑条编辑目标，开关才启停） ----------
    // 当前聚焦对象 = 选中的预设。若该预设已激活，则指向其运行中的 slot；否则仅作为预览选中。
    private fun focused(instanceId: String?): FxState? {
        val instId = instanceId ?: "global"
        // 若选中预设已激活 → 返回其 FxState（滑条改运行中参数）
        val sel = selectedPresetByKey[instId]
        if (sel != null) {
            states[instId]?.find { it.fxId == sel }?.let { return it }
        }
        // 否则退回：实例最后添加的效果（兼容旧调用，无选中时）
        val list = states[instId] ?: return null
        val fs = focusedSlotByKey[instId]
        return list.find { it.slot == fs } ?: list.lastOrNull()
    }

    /** 选中一个预设（点击预设项触发）：只选中，不启动。 */
    fun setSelectedPreset(instanceId: String?, fxId: Int) {
        selectedPresetByKey[key(instanceId)] = fxId
    }

    /** 是否有选中预设。 */
    fun hasSelectedPreset(instanceId: String? = null): Boolean =
        selectedPresetByKey[key(instanceId)] != null

    /** 设定某实例的聚焦效果（参数编辑对象，兼容旧接口）。 */
    fun setFocused(instanceId: String?, slot: Int) {
        focusedSlotByKey[key(instanceId)] = slot
    }

    /** 当前选中预设是否已激活。 */
    fun selectedIsActive(instanceId: String? = null): Boolean {
        val sel = selectedPresetByKey[key(instanceId)] ?: return false
        return states[key(instanceId)]?.any { it.fxId == sel } == true
    }

    // ---------- 槽位分配（全局共享 8 槽） ----------
    private fun usedSlots(): Set<Int> =
        states.values.flatten().map { it.slot }.toSet()

    private fun nextFreeSlot(): Int? {
        val used = usedSlots()
        for (s in 0 until SLOT_COUNT) if (s !in used) return s
        return null
    }

    // ---------- 同实例内的通道冲突检测 ----------
    /** 该效果会写入的真实 DMX 地址集合（含 fine、基于 startAddr + 当前通道映射）。 */
    private fun occupiedChannels(fxId: Int): Set<Int> {
        fun r(ch: Int): Int = if (ch >= 1) startAddr + ch - 1 else 0
        fun p(coarse: Int, fine: Int): Set<Int> =
            setOf(r(coarse), if (fine >= 1) r(fine) else 0).filter { it != 0 }.toSet()
        return when (fxId) {
            1 -> p(panCh, panFineCh) + p(tiltCh, tiltFineCh)      // 圆形：pan+tilt
            2 -> p(panCh, panFineCh)                               // 水平：pan
            3 -> p(tiltCh, tiltFineCh)                             // 垂直：tilt
            4 -> p(dimCh, dimFineCh)                               // 频闪：dim
            5 -> setOf(r(rCh), r(gCh), r(bCh)).filter { it != 0 }.toSet()   // RGB
            6 -> p(zoomCh, zoomFineCh)                             // 放大
            7 -> p(focusCh, focusFineCh)                           // 调焦
            8 -> setOf(r(colorCh)).filter { it != 0 }.toSet()      // 色盘
            9 -> setOf(r(goboCh)).filter { it != 0 }.toSet()       // 图案盘
            10 -> setOf(r(goboRotCh)).filter { it != 0 }.toSet()   // 图案盘自转
            11 -> setOf(r(goboCh), r(goboRotCh)).filter { it != 0 }.toSet() // 固定图案
            13 -> bladeCh.filter { it != 0 }.map { r(it) }.toSet() + setOf(r(shaperRotCh)).filter { it != 0 } // 切割循环
            else -> emptySet()
        }
    }

    /** 取当前聚焦效果（作为默认聚焦），用于兼容旧单参调用。 */
    @Deprecated("由 start() 内部自动聚焦，保留以兼容外部调用")
    var slotAllocator: ((String) -> Int)? = null

    // ---------- 通道映射 ----------

    /** 应用灯具配置（当前实例）。可在效果运行中调用。 */
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
        // v5：切割片（blade1a..4b，0=该片不存在→跳过）。
        // 兼容三种灯库命名：MA2 XML(attribute=BLADE1A)、老虎 D4(attribute=BLADE1..8)、珍珠 R20(名字=BLADE1..8)。
        // attribute 精确匹配优先，避免与用户通道名（MA 里切割片叫 "1A"/"1B"）或 "blade1" 前缀歧义。
        val byAttrExact = mutableMapOf<String, Pair<Int, Int?>>()
        for (ch in def.channels) {
            if (ch.attribute.isBlank()) continue
            byAttrExact.putIfAbsent(
                FixtureDef.normalizeKey(ch.attribute),
                ch.number to (if (ch.hasFine) ch.fineNumber else null))
        }
        val bladeKeys = listOf(
            listOf("blade1a", "blade1"),
            listOf("blade1b", "blade2"),
            listOf("blade2a", "blade3"),
            listOf("blade2b", "blade4"),
            listOf("blade3a", "blade5"),
            listOf("blade3b", "blade6"),
            listOf("blade4a", "blade7"),
            listOf("blade4b", "blade8"),
        )
        for (idx in bladeKeys.indices) bladeCh[idx] = 0
        for (idx in bladeKeys.indices) {
            for (key in bladeKeys[idx]) {
                val hit = byAttrExact[FixtureDef.normalizeKey(key)] ?: def.findChFine(key)
                if (hit != null) { bladeCh[idx] = hit.first; break }
            }
        }
        // 切割旋转
        shaperRotCh = 0
        for (key in listOf("shaper_rot", "shaperrot", "frame_rot", "framerot",
                           "shaper_rot_index", "shaper_rot_pos", "shaperrotation", "framearotation")) {
            val hit = byAttrExact[FixtureDef.normalizeKey(key)] ?: def.findChFine(key)
            if (hit != null) { shaperRotCh = hit.first; break }
        }
        ptSpeedCh = def.ptSpeedCh
    }

    /** 灯内通道号 → 真实 DMX 通道（1-based）。 */
    private fun real(ch: Int): Int =
        if (ch >= 1) startAddr + ch - 1 else 0

    /** 生成并发送一个效果配置给固件。 */
    private fun sendFxSetFor(eng: DmxEngine, st: FxState) {
        val amp16 = st.amplitude * 128
        eng.sendFxSet(st.slot, st.fxId,
            real(panCh), real(panFineCh), real(tiltCh), real(tiltFineCh),
            real(dimCh), real(dimFineCh), real(rCh), real(gCh), real(bCh),
            real(zoomCh), real(zoomFineCh), real(focusCh), real(focusFineCh),
            real(colorCh), real(goboCh), real(goboRotCh),
            amp16, st.speed,
            blades = bladeCh.map { real(it) },   // v5：切割片真实地址
            shaperRot = real(shaperRotCh))       // v5：切割旋转真实地址
    }

    /**
     * 启动指定实例的效果（叠加到新 slot）。
     * - 若该实例已激活同类型效果 → 只更新参数（不重复叠加）。
     * - 若新增效果与同实例现有效果通道冲突 → 拒绝，返回 false。
     * - 若全局已用满 8 槽 → 拒绝，返回 false。
     * @return 是否成功启动。
     */
    fun start(eng: DmxEngine, fxId: Int, instanceId: String?,
              amp: Int = getAmplitude(instanceId), speed: Int = getSpeed(instanceId)): Boolean {
        engine = eng
        val k = key(instanceId)
        val list = states.getOrPut(k) { mutableListOf() }
        val amplitude = amp.coerceIn(0, 255)
        // 速度仍走 33..3277 的合法区间（切割循环的“每步时长”换算后落在这个区间内）
        val spd = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        previewAmpById[fxId] = amplitude
        previewSpeedById[fxId] = spd

        // 同类型已激活 → 只更新参数
        val existing = list.find { it.fxId == fxId }
        if (existing != null) {
            val st = existing.copy(amplitude = amplitude, speed = spd)
            list[list.indexOf(existing)] = st
            focusedSlotByKey[k] = st.slot
            selectedPresetByKey[k] = fxId
            sendFxSetFor(eng, st)
            return true
        }

        // 全局槽预算（固件 8 槽）
        if (totalActiveCount() >= SLOT_COUNT) return false
        // 同实例通道冲突：按真实 DMX 地址重叠检测（含 fine），更精确
        val newRev = occupiedChannels(fxId)
        if (newRev.any { ch -> list.any { occupiedChannels(it.fxId).contains(ch) } }) return false

        val slot = nextFreeSlot() ?: return false
        val ptReal = ptSpeedCh?.let { real(it) } ?: 0
        val st = FxState(fxId, amplitude, spd, slot, ptSpeedCh, ptReal)
        list.add(st)
        focusedSlotByKey[k] = slot
        selectedPresetByKey[k] = fxId

        // PT Speed 通道：0 = 最快跟踪（真实地址），只在该实例第一个效果时设置
        if (ptReal != 0) eng.set(ptReal, 0)
        sendFxSetFor(eng, st)
        return true
    }

    /** 停止某实例的所有效果。 */
    fun stop(instanceId: String?) {
        val k = key(instanceId)
        val list = states.remove(k) ?: return
        for (st in list) {
            engine?.sendFxStop(st.slot)
            if (st.ptSpeedReal != 0) engine?.set(st.ptSpeedReal, 128)
        }
        focusedSlotByKey.remove(k)
        selectedPresetByKey.remove(k)
    }

    /** 停止某实例的单个 slot 效果（只停一个，不影响其它叠加效果）。 */
    fun stopSlot(instanceId: String?, slot: Int) {
        val k = key(instanceId)
        val list = states[k] ?: return
        val st = list.firstOrNull { it.slot == slot } ?: return
        // 若这是该实例最后一个效果，恢复 PT Speed
        val lastForInstance = list.size == 1
        list.remove(st)
        engine?.sendFxStop(slot)
        if (lastForInstance && st.ptSpeedReal != 0) engine?.set(st.ptSpeedReal, 128)
        if (list.isEmpty()) states.remove(k)
    }

    /** 停止所有实例的所有效果（全停）。 */
    fun stopAll() {
        for ((k, list) in states) {
            for (st in list) {
                engine?.sendFxStop(st.slot)
                if (st.ptSpeedReal != 0) engine?.set(st.ptSpeedReal, 128)
            }
        }
        states.clear()
        focusedSlotByKey.clear()
    }

    // ---------- 参数编辑（作用于聚焦/选中预设） ----------

    fun setAmplitude(v: Int, instanceId: String? = null) {
        val k = key(instanceId)
        val list = states[k]
        val f = focused(instanceId)
        if (list != null && f != null && list.contains(f)) {
            val idx = list.indexOf(f)
            if (idx < 0) return
            val st = f.copy(amplitude = v.coerceIn(0, 255))
            list[idx] = st
            engine?.let { sendFxSetFor(it, st) }
        } else {
            // 未激活（预览选中）→ 只改该预设自己的预览值，开关启动时生效
            previewAmpById[selectedPresetByKey[k] ?: 0] = v.coerceIn(0, 255)
        }
    }

    fun setSpeed(v: Int, instanceId: String? = null) {
        val k = key(instanceId)
        val list = states[k]
        val f = focused(instanceId)
        if (list != null && f != null && list.contains(f)) {
            val idx = list.indexOf(f)
            if (idx < 0) return
            val st = f.copy(speed = v.coerceIn(SPEED_MIN, SPEED_MAX))
            list[idx] = st
            engine?.let { sendFxSetFor(it, st) }
        } else {
            // 未激活（预览选中）→ 只改该预设自己的预览值
            previewSpeedById[selectedPresetByKey[k] ?: 0] = v.coerceIn(SPEED_MIN, SPEED_MAX)
        }
    }

    /** 启动当前选中的预设（开关打开）。返回是否成功（false=冲突/满槽/未选中）。 */
    fun startSelected(eng: DmxEngine, instanceId: String?): Boolean {
        val sel = selectedPresetByKey[key(instanceId)] ?: return false
        return start(eng, sel, instanceId, getAmplitude(instanceId), getSpeed(instanceId))
    }

    /** 停止当前选中的预设（若已激活）。 */
    fun stopSelected(instanceId: String?) {
        val sel = selectedPresetByKey[key(instanceId)] ?: return
        states[key(instanceId)]?.find { it.fxId == sel }?.let {
            stopSlot(instanceId, it.slot)
        }
    }

    /** 由 FxEngine.setAmplitude/setSpeed 调用，取当前实例 id（兼容旧接口）。 */
    var currentInstanceId: (() -> String?)? = null
}
