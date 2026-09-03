package com.mirrorcast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mirrorcast.cast.*
import com.mirrorcast.cast.transport.*
import com.mirrorcast.util.DiagLog
import com.mirrorcast.util.NetInfo
import com.mirrorcast.util.Discovery
import kotlinx.coroutines.delay

/**
 * MirrorCast V0.3 主界面（纯 H.264 双机投屏）。
 *
 * 角色：
 *   - 发送端（SEND）：采集手机屏幕(相机)→H.264硬编→经 TCP 推给接收端。监听端口等待接收端连接，无需填 IP。
 *   - 接收端（RECV）：TCP 收视频流→H.264硬解→TextureView 显示。需填发送端 IP。
 *   - 回环（loopback）：同一台设备即发即收，用于调试（127.0.0.1）。
 *
 * 传输：视频 = TCP（TcpVideoSender/Receiver，[4B长度][帧]），控制 = TCP(JSON)。
 * 编码：MediaCodec Surface 输入硬编（CBR）；接收端硬解。
 */
class MainActivity : ComponentActivity() {

    /** 角色：SEND=发送端, RECV=接收端 */
    enum class Role { SEND, RECV }

    // ---------- 状态字段（@Volatile 跨线程可见） ----------
    // MediaProjection 授权 token（经由 Phase0Service 来到 CastBus 后转存此处）
    @Volatile private var pendingProjection: MediaProjection? = null
    // 接收端 TextureView 的 Surface（MediaCodec 解码输出目标）
    @Volatile private var previewSurface: Surface? = null
    // 当前角色（持久化记忆）
    @Volatile private var currentRole: Role = Role.SEND
    // 对端 IP（持久化记忆）
    @Volatile private var currentIp = "127.0.0.1"
    // 是否同机回环调试
    @Volatile private var currentLoopback = true
    // 接收端是否已请求启动（等 Surface 就绪后补起接收）
    @Volatile private var receiverStartPending = false
    // 镜像开关（接收端显示层翻转）
    @Volatile private var mirrorEnabled = true
    // 画质档位 0=720p 1=1080p 2=1440p（发送端编码分辨率 + 码率上限）
    @Volatile private var currentQuality = 1
    // 编码帧率档位（24/30/45/60），默认 30（"动作不卡"主目标）
    @Volatile private var currentFrameRate = 30
    // 实际视频宽高比（VideoSink 解码后回调更新，用于预览适配；默认竖屏9:16）
    @Volatile private var currentVideoRatio = 9f / 16f

    // 连接状态机：0=未连接, 1=正在连接/等待发送端, 2=已连接, 3=投屏中
    // （由接收端 ControlClient/TcpVideoReceiver 状态回调驱动）
    @Volatile private var connState = 0
    // 发送端当前已接入的接收端数量（TcpVideoSender 回调更新）
    @Volatile private var senderClientCount = 0


    // ---------- 组件引用（各自有 start/stop 生命周期） ----------
    // 发送端
    private var controlServer: ControlServer? = null   // TCP 控制服务器(8090)，接收端控制指令
    private var tcpSender: TcpVideoSender? = null      // TCP 视频发送(8091)，监听接收端 connect
    private var videoSource: VideoSource? = null       // H.264 采集+硬编
    // 接收端
    private var controlClient: ControlClient? = null   // TCP 控制客户端，连发送端 8090
    private var tcpReceiver: TcpVideoReceiver? = null  // TCP 视频接收，连发送端 8091
    private var videoSink: VideoSink? = null           // H.264 硬解 + 渲染到预览 Surface

