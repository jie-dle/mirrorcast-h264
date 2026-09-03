package com.mirrorcast.cast.transport

import android.util.Log
import com.mirrorcast.util.DiagLog
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP 视频接收器：接收端主动 connect 发送端，按 [4B 长度][1B 关键帧标志][payload] 切帧。
 * TCP 保证有序无丢包；丢帧用"只喂最新解出帧"缓冲策略（队列容量 1）。
 * keyframe 标志为真实编码帧判定（V0.3.1 起），供 VideoSink 保关键帧。
 *
 * 重连策略：start 后若连不上或中途断开，自动无限重连（约 1.5s 间隔），
 * 直到连接成功开始收流，或外部调用 close() 取消。解决"先点接收/中途停"收不到画面。
 */
class TcpVideoReceiver(private val ip: String, private val port: Int) {
    var onFrame: ((ByteArray, Boolean, Long) -> Unit)? = null
    /** 连接状态回调：true=已连上并收流中，false=当前未连接（重试中或已取消） */
    var onState: ((Boolean) -> Unit)? = null

    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var cancelled = false

    @Volatile private var frameCount = 0L

    /** 启动接收：开后台线程持续连接+收流，直到 close() */
    fun start() {
        if (running) return
        running = true
        cancelled = false
        thread = Thread {
            while (running) {
                if (cancelled) break
                val s = tryConnect()
                if (s == null) {
                    // 连不上：短暂等待后重试；从未连上则持续重试
                    sleepSafe(1500)
                    continue
                }
                if (!running || cancelled) { closeSocket(s); break }
                socket = s
                onState?.invoke(true)
                // 收流循环
                readLoop(s)
                closeSocket(s)
                socket = null
                onState?.invoke(false)
                DiagLog.log("TCP 视频接收断开，自动重连…")
                sleepSafe(1500)
            }
            running = false
        }.apply { isDaemon = true; name = "tcp-v-recv"; start() }
        DiagLog.log("TCP 视频接收器启动（连不上将自动重试）")
    }

    private fun tryConnect(): Socket? {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, port), 3000)
            DiagLog.log("TCP 视频连接已建立（接收端）")
            s
        } catch (t: Throwable) {
            DiagLog.log("TCP 视频连接失败：${t.javaClass.simpleName}，重试…")
            null
        }
    }

    /** 单次连接上的收流循环；断开返回 false */
    private fun readLoop(s: Socket): Boolean {
        return try {
            val input = DataInputStream(s.getInputStream())
            while (running && !cancelled) {
                val len = input.readInt()
                if (len <= 0 || len > 2 * 1024 * 1024) { DiagLog.log("TCP 帧长度异常: $len"); break }
                val isKey = (input.readByte().toInt() and 0xff) != 0
                val buf = ByteArray(len)
                input.readFully(buf)
                frameCount++
                onFrame?.invoke(buf, isKey, System.nanoTime() / 1000L)
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun closeSocket(s: Socket) {
        try { s.close() } catch (_: Exception) {}
    }

    private fun sleepSafe(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    /** 取消收流与重连 */
    fun close() {
        cancelled = true
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        onState?.invoke(false)
        DiagLog.log("TCP 视频接收器已关闭")
    }

    companion object { private const val TAG = "MirrorCastV3" }
}
