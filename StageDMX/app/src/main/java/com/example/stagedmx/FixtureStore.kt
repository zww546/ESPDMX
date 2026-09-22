package com.example.stagedmx

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 灯具通道定义。
 */
data class FixtureChannel(
    val number: Int,         // 1-based DMX 通道
    val name: String,        // 中文名
    val originalName: String,// 原始英文名（用于关闭翻译时显示）
    val attribute: String = "", // MA 标准 attribute（COLOR1/GOBO1/PAN...），用于上下文翻译
    val defaultValue: Int,   // 0..255
    val highlightValue: Int, // 0..255
    val hasFine: Boolean = false,
    val fineNumber: Int? = null,
    val physFrom: Float = 0f,  // 物理起始值（度）
    val physTo: Float = 0f     // 物理终止值（度）
)

data class FixtureDef(
    val id: String,
    val name: String,
    val manufacturer: String,
    val mode: String,
    val channelCount: Int,
    val channels: List<FixtureChannel>,
    val panRange: Float = 0f,       // PAN 行程（度），如 540.0
    val tiltRange: Float = 0f,      // TILT 行程（度），如 270.0
    val ptSpeedCh: Int? = null      // Pan/Tilt Speed 通道号，没有则为 null
) {
    /**
     * 按属性名查找通道号（1-based），找不到返 null。
     *
     * 匹配优先级（重要）：
     *   1) 标准 attribute 字段精确匹配（MA2 XML / D4 的 attribute 如 BLADE1A、SHAPER ROT）
     *   2) attribute 包含匹配
     *   3) 用户通道名/原始名精确匹配
     *   4) 用户通道名/原始名包含匹配
     *
     * 之所以必须先看 attribute：MA2 灯库把切割片命名成 "1A"/"1B"…、切割旋转命名成 "Index"，
     * 而 attribute 才是唯一可靠的英文标识（BLADE1A / SHAPER ROT）。早期只按名字做包含匹配，
     * 导致 blade、shaper_rot 一律找不到通道 → 切割循环下发全 0 通道 → 效果“无法使用”。
     */
    fun findCh(attribute: String): Int? = findChFine(attribute)?.first

    /** 按属性名查找带 fine 的通道对（coarse, fine），无 fine 则 fine=null。 */
    fun findChFine(attribute: String): Pair<Int, Int?>? {
        val key = attribute.lowercase()
        val norm = normalizeKey(key)
        fun attrNorm(c: FixtureChannel) = normalizeKey(c.attribute)
        fun nameNorm(c: FixtureChannel) = normalizeKey(c.originalName)
        fun zhNorm(c: FixtureChannel) = normalizeKey(c.name)

        val ch = channels.firstOrNull { attrNorm(it) == norm }
            ?: channels.firstOrNull { attrNorm(it).contains(norm) }
            ?: channels.firstOrNull { nameNorm(it) == norm || zhNorm(it) == norm }
            ?: channels.firstOrNull { nameNorm(it).contains(key) || zhNorm(it).contains(key) }
            ?: return null
        return ch.number to (if (ch.hasFine) ch.fineNumber else null)
    }

    companion object {
        /** 归一化：小写 + 去掉空格/下划线/连字符，便于 BLADE1A == blade1a == "Blade 1A"。 */
        fun normalizeKey(s: String): String =
            s.lowercase().replace(" ", "").replace("_", "").replace("-", "")

        /**
         * **型号名**归一化：在 [normalizeKey] 基础上再去掉括号。
         *
         * ⚠ 型号匹配必须用这个，不能用 [normalizeKey]：
         *   RDM 的 DEVICE_MODEL_DESCRIPTION 常写成 `ARES (S4)` / `Wash 20CH (Std)`，
         *   而灯库里的 name 往往没有括号。之前工程里有**两套**归一化 ——
         *   `fixtureForModel()`（灯库查找，用 normalizeKey，不去括号）和
         *   `matchRdmFixture()`（RDM 型号匹配，内联的那套，去括号）对同一个型号
         *   会给出**不同**答案，导致"分组里能自动匹配到灯库、加实例却匹配不到"
         *   这种自相矛盾的现象。
         *
         * 注意**通道属性匹配**（[findChFine]）仍然只用 [normalizeKey]：
         *   那边比的是 attribute（BLADE1A / SHAPER ROT），去括号没有意义。
         */
        fun modelKey(s: String): String =
            normalizeKey(s).replace("(", "").replace(")", "")
    }
}

/**
 * 灯具实例（Patch）——同型号多台 = 多个实例不同起始地址。
 *
 * **双宇宙按 A/B 两条通道分，各自从 1 编号到 512**（与 MA2/Titan 的 universe 模型一致）：
 *   A = 宇宙 1（UART1 / GPIO17），地址 1~512
 *   B = 宇宙 2（UART2 / GPIO18），地址 1~512
 *
 * 对外统一换算成**全局通道 1~1024**（协议与效果计算都用它）：
 *   `globalAddr() = (universe-1)×512 + addr`
 *
 * 好处：一台灯天然不可能跨宇宙 —— 每个宇宙只有 512 个地址，放不下就是放不下，
 * 不会出现"灯的通道一半在 A 线一半在 B 线"这种物理上接不出来的 patch。
 */