    /**
     * 应用启动：读取持久化记忆(IP/角色/回环/镜像/画质)，并记录本机 IP。
     * 用户首次使用需手动配置一次（选角色、填 IP），之后重启自动恢复。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("mc_v03", Context.MODE_PRIVATE)
        // 以下为该 APP 的"配置记忆"，用 SharedPreferences 保证重启不丢
        currentIp = prefs.getString("peer_ip", "127.0.0.1") ?: "127.0.0.1"
        currentRole = if (prefs.getString("role", "SEND") == "RECV") Role.RECV else Role.SEND
        currentLoopback = prefs.getBoolean("loopback", true)
        mirrorEnabled = prefs.getBoolean("mirror", true)
        currentQuality = prefs.getInt("quality", 1)
        currentFrameRate = prefs.getInt("frame_rate", 30)
        DiagLog.log("V0.3 H.264 双机启动 本机IP=" + (NetInfo.wifiIpv4(this) ?: "未知"))
        setContent { MainScreen() }
    }

    override fun onDestroy() {
        // Activity 销毁：停止所有推流/接收组件、释放 Surface 与投影 token
        stopAll()
        previewSurface?.release(); previewSurface = null
        pendingProjection?.stop(); pendingProjection = null
        stopService(Intent(this, Phase0Service::class.java))
        super.onDestroy()
    }

    // ================ 发送端 ================
    /**
     * 启动发送端：
     *   - ControlServer：监听 8090，响应接收端控制(STOP等)
     *   - TcpVideoSender：监听 8091，接收端 connect 后推视频
     *   - VideoSource：VirtualDisplay→H.264硬编→帧回调→tcpSender 发送
     * 分辨率/码率由画质档位(currentQuality)决定：1=1080p@14M, 0=720p@8M。
     * 注意：发送端是监听模式，接收端会主动 connect，因此这里不关心 ip 参数实际值。
     */
    private fun startSender(proj: MediaProjection, ip: String) {
        stopSender()
        val cs = ControlServer(CTRL_PORT).apply {
            localRole = "SEND"
            // 发送端若已知对端IP，用 IP 做设备名提示；否则用默认
            localDeviceName = "发送端(" + ip + ")"
            onStop = { runOnUiThread { stopAll() } }
        }
        val tsv = TcpVideoSender(VIDEO_PORT).apply {
            onConnected = { runOnUiThread { videoSource?.requestSyncFrame() } }
            onClientCount = { n -> runOnUiThread { senderClientCount = n } }
        }
        // 【关键】发送端虚拟屏必须按"发送手机屏幕真实比例"建，否则 MediaProjection 会拉伸变形。
        // 读取本机屏幕物理尺寸比例（宽:高），按其建虚拟屏 → 采集画面不变形。
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val realW = dm.widthPixels.coerceAtLeast(1)
        val realH = dm.heightPixels.coerceAtLeast(1)
        // 目标基准宽：0=720p 1=1080p 2=1440p，高度按真实屏幕比例缩放
        val baseW = when (currentQuality) { 0 -> 720; 1 -> 1080; else -> 1440 }
        val srcW = baseW
        val srcH = ((srcW.toLong() * realH) / realW / 2L * 2L).toInt().coerceAtLeast(2)
        // 码率（VBR 时作为目标码率，随分辨率提高；帧率越高可适当提高）
        val baseBitrate = when (currentQuality) { 0 -> 10_000_000; 1 -> 22_000_000; else -> 24_000_000 }
        val fpsScale = if (currentFrameRate > 30) (currentFrameRate.toDouble() / 30.0) else 1.0
        val targetBitrate = (baseBitrate * fpsScale).toInt().coerceAtMost(32_000_000)
        // V0.3.4 改回 VBR：V0.4 实证「VBR 超发 + 队列8 + 保关键帧」组合收丢仅 3 帧、无花屏；
        // VBR 保留高动态画质（超发借码）与静态省码率/省电；目标码率维持 22M（高动态更清晰，峰值更高但队列 8 兜底）
        val vbrMode = true
        DiagLog.log("发送端屏幕真实比例: " + realW + "x" + realH + " → 编码 " + srcW + "x" + srcH + " @${currentFrameRate}fps ${if (vbrMode) "VBR" else "CBR"} ${targetBitrate / 1000}kbps")
        val vs = VideoSource(proj, srcW, srcH, currentFrameRate, targetBitrate, useVbr = vbrMode, keyIntervalSec = 0.5f).apply {
            frameListener = com.mirrorcast.cast.FrameListener { f, k, p -> tsv.sendFrame(f, k, p) }
        }
        val ok = cs.start() && tsv.start() && vs.start()
        if (ok) { controlServer = cs; tcpSender = tsv; videoSource = vs
            DiagLog.log("发送端已启动 (H264 " + srcW + "x" + srcH + ") → TCP :" + VIDEO_PORT)
            // 发送端主导：告知接收端开始接收，并下发编码尺寸用于比例对齐
            val extra = org.json.JSONObject().put("width", srcW).put("height", srcH)
            cs.broadcast("SESSION_START", extra)
        } else { cs.stop(); tsv.close(); vs.stop() }
    }

