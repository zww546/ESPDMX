package com.example.stagedmx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置效果预设存储。
 *
 * 一个预设 = 当前实例上**全部**内置效果的数值（幅度/速度）与开关状态。
 * 在效果页可以一键保存、一键应用（恢复开关 + 数值），默认名字取当前实例名。
 */
class FxPresetStore(ctx: Context) {

    data class Preset(
        val name: String,
        val instanceId: String?,               // null = 全局（未选实例）
        val params: List<FxEngine.FxParam>
    )

    private val prefs = ctx.getSharedPreferences("fx_presets", Context.MODE_PRIVATE)
    private val key = "presets"

    private fun read(): MutableList<Preset> {
        val out = mutableListOf<Preset>()
        try {
            val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pa = o.optJSONArray("p") ?: JSONArray()
                val params = (0 until pa.length()).map { j ->
                    val q = pa.getJSONObject(j)
                    FxEngine.FxParam(
                        fxId = q.optInt("id", 0),
                        amplitude = q.optInt("a", 0),
                        speed = q.optInt("s", 0),
                        on = q.optBoolean("on", false),
                        spread = q.optInt("sp", 0),
                        shape = q.optInt("sh", 0),
                        direction = q.optInt("di", 0),
                        phase = q.optInt("ph", 0),
                        envelope = q.optInt("en", 0))
                }
                out.add(Preset(
                    name = o.optString("n", ""),
                    instanceId = if (o.isNull("i")) null else o.optString("i", null),
                    params = params))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun write(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("n", p.name)
                put("i", p.instanceId ?: JSONObject.NULL)
                put("p", JSONArray().apply {
                    p.params.forEach { q ->
                        put(JSONObject().apply {
                            put("id", q.fxId); put("a", q.amplitude)
                            put("s", q.speed); put("on", q.on)
                            put("sp", q.spread); put("sh", q.shape)
                            put("di", q.direction); put("ph", q.phase)
                            put("en", q.envelope)
                        })
                    }
                })
            })
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    /** 某实例（或全局 null）可见的预设。 */
    fun forInstance(instanceId: String?): List<Preset> =
        read().filter { it.instanceId == instanceId }.sortedBy { it.name }

    fun names(instanceId: String?): List<String> = forInstance(instanceId).map { it.name }

    fun get(instanceId: String?, name: String): Preset? =
        forInstance(instanceId).firstOrNull { it.name == name }

    /** 保存（同实例同名覆盖）。 */
    fun save(preset: Preset) {
        val list = read().filterNot { it.instanceId == preset.instanceId && it.name == preset.name }.toMutableList()
        list.add(preset)
        write(list)
    }

    fun delete(instanceId: String?, name: String) {
        write(read().filterNot { it.instanceId == instanceId && it.name == name })
    }
}
