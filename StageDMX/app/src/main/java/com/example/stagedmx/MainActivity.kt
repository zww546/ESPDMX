package com.example.stagedmx

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.graphics.drawable.GradientDrawable
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.graphics.Typeface
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.stagedmx.databinding.ActivityMainBinding
import com.example.stagedmx.databinding.DialogDevicesBinding
import com.example.stagedmx.databinding.ItemDeviceBinding
import com.example.stagedmx.databinding.ItemFixtureBinding
import com.example.stagedmx.databinding.PageFaderBinding
import com.example.stagedmx.databinding.PageFixturesBinding
import com.example.stagedmx.databinding.PageFixtureEditorBinding
import com.example.stagedmx.databinding.PageFxBinding
import com.example.stagedmx.databinding.PageInstancesBinding
import com.example.stagedmx.databinding.PageProgramBinding
import com.example.stagedmx.databinding.PageStorageBinding

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var fb: PageFaderBinding
    private lateinit var pb: PageProgramBinding
    private lateinit var fixb: PageFixturesBinding
    private lateinit var fxb: PageFxBinding
    private lateinit var edb: PageFixtureEditorBinding
    private lateinit var sdb: PageStorageBinding
    private lateinit var imfb: PageInstancesBinding

    private lateinit var ble: BleManager
    private lateinit var engine: DmxEngine
    private lateinit var steps: StepStore
    private lateinit var fixtureStore: FixtureStore
    private lateinit var channelAdapter: ChannelAdapter

    private lateinit var progAdapter: ArrayAdapter<String>
    private lateinit var stepAdapter: ArrayAdapter<String>

    private var flashSnapshot: IntArray? = null
    private val playingSlots = mutableSetOf<Int>()   // 正在播放的板载程序槽位
    private lateinit var fixtureEditor: FixtureEditor // 灯库编辑器
    private var deviceDialog: AlertDialog? = null
    private var deviceAdapter: DeviceAdapter? = null
    private var fixtureAdapter: FixtureAdapter? = null

    // 多灯实例（Patch）
    private var currentInstanceId: String? = null   // 当前主实例（单实例模式/组模式主灯）
    private val selectedInstanceIds = linkedSetOf<String>()   // 多选：同时控制的实例（按加入顺序，控制时按地址排序）
    private val instanceButtons = mutableListOf<TextView>()

    // 跟随延时（多实例组控制）
    private val followPrefs by lazy { getSharedPreferences("follow_group", MODE_PRIVATE) }
    private var followDelayEnabled: Boolean = false
    private var followDelayMs: Int = 200
    private val followHandler = Handler(Looper.getMainLooper())
    private val followPending = mutableMapOf<String, MutableMap<Int, Int>>()  // instId -> (realCh -> value)
    private var followRunnable: Runnable? = null

    private val channelPresets = listOf(16, 32, 64)

    // 自动连接
    private val autoPrefs by lazy { getSharedPreferences("auto_connect", MODE_PRIVATE) }
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var autoConnectActive = false  // 用户是否开启了自动连接
    private var intentionalDisconnect = false  // 用户主动断开（点按钮/关开关）
    private var lastDeviceMac: String? = null

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) importFixtureZips(uris)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) showDeviceDialog()
        else toast("需要蓝牙权限才能扫描设备")
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (ble.isBluetoothOn()) onConnectClicked() else toast("请打开蓝牙")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 避开系统状态栏/导航栏(沉浸式全面屏)
        val basePad = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(basePad + bars.left, basePad + bars.top, basePad + bars.right, basePad + bars.bottom)
            insets
        }

        ble = BleManager(this).also { it.listener = this }
        engine = DmxEngine(ble)
        steps = StepStore(this)
        fixtureStore = FixtureStore(this)
        fixtureEditor = FixtureEditor(this, fixtureStore)

        // 实例 → 槽位分配器（效果槽 0..3，绑定实例创建时分配的槽位）
        FxEngine.slotAllocator = { instId ->
            fixtureStore.instances().find { it.id == instId }?.slot ?: 0
        }
        // 效果滑条重发需要当前实例 id
        FxEngine.currentInstanceId = { currentInstanceId }

        // App 升级后自动重新解析灯库（versionCode 变化触发）
        val reimportCount = fixtureStore.checkAndReimport(this)
        if (reimportCount > 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                toast("已自动更新 $reimportCount 个灯库")
            }, 1500)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fb = PageFaderBinding.inflate(layoutInflater)
        pb = PageProgramBinding.inflate(layoutInflater)
        fxb = PageFxBinding.inflate(layoutInflater)
        fixb = PageFixturesBinding.inflate(layoutInflater)
        edb = PageFixtureEditorBinding.inflate(layoutInflater)
        sdb = PageStorageBinding.inflate(layoutInflater)
        imfb = PageInstancesBinding.inflate(layoutInflater)

        channelAdapter = ChannelAdapter(
            engine,
            onSet = { chInFixture, value -> setChannelValue(chInFixture, value) },
            onEditValue = { chInFixture -> editValueDialog(chInFixture) }
        )
        fb.rvChannels.layoutManager = LinearLayoutManager(this)
        fb.rvChannels.adapter = channelAdapter

        setupPager()
        wireFaderPage()
        wireProgramPage()
        wireFxPage()
        wireFixturePage()
        wireEditorPage()
        wireStoragePage()
        wireInstanceMgrPage()

        b.btnConnect.setOnClickListener { onConnectClicked() }
        // 实例管理入口
        b.btnInstanceMgr.setOnClickListener {
            refreshInstanceMgrList()
            b.pager.currentItem = 6
        }

        // 自动连接偏好加载
        autoConnectActive = autoPrefs.getBoolean("enabled", false)
        lastDeviceMac = autoPrefs.getString("last_mac", null)

        updateStatusUi(BleManager.State.IDLE, null)

        // 开机自动连接（蓝牙打开 + 有历史设备）
        if (autoConnectActive && !lastDeviceMac.isNullOrEmpty() && ble.isBluetoothOn()) {
            scheduleReconnect(800)
        }
    }

    // ---------------- ViewPager2 四页 + 底部导航 ----------------
    private fun setupPager() {
        val pages = listOf(fb.root, pb.root, fxb.root, fixb.root, sdb.root, edb.root, imfb.root)
        b.pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val fl = FrameLayout(parent.context)
                fl.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                return object : RecyclerView.ViewHolder(fl) {}
            }
            override fun getItemCount() = pages.size
            override fun getItemViewType(pos: Int) = pos
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val page = pages[pos]
                (page.parent as? ViewGroup)?.removeView(page)
                (holder.itemView as FrameLayout).apply { removeAllViews(); addView(page) }
            }
        }
        b.pager.offscreenPageLimit = 5
        b.pager.isUserInputEnabled = false

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_fader -> { b.pager.currentItem = 0; true }
                R.id.nav_program -> { b.pager.currentItem = 1; refreshProgramPage(); true }
                R.id.nav_fx -> { b.pager.currentItem = 2; refreshFxPage(); true }
                R.id.nav_fixture -> { b.pager.currentItem = 3; refreshFixturePage(); true }
                R.id.nav_storage -> { b.pager.currentItem = 4; refreshStoragePage(); true }
                else -> false
            }
        }

        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) {
                if (pos < b.bottomNav.menu.size()) {
                    b.bottomNav.menu.getItem(pos).isChecked = true
                }
                when (pos) {
                    1 -> refreshProgramPage()
                    2 -> refreshFxPage()
                    3 -> refreshFixturePage()
                }
            }
        })
    }

    // ---------------- 推子页 ----------------
    private fun wireFaderPage() {
        setupChannelPresets()

        fb.btnBlackout.setOnClickListener {
            // 实例/组模式下只作用于选中的地址段
            val r = channelAdapter.uniformRange()
            if (r != null) engine.setRangeUniform(r.first, r.second, 0)
            else applyGroupUniform(0)   // 组模式：遍历组内实例地址段
            channelAdapter.refresh()
        }
        fb.btnLocate.isEnabled = fixtureStore.currentFixture != null || fixtureStore.instances().isNotEmpty()
        fb.btnLocate.setOnClickListener {
            // 定位：组模式作用于组内所有实例；否则当前选中实例/当前灯库
            val instId = currentInstanceId
            val inst = instId?.let { fixtureStore.instances().find { i -> i.id == it } }
            val def = if (inst != null) fixtureStore.fixtureOf(inst)
                      else fixtureStore.currentFixture
            if (def == null) {
                toast("请先在灯具页选择一个灯库或添加实例")
                return@setOnClickListener
            }
            val targets = if (channelAdapter.isGroupMode()) groupInstances()
                          else listOfNotNull(inst)
            for (t in targets) {
                val base = t.startAddr
                // 水平/垂直居中
                def.findCh("pan")?.let { engine.set(base + it - 1, 128) }
                def.findCh("tilt")?.let { engine.set(base + it - 1, 128) }
                // 调光拉满
                def.findCh("dim")?.let { engine.set(base + it - 1, 255) }
                // 频闪打开（兼容 Shutter / Strobe 两种命名）
                (def.findCh("shutter") ?: def.findCh("strobe"))?.let { engine.set(base + it - 1, 255) }
                // 第一个 W 通道拉满（兼容 White / W 两种命名）
                (def.findCh("white") ?: def.findCh("w"))?.let { engine.set(base + it - 1, 255) }
            }
            channelAdapter.refresh()
        }
        // Flash 用触摸事件（按住亮、松开恢复）
        fb.btnFlash.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    flashSnapshot = engine.snapshot()
                    val r = channelAdapter.uniformRange()
                    if (r != null) engine.setRangeUniform(r.first, r.second, 255)
                    else applyGroupUniform(255)
                    channelAdapter.refresh()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    flashSnapshot?.let { engine.applyAll(it) }
                    channelAdapter.refresh()
                    v.performClick()
                }
            }
            true
        }
        fb.btnRecord.setOnClickListener { recordStep() }
        renderInstanceBar()
    }

    // ---------- 多灯实例（Patch）----------
    /** 组内实例：选中的实例按 DMX 起始地址排序（组控制用）。 */
    private fun groupInstances(): List<FixtureInstance> =
        selectedInstanceIds.mapNotNull { id -> fixtureStore.instances().find { it.id == id } }
            .sortedBy { it.startAddr }

    /** 渲染顶部实例标签条；无实例时隐藏。 */
    private fun renderInstanceBar() {
        val insts = fixtureStore.instances()
        b.instScroll.visibility = if (insts.isEmpty()) View.GONE else View.VISIBLE
        b.btnInstanceMgr.visibility = if (insts.isEmpty()) View.GONE else View.VISIBLE
        b.instBar.removeAllViews()
        instanceButtons.clear()
        if (insts.isEmpty()) return

        // 清理已删除实例的选择
        selectedInstanceIds.retainAll { id -> insts.any { it.id == id } }
        if (currentInstanceId != null && insts.none { it.id == currentInstanceId }) {
            currentInstanceId = null
        }
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (inst in insts) {
            val tv = TextView(this)
            tv.text = inst.name
            tv.tag = inst.id
            tv.textSize = 13f
            tv.setPadding(pad * 2, pad, pad * 2, pad)
            tv.isSelected = inst.id in selectedInstanceIds
            // 选中用描边区分；禁用 stateListAnimator 防止点击按压变色
            tv.setTextColor(ContextCompat.getColor(this, R.color.text))
            tv.stateListAnimator = null
            tv.background = ContextCompat.getDrawable(this,
                if (tv.isSelected) R.drawable.bg_inst_active else R.drawable.bg_inst)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, pad, 0)
            tv.layoutParams = lp
            tv.setOnClickListener { toggleInstance(inst.id) }
            tv.setOnLongClickListener {
                confirmDeleteInstance(inst)
                true
            }
            b.instBar.addView(tv)
            instanceButtons.add(tv)
        }
        // 有实例时隐藏自定义通道选择（通道数由实例决定）
        b.presetBar.visibility = View.GONE
        b.etChannels.visibility = View.GONE
        applySelectedInstance()
    }

    /** 统一刷新实例标签样式（多选描边，按压不变色）。 */
    private fun refreshInstanceBarStyles() {
        instanceButtons.forEach { tv ->
            val sel = selectedInstanceIds.contains(tv.tag as? String)
            tv.isSelected = sel
            tv.setTextColor(ContextCompat.getColor(this, R.color.text))
            tv.background = ContextCompat.getDrawable(this,
                if (sel) R.drawable.bg_inst_active else R.drawable.bg_inst)
        }
    }

    /** 切换实例：点击切换选中状态（支持多选），全取消回到裸通道。 */
    private fun toggleInstance(id: String) {
        if (!selectedInstanceIds.remove(id)) selectedInstanceIds.add(id)
        // 主灯 = 组内地址最小的一台（用于回读/效果/程序）
        currentInstanceId = groupInstances().firstOrNull()?.id
        refreshInstanceBarStyles()
        if (selectedInstanceIds.isEmpty()) {
            clearFixtureMode()
        } else {
            applySelectedInstance()
        }
        refreshProgramPage()
        refreshFxPage()
    }

    /** 长按实例确认删除。 */
    private fun confirmDeleteInstance(inst: FixtureInstance) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除实例")
            .setMessage("删除实例「${inst.name}」（@${inst.startAddr}）？")
            .setPositiveButton("删除") { _, _ ->
                // 停止该实例的效果和程序（板载槽位释放，槽位绑定不重排）
                FxEngine.stop(inst.id)
                if (playingSlots.remove(inst.slot)) {
                    engine.sendProgStop(inst.slot)
                }
                updatePlayBtnUI()
                fixtureStore.deleteInstance(inst.id)
                selectedInstanceIds.remove(inst.id)
                if (currentInstanceId == inst.id) currentInstanceId = groupInstances().firstOrNull()?.id
                toast("已删除 ${inst.name}")
                renderInstanceBar()
                // 若无剩余实例，回到裸通道模式
                if (fixtureStore.instances().isEmpty()) clearFixtureMode()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 当前实例的板载程序槽位（0..3，实例创建时绑定，删除不重排）。 */
    private fun currentProgSlot(): Int {
        val instId = currentInstanceId ?: return 0
        return fixtureStore.instances().find { it.id == instId }?.slot ?: 0
    }

    /** 把选中实例应用到推子页：单台 = 单实例布局；多台（同灯型）= 组模式。 */
    private fun applySelectedInstance() {
        val insts = groupInstances()
        if (insts.isEmpty()) return
        val first = insts.first()
        val def = fixtureStore.fixtureOf(first) ?: return
        if (insts.size > 1) {
            // 组模式：校验同灯型
            if (insts.any { fixtureStore.fixtureOf(it)?.id != def.id }) {
                toast("组内灯具类型不一致，仅保留第一台")
                selectedInstanceIds.retainAll { it == first.id }
                currentInstanceId = first.id
                refreshInstanceBarStyles()
                applySelectedInstance()
                return
            }
            channelAdapter.applyFixtureGroup(def, insts)
            fb.tvFixtureLabel.text = "${def.name} 组（${insts.size} 台）"
            fb.tvFixtureLabel.visibility = View.VISIBLE
        } else {
            channelAdapter.applyFixture(def, first.startAddr)
            FxEngine.applyFixture(def, first.startAddr)  // 效果按该实例的灯型+地址偏移
            fb.tvFixtureLabel.text = "${first.name} @${first.startAddr} (${def.name}/${def.mode})"
            fb.tvFixtureLabel.visibility = View.VISIBLE
        }
        b.presetBar.visibility = View.GONE
        b.etChannels.visibility = View.GONE
        fb.btnLocate.isEnabled = true
    }

    /**
     * 组控制写入入口（ChannelAdapter 回调）：单实例直接写；组模式遍历组，跟随延时开启时按地址顺序延时。
     * @param chInFixture 灯内通道号（1-based）
     */
    private fun setChannelValue(chInFixture: Int, value: Int) {
        if (channelAdapter.isGroupMode()) {
            groupSet(chInFixture, value)
        } else {
            val inst = currentInstanceId?.let { fixtureStore.instances().find { i -> i.id == it } }
            if (inst != null) engine.set(inst.startAddr + chInFixture - 1, value)
            else engine.set(chInFixture, value)
        }
    }

    /** 组内所有实例的某通道同时写入（跟随延时开启时按地址顺序延时响应）。 */
    private fun groupSet(chInFixture: Int, value: Int) {
        val insts = groupInstances()
        if (insts.isEmpty()) return
        if (!followDelayEnabled || insts.size < 2) {
            for (inst in insts) {
                engine.set(inst.startAddr + chInFixture - 1, value)
            }
            return
        }
        // 延时模式：记录每台该通道的最新值，调度器按地址顺序每台延后 delayMs 发送
        for (inst in insts) {
            val real = inst.startAddr + chInFixture - 1
            followPending.getOrPut(inst.id) { mutableMapOf() }[real] = value
        }
        scheduleFollow()
    }

    /** 跟随延时调度：每 delayMs 推进一台（按地址顺序），发送其最新待写值。 */
    private fun scheduleFollow() {
        val insts = groupInstances()
        if (insts.isEmpty()) { followPending.clear(); return }
        val delayMs = followDelayMs.coerceIn(20, 5000)
        followRunnable?.let { followHandler.removeCallbacks(it) }
        var idx = 0
        val r = object : Runnable {
            override fun run() {
                if (idx >= insts.size) idx = 0
                val inst = insts[idx]
                val pending = followPending.remove(inst.id)
                if (pending != null) {
                    for ((ch, v) in pending) engine.set(ch, v)
                }
                idx++
                // 仍有待写值则继续推进下一台（或回到第一台），形成波浪
                if (followPending.isNotEmpty()) {
                    followHandler.postDelayed(this, delayMs.toLong())
                }
            }
        }
        followRunnable = r
        followHandler.postDelayed(r, 0)
    }

    /** 组模式：组内所有实例的整个地址段统一置值（全黑/Flash）。 */
    private fun applyGroupUniform(value: Int) {
        val chCount = channelAdapter.channelCount()
        for (inst in groupInstances()) {
            engine.setRangeUniform(inst.startAddr, inst.startAddr + chCount - 1, value)
        }
    }

    /** 预设通道按钮组 + 自定义输入框。 */
    private fun setupChannelPresets() {
        b.presetBar.removeAllViews()
        val pad = (6 * resources.displayMetrics.density).toInt()
        for (p in channelPresets) {
            val tv = TextView(this)
            tv.text = p.toString()
            tv.tag = p
            tv.textSize = 12f
            tv.gravity = android.view.Gravity.CENTER
            tv.setPadding(pad, 0, pad, 0)
            tv.setMinWidth((34 * resources.displayMetrics.density).toInt())
            tv.setTextColor(ContextCompat.getColor(this, R.color.text))
            tv.background = ContextCompat.getDrawable(this, R.drawable.bg_pill)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
            lp.setMargins(0, 0, pad, 0)
            tv.layoutParams = lp
            tv.setOnClickListener {
                val v = tv.tag as Int
                if (!channelAdapter.isFixtureMode()) {
                    channelAdapter.setChannelCount(v)
                }
            }
            b.presetBar.addView(tv)
        }
        // 输入框：常驻显示，输入完回车/失焦应用
        b.etChannels.setOnEditorActionListener { _, _, _ -> applyChannelCount(); true }
        b.etChannels.setOnFocusChangeListener { _, has -> if (!has) applyChannelCount() }
    }

    /** 应用灯具后通道数区域显示灯具名 */
    @SuppressLint("NotifyDataSetChanged")
    private fun onFixtureApplied(def: FixtureDef) {
        selectedInstanceIds.clear()
        currentInstanceId = null
        channelAdapter.applyFixture(def)
        FxEngine.applyFixture(def, 1)  // 非实例模式从地址 1 开始
        fb.btnLocate.isEnabled = true
        // 隐藏通道选择器，显示灯具名
        b.presetBar.visibility = View.GONE
        b.etChannels.visibility = View.GONE
        fb.tvFixtureLabel.text = "${def.name} / ${def.mode} (${def.channelCount}CH)"
        fb.tvFixtureLabel.visibility = View.VISIBLE
        // 点灯具标签退出手动模式
        fb.tvFixtureLabel.setOnClickListener { clearFixtureMode() }
    }

    private fun clearFixtureMode() {
        // 回到裸通道：停止所有效果和程序
        FxEngine.stopAll()
        val slot = currentProgSlot()
        if (playingSlots.remove(slot)) {
            engine.sendProgStop(slot)
        }
        updatePlayBtnUI()
        fixtureStore.currentFixtureId = null
        selectedInstanceIds.clear()
        currentInstanceId = null
        channelAdapter.clearFixture()
        fb.btnLocate.isEnabled = false
        // 有实例时通道数由实例决定，不显示自定义通道选择；无实例时预设+输入框都显示
        val showCh = fixtureStore.instances().isEmpty()
        b.presetBar.visibility = if (showCh) View.VISIBLE else View.GONE
        b.etChannels.visibility = if (showCh) View.VISIBLE else View.GONE
        fb.tvFixtureLabel.visibility = View.GONE
        // 回到全局程序列表
        refreshProgramPage()
        refreshFxPage()
    }

    private fun applyChannelCount() {
        val n = b.etChannels.text.toString().toIntOrNull() ?: return
        val c = n.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        b.etChannels.setText(c.toString())
        channelAdapter.setChannelCount(c)
    }

    /**
     * 录制前的快照净化：把复位类通道（attribute 含 reset，如 fixtureglobalreset）清零，
     * 避免程序播放时复位通道值（>128 触发复位）导致灯具复位。
     */
    private fun sanitizeSnapshot(): IntArray {
        val snap = engine.snapshot()
        val insts = if (channelAdapter.isGroupMode()) groupInstances()
                    else listOfNotNull(currentInstanceId?.let { fixtureStore.instances().find { i -> i.id == it } })
        for (inst in insts) {
            val def = fixtureStore.fixtureOf(inst) ?: continue
            for (ch in def.channels) {
                if (ch.attribute.lowercase().contains("reset")) {
                    val real = inst.startAddr + ch.number - 1
                    if (real in 1..DmxProtocol.MAX_CHANNELS) snap[real - 1] = 0
                }
            }
        }
        return snap
    }

    private fun recordStep() {
        val instId = currentInstId()
        val prog = steps.currentProgram
        if (prog == null || !steps.hasProgram(prog, instId)) {
            toast("请到“程序”页新建或选择一个程序")
            b.pager.currentItem = 1
            return
        }
        steps.addStep(prog, instId, steps.defaultTimeMs, sanitizeSnapshot())
        refreshStepsUI()
        toast("已记录 → $prog 第 ${steps.stepCount(prog, instId)} 步")
    }

    // ---- 效果页 ----
    private fun wireFxPage() {
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = FxEngine.presets.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_fx_preset, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val def = FxEngine.presets[pos]
                holder.itemView.findViewById<TextView>(R.id.tvFxName).text = def.name
                holder.itemView.findViewById<TextView>(R.id.tvFxParams).text = def.params.joinToString("+")
                holder.itemView.background.setTint(
                    if (FxEngine.isActive(currentInstanceId) && FxEngine.activeFxId(currentInstanceId) == def.id)
                        ContextCompat.getColor(this@MainActivity, R.color.surface2)
                    else ContextCompat.getColor(this@MainActivity, R.color.surface))
                holder.itemView.setOnClickListener {
                    if (FxEngine.isActive(currentInstanceId) && FxEngine.activeFxId(currentInstanceId) == def.id) {
                        FxEngine.stop(currentInstanceId)
                    } else {
                        if (ble.state != BleManager.State.CONNECTED) {
                            toast("请先连接设备再启动效果")
                            return@setOnClickListener
                        }
                        updateFxChannels()
                        FxEngine.start(engine, def.id, currentInstanceId,
                            amp = FxEngine.getAmplitude(currentInstanceId), speed = FxEngine.getSpeed(currentInstanceId))
                    }
                    refreshFxPage()
                }
            }
        }
        fxb.rvFxPresets.layoutManager = LinearLayoutManager(this)
        fxb.rvFxPresets.adapter = adapter

        // 速度滑条使用对数刻度（SPEED_MIN ~ SPEED_MAX 映射到 0-255，越大越快，与幅度同范围）
        val speedSeekMax = 255
        val speedLogMin = kotlin.math.ln(FxEngine.SPEED_MIN.toDouble())
        val speedLogMax = kotlin.math.ln(FxEngine.SPEED_MAX.toDouble())
        fun seekToSpeed(s: Int) = kotlin.math.exp(speedLogMin + (speedLogMax - speedLogMin) * s / speedSeekMax).toInt()

        fxb.seekAmplitude.max = 255
        fxb.seekAmplitude.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                FxEngine.setAmplitude(p, currentInstanceId); fxb.tvAmplitude.text = "幅度: $p"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        fxb.seekSpeed.max = speedSeekMax
        fxb.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val speed = seekToSpeed(p).coerceIn(FxEngine.SPEED_MIN, FxEngine.SPEED_MAX)
                FxEngine.setSpeed(speed, currentInstanceId)
                fxb.tvSpeed.text = "速度: $p"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        fxb.btnFxStop.setOnClickListener { FxEngine.stop(currentInstanceId); refreshFxPage() }

        refreshFxPage()
    }

    private fun refreshFxPage() {
        val instId = currentInstanceId
        if (FxEngine.isActive(instId)) {
            val def = FxEngine.presets.find { it.id == FxEngine.activeFxId(instId) }
            fxb.tvFxStatus.text = def?.name ?: "运行中"
            fxb.tvFxStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            fxb.btnFxStop.visibility = View.VISIBLE
            fxb.fxParams.visibility = View.VISIBLE
            fxb.seekAmplitude.progress = FxEngine.getAmplitude(instId)
            fxb.tvAmplitude.text = "幅度: ${FxEngine.getAmplitude(instId)}"
            val speed = FxEngine.getSpeed(instId)
            val speedLogMin = kotlin.math.ln(FxEngine.SPEED_MIN.toDouble())
            val speedLogMax = kotlin.math.ln(FxEngine.SPEED_MAX.toDouble())
            val seekPos = ((kotlin.math.ln(speed.toDouble()) - speedLogMin) / (speedLogMax - speedLogMin) * 255).toInt().coerceIn(0, 255)
            fxb.seekSpeed.progress = seekPos
            fxb.tvSpeed.text = "速度: $seekPos"
        } else {
            fxb.tvFxStatus.text = "未启动"
            fxb.tvFxStatus.setTextColor(ContextCompat.getColor(this, R.color.textDim))
            fxb.btnFxStop.visibility = View.GONE
            fxb.fxParams.visibility = View.GONE
        }
        // 刷新列表高亮
        fxb.rvFxPresets.adapter?.notifyDataSetChanged()
    }

    private fun updateFxChannels() {
        // 优先当前选中实例（含地址偏移），否则用当前灯库
        val inst = currentInstanceId?.let { id ->
            fixtureStore.instances().find { it.id == id }
        }
        if (inst != null) {
            fixtureStore.fixtureOf(inst)?.let { FxEngine.applyFixture(it, inst.startAddr) }
        } else {
            fixtureStore.currentFixture?.let { FxEngine.applyFixture(it, 1) }
        }
    }

    private fun editValueDialog(chInFixture: Int) {
        // 读取当前值：组模式读主灯，单实例读该实例地址段，裸通道读全局
        val cur = if (channelAdapter.isGroupMode()) {
            val inst = groupInstances().firstOrNull()
            if (inst != null) engine.get(inst.startAddr + chInFixture - 1) else 0
        } else {
            val inst = currentInstanceId?.let { fixtureStore.instances().find { i -> i.id == it } }
            if (inst != null) engine.get(inst.startAddr + chInFixture - 1) else engine.get(chInFixture)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cur.toString())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("通道 $chInFixture  数值 (0-255)")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = (input.text.toString().toIntOrNull() ?: 0).coerceIn(0, 255)
                setChannelValue(chInFixture, v); channelAdapter.refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 程序页 ----------------
    private fun fmtSeconds(ms: Int): String = "%.1f".format(ms / 1000f)

    /** 当前选中实例 id（无则 null=全局程序）。 */
    private fun currentInstId(): String? = currentInstanceId

    private fun currentProgramSel(): String? =
        steps.programNames(currentInstId()).getOrNull(pb.spProgram.selectedItemPosition)

    private fun refreshStepsUI() {
        val prog = currentProgramSel()
        val s = if (prog != null) steps.steps(prog, currentInstId()) else emptyList()
        stepAdapter.clear()
        stepAdapter.addAll(s.mapIndexed { i, st -> "第${i + 1}步    ${fmtSeconds(st.timeMs)}s" })
        stepAdapter.notifyDataSetChanged()
    }

    private fun reloadProgramsUI(select: String?) {
        val names = steps.programNames(currentInstId())
        progAdapter.clear(); progAdapter.addAll(names); progAdapter.notifyDataSetChanged()
        if (names.isNotEmpty()) {
            val want = select ?: steps.currentProgram
            pb.spProgram.setSelection(names.indexOf(want).let { if (it >= 0) it else 0 })
        }
        refreshStepsUI()
    }

    private fun updatePlayBtnUI() { pb.btnPlay.text = if (playingSlots.contains(currentProgSlot())) "停止" else "播放" }

    private fun refreshProgramPage() { reloadProgramsUI(null); updatePlayBtnUI() }

    /**
     * 把整个程序下发到板子并启动板载循环播放（断连也继续）。
     * 按实例：只上传该实例地址段的稀疏差异，其余通道不动。
     */
    private fun uploadProgramAndPlay(list: List<StepStore.Step>) {
        val slot = currentProgSlot()
        engine.sendProgStop(slot)
        engine.sendProgClear(slot)
        // 作用范围：实例地址段 or 全局 1..512
        val inst = currentInstanceId?.let { id -> fixtureStore.instances().find { it.id == id } }
        val base = inst?.startAddr ?: 1
        val chCount = inst?.let {
            fixtureStore.fixtureOf(it)?.channelCount ?: DmxProtocol.MAX_CHANNELS
        } ?: DmxProtocol.MAX_CHANNELS
        val chLo = base
        val chHi = (base + chCount - 1).coerceAtMost(DmxProtocol.MAX_CHANNELS)
        // 稀疏上传：对比上一步，只发送变化的通道（限本实例地址段）
        var prev = IntArray(DmxProtocol.MAX_CHANNELS)  // 全 0 起点
        for (st in list) {
            val changes = mutableListOf<Pair<Int, Int>>()
            for (ch in chLo..chHi) {
                val v = st.values.getOrNull(ch - 1) ?: 0
                val pv = prev.getOrNull(ch - 1) ?: 0
                if (v != pv) changes.add(ch to v)
            }
            engine.sendProgAppendSparse(slot, st.timeMs, changes)
            prev = st.values.copyOf()
        }
        engine.sendProgPlay(slot, true)
    }

    private fun stepTimeMs(): Int {
        val sec = pb.etStepTime.text.toString().toFloatOrNull() ?: 1.0f
        val ms = (sec * 1000).toInt().coerceIn(50, 600000)
        steps.defaultTimeMs = ms
        return ms
    }

    private fun wireProgramPage() {
        progAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        progAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        pb.spProgram.adapter = progAdapter

        stepAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        pb.lvSteps.adapter = stepAdapter

        pb.etStepTime.setText(fmtSeconds(steps.defaultTimeMs))

        pb.spProgram.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                steps.currentProgram = currentProgramSel()
                refreshStepsUI()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        pb.btnNewProg.setOnClickListener {
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = "程序名称" }
            MaterialAlertDialogBuilder(this)
                .setTitle("新建程序")
                .setView(input)
                .setPositiveButton("创建") { _, _ ->
                    val nm = input.text.toString().trim()
                    if (nm.isEmpty()) { toast("请输入名称"); return@setPositiveButton }
                    if (steps.hasProgram(nm, currentInstId())) { toast("已存在同名程序"); return@setPositiveButton }
                    steps.addProgram(nm, currentInstId()); steps.currentProgram = nm; reloadProgramsUI(nm); toast("已新建：$nm")
                }
                .setNegativeButton("取消", null).show()
        }
        pb.btnDelProg.setOnClickListener {
            val prog = currentProgramSel() ?: run { toast("暂无程序"); return@setOnClickListener }
            MaterialAlertDialogBuilder(this)
                .setMessage("删除程序「$prog」？")
                .setPositiveButton("删除") { _, _ ->
                    val slot = currentProgSlot()
                    if (playingSlots.remove(slot)) { engine.sendProgStop(slot) }
                    steps.deleteProgram(prog, currentInstId())
                    if (steps.currentProgram == prog) steps.currentProgram = steps.programNames(currentInstId()).firstOrNull()
                    reloadProgramsUI(null); updatePlayBtnUI(); toast("已删除")
                }
                .setNegativeButton("取消", null).show()
        }
        pb.btnSaveStep.setOnClickListener {
            val prog = currentProgramSel() ?: run { toast("先新建一个程序"); return@setOnClickListener }
            steps.addStep(prog, currentInstId(), stepTimeMs(), sanitizeSnapshot()); refreshStepsUI()
            toast("已记录第 ${steps.stepCount(prog, currentInstId())} 步")
        }
        pb.btnPlay.setOnClickListener {
            val slot = currentProgSlot()
            if (playingSlots.contains(slot)) {
                playingSlots.remove(slot)
                engine.sendProgStop(slot)
            } else {
                if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备再播放"); return@setOnClickListener }
                val prog = currentProgramSel() ?: run { toast("先新建一个程序"); return@setOnClickListener }
                val s = steps.steps(prog, currentInstId())
                if (s.isEmpty()) { toast("该程序还没有步"); return@setOnClickListener }
                uploadProgramAndPlay(s)
                playingSlots.add(slot)
                toast("已下发到设备播放（App 断连也继续）")
            }
            updatePlayBtnUI()
        }
        pb.btnClearSteps.setOnClickListener {
            val prog = currentProgramSel() ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setMessage("清空「$prog」全部步？")
                .setPositiveButton("清空") { _, _ ->
                    val slot = currentProgSlot()
                    if (playingSlots.remove(slot)) { engine.sendProgStop(slot) }
                    steps.clearSteps(prog, currentInstId()); refreshStepsUI(); updatePlayBtnUI(); toast("已清空")
                }
                .setNegativeButton("取消", null).show()
        }
        pb.lvSteps.setOnItemClickListener { _, _, pos, _ ->
            val prog = currentProgramSel() ?: return@setOnItemClickListener
            val s = steps.steps(prog, currentInstId())
            if (pos in s.indices) {
                // 预览：组模式作用于组内所有实例；单实例只应用其地址段；否则全局
                val targets = if (channelAdapter.isGroupMode()) groupInstances()
                              else listOfNotNull(currentInstanceId?.let { id -> fixtureStore.instances().find { i -> i.id == id } })
                if (targets.isNotEmpty()) {
                    for (inst in targets) {
                        val def = fixtureStore.fixtureOf(inst)
                        val base = inst.startAddr
                        val n = def?.channelCount ?: DmxProtocol.MAX_CHANNELS
                        for (i in 0 until n.coerceAtMost(DmxProtocol.MAX_CHANNELS - base + 1)) {
                            val v = s[pos].values.getOrNull(base + i - 1) ?: 0
                            engine.set(base + i, v)
                        }
                    }
                    channelAdapter.refresh()
                } else {
                    engine.applyAll(s[pos].values); channelAdapter.refresh()
                }
                toast("预览第 ${pos + 1} 步")
            }
        }
        pb.lvSteps.setOnItemLongClickListener { _, _, pos, _ ->
            val prog = currentProgramSel() ?: return@setOnItemLongClickListener true
            MaterialAlertDialogBuilder(this)
                .setMessage("删除第 ${pos + 1} 步？")
                .setPositiveButton("删除") { _, _ -> steps.removeStep(prog, currentInstId(), pos); refreshStepsUI() }
                .setNegativeButton("取消", null).show()
            true
        }
        reloadProgramsUI(null); updatePlayBtnUI()
    }

    // ---------------- 连接流程 ----------------
    private fun onConnectClicked() {
        reconnectHandler.removeCallbacksAndMessages(null)
        val perms = requiredPerms()
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        } else if (!ble.isBluetoothOn()) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            showDeviceDialog()
        }
    }

    /** 延迟尝试连接上一次的设备。 */
    private fun scheduleReconnect(delayMs: Long) {
        reconnectHandler.removeCallbacksAndMessages(null)
        val mac = lastDeviceMac ?: return
        if (!ble.isBluetoothOn()) return
        // 已经连上了就不重连
        if (ble.state == BleManager.State.CONNECTED || ble.state == BleManager.State.CONNECTING) return
        reconnectHandler.postDelayed({
            if (autoConnectActive && !intentionalDisconnect) {
                ble.connectByAddress(mac)
            }
        }, delayMs)
    }

    private fun requiredPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun showDeviceDialog() {
        val db = DialogDevicesBinding.inflate(layoutInflater)
        deviceAdapter = DeviceAdapter(
            onClick = { found ->
                // 点击设备后保持对话框打开，不关闭（连接状态通过 toast/状态点反馈）
                autoPrefs.edit().putString("last_name", found.name).apply()
                if (found.device.address == ble.connectedAddress()) {
                    // 点击已连接设备 → 断开连接
                    intentionalDisconnect = true
                    reconnectHandler.removeCallbacksAndMessages(null)
                    ble.disconnect()
                    toast("已断开 ${found.name}")
                    deviceAdapter?.notifyDataSetChanged()
                } else {
                    intentionalDisconnect = false
                    ble.connect(found.device)
                }
            },
            connectedAddr = { ble.connectedAddress() }
        )
        db.rvDevices.layoutManager = LinearLayoutManager(this)
        db.rvDevices.adapter = deviceAdapter

        // 自动连接开关（对话框内）
        db.swAutoConnect.isChecked = autoConnectActive
        db.swAutoConnect.setOnCheckedChangeListener { _, on ->
            autoConnectActive = on
            autoPrefs.edit().putBoolean("enabled", on).apply()
            if (!on) reconnectHandler.removeCallbacksAndMessages(null)
        }
        // 上次设备名
        val lastName = autoPrefs.getString("last_name", null)
        db.tvLastDevice.text = if (lastName != null) "上次设备：$lastName" else "上次设备：无"

        val connected = ble.state == BleManager.State.CONNECTED
        val title = if (connected) "设备管理" else "选择设备"
        db.tvHint.text = "扫描中…（每隔几秒自动扫描）"

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(db.root)
            .setOnDismissListener { ble.stopPeriodicScan() }

        if (connected) {
            builder.setNegativeButton("断开连接") { _, _ ->
                intentionalDisconnect = true
                reconnectHandler.removeCallbacksAndMessages(null)
                ble.disconnect()
            }
        }

        deviceDialog = builder.create()
        deviceDialog?.show()

        // 打开对话框：已连接设备固定显示在列表顶部（不依赖扫描发现——连接后 Android 可能不再上报其扫描结果）
        deviceAdapter?.clear()
        ble.connectedDevice()?.let { dev ->
            val name = autoPrefs.getString("last_name", null) ?: dev.name ?: dev.address
            deviceAdapter?.add(BleManager.Found(dev, name, 0))
        }
        // 周期扫描：每轮扫 5 秒、停 3 秒后继续，列表持续更新
        ble.startPeriodicScan(durationMs = 5000, pauseMs = 3000)
    }

    // ---------------- BleManager.Listener ----------------
    override fun onScanResult(found: BleManager.Found) {
        deviceAdapter?.add(found)
    }

    override fun onStateChanged(state: BleManager.State, info: String?) {
        updateStatusUi(state, info)
        when (state) {
            BleManager.State.CONNECTED -> {
                toast("已连接")
                engine.sendFullFrame()
                // 记住设备 MAC + 名称供自动重连和显示
                lastDeviceMac = info  // connect 时传了 device.address
                lastDeviceMac?.let { autoPrefs.edit().putString("last_mac", it).apply() }
                intentionalDisconnect = false
                // 若设备对话框打开，刷新列表让已连接设备高亮
                deviceAdapter?.notifyDataSetChanged()
            }
            BleManager.State.DISCONNECTED -> {
                val msg = "连接断开" + (info?.let { " ($it)" } ?: "")
                toast(msg)
                // 若设备对话框打开，刷新列表移除"已连接"高亮
                deviceAdapter?.notifyDataSetChanged()
                if (autoConnectActive && !intentionalDisconnect) {
                    scheduleReconnect(2000)
                }
            }
            else -> {}
        }
    }

    // ---- 设备文件列表 ----
    data class DevFile(val name: String, val size: Int, val isDir: Boolean = false)
    private val devFiles = mutableListOf<DevFile>()
    private var downloadingFile: String? = null
    private var downloadBuf = ByteArray(0)
    private var deletingFile: String? = null
    private var dirOpName: String? = null    // 正在创建/删除的文件夹名
    private var moveOp: String? = null       // 正在移动/复制的条目（"操作: name → dir"）
    private var curPath = ""                 // 文件管理当前设备目录（相对 /fw，空串 = 根）
    private val dirList = mutableListOf<String>()          // 全量目录收集（0x97 帧累加）
    private var dirCollectCb: (() -> Unit)? = null         // 目录收集完成回调（0x98 后触发）
    private var showDeviceFiles = false  // false=App列表, true=设备列表
    private var storageMode = 0          // 存储页签：0=App灯库 1=设备灯库 2=文件管理

    override fun onNotify(data: ByteArray) {
        when {
            data.size >= 2 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_UPLOAD_RESULT -> {
                val ok = data[1].toInt() == 0
                toast(if (ok) "上传完成" else "上传失败")
            }
            data.size >= 2 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_FILE_LIST -> {
                // 帧: 0x92 count [nameLen name type sizeHi sizeLo]*  (type: 1=目录 0=文件)
                val cnt = data[1].toInt()
                var p = 2
                for (i in 0 until cnt) {
                    if (p >= data.size) break
                    val nl = data[p++].toInt()
                    if (nl <= 0 || p + nl + 3 > data.size) break
                    val name = String(data, p, nl, Charsets.UTF_8)
                    p += nl
                    val isDir = data[p++].toInt() != 0
                    val size = ((data[p].toInt() and 0xFF) shl 8) or (data[p+1].toInt() and 0xFF)
                    p += 2
                    devFiles.add(DevFile(name, size, isDir))
                }
                runOnUiThread { refreshDeviceFilesUI() }
            }
            data.size >= 2 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_DELETE_RESULT -> {
                val ok = data[1].toInt() == 0
                val name = deletingFile
                runOnUiThread {
                    if (ok) {
                        toast("已删除 ${name ?: "文件"}")
                        // 从本地列表移除并刷新
                        if (name != null) devFiles.removeAll { it.name == name }
                        refreshDeviceFilesUI()
                    } else {
                        toast("删除失败${name?.let { "：$it" } ?: ""}")
                    }
                }
                deletingFile = null
            }
            data.size >= 2 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_DIR_RESULT -> {
                val ok = data[1].toInt() == 0
                val name = dirOpName
                val mv = moveOp
                runOnUiThread {
                    if (mv != null) {
                        toast(if (ok) "$mv 成功" else "$mv 失败")
                    } else {
                        toast(if (ok) "文件夹操作成功${name?.let { "：$it" } ?: ""}" else "文件夹操作失败${name?.let { "：$it" } ?: ""}")
                    }
                    if (ok) refreshDeviceFiles()
                }
                dirOpName = null
                moveOp = null
            }
            data.size >= 2 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_DIRS_LIST -> {
                // 全量目录收集帧: 0x97 count [dirLen dir…]*
                val cnt = data[1].toInt()
                var p = 2
                for (i in 0 until cnt) {
                    if (p >= data.size) break
                    val dl = data[p++].toInt()
                    if (dl <= 0 || p + dl > data.size) break
                    dirList.add(String(data, p, dl, Charsets.UTF_8))
                    p += dl
                }
            }
            data.size >= 2 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_DIRS_END -> {
                val cb = dirCollectCb
                dirCollectCb = null
                runOnUiThread { cb?.invoke() }
            }
            data.size >= 4 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_FILE_CHUNK -> {
                val dataLen = data[3].toInt() and 0xFF
                if (data.size >= 4 + dataLen) {
                    downloadBuf += data.copyOfRange(4, 4 + dataLen)
                }
            }
            data.size >= 2 && (data[0].toInt() and 0xFF) == DmxProtocol.RESP_FILE_END -> {
                val ok = data[1].toInt() == 0
                val name = downloadingFile ?: "device_fixture.xml"
                if (ok && downloadBuf.isNotEmpty()) {
                    val imported = fixtureStore.importFile(downloadBuf.inputStream(), name)
                    runOnUiThread {
                        if (imported.isNotEmpty()) {
                            toast("从设备导入 ${imported.size} 个灯具")
                            refreshFixturePage()
                        } else {
                            toast("未能解析灯库")
                        }
                    }
                } else {
                    runOnUiThread { toast(if (ok) "下载完成(nodata)" else "设备无此文件") }
                }
                downloadingFile = null
                downloadBuf = ByteArray(0)
            }
        }
    }

    private fun updateStatusUi(state: BleManager.State, info: String?) {
        // 只显示状态点颜色，不显示文字/MAC（状态点颜色区分）
        val colorRes = when (state) {
            BleManager.State.CONNECTED -> R.color.ok
            BleManager.State.CONNECTING -> R.color.connecting
            BleManager.State.SCANNING -> R.color.connecting
            else -> R.color.err
        }
        b.statusDot.background.setTint(ContextCompat.getColor(this, colorRes))
        b.btnConnect.text = "设备"
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectHandler.removeCallbacksAndMessages(null)
        followHandler.removeCallbacksAndMessages(null)
        ble.close()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- 设备列表适配器 ----------------
    private class DeviceAdapter(
        val onClick: (BleManager.Found) -> Unit,
        val connectedAddr: () -> String?
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {
        private val items = mutableListOf<BleManager.Found>()

        @SuppressLint("NotifyDataSetChanged")
        fun clear() { items.clear(); notifyDataSetChanged() }

        fun add(f: BleManager.Found) {
            val i = items.indexOfFirst { it.device.address == f.device.address }
            if (i >= 0) { items[i] = f; notifyItemChanged(i) }
            else { items.add(f); notifyItemInserted(items.size - 1) }
        }

        class VH(val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(vb)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            val isCurrent = f.device.address == connectedAddr()
            if (isCurrent) {
                holder.b.tvName.text = "✓ ${f.name}（已连接）"
                holder.b.tvName.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.ok))
            } else {
                holder.b.tvName.text = f.name
                holder.b.tvName.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text))
            }
            holder.b.tvAddr.text = f.device.address
            holder.b.tvRssi.text = if (f.rssi == 0) "--" else "${f.rssi} dBm"
            holder.b.root.setOnClickListener { onClick(f) }
        }

        override fun getItemCount() = items.size
    }

    // ---------------- 灯具管理 ----------------
    private fun wireFixturePage() {
        fixtureAdapter = FixtureAdapter(
            onApply = { def ->
                // 切换灯库：先停止当前选中实例的效果和程序
                currentInstanceId?.let { cid ->
                    val ci = fixtureStore.instances().find { i -> i.id == cid }
                    if (ci != null) {
                        FxEngine.stop(ci.id)
                        if (playingSlots.remove(ci.slot)) {
                            engine.sendProgStop(ci.slot)
                        }
                        updatePlayBtnUI()
                    }
                }
                fixtureStore.currentFixtureId = def.id
                channelAdapter.translated = true
                // 找该灯型已有实例；没有则自动添加一个（默认起始通道 1）
                var inst = fixtureStore.instances().find { it.fixtureId == def.id }
                if (inst == null) {
                    val err = fixtureStore.addInstance(def.id, def.name, 1)
                    if (err == null) {
                        inst = fixtureStore.instances().find { it.fixtureId == def.id }
                    } else {
                        toast(err)
                    }
                }
                if (inst != null) {
                    // 应用灯库：选中该实例（重置多选集合），渲染实例栏
                    selectedInstanceIds.clear()
                    selectedInstanceIds.add(inst.id)
                    currentInstanceId = inst.id
                    renderInstanceBar()
                } else {
                    onFixtureApplied(def)
                }
                b.pager.currentItem = 0
                toast("已应用: ${def.name}" + if (inst != null) "（实例 @${inst.startAddr}）" else "")
            },
            onToggleLang = { def, translated ->
                // 无条件切换推子页通道名语言（点哪个灯的按钮就切换当前显示的灯）
                channelAdapter.translated = translated
                channelAdapter.refresh()
            },
            onSelectModeChanged = { on ->
                selectMode = on
                fixb.batchBar.visibility = if (on) View.VISIBLE else View.GONE
                fixb.btnEdit.text = if (on) "取消" else "编辑灯库"
                if (!on) updateBatchBar()
            }
        )
        fixb.rvFixtures.layoutManager = LinearLayoutManager(this)
        fixb.rvFixtures.adapter = fixtureAdapter

        fixb.btnImport.setOnClickListener {
            importFileLauncher.launch("*/*")
        }

        // 格式筛选标签：XML / D4 / R20
        fixb.btnFmtXml.setOnClickListener { setFixtureFmt("xml") }
        fixb.btnFmtD4.setOnClickListener { setFixtureFmt("d4") }
        fixb.btnFmtR20.setOnClickListener { setFixtureFmt("r20") }

        fixb.btnAddInstance.setOnClickListener { showAddInstanceDialog() }

        fixb.btnEdit.setOnClickListener {
            if (selectMode) {
                toggleSelectMode()
            } else {
                b.pager.currentItem = 5  // 编辑器页（页5，不在底部导航中）
            }
        }

        fixb.btnSelectAll.setOnClickListener {
            fixtureAdapter?.toggleAll()
            updateBatchBar()
        }

        fixb.btnDeleteSelected.setOnClickListener {
            val selected = fixtureAdapter?.getSelectedIds() ?: emptySet()
            if (selected.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setMessage("删除选中的 ${selected.size} 个灯具？")
                .setPositiveButton("删除") { _, _ ->
                    selected.forEach { fixtureStore.delete(it) }
                    refreshFixturePage()
                    toast("已删除 ${selected.size} 个")
                }
                .setNegativeButton("取消", null).show()
        }

        refreshFixturePage()
    }

    private var selectMode = false
    private var fixtureFmt = "xml"          // 灯具页当前格式筛选：xml / d4 / r20

    /** 切换灯具格式筛选并刷新列表。 */
    private fun setFixtureFmt(fmt: String) {
        fixtureFmt = fmt
        updateFmtTabs()
        refreshFixturePage()
    }

    /** 灯具页格式标签（XML/D4/R20）统一风格 + 选中提示。
     *  选中 = 空心蓝圈 + 白粗体字；未选中 = 空心白圈 + 白字。 */
    private fun updateFmtTabs() {
        fun setTab(btn: Button, active: Boolean) {
            btn.setBackgroundResource(if (active) R.drawable.bg_pill_outline_accent else R.drawable.bg_pill_outline_white)
            btn.setTextColor(getColor(R.color.text))
            btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        setTab(fixb.btnFmtXml, fixtureFmt == "xml")
        setTab(fixb.btnFmtD4, fixtureFmt == "d4")
        setTab(fixb.btnFmtR20, fixtureFmt == "r20")
    }

    private fun toggleSelectMode() {
        selectMode = !selectMode
        fixtureAdapter?.setSelectMode(selectMode)
        updateBatchBar()
    }

    private fun updateBatchBar() {
        if (!selectMode) return
        val count = fixtureAdapter?.getSelectedCount() ?: 0
        fixb.tvSelectedCount.text = "已选 $count"
        fixb.btnDeleteSelected.isEnabled = count > 0
    }

    private fun refreshFixturePage() {
        val def = fixtureStore.currentFixture
        fixb.tvCurrentFixture.text = def?.let { "${it.name} / ${it.mode}" } ?: "无"
        fixtureAdapter?.submitList(fixtureStore.fixturesOfFormat(fixtureFmt))
        updateFmtTabs()
        renderInstanceBar()
    }

    private fun importFixtureZips(uris: List<Uri>) {
        var total = 0
        for (uri in uris) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: continue
                // 取显示文件名（用于单文件 XML/D4/R20 分派）
                var name = ""
                try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: ""
                    }
                } catch (_: Exception) {}
                if (name.isEmpty()) name = uri.lastPathSegment ?: ""
                total += fixtureStore.importFile(stream, name).size
            } catch (e: Exception) {
                toast("导入失败: ${e.message}")
                e.printStackTrace()
            }
        }
        if (total > 0) {
            toast("导入了 $total 个灯具")
        } else {
            toast("未找到可识别的灯库文件")
        }
        refreshFixturePage()
    }

    /** 添加灯具实例：选灯型 → 名称 + DMX 起始地址。 */
    private fun showAddInstanceDialog() {
        val defs = fixtureStore.fixtures
        if (defs.isEmpty()) {
            toast("请先导入灯库")
            return
        }
        val names = defs.map { "${it.manufacturer} ${it.name} (${it.mode}) ${it.channelCount}CH" }.toTypedArray()
        val holder = layoutInflater.inflate(R.layout.dialog_add_instance, null)
        val spType = holder.findViewById<Spinner>(R.id.spInstType)
        val etName = holder.findViewById<EditText>(R.id.etInstName)
        val etAddr = holder.findViewById<EditText>(R.id.etInstAddr)
        spType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        // 默认名称 = 灯型名（选择灯型时自动更新，用户改过则保留）
        var nameEdited = false
        etName.setText(defs[0].name)
        etName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s?.toString() != defs[spType.selectedItemPosition].name) nameEdited = true
            }
        })
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!nameEdited) etName.setText(defs[pos].name)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        etAddr.setText("1")
        MaterialAlertDialogBuilder(this)
            .setTitle("添加灯具实例")
            .setView(holder)
            .setPositiveButton("添加") { _, _ ->
                val idx = spType.selectedItemPosition
                val def = defs[idx]
                var name = etName.text.toString().trim()
                if (name.isEmpty()) name = def.name
                val addr = etAddr.text.toString().toIntOrNull() ?: 1
                // 同型号自动编号：EOS-1, EOS-2...
                val base = name
                val exists = fixtureStore.instances().count {
                    fixtureStore.fixtureOf(it)?.id == def.id && it.name.startsWith(base)
                }
                val finalName = if (exists > 0) "$base-${exists + 1}" else "$base-1"
                val err = fixtureStore.addInstance(def.id, finalName, addr)
                if (err != null) {
                    toast(err)
                } else {
                    toast("已添加 $finalName @$addr")
                    refreshFixturePage()
                    renderInstanceBar()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 灯具列表适配器 ----------------
    private class FixtureAdapter(
        private val onApply: (FixtureDef) -> Unit,
        private val onToggleLang: (FixtureDef, Boolean) -> Unit,
        private val onSelectModeChanged: (Boolean) -> Unit = {}
    ) : RecyclerView.Adapter<FixtureAdapter.VH>() {
        private var items = listOf<FixtureDef>()
        private val translatedMap = mutableMapOf<String, Boolean>()
        private val selectedIds = mutableSetOf<String>()
        private var selectMode = false

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(list: List<FixtureDef>) {
            items = list
            selectedIds.clear()
            notifyDataSetChanged()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun setSelectMode(on: Boolean) {
            selectMode = on
            if (!on) selectedIds.clear()
            notifyDataSetChanged()
            onSelectModeChanged(on)
        }

        fun toggleAll() {
            if (selectedIds.size == items.size) selectedIds.clear()
            else items.forEach { selectedIds.add(it.id) }
            notifyDataSetChanged()
        }

        fun getSelectedIds(): Set<String> = selectedIds.toSet()
        fun getSelectedCount(): Int = selectedIds.size

        class VH(val b: ItemFixtureBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemFixtureBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(vb)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val def = items[position]
            val translated = translatedMap[def.id] ?: true
            holder.b.tvFixtureName.text = "${def.manufacturer} ${def.name}"
            holder.b.tvFixtureMode.text = def.mode
            holder.b.tvFixtureChannels.text = "${def.channelCount} CH"
            holder.b.btnToggleLang.text = if (translated) "中" else "EN"
            holder.b.btnToggleLang.setOnClickListener {
                val current = translatedMap[def.id] ?: true
                val newVal = !current
                translatedMap[def.id] = newVal
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                onToggleLang(def, newVal)
            }

            // 选择模式
            holder.b.cbSelect.visibility = if (selectMode) View.VISIBLE else View.GONE
            holder.b.cbSelect.isChecked = selectedIds.contains(def.id)
            holder.b.cbSelect.setOnCheckedChangeListener(null)
            holder.b.cbSelect.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedIds.add(def.id) else selectedIds.remove(def.id)
            }

            holder.b.root.setOnClickListener {
                if (selectMode) {
                    holder.b.cbSelect.toggle()
                } else {
                    onApply(def)
                }
            }
            holder.b.root.setOnLongClickListener {
                if (!selectMode) {
                    // 长按进入选择模式并选中此项
                    setSelectMode(true)
                    selectedIds.add(def.id)
                    notifyDataSetChanged()
                }
                true
            }
        }

        override fun getItemCount() = items.size
    }

    // ---------------- 灯库编辑器 --------------
    private var editorFixtureId: String? = null  // 正在编辑的灯型 id（null = 新建）

    private fun wireEditorPage() {
        lateinit var onChanged: () -> Unit
        onChanged = {
            fixtureEditor.renderChannels(edb.chList, onChanged)
            edb.tvChCount.text = "${fixtureEditor.channels.size} 通道"
        }

        edb.btnAddCh.setOnClickListener {
            val nextNum = (fixtureEditor.channels.maxOfOrNull { it.number } ?: 0) + 1
            fixtureEditor.channels.add(FixtureEditor.ChData(number = nextNum))
            onChanged()
        }

        // 编辑已有灯库
        edb.btnImportEdit.setOnClickListener {
            val defs = fixtureStore.fixtures
            if (defs.isEmpty()) { toast("没有已导入的灯库"); return@setOnClickListener }
            val names = defs.map { "${it.name} / ${it.mode} (${it.channelCount}CH)" }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("选择灯库编辑")
                .setItems(names) { _, idx ->
                    val def = defs[idx]
                    editorFixtureId = def.id
                    edb.etFixName.setText(def.name)
                    edb.etFixManu.setText(def.manufacturer)
                    edb.etFixMode.setText(def.mode)
                    if (def.panRange > 0) edb.etPanRange.setText("${def.panRange}")
                    if (def.tiltRange > 0) edb.etTiltRange.setText("${def.tiltRange}")
                    fixtureEditor.loadFixture(def)
                    onChanged()
                }
                .show()
        }

        // 导出 ZIP
        edb.btnExportZip.setOnClickListener {
            val name = edb.etFixName.text.toString().trim()
            if (name.isEmpty()) { toast("请输入灯型名称"); return@setOnClickListener }
            val manu = edb.etFixManu.text.toString().trim().ifEmpty { "Unknown" }
            val mode = edb.etFixMode.text.toString().trim().ifEmpty { "1ch" }
            val pan = edb.etPanRange.text.toString().toFloatOrNull() ?: 0f
            val tilt = edb.etTiltRange.text.toString().toFloatOrNull() ?: 0f
            if (fixtureEditor.channels.isEmpty()) { toast("请至少添加一个通道"); return@setOnClickListener }

            val def = fixtureEditor.buildFixture(name, manu, mode, pan, tilt)
            // 保存到内部存储（后续可导入到灯具列表）
            val xml = fixtureEditor.buildMa2Xml(def)
            fixtureEditor.saveToStore(def, xml.toByteArray(Charsets.UTF_8))
            // 刷新灯具页
            refreshFixturePage()
            // 导出分享
            fixtureEditor.exportZip(def, contentResolver)
            toast("已导出 ${name}")
        }
    }

    // ---------------- 文件管理 ----------------
    // ---------------- 实例管理页 ----------------
    private fun wireInstanceMgrPage() {
        followDelayEnabled = followPrefs.getBoolean("enabled", false)
        followDelayMs = followPrefs.getInt("delay_ms", 200)

        imfb.swFollowDelay.isChecked = followDelayEnabled
        imfb.etFollowDelay.setText(followDelayMs.toString())

        imfb.btnBackInstances.setOnClickListener { b.pager.currentItem = 0 }

        imfb.swFollowDelay.setOnCheckedChangeListener { _, on ->
            followDelayEnabled = on
            followPrefs.edit().putBoolean("enabled", on).apply()
            if (!on) {
                followHandler.removeCallbacksAndMessages(null)
                followPending.clear()
            }
        }
        imfb.etFollowDelay.setOnEditorActionListener { _, _, _ -> saveFollowDelay(); true }
        imfb.etFollowDelay.setOnFocusChangeListener { _, has -> if (!has) saveFollowDelay() }

        imfb.rvInstanceMgr.layoutManager = LinearLayoutManager(this)
    }

    private fun saveFollowDelay() {
        val ms = imfb.etFollowDelay.text.toString().toIntOrNull() ?: return
        followDelayMs = ms.coerceIn(20, 5000)
        imfb.etFollowDelay.setText(followDelayMs.toString())
        followPrefs.edit().putInt("delay_ms", followDelayMs).apply()
    }

    /** 刷新实例管理页列表（按 DMX 地址排序，勾选 = 参与同时控制）。 */
    private fun refreshInstanceMgrList() {
        val insts = fixtureStore.instances().sortedBy { it.startAddr }
        imfb.rvInstanceMgr.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = insts.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_instance_mgr, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val inst = insts[pos]
                val def = fixtureStore.fixtureOf(inst)
                val root = holder.itemView
                val cb = root.findViewById<CheckBox>(R.id.cbInstSel)
                val tvName = root.findViewById<TextView>(R.id.tvInstName)
                val tvInfo = root.findViewById<TextView>(R.id.tvInstInfo)
                tvName.text = inst.name
                tvInfo.text = "@${inst.startAddr}  ${def?.channelCount ?: 0}CH  ${def?.name ?: ""}"
                cb.isChecked = inst.id in selectedInstanceIds
                cb.setOnCheckedChangeListener(null)
                cb.setOnCheckedChangeListener { _, on ->
                    if (on) selectedInstanceIds.add(inst.id)
                    else selectedInstanceIds.remove(inst.id)
                    currentInstanceId = groupInstances().firstOrNull()?.id
                    if (selectedInstanceIds.isNotEmpty()) {
                        applySelectedInstance()
                        refreshInstanceBarStyles()
                    }
                    refreshProgramPage()
                    refreshFxPage()
                }
                root.setOnClickListener { cb.toggle() }
            }
        }
    }

    private fun wireStoragePage() {
        var mscEnabled = false
        sdb.swMsc.setOnClickListener {
            if (!mscEnabled) {
                if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
                engine.sendRawCmd(0x30, 1)
                mscEnabled = true
                sdb.swMsc.text = "关闭"
                sdb.swMsc.setBackgroundColor(getColor(R.color.ok))
                sdb.tvMscStatus.text = "已开启 — 控台可访问 ESP32 灯库文件"
                sdb.tvMscStatus.setTextColor(getColor(R.color.ok))
            } else {
                engine.sendRawCmd(0x30, 0)
                mscEnabled = false
                sdb.swMsc.text = "开启"
                sdb.swMsc.setBackgroundColor(getColor(R.color.surface2))
                sdb.tvMscStatus.text = "关闭"
                sdb.tvMscStatus.setTextColor(getColor(R.color.textDim))
            }
        }

        // 三个同级页签：App灯库 / 设备灯库 / 文件管理
        sdb.btnAppLibs.setOnClickListener {
            storageMode = 0
            sdb.btnNewFolder.visibility = View.GONE
            refreshStoragePage()
        }

        sdb.btnDeviceLibs.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
            storageMode = 1
            curPath = ""            // 设备灯库固定显示根目录
            sdb.btnNewFolder.visibility = View.GONE
            refreshDeviceFiles()
        }

        sdb.btnFileMgr.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
            storageMode = 2
            sdb.btnNewFolder.visibility = View.VISIBLE
            refreshDeviceFiles()
        }

        // 返回上级目录（文件管理页签）
        sdb.btnGoUp.setOnClickListener {
            if (storageMode != 2) return@setOnClickListener
            if (curPath.isEmpty()) { toast("已在根目录"); return@setOnClickListener }
            curPath = curPath.substringBeforeLast('/', "")
            refreshDeviceFiles()
        }

        // 新建文件夹
        sdb.btnNewFolder.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "文件夹名（不含 / 或 \\）"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("新建文件夹")
                .setView(input)
                .setPositiveButton("创建") { _, _ ->
                    val nm = input.text.toString().trim()
                    if (nm.isEmpty() || nm.contains('/') || nm.contains('\\')) {
                        toast("名称无效")
                        return@setPositiveButton
                    }
                    dirOpName = nm
                    engine.sendMkdir(curPath, nm)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        refreshStoragePage()
    }

    /** 重新拉取设备文件列表。 */
    private fun refreshDeviceFiles() {
        if (ble.state != BleManager.State.CONNECTED) return
        devFiles.clear()
        showDeviceFiles = true
        refreshStoragePage()
        sdb.tvListTitle.text = if (storageMode == 2) "ESP32 文件管理" else "ESP32 设备灯库"
        sdb.tvListHint.text = "正在获取..."
        engine.sendListFiles(curPath)
    }

    /** 确认删除设备文件夹（空文件夹）。 */
    private fun rmdirConfirm(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除文件夹")
            .setMessage("删除设备上的空文件夹「$name」？（文件夹非空将失败）")
            .setPositiveButton("删除") { _, _ ->
                dirOpName = name
                engine.sendRmdir(curPath, name)
            }
            .setNegativeButton("取消", null).show()
    }

    /** 重命名设备文件/文件夹。 */
    private fun renameDialog(oldName: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(oldName)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setMessage("原名：$oldName")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val nm = input.text.toString().trim()
                if (nm.isEmpty() || nm.contains('/') || nm.contains('\\')) {
                    toast("名称无效")
                    return@setPositiveButton
                }
                dirOpName = nm
                engine.sendRename(curPath, oldName, nm)
            }
            .setNegativeButton("取消", null).show()
    }

    /** 移动/复制目标选择：先请求设备全量目录树（0x3C），收集完（0x98）后弹窗选择。 */
    private fun pickDestDialog(name: String, isCopy: Boolean) {
        if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return }
        dirList.clear()
        dirCollectCb = {
            val act = if (isCopy) "复制" else "移动"
            val opts = mutableListOf("（根目录）")
            opts.addAll(dirList)
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("$act「$name」到")
                .setItems(opts.toTypedArray()) { _, which ->
                    val dst = if (which == 0) "" else opts[which]
                    if (dst == curPath) { toast("目标与当前位置相同"); return@setItems }
                    val dstShow = if (dst.isEmpty()) "根目录" else dst
                    moveOp = "$act: $name → $dstShow"
                    if (isCopy) engine.sendCopy(curPath, name, dst)
                    else        engine.sendMove(curPath, name, dst)
                    toast("正在${act}到 $dstShow...")
                }
                .setNegativeButton("取消", null)
                .show()
        }
        engine.sendListDirs()
        toast("正在获取设备目录...")
    }

    private fun moveDialog(name: String) = pickDestDialog(name, false)
    private fun copyDialog(name: String) = pickDestDialog(name, true)

    private fun refreshDeviceFilesUI() {
        showDeviceFiles = true
        sdb.btnNewFolder.visibility = if (storageMode == 2) View.VISIBLE else View.GONE
        sdb.tvListTitle.text = if (storageMode == 2) "ESP32 文件管理" else "ESP32 设备灯库"
        refreshStoragePage()
    }

    private fun refreshStoragePage() {
        updateStorageTabs()
        // 0=App灯库(本地列表) 1=设备灯库 2=文件管理：统一按 storageMode 决定显示哪边，
        // 否则从设备页切回 App 灯库时 showDeviceFiles 残留 true 导致本地灯库不显示
        showDeviceFiles = (storageMode != 0)
        // 设备灯库页只显示灯库文件，文件夹仅出现在文件管理页
        val shownDev = if (storageMode == 1) devFiles.filter { !it.isDir } else devFiles
        val libs = fixtureStore.fixtures
        val ctx = this
        sdb.rvFileList.layoutManager = LinearLayoutManager(ctx)
        sdb.rvFileList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (showDeviceFiles) shownDev.size else libs.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file_row, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val root = holder.itemView
                val tvName = root.findViewById<TextView>(R.id.tvFileName)
                val tvInfo = root.findViewById<TextView>(R.id.tvFileInfo)
                val btn = root.findViewById<Button>(R.id.btnAction)
                if (showDeviceFiles) {
                    val f = shownDev[pos]
                    if (f.isDir) {
                        tvName.text = "📁 ${f.name}"
                        tvInfo.text = "文件夹"
                        btn.text = "删除"
                        btn.setOnClickListener {
                            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
                            rmdirConfirm(f.name)
                        }
                        // 文件管理页签：点按进入文件夹
                        root.setOnClickListener {
                            if (storageMode != 2) return@setOnClickListener
                            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
                            curPath = if (curPath.isEmpty()) f.name else "$curPath/${f.name}"
                            refreshDeviceFiles()
                        }
                        // 长按文件夹 → 重命名 / 移动 / 删除（仅文件管理页签）
                        root.setOnLongClickListener {
                            if (storageMode != 2) return@setOnLongClickListener true
                            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnLongClickListener true }
                            val opts = arrayOf("重命名", "移动", "删除")
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle(f.name)
                                .setItems(opts) { _, which ->
                                    when (which) {
                                        0 -> renameDialog(f.name)
                                        1 -> moveDialog(f.name)
                                        else -> rmdirConfirm(f.name)
                                    }
                                }
                                .show()
                            true
                        }
                    } else {
                        tvName.text = f.name
                        tvInfo.text = "${f.size} 字节"
                        btn.text = "下载"
                        btn.setOnClickListener {
                            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
                            downloadingFile = f.name
                            downloadBuf = ByteArray(0)
                            engine.sendDownloadFile(curPath, f.name)
                            toast("正在下载 ${f.name}...")
                        }
                        // 长按设备文件：文件管理页签 → 重命名/移动/复制/删除
                        root.setOnLongClickListener {
                            if (ble.state != BleManager.State.CONNECTED) {
                                toast("请先连接设备")
                                return@setOnLongClickListener true
                            }
                            if (storageMode == 2) {
                                val opts = arrayOf("重命名", "移动", "复制", "删除")
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(f.name)
                                    .setItems(opts) { _, which ->
                                        when (which) {
                                            0 -> renameDialog(f.name)
                                            1 -> moveDialog(f.name)
                                            2 -> copyDialog(f.name)
                                            else -> {
                                                deletingFile = f.name
                                                engine.sendDeleteFile(curPath, f.name)
                                                toast("正在删除 ${f.name}...")
                                            }
                                        }
                                    }
                                    .show()
                            } else {
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle("删除文件")
                                    .setMessage("删除设备上的文件「${f.name}」？")
                                    .setPositiveButton("删除") { _, _ ->
                                        deletingFile = f.name
                                        engine.sendDeleteFile(curPath, f.name)
                                        toast("正在删除 ${f.name}...")
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            true
                        }
                    }
                } else {
                    val lib = libs[pos]
                    tvName.text = "${lib.manufacturer} ${lib.name}"
                    tvInfo.text = "${lib.mode}  ${lib.channelCount}CH"
                    btn.text = "上传"
                    root.setOnLongClickListener(null)
                    btn.setOnClickListener {
                        if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
                        // 优先上传保存的原始文件（xml/d4/r20 三种）；无原始文件则重建 xml
                        val raws = fixtureStore.rawFiles(lib)
                        if (raws.isNotEmpty()) {
                            uploadFilesSequential(raws, lib.name)
                        } else {
                            val xml = fixtureEditor.buildMa2Xml(lib)
                            uploadFileData("${lib.id}.xml", xml.toByteArray(Charsets.UTF_8), lib.name)
                        }
                    }
                }
            }
        }
        if (showDeviceFiles) {
            sdb.btnNewFolder.visibility = if (storageMode == 2) View.VISIBLE else View.GONE
            sdb.tvListTitle.text = if (storageMode == 2) {
                val p = if (curPath.isEmpty()) "/fw" else "/fw/$curPath"
                "ESP32 文件管理  $p"
            } else "ESP32 设备灯库"
            sdb.btnGoUp.visibility = if (storageMode == 2 && curPath.isNotEmpty()) View.VISIBLE else View.GONE
            sdb.tvListHint.text = when {
                shownDev.isEmpty() && storageMode == 1 -> "设备上暂无灯库，请先上传"
                shownDev.isEmpty() -> "设备上暂无文件"
                storageMode == 1 -> "${shownDev.size} 个灯库 — 点按下载到 App"
                else -> "${shownDev.size} 个条目 — 点文件夹进入，长按操作"
            }
        } else {
            sdb.btnNewFolder.visibility = View.GONE
            sdb.btnGoUp.visibility = View.GONE
            sdb.tvListTitle.text = "App 端已保存灯库"
            sdb.tvListHint.text = if (libs.isEmpty()) "暂无灯库" else "${libs.size} 个 — 点按上传到设备"
        }
    }

    /** 存储页三个标签（App灯库/设备灯库/文件管理）统一风格 + 选中提示。
     *  选中 = 空心蓝圈 + 白粗体字；未选中 = 空心白圈 + 白字。 */
    private fun updateStorageTabs() {
        fun setTab(btn: Button, active: Boolean) {
            btn.setBackgroundResource(if (active) R.drawable.bg_pill_outline_accent else R.drawable.bg_pill_outline_white)
            btn.setTextColor(getColor(R.color.text))
            btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        setTab(sdb.btnAppLibs, storageMode == 0)
        setTab(sdb.btnDeviceLibs, storageMode == 1)
        setTab(sdb.btnFileMgr, storageMode == 2)
    }

    /** 上传单个文件数据（分块 200B，间隔 50ms；上传到当前目录 curPath）。 */
    private fun uploadFileData(fileName: String, data: ByteArray, label: String, onDone: (() -> Unit)? = null) {
        engine.sendUploadStart(curPath, fileName, data.size)
        val handler = Handler(Looper.getMainLooper())
        var off = 0
        val chunkSize = 200
        val sendChunk = object : Runnable {
            override fun run() {
                if (off >= data.size) {
                    engine.sendUploadEnd()
                    onDone?.invoke()
                    return
                }
                val end = minOf(off + chunkSize, data.size)
                engine.sendUploadChunk(off / chunkSize, data.copyOfRange(off, end))
                off = end
                handler.postDelayed(this, 50)
            }
        }
        handler.post(sendChunk)
        toast("正在上传 ${fileName}...")
    }

    /** 顺序上传一个灯具的全部原始文件（xml/d4/r20）。 */
    private fun uploadFilesSequential(files: List<File>, label: String) {
        fun next(idx: Int) {
            if (idx >= files.size) { toast("全部上传完成: $label"); return }
            val f = files[idx]
            uploadFileData(f.name, f.readBytes(), label) {
                Handler(Looper.getMainLooper()).postDelayed({ next(idx + 1) }, 300)
            }
        }
        next(0)
    }
}
