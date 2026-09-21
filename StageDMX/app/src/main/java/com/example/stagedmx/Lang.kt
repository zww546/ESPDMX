package com.example.stagedmx

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.Locale

/**
 * 全局中英文（v9：以 `strings.xml` 为准）。
 *
 * ## 为什么不用系统的 setApplicationLocales()
 * `MainActivity` 的 `configChanges` 不含 `locale`，改系统语言会**重建 Activity**，
 * 而 BLE 连接状态挂在 Activity 上 —— 现场表现就是"切换语言后要重新连设备"。
 * 所以这里用**语言覆盖型 Context**：`createConfigurationContext()` 拿到指定语言的
 * `Resources`，`getString()` 从它取，**不改系统语言、不重建 Activity、BLE 不断**。
 *
 * ## 文字是怎么被翻译的
 * 1. **布局/菜单**：已改成 `android:text="@string/xxx"`（阶段 2 迁移完），
 *    但页面是用 Activity 的 Context 加载的（跟随系统语言 = 中文），
 *    所以还需要 [apply] 把 View 树上的中文换成英文。
 * 2. [apply] 的映射**由资源自动生成**（遍历 `R.string` 取中/英两个值配对），
 *    不再像 v8 那样手工维护字典 —— 这就是迁移到 strings.xml 的核心收益：
 *    加一条资源就自动多一条映射，不存在"忘了同步字典"。
 * 3. **代码里设置的文字**：新代码用 `Lang.t(R.string.xxx)`；
 *    阶段 3 尚未迁移完的老代码仍可用 `Lang.t("中文")`（走下方遗留字典）。
 *
 * ⚠ EditText 只翻译 `hint`，不动 `text` —— 用户输入不能被翻译掉。
 */
object Lang {

    /** 当前是否为英文。持久化在 MainActivity 的 appSettings（键 "translated"）。 */
    @Volatile
    var en: Boolean = false
        private set

    private var appCtx: Context? = null
    private var locCtx: Context? = null
    private var zhCtx: Context? = null
    private var enCtx: Context? = null

