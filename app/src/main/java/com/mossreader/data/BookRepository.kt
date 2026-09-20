package com.mossreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mossreader.text.Chapter
import com.mossreader.text.ChapterSplitter
import com.mossreader.text.EpubReader
import com.mossreader.text.TextCleaner
import com.mossreader.text.TextDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BookMeta(
    val id: String,
    val title: String,
    val chapters: List<Chapter>,
    val totalChars: Int,
    val addedAt: Long,
    val narratorVoice: String = "",
    val dialogueVoice: String = "",
)

class BookRepository(private val ctx: Context) {
    private val root = File(ctx.filesDir, "books").apply { mkdirs() }
    private val prog = ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val textCache = HashMap<String, String>()

    fun list(): List<BookMeta> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory }
        .mapNotNull { runCatching { readMeta(File(it, "meta.json")) }.getOrNull() }
        .sortedByDescending { it.addedAt }

    fun get(id: String): BookMeta? = runCatching { readMeta(File(File(root, id), "meta.json")) }.getOrNull()

    fun text(id: String): String = textCache.getOrPut(id) { File(File(root, id), "text.txt").readText() }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        textCache.remove(id)
        prog.edit().remove(id).apply()
    }

    fun saveVoices(id: String, narrator: String, dialogue: String) {
        val m = get(id) ?: return
        writeMeta(File(File(root, id), "meta.json"), m.copy(narratorVoice = narrator, dialogueVoice = dialogue))
    }

    fun saveProgress(id: String, chapter: Int, offset: Int) { prog.edit().putString(id, "$chapter:$offset").apply() }

    fun progress(id: String): Pair<Int, Int> {
        val s = prog.getString(id, null) ?: return 0 to 0
        val p = s.split(':')
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** 导入 TXT / EPUB：识别编码 → 整理换行 → 自动分章 → 存成 UTF-8。 */
    fun import(uri: Uri): BookMeta {
        val name = displayName(uri)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取文件")
        val base = name.substringBeforeLast('.').ifBlank { "未命名" }
        val isEpub = name.lowercase().endsWith(".epub") || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes.decodeToString(0, minOf(bytes.size, 80)).contains("mimetype"))
        var title = base
        val text: String
        val chapters: List<Chapter>
        if (isEpub) {
            val epub = EpubReader.read(bytes.inputStream())
            if (epub.title.isNotBlank()) title = epub.title
            require(epub.chapters.isNotEmpty()) { "EPUB 里没有找到正文" }
            val sb = StringBuilder()
            val list = ArrayList<Chapter>()
            for ((t, body) in epub.chapters) {
                val start = sb.length
                val heading = if (body.lineSequence().firstOrNull()?.trim()?.take(40) == t.trim()) "" else t + "\n"
                sb.append(heading).append(body.trim()).append("\n\n")
                list.add(Chapter(t, start, sb.length))
            }
            val joined = sb.toString()
            if (list.size < 3 && joined.length > 20000) {
                text = TextCleaner.normalize(joined)
                chapters = ChapterSplitter.split(text, title)
            } else { text = joined; chapters = list }
        } else {
            text = TextCleaner.normalize(TextDecoder.decode(bytes))
            require(text.isNotBlank()) { "文件是空的" }
            chapters = ChapterSplitter.split(text, title)
        }
        val id = UUID.randomUUID().toString().take(12)
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "text.txt").writeText(text)
        val meta = BookMeta(id, title, chapters, text.length, System.currentTimeMillis())
        writeMeta(File(dir, "meta.json"), meta)
        return meta
    }

    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "book"
        }
        return uri.lastPathSegment ?: "book"
    }

    private fun readMeta(f: File): BookMeta {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("chapters")
        val chapters = List(arr.length()) { i -> val c = arr.getJSONObject(i); Chapter(c.getString("t"), c.getInt("s"), c.getInt("e")) }
        return BookMeta(o.getString("id"), o.getString("title"), chapters, o.getInt("total"), o.getLong("added"), o.optString("nv"), o.optString("dv"))
    }

    private fun writeMeta(f: File, m: BookMeta) {
        val o = JSONObject()
        o.put("id", m.id); o.put("title", m.title); o.put("total", m.totalChars); o.put("added", m.addedAt)
        o.put("nv", m.narratorVoice); o.put("dv", m.dialogueVoice)
        val arr = JSONArray()
        for (c in m.chapters) arr.put(JSONObject().put("t", c.title).put("s", c.start).put("e", c.end))
        o.put("chapters", arr)
        f.writeText(o.toString())
    }
}
