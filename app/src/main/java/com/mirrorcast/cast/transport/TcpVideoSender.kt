package com.mirrorcast.cast.transport

import android.util.Log
import com.mirrorcast.util.DiagLog
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * TCP 视频发送器：发送端自建 ServerSocket，接收端 connect。
 * 帧格式：[4B 大端长度][payload]，TCP 可靠有序无需分片重组。
 *
 * 多路由推流：accept 循环无限接收新连接（含重连），同一份帧写给所有已连接接收端。
 * 慢接收端防拖累：发送超时（写不进）即断开该路，避免阻塞快接收端。
 *
 * 帧格式：[4B 大端长度][1B 关键帧标志 0/1][payload]（V0.3.1 起带 keyframe 标志，供接收端保关键帧）
 */
class TcpVideoSender(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private val clients = ArrayList<Client>()
    private var acceptThread: Thread? = null

    /** 队列元素：帧数据 + 关键帧标志 */
    private class Queued(val bytes: ByteArray, val keyframe: Boolean)

    private val pending = LinkedBlockingQueue<Queued>(4)
    @Volatile private var running = false

    /** 发送端丢帧计数（探针）：队列满丢最旧帧时 +1（仅累计，不改逻辑） */
    @Volatile var droppedFrames = 0L
        private set

    /** 有接收端接入回调 */
    var onConnected: (() -> Unit)? = null
    /** 活跃接收端数量回调（连接/断开时触发，用于 UI 展示"已连 N 台"） */
    var onClientCount: ((Int) -> Unit)? = null

    /** 通知当前接收端数量（在 push 线程去除死路后调用，避免与 UI 竞态） */
    private fun notifyClientCount() {
        val n = synchronized(clients) { clients.size }
        onClientCount?.invoke(n)
    }

    /** 一路接收端：持有输出流；数据并发写需串行化 */
    private class Client(val socket: Socket, val output: DataOutputStream) {
        val lock = Any()
        @Volatile var dead = false
    }

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket(port)
            running = true
            acceptThread = Thread {
                while (running) {
                    try {
                        val s = serverSocket?.accept() ?: break
                        s.tcpNoDelay = true
                        val c = Client(s, DataOutputStream(s.getOutputStream()))
                        synchronized(clients) { clients.add(c) }
                        DiagLog.log("TCP 视频连接建立：${s.inetAddress?.hostAddress ?: "?"}")
                        onConnected?.invoke()
                        notifyClientCount()
                    } catch (t: Throwable) {
                        if (running) Log.w(TAG, "tcp video accept", t)
                    }
                }
            }.apply { isDaemon = true; name = "tcp-v-send"; start() }
            startSenderThread()
            DiagLog.log("TCP 视频服务器就绪：:${port}")
            true
        } catch (t: Throwable) {
            DiagLog.log("TCP 视频启动失败：" + t.javaClass.simpleName)
            false
        }
    }

    fun sendFrame(frame: ByteArray, keyframe: Boolean, ptsUs: Long) {
        if (!running) return
        try {
            if (pending.remainingCapacity() == 0) { pending.poll(); droppedFrames++ }
            pending.offer(Queued(frame, keyframe))
        } catch (_: Exception) {}
    }

    /** 推送线程：从队列取帧，广播给所有活跃客户端 */
    private fun startSenderThread() {
        val th = Thread {
            while (running) {
                try {
                    val q = pending.take()
                    // 快照活跃客户端；先清掉已断开/写失败的路，避免拖累快接收端
                    var snapshot: List<Client>? = null
                    synchronized(clients) {
                        val before = clients.size
                        clients.removeAll { it.dead }
                        if (clients.size != before) notifyClientCount()
                        if (clients.isNotEmpty()) snapshot = ArrayList(clients)
                    }
                    if (snapshot == null) continue // 无活跃接收端，等下一帧
                    for (c in snapshot!!) {
                        synchronized(c.lock) {
                            try {
                                c.output.writeInt(q.bytes.size)
                                c.output.writeByte(if (q.keyframe) 1 else 0)
                                c.output.write(q.bytes)
                                c.output.flush()
                            } catch (_: Throwable) {
                                c.dead = true // 该路写失败，标记剔除
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                }
            }
        }
        th.isDaemon = true
        th.name = "tcp-v-fanout"
        th.start()
    }

    fun hasClient(): Boolean = synchronized(clients) { clients.isNotEmpty() }

    fun close() {
        running = false
        synchronized(clients) {
            clients.forEach { try { it.socket.close() } catch (_: Exception) {} }
            clients.clear()
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pending.clear()
        DiagLog.log("TCP 视频发送器已关闭")
    }

    companion object { private const val TAG = "MirrorCastV3" }
}