    /** 资源生成的映射：中文 → 英文 / 英文 → 中文（用于 [apply] 双向替换）。 */
    private var zh2en: Map<String, String> = emptyMap()
    private var en2zh: Map<String, String> = emptyMap()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        rebuild()
    }

    fun set(enabled: Boolean) {
        en = enabled
        rebuild()
    }

    private fun ctxFor(locale: Locale): Context? {
        val base = appCtx ?: return null
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }

    private fun rebuild() {
        if (appCtx == null) return
        if (zhCtx == null) zhCtx = ctxFor(Locale.SIMPLIFIED_CHINESE)
        if (enCtx == null) enCtx = ctxFor(Locale.ENGLISH)
        locCtx = if (en) enCtx else zhCtx
        if (zh2en.isEmpty()) buildMaps()
    }

    /**
     * 遍历 `R.string` 的全部条目，取"中文值 → 英文值"配对。
     *
     * 用反射枚举资源 id 是为了**不用维护第二份清单**：往 strings.xml 里加一条，
     * 这里自动就多一条映射，不存在"忘了同步字典"的问题。
     */
    private fun buildMaps() {
        val z = zhCtx ?: return
        val e = enCtx ?: return
        val fwd = HashMap<String, String>()
        val rev = HashMap<String, String>()
        for (f in R.string::class.java.fields) {
            val id = try { f.getInt(null) } catch (_: Exception) { continue }
            if (id == 0) continue
            val zhVal = try { z.getString(id) } catch (_: Exception) { continue }
            val enVal = try { e.getString(id) } catch (_: Exception) { continue }
            if (zhVal != enVal) {
                fwd[zhVal] = enVal
                // 反向表值重复时保留第一个，避免"还原成别的词"
                rev.putIfAbsent(enVal, zhVal)
            }
        }
        zh2en = fwd
        en2zh = rev
    }

    // ---- 取文案 ----

    /** 新代码：从资源取（推荐）。 */
    fun t(resId: Int): String = (locCtx ?: appCtx)?.getString(resId) ?: ""

    /**
     * 带参数取文案。
     *
     * ⚠ 参数类型是 `Any?` 而不是 `Any`：调用点常传可空值（如 `f.name` 是 String?），
     *   若收窄成 `Any` 会编译不过。null 在这里**显式转成 "null" 之外的空串**，
     *   避免界面上出现字面的 "null"。
     */
    fun t(resId: Int, vararg args: Any?): String {
        val safe = args.map { it ?: "" }.toTypedArray()
        return (locCtx ?: appCtx)?.getString(resId, *safe) ?: ""
    }

    /**
     * 遗留用法：直接传中文。阶段 3 迁移完成后可删除。
     * 先查资源映射（覆盖已迁到 strings.xml 的词条），再查遗留字典。
     */
    fun t(zh: String): String {
        if (!en) return zh
        return zh2en[zh] ?: LEGACY_EN[zh] ?: zh
    }

    /** 遗留用法（带参数）。 */
    fun t(zh: String, vararg args: Any): String = String.format(t(zh), *args)

    // ---- 应用到界面 ----

    /**
     * 递归翻译 View 树上的文字。幂等（中文模式用反向表还原），可反复调用。
     * 在切页、切语言后调用即可。
     */
    fun apply(root: View?) {
        when (root) {
            null -> return
            is EditText -> {
                val h = root.hint?.toString() ?: return
                val t = if (en) (zh2en[h] ?: LEGACY_EN[h]) else (en2zh[h] ?: LEGACY_ZH[h])
                if (t != null && t != h) root.hint = t
            }
            is TextView -> {
                val cur = root.text?.toString() ?: return
                val t = if (en) (zh2en[cur] ?: LEGACY_EN[cur]) else (en2zh[cur] ?: LEGACY_ZH[cur])
                if (t != null && t != cur) root.text = t
            }
            is ViewGroup -> for (i in 0 until root.childCount) apply(root.getChildAt(i))
        }
    }

    // ========================================================================
    // 遗留字典（v8 期间手工维护）。
    //
    // 阶段 3 会把 Kotlin 里的中文调用点逐步换成 Lang.t(R.string.xxx)，
    // 每迁一条，这里就可以删一条。**新词条请加到 strings.xml，不要加到这里。**
    // ========================================================================
    private val LEGACY_EN: Map<String, String> = mapOf(
        "取消" to "Cancel",
        "确定" to "OK",
        "好" to "OK",
        "关闭" to "Close",
        "删除" to "Delete",
        "保存" to "Save",
        "完成" to "Done",
        "完成排序" to "Finish",
        "创建" to "Create",
        "不用" to "No",
        "打开" to "Open",
        "返回" to "Back",
        "识别" to "Identify",
        "改址" to "Address",
        "写入" to "Write",
        "加实例" to "Add",
        "编辑顺序" to "Reorder",
        "取消排序" to "Cancel Sort",
        "扫描设备" to "Scan",
        "写入地址" to "Write Addr",
        "按顺序写入地址" to "Write in Order",
        "按顺序加实例" to "Add in Order",
        "新建灯型" to "New Type",
        "推子" to "Faders",
        "效果" to "Effects",
        "灯库" to "Library",
        "灯具" to "Fixtures",
        "设置" to "Settings",
        "灯具管理" to "Fixture Manager",
        "设备" to "Device",
        "已配接" to "Patched",
        "RDM 设备" to "RDM Devices",
        "主控亮度" to "Master",
        "全黑" to "Blackout",
        "定位" to "Locate",
        "记录" to "Record",
        "语言 / Language" to "Language",
        "双宇宙输出" to "Dual universe",
        "灯库编辑" to "Fixture editor",
        "点「扫描设备」开始" to "Tap Scan to start",
        "扫描中…" to "Scanning…",
        "正在扫描…（DMX 输出会暂停数秒）" to "Scanning… (DMX paused briefly)",
        "还没有扫描到 RDM 设备" to "No RDM devices yet",
        "起始地址" to "Start addr",
        "当前" to "Now",
        "厂商" to "Vendor",
        "型号描述" to "Model",
        "设备标签" to "Label",
        "型号 ID" to "Model ID",
        "产品类别" to "Category",
        "当前模式" to "Personality",
        "软件版本" to "Software",
        "子设备数" to "Sub-devices",
        "传感器数" to "Sensors",
        "创建结果" to "Result",
        "未发现 RDM 设备" to "No RDM devices found",
        "内置效果" to "Built-in",
        "程序" to "Programs",
        "运行中" to "Running",
        "预设" to "Presets",
        "已连接" to "Connected",
        "未连接" to "Disconnected",
        "连接中" to "Connecting",
        "连接断开" to "Disconnected",
        "连接超时" to "Connect timeout",
        "请先连接设备" to "Connect to a device first",
        "共 %d 台" to "%d fixtures",
    )

    private val LEGACY_ZH: Map<String, String> =
        LEGACY_EN.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() }
}
