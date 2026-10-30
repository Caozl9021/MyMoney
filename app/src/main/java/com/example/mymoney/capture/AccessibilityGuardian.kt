package com.example.mymoney.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.mymoney.R
import com.example.mymoney.systems.BrandIcons
import com.example.mymoney.data.CaptureMode
import com.example.mymoney.data.Settings
import com.example.mymoney.ui.MainActivity

/**
 * 无障碍守护进程：**真正在跑的前台服务**，负责发现"无障碍被系统/厂商关掉"并立刻提醒用户。
 *
 * 它不试图自己把无障碍重新打开（普通应用没有这个权限，谁也做不到），
 * 职责只有一个：**尽快让用户知道**，而不是让记账静悄悄地停摆。
 *
 * 保活策略：
 * 1. 前台服务（`specialUse`），通知栏常驻一条低优先级通知，进程优先级高于普通后台；
 * 2. 启动后立刻检查一次（不是等 30s），之后每 [CHECK_INTERVAL_MS] 检查一次；
 * 3. 连续 [GuardianPolicy.ALERT_AFTER_MISSES] 次未启用（约 60s）才报警，避开切换抖动；
 * 4. `START_STICKY`：被系统杀掉后由系统重建。
 *
 * 只有在「已选择无障碍模式 + 识别总开关开启 + 页面辅助开启」时由 [CaptureModeManager] 拉起；
 * 切到 Root 模式或暂停识别时会被停掉，不留后台空转。
 */
class AccessibilityGuardian : Service() {

    companion object {
        private const val TAG = "AB-Guardian"

        /** 与 root 服务错开，避免任何潜在的 id 冲突 */
        const val NOTIFICATION_ID = 1002
        const val ALERT_NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "mymoney_guardian"

        /** 30 秒检查一次：足够快，又不会明显耗电 */
        const val CHECK_INTERVAL_MS = 30_000L

        @Volatile
        var running: Boolean = false
            private set

        /** 渠道必须在使用前建好；[com.example.mymoney.App] 启动时也会调一次。 */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = manager.getNotificationChannel(CHANNEL_ID) ?: NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_guardian_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                }
            channel.name = context.getString(R.string.notification_guardian_channel)
            channel.description = "无障碍模式下常驻，发现服务被系统关闭时提醒你重新开启"
            manager.createNotificationChannel(channel)
        }

        /** 是否应该跑守护：识别和页面辅助开启，且当前是无障碍模式。 */
        fun shouldRun(context: Context): Boolean {
            val settings = Settings(context)
            return settings.enabled && settings.pageEnabled && settings.captureMode == CaptureMode.ACCESSIBILITY
        }

        fun start(context: Context) {
            if (!shouldRun(context)) return
            ensureChannel(context)
            val app = context.applicationContext
            val intent = Intent(app, AccessibilityGuardian::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            }.onFailure { Log.w(TAG, "守护服务启动失败: ${it.message}") }
        }

        /** 幂等：已经在跑就什么都不做（避免频繁 startForegroundService 触发 ANR 警告）。 */
        fun ensureRunning(context: Context) {
            if (running) return
            start(context)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, AccessibilityGuardian::class.java)) }
        }

        /** 系统设置里是否勾了本 App 的无障碍服务（不看实际有没有连上）。 */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${BillAccessibilityService::class.java.name}"
            val enabled = SystemSettings.Secure.getString(
                context.contentResolver,
                SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /**
         * 无障碍服务**是否在跑**：只有真的连着才算。
         *
         * 注意这跟"系统设置里打着勾"是两件事：厂商/系统可以在设置里保留勾选
         * 的同时把服务解绑，用户看起来一切正常，实际早就不采集了。
         */
        fun isAccessibilityEnabled(context: Context): Boolean =
            isEnabledInSettings(context) && BillAccessibilityService.instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var misses = 0

    private val checkTask = object : Runnable {
        override fun run() {
            checkAccessibilityStatus()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException：
        // 拦下来自己退出，别把 App 崩掉（用户下次回到 App 时 reconcile 会再拉一次）。
        val started = runCatching { startInForeground() }.onFailure {
            Log.w(TAG, "前台服务启动被拒: ${it.message}")
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        running = true
        // 立刻查一次：旧实现要等 30s，且首检永远不报警（lastCheckEnabled 初值 false）
        checkAccessibilityStatus()
        handler.postDelayed(checkTask, CHECK_INTERVAL_MS)
        Log.i(TAG, "守护进程已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 模式/开关已经不是无障碍了（比如从旧任务把它拉起来），直接退出
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "守护进程已停止")
        super.onDestroy()
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_small)
            .setLargeIcon(BrandIcons.large(this))
            .setContentTitle(getString(R.string.notification_guardian_title))
            .setContentText("无障碍服务被系统关闭时会提醒你")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
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

    private fun checkAccessibilityStatus() {
        val health = GuardianPolicy.health(
            enabledInSettings = isEnabledInSettings(this),
            serviceBound = BillAccessibilityService.instance != null,
        )
        misses = GuardianPolicy.nextMisses(misses, health)
        if (health != GuardianPolicy.Health.OK) {
            Log.w(TAG, "${health.name}（连续 $misses 次检查）")
            if (GuardianPolicy.shouldAlert(misses, health)) onAccessibilityLost(health)
        }
    }

    private fun onAccessibilityLost(health: GuardianPolicy.Health) {
        Log.e(TAG, "无障碍异常: ${health.name}，发送通知引导用户重新开启")

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            // 连通知权限都没有，只能靠日志；用户在权限页能看到通知状态
            Log.e(TAG, "通知权限未授予，无法提醒")
            return
        }

        val settingsIntent = Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, ConfirmDelivery.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_small)
            .setLargeIcon(BrandIcons.large(this))
            .setContentTitle(getString(R.string.notification_guardian_alert_title))
            .setContentText(GuardianPolicy.message(health) + "，点这里重新开启")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(GuardianPolicy.message(health) + "。点这里打开系统无障碍设置。")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching { NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, notification) }
    }
}
