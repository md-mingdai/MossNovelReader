package com.mossreader.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Resampler {
    /** 单声道 float PCM 重采样（加窗 sinc，下采样时自动低通）。 */
    fun resample(input: FloatArray, srcRate: Int, dstRate: Int, zeros: Int = 12): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input.copyOf()
        val ratio = srcRate.toDouble() / dstRate
        val outLen = floor(input.size / ratio).toInt()
        val out = FloatArray(outLen)
        val fc = min(1.0, dstRate.toDouble() / srcRate) * 0.97      // 归一化截止频率
        val half = zeros / fc                                        // 以源采样点为单位的半宽
        for (j in 0 until outLen) {
            val t = j * ratio
            val lo = max(0, kotlin.math.ceil(t - half).toInt())
            val hi = min(input.size - 1, floor(t + half).toInt())
            var acc = 0.0
            var wsum = 0.0
            for (n in lo..hi) {
                val x = n - t
                val w = 0.5 * (1.0 + cos(PI * x / half))
                val s = if (abs(x) < 1e-9) 1.0 else sin(PI * fc * x) / (PI * fc * x)
                val k = fc * s * w
                acc += input[n] * k
                wsum += k
            }
            out[j] = if (wsum > 1e-9) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    /** float [-1,1] → 16-bit 小端交错立体声（channels 为 1 或 2 的 float 数组列表）。 */
    fun toInterleavedPcm16(channels: List<FloatArray>, count: Int): ShortArray {
        val ch = channels.size
        val out = ShortArray(count * ch)
        for (i in 0 until count) {
            for (c in 0 until ch) {
                val v = channels[c][i]
                val clipped = if (v > 1f) 1f else if (v < -1f) -1f else v
                out[i * ch + c] = (clipped * 32767f).toInt().toShort()
            }
        }
        return out
    }

    /** 去掉首尾静音，保留 keepMs 的余量（interleaved 16-bit）。 */
    fun trimSilence(pcm: ShortArray, channels: Int, sampleRate: Int, threshold: Int = 180, keepStartMs: Int = 30, keepEndMs: Int = 60): ShortArray {
        val frames = pcm.size / channels
        if (frames == 0) return pcm
        fun loud(f: Int): Boolean {
            for (c in 0 until channels) if (abs(pcm[f * channels + c].toInt()) > threshold) return true
            return false
        }
        var s = 0
        while (s < frames && !loud(s)) s++
        if (s >= frames) return ShortArray(0)
        var e = frames - 1
        while (e > s && !loud(e)) e--
        val start = max(0, s - sampleRate * keepStartMs / 1000)
        val end = min(frames, e + 1 + sampleRate * keepEndMs / 1000)
        return pcm.copyOfRange(start * channels, end * channels)
    }
}
