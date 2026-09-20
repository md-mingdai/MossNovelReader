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
        lines.addLast("${fmt.format(java.util.Date())} [错误] $msg" + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        while (lines.size > 400) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
