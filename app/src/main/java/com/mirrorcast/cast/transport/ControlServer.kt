package com.mirrorcast.cast.transport

import android.util.Log
import com.mirrorcast.util.DiagLog
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 控制通道（TCP，发送端一侧驻留）：接收端的指令（HELLO/STOP）→ 应答 JSON。
 * 消息格式：4 字节大端长度 + UTF-8 JSON。
 */
class ControlServer(private val port: Int) {

    /** 有新 HELLO 时回调（可返回 false 拒绝）；null 表示接受并应 HELLO_ACK */
    var onHello: ((JSONObject) -> JSONObject)? = null
    /** 向对端反向暴露的本端身份：SEND / RECV */
    var localRole: String = "SEND"
    /** 本端设备名（多机场景辨识） */
    var localDeviceName: String = "发送端"
    /** 收到 STOP 时回调 */
    var onStop: (() -> Unit)? = null

    // 每个已连接接收端的输出流（用于发送端广播控制信号）
    private val clientOutputs = ArrayList<DataOutputStream>()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    private val clientSockets = ArrayList<Socket>()

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket(port)
            running = true
            acceptThread = Thread {
                while (running) {
                    try {
                        val s = serverSocket?.accept() ?: break
                        synchronized(clientSockets) { clientSockets.add(s) }
                        handle(s)
                    } catch (t: Throwable) {
                        if (running) Log.w(TAG, "accept", t)
                    }
                }
            }.apply { isDaemon = true; name = "ctrl-srv"; start() }
            DiagLog.log("控制服务器就绪：:$port")
            true
        } catch (t: Throwable) {
            DiagLog.log("控制服务器启动失败：${t.javaClass.simpleName}: ${t.message ?: ""}")
            false
        }
    }

    private fun handle(s: Socket) {
        var output: DataOutputStream? = null
        try {
            s.tcpNoDelay = true
            val input = DataInputStream(s.getInputStream())
            output = DataOutputStream(s.getOutputStream())
            synchronized(clientOutputs) { clientOutputs.add(output!!) }
            val hello = readMsg(input)
            if (hello == null) return
            val cmd = hello.optString("cmd", "")
            when (cmd) {
                "HELLO" -> {
                    DiagLog.log("接收端接入：${hello.optString("name", "?")}")
                    val peerRole = hello.optString("role", "RECV")
                    val ack = (onHello?.invoke(hello) ?: JSONObject().put("cmd", "HELLO_ACK"))
                        .put("role", peerRole)
                        .put("name", hello.optString("name", "?"))
                        .put("serverRole", localRole)
                        .put("serverName", localDeviceName)
                    output?.let { writeMsg(it, ack) }
                    // 保持连接以接收后续指令
                    while (running && s.isConnected) {
                        val m = readMsg(input) ?: break
                        when (m.optString("cmd", "")) {
                            "STOP" -> {
                                DiagLog.log("接收端请求停止")
                                onStop?.invoke()
                                output?.let { writeMsg(it, JSONObject().put("cmd", "STOP_ACK")) }
                                break
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ctrl conn error", t)
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
            synchronized(clientSockets) { clientSockets.remove(s) }
            synchronized(clientOutputs) { output?.let { clientOutputs.remove(it) } }
        }
    }

    /** 发送端向所有已连接接收端广播控制信号（如 SESSION_START/END） */
    fun broadcast(cmd: String, extra: JSONObject? = null) {
        val msg = JSONObject().put("cmd", cmd)
        if (extra != null) {
            val it = extra.keys()
            while (it.hasNext()) { val k = it.next(); msg.put(k, extra.get(k)) }
        }
        synchronized(clientOutputs) {
            clientOutputs.removeAll { out ->
                try { writeMsg(out, msg); false } catch (e: Exception) { true }
            }
        }
        DiagLog.log("控制广播: " + cmd)
    }

    fun stop() {
        running = false
        try {
            synchronized(clientSockets) {
                clientSockets.forEach { it.close() }
                clientSockets.clear()
            }
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        DiagLog.log("控制服务器已关闭")
    }

    companion object {
        private const val TAG = "MirrorCastV3"

        fun readMsg(input: DataInputStream): JSONObject? {
            val len = try {
                input.readInt()
            } catch (_: Exception) {
                return null
            }
            if (len <= 0 || len > 64 * 1024) return null
            val buf = ByteArray(len)
            input.readFully(buf)
            return try {
                JSONObject(String(buf, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }

        fun writeMsg(output: DataOutputStream, msg: JSONObject) {
            val data = msg.toString().toByteArray(Charsets.UTF_8)
            output.writeInt(data.size)
            output.write(data)
            output.flush()
        }
    }
}