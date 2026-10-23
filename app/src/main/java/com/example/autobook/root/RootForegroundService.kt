package com.example.autobook.root

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.autobook.R
import com.example.autobook.systems.BrandIcons
import com.example.autobook.data.Settings
import com.example.autobook.data.CaptureMode
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.ui.MainActivity

/**
 * root 轮询的常驻宿主。
 *
 * 为什么要有它：过去 [RootWatcher] 是在 `SettingsScreen` 里 `remember { RootWatcher(...) }` 的，
 * 一离开设置页（甚至只是进程被回收）轮询就停了 —— 等于"root 模式"只在设置页里活着。
 * 现在改成前台服务：开启后一直按节拍抓，通知栏有一条常驻通知（不可划掉，但很低调）。
 *
 * 仅在用户已选择 Root 模式且启用识别时启动；
 * 不读第三方 App 私有数据，只用 uiautomator dump 读当前界面文字。
 */
class RootForegroundService : Service() {

    companion object {
        @Volatile
        var active: Boolean = false
            private set

        const val CHANNEL_ID = "autobook_root"
        private const val NOTIFICATION_ID = 1001
        private const val INTERVAL_MS = 1_200L

        /** 开关落在这里，进程重建后 [android.app.Application] 才知道要不要自动拉起服务 */
        private const val ACTION_START = "com.example.autobook.root.START"
        private const val ACTION_STOP = "com.example.autobook.root.STOP"

        fun start(context: Context) {
            val settings = Settings(context)
            if (!settings.enabled || settings.captureMode != CaptureMode.ROOT) return
            val intent = Intent(context, RootForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止。用 `stopService` 而不是发 ACTION_STOP：
         * Android 8+ 在后台 `startService` 会直接抛 IllegalStateException，
         * 而 `stopService` 任何场景都合法。
         */
        fun stop(context: Context) {
            RootWatcher.stop()
            context.stopService(Intent(context, RootForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CaptureModeManager.setEnabled(this, false)
                RootWatcher.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val settings = Settings(this)
                if (!settings.enabled || settings.captureMode != CaptureMode.ROOT) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                active = true
                startInForeground()
                RootWatcher.start(this, settings, INTERVAL_MS) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        // 被系统杀掉后自动重建：轮询是"常驻"语义，重建时不要弹新通知
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        RootWatcher.stop()
        super.onDestroy()
    }

    private fun startInForeground() {
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RootForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_small)
            .setLargeIcon(BrandIcons.large(this))
            .setContentTitle("AutoBook 正在监听账单页面")
            .setContentText("root 模式 · 每 1.2 秒检查一次前台应用")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "root 监听", NotificationManager.IMPORTANCE_LOW).apply {
                description = "root 模式下持续检查前台应用是否为账单页面"
                setShowBadge(false)
            },
        )
    }
}