    private fun stopSender() {
        // 通知接收端结束投屏
        try { controlServer?.broadcast("SESSION_END") } catch (_: Exception) {}
        try { Thread.sleep(50) } catch (_: Exception) {}
        videoSource?.stop(); videoSource = null
        tcpSender?.close(); tcpSender = null
        controlServer?.stop(); controlServer = null
        senderClientCount = 0
    }

    // ================ 接收端 ================
    /**
     * 启动接收端：
     *   - TcpVideoReceiver：connect 发送端 8091 收视频帧
     *   - VideoSink：H.264 硬解 → 渲染到 previewSurface(TextureView)
     *   - ControlClient：connect 发送端 8090 控制通道
     * onFormatReady 回调会把实际视频宽高比写入 currentVideoRatio，供预览缩放适配。
     */
    /**
     * 接收端：建立视频接收 + 控制连接。
     * 主导模型下：接收端先建好视频接收(recv+sink)，连上控制后，实际开流由发送端 SESSION_START 触发。
     * 为兼容"回环/手动直接接收"，视频接收立即启动；SESSION_START 时仅确保已就绪。
     */
    private fun startReceiver(ip: String) {
        stopReceiver()
        receiverStartPending = false
        connState = 1 // 正在连接/等待发送端
        val recv = TcpVideoReceiver(ip, VIDEO_PORT).apply {
            onState = { connected ->
                // 视频通道连上/断开不改变"已握手"主状态，仅作日志
                runOnUiThread { if (connected) DiagLog.log("视频通道已连上") }
            }
        }
        val sink = VideoSink(720, 1280, previewSurface ?: run { recv.close(); return }).apply {
            onFormatReady = { w, h -> if (h > 0) currentVideoRatio = w.toFloat() / h }
        }
        recv.onFrame = { f, k, p -> sink.push(f, k, p) }
        val started = sink.start()
        // TcpVideoReceiver.start() 为无限重连式，只要没取消就一直尝试
        if (started) tcpReceiver = recv
        videoSink = sink
        val client = ControlClient(ip, CTRL_PORT).apply {
            onSessionStart = { w, h ->
                // 发送端指示开始：视频接收已就绪；若发送端带了比例用发送端的
                runOnUiThread {
                    if (h > 0) currentVideoRatio = w.toFloat() / h
                    connState = 3 // 投屏中
                }
            }
            onSessionEnd = { runOnUiThread { connState = 2 } }
            onState = { ok ->
                runOnUiThread {
                    if (ok) connState = 2 // 已连接（等待发送端开始信号）
                    else if (connState >= 2) connState = 1 // 控制断开，回到重连态
                }
            }
            onDisconnect = { runOnUiThread { connState = 1; DiagLog.log("控制通道断开，视频仍在重试…") } }
        }
        // 可控连接立即建立（无论成败视频通道都已进入重连）
        client.connect("接收端", "RECV")
        controlClient = client
        // 视频通道在后台无限重连收流
        recv.start()
        DiagLog.log("接收端启动：控制通道已连接、视频通道后台重连中")
    }

