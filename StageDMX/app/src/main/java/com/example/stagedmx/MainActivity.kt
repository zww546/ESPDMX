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
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.graphics.Typeface
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.google.android.material.materialswitch.MaterialSwitch
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
import com.example.stagedmx.databinding.PageSettingsBinding

/** 文件上传分块大小（字节）。与固件 file_xfer.c 的 CHUNK_SIZE 对应，需 < ATT MTU-3。 */
private const val UPLOAD_CHUNK_SIZE = 200

/** 文件上传分块间隔（ms）。BLE 写是串行无流控的，太快会丢帧。 */
private const val UPLOAD_CHUNK_INTERVAL_MS = 50L

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var fb: PageFaderBinding
    private lateinit var pb: PageProgramBinding
    private lateinit var fixb: PageFixturesBinding
    private lateinit var fxb: PageFxBinding
    private lateinit var edb: PageFixtureEditorBinding
    private lateinit var imfb: PageInstancesBinding
    private lateinit var stb: PageSettingsBinding

    private lateinit var ble: BleManager
    private lateinit var engine: DmxEngine
    private lateinit var steps: StepStore
    private lateinit var fixtureStore: FixtureStore
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var layoutStore: LayoutStore
    private lateinit var fxPresetStore: FxPresetStore

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

    /** 实例管理页的多选删除模式（长按任意一行进入）。 */
    private var instEditMode = false
    private val instEditSel = mutableSetOf<String>()

    /**
     * 属性别名：不同灯库对同一功能命名不同（dimmer / intensity / dim），
     * 混灯型组控制时靠它把"调光"映射到各台灯各自的通道。
     * key = 参考灯型的属性名，value = 可接受的其它叫法。
     */
    private val ATTRIBUTE_ALIAS = mapOf(
        "dim" to setOf("dimmer", "intensity", "master"),
        "dim_fine" to setOf("dimmer_fine", "intensity_fine"),
        "shutter" to setOf("strobe", "shutter1"),
        "strobe" to setOf("shutter", "strobe1"),
        "pan" to setOf("pan1"),
        "tilt" to setOf("tilt1"),
        "pan_fine" to setOf("pan1_fine", "panfine"),
        "tilt_fine" to setOf("tilt1_fine", "tiltfine"),
        "color1" to setOf("color", "colour", "colour1", "colorwheel"),
        "gobo1" to setOf("gobo", "gobo1_select", "gobowheel"),
        "gobo1_pos" to setOf("gobo1_rot", "gobo_rot", "gobo1_index"),
        "prisma1" to setOf("prism1", "prism"),
        "prism_rot" to setOf("prisma1_pos", "prism1_pos", "prism_rot"),
        "zoom" to setOf("zoom1", "beam"),
        "focus" to setOf("focus1"),
        "frost" to setOf("frost1", "filter"),
        "iris" to setOf("iris1"),
        "ptspeed" to setOf("pt_speed", "panspeed", "speed_pt"),
    )

    // 全局设置（语言 / 排列方式）
    private val appSettings by lazy { getSharedPreferences("app_settings", MODE_PRIVATE) }

    // ---- 状态同步：单片机 / APP 重启后保持一致 ----
    private val syncPrefs by lazy { getSharedPreferences("state_sync", MODE_PRIVATE) }
    private var stateSyncPending = false
    private var stateSyncTimeout: Runnable? = null
    private var devUptime: Long = -1
    private val devChannels = IntArray(DmxProtocol.MAX_CHANNELS)
    private val lastDevFx = mutableListOf<FxEngine.DeviceFx>()
    private var devProgMask = 0
    private val devFxAdopted = mutableSetOf<String>()   // 已把设备上报的效果并入哪些实例
    private val syncHandler = Handler(Looper.getMainLooper())

    /** 文件上传进度用的 Handler（Activity 级，便于 onDestroy 取消）。 */
    private val uploadHandler = Handler(Looper.getMainLooper())

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
        layoutStore = LayoutStore(this)
        fxPresetStore = FxPresetStore(this)

        // 实例 → 槽位分配器（效果槽 0..3，绑定实例创建时分配的槽位）
        // 效果滑条重发需要当前实例 id
        // ⚠ FxEngine 是进程级单例而 DmxEngine 绑定本 Activity：必须在这里重新挂接，
        //   否则 Activity 重建后单例仍指向已关闭的旧链路（参数改动静默丢弃）。
        FxEngine.attachEngine(engine)

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
        imfb = PageInstancesBinding.inflate(layoutInflater)
        stb = PageSettingsBinding.inflate(layoutInflater)

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
        wireSettingsPage()
        applySavedLanguage()
        showFxTab(true)     // 效果页默认显示"内置效果"

        b.btnConnect.setOnClickListener { onConnectClicked() }
        // 实例管理入口
        b.btnInstanceMgr.setOnClickListener {
            refreshInstanceMgrList()
            b.pager.currentItem = Page.INSTANCES
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

    // ---------------- ViewPager2 页面 + 底部导航 ----------------
    /**
     * 页面索引集中定义。
     * 底部导航 5 项：推子 / 效果 / 灯具 / 实例 / 设置。
     * - 程序已并入"效果"页（顶部按钮切换：内置效果 / 程序），默认显示内置效果
     * - 灯库编辑不在导航里，由"设置"页进入
     * 集中成常量是因为散落的 `pager.currentItem = 5` 这种魔数在增删页面时极易错位。
     */
    private object Page {
        const val FADER = 0
        const val FX = 1           // 效果（含 内置效果/程序 两个页签）
        const val FIXTURE = 2      // 灯具（含 App灯库/设备灯库/文件管理 三个页签）
        const val INSTANCES = 3
        const val SETTINGS = 4
        const val EDITOR = 5       // 不在底部导航
        const val COUNT = 6
    }

    private fun setupPager() {
        // 程序页不再是一个独立的 pager 页面：它的根视图被装进"效果"页的程序容器里
        val pages = listOf(fb.root, fxb.root, fixb.root, imfb.root, stb.root, edb.root)
        fxb.layoutProgram.addView(pb.root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
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
                R.id.nav_fader -> { b.pager.currentItem = Page.FADER; true }
                R.id.nav_fx -> { b.pager.currentItem = Page.FX; refreshFxPage(); true }
                R.id.nav_fixture -> { b.pager.currentItem = Page.FIXTURE; refreshFixturePage(); true }
                R.id.nav_instances -> {
                    b.pager.currentItem = Page.INSTANCES; refreshInstanceMgrList(); true
                }
                R.id.nav_settings -> { b.pager.currentItem = Page.SETTINGS; refreshSettingsPage(); true }
                else -> false
            }
        }

        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) {
                // 只有真正在底部导航里的页面才回勾选状态；灯库编辑页保持原选中项
                val navPos = when (pos) {
                    Page.FADER -> 0
                    Page.FX -> 1
                    Page.FIXTURE -> 2
                    Page.INSTANCES -> 3
                    Page.SETTINGS -> 4
                    else -> -1
                }
                if (navPos in 0 until b.bottomNav.menu.size()) {
                    b.bottomNav.menu.getItem(navPos).isChecked = true
                }
                when (pos) {
                    Page.FX -> refreshFxPage()
                    Page.FIXTURE -> refreshFixturePage()
                    Page.INSTANCES -> refreshInstanceMgrList()
                    Page.SETTINGS -> refreshSettingsPage()
                }
            }
        })
    }

    // ---------------- 推子页 ----------------
    private fun wireFaderPage() {
        setupChannelPresets()

        // 总控最大亮度：0..100%，作用于全部实例（所有 DMX 输出按该百分比缩放）
        fun loadMaster() {
            val pct = autoPrefs.getInt("master_pct", 100).coerceIn(0, 100)
            fb.seekMaster.progress = pct
            fb.tvMaster.text = "$pct%"
            fb.tvMaster.setTextColor(ContextCompat.getColor(this,
                if (pct == 0) R.color.err else R.color.accent))
            engine.setMasterPct(pct)
        }
        fb.seekMaster.max = 100
        fb.seekMaster.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                engine.setMasterPct(p)
                fb.tvMaster.text = "$p%"
                fb.tvMaster.setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (p == 0) R.color.err else R.color.accent))
                if (fromUser) autoPrefs.edit().putInt("master_pct", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        loadMaster()

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
                val base = t.globalAddr()
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
            .sortedBy { it.globalAddr() }

    /** 渲染顶部实例标签条；无实例时隐藏。 */
    private fun renderInstanceBar() {
        val insts = fixtureStore.instances()
        b.instScroll.visibility = if (insts.isEmpty()) View.GONE else View.VISIBLE
        b.btnInstanceMgr.visibility = if (insts.isEmpty()) View.GONE else View.VISIBLE
        b.instBar.removeAllViews()
        instanceButtons.clear()
        if (insts.isEmpty()) {
            // 一台都不剩：必须把"选中的实例"和"当前实例"一起清干净，
            // 否则切回推子页/实例页时仍指向已删除的实例（表现为删光了还显示旧状态）
            selectedInstanceIds.clear()
            currentInstanceId = null
            refreshMasterScope()
            return
        }

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
            .setMessage("删除实例「${inst.name}」（${inst.label()}）？")
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
                refreshInstanceMgrList()
                // 若无剩余实例（或剩余的一台都没被选中），回到裸通道模式
                if (fixtureStore.instances().isEmpty() || selectedInstanceIds.isEmpty()) {
                    clearFixtureMode()
                } else {
                    applySelectedInstance()
                }
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
            // 混合灯型现在**允许**一起控制：写入时按属性映射（见 groupSet）。
            // 推子页以第一台的通道作为"参考布局"显示，属性缺失的灯会被自动跳过。
            val mixed = insts.any { fixtureStore.fixtureOf(it)?.id != def.id }
            channelAdapter.applyFixtureGroup(def, insts)
            fb.tvFixtureLabel.text =
                if (mixed) "${def.name} 等混合灯型 组（${insts.size} 台，按属性映射）"
                else "${def.name} 组（${insts.size} 台）"
            fb.tvFixtureLabel.visibility = View.VISIBLE
        } else {
            channelAdapter.applyFixture(def, first.globalAddr())
            fb.tvFixtureLabel.text = "${first.name} ${first.label()} (${def.name}/${def.mode})"
            fb.tvFixtureLabel.visibility = View.VISIBLE
        }
        // v6：效果按"规则阵列"作用到整组（非规则阵列则退回单台并提示）
        applyFxTargets(def, insts)
        applyFaderLayout(def.id)
        b.presetBar.visibility = View.GONE
        b.etChannels.visibility = View.GONE
        fb.btnLocate.isEnabled = true
        refreshMasterScope()
    }

    /** 按设置页的“推子页排列方式”套用自定义顺序（没有生效预设则回到通道顺序）。 */
    private fun applyFaderLayout(fixtureId: String?) {
        channelAdapter.applyOrder(layoutStore.orderFor(fixtureId))
    }

    /**
     * v6：把效果的作用范围设为当前的"规则阵列"。
     *
     * 规则阵列 = 组内**同灯型** 且 起始地址**构成等差数列**（等间距）。
     * 因为固件只收到 (first, stride, count) 三个数，靠 `first + i×stride` 推每一台的通道；
     * 不满足这个前提就会把值写到别的地址上去，所以这里直接拒绝并提示（方案 A）。
     *
     * @return 是否可以把效果作用到整组；false = 已退回单台（调用方按需提示）
     */
    private fun applyFxTargets(def: FixtureDef, insts: List<FixtureInstance>): Boolean {
        if (insts.isEmpty()) {
            FxEngine.applyTargets(def, 1, 0, 1)
            return true
        }
        val first = insts[0]
        if (insts.size == 1) {
            FxEngine.applyTargets(def, first.globalAddr(), 0, 1)
            return true
        }
        // 判据一：同灯型
        if (insts.any { fixtureStore.fixtureOf(it)?.id != def.id }) {
            FxEngine.applyTargets(def, first.globalAddr(), 0, 1)
            return false
        }
        // 判据二：等间距
        val globals = insts.map { it.globalAddr() }
        val stride = globals[1] - globals[0]
        val arithmetic = globals.zipWithNext().all { (a, b) -> b - a == stride }
        if (!arithmetic || stride < def.channelCount || insts.size > FxEngine.MAX_TARGETS) {
            FxEngine.applyTargets(def, first.globalAddr(), 0, 1)
            return false
        }
        FxEngine.applyTargets(def, globals[0], stride, insts.size)
        return true
    }

    /** 当前选中实例是否构成可作用整组的规则阵列（效果页用它决定是否放行/提示）。 */
    private fun fxArrayInfo(): Pair<Boolean, String> {
        val insts = groupInstances()
        if (insts.size <= 1) return true to "单台"
        val def = fixtureStore.fixtureOf(insts[0]) ?: return false to "灯型缺失"
        if (insts.any { fixtureStore.fixtureOf(it)?.id != def.id }) {
            return false to "组内灯型不一致（无法用一套阵列描述）"
        }
        val globals = insts.map { it.globalAddr() }
        val stride = globals[1] - globals[0]
        if (!globals.zipWithNext().all { (a, b) -> b - a == stride }) {
            return false to "组内地址不等间距（如 1/19/100），无法用一套阵列描述"
        }
        if (stride < def.channelCount) return false to "地址间距小于灯型通道数"
        if (insts.size > FxEngine.MAX_TARGETS) return false to "台数超过 ${FxEngine.MAX_TARGETS}"
        return true to "${insts.size} 台 · 间距 $stride"
    }

    /**
     * 组控制写入入口（ChannelAdapter 回调）：单实例直接写；组模式遍历组内所有实例。
     * @param chInFixture 灯内通道号（1-based）
     */
    private fun setChannelValue(chInFixture: Int, value: Int) {
        if (channelAdapter.isGroupMode()) {
            groupSet(chInFixture, value)
        } else {
            val inst = currentInstanceId?.let { fixtureStore.instances().find { i -> i.id == it } }
            if (inst != null) engine.set(inst.globalAddr() + chInFixture - 1, value)
            else engine.set(chInFixture, value)
        }
    }

    /** 组内所有实例的某通道同时写入（混灯型时按属性映射）。 */
    private fun groupSet(chInFixture: Int, value: Int) {
        val insts = groupInstances()
        if (insts.isEmpty()) return
        val refDef = fixtureStore.fixtureOf(insts[0]) ?: return
        // 同灯型：通道号一一对应，直接按索引写（最快且不会错）
        val sameType = insts.all { fixtureStore.fixtureOf(it)?.id == refDef.id }
        if (sameType) {
            for (inst in insts) engine.set(inst.globalAddr() + chInFixture - 1, value)
            return
        }
        // 混灯型：按"属性"映射。不同灯型第 N 通道含义不同（一个频闪一个色盘），
        // 照抄通道号会把值写到无关通道上。
        val attr = channelAdapter.attrOfChannel(chInFixture)
        for (inst in insts) {
            val def = fixtureStore.fixtureOf(inst) ?: continue
            val ch = channelForAttribute(def, attr, chInFixture)
            // 该灯没有这个属性 → 跳过（例如帕灯没有 gobo，不该被写入任何通道）
            if (ch == null) continue
            engine.set(inst.globalAddr() + ch - 1, value)
        }
    }

    /**
     * 在灯型里找与 `attr` 同属性的通道号（1-based）。
     * 属性匹配不上时：只有当该灯型通道数足够、且调用方给出 fallback 才回退到同序号，
     * 否则返回 null（宁可不写，也不能写错通道）。
     */
    private fun channelForAttribute(def: FixtureDef, attr: String, fallbackCh: Int): Int? {
        if (attr.isEmpty()) return fallbackCh.takeIf { it in 1..def.channelCount }
        def.channels.find { it.attribute.equals(attr, ignoreCase = true) }
            ?.let { return it.number }
        // 同一属性的别名（不同灯库命名不一致）：dimmer↔dim、intensity↔dim 等
        val alias = ATTRIBUTE_ALIAS[attr.lowercase()]
        if (alias != null) {
            def.channels.find { it.attribute.lowercase() in alias }?.let { return it.number }
        }
        return null
    }

    /** 组模式：组内所有实例的整个地址段统一置值（全黑/Flash）。 */
    private fun applyGroupUniform(value: Int) {
        val chCount = channelAdapter.channelCount()
        for (inst in groupInstances()) {
            engine.setRangeUniform(inst.globalAddr(), inst.globalAddr() + chCount - 1, value)
        }
    }

    /**
     * 刷新主控亮度的作用范围：只登记"当前受控实例"灯库里的调光(DIM)通道。
     *
     * 两条规则：
     * 1. **只缩放调光** —— 主控是亮度总控，不该把水平/垂直/图案/棱镜一起缩放
     * 2. **跟随推子页的作用范围** —— 组模式=选中的组、单实例=该实例。
     *    早期版本遍历 `fixtureStore.instances()`（全部实例），导致"只选了 2 台灯、
     *    拉主控却让舞台上所有灯都变暗"。
     * 3. 只有在完全没有实例时才退回全通道缩放（裸通道模式）
     */
    private fun refreshMasterScope() {
        val insts = when {
            channelAdapter.isGroupMode() -> groupInstances()
            currentInstanceId != null ->
                listOfNotNull(fixtureStore.instances().find { it.id == currentInstanceId })
            // 有实例但一台都没选：仍管全部，避免集合为空时退回"全通道缩放"（那会连 pan/tilt 一起缩放）
            fixtureStore.instances().isNotEmpty() -> fixtureStore.instances()
            else -> emptyList()
        }
        val dims = mutableSetOf<Int>()
        if (insts.isNotEmpty()) {
            for (inst in insts) {
                val def = fixtureStore.fixtureOf(inst) ?: continue
                // 按属性找调光通道（兼容 dimmer/intensity 等命名差异）
                val d = channelForAttribute(def, "dim", def.findChFine("dim")?.first ?: 0) ?: continue
                val real = inst.globalAddr() + d - 1
                if (real in 1..DmxProtocol.MAX_CHANNELS) dims.add(real)
            }
        } else {
            // 只应用了灯库（无实例）：按起始地址 1 计算调光通道
            fixtureStore.currentFixture?.let { def ->
                def.findChFine("dim")?.first?.let { if (it in 1..DmxProtocol.MAX_CHANNELS) dims.add(it) }
            }
        }
        engine.setDimmerChannels(dims)
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
        applyFaderLayout(def.id)
        FxEngine.applyFixture(def, 1)  // 非实例模式从地址 1 开始
        fb.btnLocate.isEnabled = true
        // 隐藏通道选择器，显示灯具名
        b.presetBar.visibility = View.GONE
        b.etChannels.visibility = View.GONE
        fb.tvFixtureLabel.text = "${def.name} / ${def.mode} (${def.channelCount}CH)"
        fb.tvFixtureLabel.visibility = View.VISIBLE
        refreshMasterScope()
        // 点灯具标签退出手动模式
        fb.tvFixtureLabel.setOnClickListener { clearFixtureMode() }
    }

    private fun clearFixtureMode() {
        // 回到裸通道：停止所有效果和程序
        FxEngine.stopAll()
        // 先取出槽位再清选择，否则 currentProgSlot() 已经取不到实例了
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
        refreshMasterScope()
        // 回到全局程序列表
        refreshProgramPage()
        refreshFxPage()
        // 顶部实例条 + 实例管理页列表都要跟着回到"无实例"状态
        renderInstanceBar()
        refreshInstanceMgrList()
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
                    val real = inst.globalAddr() + ch.number - 1
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
            b.pager.currentItem = Page.FX
            showFxTab(false)      // 记录步 → 切到"效果"页的程序页签
            return
        }
        steps.addStep(prog, instId, steps.defaultTimeMs, sanitizeSnapshot())
        refreshStepsUI()
        toast("已记录 → $prog 第 ${steps.stepCount(prog, instId)} 步")
    }

    // ---- 效果页 ----
    /** 效果页页签：true = 内置效果（默认），false = 程序。 */
    private var fxTabBuiltin = true

    /**
     * 切换效果页的两个页签：内置效果 / 程序。
     * 交互与"灯具"页的 App灯库/设备灯库/文件管理 完全一致。
     */
    private fun showFxTab(builtin: Boolean) {
        fxTabBuiltin = builtin
        fxb.layoutFxBuiltin.visibility = if (builtin) View.VISIBLE else View.GONE
        fxb.layoutProgram.visibility = if (builtin) View.GONE else View.VISIBLE
        fun tab(btn: TextView, active: Boolean) {
            btn.setBackgroundResource(if (active) R.drawable.bg_pill_outline_accent
                                      else R.drawable.bg_pill_outline_white)
            btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        tab(fxb.btnTabBuiltin, builtin)
        tab(fxb.btnTabProgram, !builtin)
        if (builtin) refreshFxPage() else refreshProgramPage()
    }

    private fun wireFxPage() {
        fxb.btnTabBuiltin.setOnClickListener { showFxTab(true) }
        fxb.btnTabProgram.setOnClickListener { showFxTab(false) }
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
                val active = FxEngine.containsFx(currentInstanceId, def.id)
                val focused = FxEngine.getFocusedFxId(currentInstanceId)
                val isFocused = focused == def.id
                // 高亮三态用三张独立 drawable，**不能用 setTint**：
                // GradientDrawable 的 tint 存在共享 ConstantState 里，会给全 App 的 bg_card 染色。
                when {
                    isFocused -> holder.itemView.setBackgroundResource(R.drawable.bg_card_outline_accent)
                    active -> holder.itemView.setBackgroundResource(R.drawable.bg_card_active)
                    else -> holder.itemView.setBackgroundResource(R.drawable.bg_card)
                }
                // 点击条目（非开关区）→ 只聚焦，用于调幅度/速度
                holder.itemView.setOnClickListener {
                    FxEngine.setSelectedPreset(currentInstanceId, def.id)
                    refreshFxPage()
                }
                // 条目自身的启停开关：打开=启动该效果，关闭=停止该效果
                val sw = holder.itemView.findViewById<MaterialSwitch>(R.id.swFxItem)
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = active
                sw.setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        if (ble.state != BleManager.State.CONNECTED) {
                            toast("请先连接设备再启动效果")
                            sw.isChecked = false
                            return@setOnCheckedChangeListener
                        }
                        if (FxEngine.slotsFull()) {
                            toast("效果槽已满(8个)")
                            sw.isChecked = false
                            return@setOnCheckedChangeListener
                        }
                        // v6：效果要作用到"整组"，组内必须是规则阵列（同灯型 + 等间距地址）
                        val (arrayOk, arrayMsg) = fxArrayInfo()
                        if (!arrayOk) {
                            toast("无法作用到整组：$arrayMsg")
                            sw.isChecked = false
                            return@setOnCheckedChangeListener
                        }
                        FxEngine.setSelectedPreset(currentInstanceId, def.id)
                        updateFxChannels()
                        val ok = FxEngine.start(engine, def.id, currentInstanceId,
                            amp = FxEngine.getAmplitude(currentInstanceId),
                            speed = FxEngine.getSpeed(currentInstanceId))
                        if (!ok) {
                            toast("无法启动：与现有效果通道冲突")
                            sw.isChecked = false
                        }
                    } else {
                        val slot = FxEngine.slotOf(currentInstanceId, def.id)
                        if (slot != null) FxEngine.stopSlot(currentInstanceId, slot)
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
                // 切割循环的“幅度”滑条 = 循环间隔(ms)，用线性刻度覆盖 0..20s
                if (isCutLoopSelected()) FxEngine.setAmplitude(cutGapFromSeek(p), currentInstanceId)
                else FxEngine.setAmplitude(p, currentInstanceId)
                fxb.tvAmplitude.text = fxAmpText()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        fxb.seekSpeed.max = speedSeekMax
        fxb.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                // 切割循环的“速度”滑条 = 每步时长(ms)，线性覆盖 100ms..5s
                val speed = if (isCutLoopSelected()) cutStepFromSeek(p)
                            else seekToSpeed(p).coerceIn(FxEngine.SPEED_MIN, FxEngine.SPEED_MAX)
                FxEngine.setSpeed(speed, currentInstanceId)
                fxb.tvSpeed.text = fxSpeedText()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        fxb.btnFxStop.setOnClickListener { FxEngine.stop(currentInstanceId); refreshFxPage() }

        // ---- v6 阵列参数：扩散 / 相位 / 波形 / 方向 / 包络 ----
        fxb.seekSpread.max = 255
        fxb.seekSpread.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                FxEngine.setSpread(p, currentInstanceId)
                fxb.tvSpread.text = "扩散: ${p * 360 / 255}°"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        fxb.seekPhase.max = 255
        fxb.seekPhase.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                FxEngine.setPhase(p, currentInstanceId)
                fxb.tvPhase.text = "相位: ${p * 360 / 255}°"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        fun bindSpinner(sp: Spinner, names: List<String>, onPick: (Int) -> Unit) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (!fxUiUpdating) onPick(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        bindSpinner(fxb.spShape, FxEngine.SHAPE_NAMES) { FxEngine.setShape(it, currentInstanceId) }
        bindSpinner(fxb.spDirection, FxEngine.DIR_NAMES) { FxEngine.setDirection(it, currentInstanceId) }
        bindSpinner(fxb.spEnvelope, FxEngine.ENV_NAMES) { FxEngine.setEnvelope(it, currentInstanceId) }

        // ---- 效果预设：一键保存 / 一键应用（数值 + 开关状态）----
        fxpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        fxpAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fxb.spFxPreset.adapter = fxpAdapter
        fxb.spFxPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!fxPresetUpdating) fxPresetSel = fxpAdapter.getItem(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        fxb.btnFxPresetSave.setOnClickListener { saveFxPreset() }
        fxb.btnFxPresetApply.setOnClickListener {
            applyFxPreset(fxPresetSel ?: fxb.spFxPreset.selectedItem as? String)
        }
        fxb.btnFxPresetOff.setOnClickListener {
            FxEngine.stop(currentInstanceId)
            toast("已关闭当前实例的全部效果")
            refreshFxPage()
        }
        fxb.btnFxPresetDel.setOnClickListener { deleteFxPreset() }

        refreshFxPage()
    }

    // ---- 效果预设 ----
    private lateinit var fxpAdapter: ArrayAdapter<String>
    private var fxPresetSel: String? = null
    private var fxPresetUpdating = false
    /** 刷新 UI 时抑制 Spinner 回调（避免回灌参数）。 */
    private var fxUiUpdating = false

    /** 预设默认名 = 当前实例名称（无实例时用“全局”）。 */
    private fun fxPresetDefaultName(): String {
        val inst = currentInstanceId?.let { id -> fixtureStore.instances().find { i -> i.id == id } }
        return inst?.name ?: "全局"
    }

    private fun refreshFxPresets() {
        val names = fxPresetStore.names(currentInstanceId)
        fxPresetUpdating = true
        fxpAdapter.clear()
        fxpAdapter.addAll(names)
        fxpAdapter.notifyDataSetChanged()
        val want = fxPresetSel?.takeIf { names.contains(it) } ?: names.firstOrNull()
        fxPresetSel = want
        if (want != null) fxb.spFxPreset.setSelection(names.indexOf(want))
        fxPresetUpdating = false
    }

    private fun saveFxPreset() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(fxPresetDefaultName())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存效果预设")
            .setMessage("保存当前「所有内置效果」的数值与开关状态")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val nm = input.text.toString().trim().ifEmpty { fxPresetDefaultName() }
                fxPresetStore.save(FxPresetStore.Preset(
                    nm, currentInstanceId, FxEngine.snapshotParams(currentInstanceId)))
                fxPresetSel = nm
                refreshFxPage()
                toast("已保存效果预设：$nm")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyFxPreset(name: String?) {
        if (name.isNullOrEmpty()) { toast("请先保存一个效果预设"); return }
        if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return }
        val p = fxPresetStore.get(currentInstanceId, name) ?: return
        updateFxChannels()
        val failed = FxEngine.applyParams(engine, p.params, currentInstanceId)
        if (failed.isEmpty()) toast("已应用效果预设：$name")
        else {
            val nm = failed.joinToString("、") { id ->
                FxEngine.presets.find { it.id == id }?.name ?: "#$id"
            }
            toast("部分效果未启动（通道冲突/槽位已满）：$nm")
        }
        refreshFxPage()
    }

    private fun deleteFxPreset() {
        val name = fxPresetSel ?: fxb.spFxPreset.selectedItem as? String
        if (name.isNullOrEmpty()) { toast("暂无预设"); return }
        MaterialAlertDialogBuilder(this)
            .setMessage("删除效果预设「$name」？")
            .setPositiveButton("删除") { _, _ ->
                fxPresetStore.delete(currentInstanceId, name)
                fxPresetSel = null
                refreshFxPage()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 切割循环（FX 13）滑条刻度：线性映射到真实时间 ----
    private fun cutGapFromSeek(p: Int): Int =
        (p.coerceIn(0, 255) * FxEngine.CUT_GAP_MS_MAX / 255).coerceIn(
            FxEngine.CUT_GAP_MS_MIN, FxEngine.CUT_GAP_MS_MAX)

    private fun seekFromCutGap(ms: Int): Int =
        (ms.coerceIn(FxEngine.CUT_GAP_MS_MIN, FxEngine.CUT_GAP_MS_MAX) * 255 /
            FxEngine.CUT_GAP_MS_MAX).coerceIn(0, 255)

    private fun cutStepFromSeek(p: Int): Int =
        (FxEngine.CUT_STEP_MS_MIN + p.coerceIn(0, 255) *
            (FxEngine.CUT_STEP_MS_MAX - FxEngine.CUT_STEP_MS_MIN) / 255)

    private fun seekFromCutStep(ms: Int): Int =
        ((ms.coerceIn(FxEngine.CUT_STEP_MS_MIN, FxEngine.CUT_STEP_MS_MAX) - FxEngine.CUT_STEP_MS_MIN) *
            255 / (FxEngine.CUT_STEP_MS_MAX - FxEngine.CUT_STEP_MS_MIN)).coerceIn(0, 255)

    // ---- 切割循环（FX 13）参数显示：把参数换算回真实时间显示 ----
    private fun isCutLoopSelected(): Boolean = FxEngine.getFocusedFxId(currentInstanceId) == 13

    private fun fxAmpText(): String {
        val amp = FxEngine.getAmplitude(currentInstanceId)
        return if (isCutLoopSelected()) {
            "循环间隔: %.1fs".format(amp / 1000f)
        } else "幅度: $amp"
    }

    private fun fxSpeedText(): String {
        val speed = FxEngine.getSpeed(currentInstanceId)
        return if (isCutLoopSelected()) {
            "每步时长: %.2fs".format(speed / 1000f)
        } else {
            val speedLogMin = kotlin.math.ln(FxEngine.SPEED_MIN.toDouble())
            val speedLogMax = kotlin.math.ln(FxEngine.SPEED_MAX.toDouble())
            val seekPos = ((kotlin.math.ln(speed.toDouble()) - speedLogMin) /
                (speedLogMax - speedLogMin) * 255).toInt().coerceIn(0, 255)
            "速度: $seekPos"
        }
    }

    private fun refreshFxPage() {
        val instId = currentInstanceId
        // 保证效果页的通道映射与当前实例/灯库一致（切割片等通道解析结果随灯库变化）
        updateFxChannels()
        // APP 重启/重连后：把设备上正在运行的效果并入当前实例，让开关状态与单片机一致
        val fk = instId ?: "global"
        if (lastDevFx.isNotEmpty() && devFxAdopted.add(fk)) {
            FxEngine.adoptFromDevice(instId, lastDevFx)
        }
        val selId = FxEngine.getFocusedFxId(instId)   // 当前选中的预设 id
        val selActive = selId != 0 && FxEngine.containsFx(instId, selId)

        if (selId != 0) {
            // 选中了某预设：显示它的名字 + 运行状态；参数区块总是可用（聚焦项）
            val name = FxEngine.presets.find { it.id == selId }?.name ?: "#$selId"
            // 若该实例还有叠加的其它运行效果，一并列出
            val others = FxEngine.activeFxIds(instId)
                .filter { it != selId }
                .map { FxEngine.presets.find { p -> p.id == it }?.name ?: "#$it" }
            fxb.tvFxStatus.text =
                if (selActive) "▶ $name" + others.joinToString(" + ") { " + $it" }
                else name + if (others.isNotEmpty()) others.joinToString(" + ") { " + $it" } else ""
            // 切割循环：显示灯库中解析到的切割片/切割旋转通道数量，
            // 一眼确认“切割通道是否被灯库识别”（0 片 = 当前灯库没有切割片通道）。
            if (selId == 13) {
                val nBlade = FxEngine.bladeCh.count { it != 0 }
                fxb.tvFxStatus.append(
                    if (nBlade == 0 && FxEngine.shaperRotCh == 0) "  〔当前灯库无切割通道〕"
                    else "  〔切割${nBlade}片" + (if (FxEngine.shaperRotCh != 0) " + 旋转" else "") + "〕")
            }
            fxb.tvFxStatus.setTextColor(ContextCompat.getColor(this,
                if (selActive) R.color.accent else R.color.text))
            fxb.btnFxStop.visibility = if (selActive) View.VISIBLE else View.GONE
            fxb.fxParams.visibility = View.VISIBLE
            val isCut = selId == 13
            // v6：作用范围提示（规则阵列 = 整组；否则说明原因并已退回单台）
            val (arrayOk, arrayMsg) = fxArrayInfo()
            val nTargets = if (arrayOk) groupInstances().size.coerceAtLeast(1) else 1
            fxb.tvFxArray.text = if (arrayOk) "作用范围：$arrayMsg"
                                 else "作用范围：单台 ⚠ $arrayMsg"
            fxb.tvFxArray.setTextColor(ContextCompat.getColor(this,
                if (arrayOk) R.color.accent else R.color.warn))
            val amp = FxEngine.getAmplitude(instId)
            fxb.seekAmplitude.progress = if (isCut) seekFromCutGap(amp) else amp
            fxb.tvAmplitude.text = fxAmpText()
            val speed = FxEngine.getSpeed(instId)
            val speedLogMin = kotlin.math.ln(FxEngine.SPEED_MIN.toDouble())
            val speedLogMax = kotlin.math.ln(FxEngine.SPEED_MAX.toDouble())
            val seekPos = if (isCut) seekFromCutStep(speed)
                else ((kotlin.math.ln(speed.toDouble()) - speedLogMin) / (speedLogMax - speedLogMin) * 255).toInt().coerceIn(0, 255)
            fxb.seekSpeed.progress = seekPos
            fxb.tvSpeed.text = fxSpeedText()
            // v6 阵列参数
            fxUiUpdating = true
            val spread = FxEngine.getSpread(instId)
            fxb.seekSpread.progress = spread
            fxb.tvSpread.text = "扩散: ${spread * 360 / 255}°"
            val phase = FxEngine.getPhase(instId)
            fxb.seekPhase.progress = phase
            fxb.tvPhase.text = "相位: ${phase * 360 / 255}°"
            fxb.spShape.setSelection(FxEngine.getShape(instId))
            fxb.spDirection.setSelection(FxEngine.getDirection(instId))
            fxb.spEnvelope.setSelection(FxEngine.getEnvelope(instId))
            fxUiUpdating = false
        } else {
            // 未选中任何预设
            fxb.tvFxStatus.text = "点击列表选择效果，用开关启用"
            fxb.tvFxStatus.setTextColor(ContextCompat.getColor(this, R.color.textDim))
            fxb.btnFxStop.visibility = View.GONE
            fxb.fxParams.visibility = View.GONE
        }
        // 刷新列表高亮 + 效果预设下拉
        fxb.rvFxPresets.adapter?.notifyDataSetChanged()
        refreshFxPresets()
    }

    private fun updateFxChannels() {
        // 优先当前选中实例（含阵列范围），否则用当前灯库
        val insts = groupInstances()
        val def = if (insts.isNotEmpty()) fixtureStore.fixtureOf(insts[0]) else fixtureStore.currentFixture
        if (def != null) {
            applyFxTargets(def, insts)
        }
    }

    private fun editValueDialog(chInFixture: Int) {
        // 读取当前值：组模式读主灯，单实例读该实例地址段，裸通道读全局
        val cur = if (channelAdapter.isGroupMode()) {
            val inst = groupInstances().firstOrNull()
            if (inst != null) engine.get(inst.globalAddr() + chInFixture - 1) else 0
        } else {
            val inst = currentInstanceId?.let { fixtureStore.instances().find { i -> i.id == it } }
            if (inst != null) engine.get(inst.globalAddr() + chInFixture - 1) else engine.get(chInFixture)
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
    /**
     * 程序作用范围的实例集合 —— **必须与 [sanitizeSnapshot]（录制）一致**。
     *
     * 早期 BUG：录制时 `sanitizeSnapshot()` 存的是整帧、且对组内所有实例做 reset 清零，
     * 但播放时只上传 `currentInstanceId` 一个实例的地址段 → 选 3 台录的程序只有第 1 台会动。
     */
    private fun programScopeInstances(): List<FixtureInstance> =
        if (channelAdapter.isGroupMode()) groupInstances()
        else listOfNotNull(currentInstanceId?.let { id -> fixtureStore.instances().find { it.id == id } })

    private fun uploadProgramAndPlay(list: List<StepStore.Step>) {
        val slot = currentProgSlot()
        engine.sendProgStop(slot)
        engine.sendProgClear(slot)
        // 作用范围：与录制一致 —— 组内所有实例的地址段（按属性映射到各自通道）
        val insts = programScopeInstances()
        val ranges: List<Pair<Int, Int>> = if (insts.isEmpty()) {
            listOf(1 to DmxProtocol.MAX_CHANNELS)          // 裸通道：全局
        } else {
            insts.mapNotNull { inst ->
                val def = fixtureStore.fixtureOf(inst) ?: return@mapNotNull null
                val lo = inst.globalAddr()
                val hi = (lo + def.channelCount - 1).coerceAtMost(DmxProtocol.MAX_CHANNELS)
                if (lo <= hi) lo to hi else null
            }
        }
        // 每步最多 255 条（协议帧用 1 字节计数；固件 PROG_MAX_ITEMS_STEP 同值）
        var prev = IntArray(DmxProtocol.MAX_CHANNELS)
        var truncated = 0
        for (st in list) {
            val changes = mutableListOf<Pair<Int, Int>>()
            for ((lo, hi) in ranges) {
                for (ch in lo..hi) {
                    val v = st.values.getOrNull(ch - 1) ?: 0
                    val pv = prev.getOrNull(ch - 1) ?: 0
                    if (v != pv) changes.add(ch to v)
                }
            }
            if (changes.size > DmxProtocol.MAX_PROG_ITEMS_STEP) {
                truncated++
                changes.subList(DmxProtocol.MAX_PROG_ITEMS_STEP, changes.size).clear()
            }
            engine.sendProgAppendSparse(slot, st.timeMs, changes)
            prev = st.values.copyOf()
        }
        engine.sendProgPlay(slot, true)
        if (truncated > 0) {
            toast("⚠ $truncated 步的通道变化超过 ${DmxProtocol.MAX_PROG_ITEMS_STEP} 条，已截断；建议减少同时录制的灯数")
        }
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
                        val base = inst.globalAddr()
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
                // 连接后先与设备同步状态（区分“单片机重启”与“App 重启/重连”两种情况），
                // 不再无条件把 App 的（可能过期的）整帧推给设备。
                beginStateSync()
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

    /**
     * 设备通知总入口。
     *
     * **解析已抽到 [DeviceMessages.parse]（纯函数，可单测）**，这里只负责副作用分发。
     * 之前是 11 个 `data.size >= N` 分支的松散校验，越界与短帧风险都在里面；
     * 现在解析器对每个分支都做完整长度校验，非法帧直接丢弃。
     */
    override fun onNotify(data: ByteArray) {
        when (val m = DeviceMessages.parse(data)) {
            // ---- 状态同步应答（0x05 之后固件上报）----
            is Msg.StateHead -> {
                devUptime = m.uptimeSec
                devProgMask = m.progMask
                lastDevFx.clear()
                java.util.Arrays.fill(devChannels, 0)
            }
            is Msg.StateChunk -> {
                for (i in m.values.indices) {
                    val ch = m.start + i
                    if (ch in 1..DmxProtocol.MAX_CHANNELS) devChannels[ch - 1] = m.values[i]
                }
            }
            is Msg.StateFx ->
                lastDevFx.add(FxEngine.DeviceFx(m.slot, m.fxId, m.amp16, m.speed))

            Msg.StateEnd -> runOnUiThread { finishStateSync() }

            is Msg.UploadResult ->
                toast(if (m.ok) "上传完成" else "上传失败")

            is Msg.FileList -> {
                devFiles.addAll(m.files.map { DevFile(it.name, it.size, it.isDir) })
                runOnUiThread { refreshDeviceFilesUI() }
            }

            is Msg.DeleteResult -> {
                val name = deletingFile
                runOnUiThread {
                    if (m.ok) {
                        toast("已删除 ${name ?: "文件"}")
                        if (name != null) devFiles.removeAll { it.name == name }
                        refreshDeviceFilesUI()
                    } else {
                        toast("删除失败${name?.let { "：$it" } ?: ""}")
                    }
                }
                deletingFile = null
            }

            is Msg.DirResult -> {
                val name = dirOpName
                val mv = moveOp
                runOnUiThread {
                    if (mv != null) {
                        toast(if (m.ok) "$mv 成功" else "$mv 失败")
                    } else {
                        toast(if (m.ok) "文件夹操作成功${name?.let { "：$it" } ?: ""}" else "文件夹操作失败${name?.let { "：$it" } ?: ""}")
                    }
                    if (m.ok) refreshDeviceFiles()
                }
                dirOpName = null
                moveOp = null
            }

            is Msg.DirsList -> dirList.addAll(m.dirs)

            Msg.DirsEnd -> {
                val cb = dirCollectCb
                dirCollectCb = null
                runOnUiThread { cb?.invoke() }
            }

            is Msg.FileChunk -> downloadBuf += m.data

            is Msg.FileEnd -> {
                val name = downloadingFile ?: "device_fixture.xml"
                if (m.ok && downloadBuf.isNotEmpty()) {
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
                    runOnUiThread { toast(if (m.ok) "下载完成(nodata)" else "设备无此文件") }
                }
                downloadingFile = null
                downloadBuf = ByteArray(0)
            }

            null -> {}   // 未知 / 非法帧
        }
    }

    // ---- 状态同步：单片机 / APP 重启后保持一致 ----

    /**
     * 连接后请求设备上报整机状态。
     * 2 秒内没有收到结束帧（老固件不支持 0x05）→ 退回“整帧下发”。
     */
    private fun beginStateSync() {
        stateSyncPending = true
        devUptime = -1
        devProgMask = 0
        lastDevFx.clear()
        devFxAdopted.clear()
        java.util.Arrays.fill(devChannels, 0)
        engine.sendRequestState()
        // 有些手机刚连上时服务发现还没完成，第一个 write 会静默失败 —— 表现为设备端
        // 收不到 0x05、3 秒后超时退化成"App 整帧推给设备"（正是①要避免的情况）。
        // 这里补发一次，代价极小。
        syncHandler.postDelayed({ if (stateSyncPending) engine.sendRequestState() }, 400)
        stateSyncTimeout?.let { syncHandler.removeCallbacks(it) }
        val r = Runnable {
            if (stateSyncPending) {
                stateSyncPending = false
                engine.sendFullFrame()
            }
        }
        stateSyncTimeout = r
        syncHandler.postDelayed(r, 2500)
    }

    /**
     * 收到设备状态后的处理：
     *  - 设备 uptime 比上次记录的小 → 单片机重启过 → 用 App 的状态覆盖设备；
     *  - 否则 → App 自己重启/重连 → 采纳设备状态（开关与数值显示与单片机一致）。
     */
    private fun finishStateSync() {
        if (!stateSyncPending) return
        stateSyncPending = false
        stateSyncTimeout?.let { syncHandler.removeCallbacks(it) }
        stateSyncTimeout = null

        val mac = ble.connectedAddress()
        val lastSeen = if (mac != null) syncPrefs.getLong("uptime_$mac", -1L) else -1L
        val mcuRebooted = lastSeen < 0L || devUptime < lastSeen

        if (mcuRebooted) {
            // 设备重启过（或首次连接）：设备是空的，用 App 状态恢复
            engine.sendFullFrame()
            if (lastSeen >= 0L) toast("设备重启过，已同步 App 状态")
        } else {
            // App 重启 / 重新连接：采纳设备上的状态
            engine.adoptDeviceState(devChannels)
            playingSlots.clear()
            for (i in 0 until 8) if (devProgMask and (1 shl i) != 0) playingSlots.add(i)
            channelAdapter.refresh()
            if (lastDevFx.isNotEmpty()) {
                FxEngine.adoptFromDevice(currentInstanceId, lastDevFx)
                devFxAdopted.add(currentInstanceId ?: "global")
            }
            refreshFxPage()
            refreshProgramPage()
            toast("已从设备同步状态")
        }
        if (mac != null) syncPrefs.edit().putLong("uptime_$mac", devUptime).apply()
    }

    private fun updateStatusUi(state: BleManager.State, info: String?) {
        // 只显示状态点颜色，不显示文字/MAC（状态点颜色区分）
        val colorRes = when (state) {
            BleManager.State.CONNECTED -> R.color.ok
            BleManager.State.CONNECTING -> R.color.connecting
            BleManager.State.SCANNING -> R.color.connecting
            else -> R.color.err
        }
        // ⚠ GradientDrawable 的 tint 存放在 ConstantState 里，而同一 drawable 资源的所有实例共享它 ——
        // 不 mutate() 直接 setTint，会把 App 里所有用 @drawable/bg_pill 的控件（设备胶囊、通道数、
        // 输入框…）一起染色（连接后全变绿、断开后全变红）。mutate() 让状态点拿到独立 ConstantState。
        b.statusDot.background?.mutate()?.setTint(ContextCompat.getColor(this, colorRes))
        b.btnConnect.text = "设备"
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectHandler.removeCallbacksAndMessages(null)
        syncHandler.removeCallbacksAndMessages(null)
        // ⚠ 上传进度 Runnable 每 50ms 自我重排并捕获 this：不取消的话 Activity 销毁后
        //   仍会持续往已关闭的 GATT 写，直到整个文件"发完"（500KB ≈ 125 秒）。
        uploadHandler.removeCallbacksAndMessages(null)
        // ⚠ 断开 listener + 停周期扫描：否则 BleManager 会持有本 Activity，
        //   且它的周期扫描 Runnable 会自我重排、在 Activity 死后无限扫描下去。
        ble.listener = null
        ble.stopPeriodicScan()
        ble.close()
        // 单例不能持有已销毁 Activity 的引擎
        FxEngine.detachEngine()
    }

    /**
     * 返回键：灯库编辑页(5) 回到设置页，实例管理页(6) 回到推子页；
     * 其余页面保持系统默认（退出 App）。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (b.pager.currentItem) {
            Page.EDITOR -> { b.pager.currentItem = Page.SETTINGS; return }
            Page.INSTANCES -> { b.pager.currentItem = Page.FADER; return }
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
                b.pager.currentItem = Page.FADER
                toast("已应用: ${def.name}" + if (inst != null) "（实例 ${inst.label()}）" else "")
            },
            onSelectModeChanged = { on ->
                selectMode = on
                fixb.batchBar.visibility = if (on) View.VISIBLE else View.GONE
                fixb.btnEdit.text = if (on) "取消" else "批量"
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
            // 灯库编辑已移到“设置”页，这里只负责批量选择（长按条目也会进入）
            toggleSelectMode()
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
    private var fixtureFmt = "xml"            // 灯具页格式筛选：""=全部 / xml / d4 / r20（默认选中 xml）

    /** 切换灯具格式筛选：点击选中（蓝圈），再次点击同一格式取消（回到全部，白圈）。 */
    private fun setFixtureFmt(fmt: String) {
        fixtureFmt = if (fixtureFmt == fmt) "" else fmt
        updateFmtTabs()
        refreshFixturePage()
    }

    /**
     * 胶囊标签页统一风格（灯具格式 / 存储标签 / 语言）。
     * 选中 = 空心蓝圈 + 粗体；未选中 = 空心白圈 + 常规。
     *
     * 这段样式原先在 updateFmtTabs / refreshStoragePage / refreshSettingsPage 里
     * 各抄了一份（三份实现、两处细节还不一致），任何视觉调整都要改三遍。
     *
     * @param setTextColor true 时显式指定文字颜色（语言标签依赖主题默认色，传 false）
     */
    private fun stylePillTab(btn: TextView, active: Boolean, setTextColor: Boolean = true) {
        btn.setBackgroundResource(
            if (active) R.drawable.bg_pill_outline_accent else R.drawable.bg_pill_outline_white
        )
        if (setTextColor) btn.setTextColor(getColor(R.color.text))
        btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    /** 灯具页格式标签（XML/D4/R20）统一风格 + 选中提示。 */
    private fun updateFmtTabs() {
        stylePillTab(fixb.btnFmtXml, fixtureFmt == "xml")
        stylePillTab(fixb.btnFmtD4, fixtureFmt == "d4")
        stylePillTab(fixb.btnFmtR20, fixtureFmt == "r20")
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
        // fixtureFmt 为空 = 显示全部格式（此时三个格式标签均为未选中白色空心）
        fixtureAdapter?.submitList(
            if (fixtureFmt.isEmpty()) fixtureStore.fixtures
            else fixtureStore.fixturesOfFormat(fixtureFmt)
        )
        updateFmtTabs()
        renderInstanceBar()
        refreshMasterScope()
    }

    /**
     * 导入灯库：**先体检、给用户看报告、确认后才写盘**。
     *
     * 以前的流程是"直接导"，任何解析不出的文件都静默消失，最后只弹一句
     * "未找到可识别的灯库文件" —— 用户没法知道压缩包里到底哪个文件有问题。
     * 现在把 [ZipScan] 的报告摊开：共多少文件、多少可导入、每个跳过的原因。
     */
    private fun importFixtureZips(uris: List<Uri>) {
        // 逐个读取 + 体检（不写盘）
        val scanned = mutableListOf<Pair<ByteArray, ZipScan.Report>>()
        for (uri in uris) {
            try {
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                var name = ""
                try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: ""
                    }
                } catch (_: Exception) {}
                if (name.isEmpty()) name = uri.lastPathSegment ?: ""
                scanned.add(raw to fixtureStore.inspect(raw.inputStream(), name))
            } catch (e: Exception) {
                toast("读取失败: ${e.message}")
            }
        }
        if (scanned.isEmpty()) {
            toast("没读到文件")
            return
        }

        val totalImportable = scanned.sumOf { it.second.fixtureCount }
        if (totalImportable == 0) {
            // 一个都导不进来：直接把问题清单摆出来，而不是只说"未找到"
            showImportReportDialog(scanned, totalImportable, allowImport = false)
            return
        }
        showImportReportDialog(scanned, totalImportable, allowImport = true)
    }

    /**
     * 导入体检报告对话框。
     *
     * @param allowImport false 时只报告问题（没有可导入的内容），确认按钮变成"知道了"
     */
    private fun showImportReportDialog(
        scanned: List<Pair<ByteArray, ZipScan.Report>>,
        totalImportable: Int,
        allowImport: Boolean
    ) {
        val body = StringBuilder()
        for ((_, report) in scanned) {
            if (scanned.size > 1) body.append("■ ${report.sourceName.ifEmpty { "压缩包" }}\n")
            body.append(report.summary()).append('\n')

            // 可导入的列出来（最多 12 条，避免对话框过长）
            val ok = report.importableFiles
            if (ok.isNotEmpty()) {
                body.append("\n✅ 将导入：\n")
                ok.take(12).forEach { e ->
                    val names = e.fixtures.joinToString("、") { "${it.name}(${it.channelCount}CH)" }
                    body.append("  · ${e.path.substringAfterLast('/')} → $names\n")
                }
                if (ok.size > 12) body.append("  … 另有 ${ok.size - 12} 个\n")
            }

            // 问题条目：这是本次修复的重点 —— 每个跳过都要有原因
            val problems = report.problems
            if (problems.isNotEmpty()) {
                body.append("\n⚠ 无法导入 ${problems.size} 个：\n")
                problems.take(15).forEach { e ->
                    val why = when (val k = e.kind) {
                        is ZipScan.Kind.Unsupported -> k.reason
                        is ZipScan.Kind.Unreadable -> k.reason
                        ZipScan.Kind.NestedZip -> "嵌套压缩包（本 App 不递归展开，请先解压再导入）"
                        else -> "跳过"
                    }
                    body.append("  · ${e.path.substringAfterLast('/')}：$why\n")
                }
                if (problems.size > 15) body.append("  … 另有 ${problems.size - 15} 个\n")
            }
            body.append('\n')
        }

        val tv = TextView(this).apply {
            text = body.toString().trimEnd()
            setTextIsSelectable(true)   // 方便用户复制文件路径
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            textSize = 12f
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, pad / 2, 0, 0)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (allowImport) "导入灯库：确认内容" else "没有可导入的灯库")
            .setView(scroll)
            .setPositiveButton(if (allowImport) "导入 $totalImportable 个灯具" else "知道了") { _, _ ->
                if (allowImport) doImportScanned(scanned)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用户确认后真正写盘。 */
    private fun doImportScanned(scanned: List<Pair<ByteArray, ZipScan.Report>>) {
        var total = 0
        val failures = mutableListOf<String>()
        for ((data, report) in scanned) {
            try {
                val res = fixtureStore.importScanned(data, report)
                total += res.fixtures.size
                res.failed.forEach { (path, why) -> failures.add("${path.substringAfterLast('/')}: $why") }
            } catch (e: Exception) {
                failures.add("${report.sourceName}: ${e.message}")
                e.printStackTrace()
            }
        }
        refreshFixturePage()
        when {
            failures.isEmpty() -> toast("导入了 $total 个灯具")
            total > 0 -> toast("导入了 $total 个灯具，另有 ${failures.size} 个失败")
            else -> showFailureDetails(failures)
        }
    }

    /** 有失败且一个都没成功时，把具体原因列出来（别只弹一句"导入失败"）。 */
    private fun showFailureDetails(failures: List<String>) {
        val msg = failures.take(15).joinToString("\n") { "· $it" } +
                  if (failures.size > 15) "\n… 另有 ${failures.size - 15} 个" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("导入失败（${failures.size} 个文件）")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
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
        val tvChCount = holder.findViewById<TextView>(R.id.tvInstChCount)
        val btnBandA = holder.findViewById<TextView>(R.id.btnBandA)
        val btnBandB = holder.findViewById<TextView>(R.id.btnBandB)
        val etAddr = holder.findViewById<EditText>(R.id.etInstAddr)
        val etCount = holder.findViewById<EditText>(R.id.etInstCount)
        val tvPreview = holder.findViewById<TextView>(R.id.tvInstPreview)
        spType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        // 通道 A/B（A = 宇宙1 口，B = 宇宙2 口），每个宇宙各自编号 1-512
        var band = 1
        fun paintBand() {
            fun t(btn: TextView, on: Boolean) {
                btn.setBackgroundResource(if (on) R.drawable.bg_pill_outline_accent
                                          else R.drawable.bg_pill_outline_white)
                btn.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
            t(btnBandA, band == 1)
            t(btnBandB, band == 2)
        }

        // 输入时就限制成合法范围，避免出现 9999 这种明显无效值（等用户填完才报错体验差）
        fun bindRangeFilter(et: EditText, lo: Int, hi: Int) {
            et.filters = arrayOf(android.text.InputFilter { src, _, _, _, _, _ ->
                val s = src.toString()
                if (s.isEmpty()) return@InputFilter null
                val joined = et.text.toString() + s
                val n = joined.toIntOrNull()
                when {
                    n == null -> ""                      // 非数字直接丢弃
                    n > hi -> ""                         // 超上限丢弃（避免拼出 1003）
                    else -> null
                }
            })
        }
        bindRangeFilter(etAddr, 1, DmxProtocol.UNIVERSE_SIZE)
        bindRangeFilter(etCount, 1, MAX_BATCH_ADD)

        // 实时回显：该灯型占多少通道、本宇宙内每台落在哪个地址段。
        // 算术与文案都在 InstanceForm（纯逻辑、有单测），这里只负责取控件值 + 贴文本。
        fun refreshPreview() {
            val def = defs[spType.selectedItemPosition.coerceIn(0, defs.size - 1)]
            val form = InstanceForm.of(
                def,
                etAddr.text.toString().toIntOrNull() ?: 1,
                etCount.text.toString().toIntOrNull() ?: 1,
                band
            )
            tvChCount.text = form.fixtureHintText()
            tvPreview.text = form.summaryText()
        }
        btnBandA.setOnClickListener { band = 1; paintBand(); refreshPreview() }
        btnBandB.setOnClickListener { band = 2; paintBand(); refreshPreview() }
        paintBand()
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshPreview()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        for (et in listOf(etAddr, etCount)) {
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshPreview()
            })
        }
        // 软键盘会遮住对话框底部的按钮（键盘高约 1240px），所以数量框输完按回车
        // 直接收键盘，用户不必去够被挡住的"添加"。
        etCount.setOnEditorActionListener { _, _, _ ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etCount.windowToken, 0)
            etCount.clearFocus()
            true
        }
        refreshPreview()

        MaterialAlertDialogBuilder(this)
            .setTitle("添加灯具实例")
            .setView(holder)
            .setPositiveButton("添加") { _, _ ->
                val def = defs[spType.selectedItemPosition.coerceIn(0, defs.size - 1)]
                val form = InstanceForm.of(
                    def,
                    etAddr.text.toString().toIntOrNull() ?: 1,
                    etCount.text.toString().toIntOrNull() ?: 1,
                    band
                )
                val err = fixtureStore.addInstances(def.id, def.name, form.addr, form.numInstances, band)
                if (err != null) {
                    toast(err)
                } else {
                    form.addedToast(def.name)?.let { toast(it) }
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
        private val onSelectModeChanged: (Boolean) -> Unit = {}
    ) : RecyclerView.Adapter<FixtureAdapter.VH>() {
        private var items = listOf<FixtureDef>()
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
            holder.b.tvFixtureName.text = "${def.manufacturer} ${def.name}"
            holder.b.tvFixtureChannels.text = "${def.channelCount} CH"

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

        // 灯库编辑已移到设置页，这里提供返回设置页的入口
        edb.btnBackFromEditor.setOnClickListener { b.pager.currentItem = Page.SETTINGS }

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
        imfb.rvInstanceMgr.layoutManager = LinearLayoutManager(this)
    }

    // ---------------- 设置页 ----------------
    private fun wireSettingsPage() {
        // 语言：全局中英文切换（不再每个灯库单独设置）
        stb.btnLangZh.setOnClickListener { setLanguage(true) }
        stb.btnLangEn.setOnClickListener { setLanguage(false) }

        // 灯库编辑（从“灯具”页移入设置页）
        stb.btnOpenEditor.setOnClickListener { b.pager.currentItem = Page.EDITOR }

        // 推子页排列方式
        stb.btnLayoutChannel.setOnClickListener {
            val id = layoutFixtureId()
            if (id == null) { toast("请先在灯具页应用一个灯库/实例"); return@setOnClickListener }
            layoutStore.setActive(id, null)
            applyFaderLayout(id)
            refreshSettingsPage()
            toast("已切换为：按通道顺序")
        }
        stb.btnLayoutCustom.setOnClickListener {
            val id = layoutFixtureId()
            if (id == null) { toast("请先在灯具页应用一个灯库/实例"); return@setOnClickListener }
            val first = layoutStore.presets(id).firstOrNull()
            if (first == null) {
                toast("还没有排列预设，请先“编辑顺序”并保存")
                return@setOnClickListener
            }
            layoutStore.setActive(id, first.name)
            applyFaderLayout(id)
            refreshSettingsPage()
            toast("已切换为自定义顺序：${first.name}")
        }
        stb.btnLayoutEdit.setOnClickListener { editLayoutOrder() }
        stb.btnLayoutSave.setOnClickListener { saveLayoutPreset() }
        stb.btnLayoutDelete.setOnClickListener { deleteLayoutPreset() }
        stb.spLayoutPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (layoutUpdating) return
                val id0 = layoutFixtureId() ?: return
                // 下拉第 0 项固定是“按通道顺序”，其余为各预设
                if (pos <= 0) {
                    layoutStore.setActive(id0, null)
                } else {
                    val name = layoutStore.presets(id0).getOrNull(pos - 1)?.name ?: return
                    layoutStore.setActive(id0, name)
                }
                applyFaderLayout(id0)
                refreshSettingsPage()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        refreshSettingsPage()
    }

    private var layoutUpdating = false
    private var layoutAdapter: ArrayAdapter<String>? = null

    private fun applySavedLanguage() {
        channelAdapter.translated = appSettings.getBoolean("translated", true)
        channelAdapter.refresh()
    }

    private fun setLanguage(zh: Boolean) {
        appSettings.edit().putBoolean("translated", zh).apply()
        channelAdapter.translated = zh
        channelAdapter.refresh()
        refreshSettingsPage()
        toast(if (zh) "已切换为中文" else "Switched to English")
    }

    /** 当前用于排列预设的灯型 id（优先选中实例的灯型，其次当前灯库）。 */
    private fun layoutFixtureId(): String? {
        val inst = currentInstanceId?.let { id -> fixtureStore.instances().find { it.id == id } }
        if (inst != null) return fixtureStore.fixtureOf(inst)?.id
        return fixtureStore.currentFixture?.id
    }

    private fun refreshSettingsPage() {
        val zh = appSettings.getBoolean("translated", true)
        // 语言标签不显式指定文字颜色（沿用主题默认），故 setTextColor = false
        stylePillTab(stb.btnLangZh, zh, setTextColor = false)
        stylePillTab(stb.btnLangEn, !zh, setTextColor = false)

        val id = layoutFixtureId()
        val activeName = id?.let { layoutStore.active(it) }
        stb.tvLayoutMode.text = when {
            id == null -> "请先应用灯库/实例"
            activeName == null -> "按通道顺序"
            else -> "自定义顺序：$activeName"
        }
        val names = id?.let { layoutStore.presets(it).map { p -> p.name } } ?: emptyList()
        // Spinner 内容固定为：按通道顺序 + 各预设
        val opts = listOf("按通道顺序") + names
        val ad = layoutAdapter
        layoutUpdating = true
        if (ad == null) {
            layoutAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opts.toMutableList()).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                stb.spLayoutPreset.adapter = it
            }
        } else {
            ad.clear(); ad.addAll(opts); ad.notifyDataSetChanged()
        }
        stb.spLayoutPreset.setSelection(
            if (activeName == null) 0 else (opts.indexOf(activeName).coerceAtLeast(0)))
        layoutUpdating = false

        stb.tvLayoutHint.text = if (id == null)
            "先到“灯具”页应用一个灯库或选中一个实例，再回来调整推子页的通道排列顺序。"
        else
            "当前灯型：${fixtureStore.fixtures.find { it.id == id }?.name ?: id}，共 ${channelAdapter.channelCount()} 通道。\n" +
            "点“编辑顺序”用 ↑/↓ 调整通道位置，“保存为预设”存下这套排列，之后可随时一键切换。"
    }

    /** 编辑推子页通道排列顺序（↑/↓ 调整）。 */
    private fun editLayoutOrder() {
        val id = layoutFixtureId()
        if (id == null) { toast("请先在灯具页应用一个灯库/实例"); return }
        val n = channelAdapter.channelCount()
        if (n <= 1) { toast("通道数不足，无需排列"); return }
        val order = channelAdapter.currentOrder()
        val labels = channelAdapter.channelLabels().toMutableList()

        val ctx = this
        val pad = (8 * resources.displayMetrics.density).toInt()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt())
        }

        fun render() {
            list.removeAllViews()
            for (i in labels.indices) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(pad, pad, pad, pad)
                }
                row.addView(TextView(ctx).apply {
                    text = labels[i]
                    setTextColor(getColor(R.color.text))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(ctx).apply {
                    text = "↑"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(getColor(R.color.accent))
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        if (i > 0) {
                            val t = labels[i]; labels[i] = labels[i - 1]; labels[i - 1] = t
                            val o = order[i]; order[i] = order[i - 1]; order[i - 1] = o
                            render()
                        }
                    }
                })
                row.addView(TextView(ctx).apply {
                    text = "↓"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(getColor(R.color.accent))
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        if (i < labels.size - 1) {
                            val t = labels[i]; labels[i] = labels[i + 1]; labels[i + 1] = t
                            val o = order[i]; order[i] = order[i + 1]; order[i + 1] = o
                            render()
                        }
                    }
                })
                list.addView(row)
            }
        }
        render()

        MaterialAlertDialogBuilder(this)
            .setTitle("调整推子页顺序")
            .setView(scroll)
            .setPositiveButton("应用") { _, _ ->
                channelAdapter.applyOrder(order)
                toast("已应用新的排列顺序（保存为预设后可复用）")
            }
            .setNeutralButton("保存为预设") { _, _ -> saveLayoutPreset(order) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存当前顺序为预设（默认名字 = 灯型名）。 */
    private fun saveLayoutPreset(order: IntArray? = null) {
        val id = layoutFixtureId()
        if (id == null) { toast("请先在灯具页应用一个灯库/实例"); return }
        val o = order ?: channelAdapter.currentOrder()
        val defName = fixtureStore.fixtures.find { it.id == id }?.name ?: "排列预设"
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(defName)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存排列预设")
            .setMessage("把这套通道顺序保存下来，之后可一键切换")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val nm = input.text.toString().trim().ifEmpty { defName }
                layoutStore.save(id, nm, o)
                layoutStore.setActive(id, nm)
                channelAdapter.applyOrder(o)
                refreshSettingsPage()
                toast("已保存排列预设：$nm")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteLayoutPreset() {
        val id = layoutFixtureId() ?: return
        val name = layoutStore.active(id) ?: run {
            toast("当前没有生效的排列预设"); return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage("删除排列预设「$name」？")
            .setPositiveButton("删除") { _, _ ->
                layoutStore.delete(id, name)
                channelAdapter.applyOrder(layoutStore.orderFor(id))
                refreshSettingsPage()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 刷新实例管理页列表（按 DMX 地址排序，勾选 = 参与同时控制）。 */
    /**
     * 实例管理页列表。
     *
     * 两种模式共用同一个勾选框，避免多塞一个控件：
     * - **平时**：勾选 = 该实例参与同时控制（selectedInstanceIds）
     * - **长按进入多选后**：勾选 = 待删除（instEditSel）
     *   此时显示批量操作栏（全选 / 取消全选 / 删除选中 / 完成）
     */
    private fun refreshInstanceMgrList() {
        val insts = fixtureStore.instances().sortedBy { it.globalAddr() }
        imfb.btnInstSelectAll.setOnClickListener {
            instEditSel.addAll(insts.map { it.id })
            refreshInstanceMgrList()
        }
        imfb.btnInstSelectNone.setOnClickListener {
            instEditSel.clear()
            refreshInstanceMgrList()
        }
        imfb.btnInstDeleteSel.setOnClickListener {
            if (instEditSel.isEmpty()) { toast("请先勾选要删除的实例"); return@setOnClickListener }
            confirmDeleteInstances(instEditSel.toList())
        }
        imfb.btnInstExitEdit.setOnClickListener {
            instEditMode = false
            instEditSel.clear()
            refreshInstanceMgrList()
        }

        imfb.instBatchBar.visibility = if (instEditMode) View.VISIBLE else View.GONE
        imfb.tvInstEditHint.visibility = if (instEditMode) View.VISIBLE else View.GONE
        imfb.tvInstListTitle.text = if (instEditMode) "多选删除模式"
                                   else "参与同时控制的实例（勾选，按地址顺序）"
        imfb.tvInstCount.text = "共 ${insts.size} 台"
        imfb.tvInstSelCount.text = "已选 ${instEditSel.size}"

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
                val tvName = root.findViewById<TextView>(R.id.tvInstName)
                val tvInfo = root.findViewById<TextView>(R.id.tvInstInfo)
                val tvCheck = root.findViewById<TextView>(R.id.tvInstCheck)
                val btnEdit = root.findViewById<TextView>(R.id.btnInstEdit)
                tvName.text = inst.name
                tvInfo.text = "${inst.label()}  ${def?.channelCount ?: 0}CH  ${def?.name ?: ""}"
                btnEdit.visibility = if (instEditMode) View.GONE else View.VISIBLE

                // 选中状态用整行高亮 + 右侧标记表示（勾选框已去掉）
                val checked = if (instEditMode) inst.id in instEditSel
                              else inst.id in selectedInstanceIds
                root.setBackgroundResource(if (checked) R.drawable.bg_card_active else R.drawable.bg_card)
                tvCheck.text = when {
                    instEditMode && checked -> "✓ 待删"
                    checked -> "✓ 参与控制"
                    else -> ""
                }
                tvCheck.setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (instEditMode) R.color.err else R.color.ok))

                root.setOnClickListener {
                    if (instEditMode) toggleEditSel(inst.id) else toggleControlSel(inst.id)
                }
                // 长按 = 进入多选并勾上这一行
                root.setOnLongClickListener {
                    if (!instEditMode) {
                        instEditMode = true
                        instEditSel.clear()
                        instEditSel.add(inst.id)
                        toast("已进入多选，可「全选」后删除")
                    } else {
                        toggleEditSel(inst.id)
                    }
                    refreshInstanceMgrList()
                    true
                }
                btnEdit.setOnClickListener { showEditInstanceDialog(inst) }
            }
        }
    }

    /** 多选模式下切换某行的待删除状态。 */
    private fun toggleEditSel(id: String) {
        if (!instEditSel.remove(id)) instEditSel.add(id)
        refreshInstanceMgrList()
    }

    /** 平时切换某实例是否参与同时控制。 */
    private fun toggleControlSel(id: String) {
        if (!selectedInstanceIds.remove(id)) selectedInstanceIds.add(id)
        currentInstanceId = groupInstances().firstOrNull()?.id
        if (selectedInstanceIds.isNotEmpty()) {
            applySelectedInstance()
            refreshInstanceBarStyles()
        }
        refreshProgramPage()
        refreshFxPage()
        refreshInstanceMgrList()
    }

    /** 删除前的二次确认（批量时列出台数，避免误删）。 */
    private fun confirmDeleteInstances(ids: List<String>) {
        val all = fixtureStore.instances()
        val names = ids.mapNotNull { id -> all.find { it.id == id }?.name }
        val detail = if (names.size <= 5) names.joinToString("、")
                     else names.take(5).joinToString("、") + " 等 ${names.size} 台"
        MaterialAlertDialogBuilder(this)
            .setTitle("删除 ${ids.size} 个实例？")
            .setMessage("$detail\n\n该操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                // 与单台删除保持同一套清理：停效果/程序 → 删数据 → 清选择 → 刷 UI。
                // （早期批量删除漏了停止与清模式，导致删光后推子页仍停在灯具模式）
                val victims = all.filter { it.id in ids }
                for (v in victims) {
                    FxEngine.stop(v.id)
                    if (playingSlots.remove(v.slot)) engine.sendProgStop(v.slot)
                }
                updatePlayBtnUI()

                fixtureStore.removeInstances(ids)
                selectedInstanceIds.removeAll(ids.toSet())
                instEditSel.removeAll(ids.toSet())
                if (currentInstanceId in ids) {
                    currentInstanceId = groupInstances().firstOrNull()?.id
                }
                instEditMode = false
                toast("已删除 ${ids.size} 个实例")

                renderInstanceBar()
                refreshInstanceMgrList()
                refreshFixturePage()
                // 一台不剩 → 回到裸通道模式（隐藏实例条、恢复通道预设、清空灯具页标签）
                if (fixtureStore.instances().isEmpty()) {
                    clearFixtureMode()
                } else if (selectedInstanceIds.isEmpty()) {
                    // 还有灯但全被删了：退回未选状态，避免推子页继续显示已删除的实例
                    clearFixtureMode()
                } else if (victims.any { it.id == currentInstanceId } || currentInstanceId == null) {
                    applySelectedInstance()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 改单个实例的通道 / 地址（不用删了重建）。 */
    private fun showEditInstanceDialog(inst: FixtureInstance) {
        val def = fixtureStore.fixtureOf(inst) ?: return
        val holder = layoutInflater.inflate(R.layout.dialog_add_instance, null)
        val spType = holder.findViewById<Spinner>(R.id.spInstType)
        val tvChCount = holder.findViewById<TextView>(R.id.tvInstChCount)
        val btnBandA = holder.findViewById<TextView>(R.id.btnBandA)
        val btnBandB = holder.findViewById<TextView>(R.id.btnBandB)
        val etAddr = holder.findViewById<EditText>(R.id.etInstAddr)
        val etCount = holder.findViewById<EditText>(R.id.etInstCount)
        val tvPreview = holder.findViewById<TextView>(R.id.tvInstPreview)
        // 改址只针对这一台：灯型和数量都固定
        spType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                                      arrayOf("${def.manufacturer} ${def.name} (${def.mode})"))
        spType.isEnabled = false
        etCount.setText("1")
        etCount.isEnabled = false
        var band = inst.universe
        fun paintBand() {
            fun t(btn: TextView, on: Boolean) {
                btn.setBackgroundResource(if (on) R.drawable.bg_pill_outline_accent
                                          else R.drawable.bg_pill_outline_white)
                btn.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
            t(btnBandA, band == 1); t(btnBandB, band == 2)
        }
        fun refresh() {
            val ch = def.channelCount.coerceAtLeast(1)
            val a = etAddr.text.toString().toIntOrNull() ?: 1
            val bn = if (band == 1) "A" else "B"
            tvChCount.text = "「${inst.name}」占 $ch 个通道"
            tvPreview.text = if (a + ch - 1 > DmxProtocol.UNIVERSE_SIZE)
                "⚠ $bn 通道放不下：需要 $a~${a + ch - 1}，本宇宙上限 ${DmxProtocol.UNIVERSE_SIZE}"
            else "$bn 通道 $a ~ ${a + ch - 1}"
        }
        btnBandA.setOnClickListener { band = 1; paintBand(); refresh() }
        btnBandB.setOnClickListener { band = 2; paintBand(); refresh() }
        etAddr.setText(inst.addr.toString())
        etAddr.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        paintBand(); refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle("修改地址")
            .setView(holder)
            .setPositiveButton("保存") { _, _ ->
                val a = (etAddr.text.toString().toIntOrNull() ?: 1)
                    .coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
                val before = inst
                fixtureStore.updateInstance(inst.copy(addr = a, universe = band))
                val after = fixtureStore.instances().find { it.id == inst.id }
                if (after == null || after.addr != a || after.universe != band) {
                    toast("保存失败：${if (band == 1) "A" else "B"} 通道 $a 越界或与已有实例重叠")
                } else {
                    toast("已改为 ${after.label()}")
                }
                refreshInstanceMgrList()
                renderInstanceBar()
                refreshFixturePage()
                if (before.id == currentInstanceId) applySelectedInstance()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun wireStoragePage() {
        var mscEnabled = false
        fixb.swMsc.setOnClickListener {
            if (!mscEnabled) {
                if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
                engine.sendRawCmd(0x30, 1)
                mscEnabled = true
                fixb.swMsc.text = "关闭"
                fixb.swMsc.setBackgroundColor(getColor(R.color.ok))
                fixb.tvMscStatus.text = "已开启 — 控台可访问 ESP32 灯库文件"
                fixb.tvMscStatus.setTextColor(getColor(R.color.ok))
            } else {
                engine.sendRawCmd(0x30, 0)
                mscEnabled = false
                fixb.swMsc.text = "开启"
                fixb.swMsc.setBackgroundColor(getColor(R.color.surface2))
                fixb.tvMscStatus.text = "关闭"
                fixb.tvMscStatus.setTextColor(getColor(R.color.textDim))
            }
        }

        // 三个同级页签：App灯库 / 设备灯库 / 文件管理
        fixb.btnAppLibs.setOnClickListener {
            storageMode = 0
            fixb.btnNewFolder.visibility = View.GONE
            refreshStoragePage()
        }

        fixb.btnDeviceLibs.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
            storageMode = 1
            curPath = ""            // 设备灯库固定显示根目录
            fixb.btnNewFolder.visibility = View.GONE
            refreshDeviceFiles()
        }

        fixb.btnFileMgr.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast("请先连接设备"); return@setOnClickListener }
            storageMode = 2
            fixb.btnNewFolder.visibility = View.VISIBLE
            refreshDeviceFiles()
        }

        // 返回上级目录（文件管理页签）
        fixb.btnGoUp.setOnClickListener {
            if (storageMode != 2) return@setOnClickListener
            if (curPath.isEmpty()) { toast("已在根目录"); return@setOnClickListener }
            curPath = curPath.substringBeforeLast('/', "")
            refreshDeviceFiles()
        }

        // 新建文件夹
        fixb.btnNewFolder.setOnClickListener {
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
        fixb.tvListTitle.text = if (storageMode == 2) "ESP32 文件管理" else "ESP32 设备灯库"
        fixb.tvListHint.text = "正在获取..."
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
        fixb.btnNewFolder.visibility = if (storageMode == 2) View.VISIBLE else View.GONE
        fixb.tvListTitle.text = if (storageMode == 2) "ESP32 文件管理" else "ESP32 设备灯库"
        refreshStoragePage()
    }

    private fun refreshStoragePage() {
        updateStorageTabs()
        // 三个页签共用"灯具"页：App灯库 → 灯具列表；设备灯库/文件管理 → 设备文件列表
        fixb.layoutAppLibs.visibility = if (storageMode == 0) View.VISIBLE else View.GONE
        fixb.layoutDeviceFiles.visibility = if (storageMode == 0) View.GONE else View.VISIBLE
        // 0=App灯库(本地列表) 1=设备灯库 2=文件管理：统一按 storageMode 决定显示哪边，
        // 否则从设备页切回 App 灯库时 showDeviceFiles 残留 true 导致本地灯库不显示
        showDeviceFiles = (storageMode != 0)
        // 设备灯库页只显示灯库文件，文件夹仅出现在文件管理页
        val shownDev = if (storageMode == 1) devFiles.filter { !it.isDir } else devFiles
        val libs = fixtureStore.fixtures
        val ctx = this
        fixb.rvFileList.layoutManager = LinearLayoutManager(ctx)
        fixb.rvFileList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
                            uploadFileData("${lib.id}.xml", xml.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
        if (showDeviceFiles) {
            fixb.btnNewFolder.visibility = if (storageMode == 2) View.VISIBLE else View.GONE
            fixb.tvListTitle.text = if (storageMode == 2) {
                val p = if (curPath.isEmpty()) "/fw" else "/fw/$curPath"
                "ESP32 文件管理  $p"
            } else "ESP32 设备灯库"
            fixb.btnGoUp.visibility = if (storageMode == 2 && curPath.isNotEmpty()) View.VISIBLE else View.GONE
            fixb.tvListHint.text = when {
                shownDev.isEmpty() && storageMode == 1 -> "设备上暂无灯库，请先上传"
                shownDev.isEmpty() -> "设备上暂无文件"
                storageMode == 1 -> "${shownDev.size} 个灯库 — 点按下载到 App"
                else -> "${shownDev.size} 个条目 — 点文件夹进入，长按操作"
            }
        } else {
            fixb.btnNewFolder.visibility = View.GONE
            fixb.btnGoUp.visibility = View.GONE
            fixb.tvListTitle.text = "App 端已保存灯库"
            fixb.tvListHint.text = if (libs.isEmpty()) "暂无灯库" else "${libs.size} 个 — 点按上传到设备"
        }
    }

    /** 存储页三个标签（App灯库/设备灯库/文件管理）统一风格 + 选中提示。
     *  选中 = 空心蓝圈 + 白粗体字；未选中 = 空心白圈 + 白字。
     *  标签用 TextView（非 Button）——Button 的 Material backgroundTint 会染色 shape 背景。 */
    private fun updateStorageTabs() {
        stylePillTab(fixb.btnAppLibs, storageMode == 0)
        stylePillTab(fixb.btnDeviceLibs, storageMode == 1)
        stylePillTab(fixb.btnFileMgr, storageMode == 2)
    }

    /**
     * 上传单个文件数据（分块 200B，间隔 50ms；上传到当前目录 curPath）。
     *
     * ⚠ 用 Activity 级 [uploadHandler] 而不是局部 Handler：局部 Handler 无法在 onDestroy
     *   里取消，会导致 Activity 销毁后仍持续写已关闭的 GATT。
     */
    private fun uploadFileData(fileName: String, data: ByteArray, onDone: (() -> Unit)? = null) {
        engine.sendUploadStart(curPath, fileName, data.size)
        var off = 0
        val sendChunk = object : Runnable {
            override fun run() {
                if (off >= data.size) {
                    engine.sendUploadEnd()
                    onDone?.invoke()
                    return
                }
                val end = minOf(off + UPLOAD_CHUNK_SIZE, data.size)
                engine.sendUploadChunk(off / UPLOAD_CHUNK_SIZE, data.copyOfRange(off, end))
                off = end
                uploadHandler.postDelayed(this, UPLOAD_CHUNK_INTERVAL_MS)
            }
        }
        uploadHandler.post(sendChunk)
        toast("正在上传 ${fileName}...")
    }

    /** 顺序上传一个灯具的全部原始文件（xml/d4/r20）。 */
    private fun uploadFilesSequential(files: List<File>, label: String) {
        fun next(idx: Int) {
            if (idx >= files.size) { toast("全部上传完成: $label"); return }
            val f = files[idx]
            uploadFileData(f.name, f.readBytes()) {
                uploadHandler.postDelayed({ next(idx + 1) }, 300)
            }
        }
        next(0)
    }
}
