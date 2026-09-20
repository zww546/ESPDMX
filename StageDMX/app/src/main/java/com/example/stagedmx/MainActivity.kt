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
    /**
     * 效果预设弹窗的根视图（v9：预设从页面搬进弹窗）。
     *
     * 这里用 findViewById 而不是 ViewBinding：dialog_* 的绑定类在本工程的
     * 增量构建里没被生成（资源本身没报错），与其和构建缓存较劲，不如直接取视图 ——
     * 弹窗只有 5 个控件，findViewById 行为确定。
     */
    private lateinit var fxp: View
    private val fxpSpinner get() = fxp.findViewById<Spinner>(R.id.spFxPreset)
    private val fxpSave get() = fxp.findViewById<View>(R.id.btnFxPresetSave)
    private val fxpApply get() = fxp.findViewById<View>(R.id.btnFxPresetApply)
    private val fxpOff get() = fxp.findViewById<View>(R.id.btnFxPresetOff)
    private val fxpDel get() = fxp.findViewById<View>(R.id.btnFxPresetDel)
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

    // ---- 灯具管理页页签 / RDM ----
    /** true = 已配接列表，false = RDM 设备列表。 */
    private var instTabPatched = true
    private var rdmDevices: List<RdmDevice> = emptyList()
    private var rdmScanning = false
    private var rdmLastError = ""
    /** 固件当前是否处于 RDM 模拟模式（没有真实 RDM 灯具时返回虚拟灯具）。 */
    private var rdmSimulated = false
    /** App 侧记住的模拟开关状态（与固件同步）。 */
    private var rdmSimOn = true
    // 状态总览条的数据（来自固件 0x82）
    private var devFxCount = 0
    /** RDM 建实例时地址冲突是否直接覆盖（设置页开关，默认关闭=先询问） */
    private var rdmOverwrite = false
    private var devDmxOk = -1
    private var devDmxFails = 0L
    private var devFps1 = -1
    private var devFps2 = -1
    /** 刚写入的那批"设备 → 新地址"，用于接着问"要不要建实例"。 */
    private var rdmPendingAssign: List<Pair<RdmDevice, Int>>? = null
    /** RDM 排序（编辑顺序）模式：拖动调整灯具顺序，地址按顺序自动分配。 */
    private var rdmReorderMode = false
    private var rdmOrder: List<RdmDevice> = emptyList()
    private var rdmTouchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null
    private lateinit var rdmStore: RdmStore

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

    /**
     * 双宇宙开关：关闭时只使用 A 通道（宇宙 1，512 路），B 通道相关 UI 全部隐藏。
     * 现场只接了一条 DMX 线时关掉它，避免把灯误配到 B 通道却没有输出。
     */
    private var dualUniverse: Boolean
        get() = appSettings.getBoolean("dual_universe", true)
        set(v) { appSettings.edit().putBoolean("dual_universe", v).apply() }

    /** RDM 开关（设置页）。 */
    private var rdmEnabled: Boolean
        get() = rdmStore.enabled
        set(v) { rdmStore.enabled = v }

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
        else toast(Lang.t(R.string.k_bluetooth_permission_is_required_to_scan))
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (ble.isBluetoothOn()) onConnectClicked() else toast(Lang.t(R.string.k_turn_on_bluetooth))
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
        rdmStore = RdmStore(this)
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
                toast(Lang.t(R.string.k_auto_updated_1_s_fixture_types, reimportCount))
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
        // 必须先 Lang.init：它用 applicationContext 建立"语言覆盖 Context"并遍历
        // R.string 生成中英映射，之后 applySavedLanguage()/apply() 才有效。
        Lang.init(this)
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
        // ⚠ 页面序号必须与底部导航的**显示顺序**一致（推子/灯具/效果/灯库/设置）。
        //   否则点第 2 项会跳到第 4 页（动画要滚过中间所有页），
        //   回勾选状态也会标错项 —— 因为 ViewPager 是按位置索引的。
        const val FADER = 0
        const val INSTANCES = 1    // 灯具（已配接的灯具 + RDM）
        const val FX = 2           // 效果（含 内置效果/程序 两个页签）
        const val FIXTURE = 3      // 灯库（App灯库/设备灯库/文件管理 三个页签）
        const val SETTINGS = 4
        const val EDITOR = 5       // 不在底部导航
        const val COUNT = 6
    }

    private fun setupPager() {
        // 程序页不再是一个独立的 pager 页面：它的根视图被装进"效果"页的程序容器里
        // 顺序与 Page 常量、底部导航三者必须一致
        val pages = listOf(fb.root, imfb.root, fxb.root, fixb.root, stb.root, edb.root)
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
        applySavedLanguage()   // 先恢复语言，再建页面，避免首屏闪一下中文
        b.bottomNav.post { applyNavTitles() }
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
                // 按 **ID** 映射，不写死序号 —— 以后菜单顺序再变也不会错位
                val navId = when (pos) {
                    Page.FADER -> R.id.nav_fader
                    Page.INSTANCES -> R.id.nav_instances
                    Page.FX -> R.id.nav_fx
                    Page.FIXTURE -> R.id.nav_fixture
                    Page.SETTINGS -> R.id.nav_settings
                    else -> -1
                }
                if (navId != -1) {
                    b.bottomNav.menu.findItem(navId)?.isChecked = true
                }
                // 切页后统一刷新一次文案：每个页面在 onBindViewHolder 里用代码设过文字，
                // 布局里的 android:text 也在这里被自动翻译覆盖（见 Lang.apply 的说明）。
                b.root.post { Lang.apply(b.root); applyNavTitles() }
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
                toast(Lang.t(R.string.k_select_a_fixture_type_or_add_fixtures_on_the_lib))
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
            .setTitle(Lang.t(R.string.k_delete_fixture))
            .setMessage(Lang.t(R.string.k_delete_fixture_1_s_2_s, inst.name, inst.label()))
            .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ ->
                // 停止该实例的效果和程序（板载槽位释放，槽位绑定不重排）
                FxEngine.stop(inst.id)
                if (playingSlots.remove(inst.slot)) {
                    engine.sendProgStop(inst.slot)
                }
                updatePlayBtnUI()
                fixtureStore.deleteInstance(inst.id)
                selectedInstanceIds.remove(inst.id)
                if (currentInstanceId == inst.id) currentInstanceId = groupInstances().firstOrNull()?.id
                toast(Lang.t(R.string.k_deleted_1_s, inst.name))
                renderInstanceBar()
                refreshInstanceMgrList()
                // 若无剩余实例（或剩余的一台都没被选中），回到裸通道模式
                if (fixtureStore.instances().isEmpty() || selectedInstanceIds.isEmpty()) {
                    clearFixtureMode()
                } else {
                    applySelectedInstance()
                }
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
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

    /**
     * 套用推子页的通道排列顺序。
     *
     * v9 起**固定按通道顺序**：原来的"自定义顺序 + 排列预设"整套已移除
     * （用户要求）。参数保留是为了不改调用点，未使用。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun applyFaderLayout(fixtureId: String?) {
        channelAdapter.applyOrder(null)
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
            toast(Lang.t(R.string.k_create_or_select_a_program_on_the_programs_page))
            b.pager.currentItem = Page.FX
            showFxTab(false)      // 记录步 → 切到"效果"页的程序页签
            return
        }
        steps.addStep(prog, instId, steps.defaultTimeMs, sanitizeSnapshot())
        refreshStepsUI()
        toast(Lang.t(R.string.k_recorded_1_s_step_2_s, prog, steps.stepCount(prog, instId)))
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
                            toast(Lang.t(R.string.k_connect_to_a_device_before_starting_effects))
                            return@setOnCheckedChangeListener
                        }
                        if (FxEngine.slotsFull()) {
                            toast(Lang.t(R.string.k_effect_slots_full_8))
                            return@setOnCheckedChangeListener
                        }
                        // v6：效果要作用到"整组"，组内必须是规则阵列（同灯型 + 等间距地址）
                        val (arrayOk, arrayMsg) = fxArrayInfo()
                        if (!arrayOk) {
                            toast(Lang.t(R.string.k_cannot_apply_to_the_whole_group_1_s, arrayMsg))
                            return@setOnCheckedChangeListener
                        }
                        FxEngine.setSelectedPreset(currentInstanceId, def.id)
                        updateFxChannels()
                        val ok = FxEngine.start(engine, def.id, currentInstanceId,
                            amp = FxEngine.getAmplitude(currentInstanceId),
                            speed = FxEngine.getSpeed(currentInstanceId))
                        if (!ok) {
                            toast(Lang.t(R.string.k_cannot_start_channel_conflict_with_a_running_eff))
                        }
                    } else {
                        val slot = FxEngine.slotOf(currentInstanceId, def.id)
                        if (slot != null) FxEngine.stopSlot(currentInstanceId, slot)
                    }
                    refreshFxPage()
                }
            }
        }
        // v9：效果列表改 3 列网格（项变多时不用一直往下滚）
        // v9.2：改回单列列表（每行一个效果，名称完整、开关好认）
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
                fxb.tvSpread.text = Lang.t(R.string.k_spread_1_s, p * 360 / 255)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        fxb.seekPhase.max = 255
        fxb.seekPhase.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                FxEngine.setPhase(p, currentInstanceId)
                fxb.tvPhase.text = Lang.t(R.string.k_phase_1_s, p * 360 / 255)
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
        // 预设弹窗：创建一次、保留绑定，按钮点开即可（引用 fxp.* 的地方都靠它）
        fxp = layoutInflater.inflate(R.layout.dialog_fx_presets, null)
        fxb.btnFxPresets.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(Lang.t(R.string.s_presets))
                .setView(fxp)
                .setPositiveButton(Lang.t(R.string.s_close), null)
                .show()
        }

        fxpSpinner.adapter = fxpAdapter
        fxpSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!fxPresetUpdating) fxPresetSel = fxpAdapter.getItem(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        fxpSave.setOnClickListener { saveFxPreset() }
        fxpApply.setOnClickListener {
            applyFxPreset(fxPresetSel ?: fxpSpinner.selectedItem as? String)
        }
        fxpOff.setOnClickListener {
            FxEngine.stop(currentInstanceId)
            toast(Lang.t(R.string.k_all_effects_stopped_for_the_current_fixture))
            refreshFxPage()
        }
        fxpDel.setOnClickListener { deleteFxPreset() }

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
        if (want != null) fxpSpinner.setSelection(names.indexOf(want))
        fxPresetUpdating = false
    }

    private fun saveFxPreset() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(fxPresetDefaultName())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_save_effect_preset))
            .setMessage(Lang.t(R.string.k_save_values_and_on_off_state_of_all_built_in_eff))
            .setView(input)
            .setPositiveButton(Lang.t(R.string.k_save)) { _, _ ->
                val nm = input.text.toString().trim().ifEmpty { fxPresetDefaultName() }
                fxPresetStore.save(FxPresetStore.Preset(
                    nm, currentInstanceId, FxEngine.snapshotParams(currentInstanceId)))
                fxPresetSel = nm
                refreshFxPage()
                toast(Lang.t(R.string.k_effect_preset_saved_1_s, nm))
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
            .show()
    }

    private fun applyFxPreset(name: String?) {
        if (name.isNullOrEmpty()) { toast(Lang.t(R.string.k_save_an_effect_preset_first)); return }
        if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return }
        val p = fxPresetStore.get(currentInstanceId, name) ?: return
        updateFxChannels()
        val failed = FxEngine.applyParams(engine, p.params, currentInstanceId)
        if (failed.isEmpty()) toast(Lang.t(R.string.k_effect_preset_applied_1_s, name))
        else {
            val nm = failed.joinToString("、") { id ->
                FxEngine.presets.find { it.id == id }?.name ?: "#$id"
            }
            toast(Lang.t(R.string.k_some_effects_did_not_start_channel_conflict_slot, nm))
        }
        refreshFxPage()
    }

    private fun deleteFxPreset() {
        val name = fxPresetSel ?: fxpSpinner.selectedItem as? String
        if (name.isNullOrEmpty()) { toast(Lang.t(R.string.k_no_presets)); return }
        MaterialAlertDialogBuilder(this)
            .setMessage(Lang.t(R.string.k_delete_effect_preset_1_s, name))
            .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ ->
                fxPresetStore.delete(currentInstanceId, name)
                fxPresetSel = null
                refreshFxPage()
                toast(Lang.t(R.string.k_deleted))
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
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
            fxb.tvSpread.text = Lang.t(R.string.k_spread_1_s_2, spread * 360 / 255)
            val phase = FxEngine.getPhase(instId)
            fxb.seekPhase.progress = phase
            fxb.tvPhase.text = Lang.t(R.string.k_phase_1_s_2, phase * 360 / 255)
            fxb.spShape.setSelection(FxEngine.getShape(instId))
            fxb.spDirection.setSelection(FxEngine.getDirection(instId))
            fxb.spEnvelope.setSelection(FxEngine.getEnvelope(instId))
            fxUiUpdating = false
        } else {
            // 未选中任何预设
            fxb.tvFxStatus.text = Lang.t(R.string.k_tap_a_row_to_select_an_effect_then_use_the_switc)
            fxb.tvFxStatus.setTextColor(ContextCompat.getColor(this, R.color.textDim))
            fxb.btnFxStop.visibility = View.GONE
            fxb.fxParams.visibility = View.GONE
        }
        // 刷新列表高亮 + 效果预设下拉
        fxb.rvFxPresets.adapter?.notifyDataSetChanged()
        renderRunFxCards()
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
            .setTitle(Lang.t(R.string.k_channel_1_s_value_0_255, chInFixture))
            .setView(input)
            .setPositiveButton(Lang.t(R.string.k_ok)) { _, _ ->
                val v = (input.text.toString().toIntOrNull() ?: 0).coerceIn(0, 255)
                setChannelValue(chInFixture, v); channelAdapter.refresh()
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
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
            toast(Lang.t(R.string.k_1_s_steps_had_more_than_2_s_channel_changes_and_, truncated, DmxProtocol.MAX_PROG_ITEMS_STEP))
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
                .setTitle(Lang.t(R.string.k_new_program))
                .setView(input)
                .setPositiveButton(Lang.t(R.string.k_create)) { _, _ ->
                    val nm = input.text.toString().trim()
                    if (nm.isEmpty()) { toast(Lang.t(R.string.k_enter_a_name)); return@setPositiveButton }
                    if (steps.hasProgram(nm, currentInstId())) { toast(Lang.t(R.string.k_a_program_with_that_name_already_exists)); return@setPositiveButton }
                    steps.addProgram(nm, currentInstId()); steps.currentProgram = nm; reloadProgramsUI(nm); toast(Lang.t(R.string.k_created_1_s, nm))
                }
                .setNegativeButton(Lang.t(R.string.k_cancel), null).show()
        }
        pb.btnDelProg.setOnClickListener {
            val prog = currentProgramSel() ?: run { toast(Lang.t(R.string.k_no_programs)); return@setOnClickListener }
            MaterialAlertDialogBuilder(this)
                .setMessage(Lang.t(R.string.k_delete_program_1_s, prog))
                .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ ->
                    val slot = currentProgSlot()
                    if (playingSlots.remove(slot)) { engine.sendProgStop(slot) }
                    steps.deleteProgram(prog, currentInstId())
                    if (steps.currentProgram == prog) steps.currentProgram = steps.programNames(currentInstId()).firstOrNull()
                    reloadProgramsUI(null); updatePlayBtnUI(); toast(Lang.t(R.string.k_deleted))
                }
                .setNegativeButton(Lang.t(R.string.k_cancel), null).show()
        }
        pb.btnSaveStep.setOnClickListener {
            val prog = currentProgramSel() ?: run { toast(Lang.t(R.string.k_create_a_program_first)); return@setOnClickListener }
            steps.addStep(prog, currentInstId(), stepTimeMs(), sanitizeSnapshot()); refreshStepsUI()
            toast(Lang.t(R.string.k_recorded_step_1_s, steps.stepCount(prog, currentInstId())))
        }
        pb.btnPlay.setOnClickListener {
            val slot = currentProgSlot()
            if (playingSlots.contains(slot)) {
                playingSlots.remove(slot)
                engine.sendProgStop(slot)
            } else {
                if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_before_playing)); return@setOnClickListener }
                val prog = currentProgramSel() ?: run { toast(Lang.t(R.string.k_create_a_program_first)); return@setOnClickListener }
                val s = steps.steps(prog, currentInstId())
                if (s.isEmpty()) { toast(Lang.t(R.string.k_this_program_has_no_steps_yet)); return@setOnClickListener }
                uploadProgramAndPlay(s)
                playingSlots.add(slot)
                toast(Lang.t(R.string.k_sent_to_device_keeps_playing_if_the_app_disconne))
            }
            updatePlayBtnUI()
        }
        pb.btnClearSteps.setOnClickListener {
            val prog = currentProgramSel() ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setMessage(Lang.t(R.string.k_clear_all_steps_of_1_s, prog))
                .setPositiveButton(Lang.t(R.string.k_clear)) { _, _ ->
                    val slot = currentProgSlot()
                    if (playingSlots.remove(slot)) { engine.sendProgStop(slot) }
                    steps.clearSteps(prog, currentInstId()); refreshStepsUI(); updatePlayBtnUI(); toast(Lang.t(R.string.k_cleared))
                }
                .setNegativeButton(Lang.t(R.string.k_cancel), null).show()
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
                toast(Lang.t(R.string.k_preview_step_1_s, pos + 1))
            }
        }
        pb.lvSteps.setOnItemLongClickListener { _, _, pos, _ ->
            val prog = currentProgramSel() ?: return@setOnItemLongClickListener true
            MaterialAlertDialogBuilder(this)
                .setMessage(Lang.t(R.string.k_delete_step_1_s, pos + 1))
                .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ -> steps.removeStep(prog, currentInstId(), pos); refreshStepsUI() }
                .setNegativeButton(Lang.t(R.string.k_cancel), null).show()
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
                    toast(Lang.t(R.string.k_disconnected_1_s, found.name))
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
        db.tvHint.text = Lang.t(R.string.k_scanning_auto_scans_every_few_seconds)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(db.root)
            .setOnDismissListener { ble.stopPeriodicScan() }

        if (connected) {
            builder.setNegativeButton(Lang.t(R.string.k_disconnect)) { _, _ ->
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
        refreshStatusBar()
        when (state) {
            BleManager.State.CONNECTED -> {
                toast(Lang.t(R.string.k_connected))
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
        // ---- RDM 应答（0x89/0x8A/0x8B）----
        // 这三帧结构简单、与其它帧不冲突，直接在分发前处理掉。
        if (data.isNotEmpty()) {
            when (data[0].toInt() and 0xFF) {
                DmxProtocol.RESP_RDM_SCAN -> {
                    // 0x89 count uni ok simulate errLen err…
                    if (data.size >= 6) {
                        val count = data[1].toInt() and 0xFF
                        val uni = data[2].toInt() and 0xFF
                        val ok = (data[3].toInt() and 0xFF) == 0
                        val sim = (data[4].toInt() and 0xFF) != 0
                        val n = (data[5].toInt() and 0xFF).coerceAtMost(data.size - 6)
                        val err = String(data, 6, n, Charsets.UTF_8)
                        runOnUiThread { onRdmScanDone(count, uni, ok, sim, err) }
                    }
                    return
                }
                DmxProtocol.RESP_RDM_DEVICE -> {
                    runOnUiThread { onRdmDevice(data) }
                    return
                }
                DmxProtocol.RESP_RDM_RESULT -> {
                    if (data.size >= 3) {
                        val ok = (data[1].toInt() and 0xFF) == 0
                        val n = (data[2].toInt() and 0xFF).coerceAtMost(data.size - 3)
                        val err = String(data, 3, n, Charsets.UTF_8)
                        runOnUiThread {
                            toast(if (ok) "RDM 命令执行成功" else "RDM 失败：$err")
                            refreshRdmList()
                            // 地址写完 → 问是否把这些灯具也建成 App 里的实例
                            if (ok) offerCreateInstances()
                        }
                    }
                    return
                }
            }
        }
        when (val m = DeviceMessages.parse(data)) {
            // ---- 状态同步应答（0x05 之后固件上报）----
            is Msg.StateHead -> {
                devUptime = m.uptimeSec
                devProgMask = m.progMask
                devFxCount = m.fxCount
                devDmxOk = m.dmxOk
                devDmxFails = m.dmxFails
                devFps1 = m.fps1
                devFps2 = m.fps2
                lastDevFx.clear()
                runOnUiThread { refreshStatusBar() }
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
                            toast(Lang.t(R.string.k_imported_1_s_fixtures_from_device, imported.size))
                            refreshFixturePage()
                        } else {
                            toast(Lang.t(R.string.k_could_not_parse_the_fixture_file))
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
            if (lastSeen >= 0L) toast(Lang.t(R.string.k_device_was_restarted_app_state_synced))
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
            toast(Lang.t(R.string.k_state_synced_from_device))
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
        b.btnConnect.text = Lang.t(R.string.s_device)
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
                holder.b.tvName.text = Lang.t(R.string.k_1_s_connected, f.name)
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
                toast(Lang.t(R.string.k_applied_1_s, def.name) + if (inst != null) "（${inst.label()}）" else "")
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
                .setMessage(Lang.t(R.string.k_delete_1_s_selected_fixtures, selected.size))
                .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ ->
                    selected.forEach { fixtureStore.delete(it) }
                    refreshFixturePage()
                    toast(Lang.t(R.string.k_deleted_1_s_2, selected.size))
                }
                .setNegativeButton(Lang.t(R.string.k_cancel), null).show()
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
        fixb.tvSelectedCount.text = Lang.t(R.string.k_1_s_selected, count)
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
                toast(Lang.t(R.string.k_read_failed_1_s, e.message))
            }
        }
        if (scanned.isEmpty()) {
            toast(Lang.t(R.string.k_no_file_read))
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
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
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
            failures.isEmpty() -> toast(Lang.t(R.string.k_imported_1_s_fixtures, total))
            total > 0 -> toast(Lang.t(R.string.k_imported_1_s_fixtures_2_s_failed, total, failures.size))
            else -> showFailureDetails(failures)
        }
    }

    /** 有失败且一个都没成功时，把具体原因列出来（别只弹一句"导入失败"）。 */
    private fun showFailureDetails(failures: List<String>) {
        val msg = failures.take(15).joinToString("\n") { "· $it" } +
                  if (failures.size > 15) "\n… 另有 ${failures.size - 15} 个" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_import_failed_1_s_files, failures.size))
            .setMessage(msg)
            .setPositiveButton(Lang.t(R.string.k_got_it), null)
            .show()
    }

    /** 添加灯具实例：选灯型 → 名称 + DMX 起始地址。 */
    private fun showAddInstanceDialog() {
        val defs = fixtureStore.fixtures
        if (defs.isEmpty()) {
            toast(Lang.t(R.string.k_import_a_fixture_library_first))
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
        // 双宇宙关闭时只留 A 通道（避免把灯配到没接线的 B 口上）
        btnBandB.visibility = if (dualUniverse) View.VISIBLE else View.GONE
        holder.findViewById<TextView>(R.id.tvBandLabel).text = if (dualUniverse)
            "通道 A / B（A = 宇宙1 口，B = 宇宙2 口）" else "通道 A（宇宙1 口，双宇宙已关闭）"
        if (!dualUniverse) band = 1
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
            .setTitle(Lang.t(R.string.k_add_fixture))
            .setView(holder)
            .setPositiveButton(Lang.t(R.string.k_add)) { _, _ ->
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
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
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
            edb.tvChCount.text = Lang.t(R.string.k_1_s_channels, fixtureEditor.channels.size)
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
            if (defs.isEmpty()) { toast(Lang.t(R.string.k_no_fixture_libraries_imported)); return@setOnClickListener }
            val names = defs.map { "${it.name} / ${it.mode} (${it.channelCount}CH)" }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(Lang.t(R.string.k_choose_a_fixture_library_to_edit))
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
            if (name.isEmpty()) { toast(Lang.t(R.string.k_enter_a_fixture_type_name)); return@setOnClickListener }
            val manu = edb.etFixManu.text.toString().trim().ifEmpty { "Unknown" }
            val mode = edb.etFixMode.text.toString().trim().ifEmpty { "1ch" }
            val pan = edb.etPanRange.text.toString().toFloatOrNull() ?: 0f
            val tilt = edb.etTiltRange.text.toString().toFloatOrNull() ?: 0f
            if (fixtureEditor.channels.isEmpty()) { toast(Lang.t(R.string.k_add_at_least_one_channel)); return@setOnClickListener }

            val def = fixtureEditor.buildFixture(name, manu, mode, pan, tilt)
            // 保存到内部存储（后续可导入到灯具列表）
            val xml = fixtureEditor.buildMa2Xml(def)
            fixtureEditor.saveToStore(def, xml.toByteArray(Charsets.UTF_8))
            // 刷新灯具页
            refreshFixturePage()
            // 导出分享
            fixtureEditor.exportZip(def, contentResolver)
            toast(Lang.t(R.string.k_exported_1_s, name))
        }
    }

    // ---------------- 文件管理 ----------------
    // ---------------- 实例管理页 ----------------
    private fun wireInstanceMgrPage() {
        imfb.rvInstanceMgr.layoutManager = LinearLayoutManager(this)
        // 少了这行 RecyclerView 不会渲染任何子项（列表看起来是空的）
        imfb.rvRdm.layoutManager = LinearLayoutManager(this)
    }

    // ---------------- 设置页 ----------------
    /** 双宇宙开关下方的说明文字（实时反映当前状态）。 */
    private fun updateDualUniverseHint() {
        stb.tvDualUniverseHint.text = if (dualUniverse)
            "开启：A + B 两条 DMX（共 1024 通道）"
        else
            "关闭：只用 A 通道（512 路），B 通道的灯具不会输出"
    }

    /** RDM 开关下方的说明文字。 */
    private fun updateRdmHint() {
        stb.tvRdmHint.text = if (rdmEnabled)
            "已开启：可在「灯具」页扫描并管理支持 RDM 的灯具"
        else
            "开启后可在「灯具」页扫描并管理支持 RDM 的灯具"
        stb.tvRdmHint.setTextColor(ContextCompat.getColor(this,
            if (rdmEnabled) R.color.ok else R.color.textDim))
    }

    private fun wireSettingsPage() {
        // 语言：全局中英文切换（不再每个灯库单独设置）
        stb.btnLangZh.setOnClickListener { setLanguage(true) }
        stb.btnLangEn.setOnClickListener { setLanguage(false) }

        // 双宇宙输出开关
        stb.swDualUniverse.isChecked = dualUniverse
        stb.swDualUniverse.setOnCheckedChangeListener { _, on ->
            dualUniverse = on
            updateDualUniverseHint()
            if (!on) {
                val bCount = fixtureStore.instances().count { it.universe == 2 }
                if (bCount > 0) toast(Lang.t(R.string.k_dual_universe_off_1_s_fixtures_on_band_b_will_no, bCount))
            }
            renderInstanceBar()
            refreshInstanceMgrList()
        }
        updateDualUniverseHint()

        // RDM 开关
        stb.swRdm.isChecked = rdmEnabled
        stb.swRdm.setOnCheckedChangeListener { _, on ->
            rdmEnabled = on
            updateRdmHint()
            instTabPatched = true
            refreshInstanceMgrList()
        }
        updateRdmHint()

        // 推子页按功能分组折叠（v9）
        stb.swFaderGroup.isChecked = appSettings.getBoolean("fader_group", false)
        stb.swFaderGroup.setOnCheckedChangeListener { _, on ->
            appSettings.edit().putBoolean("fader_group", on).apply()
            channelAdapter.groupByFunction = on       // setter 内部会 refresh
            toast(Lang.t(if (on) R.string.k_fader_grouping_on else R.string.k_fader_grouping_off))
        }
        channelAdapter.groupByFunction = stb.swFaderGroup.isChecked
        // 点整行也能切换开关（交互更自然，也让自动化可点）
        stb.rowFaderGroup.setOnClickListener { stb.swFaderGroup.toggle() }
        stb.rowDualUniverse.setOnClickListener { stb.swDualUniverse.toggle() }
        stb.rowRdm.setOnClickListener { stb.swRdm.toggle() }

        // RDM 建实例：地址冲突时覆盖 or 询问（v9.3）
        rdmOverwrite = appSettings.getBoolean("rdm_overwrite", false)
        stb.swRdmOverwrite.isChecked = rdmOverwrite
        stb.swRdmOverwrite.setOnCheckedChangeListener { _, on ->
            rdmOverwrite = on
            appSettings.edit().putBoolean("rdm_overwrite", on).apply()
            refreshRdmOverwriteHint()
            toast(Lang.t(if (on) R.string.s_rdm_overwrite_toast_on else R.string.s_rdm_overwrite_toast_off))
        }
        stb.rowRdmOverwrite.setOnClickListener { stb.swRdmOverwrite.toggle() }
        refreshRdmOverwriteHint()

        // 灯库编辑（从“灯具”页移入设置页）
        stb.btnOpenEditor.setOnClickListener { b.pager.currentItem = Page.EDITOR }


        refreshSettingsPage()
    }


    /**
     * 应用保存的语言。
     *
     * 这里把两件事收敛到同一个开关下：
     *   · [channelAdapter.translated] —— 灯库通道名的翻译（原有能力）
     *   · [Lang]                     —— 整个界面的中英文（新增，全局）
     * 两者共用 appSettings 的 "translated" 键（true = 中文），不引入第二份存储。
     */
    /**
     * 翻译底部导航的标题。
     *
     * 必须单独做：BottomNavigationView 的菜单项不在普通 View 树里
     * （它自己管理 item 的视图），所以 Lang.apply 的递归遍历覆盖不到它 ——
     * 只靠遍历会出现"页面全变英文了、底部导航还是中文"。
     */
    private fun applyNavTitles() {
        val m = b.bottomNav.menu
        m.findItem(R.id.nav_fader)?.title = Lang.t(Lang.t(R.string.s_faders))
        m.findItem(R.id.nav_instances)?.title = Lang.t(Lang.t(R.string.s_fixtures))
        m.findItem(R.id.nav_fx)?.title = Lang.t(Lang.t(R.string.s_effects))
        m.findItem(R.id.nav_fixture)?.title = Lang.t(Lang.t(R.string.s_library))
        m.findItem(R.id.nav_settings)?.title = Lang.t(Lang.t(R.string.s_settings))
    }

    /**
     * 刷新状态总览条。
     *
     * 数据来源：连接状态（本地）+ 固件 0x82 状态帧（uptime / 效果数 / 程序位图 / DMX 遥测）。
     * DMX 遥测是 v9 固件才有的字段，旧固件下显示"--"，不会误报成故障。
     */
    private fun refreshStatusBar() {
        val connected = ble.state == BleManager.State.CONNECTED
        fb.tvStLink.text = if (connected) Lang.t(R.string.st_connected) else Lang.t(R.string.st_offline)
        fb.tvStLink.setTextColor(ContextCompat.getColor(this,
            if (connected) R.color.ok else R.color.err))

        fb.tvStDmx.text = when {
            !connected -> Lang.t(R.string.st_dmx_wait)
            devDmxOk < 0 -> Lang.t(R.string.st_dmx_na)      // 旧固件无遥测
            else -> {
                val f1 = if (devFps1 in 0..255) devFps1 else 0
                val f2 = if (devFps2 in 0..255) devFps2 else 0
                val fps = if (dualUniverse) "$f1/$f2" else "$f1"
                val fail = if (devDmxFails > 0) "  ✗${devDmxFails}" else ""
                "${Lang.t(R.string.st_dmx)} ${fps}fps$fail"
            }
        }
        fb.tvStDmx.setTextColor(ContextCompat.getColor(this,
            if (connected && devDmxOk >= 0 && devDmxOk != 0) R.color.textDim else R.color.textDim))

        fb.tvStRun.text = if (!connected) "" else {
            val parts = ArrayList<String>()
            if (devFxCount > 0) parts.add("${Lang.t(R.string.st_fx)} $devFxCount")
            val progs = Integer.bitCount(devProgMask)
            if (progs > 0) parts.add("${Lang.t(R.string.st_prog)} $progs")
            if (devUptime > 0) parts.add(fmtUptime(devUptime))
            parts.joinToString("  ")
        }
    }

    private fun fmtUptime(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60
        return if (h > 0) "${h}h${m}m" else "${m}m"
    }

    /**
     * 渲染"运行中效果卡片"（v9）。
     *
     * 数据来自固件 0x84 帧（lastDevFx）—— 也就是**设备实际在跑的效果**，
     * 而不是 App 以为勾选了的。这样"以为开了其实没开"（槽位满/通道冲突）一眼可见。
     */
    private fun renderRunFxCards() {
        if (!::fxb.isInitialized) return
        val box = fxb.runFxCards
        box.removeAllViews()
        val density = resources.displayMetrics.density
        if (lastDevFx.isEmpty()) {
            fxb.runFxScroll.visibility = View.GONE
            return
        }
        fxb.runFxScroll.visibility = View.VISIBLE
        for (fx in lastDevFx.take(12)) {
            val name = FxEngine.presets.getOrNull(fx.fxId)?.name ?: "FX${fx.fxId}"
            val card = TextView(this).apply {
                text = "$name\n${fx.amp16 * 100 / 255}%  ${fx.speed}"
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text))
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_pill)
                val pad = (8 * density).toInt()
                setPadding(pad, (5 * density).toInt(), pad, (5 * density).toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = (6 * density).toInt()
            box.addView(card, lp)
        }
    }

    /** 刷新"地址冲突时覆盖"的说明文字。 */
    private fun refreshRdmOverwriteHint() {
        if (!::stb.isInitialized) return
        stb.tvRdmOverwriteHint.text = Lang.t(
            if (rdmOverwrite) R.string.s_rdm_overwrite_on else R.string.s_rdm_overwrite_off)
    }

    private fun applySavedLanguage() {
        val zh = appSettings.getBoolean("translated", true)
        Lang.set(!zh)
        channelAdapter.translated = zh
        channelAdapter.refresh()
    }

    private fun setLanguage(zh: Boolean) {
        appSettings.edit().putBoolean("translated", zh).apply()
        Lang.set(!zh)
        channelAdapter.translated = zh
        channelAdapter.refresh()
        // 立即把当前界面上所有 View 的文字换掉（布局里的 android:text 靠这个自动覆盖）
        Lang.apply(b.root)
        applyNavTitles()
        refreshSettingsPage()
        toast(if (zh) "已切换为中文" else "Switched to English")
    }


    private fun refreshSettingsPage() {
        // 说明文字依赖 Lang.t()，而 wire* 阶段 Lang 还没初始化 → 每次进设置页重刷一遍
        refreshRdmOverwriteHint()
        val zh = appSettings.getBoolean("translated", true)
        // 语言标签不显式指定文字颜色（沿用主题默认），故 setTextColor = false
        stylePillTab(stb.btnLangZh, zh, setTextColor = false)
        stylePillTab(stb.btnLangEn, !zh, setTextColor = false)

    }


    /** 刷新实例管理页列表（按 DMX 地址排序，勾选 = 参与同时控制）。 */
    /**
     * 灯具管理页列表。
     *
     * 两种模式共用同一个勾选框，避免多塞一个控件：
     * - **平时**：勾选 = 该灯具参与同时控制（selectedInstanceIds）
     * - **长按进入多选后**：勾选 = 待删除（instEditSel）
     *   此时显示批量操作栏（全选 / 取消全选 / 删除选中 / 完成）
     *
     * RDM 打开时顶部出现页签，可切到 RDM 设备列表。
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
            if (instEditSel.isEmpty()) { toast(Lang.t(R.string.k_select_the_fixtures_to_delete_first)); return@setOnClickListener }
            confirmDeleteInstances(instEditSel.toList())
        }
        imfb.btnInstExitEdit.setOnClickListener {
            instEditMode = false
            instEditSel.clear()
            refreshInstanceMgrList()
        }

        // ---- 页签：RDM 关闭时隐藏整条 ----
        imfb.instTabBar.visibility = if (rdmEnabled) View.VISIBLE else View.GONE
        if (!rdmEnabled) instTabPatched = true
        imfb.layoutPatched.visibility = if (instTabPatched) View.VISIBLE else View.GONE
        imfb.layoutRdm.visibility = if (!instTabPatched) View.VISIBLE else View.GONE

        fun tab(btn: TextView, active: Boolean) {
            btn.setBackgroundResource(if (active) R.drawable.bg_pill_outline_accent
                                      else R.drawable.bg_pill_outline_white)
            btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        tab(imfb.btnTabPatched, instTabPatched)
        tab(imfb.btnTabRdm, !instTabPatched)
        imfb.btnTabPatched.setOnClickListener { instTabPatched = true; refreshInstanceMgrList() }
        imfb.btnTabRdm.setOnClickListener { instTabPatched = false; refreshInstanceMgrList() }

        if (!instTabPatched) {
            refreshRdmList()
            return                       // RDM 页签下不构建"已配接"列表
        }

        imfb.instBatchBar.visibility = if (instEditMode) View.VISIBLE else View.GONE
        imfb.tvInstEditHint.visibility = if (instEditMode) View.VISIBLE else View.GONE
        imfb.tvInstListTitle.text = if (instEditMode) "多选删除模式"
                                   else "参与同时控制的灯具（点行勾选，按地址顺序）"
        imfb.tvInstCount.text = Lang.t("共 %d 台", insts.size)
        imfb.tvInstSelCount.text = Lang.t(R.string.k_1_s_selected_2, instEditSel.size)

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
                val dead = !dualUniverse && inst.universe == 2
                val suffix = if (dead) "  ⚠ 双宇宙已关闭，无输出" else ""
                tvInfo.text = "${inst.label()}  ${def?.channelCount ?: 0}CH  ${def?.name ?: ""}$suffix"
                tvInfo.setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (dead) R.color.warn else R.color.textDim))
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
                        toast(Lang.t(R.string.k_multi_select_on_use_select_all_then_delete))
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

    /** RDM 设备列表（真实/模拟扫描结果，由固件 0x8A 帧填充）。 */
    private fun refreshRdmList() {
        // 每次刷新都按用户保存的顺序重排一遍（幂等）。
        // ⚠ 必须在这里做：底部导航切页会触发刷新，而列表显示用的是 rdmDevices，
        //   它如果只在"扫描收到设备帧"时才排序，切页回来就退回固件顺序了 ——
        //   现象就是"切页之后顺序错了"。
        if (rdmDevices.isNotEmpty()) rdmDevices = applySavedRdmOrder(rdmDevices)
        imfb.btnRdmScan.setOnClickListener { doRdmScan() }
        imfb.btnRdmScan.isEnabled = !rdmScanning && !rdmReorderMode
        imfb.btnRdmScan.text = if (rdmScanning) "扫描中…" else "扫描设备"
        imfb.btnRdmReorder.isEnabled = rdmDevices.isNotEmpty() && !rdmScanning
        imfb.btnRdmReorder.text = if (rdmReorderMode) "取消排序" else "编辑顺序"
        imfb.btnRdmReorder.setOnClickListener {
            rdmReorderMode = !rdmReorderMode
            if (rdmReorderMode && rdmDevices.isNotEmpty()) rdmOrder = rdmDevices
            refreshRdmList()
        }
        // 模拟开关：没有真实 RDM 灯具时用固件内置的虚拟灯具
        imfb.btnRdmSim.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
            rdmSimOn = !rdmSimOn
            engine.sendRaw(encodeRdmSimulate(rdmSimOn))
            toast(if (rdmSimOn) "已开模拟：扫描会返回固件内置的虚拟灯具" else "已关模拟：扫描真实 RDM 总线")
            refreshRdmList()
        }

        // 4 个操作按钮常驻，只按可用性禁用（与推子页那行一致：一直看得见，不闪）
        imfb.btnRdmApplyOrder.isEnabled = rdmDevices.isNotEmpty()
        imfb.btnRdmMakeInstances.isEnabled = rdmDevices.isNotEmpty()
        // 只有"起始地址 + 完成排序"这一行随排序模式出现
        imfb.rdmReorderBar.visibility = if (rdmReorderMode) View.VISIBLE else View.GONE
        imfb.btnRdmSim.text = if (rdmSimOn) "模拟·开" else "模拟·关"
        imfb.btnRdmSim.setTextColor(ContextCompat.getColor(this,
            if (rdmSimOn) R.color.warn else R.color.textDim))
        if (rdmReorderMode) {
            val start = (imfb.etRdmStart.text.toString().toIntOrNull() ?: 1)
                .coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
            val plan = assignAddresses(rdmOrder, start)
            imfb.tvRdmStatus.text = if (plan == null)
                "⚠ 通道不够：共 ${rdmOrder.size} 台超出 ${DmxProtocol.UNIVERSE_SIZE} 通道，请减少灯具或把起始地址提前"
            else "按住 ☰ 拖到任意位置调整顺序 · 共 ${rdmOrder.size} 台 · 占 ${plan.last().second + rdmOrder.last().channelCount - 1} 通道"
            imfb.etRdmStart.setOnEditorActionListener { _, _, _ -> refreshRdmList(); true }
            imfb.etRdmStart.setOnFocusChangeListener { _, has -> if (!has) refreshRdmList() }
            imfb.btnRdmApplyOrder.setOnClickListener {
                val p = assignAddresses(rdmOrder, start)
                if (p == null) { toast(Lang.t(R.string.k_does_not_fit_in_this_universe_adjust_the_start_a)); return@setOnClickListener }
                if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
                // 记住这批指派：写入成功后要问"是否同时在 App 里创建为灯具实例"
                rdmPendingAssign = rdmOrder.zip(p) { d, pair -> d to pair.second }
                engine.sendRaw(encodeRdmSetAddresses(rdmOrder.first().universe - 1, p))
                toast(Lang.t(R.string.k_writing_new_addresses_for_1_s_fixtures_in_order, p.size))
            }
            // 常驻按钮：不必等"写入地址"的结果弹窗，随时可把当前顺序加成实例
            imfb.btnRdmMakeInstances.setOnClickListener {
                val p = assignAddresses(rdmOrder, start)
                if (p == null) { toast(Lang.t(R.string.k_does_not_fit_in_this_universe_adjust_the_start_a)); return@setOnClickListener }
                createInstancesFromRdm(rdmOrder.zip(p) { d, pair -> d to pair.second })
            }
            imfb.btnRdmReorderDone.setOnClickListener {
                rdmReorderMode = false
                refreshRdmList()
            }
        } else {
            imfb.tvRdmStatus.text = when {
                rdmScanning -> "正在扫描…（DMX 输出会暂停数秒）"
                rdmDevices.isEmpty() -> rdmLastError.ifEmpty { "点「扫描设备」开始" }
                rdmSimulated -> "⚠ 固件模拟数据 · ${rdmDevices.size} 台 · 点行看全部参数，长按可拖动排序"
                else -> "${rdmDevices.size} 台设备 · 点行看全部参数 · 「编辑顺序」可拖动排序并自动分配地址"
            }
        }
        val empty = !rdmReorderMode && rdmDevices.isEmpty() && !rdmScanning
        imfb.tvRdmEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        imfb.tvRdmEmpty.text = if (rdmLastError.isNotEmpty())
            "${rdmLastError}\n\n点上面的「扫描设备」重试\n或点「模拟·开」用固件内置虚拟灯具验证界面"
        else "还没有扫描到 RDM 设备\n\n点上面的「扫描设备」开始\n（扫描期间该通道的 DMX 输出会短暂暂停）"

        imfb.rvRdm.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = (if (rdmReorderMode) rdmOrder else rdmDevices).size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rdm_device, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val list = if (rdmReorderMode) rdmOrder else rdmDevices
                if (pos >= list.size) return
                val d = list[pos]
                val root = holder.itemView
                root.findViewById<TextView>(R.id.tvRdmUid).text = d.uid
                root.findViewById<TextView>(R.id.tvRdmModel).text = "${d.manufacturer} ${d.model}"
                root.findViewById<TextView>(R.id.tvRdmDrag).visibility =
                    if (rdmReorderMode) View.VISIBLE else View.GONE

                val dead = !dualUniverse && d.universe == 2
                val tvAddr = root.findViewById<TextView>(R.id.tvRdmAddr)
                val startAddr = (imfb.etRdmStart.text.toString().toIntOrNull() ?: 1)
                    .coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
                val target = if (rdmReorderMode)
                    assignAddresses(rdmOrder, startAddr)?.getOrNull(pos)?.second else null
                if (rdmReorderMode) {
                    // 排序模式：右边**直接显示预期地址**（拖完它就在这个位置），
                    // 不再显示"旧 → 新"的对照 —— 顺序变了以后旧地址已无意义。
                    // 原地址确实不同时，挪到下面信息行里以小字注明。
                    tvAddr.text = if (target == null) "✗ 放不下"
                                  else "${if (d.universe == 1) "A" else "B"}@$target"
                    tvAddr.setTextColor(ContextCompat.getColor(this@MainActivity,
                        if (target == null) R.color.err else R.color.ok))
                } else {
                    tvAddr.text = d.addrLabel()
                    tvAddr.setTextColor(ContextCompat.getColor(this@MainActivity,
                        if (dead) R.color.err else R.color.warn))
                }
                root.findViewById<TextView>(R.id.tvRdmInfo).text = buildString {
                    // 排序模式下通道范围要按**预期地址**算，否则会出现
                    // "右边 A@1、左边却写占通道 80~99"（那是旧地址的范围）
                    val lo = if (rdmReorderMode && target != null) target else d.address
                    append("占通道 $lo ~ ${lo + d.channelCount - 1}（${d.channelCount}CH")
                    if (d.personality.isNotEmpty()) append(" · ${d.personality}")
                    if (d.personalityCount > 1) append(" · 模式${d.personalityNum}/${d.personalityCount}")
                    append("）")
                    // 排序模式下地址会被改写：原来的地址用小字保留，便于核对
                    if (rdmReorderMode && target != null && target != d.address) {
                        append(" · 原 ${d.addrLabel()}")
                    }
                    if (dead) append(" ⚠ 双宇宙已关闭")
                }
                // 排序模式下：**识别按钮保留**（现场对位时正是要边拖边闪灯找位置），
                // 只隐藏"改址"（顺序才是指派方式，避免两套逻辑冲突）
                root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRdmIdentify)
                    .visibility = View.VISIBLE
                root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRdmSetAddr)
                    .visibility = if (rdmReorderMode) View.GONE else View.VISIBLE
                root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRdmIdentify)
                    .setOnClickListener { identifyRdmDevice(d) }
                root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRdmSetAddr)
                    .setOnClickListener { showRdmSetAddressDialog(d) }
                root.setOnClickListener { if (!rdmReorderMode) showRdmDetailDialog(d) }
            }
        }

        // 拖动排序（ItemTouchHelper）：只在"编辑顺序"模式生效
        rdmTouchHelper?.attachToRecyclerView(null)
        rdmTouchHelper = null
        if (rdmReorderMode && imfb.rvRdm.adapter != null) {
            // ⚠ 必须操作**同一个** MutableList 实例，不能每次 onMove 都 rdmOrder = 新表。
            //   原因：拖到列表边缘时 ItemTouchHelper 会自动滚动页面（平移），onMove 被
            //   连续调用几十次。若每次都"拷贝 → 重排 → 换新表"，后一次拷贝会基于动画中的
            //   旧位置，索引越滚越偏，松手后顺序就错了（现象："平移页面时顺序错误"）。
            val list = rdmOrder.toMutableList()
            rdmOrder = list            // 让适配器读的就是这个实例
            val cb = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                    tgt: RecyclerView.ViewHolder): Boolean {
                    val a = vh.bindingAdapterPosition
                    val b = tgt.bindingAdapterPosition
                    if (a < 0 || b < 0 || a >= list.size || b >= list.size) return false
                    // 就地逐格交换：中间任何一次回调被丢弃都不会累积错位
                    if (a < b) for (i in a until b) java.util.Collections.swap(list, i, i + 1)
                    else       for (i in a downTo b + 1) java.util.Collections.swap(list, i, i - 1)
                    imfb.rvRdm.adapter?.notifyItemMoved(a, b)
                    // ⚠ 这里**不能**调 refreshRdmList()：它会整个替换 adapter，
                    //   手势会被打断，表现成"只能相邻换位"。只就地更新地址预览。
                    updateRdmPreviews()
                    return true
                }
                override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
                override fun isLongPressDragEnabled() = true
                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    updateRdmPreviews()
                    saveRdmOrder()          // 记住这次拖出来的顺序，下次扫描还按它排
                }
            }
            rdmTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(cb)
            rdmTouchHelper?.attachToRecyclerView(imfb.rvRdm)
        }
    }

    /**
     * 只就地更新每行的"新地址"预览，**不重建 adapter**。
     *
     * 拖动过程中若调用 refreshRdmList()（内部会 `rvRdm.adapter = ...`），
     * ItemTouchHelper 正在拖的那个 ViewHolder 会失效 → 手势中断，
     * 表现成"每动一格就掉一下、只能相邻换位"。
     */
    private fun updateRdmPreviews() {
        if (!rdmReorderMode) return
        val start = (imfb.etRdmStart.text.toString().toIntOrNull() ?: 1)
            .coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
        val plan = assignAddresses(rdmOrder, start)
        val rv = imfb.rvRdm
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val pos = rv.getChildAdapterPosition(child)
            if (pos < 0 || pos >= rdmOrder.size) continue
            val d = rdmOrder[pos]
            val tv = child.findViewById<TextView>(R.id.tvRdmAddr) ?: continue
            val na = plan?.getOrNull(pos)?.second
            // 与 onBindViewHolder 保持同一规则：右边只显示预期地址
            tv.text = if (na == null) "✗ 放不下" else "${if (d.universe == 1) "A" else "B"}@$na"
            tv.setTextColor(ContextCompat.getColor(this,
                if (na == null) R.color.err else R.color.ok))
            // 信息行里的小字"原地址"也跟着更新
            val info = child.findViewById<TextView>(R.id.tvRdmInfo)
            if (info != null) {
                info.text = buildString {
                    val lo2 = if (na != null) na else d.address
                    append("占通道 $lo2 ~ ${lo2 + d.channelCount - 1}（${d.channelCount}CH")
                    if (d.personality.isNotEmpty()) append(" · ${d.personality}")
                    if (d.personalityCount > 1) append(" · 模式${d.personalityNum}/${d.personalityCount}")
                    append("）")
                    if (na != null && na != d.address) append(" · 原 ${d.addrLabel()}")
                }
            }
        }
    }

    /**
     * 把 RDM 扫描到的灯具按当前排序**建成 App 里的灯具实例**。
     *
     * 为什么需要它：RDM 改的是灯具**内部**的地址，而 App 的推子/效果/程序都作用于
     * "实例"。两边不同步的话，RDM 排完序还得在灯库里手工再配一遍。
     */
    private fun offerCreateInstances() {
        val batch = rdmPendingAssign ?: return
        rdmPendingAssign = null
        if (batch.isEmpty()) return
        // 先看能匹配上几个灯型，避免点了才发现没一个对得上
        val matched = batch.count { matchRdmFixture(it.first) != null }
        if (matched == 0) {
            toast(Lang.t(R.string.k_address_set_no_matching_fixture_type_in_the_libr))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_also_create_as_fixtures))
            .setMessage(Lang.t(R.string.k_1_s_2_s_match_a_fixture_type_in_the_library_n_n, matched, batch.size) +
                        "创建后就可以在推子页直接控制这些灯（地址与刚写入的一致）。")
            .setPositiveButton(Lang.t(R.string.k_create)) { _, _ -> createInstancesFromRdm(batch) }
            .setNegativeButton(Lang.t(R.string.k_no), null)
            .show()
    }

    /** 在本地灯库里找与某台 RDM 设备匹配的灯型。 */
    /**
     * 在本地灯库里找与某台 RDM 设备匹配的灯型。
     *
     * ⚠ 匹配必须**保守**：宁可不建，也不能猜错。
     *   猜错会静默产生一台"通道数对、属性全错"的实例（例如 RDM 报 ARES-S4 20CH，
     *   灯库里却没有这个型号，按通道数就匹配到了 Eos B19），
     *   现场表现为推子能推、灯乱动，比没建更难查。
     */
    private fun matchRdmFixture(d: RdmDevice): FixtureDef? {
        fun norm(s: String) = s.uppercase().replace(" ", "").replace("-", "")
            .replace("_", "").replace("(", "").replace(")", "")
        val key = norm(d.modelDesc.ifEmpty { d.model })
        if (key.isEmpty()) return null
        val all = fixtureStore.fixtures
        // 1) 名称互相包含，且**通道数也要对得上**（同一型号常有 15CH/20CH 等不同模式）
        val byName = all.filter {
            val n = norm(it.name)
            n.contains(key) || key.contains(n)
        }
        byName.firstOrNull { it.channelCount == d.channelCount }?.let {
            android.util.Log.d("RDM", "匹配 [$key] → 名称+通道数命中: ${it.name}(${it.channelCount}CH)")
            return it
        }
        // 2) 名称命中但只有唯一一个候选（通道数不一致也认，型号名是更强证据）
        if (byName.size == 1) {
            android.util.Log.d("RDM", "匹配 [$key] → 仅名称命中: ${byName[0].name}(${byName[0].channelCount}CH)")
            return byName[0]
        }
        // 3) 名称完全没命中：只有"通道数唯一"时才敢认，否则放弃
        val byCh = all.filter { it.channelCount == d.channelCount }
        android.util.Log.d("RDM", "匹配 [$key] 名称未命中（名称候选 ${byName.size} 个）；" +
            "通道数 ${d.channelCount} 候选 ${byCh.size} 个: ${byCh.take(5).joinToString { it.name }}")
        if (byName.isEmpty() && byCh.size == 1) return byCh[0]
        return null      // 不确定 → 不建（由调用方明确提示）
    }

    /**
     * 按 (设备 → 地址) 列表创建实例。
     *
     * ## 地址冲突怎么处理（v9.3）
     * 扫描→排序→建实例时，同一地址上很可能已经有旧实例（换灯、重扫、改过地址都会）。
     * 原来是一律跳过，用户只看到"跳过 N 台"却不知道该怎么办。现在两种模式：
     *   · 设置页开关「地址冲突时覆盖」打开 → 直接覆盖（适合熟练用户批量重建）
     *   · 关闭（默认）→ 弹窗问一次，二选一【覆盖】【跳过】，把决定权交回用户
     */
    private fun createInstancesFromRdm(batch: List<Pair<RdmDevice, Int>>) {
        // 实例是按这个顺序建的，把顺序记下来，下次扫描仍按它排
        if (batch.isNotEmpty()) rdmStore.saveOrder(batch.map { it.first.uid })

        // 三分类：可直接建 / 灯库里没这个灯型 / 地址已被占用
        val ready = ArrayList<Triple<FixtureDef, RdmDevice, Int>>()
        val noMatch = ArrayList<String>()
        val clash = ArrayList<Triple<FixtureDef, RdmDevice, Int>>()
        for ((d, addr) in batch) {
            val def = matchRdmFixture(d)
            if (def == null) {
                // 匹配不到就明确报告型号，让人知道该往灯库里加什么
                noMatch.add("${d.modelDesc.ifEmpty { d.model }}(${d.channelCount}CH)")
                continue
            }
            val taken = fixtureStore.instances().any { it.universe == d.universe && it.addr == addr }
            if (taken) clash.add(Triple(def, d, addr)) else ready.add(Triple(def, d, addr))
        }

        if (clash.isEmpty()) {
            commitRdmInstances(ready, emptyList(), noMatch)
            return
        }
        if (rdmOverwrite) {
            commitRdmInstances(ready, clash, noMatch)
            return
        }
        // 提示模式：把冲突数量说清楚，让用户决定
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.s_rdm_clash_title))
            .setMessage(Lang.t(R.string.s_rdm_clash_msg, clash.size, ready.size))
            .setPositiveButton(Lang.t(R.string.s_overwrite)) { _, _ ->
                commitRdmInstances(ready, clash, noMatch)
            }
            .setNegativeButton(Lang.t(R.string.s_skip)) { _, _ ->
                commitRdmInstances(ready, emptyList(), noMatch)
            }
            .show()
    }

    /**
     * 真正落盘：先按需删掉被占地址上的旧实例，再建新的。
     *
     * @param overwrite 需要覆盖的项（会先删掉同宇宙同地址的旧实例）
     */
    private fun commitRdmInstances(
        ready: List<Triple<FixtureDef, RdmDevice, Int>>,
        overwrite: List<Triple<FixtureDef, RdmDevice, Int>>,
        noMatch: List<String>
    ) {
        var replaced = 0
        for ((_, d, addr) in overwrite) {
            val olds = fixtureStore.instances().filter { it.universe == d.universe && it.addr == addr }
            if (olds.isNotEmpty()) {
                fixtureStore.removeInstances(olds.map { it.id })
                replaced++
            }
        }
        var created = 0
        var failed = 0
        for ((def, d, addr) in ready + overwrite) {
            val err = fixtureStore.addInstances(def.id, d.model.ifEmpty { def.name }, addr, 1, d.universe)
            if (err == null) created++ else failed++
        }
        val msg = buildString {
            append(Lang.t(R.string.s_created_n, created))
            if (replaced > 0) append(Lang.t(R.string.s_replaced_n, replaced))
            if (failed > 0) append(Lang.t(R.string.s_failed_n, failed))
            if (noMatch.isNotEmpty()) {
                append("\n\n" + Lang.t(R.string.s_n_no_match_p, noMatch.size) + "\n")
                append(noMatch.joinToString("、"))
                append("\n" + Lang.t(R.string.s_import_then_retry))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_result))
            .setMessage(msg)
            .setPositiveButton(Lang.t(R.string.k_ok_2), null)
            .show()
        renderInstanceBar()
        refreshInstanceMgrList()
    }

    /** 弹出某台 RDM 设备的**全部**参数（GET 到的每一项）。 */
    private fun showRdmDetailDialog(d: RdmDevice) {
        val lines = d.detailLines().joinToString("\n") { (k, v) -> "%-8s %s".format(k, v) }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(d.model)
            .setMessage(lines)
            .setPositiveButton(Lang.t(R.string.s_close), null)
            .setNeutralButton(Lang.t(R.string.s_identify)) { _, _ -> identifyRdmDevice(d) }
        // 灯库里没有匹配型号时，就地提供"建一个灯型"——
        // 这正是用户发现缺型号的那一刻，比让他跑去灯库页找入口顺手得多
        if (matchRdmFixture(d) == null) {
            b.setNegativeButton(Lang.t(R.string.k_new_fixture_type)) { _, _ -> createFixtureFromRdm(d) }
        }
        b.show()
    }

    /**
     * 用这台 RDM 灯的信息在灯库里新建一个**骨架灯型**，并立刻建成实例。
     *
     * 生成的是"通道数正确、通道含义待补"的灯型（RDM 拿不到每通道用途，
     * 详见 FixtureStore.createFromRdm 的说明）。
     */
    private fun createFixtureFromRdm(d: RdmDevice) {
        val def = fixtureStore.createFromRdm(
            name = d.modelDesc.ifEmpty { d.model },
            manufacturer = d.manufacturer,
            channelCount = d.channelCount,
            mode = d.personality
        )
        android.util.Log.d("RDM", "新建骨架灯型: ${def.name} ${def.channelCount}CH id=${def.id}")
        // 顺手建实例：用户要的就是"能推到这台灯"，不必再点一次
        val uni = d.universe
        val addr = rdmPendingAssign?.firstOrNull { it.first.uid == d.uid }?.second ?: d.address
        val err = fixtureStore.addInstances(def.id, d.model.ifEmpty { def.name }, addr, 1, uni)
        val msg = buildString {
            append("已新建灯型「${def.name}」（${def.channelCount}CH）")
            if (err == null) {
                append("\n并已在 ${if (uni == 1) "A" else "B"}@$addr 建好实例。")
            } else {
                append("\n但建实例失败：$err")
            }
            append("\n\n注意：RDM 读不到每个通道的用途，通道名暂为 CH1~CH${d.channelCount}。")
            append("如需完整通道定义，请导入同型号灯库文件覆盖。")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_new_fixture_type))
            .setMessage(msg)
            .setPositiveButton(Lang.t(R.string.k_ok_2)) { _, _ ->
                renderInstanceBar()
                refreshInstanceMgrList()
                refreshFixturePage()
            }
            .show()
    }

    /**
     * 扫描 RDM 总线（真实扫描：固件做设备发现 + 逐台 GET 参数）。
     *
     * 流程：发 0x40 → 固件暂停该通道 DMX → RDM 发现 + 读参数 → 回 0x89 头
     *       → 每台一条 0x8A（全部参数）→ App 逐条解析入列表。
     * 扫描期间保持"扫描中"状态，收到 0x89 头即视为结束（0x8A 先于头之前到达也无妨，
     * 因为固件是先发头再发设备帧）。
     */
    private fun doRdmScan() {
        android.util.Log.d("RDM", "doRdmScan 进入: bleState=${ble.state} scanning=$rdmScanning")
        if (rdmScanning) return
        if (ble.state != BleManager.State.CONNECTED) {
            android.util.Log.w("RDM", "扫描被拒：未连接（state=${ble.state}）")
            toast(Lang.t(R.string.k_connect_to_a_device_first)); return
        }
        rdmScanning = true
        rdmDevices = emptyList()
        rdmLastError = ""
        refreshRdmList()
        // 一次扫描最多可能几秒（设备多、响应慢），超时兜底避免一直转圈
        syncHandler.postDelayed({
            if (rdmScanning) {
                rdmScanning = false
                if (rdmLastError.isEmpty()) rdmLastError = "扫描超时（设备无响应）"
                refreshRdmList()
                toast(Lang.t(R.string.k_rdm_scan_timed_out))
            }
        }, 15000)
        // A 通道（宇宙 0）。B 通道（宇宙 1）在双宇宙打开且需要时可再加一个按钮。
        engine.sendRaw(encodeRdmScan(0))
        android.util.Log.d("RDM", "已发送 0x40 扫描帧（A 通道）")
    }

    /** 收到固件 0x89 扫描头：结束"扫描中"状态。 */
    private fun onRdmScanDone(count: Int, universe: Int, ok: Boolean, simulate: Boolean, err: String) {
        syncHandler.removeCallbacksAndMessages(null)
        rdmScanning = false
        rdmSimulated = simulate
        rdmLastError = if (ok) "" else err.ifEmpty { "未发现 RDM 设备" }
        // 扫描结果是固件侧的 UID 顺序，这里套用用户保存过的顺序
        rdmDevices = applySavedRdmOrder(rdmDevices)
        if (rdmReorderMode) rdmOrder = rdmDevices
        refreshRdmList()
        toast(if (ok) "发现 $count 台 RDM 设备${if (simulate) "（模拟）" else ""}" else rdmLastError)
    }

    /** 收到固件 0x8A 设备帧：解析出全部参数并加入列表。 */
    private fun onRdmDevice(d: ByteArray) {
        val dev = parseRdmDevice(d) ?: return
        // ⚠ 顺序必须在这里套用，不能只放在 onRdmScanDone：
        //   固件是"先发 0x89 头、再逐条发 0x8A 设备"，头到达时列表还是空的，
        //   在那一刻排序等于没排。每来一台就按保存的顺序插入，才是对的。
        rdmDevices = applySavedRdmOrder(
            rdmDevices.filterNot { it.uid == dev.uid } + dev)
        if (rdmReorderMode) rdmOrder = rdmDevices
        refreshRdmList()
    }

    /**
     * 按用户保存的顺序重排扫描结果。
     *
     * 为什么需要：RDM 发现算法返回的顺序由 UID 决定（跟现场位置无关），
     * 用户辛苦拖出来的顺序如果一扫描就没了，等于白拖。
     * 保存的是 UID 列表，所以灯换个口/换个地址也还认得出同一台。
     */
    private fun applySavedRdmOrder(list: List<RdmDevice>): List<RdmDevice> {
        val saved = rdmStore.savedOrder()
        if (saved.isEmpty()) return list
        val idx = saved.withIndex().associate { it.value to it.index }
        // 保存过顺序的按记录排；新出现的（索引 -1）拍到末尾
        return list.sortedBy { idx[it.uid] ?: Int.MAX_VALUE }
    }

    /** 把当前顺序存起来（拖动结束、加实例时调用）。 */
    private fun saveRdmOrder() {
        // 让 rdmDevices 与 rdmOrder 保持一致：非排序模式下列表读的是 rdmDevices，
        // 不同步的话"拖完退出排序 → 列表又变回原顺序"。
        if (rdmOrder.isNotEmpty()) rdmDevices = rdmOrder
        if (rdmOrder.isNotEmpty()) rdmStore.saveOrder(rdmOrder.map { it.uid })
        else if (rdmDevices.isNotEmpty()) rdmStore.saveOrder(rdmDevices.map { it.uid })
    }

    /** 识别：让灯具闪烁，便于现场对位。 */
    private fun identifyRdmDevice(d: RdmDevice) {
        if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return }
        engine.sendRaw(encodeRdmIdentify(d.universe - 1, d.uidBytes, true))
        syncHandler.postDelayed({
            engine.sendRaw(encodeRdmIdentify(d.universe - 1, d.uidBytes, false))
        }, 1500)
        toast(Lang.t(R.string.k_1_s_will_flash_for_1_5_s, d.model))
    }

    /** 远程改地址（RDM SET DMX_START_ADDRESS）。 */
    private fun showRdmSetAddressDialog(d: RdmDevice) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(d.address.toString())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_set_address_1_s, d.model))
            .setMessage(Lang.t(R.string.k_uid_1_s_nnow_2_s_3_s_channels, d.uid, d.addrLabel(), d.channelCount))
            .setView(input)
            .setPositiveButton(Lang.t(R.string.k_write)) { _, _ ->
                if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setPositiveButton }
                val a = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                if (a < 1 || a + d.channelCount - 1 > DmxProtocol.UNIVERSE_SIZE) {
                    toast(Lang.t(R.string.k_out_of_range_needs_1_s_2_s_universe_limit_3_s, a, a + d.channelCount - 1, DmxProtocol.UNIVERSE_SIZE))
                    return@setPositiveButton
                }
                engine.sendRaw(encodeRdmSetAddress(d.universe - 1, d.uidBytes, a))
                rdmStore.setCachedAddress(d.uid, d.universe, a)
                toast(Lang.t(R.string.k_address_command_sent_waiting_for_the_device))
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
            .show()
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
            .setTitle(Lang.t(R.string.k_delete_1_s_fixtures, ids.size))
            .setMessage(Lang.t(R.string.k_1_s_n_nthis_cannot_be_undone, detail))
            .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ ->
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
                toast(Lang.t(R.string.k_deleted_1_s_fixtures, ids.size))

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
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
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
            tvChCount.text = Lang.t(R.string.k_1_s_uses_2_s_channels, inst.name, ch)
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
            .setTitle(Lang.t(R.string.k_set_address))
            .setView(holder)
            .setPositiveButton(Lang.t(R.string.k_save)) { _, _ ->
                val a = (etAddr.text.toString().toIntOrNull() ?: 1)
                    .coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
                val before = inst
                fixtureStore.updateInstance(inst.copy(addr = a, universe = band))
                val after = fixtureStore.instances().find { it.id == inst.id }
                if (after == null || after.addr != a || after.universe != band) {
                    toast("保存失败：${if (band == 1) "A" else "B"} 通道 $a 越界或与已有灯具重叠")
                } else {
                    toast(Lang.t(R.string.k_changed_to_1_s, after.label()))
                }
                refreshInstanceMgrList()
                renderInstanceBar()
                refreshFixturePage()
                if (before.id == currentInstanceId) applySelectedInstance()
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null)
            .show()
    }

    private fun wireStoragePage() {
        var mscEnabled = false
        fixb.swMsc.setOnClickListener {
            if (!mscEnabled) {
                if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
                engine.sendRawCmd(0x30, 1)
                mscEnabled = true
                fixb.swMsc.text = Lang.t(R.string.s_close)
                fixb.swMsc.setBackgroundColor(getColor(R.color.ok))
                fixb.tvMscStatus.text = Lang.t(R.string.k_enabled_the_console_can_access_the_esp32_fixture)
                fixb.tvMscStatus.setTextColor(getColor(R.color.ok))
            } else {
                engine.sendRawCmd(0x30, 0)
                mscEnabled = false
                fixb.swMsc.text = Lang.t(R.string.s_on)
                fixb.swMsc.setBackgroundColor(getColor(R.color.surface2))
                fixb.tvMscStatus.text = Lang.t(R.string.s_close)
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
            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
            storageMode = 1
            curPath = ""            // 设备灯库固定显示根目录
            fixb.btnNewFolder.visibility = View.GONE
            refreshDeviceFiles()
        }

        fixb.btnFileMgr.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
            storageMode = 2
            fixb.btnNewFolder.visibility = View.VISIBLE
            refreshDeviceFiles()
        }

        // 返回上级目录（文件管理页签）
        fixb.btnGoUp.setOnClickListener {
            if (storageMode != 2) return@setOnClickListener
            if (curPath.isEmpty()) { toast(Lang.t(R.string.k_already_at_root)); return@setOnClickListener }
            curPath = curPath.substringBeforeLast('/', "")
            refreshDeviceFiles()
        }

        // 新建文件夹
        fixb.btnNewFolder.setOnClickListener {
            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "文件夹名（不含 / 或 \\）"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(Lang.t(R.string.k_new_folder))
                .setView(input)
                .setPositiveButton(Lang.t(R.string.k_create)) { _, _ ->
                    val nm = input.text.toString().trim()
                    if (nm.isEmpty() || nm.contains('/') || nm.contains('\\')) {
                        toast(Lang.t(R.string.k_invalid_name))
                        return@setPositiveButton
                    }
                    dirOpName = nm
                    engine.sendMkdir(curPath, nm)
                }
                .setNegativeButton(Lang.t(R.string.k_cancel), null)
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
        fixb.tvListHint.text = Lang.t(R.string.k_fetching)
        engine.sendListFiles(curPath)
    }

    /** 确认删除设备文件夹（空文件夹）。 */
    private fun rmdirConfirm(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_delete_folder))
            .setMessage(Lang.t(R.string.k_delete_empty_folder_1_s_on_device_fails_if_not_e, name))
            .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ ->
                dirOpName = name
                engine.sendRmdir(curPath, name)
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null).show()
    }

    /** 重命名设备文件/文件夹。 */
    private fun renameDialog(oldName: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(oldName)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(Lang.t(R.string.k_rename))
            .setMessage(Lang.t(R.string.k_original_name_1_s, oldName))
            .setView(input)
            .setPositiveButton(Lang.t(R.string.k_ok)) { _, _ ->
                val nm = input.text.toString().trim()
                if (nm.isEmpty() || nm.contains('/') || nm.contains('\\')) {
                    toast(Lang.t(R.string.k_invalid_name))
                    return@setPositiveButton
                }
                dirOpName = nm
                engine.sendRename(curPath, oldName, nm)
            }
            .setNegativeButton(Lang.t(R.string.k_cancel), null).show()
    }

    /** 移动/复制目标选择：先请求设备全量目录树（0x3C），收集完（0x98）后弹窗选择。 */
    private fun pickDestDialog(name: String, isCopy: Boolean) {
        if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return }
        dirList.clear()
        dirCollectCb = {
            val act = if (isCopy) "复制" else "移动"
            val opts = mutableListOf("（根目录）")
            opts.addAll(dirList)
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(Lang.t(R.string.k_1_s_2_s_to, act, name))
                .setItems(opts.toTypedArray()) { _, which ->
                    val dst = if (which == 0) "" else opts[which]
                    if (dst == curPath) { toast(Lang.t(R.string.k_target_is_the_same_as_the_current_position)); return@setItems }
                    val dstShow = if (dst.isEmpty()) "根目录" else dst
                    moveOp = "$act: $name → $dstShow"
                    if (isCopy) engine.sendCopy(curPath, name, dst)
                    else        engine.sendMove(curPath, name, dst)
                    toast(Lang.t(R.string.k_in_progress_1_s_to_2_s, act, dstShow))
                }
                .setNegativeButton(Lang.t(R.string.k_cancel), null)
                .show()
        }
        engine.sendListDirs()
        toast(Lang.t(R.string.k_fetching_device_folders))
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
                        tvInfo.text = Lang.t(R.string.k_folder)
                        btn.text = Lang.t(R.string.s_delete)
                        btn.setOnClickListener {
                            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
                            rmdirConfirm(f.name)
                        }
                        // 文件管理页签：点按进入文件夹
                        root.setOnClickListener {
                            if (storageMode != 2) return@setOnClickListener
                            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
                            curPath = if (curPath.isEmpty()) f.name else "$curPath/${f.name}"
                            refreshDeviceFiles()
                        }
                        // 长按文件夹 → 重命名 / 移动 / 删除（仅文件管理页签）
                        root.setOnLongClickListener {
                            if (storageMode != 2) return@setOnLongClickListener true
                            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnLongClickListener true }
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
                        tvInfo.text = Lang.t(R.string.k_1_s_bytes, f.size)
                        btn.text = Lang.t(R.string.k_download)
                        btn.setOnClickListener {
                            if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
                            downloadingFile = f.name
                            downloadBuf = ByteArray(0)
                            engine.sendDownloadFile(curPath, f.name)
                            toast(Lang.t(R.string.k_downloading_1_s, f.name))
                        }
                        // 长按设备文件：文件管理页签 → 重命名/移动/复制/删除
                        root.setOnLongClickListener {
                            if (ble.state != BleManager.State.CONNECTED) {
                                toast(Lang.t(R.string.k_connect_to_a_device_first))
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
                                                toast(Lang.t(R.string.k_deleting_1_s, f.name))
                                            }
                                        }
                                    }
                                    .show()
                            } else {
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(Lang.t(R.string.k_delete_file))
                                    .setMessage(Lang.t(R.string.k_delete_file_1_s_on_device, f.name))
                                    .setPositiveButton(Lang.t(R.string.s_delete)) { _, _ ->
                                        deletingFile = f.name
                                        engine.sendDeleteFile(curPath, f.name)
                                        toast(Lang.t(R.string.k_deleting_1_s, f.name))
                                    }
                                    .setNegativeButton(Lang.t(R.string.k_cancel), null)
                                    .show()
                            }
                            true
                        }
                    }
                } else {
                    val lib = libs[pos]
                    tvName.text = "${lib.manufacturer} ${lib.name}"
                    tvInfo.text = "${lib.mode}  ${lib.channelCount}CH"
                    btn.text = Lang.t(R.string.k_upload)
                    root.setOnLongClickListener(null)
                    btn.setOnClickListener {
                        if (ble.state != BleManager.State.CONNECTED) { toast(Lang.t(R.string.k_connect_to_a_device_first)); return@setOnClickListener }
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
            fixb.tvListTitle.text = Lang.t(R.string.k_saved_in_the_app)
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
        toast(Lang.t(R.string.k_uploading_1_s, fileName))
    }

    /** 顺序上传一个灯具的全部原始文件（xml/d4/r20）。 */
    private fun uploadFilesSequential(files: List<File>, label: String) {
        fun next(idx: Int) {
            if (idx >= files.size) { toast(Lang.t(R.string.k_all_uploads_complete_1_s, label)); return }
            val f = files[idx]
            uploadFileData(f.name, f.readBytes()) {
                uploadHandler.postDelayed({ next(idx + 1) }, 300)
            }
        }
        next(0)
    }
}
