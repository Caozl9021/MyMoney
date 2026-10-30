package io.github.mymoney.root

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
import io.github.mymoney.R
import io.github.mymoney.systems.BrandIcons
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.data.Settings
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.capture.NotificationPlanState
import io.github.mymoney.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * root 通道的常驻宿主。
 *
 * 为什么要有它：root 读通知是 5 秒一次的轮询，靠协程在进程内活着。App 退到后台后
 * 普通进程会被系统回收或冻结，轮询就断了。挂一个前台服务才能让它一直在。
 *
 * 这里**只保活，不采集**：真正的轮询在 [io.github.mymoney.capture.RootNotificationIngress]，
 * 启停由 [CaptureModeManager.reconcile] 统一决定。本服务不持有任何采集逻辑。
 *
 * 历史：这个服务过去还驱动 `RootWatcher`（uiautomator dump 抓页面 + 截图 OCR）。
 * 那条路已删除——它每 1.2/3 秒开一次 root shell，开销远大于收益，
 * 而它想补的「页面有付款信息但既没通知也没 hook」场景在微信/支付宝上并不存在。
 * 它现在的唯一作用：**让通知通道在用户没给「通知使用权」时仍然可用**。
 * 要不要起它不看采集模式，看 [io.github.mymoney.capture.ChannelSelection] 判出来的
 * 通知实现是不是 `DUMPSYS`——listener 连得上时这个服务根本不该在。
 */
class RootForegroundService : Service() {

    companion object {
        private val mutableActive = MutableStateFlow(false)
        val activeState = mutableActive.asStateFlow()
        val active: Boolean get() = mutableActive.value

        const val CHANNEL_ID = "mymoney_root"
        private const val NOTIFICATION_ID = 1001

        /** 开关落在这里，进程重建后 [android.app.Application] 才知道要不要自动拉起服务 */
        private const val ACTION_START = "io.github.mymoney.root.START"
        private const val ACTION_STOP = "io.github.mymoney.root.STOP"

        /** Refresh display metadata on the original channel, keeping user notification settings. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = manager.getNotificationChannel(CHANNEL_ID) ?: NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notification_root_channel), NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            channel.name = context.getString(R.string.notification_root_channel)
            channel.description = "通知监听不可用时，通过 Root 权限读取通知栏中的交易消息"
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val settings = Settings(context)
            if (!settings.enabled || !settings.notificationCaptureEnabled ||
                NotificationPlanState.value != NotificationPath.DUMPSYS) return
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
            context.stopService(Intent(context, RootForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CaptureModeManager.setNotificationEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val settings = Settings(this)
                // 被 START_STICKY 重建时计划可能早已换回 listener：这里再核一次，别让服务空转。
                if (!settings.enabled || !settings.notificationCaptureEnabled ||
                    NotificationPlanState.value != NotificationPath.DUMPSYS) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                mutableActive.value = true
            }
        }
        // 被系统杀掉后自动重建：轮询是"常驻"语义，重建时不要弹新通知
        return START_STICKY
    }

    override fun onDestroy() {
        mutableActive.value = false
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
            .setContentTitle(getString(R.string.notification_root_title))
            .setContentText("Root 读取 · 每 5 秒读取一次通知栏")
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
        ensureChannel(this)
    }
}
