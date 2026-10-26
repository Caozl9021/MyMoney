package com.example.autobook.capture

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.autobook.R
import com.example.autobook.systems.BrandIcons
import com.example.autobook.data.RecognitionStore
import com.example.autobook.data.RecognitionStatus
import com.example.autobook.overlay.ConfirmOverlay
import com.example.autobook.overlay.OverlayAnchor
import com.example.billkit.BillDraft
import java.util.Locale

/**
 * 把草稿"送到用户面前"。**只有一种 UI** —— 底部半屏确认卡（[ConfirmSheet]），
 * 只是有两种宿主，按可靠性依次尝试：
 *
 * 1. **[ConfirmOverlay]（首选）**：把同一张卡以悬浮窗直接盖在对方 App 上。
 *    不启动 Activity，所以完全绕开 BAL，用户看到的就是"识别完立刻弹出"。
 * 2. **[com.example.autobook.ui.ConfirmActivity]**：没有悬浮窗权限时退回透明 Activity
 *    （前台必定成功；后台先挂 [OverlayAnchor] 拿 SAW 豁免，再用 [ConfirmLaunch]
 *    回执确认它真的起来了）。
 *
 * 两条路都不通时才落待确认队列 + 通知，绝不静默丢弃。
 *
 * 返回值（进 DebugBus，排查"没弹窗"先看这里）：
 * - `overlay`             悬浮卡已挂上（正常路径）
 * - `confirm` / `confirm-bg`   Activity 弹出来了（前台 / 后台）
 * - `pending+notified` / `pending` / `pending-only`  落队列了
 */
object ConfirmDelivery {

    const val CHANNEL_ID = "autobook_confirm"
    private const val CHANNEL_NAME = "账单待确认"

    /** 等待确认页回执的时长：够一次冷启动 onCreate，又不至于长时间卡住采集 */
    private const val LAUNCH_TIMEOUT_MS = 1_500L

    /** 返回值直接进调试面板，方便一眼看出"到底有没有送到用户面前" */
    suspend fun deliver(context: Context, incoming: BillDraft, autoConfirm: Boolean, allowPresentation: () -> Boolean = { true }): String {
        val record = RecognitionStore.record(incoming)
        if (record.status != RecognitionStatus.PENDING) return "history-${record.status.lowercase()}"
        val draft = record.toDraft()
        if (!autoConfirm) {
            return "pending-only"
        }

        val app = context.applicationContext
        fun mayShow(): Boolean = allowPresentation() &&
            (app.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked != true
        if (!mayShow()) return "pending-source-disabled"

        // ① 有悬浮窗权限：直接盖一张卡，不启动 Activity（不受 BAL 影响）
        if (ConfirmOverlay.canShow(app) && ConfirmOverlay.show(app, draft, allowed = { mayShow() })) {
            return "overlay"
        }

        // ② 退回透明 Activity：不在前台就先挂锚点拿豁免
        if (!mayShow()) return "pending-source-disabled"
        val foreground = isAppForeground(app)
        val anchored = !foreground && OverlayAnchor.hold(app)
        val exempt = foreground || anchored || isAccessibilityServiceEnabled(app)
        val launched = exempt && launchConfirm(app, draft, waitReceipt = !foreground, allowed = { mayShow() })
        OverlayAnchor.release()

        if (launched) {
            return if (foreground) "confirm" else "confirm-bg"
        }

        // 没授通知权限时必须和"通知被系统静音"区分开，否则排查时只会看到 pending，
        // 不知道到底是 BAL 拦了还是根本发不出去通知。
        if (!mayShow()) return "pending-source-disabled"
        if (!hasNotificationPermission(app)) return "pending-no-notify"
        return if (notify(app, draft)) "pending+notified" else "pending"
    }

    /** 通知权限（Android 13+ 是运行时权限；更早的版本看渠道总开关） */
    fun hasNotificationPermission(context: Context): Boolean =
        NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()

    /**
     * 拉起确认页，并等它回报"我真的起来了"。
     * @param waitReceipt 主线程上不能阻塞等回执（会把 UI 卡住 1.5s），只有后台线程才等
     */
    private fun launchConfirm(context: Context, draft: BillDraft, waitReceipt: Boolean, allowed: () -> Boolean): Boolean {
        val presentationToken = NotificationIngress.configurationToken(context)
        if (!allowed()) return false
        ConfirmLaunch.begin(draft.historyId ?: draft.fingerprint)
        val started = try {
            if (!allowed()) return false
            context.startActivity(ConfirmIntents.intent(context, draft).putExtra("autobook_auto_capture_token", presentationToken))
            true
        } catch (e: Exception) {
            false
        }
        if (!started) return false
        if (!waitReceipt || Looper.myLooper() == Looper.getMainLooper()) return true
        return ConfirmLaunch.awaitLaunched(draft.historyId ?: draft.fingerprint, LAUNCH_TIMEOUT_MS)
    }

    /** 无障碍服务被系统绑定时，后台 startActivity 才有豁免（仅作辅助判断） */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${BillAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** 只关心"我自己的进程是不是前台"，所以 runningAppProcesses 够用 */
    private fun isAppForeground(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val myPid = Process.myPid()
        am.runningAppProcesses?.any {
            it.pid == myPid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
    } catch (e: Exception) {
        false
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "识别到账单却弹不出确认卡时提醒你，点开即可记一笔"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notify(context: Context, draft: BillDraft): Boolean {
        ensureChannel(context)
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return false

        val pendingIntent = PendingIntent.getActivity(
            context,
            (draft.historyId ?: draft.fingerprint).hashCode(),
            ConfirmIntents.intent(context, draft),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val typeLabel = when (draft.type.name) {
            "INCOME" -> "收入"
            "REFUND" -> "退款"
            "TRANSFER" -> "转账"
            else -> "支出"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_small)
            .setLargeIcon(BrandIcons.large(context))
            .setContentTitle("识别到一笔$typeLabel：¥" + String.format(Locale.US, "%.2f", draft.priceCents / 100.0))
            .setContentText(draft.merchant ?: draft.sourcePkg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            notifications.notify((draft.historyId ?: draft.fingerprint).hashCode(), notification)
            true
        } catch (e: SecurityException) {
            false // 没给 POST_NOTIFICATIONS 权限
        }
    }

    fun cancel(context: Context, fingerprint: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(fingerprint.hashCode())
        }
    }
}
