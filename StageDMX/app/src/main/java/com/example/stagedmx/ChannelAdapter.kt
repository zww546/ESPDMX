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
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var count = 10
    private var channelNames: List<String>? = null
    private var channelOrigNames: List<String>? = null  // 原始英文名
    private var channelAttrs: List<String>? = null      // MA attribute（用于上下文翻译）
    private var fixtureName = ""                        // 当前灯名（用于前缀专属翻译）
    private var startAddr = 1                           // 实例 DMX 起始地址（灯具模式/组模式主灯）
    private var defaultValues: IntArray? = null
    private var groupInstances: List<FixtureInstance>? = null  // 组模式：参与控制的实例（按地址排序）
    @Volatile var translated = true  // 翻译开关

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
        notifyDataSetChanged()
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
        notifyDataSetChanged()
    }

    /**
     * 多实例组模式：同时控制多台相同灯库的灯。
     * @param instances 组内实例（同灯型，按 DMX 起始地址排序），[0] 为主灯（用于回读显示）
     */
    @SuppressLint("NotifyDataSetChanged")
    fun applyFixtureGroup(fixture: FixtureDef, instances: List<FixtureInstance>) {
        require(instances.isNotEmpty()) { "empty group" }
        count = fixture.channelCount.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        startAddr = instances[0].startAddr.coerceIn(1, DmxProtocol.MAX_CHANNELS)
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
        groupInstances = instances.sortedBy { it.startAddr }
        notifyDataSetChanged()
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
        notifyDataSetChanged()
    }

    fun isFixtureMode(): Boolean = channelNames != null
    fun isGroupMode(): Boolean = groupInstances != null
    fun groupInstances(): List<FixtureInstance> = groupInstances ?: emptyList()
    fun groupSize(): Int = groupInstances?.size ?: 0
    fun channelCount() = count
    fun startAddress() = startAddr

    /**
     * 全黑/全亮/Flash 的作用范围（1-based 闭区间）。
     * 裸通道 = 1..count；单实例 = 该实例地址段；组模式返回 null（由 MainActivity 遍历组）。
     */
    fun uniformRange(): Pair<Int, Int>? =
        if (isGroupMode()) null
        else if (isFixtureMode()) (startAddr to startAddr + count - 1)
        else (1 to count)

    /** position → 真实 DMX 通道号（1-based）；组模式返回主灯的真实地址（仅用于回读显示）。 */
    fun dmxChannel(position: Int): Int = startAddr + position

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
    fun refresh() = notifyDataSetChanged()

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
                    val ch = bound + 1   // 灯内通道号（1-based），写入由 MainActivity 分发
                    if (ch >= 1) {
                        onSet(ch, progress)
                        tvVal.text = progress.toString()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            tvVal.setOnClickListener {
                if (bound >= 0) onEditValue(bound + 1)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bound = position
        val v = engine.get(dmxChannel(position))
        holder.binding = true
        holder.tvCh.text = displayName(position)
        holder.seek.progress = v
        holder.tvVal.text = v.toString()
        holder.binding = false
    }

    override fun getItemCount(): Int = count
}
