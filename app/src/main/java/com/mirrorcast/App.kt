package com.mirrorcast

import android.app.Application
import android.util.Log
import com.mirrorcast.util.DiagLog
import java.io.File

/**
 * 全局崩溃捕获：把任何未捕获异常写入 filesDir/crash.log，
 * 下次启动时注入诊断日志，方便用户反馈闪退原因。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val crashFile = File(filesDir, CRASH_LOG)

        // 上次崩溃原因注入诊断日志（展示一次后清除，避免每次启动都显示旧记录）
        if (crashFile.exists()) {
            try {
                val text = crashFile.readText()
                if (text.isNotBlank()) {
                    DiagLog.log("【上次崩溃原因】\n$text")
                }
                crashFile.delete()
            } catch (_: Exception) {
            }
        }

        // 全局未捕获异常处理器：记录后进程照常终止
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "uncaught on ${thread.name}", throwable)
                val text = buildString {
                    append(throwable.javaClass.name)
                    append(": ")
                    append(throwable.message ?: "")
                    append("\n")
                    append(
                        throwable.stackTrace.take(20).joinToString("\n") { "    at $it" }
                    )
                    append("\n")
                    append(
                        throwable.cause?.let { "Caused by: ${it.javaClass.name}: ${it.message}" }
                            ?: ""
                    )
                }
                crashFile.writeText(text)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "MirrorCast"
        private const val CRASH_LOG = "crash.log"
    }
}
