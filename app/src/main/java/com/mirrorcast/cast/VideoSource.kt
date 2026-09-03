package com.mirrorcast.cast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.mirrorcast.util.DiagLog

/** 编码帧回调：frame 为 Annex-B 访问单元（含起始码） */
fun interface FrameListener {
    fun onFrame(frame: ByteArray, keyframe: Boolean, ptsUs: Long)
}

/**
 * 视频源：VirtualDisplay → 编码器输入 Surface（零拷贝）→ 帧回调。
 * 发送端零像素处理（CPU≈0），翻转移至接收端；关键帧即时标记，供接收端重同步。
 */
class VideoSource(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitrate: Int = 10_000_000,
    private val useVbr: Boolean = true,
    private val keyIntervalSec: Float = 0.5f
) {

    var frameListener: FrameListener? = null

    private var thread: HandlerThread? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private val encInfo = MediaCodec.BufferInfo()
    private var encSurface: Surface? = null
    private var encodeThread: Thread? = null
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null

    @Volatile var running = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var encodedFrames = 0L
        private set
    @Volatile var encodedBytes = 0L
        private set

    // ---- 诊断统计（本 App 进程视角） ----
    /** 编码耗时滑动均值（毫秒）：最近一帧编码输出耗时 */
    @Volatile var lastEncodeMs = 0.0
        private set
    /** 实际测得码率（kbps）：滚动窗口内（约最近1s）编码字节数换算 */
    @Volatile var measuredBitrate = 0
        private set

    // 码率滚动窗口（仅在编码线程访问，无需加锁）
    private var bitrateWinBytes = 0L
    private var bitrateWinFrames = 0
    private var bitrateWinStart = System.nanoTime()

    // ---- 静止画面自适应降帧（仅在编码线程访问） ----
    // 帧很小=P帧内容变化少≈静止。连续多帧很小则下调目标帧率省资源；画面动起来再升回。
    private var staticCount = 0
    private var currentAdaptiveFps = 0        // 0=未设置过
    private val STATIC_TINY = 1800            // 单帧字节低于此视为"几乎静止"
    private val STATIC_TRIGGER = 12           // 连续这么多帧静止则降帧

    private var lastErrLogMs = 0L

    fun start(): Boolean {
        return try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyIntervalSec.toInt().coerceAtLeast(1)) // 关键帧间隔(秒)
            // 强制 Profile/Level：让编码器在关键帧内携带 SPS/PPS（MediaTek 需显式指定才给参数）
            try { fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) } catch (_: Exception) {}
            try { fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel32) } catch (_: Exception) {}
            // VBR（可变码率）：相机/动态画面细节多时质量更稳，缓解马赛克；静态画面自动降低码率
            try {
                fmt.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    if (useVbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 30) {
                try { fmt.setInteger(MediaFormat.KEY_LATENCY, 1) } catch (_: Exception) {}
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // 记录编码器硬件名（证明硬编生效，因 Android 无 GPU 占用 API）
            try { DiagLog.log("编码器名: " + enc.name) } catch (_: Throwable) {}
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encSurface = enc.createInputSurface()
            enc.start()
            // 请求首关键帧立即输出（配置帧随 IDR 出）
            try { val b = android.os.Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); enc.setParameters(b) } catch (_: Exception) {}
            encoder = enc

            val ht = HandlerThread("source").also { it.start() }
            thread = ht
            try {
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        DiagLog.log("投影被系统停止")
                        stop()
                    }
                }, Handler(ht.looper))
            } catch (_: Throwable) {}

            virtualDisplay = projection.createVirtualDisplay(
                "V3Source", width, height, 240,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encSurface!!, null, Handler(ht.looper)
            )
            running = true
            DiagLog.log("视频源启动：${width}x${height} @${frameRate}fps ${bitrate / 1000}kbps ${if (useVbr) "VBR" else "CBR"} 关键帧${keyIntervalSec}s")
            startEncodeThread()
            true
        } catch (t: Throwable) {
            logErr(t)
            stop()
            false
        }
    }

    private fun startEncodeThread() {
        encodeThread = Thread {
            while (running) {
                try {
                    val enc = encoder ?: break
                    when (val idx = enc.dequeueOutputBuffer(encInfo, 100_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // 提取 SPS/PPS（仅此一次；多数编码器后续帧不再携带）
                            try {
                                val fmt = enc.outputFormat
                                csd0 = readCsd(fmt, "csd-0")
                                csd1 = readCsd(fmt, "csd-1")
                                DiagLog.log("视频源格式就绪: csd0=" + csd0?.size + " csd1=" + csd1?.size + " csd0首字节=" + (csd0?.firstOrNull()?.toInt()?.and(0x1f) ?: -1) + " 期望7=SPS")
                            } catch (e: Throwable) {
                                DiagLog.log("csd 提取失败：${e.javaClass.simpleName}")
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        in Int.MIN_VALUE until 0 -> {}
                        else -> {
                            val t0 = System.nanoTime()
                            val buf = enc.getOutputBuffer(idx)
                            if (buf != null) {
                                val bytes = ByteArray(encInfo.size)
                                buf.get(bytes)
                                encodedFrames++
                                encodedBytes += bytes.size
                                // 滑动窗口测实际码率（约最近 1s，最多 60 帧）
                                bitrateWinBytes += bytes.size
                                bitrateWinFrames++
                                if (bitrateWinFrames >= 30 || System.nanoTime() - bitrateWinStart > 1_000_000_000L) {
                                    val spanS = (System.nanoTime() - bitrateWinStart) / 1_000_000_000.0
                                    if (spanS > 0) measuredBitrate = (bitrateWinBytes * 8L / spanS / 1000).toInt().coerceAtLeast(0)
                                    bitrateWinBytes = 0; bitrateWinFrames = 0; bitrateWinStart = System.nanoTime()
                                }
                                val key = isKeyframe(bytes)
                                // 静止/动态自适应降帧：小帧连续超过阈值则降帧，反之恢复
                                if (key) {
                                    staticCount = 0 // 关键帧算"变化"，重置静电计数
                                } else {
                                    if (bytes.size < STATIC_TINY) {
                                        if (staticCount < STATIC_TRIGGER) staticCount++
                                        // 持续静止：降到最低档（约原帧率的 1/3，至少 ≥5）
                                        val target = (frameRate / 3).coerceAtLeast(5)
                                        if (staticCount >= STATIC_TRIGGER && currentAdaptiveFps != target) {
                                            currentAdaptiveFps = target
                                            try {
                                                val b = android.os.Bundle()
                                                b.putInt(MediaFormat.KEY_FRAME_RATE, target)
                                                encoder?.setParameters(b)
                                            } catch (_: Exception) {}
                                        }
                                    } else {
                                        // 画面在动：恢复原帧率
                                        staticCount = 0
                                        if (currentAdaptiveFps != 0 && currentAdaptiveFps != frameRate) {
                                            currentAdaptiveFps = frameRate
                                            try {
                                                val b = android.os.Bundle()
                                                b.putInt(MediaFormat.KEY_FRAME_RATE, frameRate)
                                                encoder?.setParameters(b)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                                // 修复：SPS/PPS 可能出现在任意 AU 开头（尤其 format-change 后首帧），对每帧都尝试提取
                                if (csd0 == null) {
                                    extractSpsPps(bytes)
                                }
                                val out = if (key) withCsd(bytes) else bytes
                                frameListener?.onFrame(out, key, encInfo.presentationTimeUs)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            lastEncodeMs = (System.nanoTime() - t0) / 1_000_000.0
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    if (!running) break // 停止阶段的竞态不记录
                    logErr(t)
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                    // 编码器致命错误（如 CodecException）：停止并释放资源
                    if (running && t is android.media.MediaCodec.CodecException) {
                        DiagLog.log("编码器故障，自动停止：${t.diagnosticInfo}")
                        stop()
                        break
                    }
                }
            }
        }.apply { isDaemon = true; name = "src-enc"; start() }
    }

    /** 安全读取 csd buffer：rewind 读，并校验 NAL 类型（空壳返回 null，交由关键帧提取兜底） */
    private fun readCsd(fmt: android.media.MediaFormat, key: String): ByteArray? {
        return try {
            val buf = fmt.getByteBuffer(key) ?: return null
            val dup = buf.duplicate()
            dup.rewind()
            val out = ByteArray(dup.remaining())
            dup.get(out)
            if (out.isEmpty()) return null
            val type = out[0].toInt() and 0x1f
            if (type != 7 && type != 8) {
                DiagLog.log("csd 非 SPS/PPS(空壳)，转关键帧提取兜底")
                return null
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /** 从关键帧的 Annex-B 流里扫描 SPS(7)/PPS(8) NAL 并缓存（format csd 为空时的兜底） */
    private fun extractSpsPps(au: ByteArray) {
        val n = au.size
        var i = 0
        while (i + 3 < n) {
            var scLen = 0
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
                scLen = 3
            } else if (i + 4 < n && au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte()) {
                scLen = 4
            }
            if (scLen > 0) {
                val payloadStart = i + scLen
                if (payloadStart < n) {
                    val type = au[payloadStart].toInt() and 0x1f
                    if (type == 7 || type == 8) {
                        // 找该 NAL 结束（下一起始码或末尾）
                        var end = payloadStart
                        var j = payloadStart + 1
                        while (j + 3 < n) {
                            if (au[j] == 0.toByte() && au[j + 1] == 0.toByte() && (au[j + 2] == 1.toByte() || (j + 3 < n && au[j + 2] == 0.toByte() && au[j + 3] == 1.toByte()))) {
                                end = j
                                break
                            }
                            j++
                        }
                        val payload = if (end <= payloadStart) au.copyOfRange(payloadStart, n) else au.copyOfRange(payloadStart, end)
                        if (type == 7 && csd0 == null) {
                            csd0 = payload
                            DiagLog.log("从关键帧提取 SPS，size=" + payload.size)
                        } else if (type == 8 && csd1 == null) {
                            csd1 = payload
                            DiagLog.log("从关键帧提取 PPS，size=" + payload.size)
                        }
                        i = if (end > payloadStart) end else (payloadStart + 1)
                        continue
                    }
                }
            }
            i++
        }
    }

    private fun withCsd(frame: ByteArray): ByteArray {
        val sps = csd0 ?: return frame
        val pps = csd1
        val spsLen = sps.size + 4
        val ppsLen = (pps?.size ?: 0) + 4
        val out = ByteArray(spsLen + ppsLen + frame.size)
        // 00 00 00 01 + SPS
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(sps, 0, out, 4, sps.size)
        var off = spsLen
        if (pps != null) {
            out[off] = 0; out[off + 1] = 0; out[off + 2] = 0; out[off + 3] = 1
            System.arraycopy(pps, 0, out, off + 4, pps.size)
            off += ppsLen
        }
        System.arraycopy(frame, 0, out, off, frame.size)
        return out
    }

    /** 请求立即输出关键帧（供新接收端连接时调用，加快首帧/重同步） */
    fun requestSyncFrame() {
        try {
            val b = android.os.Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(b)
        } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        thread?.quitSafely()
        thread = null
        encodeThread?.interrupt()
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        encSurface = null
        try { projection.stop() } catch (_: Exception) {}
        DiagLog.log("视频源已停止")
    }

    private fun logErr(t: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastErrLogMs > 2000) {
            lastErrLogMs = now
            Log.e(TAG, "source error", t)
            DiagLog.log("视频源错误：${t.javaClass.simpleName}: ${t.message ?: ""}")
            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}"
        }
    }

    companion object {
        private const val TAG = "MirrorCastV3"

        /** 判定访问单元是否为关键帧：首个 NAL 类型 == 5（IDR）；SPS/PPS(7/8) 会随关键帧同来 */
        fun isKeyframe(au: ByteArray): Boolean {
            var i = 0
            while (i + 4 <= au.size) {
                // 找起始码 00 00 01 或 00 00 00 01
                if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte()) {
                    val type = au.getOrNull(i + 4)?.toInt()?.and(0x1F) ?: return false
                    // 跳过 SPS(7)/PPS(8)/SEI(6)，直到 IDR(5) 或非配置 NAL
                    if (type == 5) return true
                    if (type == 7 || type == 8 || type == 6) { i += 4; continue }
                    return false
                }
                if (i + 3 <= au.size && au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
                    val type = au.getOrNull(i + 3)?.toInt()?.and(0x1F) ?: return false
                    if (type == 5) return true
                    if (type == 7 || type == 8 || type == 6) { i += 3; continue }
                    return false
                }
                i++
            }
            return false
        }
    }
}