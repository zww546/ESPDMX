package com.example.stagedmx

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 通道滑条列表（横向滑条，单列）。
 * 支持三种模式：
 *  - 裸通道模式：position i ↔ DMX 通道 i+1
 *  - 灯具/实例模式：position i ↔ 灯内通道 i+1，写入真实地址 startAddr + i
 *  - 多实例组模式：position i ↔ 灯内通道 i+1，写入由 MainActivity 统一分发到组内所有实例
 * 拖动 -> onSet(灯内通道, 值)；场景/程序/全黑全亮改动后 refresh() 回读引擎值
 * （binding 保护位避免程序回填触发 set 回灌）。点数值可精确输入。
 */
class ChannelAdapter(
    private val engine: DmxEngine,
    private val onSet: (chInFixture: Int, value: Int) -> Unit,
    private val onEditValue: (chInFixture: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var count = DEFAULT_BARE_COUNT
    private var channelNames: List<String>? = null
    private var channelOrigNames: List<String>? = null  // 原始英文名
    private var channelAttrs: List<String>? = null      // MA attribute（用于上下文翻译）
    private var fixtureName = ""                        // 当前灯名（用于前缀专属翻译）
    private var startAddr = 1                           // 实例 DMX 起始地址（灯具模式/组模式主灯）
    private var defaultValues: IntArray? = null
    private var groupInstances: List<FixtureInstance>? = null  // 组模式：参与控制的实例（按地址排序）
    /** 自定义排列：order[显示位置] = 灯内通道下标(0-based)；null = 按通道顺序。 */
    private var order: IntArray? = null
    @Volatile var translated = true  // 翻译开关（设置页全局控制）

    /** 显示位置 → 灯内通道下标(0-based)。 */
    private fun idx(position: Int): Int =
        order?.getOrNull(position)?.takeIf { it in 0 until count } ?: position

    /**
     * 应用自定义排列（设置页里的“推子页排列方式”）。
     * 数组必须是 0..count-1 的一个排列，否则忽略并回到通道顺序。
     */
    @SuppressLint("NotifyDataSetChanged")
    fun applyOrder(o: IntArray?) {
        order = if (o == null || o.size != count || o.toSet().size != count || o.any { it !in 0 until count })
            null else o.copyOf()
        refresh()
    }

    /** 当前显示顺序（位置 → 灯内通道下标）。 */
    fun currentOrder(): IntArray = IntArray(count) { idx(it) }

    /** 当前显示顺序下的通道标签（排列编辑对话框用）。 */
    fun channelLabels(): List<String> = List(count) { displayName(idx(it)) }

    /**
     * 第 position 行对应灯型的属性名（如 "dim" / "pan" / "gobo1_pos"）。
     *
     * 混合灯型的组控制需要它：不同灯型的"第 3 通道"含义不同（一个频闪一个色盘），
     * 所以不能按通道号照抄，必须按属性名找到每台灯各自的通道。
     * 显示顺序（自定义排列）也一并处理，保证"推子第 5 行"与属性一致。
     */
    fun attrAt(position: Int): String =
        channelAttrs?.getOrNull(idx(position)) ?: ""

    /** 灯内通道号（1-based）→ 属性名。 */
    fun attrOfChannel(chNumber: Int): String =
        channelAttrs?.getOrNull(chNumber - 1) ?: ""

    @SuppressLint("NotifyDataSetChanged")
    fun setChannelCount(n: Int) {
        count = n.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        channelNames = null
        channelOrigNames = null
        channelAttrs = null
        fixtureName = ""
        startAddr = 1
        defaultValues = null
        groupInstances = null
        order = null
        refresh()
    }

    /** 应用灯具（可选实例起始地址）：完整通道名数组（未定义的填 CH N）。 */
    @SuppressLint("NotifyDataSetChanged")
    fun applyFixture(fixture: FixtureDef, startAddress: Int = 1) {
        count = fixture.channelCount.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        startAddr = startAddress.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        val names = MutableList(count) { "CH ${it + 1}" }
        val origNames = MutableList(count) { "CH ${it + 1}" }
        val attrs = MutableList(count) { "" }
        fixture.channels.forEach {
            val idx = it.number - 1
            if (idx in 0 until count) {
                names[idx] = "${it.number}.${it.originalName}"
                origNames[idx] = names[idx]
                attrs[idx] = it.attribute
            }
        }
        channelNames = names
        channelOrigNames = origNames
        channelAttrs = attrs
        fixtureName = fixture.name
        defaultValues = null
        groupInstances = null
        order = null
        refresh()
    }

    /**
     * 多实例组模式：同时控制多台相同灯库的灯。
     * @param instances 组内实例（同灯型，按 DMX 起始地址排序），[0] 为主灯（用于回读显示）
     */
    @SuppressLint("NotifyDataSetChanged")
    fun applyFixtureGroup(fixture: FixtureDef, instances: List<FixtureInstance>) {
        require(instances.isNotEmpty()) { "empty group" }
        count = fixture.channelCount.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        startAddr = instances[0].globalAddr().coerceIn(1, DmxProtocol.MAX_CHANNELS)
        val names = MutableList(count) { "CH ${it + 1}" }
        val origNames = MutableList(count) { "CH ${it + 1}" }
        val attrs = MutableList(count) { "" }
        fixture.channels.forEach {
            val idx = it.number - 1
            if (idx in 0 until count) {
                names[idx] = "${it.number}.${it.originalName}"
                origNames[idx] = names[idx]
                attrs[idx] = it.attribute
            }
        }
        channelNames = names
        channelOrigNames = origNames
        channelAttrs = attrs
        fixtureName = fixture.name
        defaultValues = null
        groupInstances = instances.sortedBy { it.globalAddr() }
        order = null
        refresh()
    }

    /** 清除灯具模式，回到裸通道。 */
    @SuppressLint("NotifyDataSetChanged")
    fun clearFixture() {
        channelNames = null
        channelOrigNames = null
        channelAttrs = null
        fixtureName = ""
        startAddr = 1
        defaultValues = null
        groupInstances = null
        order = null
        // ⚠ 通道数必须一起复位。这里以前**漏了** `count`，于是灯具模式下的
        //   `count = 灯库通道数` 会留到裸通道模式 —— 用户看到的就是
        //   "取消选中灯具后，推子数量还是那台灯的通道数"（而不是原来的 10）。
        //   放在这里而不是只放在调用方，是为了让别的调用点也不可能漏。
        count = DEFAULT_BARE_COUNT
        // 切割面板的状态也一起清：否则「切割」分组会继续挂在列表上
        collapsedBladeChannels = null
        shaperBlades = IntArray(8)
        shaperRotCh = 0
        shaperSummary = ""
        refresh()
    }

    fun isFixtureMode(): Boolean = channelNames != null
    fun isGroupMode(): Boolean = groupInstances != null
    fun groupInstances(): List<FixtureInstance> = groupInstances ?: emptyList()
    fun channelCount() = count

    /**
     * 全黑/全亮/Flash 的作用范围（1-based 闭区间）。
     * 裸通道 = 1..count；单实例 = 该实例地址段；组模式返回 null（由 MainActivity 遍历组）。
     */
    fun uniformRange(): Pair<Int, Int>? =
        if (isGroupMode()) null
        else if (isFixtureMode()) (startAddr to startAddr + count - 1)
        else (1 to count)

    /** position → 真实 DMX 通道号（1-based）；组模式返回主灯的真实地址（仅用于回读显示）。 */
    fun dmxChannel(position: Int): Int = startAddr + idx(position)

    /**
     * 灯内通道号（1-based）→ 该实例的**全局 DMX 通道**（1-based）。
     *
     * 和 [onSet] 的写入目标是同一个地址（组模式下取主灯），所以切割面板只要用
     * "读这个地址 + 走 onSet 写"这一对，就自动和推子页完全一致 ——
     * 多实例、组模式、混灯型都不用再单独判断一套。
     */
    fun dmxChannelOfFixtureCh(chInFixture: Int): Int = startAddr + (chInFixture - 1)

    private fun displayName(position: Int): String {
        val raw = (if (translated || channelOrigNames == null)
            channelNames else channelOrigNames)?.getOrNull(position)
            ?: "CH ${position + 1}"
        if (!translated || !isFixtureMode()) return raw
        // 翻译 "28.Pan" → "28.水平"；attribute 提供上下文（COLOR1/GOBO1/PAN...）
        val dot = raw.indexOf('.')
        if (dot < 0) return raw
        val attr = channelAttrs?.getOrNull(position) ?: ""
        return raw.substring(0, dot + 1) + FixtureParser.translate(raw.substring(dot + 1), attr, fixtureName)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        rebuildRows()          // 分组开关/灯型/通道数变化后行结构要重算
        notifyDataSetChanged()
    }

    inner class VH(root: View) : RecyclerView.ViewHolder(root) {
        val tvCh: TextView = root.findViewById(R.id.tvCh)
        val seek: SeekBar = root.findViewById(R.id.seek)
        val tvVal: TextView = root.findViewById(R.id.tvVal)
        var bound = -1
        var binding = false

        init {
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (binding) return
                    val ch = idx(bound) + 1   // 灯内通道号（1-based），写入由 MainActivity 分发
                    if (ch >= 1) {
                        onSet(ch, progress)
                        tvVal.text = progress.toString()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            tvVal.setOnClickListener {
                if (bound >= 0) onEditValue(idx(bound) + 1)
            }
        }
    }

    // ⚠ 行数一律以 rows 为准：不分组时 rows 就是 0..count-1（恒等），
    //   所以这里不需要再区分分组开关 —— 而切割入口行要求"不分组时也能替掉几行"。
    override fun getItemCount(): Int = rows.size


    // ========================================================================
    // 按功能分组折叠（设置页 swFaderGroup 控制）+ 切割入口行
    //
    // 设计要点：**对外 API 仍然以"通道下标 position"为单位**（dmxChannel/attrAt/
    // onSet 全都如此），分组只改变**行的排布**。
    // 行模型的算法本体已经抽到 [ChannelRows]（纯逻辑，可单测）—— 因为它踩过坑
    // （折叠切割时漏掉「切割旋转」会导致组头和入口行同时出现），放在这里没法测。
    // ========================================================================

    /** 是否按功能分组折叠。 */
    var groupByFunction = false
        set(v) {
            field = v
            collapsedGroups.clear()
            refresh()
        }

    private val collapsedGroups = mutableSetOf<String>()

    /**
     * 模式 1/2：要收进内嵌面板的**灯内通道号**（1-based）。null = 不收进（模式 0）。
     * 由 MainActivity 按 `FxEngine.bladeCh` 设置（必须含 8 个切割片**和切割旋转**）。
     */
    var collapsedBladeChannels: Set<Int>? = null

    /** 8 个切割片通道号（灯内号，0 = 该通道不存在）—— 决定面板里 8 条滑块的顺序。 */
    var shaperBlades: IntArray = IntArray(8)

    /** 切割旋转通道号（灯内号，0 = 没有）。 */
    var shaperRotCh: Int = 0

    /** 面板标题右侧的小字（MainActivity 填当前形状，如"梯形 · 2 片在切"）。 */
    var shaperSummary: String = ""

    /**
     * 填充预览图。MainActivity 在这里把当前刀片几何 / 旋转角塞进 [ShaperWindowView]，
     * 几何知识全部留在 MainActivity（面板只是同一份通道值的另一个视图）。
     */
    var shaperPreviewBinder: ((ShaperWindowView) -> Unit)? = null

    /** 面板底部那一排按钮已整排删掉（映射挪到设置页，见 MainActivity）。 */
    private var rows: List<ChannelRows.Row> = emptyList()

    // ---- 面板视图的「原地更新」句柄 ----
    // 拖动过程中**绝不能**重绑列表：notifyItemChanged 会把用户正按着的 View detach 掉，
    // 手势立刻收到 ACTION_CANCEL，拖一半就断。推子页本来也是这么做的（拖通道滑条不 refresh）。
    private var shaperBoundPreview: ShaperWindowView? = null
    private var shaperBoundShape: TextView? = null
    private var shaperBoundHeader: TextView? = null

    /** 当前绑定的面板滑块（用来在值从别处变了之后把滑条位置拉回来）。 */
    private val boundShaperSliders = ArrayList<ShaperSliderVH>()

    /**
     * 值变了之后重画面板：只改当前绑定的那几个 View，不触发任何 notify。
     * 用户拖刀片 / 拖旋转 / 拖滑块时走这里。
     *
     * 顺带把面板里**其它**滑条的位置拉回来 —— 模式 2 是"拖预览改值"，
     * 拖一次会同时改到偏移（和旋转），旁边那条滑条不跟着动的话就对不上号了。
     */
    fun refreshShaperPanel(
        blades: List<ShaperGeometry.Blade>, rotationRad: Double, label: String
    ) {
        shaperSummary = label
        shaperBoundPreview?.let { it.blades = blades; it.rotationRad = rotationRad }
        shaperBoundShape?.text = label
        // 标题上的三角形要跟着折叠状态（面板开着时用户改值也会走到这儿）
        shaperBoundHeader?.text =
            label + if (isGroupCollapsed(ChannelRows.CUT_GROUP)) "  ▸" else "  ▾"
        for (h in boundShaperSliders.toList()) {
            if (h.chInFixture <= 0) continue
            val v = engine.get(dmxChannelOfFixtureCh(h.chInFixture))
            h.binding = true
            if (!h.seek.isPressed) h.seek.progress = v   // 正在拖的那条别打断
            h.tvVal.text = v.toString()
            h.binding = false
        }
    }

    /** 组的固定顺序（也让相同功能始终挨在一起）。规则本体见 [ChannelGroups]。 */
    private val groupOrder = ChannelGroups.ORDER

    /**
     * 按 attribute（优先）或通道名判断该通道属于哪个功能组。
     *
     * 规则抽到了 [ChannelGroups] —— 它是纯字符串判断，用普通 JUnit 就能测；
     * 留在这里的话单测要拉 Robolectric（RecyclerView.Adapter 是 Android 类型）。
     */
    private fun groupOf(position: Int): String = ChannelGroups.of(
        channelAttrs?.getOrNull(position) ?: "",
        channelNames?.getOrNull(position) ?: "",
        channelOrigNames?.getOrNull(position) ?: "")

    /** 通道下标 → 灯内通道号（1-based）。 */
    private fun chNumberOf(position: Int): Int = idx(position) + 1

    /**
     * 重建行模型。
     *
     * 算法本体在 [ChannelRows]（纯逻辑、可单测），这里只负责把 Adapter 的当前状态喂给它。
     */
    fun rebuildRows() {
        rows = ChannelRows.build(
            count = count,
            grouped = groupByFunction,
            groupOf = { groupOf(it) },
            chNumberOf = { chNumberOf(it) },
            collapsedGroups = collapsedGroups,
            shaperSkip = collapsedBladeChannels,
            shaperMode = shaperMode,
            shaperBlades = shaperBlades,
            shaperRotCh = shaperRotCh,
            groupOrder = groupOrder,
        )
    }

    /** 当前切割 UI 模式（0 推杆 / 1 面板 / 2 窗口），由 MainActivity 设置。 */
    var shaperMode: Int = ShaperStore.MODE_FADERS


    /** 点组标题：折叠/展开并刷新。 */
    fun toggleGroup(name: String) {
        if (!collapsedGroups.remove(name)) collapsedGroups.add(name)
        refresh()
    }

    /**
     * 直接设定某个功能组的折叠状态（不刷新，调用方决定什么时候 refresh）。
     * 用来实现"换到面板/窗口模式时，切割块默认收起"。
     */
    fun setGroupCollapsed(name: String, collapsed: Boolean) {
        if (collapsed) collapsedGroups.add(name) else collapsedGroups.remove(name)
    }

    /** 该功能组当前是否折叠。 */
    fun isGroupCollapsed(name: String): Boolean = name in collapsedGroups

    /** 行 → 通道下标（组标题 / 切割面板的几种行返回 -1，调用方据此忽略）。 */
    private fun posOfRow(row: Int): Int =
        (rows.getOrNull(row) as? ChannelRows.Row.Channel)?.position ?: -1

    /**
     * 行类型：
     *  0 = 通道滑条（[item_channel]）
     *  1 = 功能分组标题（[item_channel_group]）
     *  2 = 切割圆形预览（[item_shaper_preview]）
     *  3 = 切割滑块：偏移/角度/旋转（[item_shaper_slider]）
     */
    override fun getItemViewType(position: Int): Int = when (rows.getOrNull(position)) {
        is ChannelRows.Row.Group -> 1
        is ChannelRows.Row.ShaperPreview -> 2
        is ChannelRows.Row.ShaperSlider, is ChannelRows.Row.ShaperRot -> 3
        else -> 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> HeaderVH(inf.inflate(R.layout.item_channel_group, parent, false))
            2 -> PreviewVH(inf.inflate(R.layout.item_shaper_preview, parent, false))
            3 -> ShaperSliderVH(inf.inflate(R.layout.item_shaper_slider, parent, false))
            else -> VH(inf.inflate(R.layout.item_channel, parent, false))
        }
    }

    private inner class HeaderVH(root: View) : RecyclerView.ViewHolder(root) {
        val tv: TextView = root.findViewById(R.id.tvGroupName)
        val tvCount: TextView = root.findViewById(R.id.tvGroupCount)
        init {
            // 切割面板标题点一下也折叠（整块收起，和普通分组一致）
            root.setOnClickListener {
                (rows.getOrNull(bindingAdapterPosition) as? ChannelRows.Row.Group)?.let {
                    toggleGroup(it.name)
                }
            }
        }
    }

    /** 切割圆形预览行。 */
    private inner class PreviewVH(root: View) : RecyclerView.ViewHolder(root) {
        val view: ShaperWindowView = root.findViewById(R.id.shaperPreview)
        val tvShape: TextView = root.findViewById(R.id.tvShaperShape)
        init {
            // 窗口模式：拖线段 = 那一片沿法线进出；拖角 = 两条相邻边跟着走。
            // 旋转不在这里拖 —— 用户要求"只能通过拖动条旋转"。
            view.onEdgeDrag = { side, inset -> onShaperEdgeDrag?.invoke(side, inset) }
            view.onCornerDrag = { i, from, p -> onShaperCornerDrag?.invoke(i, from, p) }
            view.onDragEnd = { onShaperDragEnd?.invoke() }
        }
    }

    /** 拖线段：该片属于哪条边 + 新的几何插入量。 */
    var onShaperEdgeDrag: ((ShaperGeometry.Side, Double) -> Unit)? = null

    /** 拖角：角下标 + 手势按下时的四角快照 + 新位置。 */
    var onShaperCornerDrag: ((Int, List<ShaperGeometry.Pt>, ShaperGeometry.Pt) -> Unit)? = null

    /** 手势结束（抬手）：把画面交回引擎里的真实值。 */
    var onShaperDragEnd: (() -> Unit)? = null

    /** 面板里任何一条滑块被拖动之后回调（MainActivity 用它重画预览）。 */
    var onShaperValueChanged: (() -> Unit)? = null

    /** 切割滑块行（偏移 / 角度 / 旋转共用）。 */
    private inner class ShaperSliderVH(root: View) : RecyclerView.ViewHolder(root) {
        val label: TextView = root.findViewById(R.id.tvShaperLabel)
        val seek: SeekBar = root.findViewById(R.id.seekShaper)
        val tvVal: TextView = root.findViewById(R.id.tvShaperVal)
        var chInFixture = 0
        var binding = false

        init {
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (binding || chInFixture <= 0) return
                    // 走的是**和推子页完全同一条**写入路径：面板只是同一份通道值的视图
                    onSet(chInFixture, progress)
                    tvVal.text = progress.toString()
                    onShaperValueChanged?.invoke()   // 让预览图跟着动
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            tvVal.setOnClickListener { if (chInFixture > 0) onEditValue(chInFixture) }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows.getOrNull(position)) {
            is ChannelRows.Row.Group -> {
                val h = holder as HeaderVH
                h.tv.text = row.name
                // 切割面板的标题用形状摘要（"梯形 · 2 片在切"），普通组用通道数
                if (row.name == ChannelRows.CUT_GROUP && shaperSummary.isNotEmpty()) {
                    // ⚠ 别忘了三角形：普通组靠 "▸/▾" 表示能不能点开，切割这一行换成摘要后
                    //   就没有这个提示了，用户根本不知道点标题能展开面板。
                    h.tvCount.text = shaperSummary + if (row.collapsed) "  ▸" else "  ▾"
                    shaperBoundHeader = h.tvCount
                } else {
                    h.tvCount.text = "${row.members} 通道" + if (row.collapsed) "  ▸" else "  ▾"
                    if (shaperBoundHeader === h.tvCount) shaperBoundHeader = null
                }
                return
            }
            is ChannelRows.Row.ShaperPreview -> {
                val h = holder as PreviewVH
                h.view.interactive = row.interactive
                shaperPreviewBinder?.invoke(h.view)
                h.tvShape.text = shaperSummary
                shaperBoundPreview = h.view
                shaperBoundShape = h.tvShape
                return
            }
            is ChannelRows.Row.ShaperSlider -> {
                val h = holder as ShaperSliderVH
                bindShaperSlider(h, row.chInFixture,
                    h.itemView.context.getString(
                        if (row.isAngle) R.string.s_shaper_blade_angle else R.string.s_shaper_blade_offset,
                        row.blade + 1))
                trackShaperSlider(h)
                return
            }
            is ChannelRows.Row.ShaperRot -> {
                val h = holder as ShaperSliderVH
                bindShaperSlider(h, row.chInFixture,
                    h.itemView.context.getString(R.string.s_shaper_rotate))
                trackShaperSlider(h)
                return
            }
            else -> {}
        }
        val vh = holder as VH
        val p = posOfRow(position)
        vh.bound = p
        val v = engine.get(dmxChannel(p))
        vh.binding = true
        vh.tvCh.text = displayName(p)
        // 让通道名跑马灯动起来：TV 只在 isSelected 时才会滚（XML 里配好 ellipsize=marquee
        // 还不够）。名字没超宽时不会有可见效果，所以无条件设置是安全的。
        vh.tvCh.isSelected = true
        vh.seek.progress = v
        vh.tvVal.text = v.toString()
        vh.binding = false
    }

    /** 行被回收：清掉指向它的句柄，免得之后改到已经被别人用的 View。 */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is PreviewVH) {
            if (shaperBoundPreview === holder.view) {
                shaperBoundPreview = null
                shaperBoundShape = null
            }
        } else if (holder is HeaderVH) {
            if (shaperBoundHeader === holder.tvCount) shaperBoundHeader = null
        } else if (holder is ShaperSliderVH) {
            boundShaperSliders.removeAll { it === holder }
        }
    }

    /** 记下这条面板滑块（同一个 holder 重绑时不要重复登记）。 */
    private fun trackShaperSlider(h: ShaperSliderVH) {
        if (boundShaperSliders.none { it === h }) boundShaperSliders.add(h)
    }

    /** 面板滑块绑定：通道号 0 表示该灯没有这个通道 —— 显示"—"并禁用，而不是假装有值。 */
    private fun bindShaperSlider(h: ShaperSliderVH, chInFixture: Int, label: String) {
        h.chInFixture = chInFixture
        h.label.text = label
        h.binding = true
        if (chInFixture <= 0) {
            h.seek.progress = 0
            h.seek.isEnabled = false
            h.label.alpha = 0.4f
            h.tvVal.text = h.itemView.context.getString(R.string.s_shaper_na)
        } else {
            val v = engine.get(dmxChannelOfFixtureCh(chInFixture))
            h.seek.isEnabled = true
            h.label.alpha = 1f
            // 用户正按着这条滑条时不要回写 progress（和面板整体刷新一个道理）
            if (!h.seek.isPressed) h.seek.progress = v
            h.tvVal.text = v.toString()
        }
        h.binding = false
    }

    companion object {
        /** 没有选中灯具时推子页的默认通道数（裸通道模式）。 */
        const val DEFAULT_BARE_COUNT = 10
    }
}
