package com.mossreader.tts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 用户克隆出来的音色，保存成 JSON（参考音频编码后的 codes，很小）。 */
class VoiceStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "voices").apply { mkdirs() }

    fun list(): List<VoiceEntry> = (dir.listFiles() ?: emptyArray())
        .filter { it.extension == "json" }
        .mapNotNull { runCatching { read(it) }.getOrNull() }
        .sortedBy { it.name }

    private fun read(f: File): VoiceEntry {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("codes")
        val codes = Array(arr.length()) { i -> val r = arr.getJSONArray(i); IntArray(r.length()) { r.getInt(it) } }
        return VoiceEntry(o.getString("id"), o.getString("name"), "我的克隆音色", false, codes)
    }

    fun save(name: String, codes: Array<IntArray>): VoiceEntry {
        val id = "custom:" + UUID.randomUUID().toString().take(8)
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("created", System.currentTimeMillis())
        val arr = JSONArray()
        for (row in codes) { val r = JSONArray(); for (v in row) r.put(v); arr.put(r) }
        o.put("codes", arr)
        File(dir, id.removePrefix("custom:") + ".json").writeText(o.toString())
        return VoiceEntry(id, name, "我的克隆音色", false, codes)
    }

    fun delete(id: String) { File(dir, id.removePrefix("custom:") + ".json").delete() }
}
