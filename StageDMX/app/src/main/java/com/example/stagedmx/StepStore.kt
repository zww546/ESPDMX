package com.example.stagedmx

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 步序程序库：每个程序归属一个实例（instanceId），实例间程序互相独立。
 * 每步 = 一份 512 通道快照 + 本步保持时间(ms)。播放时按顺序循环，只下发该实例地址段。
 * 持久化到 SharedPreferences（JSON + Base64）。
 */
class StepStore(ctx: Context) {

    data class Step(val timeMs: Int, val values: IntArray)
    data class Program(
        val name: String,
        val steps: MutableList<Step>,
        val instanceId: String? = null   // null = 全局/裸通道；否则归属某实例
    )

    private val prefs = ctx.getSharedPreferences("stagedmx_programs", Context.MODE_PRIVATE)
    private val keyProgs = "programs"
    private val keyDefTime = "defTimeMs"

    /** 新建步默认时间(ms)。 */
    var defaultTimeMs: Int
        get() = prefs.getInt(keyDefTime, 1000)
        set(v) { prefs.edit().putInt(keyDefTime, v.coerceIn(50, 600000)).apply() }

    /** 当前选中的程序名（主屏"记录"按钮的目标）。 */
    var currentProgram: String?
        get() = prefs.getString("current", null)
        set(v) { prefs.edit().putString("current", v).apply() }

    // ---------- 读写 ----------
    fun programs(): MutableList<Program> {
        val raw = prefs.getString(keyProgs, null) ?: return mutableListOf()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            stashRawBackup(raw)
            return mutableListOf()
        }
        val out = mutableListOf<Program>()
        var bad = 0
        for (i in 0 until arr.length()) {
            // ⚠ **逐条隔离**：一条坏程序只丢它自己。
            //   以前整个循环包在一个 try 里，任意一条解析失败（最常见：某步的 Base64
            //   被截断）就让**整张表读成 0..i-1**，而所有写操作都是"读-改-写"
            //   （addProgram / deleteProgram / addStep / removeStep / clearSteps），
            //   于是随便新建一个程序，剩下的程序就**永久消失**了。
            try {
                out.add(parseProgram(arr.getJSONObject(i)))
            } catch (_: Exception) {
                bad++
            }
        }
        if (bad > 0) {
            stashRawBackup(raw)
            android.util.Log.w("StepStore",
                "跳过 $bad 条损坏的程序（原始存档已备份到 $keyProgs.backup）")
        }
        return out
    }

    /** 解析单条程序记录。 */
    private fun parseProgram(po: JSONObject): Program {
        val name = po.getString("n")
        val instanceId = if (po.has("i")) po.getString("i") else null
        val steps = mutableListOf<Step>()
        val sa = po.getJSONArray("s")
        for (j in 0 until sa.length()) {
            val so = sa.getJSONObject(j)
            val t = so.getInt("t")
            val bytes = Base64.decode(so.getString("d"), Base64.DEFAULT)
            val vals = IntArray(DmxProtocol.MAX_CHANNELS)
            val n = minOf(bytes.size, vals.size)
            for (k in 0 until n) vals[k] = bytes[k].toInt() and 0xFF
            steps.add(Step(t, vals))
        }
        return Program(name, steps, instanceId)
    }

    /**
     * 存档读坏/被清空时把原始 JSON 另存一份。
     *
     * 一个 key 存整张表的覆盖式存储，一旦写错就不可逆 —— 留一份原文成本几乎为零。
     */
    private fun stashRawBackup(raw: String) {
        if (raw.isEmpty()) return
        prefs.edit()
            .putString("$keyProgs.backup", raw)
            .putLong("$keyProgs.backupAt", System.currentTimeMillis())
            .apply()
    }

    private fun persist(list: List<Program>) {
        // 用空表覆盖非空存档前先留底（正常"删光程序"也留，真出问题时这是救命绳）
        if (list.isEmpty()) prefs.getString(keyProgs, null)?.let { stashRawBackup(it) }
        val arr = JSONArray()
        for (p in list) {
            val po = JSONObject()
            po.put("n", p.name)
            p.instanceId?.let { po.put("i", it) }
            val sa = JSONArray()
            for (s in p.steps) {
                val so = JSONObject()
                so.put("t", s.timeMs)
                val b = ByteArray(DmxProtocol.MAX_CHANNELS) {
                    (if (it < s.values.size) s.values[it].coerceIn(0, 255) else 0).toByte()
                }
                so.put("d", Base64.encodeToString(b, Base64.NO_WRAP))
                sa.put(so)
            }
            po.put("s", sa)
            arr.put(po)
        }
        prefs.edit().putString(keyProgs, arr.toString()).apply()
    }

    // ---------- 程序级（按实例） ----------
    /** 某实例（或全局 null）可见的程序。 */
    fun programsFor(instanceId: String?): List<Program> =
        programs().filter { it.instanceId == instanceId }

    fun programNames(instanceId: String?): List<String> =
        programsFor(instanceId).map { it.name }

    fun hasProgram(name: String, instanceId: String?): Boolean =
        programsFor(instanceId).any { it.name == name }

    /** 新建程序（重名则忽略），归属指定实例。 */
    fun addProgram(name: String, instanceId: String?) {
        val list = programs()
        if (list.any { it.name == name && it.instanceId == instanceId }) return
        list.add(Program(name, mutableListOf(), instanceId))
        persist(list)
    }

    fun deleteProgram(name: String, instanceId: String?) {
        val list = programs().filter { !(it.name == name && it.instanceId == instanceId) }
        persist(list)
    }

    fun steps(program: String, instanceId: String?): List<Step> =
        programsFor(instanceId).firstOrNull { it.name == program }?.steps ?: emptyList()

    fun stepCount(program: String, instanceId: String?): Int =
        steps(program, instanceId).size

    // ---------- 步级 ----------
    fun addStep(program: String, instanceId: String?, timeMs: Int, values: IntArray) {
        val list = programs()
        val p = list.firstOrNull { it.name == program && it.instanceId == instanceId } ?: return
        p.steps.add(Step(timeMs, values.copyOf()))
        persist(list)
    }

    fun removeStep(program: String, instanceId: String?, index: Int) {
        val list = programs()
        val p = list.firstOrNull { it.name == program && it.instanceId == instanceId } ?: return
        if (index in p.steps.indices) { p.steps.removeAt(index); persist(list) }
    }

    fun clearSteps(program: String, instanceId: String?) {
        val list = programs()
        val p = list.firstOrNull { it.name == program && it.instanceId == instanceId } ?: return
        p.steps.clear(); persist(list)
    }
}
