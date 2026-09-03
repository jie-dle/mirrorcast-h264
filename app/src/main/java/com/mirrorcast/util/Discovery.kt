package com.mirrorcast.util

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 局域网自发现（V0.3.3）：
 *  - 发送端周期性 UDP 广播自身存在（8092 端口，2s 一次）
 *  - 接收端监听广播，维护在线发送端列表（5s 未见广播自动剔除）
 * 仅用于"发现并展示"，配合 UI 人工点选配对；本组件不做任何自动连接。
 */
object Discovery {

    private const val PORT = 8092
    private const val INTERVAL_MS = 2000L
    private const val TTL_MS = 5000L
    private const val PREFIX = "MirrorCastV3|SEND|"
    private val buf = ByteArray(1024)

    data class Device(val name: String, val ip: String, val lastSeen: Long)

    @Volatile private var broadcastRun = false
    @Volatile private var listenRun = false
    private var broadcaster: Thread? = null
    private var listener: Thread? = null
    @Volatile private var onDevices: ((List<Device>) -> Unit)? = null

    /** 发送端：开始周期性广播（设备名）。幂等。 */
    fun startBroadcast(deviceName: String) {
        if (broadcastRun) return
        broadcastRun = true
        broadcaster = Thread {
            try {
                val sock = DatagramSocket().apply { broadcast = true; reuseAddress = true }
                val payload = (PREFIX + deviceName).toByteArray(Charsets.UTF_8)
                val target = InetAddress.getByName("255.255.255.255")
                val pkt = DatagramPacket(payload, payload.size, target, PORT)
                while (broadcastRun) {
                    try { sock.send(pkt) } catch (_: Throwable) {}
                    try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { break }
                }
                sock.close()
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "disc-bcast"; start() }
    }

    /** 接收端：开始监听广播并回调在线列表（含超时剔除）。幂等（换回调直接替换）。 */
    fun startListening(cb: (List<Device>) -> Unit) {
        onDevices = cb
        if (listenRun) return
        listenRun = true
        listener = Thread {
            val seen = linkedMapOf<String, Device>() // ip -> device，保持到达顺序
            try {
                val sock = DatagramSocket(PORT)
                sock.soTimeout = 1000 // 1s 超时：保证停止/剔除循环及时
                val pkt = DatagramPacket(buf, buf.size)
                while (listenRun) {
                    try {
                        try { sock.receive(pkt) } catch (_: SocketTimeoutException) {}
                        if (pkt.length > 0) {
                            val txt = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                            if (txt.startsWith(PREFIX)) {
                                val ip = pkt.address?.hostAddress
                                if (ip != null) {
                                    val name = txt.removePrefix(PREFIX).ifBlank { ip }
                                    seen[ip] = Device(name, ip, System.currentTimeMillis())
                                }
                            }
                        }
                    } catch (_: Throwable) { if (!listenRun) break }
                    val now = System.currentTimeMillis()
                    seen.entries.removeAll { now - it.value.lastSeen > TTL_MS }
                    onDevices?.invoke(seen.values.toList())
                }
                sock.close()
            } catch (_: Throwable) {}
            listenRun = false
        }.apply { isDaemon = true; name = "disc-listen"; start() }
    }

    fun stopAll() {
        broadcastRun = false
        listenRun = false
        broadcaster?.interrupt(); broadcaster = null
        listener?.interrupt(); listener = null
        onDevices = null
    }
}