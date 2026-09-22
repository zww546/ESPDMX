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

    private var count = 10
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

    override fun getItemCount(): Int = if (groupByFunction) rows.size else count


    // ========================================================================
    // 按功能分组折叠（设置页 swFaderGroup 控制）
    //
    // 设计要点：**对外 API 仍然以"通道下标 position"为单位**（dmxChannel/attrAt/
    // onSet 全都如此），分组只改变**行的排布**。所以这里维护
    //   rows[i]  = 第 i 行是组标题还是通道
    //   rowPos[i] = 第 i 行对应哪个通道下标（组标题为 -1）
    // 这样 MainActivity 一行都不用改，风险被限制在这个文件里。
    // ========================================================================

    /** 是否按功能分组折叠。 */
    var groupByFunction = false
        set(v) {
            field = v
            collapsedGroups.clear()
            refresh()
        }

    private val collapsedGroups = mutableSetOf<String>()

    /** 组标题行。 */
    private class GroupHeader(val name: String, val members: Int, val collapsed: Boolean)

    private var rows: List<Any> = emptyList()
    private var rowPos: IntArray = IntArray(0)

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

    /** 重建行列表（分组开关、通道数、灯型变化后调用）。 */
    fun rebuildRows() {
        if (!groupByFunction) {
            rows = emptyList()
            rowPos = IntArray(0)
            return
        }
        val out = ArrayList<Any>()
        val pos = ArrayList<Int>()
        for (g in groupOrder) {
            val members = (0 until count).filter { groupOf(it) == g }
            if (members.isEmpty()) continue
            val collapsed = g in collapsedGroups
            out.add(GroupHeader(g, members.size, collapsed))
            pos.add(-1)
            if (!collapsed) for (m in members) {
                out.add(m)      // 直接放通道下标（Int）
                pos.add(m)
            }
        }
        rows = out
        rowPos = pos.toIntArray()
    }

    /** 点组标题：折叠/展开并刷新。 */
    fun toggleGroup(name: String) {
        if (!collapsedGroups.remove(name)) collapsedGroups.add(name)
        refresh()
    }

    /** 行 → 通道下标（组标题返回 -1）。 */
    private fun posOfRow(row: Int): Int =
        if (groupByFunction) rowPos.getOrElse(row) { -1 } else row

    override fun getItemViewType(position: Int): Int =
        if (groupByFunction && rows.getOrNull(position) is GroupHeader) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 1) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel_group, parent, false)
            HeaderVH(v)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel, parent, false)
            VH(v)
        }
    }

    private inner class HeaderVH(root: View) : RecyclerView.ViewHolder(root) {
        val tv: TextView = root.findViewById(R.id.tvGroupName)
        val tvCount: TextView = root.findViewById(R.id.tvGroupCount)
        init {
            root.setOnClickListener {
                val p = bindingAdapterPosition
                val h = rows.getOrNull(p)
                if (h is GroupHeader) toggleGroup(h.name)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = if (groupByFunction) rows.getOrNull(position) else null
        if (row is GroupHeader) {
            val h = holder as HeaderVH
            h.tv.text = row.name
            h.tvCount.text = "${row.members} 通道" + if (row.collapsed) "  ▸" else "  ▾"
            return
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
}
