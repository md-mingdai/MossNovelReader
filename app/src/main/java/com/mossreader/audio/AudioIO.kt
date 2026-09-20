package com.mossreader.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import com.mossreader.util.AppLog
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 把任意音频文件（mp3/m4a/wav/ogg/flac…）解码成 float PCM，并整理成 codec 需要的格式。 */
object AudioDecoder {
    class Pcm(val channels: Array<FloatArray>, val sampleRate: Int)

    fun decode(ctx: Context, uri: Uri, maxSeconds: Int): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
        }
        require(track >= 0 && fmt != null) { "文件里没有音频轨道" }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var data = FloatArray(rate * ch * 8)
        var count = 0
        var inEos = false
        var outEos = false
        try {
            while (!outEos) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.nativeOrder())
                    if (isFloat) {
                        val fb = ob.asFloatBuffer()
                        val n = fb.remaining()
                        if (count + n > data.size) data = data.copyOf(max(data.size * 2, count + n))
                        fb.get(data, count, n); count += n
                    } else {
                        val sb = ob.asShortBuffer()
                        val n = sb.remaining()
                        if (count + n > data.size) data = data.copyOf(max(data.size * 2, count + n))
                        for (k in 0 until n) data[count + k] = sb.get() / 32768f
                        count += n
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    if (count / ch >= rate * maxSeconds) outEos = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        val frames = min(count / ch, rate * maxSeconds)
        val chans = Array(ch) { c -> FloatArray(frames) { i -> data[i * ch + c] } }
        AppLog.d("解码参考音频：${frames / rate.toFloat()} 秒，${rate}Hz，${ch} 声道")
        return Pcm(chans, rate)
    }

    /** 重采样到 codec 采样率、整理声道、去掉开头静音、限制时长。 */
    fun prepareForCodec(pcm: Pcm, targetRate: Int, targetChannels: Int, maxSeconds: Int): Array<FloatArray> {
        var chans = pcm.channels.map { Resampler.resample(it, pcm.sampleRate, targetRate) }
        chans = when {
            chans.size == targetChannels -> chans
            chans.size == 1 -> List(targetChannels) { chans[0] }
            targetChannels == 1 -> {
                val n = chans.minOf { it.size }
                listOf(FloatArray(n) { i -> chans.sumOf { it[i].toDouble() }.toFloat() / chans.size })
            }
            else -> chans.take(targetChannels)
        }
        var start = 0
        val n = chans.minOf { it.size }
        while (start < n && chans.all { abs(it[start]) < 0.01f }) start++
        start = max(0, start - targetRate / 50)
        val end = min(n, start + targetRate * maxSeconds)
        return Array(chans.size) { chans[it].copyOfRange(start, end) }
    }
}

/** 录音克隆用：单声道 44.1kHz。 */
class VoiceRecorder {
    private var rec: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    @Volatile var seconds = 0f; private set
    @Volatile var level = 0f; private set
    val isRunning: Boolean get() = running
    val sampleRate = 44100

    @android.annotation.SuppressLint("MissingPermission")
    fun start(maxSeconds: Int, onAutoStop: () -> Unit) {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, sampleRate))
        rec = r
        chunks.clear(); seconds = 0f
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(sampleRate / 10)
            var total = 0
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    chunks.add(buf.copyOf(n)); total += n
                    var pk = 0; for (i in 0 until n) pk = max(pk, abs(buf[i].toInt()))
                    level = pk / 32768f
                    seconds = total / sampleRate.toFloat()
                    if (seconds >= maxSeconds) { running = false; onAutoStop() }
                }
            }
        }.also { it.start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(500)
        runCatching { rec?.stop() }; rec?.release(); rec = null
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var p = 0
        for (c in chunks) { for (s in c) out[p++] = s / 32768f }
        return out
    }
}

/** 流式播放 16-bit 交错 PCM。 */
class PcmPlayer(private val sampleRate: Int, private val channels: Int) {
    private var track: AudioTrack? = null

    fun start(speed: Float) {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val size = max(minBuf, (sampleRate * channels * 2 * 0.6).toInt())
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        setSpeed(speed)
        t.play()
    }

    fun setSpeed(speed: Float) {
        val t = track ?: return
        try {
            t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        } catch (e: Exception) {
            AppLog.e("设置语速失败", e)
        }
    }

    /** 阻塞写入；返回 false 表示播放器已被释放。 */
    fun write(pcm: ShortArray): Boolean {
        val t = track ?: return false
        var off = 0
        val slice = sampleRate * channels / 10
        while (off < pcm.size) {
            val n = min(slice, pcm.size - off)
            val w = try { t.write(pcm, off, n) } catch (e: Exception) { -1 }
            if (w < 0) return false
            off += w
            if (track == null) return false
        }
        return true
    }

    fun writeSilence(ms: Int): Boolean {
        if (ms <= 0) return true
        return write(ShortArray(sampleRate * channels * ms / 1000))
    }

    fun pause() { runCatching { track?.pause() } }
    fun resume() { runCatching { track?.play() } }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush() }
        runCatching { t.release() }
    }
}
