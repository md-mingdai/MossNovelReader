package com.mossreader.util

import android.util.Log

/** 内存里保留最近的日志，设置页可以查看/复制，方便排查问题。 */
object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized
    fun d(msg: String) {
        Log.d("MossReader", msg)
        lines.addLast("${fmt.format(java.util.Date())} $msg")
        while (lines.size > 400) lines.removeFirst()
    }

    @Synchronized
    fun e(msg: String, t: Throwable? = null) {
        Log.e("MossReader", msg, t)
        lines.addLast("${fmt.format(java.util.Date())} [错误] $msg" + (t?.let { " :: " + describe(it) } ?: ""))
        while (lines.size > 400) lines.removeFirst()
    }

    /** 把异常的整条 cause 链和关键堆栈都写出来（ExceptionInInitializerError 之类的 message 是 null）。 */
    private fun describe(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var root: Throwable = t
        var depth = 0
        while (cur != null && depth < 5) {
            if (depth > 0) sb.append(" ← 原因 ")
            sb.append(cur.javaClass.simpleName).append(": ").append(cur.message)
            root = cur
            cur = cur.cause
            depth++
        }
        val frames = root.stackTrace.filter { it.className.startsWith("com.mossreader") || it.className.startsWith("ai.onnxruntime") }.take(4)
        if (frames.isNotEmpty()) sb.append("\n    at ").append(frames.joinToString("\n    at "))
        return sb.toString()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
