package com.mossreader.playback

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.mossreader.audio.AudioDecoder
import com.mossreader.audio.PcmPlayer
import com.mossreader.audio.Resampler
import com.mossreader.data.BookRepository
import com.mossreader.data.SettingsStore
import com.mossreader.text.AndroidWordBreaker
import com.mossreader.text.Chapter
import com.mossreader.text.PlannedChapter
import com.mossreader.text.PlannerOptions
import com.mossreader.text.Role
import com.mossreader.text.SegmentPlanner
import com.mossreader.text.TtsTextNormalizer
import com.mossreader.tts.ModelStore
import com.mossreader.tts.MossTtsEngine
import com.mossreader.tts.SentencePiece
import com.mossreader.tts.VoiceEntry
import com.mossreader.tts.VoiceStore
import com.mossreader.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class PlayStatus { IDLE, LOADING, BUFFERING, PLAYING, PAUSED }

data class ReaderState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapters: List<Chapter> = emptyList(),
    val chapterIndex: Int = 0,
    val chapterText: String = "",
    val plan: PlannedChapter? = null,
    val segmentIndex: Int = 0,
    val status: PlayStatus = PlayStatus.IDLE,
    val message: String = "",
    val rtf: Float = 0f,
    val sleepLeftMin: Int = 0,
    val engineReady: Boolean = false,
)

private sealed class AudioItem {
    class Pcm(val data: ShortArray, val seg: Int) : AudioItem()
    class Silence(val ms: Int) : AudioItem()
    object End : AudioItem()
}

