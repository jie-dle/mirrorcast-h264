package com.mirrorcast.cast.transport

import android.util.Log
import com.mirrorcast.util.DiagLog
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * 控制通道客户端（接收端一侧）：连接发送端控制服务器，发 HELLO 拿会话参数，支持 STOP。
 */
class ControlClient(private val ip: String, private val port: Int) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    /** 握手成功回调（携带发送端回应的会话参数） */
    var onReady: ((JSONObject) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    /** 发送端下发『开始投屏』信号（携带 width/height） */
    var onSessionStart: ((Int, Int) -> Unit)? = null
    /** 发送端下发『结束投屏』信号 */
    var onSessionEnd: (() -> Unit)? = null
    /** 连接状态回调：true=已握手，false=断开/失败 */
    var onState: ((Boolean) -> Unit)? = null
    /** 对端（发送端）身份信息，握手后填充 */
    @Volatile var peerRole: String = ""
    @Volatile var peerName: String = ""

    @Volatile private var running = false
    /** 串行化 input 读取／output 写入：listen 与 latency 线程共享同一 socket */
    private val ioLock = Any()

    /** 连接并 HELLO；成功返回 true */
    fun connect(name: String, role: String): Boolean {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(java.net.InetSocketAddress(ip, port), 3000)
            socket = s
            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(s.getOutputStream())
            this.input = input
            this.output = output
            ControlServer.writeMsg(output, JSONObject().put("cmd", "HELLO").put("name", name).put("role", role))
            val ack = ControlServer.readMsg(input) ?: throw java.io.IOException("握手无响应")
            // 解析对端（发送端）身份，供界面展示"对方是谁"
            peerRole = ack.optString("serverRole", "")
            peerName = ack.optString("serverName", "发送端")
            DiagLog.log("控制握手成功：${ack.toString()}")
            onReady?.invoke(ack)
            onState?.invoke(true)
            running = true
            // 监听服务器侧推送（如发送端主动 STOP）
            val listenThread = Thread {
                while (running) {
                    try {
                        val m: JSONObject?
                        synchronized(ioLock) { m = ControlServer.readMsg(input) }
                        if (m == null) break
                        when (m.optString("cmd", "")) {
                            "SESSION_START" -> {
                                val w = m.optInt("width", 720)
                                val h = m.optInt("height", 1280)
                                DiagLog.log("发送端指示开始投屏: " + w + "x" + h)
                                onSessionStart?.invoke(w, h)
                            }
                            "SESSION_END" -> {
                                DiagLog.log("发送端指示结束投屏")
                                onSessionEnd?.invoke()
                            }
                            "STOP" -> {
                                DiagLog.log("发送端已停止")
                                onDisconnect?.invoke()
                                onState?.invoke(false)
                                break
                            }
                        }
                    } catch (t: Throwable) {
                        if (running) Log.w(TAG, "read", t)
                        onState?.invoke(false)
                        break
                    }
                }
            }
            listenThread.isDaemon = true
            listenThread.name = "ctrl-cli"
            listenThread.start()
            true
        } catch (t: Throwable) {
            DiagLog.log("控制连接失败：${t.javaClass.simpleName}: ${t.message ?: ""}")
            onState?.invoke(false)
            close()
            false
        }
    }

    fun sendStop() {
        try {
            output?.let { synchronized(ioLock) { ControlServer.writeMsg(it, JSONObject().put("cmd", "STOP")) } }
        } catch (_: Exception) {
        }
    }

    fun close() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        DiagLog.log("控制客户端已关闭")
    }

    companion object {
        private const val TAG = "MirrorCastV3"
    }
}