    private fun stopReceiver() {
        controlClient?.close(); controlClient = null
        tcpReceiver?.close(); tcpReceiver = null
        videoSink?.stop(); videoSink = null
        connState = 0
    }

    private fun stopAll() {
        receiverStartPending = false
        stopSender()
        stopReceiver()
        connState = 0
    }

    private fun runBg(block: () -> Unit) {
        val th = Thread { try { block() } catch (t: Throwable) { DiagLog.log("后台异常: " + t.javaClass.simpleName) } }
        th.isDaemon = true; th.name = "net-start"; th.start()
    }

    private fun currentSent(): Long = videoSource?.encodedFrames ?: 0L
    private fun currentRendered(): Long = videoSink?.renderedFrames ?: 0L
    private fun isAnyRunning(): Boolean = (videoSource?.running == true || videoSink?.running == true)

    /** 本 App 进程 CPU 占用率（%）：程序 CPU 时间 / 实时墙钟时间，取最近约 1s 增量 */
    @Volatile private var lastCpuInfo = 0L
    @Volatile private var lastCpuRefNano = 0L
    @Volatile private var lastCpuPct = -1.0

    /**
     * 本 App 进程 CPU 占用率（%，占整机 CPU 的比例，0~100）。
     * 正确算法：取 /proc/self/stat 的 utime+stime（clock tick，Linux USER_HZ=100）
     * 增量换算为进程 CPU 纳秒，除以墙钟增量得到"进程占单个核的百分比"，
     * 再除以 CPU 核数，得到用户直观理解的"占整机 CPU 的百分比"。
     * 取滑动平均抗抖动；多核下避免虚高到 90%+。
     */
    private fun processCpuPercent(): Double {
        val nowNano = System.nanoTime()
        val stat = try { java.io.File("/proc/self/stat").readText() } catch (_: Throwable) { return -1.0 }
        // 进程 CPU 时间在第 14、15 字段（utime, stime），以 clock tick 计（Linux USER_HZ=100）
        val parts = stat.split(" ")
        if (parts.size < 15) return -1.0
        val utime = parts[13].toLongOrNull() ?: return -1.0
        val stime = parts[14].toLongOrNull() ?: return -1.0
        val cpuTicks = utime + stime
        // 1 tick = 1/100 秒 = 10ms = 10_000_000 ns
        val cpuNanos = cpuTicks * 10_000_000.0
        if (lastCpuInfo > 0) {
            val dCpu = cpuNanos - lastCpuInfo
            val dWall = (nowNano - lastCpuRefNano).toDouble()
            if (dWall > 0) {
                // 进程占单核百分比
                val single = (dCpu / dWall * 100.0).coerceIn(0.0, 100.0 * 8)
                // 除以核数 → 占整机比例；多核并行时不会虚高
                val nProc = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val whole = (single / nProc).coerceIn(0.0, 100.0)
                // 与上次滑动平均（各半），平滑瞬时抖动
                lastCpuPct = if (lastCpuPct >= 0) (lastCpuPct * 0.5 + whole * 0.5) else whole
            }
        }
        lastCpuInfo = cpuNanos.toLong(); lastCpuRefNano = nowNano
        return lastCpuPct
    }

    /** 电池温度（℃，可能有 0.1 精度，取整）。用系统粘性广播获取，兼容 minSdk */
    private fun batteryTemp(): Double {
        return try {
            val ift = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    ?: return -1.0
            val tenths = ift.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
            if (tenths <= 0) -1.0 else tenths / 10.0
        } catch (_: Throwable) { -1.0 }
    }

