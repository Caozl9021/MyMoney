package com.example.autobook.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.autobook.data.Settings
import com.example.autobook.data.CaptureMode
import com.example.billkit.BillKit
import com.example.billkit.TextCollector
import com.example.billkit.UiSnapshot
import java.util.concurrent.Executors

/**
 * 无障碍入口（宿主侧）。它只做三件事：
 *   1. 过滤：开关 / 包名 / 事件类型 / 去抖
 *   2. 取当前窗口文本（库里的 [TextCollector]）
 *   3. 交给 [BillKit.offer]，剩下的事（分类/校验/节流/去重/回调 sink）都在库里
 *
 * 这里**不含任何**"某个 App 某个页面"的规则 —— 规则在 :billkit 的 PageClassifier。
 */
class BillAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoBook"
        private val mutableBound = MutableStateFlow(false)
        val bound = mutableBound.asStateFlow()
        @Volatile
        var instance: BillAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val collector = TextCollector()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "autobook-capture").apply { isDaemon = true }
    }

    private lateinit var settings: Settings
    private var pendingPackage: String? = null
    private var pendingClass: String? = null

    private val schedule = AccessibilityCaptureSchedule()

    private val captureTask = Runnable { captureAndOffer() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        mutableBound.value = true
        settings = Settings(this)
        refreshServiceInfo()

        // 检测 LSP 模块状态（三档，不把"看到框架"说成"模块已启用"）
        val hookState = XposedModuleDetector.detect(this)

        // 连上就确保守护在跑：模式可能是在别处改的，这里再兜一次底（幂等）
        if (settings.enabled && settings.captureMode == CaptureMode.ACCESSIBILITY) {
            AccessibilityGuardian.ensureRunning(this)
        }

        Log.i(TAG, "无障碍服务已连接, 白名单=${settings.whitelist}, LSP状态=$hookState")
    }

    /** 白名单/开关变化后由 UI 调用，减少无效回调 */
    fun refreshServiceInfo() {
        val enabled = settings.enabled && settings.captureMode == CaptureMode.ACCESSIBILITY
        if (!enabled || pendingPackage?.let { it !in settings.whitelist } == true) {
            clearPending()
        }
        val info = serviceInfo ?: return
        info.packageNames = if (enabled) settings.whitelist.toTypedArray() else null
        info.eventTypes = if (enabled) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0
        info.notificationTimeout = 300
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::settings.isInitialized) return
        if (!settings.enabled || settings.captureMode != CaptureMode.ACCESSIBILITY) { clearPending(); return }

        val pkg = event.packageName?.toString() ?: return
        if (!BillKit.isAccepted(pkg) || pkg == packageName) {
            // Foreign content updates can come from a background app/SystemUI.
            // Only a window transition is evidence that we left the pending page.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pendingPackage != pkg) {
                clearPending()
            }
            return
        }

        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> AccessibilityCaptureSchedule.Event.WINDOW
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> AccessibilityCaptureSchedule.Event.CONTENT
            else -> return
        }
        if (pendingPackage != pkg) clearPending()
        pendingPackage = pkg
        // A content event often identifies a TextView, not the hosting Activity.
        // Preserve window metadata while merging content into its deadline.
        if (kind == AccessibilityCaptureSchedule.Event.WINDOW || pendingClass == null) {
            pendingClass = event.className?.toString()
        }
        postCaptureAt(schedule.offer(pkg, kind, SystemClock.uptimeMillis()))
    }

    private fun postCaptureAt(deadline: Long) {
        handler.removeCallbacks(captureTask)
        handler.postAtTime(captureTask, deadline)
    }

    private fun clearPending() {
        handler.removeCallbacks(captureTask)
        schedule.clear()
        pendingPackage = null
        pendingClass = null
    }

    private fun captureAndOffer() {
        if (!settings.enabled || settings.captureMode != CaptureMode.ACCESSIBILITY) { clearPending(); return }
        val pkg = pendingPackage ?: return
        val root = rootInActiveWindow

        // 事件到了但无障碍树还没建好（支付宝这类自绘/WebView 页面很常见）：
        // 轻量重试一次，别把这一屏直接丢掉。
        if (root == null || root.packageName?.toString() != pkg) {
            val retryAt = schedule.retry(SystemClock.uptimeMillis())
            if (retryAt != null) postCaptureAt(retryAt) else {
                Log.i(TAG, "无障碍根节点不可用，跳过本次采集: $pkg")
                clearPending()
            }
            return
        }

        val collected = collector.collectResult(root)
        val snapshot = UiSnapshot(
            packageName = pkg,
            activityClass = pendingClass,
            items = collected.items,
            truncated = collected.truncated,
            source = UiSnapshot.Source.ACCESSIBILITY,
        )

        clearPending()
        worker.execute {
            if (settings.enabled && settings.captureMode == CaptureMode.ACCESSIBILITY) {
                BillKit.offer(snapshot)
            }
        }
    }

    override fun onInterrupt() {
        clearPending()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) { instance = null; mutableBound.value = false }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearPending()
        worker.shutdownNow()
        if (instance === this) { instance = null; mutableBound.value = false }
        super.onDestroy()
    }
}
