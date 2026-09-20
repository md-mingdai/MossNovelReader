package com.mossreader.tts

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mossreader.audio.Resampler
import com.mossreader.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.random.Random

class VoiceEntry(val id: String, val name: String, val group: String, val builtin: Boolean, val codes: Array<IntArray>)

/**
 * MOSS-TTS-Nano (ONNX) 的 Kotlin 推理实现，流程对照官方 ort_cpu_runtime.py / 浏览器版 JS：
 *   prefill → 循环 { local_fixed_sampled_frame 采样一帧 → decode_step 推进 } → codec 流式解码 → PCM
 * 全部输入输出名从 manifest / meta JSON 里读取，不写死。
 */
class MossTtsEngine(
    private val ttsDir: File,
    private val codecDir: File,
    private val threads: Int,
    val tokenizer: SentencePiece,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var manifest: JSONObject
    private lateinit var ttsMeta: JSONObject
    private lateinit var codecMeta: JSONObject
    private lateinit var prefill: OrtSession
    private lateinit var decode: OrtSession
    private lateinit var localFrame: OrtSession
    private lateinit var codecStep: OrtSession
    private var codecEncode: OrtSession? = null

    var sampleRate = 48000; private set
    var channels = 2; private set
    var builtinVoices: List<VoiceEntry> = emptyList(); private set

    private var nVq = 16
    private var rowWidth = 17
    private var padId = 0
    private var audioStartId = 0
    private var audioEndId = 0
    private var assistantSlotId = 0
    private var userSlotId = 0
    private var cbSize = 1024
    private var maxNewFrames = 375
    private var codecQuantizers = 16
    private lateinit var prefixTemplate: IntArray
    private lateinit var afterRefTemplate: IntArray
    private lateinit var assistantTemplate: IntArray
    private lateinit var decodeInputNames: List<String>
    private lateinit var decodeOutputNames: List<String>
    private lateinit var prefillOutputNames: List<String>
    private val rng = Random(1234)

    // ------------------------------------------------------------------ 加载

    fun load(log: (String) -> Unit = { AppLog.d(it) }) {
        val t0 = System.nanoTime()
        manifest = JSONObject(File(ttsDir, "browser_poc_manifest.json").readText())
        val mf = manifest.getJSONObject("model_files")
        ttsMeta = JSONObject(File(ttsDir, File(mf.getString("tts_meta")).name).readText())
        codecMeta = JSONObject(File(codecDir, File(mf.getString("codec_meta")).name).readText())

        val cfg = manifest.getJSONObject("tts_config")
        nVq = cfg.getInt("n_vq")
        rowWidth = nVq + 1
        padId = cfg.getInt("audio_pad_token_id")
        audioStartId = cfg.getInt("audio_start_token_id")
        audioEndId = cfg.getInt("audio_end_token_id")
        assistantSlotId = cfg.getInt("audio_assistant_slot_token_id")
        userSlotId = cfg.getInt("audio_user_slot_token_id")
        val tpl = manifest.getJSONObject("prompt_templates")
        prefixTemplate = tpl.getJSONArray("user_prompt_prefix_token_ids").toIntArray()
        afterRefTemplate = tpl.getJSONArray("user_prompt_after_reference_token_ids").toIntArray()
        assistantTemplate = tpl.getJSONArray("assistant_prompt_prefix_token_ids").toIntArray()
        maxNewFrames = manifest.getJSONObject("generation_defaults").optInt("max_new_frames", 375)
        cbSize = ttsMeta.getJSONObject("model_config").getJSONArray("audio_codebook_sizes").getInt(0)
        val onnx = ttsMeta.getJSONObject("onnx")
        prefillOutputNames = onnx.getJSONArray("prefill_output_names").toStringList()
        decodeInputNames = onnx.getJSONArray("decode_input_names").toStringList()
        decodeOutputNames = onnx.getJSONArray("decode_output_names").toStringList()
        val cc = codecMeta.getJSONObject("codec_config")
        sampleRate = cc.getInt("sample_rate")
        channels = cc.getInt("channels")
        codecQuantizers = cc.getInt("num_quantizers")

        val tf = ttsMeta.getJSONObject("files")
        val cf = codecMeta.getJSONObject("files")
        require(tf.optString("local_fixed_sampled_frame").isNotEmpty()) { "模型缺少 local_fixed_sampled_frame.onnx" }
        prefill = newSession(File(ttsDir, File(tf.getString("prefill")).name)); log("已加载 prefill")
        decode = newSession(File(ttsDir, File(tf.getString("decode_step")).name)); log("已加载 decode_step")
        localFrame = newSession(File(ttsDir, File(tf.getString("local_fixed_sampled_frame")).name)); log("已加载 local_fixed_sampled_frame")
        codecStep = newSession(File(codecDir, File(cf.getString("decode_step")).name)); log("已加载 codec decode_step")

        val voices = ArrayList<VoiceEntry>()
        val arr = manifest.getJSONArray("builtin_voices")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("voice")
            voices.add(VoiceEntry(id, o.optString("display_name", id).ifEmpty { id }, o.optString("group", ""), true, o.getJSONArray("prompt_audio_codes").toCodes()))
        }
        builtinVoices = voices
        log("模型加载完成，用时 ${(System.nanoTime() - t0) / 1_000_000} ms，内置音色 ${voices.size} 个，采样率 $sampleRate，声道 $channels")
        selfCheckTokenizer(log)
    }

    private fun selfCheckTokenizer(log: (String) -> Unit) {
        try {
            val samples = manifest.optJSONArray("text_samples") ?: return
            var ok = 0
            for (i in 0 until samples.length()) {
                val s = samples.getJSONObject(i)
                val exp = s.optJSONArray("text_token_ids")?.toIntArray() ?: continue
                if (tokenizer.encode(s.getString("text")).contentEquals(exp)) ok++
            }
            log("分词器自检：$ok / ${samples.length()} 条与官方结果一致")
        } catch (e: Throwable) {
            log("分词器自检跳过：${e.message}")
        }
    }

    private fun newSession(f: File): OrtSession {
        require(f.exists()) { "缺少模型文件 ${f.name}" }
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(threads)
        o.setInterOpNumThreads(1)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        return env.createSession(f.absolutePath, o)
    }

    // ------------------------------------------------------------------ 合成

    /** 返回交错 16-bit PCM（sampleRate / channels）；被取消返回 null。 */
    fun synthesize(text: String, prompt: Array<IntArray>, cancelled: () -> Boolean): ShortArray? {
        val textIds = tokenizer.encode(text)
        val rows = buildRows(prompt, textIds)
        val total = rows.size / rowWidth
        val sink = ShortSink()
        val codec = CodecStream()
        var holder: OrtSession.Result? = null
        val maskBuf: IntBuffer = ByteBuffer.allocateDirect(nVq * cbSize * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        try {
            val ids = intT(rows, 1, total.toLong(), rowWidth.toLong())
            val mask = intT(IntArray(total) { 1 }, 1, total.toLong())
            holder = prefill.run(mapOf("input_ids" to ids, "attention_mask" to mask))
            ids.close(); mask.close()
            var hidden = lastHidden(holder.tensor("global_hidden"))
            var past = pastMap(holder, prefillOutputNames)
            var pastLen = total
            val pending = ArrayList<IntArray>()
            val decodePastNames = decodeInputNames.drop(2)

            fun flush() {
                if (pending.isEmpty()) return
                val (audio, len) = codec.run(pending)
                pending.clear()
                if (len > 0) sink.append(Resampler.toInterleavedPcm16(audio.toList(), len), len * audio.size)
            }

            for (step in 0 until maxNewFrames) {
                if (cancelled()) return null
                val (cont, frame) = runLocalFrame(hidden, maskBuf)
                if (!cont) break
                for (c in 0 until nVq) {
                    val tk = frame[c]
                    if (tk in 0 until cbSize) maskBuf.put(c * cbSize + tk, 1)
                }
                pending.add(frame)
                if (pending.size >= 8) flush()

                val row = IntArray(rowWidth) { padId }
                row[0] = assistantSlotId
                for (i in 0 until nVq) row[i + 1] = frame[i]
                val rowT = intT(row, 1, 1, rowWidth.toLong())
                val plT = intT(intArrayOf(pastLen), 1)
                val feeds = HashMap<String, OnnxTensor>()
                feeds["input_ids"] = rowT
                feeds["past_valid_lengths"] = plT
                for (name in decodePastNames) feeds[name] = past[name] ?: error("缺少 past 张量 $name")
                val res = decode.run(feeds)
                rowT.close(); plT.close()
                hidden = lastHidden(res.tensor("global_hidden"))
                past = pastMap(res, decodeOutputNames)
                holder?.close()
                holder = res
                pastLen++
            }
            flush()
            return sink.toArray()
        } finally {
            holder?.close()
            codec.closeAll()
        }
    }

    private fun runLocalFrame(hidden: FloatArray, maskBuf: IntBuffer): Pair<Boolean, IntArray> {
        val hT = floatT(hidden, 1, hidden.size.toLong())
        maskBuf.rewind()
        val mT = OnnxTensor.createTensor(env, maskBuf, longArrayOf(1, nVq.toLong(), cbSize.toLong()))
        val u1 = floatT(floatArrayOf(u()), 1)
        val u2 = floatT(FloatArray(nVq) { u() }, 1, nVq.toLong())
        val res = localFrame.run(mapOf("global_hidden" to hT, "repetition_seen_mask" to mT, "assistant_random_u" to u1, "audio_random_u" to u2))
        try {
            val ids = res.tensor("frame_token_ids").ints()
            val cont = res.tensor("should_continue").ints().firstOrNull() ?: 0
            return (cont > 0) to ids.copyOf(nVq)
        } finally {
            res.close(); hT.close(); mT.close(); u1.close(); u2.close()
        }
    }

    private fun u(): Float = rng.nextFloat().coerceIn(0f, 0.99999994f)

    private fun buildRows(prompt: Array<IntArray>, textIds: IntArray): IntArray {
        val prefix = prefixTemplate.toList() + audioStartId
        val suffix = listOf(audioEndId) + afterRefTemplate.toList() + textIds.toList() + assistantTemplate.toList() + audioStartId
        val total = prefix.size + prompt.size + suffix.size
        val out = IntArray(total * rowWidth) { padId }
        var r = 0
        for (t in prefix) { out[r * rowWidth] = t; r++ }
        for (codes in prompt) {
            out[r * rowWidth] = userSlotId
            for (q in 0 until minOf(codes.size, nVq)) out[r * rowWidth + 1 + q] = codes[q]
            r++
        }
        for (t in suffix) { out[r * rowWidth] = t; r++ }
        return out
    }

    private fun pastMap(res: OrtSession.Result, outputNames: List<String>): Map<String, OnnxTensor> {
        val m = HashMap<String, OnnxTensor>()
        for (name in outputNames.drop(1)) m[name.replace("present_", "past_")] = res.tensor(name)
        return m
    }

    private fun lastHidden(t: OnnxTensor): FloatArray {
        val shape = t.info.shape
        val h = shape.last().toInt()
        val fb = t.floatBuffer
        val steps = if (shape.size == 3) shape[1].toInt() else 1
        fb.position((steps - 1) * h)
        val out = FloatArray(h)
        fb.get(out)
        return out
    }

    // ------------------------------------------------------------------ codec 流式解码

    private inner class CodecStream {
        private val sd = codecMeta.getJSONObject("streaming_decode")
        private val tSpecs = sd.optJSONArray("transformer_offsets") ?: JSONArray()
        private val aSpecs = sd.optJSONArray("attention_caches") ?: JSONArray()
        private var states = HashMap<String, OnnxTensor>()
        private var owner: OrtSession.Result? = null
        private val initial = ArrayList<OnnxTensor>()

        init {
            for (i in 0 until tSpecs.length()) {
                val s = tSpecs.getJSONObject(i)
                put(s.getString("input_name"), zerosInt(s.getJSONArray("shape").toLongArray(), 0))
            }
            for (i in 0 until aSpecs.length()) {
                val s = aSpecs.getJSONObject(i)
                put(s.getString("offset_input_name"), zerosInt(s.getJSONArray("offset_shape").toLongArray(), 0))
                put(s.getString("cached_keys_input_name"), zerosFloat(s.getJSONArray("cache_shape").toLongArray()))
                put(s.getString("cached_values_input_name"), zerosFloat(s.getJSONArray("cache_shape").toLongArray()))
                put(s.getString("cached_positions_input_name"), zerosInt(s.getJSONArray("positions_shape").toLongArray(), -1))
            }
        }

        private fun put(name: String, t: OnnxTensor) { states[name] = t; initial.add(t) }

        fun run(frames: List<IntArray>): Pair<Array<FloatArray>, Int> {
            val n = frames.size
            val flat = IntArray(n * codecQuantizers)
            for ((i, f) in frames.withIndex()) for (q in 0 until codecQuantizers) flat[i * codecQuantizers + q] = if (q < f.size) f[q] else 0
            val codes = intT(flat, 1, n.toLong(), codecQuantizers.toLong())
            val lens = intT(intArrayOf(n), 1)
            val feeds = HashMap<String, OnnxTensor>()
            feeds["audio_codes"] = codes
            feeds["audio_code_lengths"] = lens
            feeds.putAll(states)
            val res = codecStep.run(feeds)
            codes.close(); lens.close()

            val next = HashMap<String, OnnxTensor>()
            for (i in 0 until tSpecs.length()) {
                val s = tSpecs.getJSONObject(i)
                next[s.getString("input_name")] = res.tensor(s.getString("output_name"))
            }
            for (i in 0 until aSpecs.length()) {
                val s = aSpecs.getJSONObject(i)
                next[s.getString("offset_input_name")] = res.tensor(s.getString("offset_output_name"))
                next[s.getString("cached_keys_input_name")] = res.tensor(s.getString("cached_keys_output_name"))
                next[s.getString("cached_values_input_name")] = res.tensor(s.getString("cached_values_output_name"))
                next[s.getString("cached_positions_input_name")] = res.tensor(s.getString("cached_positions_output_name"))
            }
            val audioT = res.tensor("audio")
            val shape = audioT.info.shape
            val ch = shape[1].toInt()
            val totalSamples = shape[2].toInt()
            val len = minOf(res.tensor("audio_lengths").ints().first(), totalSamples)
            val fb = audioT.floatBuffer
            val out = Array(ch) { c ->
                val arr = FloatArray(len)
                fb.position(c * totalSamples)
                fb.get(arr, 0, len)
                arr
            }
            owner?.close()
            for (t in initial) t.close()
            initial.clear()
            owner = res
            states = next
            return out to len
        }

        fun closeAll() {
            owner?.close(); owner = null
            for (t in initial) t.close()
            initial.clear()
            states = HashMap()
        }
    }

    // ------------------------------------------------------------------ 克隆音色：参考音频 → prompt codes

    /** waveform: [声道][采样]，必须已经是 codec 的采样率和声道数。 */
    fun encodeReference(waveform: Array<FloatArray>): Array<IntArray> {
        if (codecEncode == null) {
            val cf = codecMeta.getJSONObject("files")
            codecEncode = newSession(File(codecDir, File(cf.getString("encode")).name))
        }
        val n = waveform[0].size
        val flat = FloatArray(waveform.size * n)
        for (c in waveform.indices) System.arraycopy(waveform[c], 0, flat, c * n, n)
        val wT = floatT(flat, 1, waveform.size.toLong(), n.toLong())
        val lT = intT(intArrayOf(n), 1)
        val res = codecEncode!!.run(mapOf("waveform" to wT, "input_lengths" to lT))
        try {
            val codesT = res.tensor("audio_codes")
            val shape = codesT.info.shape
            val frames = shape[1].toInt()
            val nq = shape[2].toInt()
            val all = codesT.ints()
            val len = minOf(res.tensor("audio_code_lengths").ints().first(), frames)
            return Array(len) { f -> IntArray(codecQuantizers) { q -> if (q < nq) all[f * nq + q] else 0 } }
        } finally {
            res.close(); wT.close(); lT.close()
        }
    }

    val codecChannels: Int get() = channels
    val codecSampleRate: Int get() = sampleRate

    override fun close() {
        runCatching { prefill.close() }
        runCatching { decode.close() }
        runCatching { localFrame.close() }
        runCatching { codecStep.close() }
        runCatching { codecEncode?.close() }
    }

    // ------------------------------------------------------------------ 工具

    private fun intT(data: IntArray, vararg shape: Long): OnnxTensor {
        val ib = ByteBuffer.allocateDirect(maxOf(4, data.size * 4)).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(data); ib.rewind()
        return OnnxTensor.createTensor(env, ib, shape)
    }

    private fun floatT(data: FloatArray, vararg shape: Long): OnnxTensor {
        val fb = ByteBuffer.allocateDirect(maxOf(4, data.size * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data); fb.rewind()
        return OnnxTensor.createTensor(env, fb, shape)
    }

    private fun zerosInt(shape: LongArray, fill: Int): OnnxTensor {
        var n = 1L; for (d in shape) n *= d
        val a = IntArray(n.toInt()); if (fill != 0) a.fill(fill)
        return intT(a, *shape)
    }

    private fun zerosFloat(shape: LongArray): OnnxTensor {
        var n = 1L; for (d in shape) n *= d
        return floatT(FloatArray(n.toInt()), *shape)
    }

    private fun OrtSession.Result.tensor(name: String): OnnxTensor =
        (this.get(name).orElse(null) as? OnnxTensor) ?: error("模型输出里没有 $name")

    private fun OnnxTensor.ints(): IntArray = when (info.type) {
        OnnxJavaType.INT32 -> { val b = intBuffer; IntArray(b.remaining()).also { b.get(it) } }
        OnnxJavaType.INT64 -> { val b = longBuffer; val p = b.position(); IntArray(b.remaining()) { b.get(p + it).toInt() } }
        OnnxJavaType.FLOAT -> { val b = floatBuffer; val p = b.position(); IntArray(b.remaining()) { b.get(p + it).toInt() } }
        OnnxJavaType.BOOL -> { val b = byteBuffer; val p = b.position(); IntArray(b.remaining()) { if (b.get(p + it).toInt() != 0) 1 else 0 } }
        else -> error("不支持的张量类型 ${info.type}")
    }

    private fun JSONArray.toIntArray() = IntArray(length()) { getInt(it) }
    private fun JSONArray.toLongArray() = LongArray(length()) { getLong(it) }
    private fun JSONArray.toStringList() = List(length()) { getString(it) }
    private fun JSONArray.toCodes(): Array<IntArray> = Array(length()) { getJSONArray(it).toIntArray() }

    private class ShortSink {
        private var buf = ShortArray(48000 * 2 * 4)
        private var size = 0
        fun append(a: ShortArray, count: Int) {
            if (size + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + count))
            System.arraycopy(a, 0, buf, size, count)
            size += count
        }
        fun toArray(): ShortArray = buf.copyOf(size)
    }
}