    /** 组装诊断行：编码方式/码率/帧率/编解码耗时/本进程CPU/电池温度 */
    private fun buildDiagLine(): String {
        val cpu = processCpuPercent()
        val temp = batteryTemp()
        val encMs = videoSource?.lastEncodeMs ?: -1.0
        val decMs = videoSink?.lastDecodeMs ?: -1.0
        val bitrate = videoSource?.measuredBitrate ?: 0
        val send = currentSent(); val render = currentRendered()
        val sb = StringBuilder()
        sb.append("编码:H.264/AVC 码率:${bitrate}kbps 发送帧:$send 渲染帧:$render")
        sb.append(" 发丢:${tcpSender?.droppedFrames ?: 0} 收丢:${videoSink?.droppedFrames ?: 0} 弃帧:${videoSink?.discardedFrames ?: 0} 解码错误:${videoSink?.decodeErrors ?: 0} 丢关键:${videoSink?.droppedKey ?: 0}")
        if (encMs >= 0) sb.append(" 编码耗时:%.1fms".format(encMs))
        if (decMs >= 0) sb.append(" 解码渲染:%.1fms".format(decMs))
        if (cpu >= 0) sb.append(" 本进程CPU:%.1f%%".format(cpu))
        if (temp >= 0) sb.append(" 电池:%.0f℃".format(temp))
        return sb.toString()
    }