data class FixtureInstance(
    val id: String,          // 实例唯一 ID
    val fixtureId: String,   // 所属灯型 FixtureDef.id
    val name: String,        // 实例名，如 "EOS-1"
    val addr: Int,           // **本宇宙内**起始地址 1..512
    val slot: Int = 0,       // 板载槽位 0..7（效果/程序），创建时分配，删除不重排
    val universe: Int = 1,   // 1 = A 通道，2 = B 通道
    /**
     * 所属**灯具分组**（[FixtureGroup.id]）；null = 未分组。
     *
     * ⚠ 一台灯**至多属于一个分组**（承重设计，别改成多归属）：
     *   推子页的"整组选中"、RDM 的"组内地址递增"、"自由调整分组"
     *   这三件事都建立在"归属唯一"上；一旦允许一台灯挂多个组，
     *   "整组选中"该选谁、递增的组内顺序按哪套、拖动该往哪边挪 —— 全会变歧义。
     *   需要"一次控制若干台不同组的灯"时，用推子页的多选（selectedInstanceIds）
     *   去组合，而不是让灯本身多归属。
     */
    val groupId: String? = null
) {
    /** 全局通道号（1..1024）。 */
    fun globalAddr(): Int =
        (universe.coerceIn(1, DmxProtocol.UNIVERSES) - 1) * DmxProtocol.UNIVERSE_SIZE +
            addr.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)

    /** 通道字母：A / B */
    val band: String get() = DmxProtocol.bandLabel(universe)

    /** 显示用："A@128" */
    fun label(): String = "$band@$addr"
}

/**
 * 灯具**分组**（编组）—— 与 [ChannelGroups]（通道功能分组：亮度/位置/颜色…）无关。
 *
 * 用途：把若干台已配接灯具归成一组，推子页可以"整组选中"一起控制。
 * 归属关系记在 [FixtureInstance.groupId] 上（一台灯至多一个组）。
 *
 * 分组本身**不影响 DMX 输出** —— 控台只认通道，推子页在下发前把一次推子动作
 * 按各灯的 attribute 分发到各自的真实通道，分组只是"选择"层面的便利。
 * 所以这个功能是纯 App 的，不需要动固件协议。
 */
data class FixtureGroup(
    val id: String,
    val name: String
)

/**
 * 板载**程序槽**总数（固件 PROG_MAX_COUNT = 8）。
 *
 * ⚠ 这**不是**实例数上限：一台灯可以有很多实例（一个宇宙 512 地址，
 * 20ch 灯能放 25 台），而"同时上传到板子运行的独立程序"才有 8 个槽。
 * 早期版本把两者混用，导致最多只能建 8 个实例 —— 那是 bug。
 */
const val PROG_SLOT_COUNT = 8

/**
 * 单次批量添加的台数上限（只是 UI 一次操作的上限，**不是实例总数上限**）。
 * 实例总数由地址空间决定：一个宇宙 512 地址，20ch 灯能放 25 台、18ch 能放 28 台，
 * A+B 两个宇宙合计约 50 台。
 */
const val MAX_BATCH_ADD = 64

class FixtureStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fixtures", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "fixtures").also { it.mkdirs() }

    // ---------- 灯具实例（Patch）----------
    private val keyInstances = "instances"

    /**
     * 实例 id 的自增序号（进程内）。
     *
     * ⚠ 实例 id 不能只用 `currentTimeMillis`：批量 patch 是**一台一次**调
     *   [addInstances] 的（count=1），同一毫秒内的第二次调用会算出完全一样的
     *   `inst_<ms>_0`。结果列表里出现两个同 id 的实例 —— 按 id 查找/删除/选中/
     *   分组（`autoGroupInstances` 传的就是 id）全部只作用到其中一个，
     *   表现成"删了一台另一台也跟着变/变不掉"这类诡异现象。
     */
    private val idSeq = java.util.concurrent.atomic.AtomicLong()

    /** 生成一个不与 [existing] 冲突的实例 id。 */
    private fun newInstanceId(existing: Collection<FixtureInstance>): String {
        var id: String
        do {
            id = "inst_${System.currentTimeMillis()}_${idSeq.incrementAndGet()}"
        } while (existing.any { it.id == id })
        return id
    }

    /** 全部灯具实例，按全局起始地址排序。 */
    fun instances(): List<FixtureInstance> {
        val raw = prefs.getString(keyInstances, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            stashRawBackup(raw)
            return emptyList()
        }
        val out = ArrayList<FixtureInstance>(arr.length())
        var bad = 0
        for (i in 0 until arr.length()) {
            // ⚠ **逐条隔离**：一条坏记录只丢它自己。
            //   以前整个 `(0 until len).map { ... }` 包在一个 try 里 —— 任意一条
            //   记录缺字段/类型不对，就让**整张实例表读成空**；而紧接着任何一次写入
            //   （哪怕只是加一台灯）都会调用 persistInstances 用空表覆盖磁盘，
            //   用户的全部配接就永久没了，且没有任何提示。
            try {
                out.add(parseInstance(arr.getJSONObject(i), i))
            } catch (_: Exception) {
                bad++
            }
        }
        if (bad > 0) {
            stashRawBackup(raw)
            android.util.Log.w("FixtureStore", "跳过 $bad 条损坏的实例记录（原始存档已备份到 ${keyInstances}.backup）")
        }
        return out.sortedBy { it.globalAddr() }
    }

    /** 解析单条实例记录（v24 的 universe+startAddr 与 v25 的全局 startAddr 都兼容）。 */
    private fun parseInstance(o: JSONObject, i: Int): FixtureInstance {
        val uniRaw = if (o.has("universe")) o.getInt("universe").coerceIn(1, DmxProtocol.UNIVERSES) else 0
        val stored = o.getInt("startAddr")
        val uni: Int
        val addr: Int
        if (uniRaw > 0) {
            uni = uniRaw
            addr = stored.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
        } else {
            uni = if (stored > DmxProtocol.UNIVERSE_SIZE) 2 else 1
            addr = (if (uni > 1) stored - DmxProtocol.UNIVERSE_SIZE else stored)
                .coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
        }
        return FixtureInstance(
            id = o.getString("id"),
            fixtureId = o.getString("fixtureId"),
            name = o.getString("name"),
            addr = addr,
            universe = uni,
            // 程序槽：只用于上传板载程序，超出 8 个时取值 0（不影响实例本身）
            slot = (if (o.has("slot")) o.getInt("slot") else i) % PROG_SLOT_COUNT,
            // v26 起：灯具分组。旧存档没有这个字段（或为 null）= 未分组，属正常
            groupId = if (o.has("groupId") && !o.isNull("groupId"))
                o.getString("groupId").takeIf { it.isNotEmpty() } else null
        )
    }

    /**
     * 存档读坏/被清空时把原始 JSON 另存一份。
     *
     * 目的是让"数据没了"变成"数据在 backup 键里还能捞" —— 这类覆盖式存储
     * （一个 key 存整张表）一旦写错就是不可逆的，留一份原文成本几乎为零。
     */
    private fun stashRawBackup(raw: String) {
        if (raw.isEmpty()) return
        prefs.edit()
            .putString("$keyInstances.backup", raw)
            .putLong("$keyInstances.backupAt", System.currentTimeMillis())
            .apply()
    }

    private fun persistInstances(list: List<FixtureInstance>) {
        // ⚠ 用空表覆盖非空存档之前先留底：正常"清空全部"也会留，但真出问题
        //   （解析失败导致的空表）这就是唯一的救命绳。
        if (list.isEmpty()) prefs.getString(keyInstances, null)?.let { stashRawBackup(it) }
        val arr = JSONArray()
        list.forEach { i ->
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("fixtureId", i.fixtureId)
                put("name", i.name)
                put("startAddr", i.addr)        // 本宇宙内地址 1..512
                put("universe", i.universe)     // 1=A, 2=B
                put("slot", i.slot)
                put("groupId", i.groupId ?: JSONObject.NULL)   // null = 未分组
            })
        }
        prefs.edit().putString(keyInstances, arr.toString()).apply()
    }

    /**
     * 分配一个板载**程序槽**（0..7）。
     *
     * 槽位是给"上传到板子运行的独立程序"用的，**不限制实例数量**：
     * 槽位用满时返回 0（多个实例共用一个槽，后上传的覆盖先前的）。
     * 这样实例可以照常建几百台，只是同时能跑的独立程序仍受固件 8 槽限制。
     */
    private fun nextFreeSlot(list: List<FixtureInstance>): Int {
        val used = list.map { it.slot }.toSet()
        for (s in 0 until PROG_SLOT_COUNT) if (s !in used) return s
        return 0
    }

    /** 添加单台（兼容旧调用）。 */
    fun addInstance(fixtureId: String, name: String, addr: Int, universe: Int = 1): String? =
        addInstances(fixtureId, name, addr, 1, universe)

    /**
     * 批量添加实例：在**同一个宇宙（A 或 B）内**按灯型通道数等距铺开。
     *
     * 第 k 台的地址 = `起始地址 + k × 灯型通道数`，全部落在本宇宙 1..512 内。
     * 因为一台灯的通道不可能跨宇宙（现场每个宇宙是一条独立的 DMX 线，
     * 一台灯只有一组 DMX 输入口），放不下就直接报错，不做自动挪动。
     *
     * @return null 表示成功；否则是错误提示（越界 / 重叠）。
     *         先整体校验再落盘，避免"建了一半失败"。
     */
    fun addInstances(fixtureId: String, namePrefix: String,
                     startAddr: Int, count: Int, universe: Int = 1): String? {
        val uni = universe.coerceIn(1, DmxProtocol.UNIVERSES)
        val band = DmxProtocol.bandLabel(uni)
        val def = fixtures.find { it.id == fixtureId } ?: return "灯型不存在"
        // 单次最多建 64 台（受本宇宙地址空间自然限制，20ch 灯上限 25 台）
        val n = count.coerceIn(1, MAX_BATCH_ADD)
        val pitch = def.channelCount.coerceAtLeast(1)
        if (pitch > DmxProtocol.UNIVERSE_SIZE) {
            return "该灯型有 $pitch 个通道，超过单个宇宙的 ${DmxProtocol.UNIVERSE_SIZE} 通道，无法 patch"
        }
        val addr0 = startAddr.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)

        // 先整体校验（都在本宇宙内 + 不与已有实例重叠），再落盘
        val list = instances().toMutableList()
        val occupied = list.filter { it.universe == uni }.mapNotNull { it ->
            val d = fixtures.find { f -> f.id == it.fixtureId } ?: return@mapNotNull null
            it.addr to (it.addr + d.channelCount - 1)
        }
        for (k in 0 until n) {
            val b1 = addr0 + k * pitch
            val b2 = b1 + pitch - 1
            if (b2 > DmxProtocol.UNIVERSE_SIZE) {
                return "$band 通道地址不足：第 ${k + 1} 台需要 $b1~$b2，" +
                       "本宇宙上限 ${DmxProtocol.UNIVERSE_SIZE}（可减少数量或把起始地址提前）"
            }
            for ((a1, a2) in occupied) {
                if (b1 <= a2 && a1 <= b2) return "$band 通道 $b1~$b2 与已有实例（$a1~$a2）重叠"
            }
        }

        for (k in 0 until n) {
            list.add(FixtureInstance(newInstanceId(list), fixtureId,
                                     "$namePrefix-${k + 1}", addr0 + k * pitch,
                                     nextFreeSlot(list), uni))
        }
        persistInstances(list)
        return null
    }

    /**
     * 原样放回一批实例（**回滚用**）。
     *
     * 用途：RDM 建实例时"覆盖已占用地址"是**先删旧再建新**，万一新实例建不起来
     * （地址越界/重叠），用户就白丢一台灯且无法撤销。这里把删掉的旧实例按原
     * id/名字/地址放回去。故意不做重叠校验 —— 它们本来就是占着这些地址的。
     */
    fun restoreInstances(insts: Collection<FixtureInstance>) {
        if (insts.isEmpty()) return
        val have = instances().map { it.id }.toSet()
        persistInstances(instances() + insts.filterNot { it.id in have })
    }

    /** 删除多个实例（批量删除）。 */
    fun removeInstances(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        persistInstances(instances().filterNot { it.id in set })
    }

    /** 清空全部实例。 */
    fun removeAllInstances() = persistInstances(emptyList())

    // 注：原先这里有一个 planAddInstances()（算预览地址），已删除 ——
    // 该算法统一到 InstanceForm（纯逻辑 + 单测），避免预览与落盘两处算术漂移。
    // 落盘仍走下面的 addInstances()，它自己会做一次完整的边界与重叠校验。

    fun updateInstance(inst: FixtureInstance) {
        val def = fixtures.find { it.id == inst.fixtureId } ?: return
        val pitch = def.channelCount.coerceAtLeast(1)
        val uni = inst.universe.coerceIn(1, DmxProtocol.UNIVERSES)
        val addr = inst.addr.coerceIn(1, DmxProtocol.UNIVERSE_SIZE)
        if (addr + pitch - 1 > DmxProtocol.UNIVERSE_SIZE) return   // 越界，忽略
        val fixed = inst.copy(addr = addr, universe = uni)
        val list = instances().toMutableList()
        // 只与**同一个宇宙内**的实例比对重叠（A 和 B 是两条独立的线，可以重复）
        for (it in list) {
            if (it.id == fixed.id || it.universe != uni) continue
            val itDef = fixtures.find { f -> f.id == it.fixtureId } ?: continue
            val a1 = it.addr; val a2 = a1 + itDef.channelCount - 1
            if (addr <= a2 && a1 <= addr + pitch - 1) return  // 重叠，拒绝更新
        }
        persistInstances(list.map { if (it.id == fixed.id) fixed else it })
    }

    fun deleteInstance(id: String) {
        persistInstances(instances().filter { it.id != id })
    }

    /** 按实例 ID 取灯型定义（找不到返 null）。 */
    fun fixtureOf(inst: FixtureInstance): FixtureDef? =
        fixtures.find { it.id == inst.fixtureId }

    // ---------- 灯具分组（编组）----------
    //
    // 归属唯一：一台灯至多属于一个分组（理由见 FixtureInstance.groupId 的注释）。
    // 分组只影响"选择"，不影响 DMX 输出，所以是纯 App 功能、不动固件协议。

    private val keyGroups = "groups"

    /** 全部分组，按创建顺序。 */
    fun groups(): List<FixtureGroup> {
        val raw = prefs.getString(keyGroups, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FixtureGroup(o.getString("id"), o.getString("name"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun persistGroups(list: List<FixtureGroup>) {
        val arr = JSONArray()
        list.forEach { g -> arr.put(JSONObject().apply { put("id", g.id); put("name", g.name) }) }
        prefs.edit().putString(keyGroups, arr.toString()).apply()
    }

    /** 新建分组，返回新组 id。名字留空时自动叫"分组 N"。 */
    fun addGroup(name: String): String {
        val list = groups().toMutableList()
        val id = "grp_${System.currentTimeMillis()}_${list.size}"
        list.add(FixtureGroup(id, name.trim().ifEmpty { "分组 ${list.size + 1}" }))
        persistGroups(list)
        return id
    }

    fun renameGroup(id: String, name: String) {
        val nm = name.trim()
        if (nm.isEmpty()) return
        persistGroups(groups().map { if (it.id == id) it.copy(name = nm) else it })
    }

    /**
     * 删除分组。**只删组、不删灯** —— 组内灯具回到"未分组"。
     *
     * 归属靠 [FixtureInstance.groupId]，指向一个已不存在的组本就等于未分组；
     * 但这里仍显式清空一下，免得存档里留下悬空 id，以后排查时让人误以为
     * 那些灯还在某个组里。
     */
    fun removeGroup(id: String) {
        persistGroups(groups().filterNot { it.id == id })
        persistInstances(instances().map { if (it.groupId == id) it.copy(groupId = null) else it })
    }

    /** 把一批实例移到指定分组；[groupId] 传 null = 移到"未分组"。 */
    fun setInstancesGroup(ids: Collection<String>, groupId: String?) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        persistInstances(instances().map { if (it.id in set) it.copy(groupId = groupId) else it })
    }

    /** 某分组下的实例；[groupId] 传 null = 取"未分组"的那些。 */
    fun instancesOfGroup(groupId: String?): List<FixtureInstance> =
        instances().filter { it.groupId == groupId }

    /**
     * 把一批**刚建好的**实例自动归到一个「按名字复用」的分组里。
     *
     * 同名组已存在就复用，否则新建 —— 复用而不是每次新建，是为了避免
     * 反复加实例时堆出一串同名组（例如连加三批 EOS 就出现三个"EOS"组）。
     * 用户确实想分开时，在「已配接」页对组改名即可。
     *
     * 两条加实例路径都要走这里，否则新建的灯会在已配接页散落在「未分组」里，
     * 还得手动归组：
     *   · 灯库页的「加实例」    → 组名 = 灯型名（def.name）
     *   · RDM 页的「加实例」    → 组名 = 型号名（RdmDevice.modelDesc）
     *
     * @return 实际使用的分组 id；[ids] 为空时返回 null。
     */
    fun autoGroupInstances(ids: Collection<String>, groupName: String): String? {
        if (ids.isEmpty()) return null
        val title = groupName.trim().ifEmpty { "分组" }
        val gid = groups().firstOrNull { it.name == title }?.id ?: addGroup(title)
        setInstancesGroup(ids, gid)
        return gid
    }

    // ---- 灯型列表缓存 ----
    @Volatile private var fixtureCache: List<FixtureDef>? = null
    @Volatile private var fixtureCacheKey: Long = 0

    /**
     * 所有灯型。**带缓存**。
     *
     * 为什么需要：以前每次访问都是 `listFiles()` + 逐个 `readText()` + JSON 解析，
     * 而访问点有 10 处 —— 其中 `createInstancesFromRdm` → `matchRdmFixture` 是
     * **按设备**调的，一次"加实例"就是 O(台数 × 灯库文件数 × 解析)，而且全在 UI 线程。
     * 灯库有几百个灯型时进页面、加实例都会明显卡。
     *
     * 缓存键是"文件名 + 大小 + 修改时间"的指纹。`listFiles()` 本身几乎不花钱，
     * 贵的 `readText`/JSON 解析只在文件真的变了时才做。这样**不需要在每个写入点
     * 手动失效** —— 漏掉一处就会读到旧表，比多几次 stat 危险得多。
     */
    val fixtures: List<FixtureDef>
        get() {
            // 排序是为了让指纹稳定（listFiles 的顺序不保证）
            val files = dir.listFiles()
                ?.filter { it.extension == "json" }
                ?.sortedBy { it.name }
                ?: emptyList()
            var key = files.size.toLong()
            for (f in files) {
                key = key * 31 + f.name.hashCode()
                key = key * 31 + f.length()
                key = key * 31 + f.lastModified()
            }
            fixtureCache?.let { if (key == fixtureCacheKey) return it }
            val list = mutableListOf<FixtureDef>()
            for (f in files) {
                try { list.add(parseFixtureJson(f.readText())) } catch (_: Exception) {}
            }
            val sorted = list.sortedBy { it.name }
            fixtureCache = sorted
            fixtureCacheKey = key
            return sorted
        }

    /**
     * 用 RDM 扫到的信息新建一个灯型（**骨架灯型**）。
     *
     * ⚠ RDM 只能告诉我们"型号名 + 占用通道数"，拿不到每个通道的用途
     *   （那要读 SLOT_INFO，很多灯根本不填）。所以这里生成的通道名是 CH1..CHn、
     *   只有第 1 通道标了 DIM —— 通道数和型号是对的，**通道含义仍需要用户补充**，
     *   或者之后导入同型号的灯库文件覆盖它。
     *
     * 这样做的价值：现场扫到一台灯库里没有的灯时，能立刻建出实例并推到推子上，
     * 不用先去找厂家灯库文件。
     *
     * @return 新建的灯型
     */
    fun createFromRdm(
        name: String,
        manufacturer: String,
        channelCount: Int,
        mode: String = ""
    ): FixtureDef {
        val safeName = name.ifBlank { "RDM-${channelCount}CH" }.trim()
        val safeManu = manufacturer.ifBlank { "RDM" }.trim()
        val safeMode = mode.ifBlank { "${channelCount}CH" }
        // id 里只留小写字母/数字/下划线，避免和导入的灯库 id 撞车时产生非法文件名
        val slug = safeName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        var id = "rdm_${slug}_${channelCount}ch"
        // 同名同通道数的灯型已存在 → 加后缀，不覆盖用户已有的
        var n = 2
        while (File(dir, "$id.json").exists()) id = "rdm_${slug}_${channelCount}ch_${n++}"

        val channels = (1..channelCount).map { k ->
            FixtureChannel(
                number = k,
                name = "CH$k",
                originalName = "CH$k",
                attribute = if (k == 1) "DIM" else "",
                defaultValue = 0,
                highlightValue = 255
            )
        }
        val def = FixtureDef(
            id = id,
            name = safeName,
            manufacturer = safeManu,
            mode = safeMode,
            channelCount = channelCount,
            channels = channels
        )
        File(dir, "$id.json").writeText(fixtureToJson(def).toString(2))
        return def
    }

    /** 指定原始格式（"xml"/"d4"/"r20"）的灯库列表（按是否保存了该格式原始文件过滤）。 */
    fun fixturesOfFormat(ext: String): List<FixtureDef> =
        fixtures.filter { File(dir, "${it.id}.$ext").exists() }

    var currentFixtureId: String?
        get() = prefs.getString("current_fixture", null)
        set(value) = prefs.edit().putString("current_fixture", value).apply()

    val currentFixture: FixtureDef?
        get() {
            val id = currentFixtureId ?: return null
            return fixtures.find { it.id == id }
        }

    /**
     * 导入灯库文件（自动识别 ZIP 或单文件 XML/D4/R20）。同时保存原始文件用于导出/上传。
     *
     * ⚠ 这是「直接导」，不做预检。需要先让用户看清压缩包里每个文件的结果时，
     *   用 [inspect] 拿到 [ZipScan.Report]，确认后再调 [importScanned]。
     */
    fun importFile(input: java.io.InputStream, fileName: String = ""): List<FixtureDef> {
        val raw = input.readBytes()
        // 检测文件类型：ZIP 以 PK 开头
        val isZip = raw.size >= 2 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()
        if (isZip) {
            return importFromZip(raw)
        }
        val ln = fileName.lowercase()
        return when {
            ln.endsWith(".d4") -> importFromD4(raw, fileName)
            ln.endsWith(".r20") -> importFromR20(raw, fileName)
            else -> importFromXml(raw, fileName)
        }
    }

    /**
     * 导入前体检：枚举压缩包内**每一个**条目并判定，不做任何写盘。
     *
     * 单文件（非 zip）也会返回一份单条目报告，界面可以统一处理。
     */
    fun inspect(input: java.io.InputStream, fileName: String = ""): ZipScan.Report {
        val raw = input.readBytes()
        val isZip = raw.size >= 2 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()
        if (isZip) return ZipScan.scanZip(raw, fileName)

        // 单文件：沿用与 importFile 相同的兜底判定
        val entry = ZipScan.classify(fileName.ifEmpty { "fixture" }, isDirectory = false, bytes = raw)
        return ZipScan.Report(fileName, listOf(entry))
    }

    /**
     * 按体检报告执行导入（用户确认后调用）。
     *
     * 只处理报告里标记为可导入的条目，并**按内容**分派 —— 不再看扩展名，
     * 这样"后缀写错但内容正确"的文件也能进得来。
     *
     * @param data 压缩包的原始字节（与 [scanZip] 用的是同一份）
     * @return 成功导入的灯具；解析失败的条目会出现在 [ImportResult.failed] 里
     */
    fun importScanned(data: ByteArray, report: ZipScan.Report): ImportResult {
        val isZip = data.size >= 2 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()
        if (!isZip) {
            // 单文件路径
            val name = report.entries.firstOrNull()?.path ?: ""
            val defs = try {
                val ln = name.lowercase()
                when {
                    ln.endsWith(".d4") -> importFromD4(data, name)
                    ln.endsWith(".r20") -> importFromR20(data, name)
                    else -> importFromXml(data, name)
                }
            } catch (e: Exception) {
                return ImportResult(emptyList(), listOf(name to e.describe()))
            }
            return ImportResult(defs, if (defs.isEmpty()) listOf(name to "解析不出任何灯型") else emptyList())
        }

        val result = mutableListOf<FixtureDef>()
        val failed = mutableListOf<Pair<String, String>>()
        // 只挑可导入的条目名，避免把整包重新解析一遍
        val wanted = report.importableFiles.map { it.path }.toHashSet()
        if (wanted.isEmpty()) return ImportResult(emptyList(), emptyList())

        val entries = try {
            ZipReader.read(data)
        } catch (e: Exception) {
            return ImportResult(emptyList(), listOf((report.sourceName.ifEmpty { "压缩包" }) to e.describe()))
        }
        for (entry in entries) {
            if (entry.isDirectory || entry.name !in wanted) continue
            try {
                // 按内容分派（detectAndParse 在体检阶段已确认过格式）
                val (kind, _) = ZipScan.detectAndParse(entry.data)
                when (kind) {
                    ZipScan.KIND_XML -> result.addAll(importFromXml(entry.data, entry.name))
                    ZipScan.KIND_D4 -> result.addAll(importFromD4(entry.data, entry.name))
                    ZipScan.KIND_R20 -> result.addAll(importFromR20(entry.data, entry.name))
                    else -> failed.add(entry.name to "内容格式已无法识别")
                }
            } catch (e: Exception) {
                failed.add(entry.name to e.describe())
            }
        }
        return ImportResult(result, failed)
    }

    /** 导入结果：成功的灯具 + 逐条失败原因（失败不再是"悄悄没了"）。 */
    data class ImportResult(
        val fixtures: List<FixtureDef>,
        val failed: List<Pair<String, String>>   // 文件路径 → 原因
    )

    private fun Exception.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun importFromZip(data: ByteArray): List<FixtureDef> {
        val result = mutableListOf<FixtureDef>()
        // ⚠ 用 ZipReader 而不是 ZipInputStream：后者对 GBK 文件名的包会抛异常。
        val entries = try {
            ZipReader.read(data)
        } catch (e: Exception) {
            return emptyList()
        }
        for (entry in entries) {
            if (entry.isDirectory) continue
            // v7：按**内容**分派，不再只看扩展名 —— 后缀写错但内容正确的
            // 文件以前会被静默丢掉（表现为"压缩包里明明有灯库却导入 0 个"）。
            val (kind, _) = ZipScan.detectAndParse(entry.data)
            when (kind) {
                ZipScan.KIND_XML -> result.addAll(importFromXml(entry.data, entry.name))
                ZipScan.KIND_D4 -> result.addAll(importFromD4(entry.data, entry.name))
                ZipScan.KIND_R20 -> result.addAll(importFromR20(entry.data, entry.name))
            }
        }
        return result
    }

    private fun importFromXml(raw: ByteArray, fileName: String): List<FixtureDef> {
        val xml = stripBom(raw)
        val defs = FixtureParser.parseMa2XmlAll(xml)
        if (defs.isEmpty()) return emptyList()
        // 原始文件按“首个灯型”的 id 保存一份（导出/上传时按灯型 id 查回）
        saveRaw(defs.first(), ".xml", raw)
        // 同一文件里的其它灯型（多模式）只写 json，原始文件共用同一份
        for (d in defs.drop(1)) File(dir, "${d.id}.json").writeText(fixtureToJson(d).toString(2))
        return defs
    }

    private fun importFromD4(raw: ByteArray, fileName: String): List<FixtureDef> {
        val txt = stripBom(raw)
        val defs = FixtureParser.parseD4All(txt)
        if (defs.isEmpty()) return emptyList()
        saveRaw(defs.first(), ".d4", raw)
        for (d in defs.drop(1)) File(dir, "${d.id}.json").writeText(fixtureToJson(d).toString(2))
        return defs
    }

    private fun importFromR20(raw: ByteArray, fileName: String): List<FixtureDef> {
        val txt = stripBom(raw)
        val def = FixtureParser.parseR20(txt) ?: return emptyList()
        saveRaw(def, ".r20", raw)
        return listOf(def)
    }

    private fun stripBom(raw: ByteArray): String {
        return if (raw.size >= 3 && raw[0] == 0xEF.toByte()
            && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte())
            raw.copyOfRange(3, raw.size).toString(Charsets.UTF_8)
        else raw.toString(Charsets.UTF_8)
    }

    /** 该灯具保存的原始文件（.xml / .d4 / .r20，存在哪些返回哪些）。 */
    fun rawFiles(def: FixtureDef): List<File> =
        listOf("xml", "d4", "r20").mapNotNull { ext ->
            val f = File(dir, "${def.id}.$ext")
            if (f.exists()) f else null
        }

    /** 导出所有灯具为 ZIP（原格式 XML/D4/R20）。 */
    fun exportZip(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            dir.listFiles()?.filter { it.extension in listOf("xml", "d4", "r20") }?.forEach { f ->
                val jsonFile = File(dir, "${f.nameWithoutExtension}.json")
                if (jsonFile.exists()) {
                    zip.putNextEntry(ZipEntry(f.name))
                    zip.write(f.readBytes())
                    zip.closeEntry()
                }
            }
        }
    }

    /** 删除灯具（json + xml/d4/r20）。 */
    fun delete(id: String): Boolean {
        if (currentFixtureId == id) currentFixtureId = null
        // 同时删除该灯型的所有实例
        val insts = instances().filter { it.fixtureId != id }
        if (insts.size != instances().size) persistInstances(insts)
        var ok = false
        for (ext in listOf("json", "xml", "d4", "r20")) {
            if (File(dir, "$id.$ext").delete()) ok = true
        }
        return ok
    }

    /**
     * 检查是否需要因 App 升级而重新导入灯库。
     * 当 versionCode 变化时，用已保存的原始文件（xml/d4/r20）重新生成 JSON，
     * 确保 FixtureParser 的改进自动生效（尤其是历史版本漏读通道的 D4/R20 灯库）。
     * @return 重新导入的数量，-1 表示首次运行无需操作
     */
    fun checkAndReimport(context: Context): Int {
        val curVer = try {
            val pi: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionCode
        } catch (_: Exception) { 0 }
        val savedVer = prefs.getInt("last_version", 0)
        // 首次运行：直接记版本号，不做重导
        if (savedVer == 0) {
            prefs.edit().putInt("last_version", curVer).apply()
            return -1
        }
        if (savedVer >= curVer) return 0

        var count = 0
        // 用原始文件重新解析。若解析出的灯型 id 与原始文件名不再一致（解析规则修正后常见），
        // 说明旧 json 是错误解析的残留 → 删掉，避免灯库里出现重复/错位的灯型。
        fun reparse(ext: String, parse: (String) -> List<FixtureDef>) {
            dir.listFiles()?.filter { it.extension == ext }?.forEach { f ->
                try {
                    val defs = parse(stripBom(f.readBytes()))
                    if (defs.isEmpty()) return@forEach
                    defs.forEach { def ->
                        File(dir, "${def.id}.json").writeText(fixtureToJson(def).toString(2))
                        count++
                    }
                    val ids = defs.map { it.id }.toSet()
                    if (!ids.contains(f.nameWithoutExtension)) {
                        File(dir, "${f.nameWithoutExtension}.json").delete()
                    }
                } catch (_: Exception) {}
            }
        }
        reparse("xml") { FixtureParser.parseMa2XmlAll(it) }
        reparse("d4") { FixtureParser.parseD4All(it) }
        reparse("r20") { listOfNotNull(FixtureParser.parseR20(it)) }
        prefs.edit().putInt("last_version", curVer).apply()
        return count
    }

    private fun saveFixture(def: FixtureDef, rawXml: ByteArray) = saveRaw(def, ".xml", rawXml)

    private fun saveRaw(def: FixtureDef, ext: String, raw: ByteArray) {
        File(dir, "${def.id}.json").writeText(fixtureToJson(def).toString(2))
        File(dir, "${def.id}$ext").writeBytes(raw)
    }

    companion object {
        fun fixtureToJson(def: FixtureDef): JSONObject = JSONObject().apply {
            put("id", def.id)
            put("name", def.name)
            put("manufacturer", def.manufacturer)
            put("mode", def.mode)
            put("channelCount", def.channelCount)
            if (def.panRange > 0) put("panRange", def.panRange.toDouble())
            if (def.tiltRange > 0) put("tiltRange", def.tiltRange.toDouble())
            def.ptSpeedCh?.let { put("ptSpeedCh", it) }
            put("channels", JSONArray().apply {
                def.channels.forEach { ch ->
                    put(JSONObject().apply {
                        put("number", ch.number)
                        put("name", ch.name)
                        put("originalName", ch.originalName)
                        if (ch.attribute.isNotEmpty()) put("attribute", ch.attribute)
                        put("defaultValue", ch.defaultValue)
                        put("highlightValue", ch.highlightValue)
                        if (ch.hasFine) put("hasFine", true)
                        ch.fineNumber?.let { put("fineNumber", it) }
                        if (ch.physFrom != 0f || ch.physTo != 0f) {
                            put("physFrom", ch.physFrom.toDouble())
                            put("physTo", ch.physTo.toDouble())
                        }
                    })
                }
            })
        }

        fun parseFixtureJson(json: String): FixtureDef {
            val obj = JSONObject(json)
            val chArr = obj.getJSONArray("channels")
            val channels = (0 until chArr.length()).map { i ->
                val c = chArr.getJSONObject(i)
                FixtureChannel(
                    number = c.getInt("number"),
                    name = c.getString("name"),
                    originalName = c.optString("originalName", c.getString("name")),
                    attribute = c.optString("attribute", ""),
                    defaultValue = c.optInt("defaultValue", 0),
                    highlightValue = c.optInt("highlightValue", 255),
                    hasFine = c.optBoolean("hasFine", false),
                    fineNumber = if (c.has("fineNumber")) c.getInt("fineNumber") else null,
                    physFrom = c.optDouble("physFrom", 0.0).toFloat(),
                    physTo = c.optDouble("physTo", 0.0).toFloat()
                )
            }
            return FixtureDef(
                id = obj.getString("id"),
                name = obj.getString("name"),
                manufacturer = obj.optString("manufacturer", ""),
                mode = obj.optString("mode", ""),
                channelCount = obj.getInt("channelCount"),
                channels = channels,
                panRange = obj.optDouble("panRange", 0.0).toFloat(),
                tiltRange = obj.optDouble("tiltRange", 0.0).toFloat(),
                ptSpeedCh = if (obj.has("ptSpeedCh")) obj.getInt("ptSpeedCh") else null
            )
        }
    }
}