class ReaderController(
    private val app: Application,
    val models: ModelStore,
    val books: BookRepository,
    val voices: VoiceStore,
    val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "moss-engine").also { it.priority = Thread.NORM_PRIORITY + 2 }
    }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state
    val modelVersion = MutableStateFlow(0)
    val voiceVersion = MutableStateFlow(0)
    val busyMessage = MutableStateFlow("")

    private var engine: MossTtsEngine? = null
    private var engineThreads = 0
    private var tokenizer: SentencePiece? = null
    private var builtinCache: List<VoiceEntry>? = null
    private var player: PcmPlayer? = null
    private var playJob: Job? = null
    private var loadJob: Job? = null
    private var sleepJob: Job? = null
    @Volatile private var epoch = 0
    @Volatile private var userPaused = false

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }.build()

    init {
        scope.launch {
            settings.flow.map { Triple(it.maxTokens, it.pauseScale, it.splitDialogue) }.distinctUntilChanged().drop(1).collect {
                if (_state.value.status == PlayStatus.IDLE) replan()
            }
        }
        scope.launch {
            settings.flow.map { it.speed }.distinctUntilChanged().collect { player?.setSpeed(it) }
        }
    }

    // ------------------------------------------------------------------ 模型 / 音色

    private fun tokenizerOrNull(): SentencePiece? {
        tokenizer?.let { return it }
        val f = File(models.ttsDir, "tokenizer.model")
        if (f.exists()) tokenizer = runCatching { SentencePiece.load(f) }.onFailure { AppLog.e("读取 tokenizer.model 失败", it) }.getOrNull()
        return tokenizer
    }

    private suspend fun ensureEngine(): MossTtsEngine = withContext(engineDispatcher) {
        val th = settings.value.threads
        engine?.let { if (engineThreads == th) return@withContext it; it.close(); engine = null }
        require(models.isCoreReady()) { "还没有下载语音模型，请先到「设置」里下载" }
        val tk = tokenizerOrNull() ?: error("无法读取 tokenizer.model")
        _state.update { it.copy(message = "正在加载语音模型…") }
        val e = MossTtsEngine(models.ttsDir, models.codecDir, th, tk)
        e.load()
        engine = e
        engineThreads = th
        _state.update { it.copy(engineReady = true, message = "") }
        e
    }

    fun releaseEngine() {
        scope.launch(engineDispatcher) {
            engine?.close(); engine = null
            tokenizer = null; builtinCache = null
            _state.update { it.copy(engineReady = false) }
            modelVersion.update { it + 1 }
        }
    }

    fun builtinVoices(): List<VoiceEntry> {
        builtinCache?.let { return it }
        val f = File(models.ttsDir, "browser_poc_manifest.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("builtin_voices")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("voice")
                val ca = o.getJSONArray("prompt_audio_codes")
                val codes = Array(ca.length()) { r -> val row = ca.getJSONArray(r); IntArray(row.length()) { row.getInt(it) } }
                VoiceEntry(id, o.optString("display_name", id).ifEmpty { id }, o.optString("group", ""), true, codes)
            }
        }.onFailure { AppLog.e("读取内置音色失败", it) }.getOrDefault(emptyList()).also { builtinCache = it }
    }

    fun allVoices(): List<VoiceEntry> = builtinVoices() + voices.list()

    fun defaultVoiceId(): String = settings.value.narratorVoice.ifEmpty { null }
        ?: builtinVoices().firstOrNull { it.id == "Junhao" }?.id ?: builtinVoices().firstOrNull()?.id ?: ""

    private fun codesFor(id: String): Array<IntArray> {
        val all = allVoices()
        val v = all.firstOrNull { it.id == id } ?: all.firstOrNull { it.builtin } ?: error("没有可用的音色")
        return v.codes
    }

    // ------------------------------------------------------------------ 书 / 章节 / 分段

    fun openBook(id: String) {
        if (_state.value.bookId == id) return
        stopPlayback()
        val meta = books.get(id) ?: return
        val (ci, off) = books.progress(id)
        _state.value = ReaderState(bookId = id, bookTitle = meta.title, chapters = meta.chapters, engineReady = engine != null)
        loadChapter(ci.coerceIn(0, meta.chapters.lastIndex), off, false)
    }

    fun closeBook() { stopPlayback(); _state.value = ReaderState(engineReady = engine != null) }

    fun openChapter(index: Int, play: Boolean) {
        val st = _state.value
        if (index !in st.chapters.indices) return
        stopPlayback()
        loadChapter(index, 0, play)
    }

    fun nextChapter() { val st = _state.value; openChapter(st.chapterIndex + 1, st.status != PlayStatus.IDLE) }
    fun prevChapter() { val st = _state.value; openChapter(st.chapterIndex - 1, st.status != PlayStatus.IDLE) }

    private fun loadChapter(index: Int, offset: Int, autoPlay: Boolean) {
        val id = _state.value.bookId ?: return
        val meta = books.get(id) ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            val full = withContext(Dispatchers.IO) { books.text(id) }
            val ch = meta.chapters[index]
            val text = full.substring(ch.start, ch.end)
            _state.update { it.copy(chapterIndex = index, chapterText = text, plan = null, segmentIndex = 0, message = "正在分段…") }
            val plan = computePlan(text, ch)
            val seg = if (plan.segments.isEmpty()) 0 else plan.segmentAt(offset)
            _state.update { it.copy(plan = plan, segmentIndex = seg, message = "") }
            books.saveProgress(id, index, offset)
            if (autoPlay) startPlayback(seg)
        }
    }

    private suspend fun computePlan(text: String, ch: Chapter): PlannedChapter = withContext(Dispatchers.Default) {
        val p = settings.value
        val tk = tokenizerOrNull()
        val counter: (String) -> Int = if (tk != null) { s -> tk.encode(s).size } else { s -> (s.length * 0.75).toInt() + 1 }
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        fun norm(s: String) = s.replace(Regex("\\s+"), "").trimStart('#')
        val firstIsTitle = first.length <= 60 && norm(first).isNotEmpty() && (norm(first).contains(norm(ch.title)) || norm(ch.title).contains(norm(first)))
        SegmentPlanner(counter, AndroidWordBreaker(), PlannerOptions(p.maxTokens, p.pauseScale, p.splitDialogue)).plan(text, firstIsTitle)
    }

    private fun replan() {
        val st = _state.value
        val id = st.bookId ?: return
        if (st.chapterText.isEmpty()) return
        val off = st.plan?.segments?.getOrNull(st.segmentIndex)?.start ?: 0
        loadChapter(st.chapterIndex, off, false)
    }

    // ------------------------------------------------------------------ 播放控制

    fun toggle() {
        when (_state.value.status) {
            PlayStatus.IDLE -> { userPaused = false; startPlayback(_state.value.segmentIndex) }
            PlayStatus.PAUSED -> resume()
            else -> pause()
        }
    }

    fun pause() {
        if (_state.value.status == PlayStatus.IDLE) return
        userPaused = true
        player?.pause()
        _state.update { it.copy(status = PlayStatus.PAUSED) }
    }

    fun resume() {
        if (_state.value.status != PlayStatus.PAUSED) return
        userPaused = false
        val pl = player
        if (pl != null) { pl.resume(); _state.update { it.copy(status = PlayStatus.PLAYING) } }
        else startPlayback(_state.value.segmentIndex)
    }

    fun seekSegment(i: Int, play: Boolean = true) {
        val plan = _state.value.plan ?: return
        if (plan.segments.isEmpty()) return
        val idx = i.coerceIn(0, plan.segments.lastIndex)
        val active = _state.value.status != PlayStatus.IDLE
        _state.update { it.copy(segmentIndex = idx) }
        _state.value.bookId?.let { id -> books.saveProgress(id, _state.value.chapterIndex, plan.segments[idx].start) }
        if (active || play) { userPaused = false; startPlayback(idx) }
    }

    fun nextSegment() = seekSegment(_state.value.segmentIndex + 1)
    fun prevSegment() = seekSegment(_state.value.segmentIndex - 1)

    fun stopPlayback() {
        cancelPlayback()
        userPaused = false
        _state.update { it.copy(status = PlayStatus.IDLE) }
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun cancelPlayback() {
        epoch++
        playJob?.cancel(); playJob = null
        player?.stop(); player = null
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { _state.update { it.copy(sleepLeftMin = 0) }; return }
        sleepJob = scope.launch {
            var left = minutes
            while (left > 0) {
                _state.update { it.copy(sleepLeftMin = left) }
                delay(60_000)
                left--
            }
            _state.update { it.copy(sleepLeftMin = 0) }
            pause()
        }
    }

    private fun startPlayback(from: Int) {
        val plan = _state.value.plan ?: return
        if (plan.segments.isEmpty()) { onChapterEnd(); return }
        cancelPlayback()
        val my = ++epoch
        val start = from.coerceIn(0, plan.segments.lastIndex)
        val bookId = _state.value.bookId
        val chapterIndex = _state.value.chapterIndex
        _state.update { it.copy(status = if (userPaused) PlayStatus.PAUSED else PlayStatus.LOADING, segmentIndex = start, message = "") }
        runCatching { audioManager.requestAudioFocus(focusRequest) }
        PlaybackService.start(app)

        playJob = scope.launch {
            try {
                val eng = ensureEngine()
                if (my != epoch) return@launch
                val prefs = settings.value
                val meta = bookId?.let { books.get(it) }
                val narratorId = meta?.narratorVoice?.ifEmpty { null } ?: defaultVoiceId()
                val dialogueId = if (prefs.splitDialogue)
                    (meta?.dialogueVoice?.ifEmpty { null } ?: prefs.dialogueVoice.ifEmpty { null } ?: narratorId) else narratorId
                val narratorCodes = codesFor(narratorId)
                val dialogueCodes = if (dialogueId == narratorId) narratorCodes else codesFor(dialogueId)

                val pl = PcmPlayer(eng.sampleRate, eng.channels)
                pl.start(prefs.speed)
                if (userPaused) pl.pause()
                player = pl
                val channel = Channel<AudioItem>(4)

                val producer = launch(engineDispatcher) {
                    try {
                        for (i in start until plan.segments.size) {
                            if (!isActive || my != epoch) break
                            val seg = plan.segments[i]
                            val codes = if (seg.role == Role.DIALOGUE) dialogueCodes else narratorCodes
                            val t0 = System.nanoTime()
                            val pcm = eng.synthesize(seg.speakText, codes) { !isActive || my != epoch } ?: break
                            val trimmed = Resampler.trimSilence(pcm, eng.channels, eng.sampleRate)
                            val sec = trimmed.size / (eng.sampleRate.toFloat() * eng.channels)
                            if (sec > 0.05f) {
                                val rtf = ((System.nanoTime() - t0) / 1e9f) / sec
                                _state.update { it.copy(rtf = rtf) }
                                AppLog.d("第${i + 1}段：音频 ${"%.1f".format(sec)}s，耗时 ${"%.1f".format(rtf * sec)}s，实时率 ${"%.2f".format(rtf)}")
                            }
                            channel.send(AudioItem.Pcm(trimmed, i))
                            channel.send(AudioItem.Silence(seg.pauseMs))
                        }
                        if (isActive && my == epoch) channel.send(AudioItem.End)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLog.e("合成失败", e)
                        _state.update { it.copy(message = "合成失败：${e.message}") }
                    } finally {
                        channel.close()
                    }
                }

                var ended = false
                while (true) {
                    val r = channel.tryReceive()
                    val item: AudioItem = (if (r.isSuccess) r.getOrNull() else {
                        _state.update { if (it.status == PlayStatus.PLAYING) it.copy(status = PlayStatus.BUFFERING) else it }
                        channel.receiveCatching().getOrNull()
                    }) ?: break
                    if (my != epoch) break
                    when (item) {
                        is AudioItem.Pcm -> {
                            val seg = plan.segments[item.seg]
                            _state.update { it.copy(segmentIndex = item.seg, status = if (userPaused) PlayStatus.PAUSED else PlayStatus.PLAYING, message = "") }
                            if (bookId != null) books.saveProgress(bookId, chapterIndex, seg.start)
                            if (!withContext(Dispatchers.IO) { pl.write(item.data) }) break
                        }
                        is AudioItem.Silence -> if (!withContext(Dispatchers.IO) { pl.writeSilence(item.ms) }) break
                        AudioItem.End -> ended = true
                    }
                    if (ended) break
                }
                producer.join()
                if (my == epoch) {
                    if (ended) { delay(800); if (my == epoch) onChapterEnd() }
                    else { player?.stop(); player = null; _state.update { it.copy(status = PlayStatus.IDLE) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("播放失败", e)
                if (my == epoch) {
                    player?.stop(); player = null
                    _state.update { it.copy(status = PlayStatus.IDLE, message = "出错：${e.message ?: e.javaClass.simpleName}") }
                }
            }
        }
    }

    private fun onChapterEnd() {
        val st = _state.value
        val next = st.chapterIndex + 1
        if (next < st.chapters.size) {
            player?.stop(); player = null
            _state.update { it.copy(status = PlayStatus.LOADING) }
            loadChapter(next, 0, true)
        } else {
            stopPlayback()
            _state.update { it.copy(message = "全书已读完") }
        }
    }

    // ------------------------------------------------------------------ 试听 / 克隆

    fun previewVoice(voiceId: String, text: String = "你好，很高兴认识你。这是我的声音，你觉得怎么样？") {
        stopPlayback()
        val my = ++epoch
        playJob = scope.launch {
            try {
                busyMessage.value = "正在合成试听…"
                val eng = ensureEngine()
                val codes = codesFor(voiceId)
                val t0 = System.nanoTime()
                val pcm = withContext(engineDispatcher) { eng.synthesize(TtsTextNormalizer.speakText(text), codes) { my != epoch } } ?: return@launch
                val trimmed = Resampler.trimSilence(pcm, eng.channels, eng.sampleRate)
                val sec = trimmed.size / (eng.sampleRate.toFloat() * eng.channels)
                val cost = (System.nanoTime() - t0) / 1e9f
                AppLog.d("试听：音频 ${"%.1f".format(sec)}s，耗时 ${"%.1f".format(cost)}s，实时率 ${"%.2f".format(cost / sec)}")
                busyMessage.value = "实时率 ${"%.2f".format(cost / sec)}（小于 1 表示比说话还快）"
                val pl = PcmPlayer(eng.sampleRate, eng.channels)
                pl.start(1f); player = pl
                withContext(Dispatchers.IO) { pl.write(trimmed) }
                delay(800)
                pl.stop(); player = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("试听失败", e)
                busyMessage.value = "试听失败：${e.message}"
            }
        }
    }

    suspend fun cloneVoice(name: String, pcm: AudioDecoder.Pcm): VoiceEntry {
        require(models.isEncoderReady()) { "还没有下载克隆编码器，请先到「设置」里下载" }
        val eng = ensureEngine()
        busyMessage.value = "正在处理参考音频…"
        val prepared = withContext(Dispatchers.Default) { AudioDecoder.prepareForCodec(pcm, eng.codecSampleRate, eng.codecChannels, 15) }
        require(prepared[0].size > eng.codecSampleRate) { "参考音频太短（至少 1 秒，建议 5–15 秒清晰人声）" }
        busyMessage.value = "正在提取音色特征…"
        val codes = withContext(engineDispatcher) { eng.encodeReference(prepared) }
        val v = voices.save(name, codes)
        voiceVersion.update { it + 1 }
        busyMessage.value = "克隆完成：${v.name}（${codes.size} 帧）"
        AppLog.d("克隆音色 ${v.name}：${prepared[0].size / eng.codecSampleRate.toFloat()} 秒参考音频 → ${codes.size} 帧")
        return v
    }

    fun saveBookVoices(narrator: String, dialogue: String) {
        val id = _state.value.bookId ?: return
        books.saveVoices(id, narrator, dialogue)
    }

    fun deleteVoice(id: String) { voices.delete(id); voiceVersion.update { it + 1 } }
}