    @Composable
    private fun MainScreen() {
        val context = LocalContext.current
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 按钮选中态配色：按钮本体填充低饱和主色调，字体不额外高亮
        val chipColors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0xFF1A1D24),   // 黑灰底（略比纯黑亮一点，避免和背景完全融为一体）
            labelColor = Color(0xFF9AA4B0),       // 未选中文字用灰（保持可读）
            selectedContainerColor = Color(0xFF62cfa7), // 低饱和青绿，按钮本体着色
            selectedLabelColor = Color(0xFF000000),   // ← 选中时文字色
        )

        var role by remember { mutableStateOf(currentRole) }
        var ip by remember { mutableStateOf(currentIp) }
        var loopback by remember { mutableStateOf(currentLoopback) }
        var mirrorState by remember { mutableStateOf(mirrorEnabled) }
        var zoomFill by remember { mutableStateOf(false) } // 手动放大(Crop)开关
        var quality by remember { mutableStateOf(currentQuality) }
        var frameRateSel by remember { mutableStateOf(currentFrameRate) }
        var videoRatio by remember { mutableStateOf(currentVideoRatio) } // 实际视频宽高比
        var running by remember { mutableStateOf(false) }
        var sentText by remember { mutableStateOf("--") }
        var renderText by remember { mutableStateOf("--") }
        var fpsText by remember { mutableStateOf("--") }
        var connText by remember { mutableStateOf("未连接") }
        var connOk by remember { mutableStateOf(false) }
        var connBusy by remember { mutableStateOf(false) }
        var senderCount by remember { mutableStateOf(0) }
        var diag by remember { mutableStateOf(DiagLog.dump()) }
        // 自发现：在线发送端列表（接收端角色点选配对）
        var devices by remember { mutableStateOf<List<Discovery.Device>>(emptyList()) }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                try {
                    val svc = Intent(context, Phase0Service::class.java)
                        .putExtra(Phase0Service.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(Phase0Service.EXTRA_RESULT_DATA, result.data)
                    ContextCompat.startForegroundService(context, svc)
                    DiagLog.log("已提交授权，等待服务处理…")
                } catch (t: Throwable) { DiagLog.log("启动授权失败") }
            } else { DiagLog.log("未授权录屏") }
            diag = DiagLog.dump()
        }

        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 自发现生命周期：发送端广播自身存在；接收端监听广播；回环模式不参与（保持本地联调）
        LaunchedEffect(role, loopback) {
            Discovery.stopAll()
            if (loopback) return@LaunchedEffect
            if (role == Role.SEND) Discovery.startBroadcast(Build.MODEL)
            else Discovery.startListening { devices = it }
        }

        /**
         * 主轮询循环（每 400ms）：
         *   1. 取投影：从 CastBus 拿授权 token，启动 startByMode()
         *   2. 取投影：从 CastBus 拿授权 token，启动 startByMode()
         *   3. 统计：同步 videoRatio、帧数、fps 到 UI 状态触发重组
         */
        LaunchedEffect(Unit) {
            var lastRendered = 0L
            var lastTime = System.currentTimeMillis()
            var diagTick = 0L
            while (true) {
                CastBus.projection?.let { proj ->
                    CastBus.projection = null
                    if (videoSource?.running != true) { pendingProjection = proj; runBg { startByMode() } } else proj.stop()
                }
                // 角色由人工通过列表点选/主按钮启动（V0.3.3 移除自动连接，防多设备误连）
                videoRatio = currentVideoRatio // 同步实际视频比例（触发重组去黑边）
                running = isAnyRunning()
                sentText = currentSent().toString()
                renderText = currentRendered().toString()
                // 连接状态 4 态显示
                connOk = (connState == 2 || connState == 3)
                connBusy = (connState == 1)
                connText = when {
                    connState == 3 -> "投屏中"
                    connState == 2 -> {
                        // 已连接时带上对端身份
                        val peer = controlClient?.peerName?.takeIf { it.isNotBlank() }
                        "已连接 @ " + (peer ?: currentIp)
                    }
                    connState == 1 -> "正在连接 / 等待发送端..."
                    else -> "未连接"
                }
                senderCount = senderClientCount
                val now = System.currentTimeMillis()
                val dt = (now - lastTime) / 1000.0
                if (dt >= 0.5) { fpsText = ((currentRendered() - lastRendered) / dt).toInt().toString(); lastRendered = currentRendered(); lastTime = now }
                // 诊断汇总：每约 2s 写入一行 DiagLog（含码率/帧率/编解码耗时/CPU/电池）
                diagTick++
                if (diagTick % 5 == 0L && running) {
                    DiagLog.log(buildDiagLine() + " fps=" + fpsText)
                }
                diag = DiagLog.dump()
                delay(400)
            }
        }

        DisposableEffect(Unit) { onDispose { stopAll() } }

        /**
         * 主按钮动作：
         *   - 正在运行 → 停止所有组件
         *   - 发送端/回环 → 发起录屏授权(Android14需每次授权，经由 Phase0Service)
         *   - 接收端 → 直接启动接收(无需授权)
         */
        fun startAction() {
            if (running) { stopAll(); return }
            if (role == Role.SEND || loopback) {
                pendingProjection = null; receiverStartPending = loopback
                DiagLog.log("请求录屏授权…")
                launcher.launch(mpm.createScreenCaptureIntent())
            } else { receiverStartPending = true; runBg { startReceiver(ip) } }
            diag = DiagLog.dump()
        }

        /** 自发现点选：设置目标 IP 并立即切换接收（L1 点选配对，无自动连接） */
        fun pickDevice(d: Discovery.Device) {
            ip = d.ip; currentIp = d.ip
            context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putString("peer_ip", d.ip).apply()
            DiagLog.log("发现设备点选: ${d.name} (${d.ip})")
            stopReceiver()
            receiverStartPending = true
            if (previewSurface != null) runBg { startReceiver(d.ip) }
            diag = DiagLog.dump()
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF06231F), background = Color(0xFF0E1116), onBackground = Color(0xFFE6E8EB), surface = Color(0xFF161B22), onSurface = Color(0xFFE6E8EB), surfaceVariant = Color(0xFF20262F), onSurfaceVariant = Color(0xFF9AA4B0), error = Color(0xFFF0716F), onError = Color(0xFF2B0A0A))) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("镜像投屏", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("H.264 硬件编码 （V0.3版）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("小布丁 & Deepseek v4 & Harness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))

                    // ==== 主操作按钮：大按钮，点击触发 startAction（开始/停止） ====
                    Button(onClick = { startAction() }, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (running) "■ 停止" else if (loopback) "开始回环联调" else if (role == Role.SEND) "开始投屏 (相机镜像)" else "接收投屏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))

                    // ==== 连接状态卡：显示连接状态标记 + 帧率统计 + 网络延迟 ====
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            // 状态：发送端根据接入的接收端数量动态显示；接收端用 4 态驱动
                            Text(
                                text = if (role == Role.SEND || loopback) {
                                    if (!running) "发送端 · 就绪"
                                    else if (senderCount > 0) "投屏中 · 已连 $senderCount 台接收端"
                                    else "投屏中 · 等待接收端接入"
                                } else {
                                    "● " + connText
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = when {
                                    role == Role.SEND || loopback -> MaterialTheme.colorScheme.onSurfaceVariant
                                    connOk -> MaterialTheme.colorScheme.primary
                                    connBusy -> Color(0xFFE6C86A) // 等待中：橙黄
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "发送 $sentText 帧 ｜ 渲染 $renderText 帧 ｜ 接收 $fpsText fps",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // 角色切换
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = role == Role.RECV && !loopback, onClick = { 
                            role = Role.RECV; loopback = false; currentRole = Role.RECV; currentLoopback = false
                            run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putString("role","RECV").putBoolean("loopback",false).apply() }
                            // 切到接收端：停掉旧发送端组件（V0.3.3 起不再自动启动，由列表点选/主按钮驱动）
                            stopAll()
                        }, colors = chipColors, label = { Text("接收端") })
                        FilterChip(selected = role == Role.SEND && !loopback, onClick = { role = Role.SEND; loopback = false; currentRole = Role.SEND; currentLoopback = false; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putString("role","SEND").putBoolean("loopback",false).apply() } }, colors = chipColors, label = { Text("发送端") })
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = loopback, onCheckedChange = { loopback = it; currentLoopback = it; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putBoolean("loopback", it).apply() } })
                        Text("同机回环联调", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))

                    // ==== 选项区按角色分区：发送端显示编码参数(分辨率/帧率)，接收端只显示镜像/放大(显示层) ====
                    if (role == Role.SEND || loopback) {
                        Text("分辨率（推荐1080P）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = quality == 0, onClick = { quality = 0; currentQuality = 0; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putInt("quality",0).apply() } }, colors = chipColors, label = { Text("720p") })
                            FilterChip(selected = quality == 1, onClick = { quality = 1; currentQuality = 1; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putInt("quality",1).apply() } }, colors = chipColors, label = { Text("1080p") })
                            FilterChip(selected = quality == 2, onClick = { quality = 2; currentQuality = 2; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putInt("quality",2).apply() } }, colors = chipColors, label = { Text("1440p") })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("帧率（发送端控制）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(24, 30, 45, 60).forEach { f ->
                                FilterChip(selected = frameRateSel == f, onClick = { frameRateSel = f; currentFrameRate = f; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putInt("frame_rate", f).apply() } }, colors = chipColors, label = { Text(if (f == 30) "30（推荐）" else f.toString()) })
                            }
                        }
                        Text("码率：VBR 自动分配；越高越清晰但越吃带宽/耗电", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 接收端：仅镜像（画面宽度占满+页面滑动，不做裁切/位置微调）
                    if (role == Role.RECV || loopback) {
                        Text("显示（接收端控制）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = mirrorState, onCheckedChange = { mirrorState = it; mirrorEnabled = it; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putBoolean("mirror", it).apply() } })
                            Text("镜像", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(16.dp))
                            TextButton(onClick = { zoomFill = !zoomFill }) { Text(if (zoomFill) "已放大" else "放大") }
                        }
                        Text(if (zoomFill) "放大填满（可再点还原）" else "完整显示，可放大", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))

                    // 本机 IP（总是显示）
                    val localIp = remember { NetInfo.wifiIpv4(context) ?: "(未连Wi-Fi)" }
                    Text("本机 IP: " + localIp + (if (role == Role.RECV) " (点开始接收后填发送端IP)" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    // 仅接收端需要填对端 IP（发送端是监听模式，无需 IP）
                    if (role == Role.RECV || loopback) {
                        OutlinedTextField(value = ip, onValueChange = { ip = it; currentIp = it; run { context.getSharedPreferences("mc_v03", Context.MODE_PRIVATE).edit().putString("peer_ip", it).apply() } }, label = { Text("发送端 IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    // 自发现设备列表（仅接收端、非回环）：点选即连，杜绝多设备误连
                    if (role == Role.RECV && !loopback) {
                        Spacer(Modifier.height(8.dp))
                        Text("发现的发送端（点选即连）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (devices.isEmpty()) {
                            Text("搜索中…（请确认对端已切到发送端）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else devices.forEach { d ->
                            Surface(onClick = { pickDevice(d) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                Text("${d.name}  (${d.ip})", modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // ================ 接收端预览（黑边/显示问题核心区） ================
                    // 当前方案：TextureView 填满"全宽 + 660dp 高"，MediaCodec 输出会拉伸铺满整个
                    // TextureView 视图 → 无黑边、画面大。这种方式会有轻微变形（不保原始比例）。
                    // ★★ 若你要"保比例不变形"，需改用 aspectRatio(videoRatio)：宽度全宽，高度按
                    //    真实视频比例，但高度可能不足 → 需配合缩放/裁剪。这是你之前反复调的点。
                    // 相关变量：
                    //   - videoRatio：实际视频宽高比，由 VideoSink.onFormatReady 回调更新(currentVideoRatio)
                    //   - zoomFill：放大开关，放大 1.25 倍(Crop效果，超出裁剪)
                    //   - mirrorState：镜像翻转
                    //   - 主 Column 用 padding(vertical=16.dp)——只给上下，左右无内边距(否则会挤压出左右黑边，已修复)
                    if (role == Role.RECV || loopback) {
                        // 【接收端预览：保比例清晰，暂用宽度填满+按比例高度】
                        AndroidView(factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(tex: SurfaceTexture, w: Int, h: Int) { tex.setDefaultBufferSize(720, 1280); previewSurface = Surface(tex); if (receiverStartPending) runBg { startReceiver(currentIp) } }
                                    override fun onSurfaceTextureSizeChanged(tex: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(tex: SurfaceTexture): Boolean { previewSurface?.release(); previewSurface = null; stopReceiver(); return true }
                                    override fun onSurfaceTextureUpdated(tex: SurfaceTexture) {}
                                }
                            }
                        }, modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(videoRatio)
                            .graphicsLayer {
                                val z = if (zoomFill) 1.4f else 1f
                                scaleX = z * (if (mirrorState) -1f else 1f)
                                scaleY = z
                            }
                            .clipToBounds()
                            .clip(RoundedCornerShape(12.dp)).background(Color.Black))
                        Spacer(Modifier.height(12.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("v03_diag", DiagLog.dump()))
                    }) { Text("复制诊断日志") }
                    if (diag.isNotBlank()) Text(diag, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    /**
     * 投影授权拿到位后按当前模式启动：
     *   - 回环：先起发送端(监听)，再起接收端(connect 127.0.0.1)
     *   - 发送端：startSender(proj, ip)
     *   - 接收端：不应走到这(接收端不授权)；proj.stop() 兜底
     */
    private fun startByMode() {
        val proj = pendingProjection ?: return
        pendingProjection = null
        if (currentLoopback) { startSender(proj, "127.0.0.1"); runBg { startReceiver("127.0.0.1") } }
        else when (currentRole) { Role.SEND -> startSender(proj, currentIp); Role.RECV -> proj.stop() }
    }

    companion object {
        // 视频通道端口：发送端 TcpVideoSender 监听，接收端 connect 过来
        private const val VIDEO_PORT = 8091
        // 控制通道端口：发送端 ControlServer 监听，接收端 ControlClient connect
        private const val CTRL_PORT = 8090
    }
}