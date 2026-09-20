package com.mossreader.tts

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mossreader.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DownloadProgress(val file: String = "", val doneBytes: Long = 0, val totalBytes: Long = 0, val message: String = "")

class ModelStore(private val ctx: Context) {
    companion object {
        const val TTS_REPO = "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX"
        const val CODEC_REPO = "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX"
        const val TTS_DIR = "MOSS-TTS-Nano-100M-ONNX"
        const val CODEC_DIR = "MOSS-Audio-Tokenizer-Nano-ONNX"
        val TTS_FILES = listOf(
            "browser_poc_manifest.json", "tts_browser_onnx_meta.json", "tokenizer.model",
            "moss_tts_prefill.onnx", "moss_tts_decode_step.onnx", "moss_tts_local_fixed_sampled_frame.onnx",
            "moss_tts_global_shared.data", "moss_tts_local_shared.data",
        )
        val CODEC_FILES = listOf(
            "codec_browser_onnx_meta.json", "moss_audio_tokenizer_decode_step.onnx", "moss_audio_tokenizer_decode_shared.data",
        )
        val ENCODER_FILES = listOf("moss_audio_tokenizer_encode.onnx", "moss_audio_tokenizer_encode.data")
        val ALL_NAMES: Set<String> = (TTS_FILES + CODEC_FILES + ENCODER_FILES).toSet()
    }

    val root = File(ctx.filesDir, "models")
    val ttsDir = File(root, TTS_DIR)
    val codecDir = File(root, CODEC_DIR)

    fun isCoreReady(): Boolean =
        TTS_FILES.all { File(ttsDir, it).let { f -> f.exists() && f.length() > 0 } } &&
            CODEC_FILES.all { File(codecDir, it).let { f -> f.exists() && f.length() > 0 } }

    fun isEncoderReady(): Boolean = ENCODER_FILES.all { File(codecDir, it).let { f -> f.exists() && f.length() > 0 } }

    fun sizeOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() { root.deleteRecursively() }

    fun deleteEncoder() { ENCODER_FILES.forEach { File(codecDir, it).delete() } }

    private class Remote(val path: String, val size: Long)

    private fun http(url: String, range: Long = 0): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 40_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MossNovelReader/1.0")
        if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
        return c
    }

    private fun tree(base: String, repo: String, wanted: List<String>): Map<String, Remote> {
        val result = HashMap<String, Remote>()
        try {
            val c = http("$base/api/models/$repo/tree/main?recursive=1")
            if (c.responseCode == 200) {
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("type") == "directory") continue
                    val p = o.optString("path")
                    val name = p.substringAfterLast('/')
                    if (name in wanted) {
                        val old = result[name]
                        if (old == null || p.length < old.path.length) result[name] = Remote(p, o.optLong("size", 0))
                    }
                }
            }
            c.disconnect()
        } catch (e: Exception) {
            AppLog.e("读取文件列表失败：$repo", e)
        }
        for (w in wanted) if (!result.containsKey(w)) result[w] = Remote(w, 0)
        return result
    }

    suspend fun download(base: String, includeEncoder: Boolean, onProgress: (DownloadProgress) -> Unit) = withContext(Dispatchers.IO) {
        val b = base.trim().trimEnd('/')
        ttsDir.mkdirs(); codecDir.mkdirs()
        onProgress(DownloadProgress(message = "正在获取文件列表…"))
        val jobs = ArrayList<Triple<String, File, Remote>>()
        val ttsTree = tree(b, TTS_REPO, TTS_FILES)
        for (n in TTS_FILES) jobs.add(Triple(TTS_REPO, File(ttsDir, n), ttsTree.getValue(n)))
        val codecNames = CODEC_FILES + (if (includeEncoder) ENCODER_FILES else emptyList())
        val codecTree = tree(b, CODEC_REPO, codecNames)
        for (n in codecNames) jobs.add(Triple(CODEC_REPO, File(codecDir, n), codecTree.getValue(n)))

        val grand = jobs.sumOf { it.third.size }
        var done = 0L
        for ((repo, dest, remote) in jobs) {
            if (dest.exists() && remote.size > 0 && dest.length() == remote.size) { done += remote.size; continue }
            if (dest.exists() && remote.size == 0L && dest.length() > 0 && dest.name.endsWith(".json")) continue
            val part = File(dest.parentFile, dest.name + ".part")
            val encoded = remote.path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val url = "$b/$repo/resolve/main/$encoded"
            var attempt = 0
            while (true) {
                try {
                    val have = if (part.exists()) part.length() else 0L
                    val c = http(url, have)
                    val code = c.responseCode
                    if (code != 200 && code != 206) error("HTTP $code（${dest.name}）")
                    val append = code == 206 && have > 0
                    var fileDone = if (append) have else 0L
                    c.inputStream.use { input ->
                        FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var last = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                fileDone += n
                                val now = System.currentTimeMillis()
                                if (now - last > 300) {
                                    last = now
                                    onProgress(DownloadProgress(dest.name, done + fileDone, if (grand > 0) grand else 0, "正在下载 ${dest.name}"))
                                }
                            }
                        }
                    }
                    c.disconnect()
                    if (remote.size > 0 && part.length() != remote.size) error("${dest.name} 大小不符：${part.length()} / ${remote.size}")
                    dest.delete()
                    if (!part.renameTo(dest)) error("无法保存 ${dest.name}")
                    done += if (remote.size > 0) remote.size else dest.length()
                    break
                } catch (e: Exception) {
                    attempt++
                    AppLog.e("下载失败（第 $attempt 次）：${dest.name}", e)
                    if (attempt >= 4) throw e
                    kotlinx.coroutines.delay(1500L * attempt)
                }
            }
        }
        onProgress(DownloadProgress(message = "下载完成", doneBytes = grand, totalBytes = grand))
    }

    /** 从用户选择的文件夹里把需要的文件拷进来（适合已经在电脑上下好模型的情况）。 */
    suspend fun importFromTree(treeUri: Uri, onProgress: (DownloadProgress) -> Unit): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: error("无法读取所选文件夹")
        ttsDir.mkdirs(); codecDir.mkdirs()
        val found = ArrayList<DocumentFile>()
        fun walk(d: DocumentFile, depth: Int) {
            for (f in d.listFiles()) {
                if (f.isDirectory) { if (depth < 3) walk(f, depth + 1) }
                else if (f.name in ALL_NAMES) found.add(f)
            }
        }
        walk(root, 0)
        var copied = 0
        for ((i, f) in found.withIndex()) {
            val name = f.name ?: continue
            val dest = File(if (name in TTS_FILES) ttsDir else codecDir, name)
            onProgress(DownloadProgress(name, i.toLong(), found.size.toLong(), "正在导入 $name"))
            ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out, 256 * 1024) }
                copied++
            }
        }
        onProgress(DownloadProgress(message = "导入完成：$copied 个文件"))
        copied
    }
}
