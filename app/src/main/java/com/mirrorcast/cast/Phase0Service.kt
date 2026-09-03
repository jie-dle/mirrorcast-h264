package com.mirrorcast.cast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.mirrorcast.util.DiagLog

/**
 * Phase 0 授权前台服务：负责「先 startForeground(mediaProjection) 再 getMediaProjection」的 Android 14 规范。
 * 授权成功后将 token 存入 CastBus，Activity 轮询取走并启动 LoopbackSession。
 * 服务在会话期间保持存活（Android 14 要求：后台运行但无 mediaProjection 前台服务时系统会停止投影）。
 */
class Phase0Service : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                // Android 14 规范：必须先 startForeground(mediaProjection) 再 getMediaProjection
                val notif = buildNotification("正在准备投屏…")
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIF_ID, notif)
                }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                CastBus.projection = mpm.getMediaProjection(resultCode, data)
                DiagLog.log("授权成功：投影 token 已就绪")
                updateNotification("投屏会话进行中")
            } catch (t: Throwable) {
                DiagLog.log("授权处理失败：${t.javaClass.simpleName}: ${t.message ?: ""}")
                CastBus.projection = null
                stopSelf()
            }
        } else {
            DiagLog.log("未授权录屏（resultCode=$resultCode）")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CastBus.projection = null
        DiagLog.log("Phase0 服务停止")
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("镜像投屏V0.3")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "v03_cast"
        private const val NOTIF_ID = 1003
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}