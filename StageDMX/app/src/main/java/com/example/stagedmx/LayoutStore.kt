package com.example.stagedmx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推子页“排列方式”存储。
 *
 * 默认排列 = 灯库通道顺序（1..N）。用户可以在设置页把通道按自己的使用习惯重新排序，
 * 并把某套顺序保存为预设（按灯型 id 分组），之后一键切换/套用。
 *
 * 数据（SharedPreferences / JSON）：
 * {
 *   "<fixtureId>": {
 *     "active": "预设名" | null,           // null = 按通道顺序
 *     "presets": { "预设名": [3,1,0,2,...] } // 数组下标 = 显示位置，值 = 灯内通道下标(0-based)
 *   }
 * }
 */
class LayoutStore(ctx: Context) {

    data class Preset(val name: String, val order: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Preset && other.name == name && other.order.contentEquals(order)
        override fun hashCode(): Int = name.hashCode() * 31 + order.contentHashCode()
    }

    private val prefs = ctx.getSharedPreferences("fader_layout", Context.MODE_PRIVATE)

    private fun root(): JSONObject = try {
        JSONObject(prefs.getString("data", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun entry(fixtureId: String, create: Boolean): JSONObject? {
        val r = root()
        var e = r.optJSONObject(fixtureId)
        if (e == null && create) {
            e = JSONObject().apply {
                put("active", JSONObject.NULL)
                put("presets", JSONObject())
            }
            r.put(fixtureId, e)
        }
        return e
    }

    private fun storeRoot(r: JSONObject) {
        prefs.edit().putString("data", r.toString()).apply()
    }

    /** 该灯型的全部排列预设。 */
    fun presets(fixtureId: String): List<Preset> {
        val e = entry(fixtureId, false) ?: return emptyList()
        val p = e.optJSONObject("presets") ?: return emptyList()
        val out = mutableListOf<Preset>()
        p.keys().forEach { name ->
            val arr = p.optJSONArray(name) ?: return@forEach
            val order = IntArray(arr.length()) { arr.optInt(it, 0) }
            out.add(Preset(name, order))
        }
        return out.sortedBy { it.name }
    }

    /** 保存/覆盖一个排列预设。 */
    fun save(fixtureId: String, name: String, order: IntArray) {
        val r = root()
        val e = entry(fixtureId, true)!!
        val p = e.optJSONObject("presets") ?: JSONObject().also { e.put("presets", it) }
        p.put(name, JSONArray().apply { order.forEach { put(it) } })
        r.put(fixtureId, e)
        storeRoot(r)
    }

    fun delete(fixtureId: String, name: String) {
        val r = root()
        val e = r.optJSONObject(fixtureId) ?: return
        e.optJSONObject("presets")?.remove(name)
        if (optActive(fixtureId) == name) e.put("active", JSONObject.NULL)
        r.put(fixtureId, e)
        storeRoot(r)
    }

    /** 当前生效的预设名；null = 按通道顺序。 */
    fun active(fixtureId: String): String? = optActive(fixtureId)

    private fun optActive(fixtureId: String): String? {
        val e = entry(fixtureId, false) ?: return null
        if (e.isNull("active")) return null
        return e.optString("active", "").ifEmpty { null }
    }

    /** 切换排列方式：name=null 表示回到“按通道顺序”。 */
    fun setActive(fixtureId: String, name: String?) {
        val r = root()
        val e = entry(fixtureId, true)!!
        e.put("active", name ?: JSONObject.NULL)
        r.put(fixtureId, e)
        storeRoot(r)
    }

    /** 当前生效的顺序数组（null = 默认通道顺序）。 */
    fun orderFor(fixtureId: String?): IntArray? {
        val id = fixtureId ?: return null
        val name = optActive(id) ?: return null
        return presets(id).firstOrNull { it.name == name }?.order
    }
}
