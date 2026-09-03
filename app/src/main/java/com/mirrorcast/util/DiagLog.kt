package com.mirrorcast.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 轻量诊断日志：内存环形缓冲，界面可直接展示/复制，方便不熟悉 Logcat 的用户反馈问题。
 */
object DiagLog {

    private val lines = ConcurrentLinkedDeque<String>()
    private const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(msg: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + " " + msg
        lines.addLast(line)
        while (lines.size > MAX) lines.pollFirst()
    }

    fun dump(): String = lines.joinToString("\n")
}
