package io.github.mymoney.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import com.example.billkit.PageCaptureEligibility
import com.example.billkit.DebugBus
import com.example.billkit.BillPipeline
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.mymoney.data.Settings
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
        private const val TAG = "MyMoney"
        private val mutableBound = MutableStateFlow(false)
        val bound = mutableBound.asStateFlow()
        @Volatile
        var instance: BillAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val collector = TextCollector()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mymoney-capture").apply { isDaemon = true }
    }

    private lateinit var settings: Settings
    private var pendingPackage: String? = null
    private var pendingClass: String? = null
    private var pendingToken: String? = null

    private val schedule = AccessibilityCaptureSchedule()
    private val observations = PageObservationScope()

    fun isObservationCurrent(id: String?): Boolean = ::settings.isInitialized && settings.pageActive &&
        (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked != true && observations.allows(id)

    private fun invalidateObservations() {
        observations.clear()
        io.github.mymoney.ocr.PageOcrAssist.cancelAll()
    }

    private val captureTask = Runnable { captureAndOffer() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        mutableBound.value = true
        settings = Settings(this)
        refreshServiceInfo()

        // 连上就确保守护在跑：模式可能是在别处改的，这里再兜一次底（幂等）
        if (settings.pageActive) {
            AccessibilityGuardian.ensureRunning(this)
        }

        Log.i(TAG, "无障碍服务已连接, 白名单=${settings.whitelist}")
    }

    /** 白名单/开关变化后由 UI 调用，减少无效回调 */
    fun refreshServiceInfo() {
        val enabled = settings.pageActive
        if (!enabled || pendingPackage?.let { it !in settings.whitelist } == true) {
            clearPending()
            invalidateObservations()
        }
        val info = serviceInfo ?: return
        // Observe foreign window transitions to invalidate leases; never read their content.
        info.packageNames = null
        info.eventTypes = if (enabled) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0
        info.notificationTimeout = 300
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::settings.isInitialized) return
        if (!settings.pageActive) { clearPending(); invalidateObservations(); return }

        val pkg = event.packageName?.toString() ?: return
        if (!BillKit.isAccepted(pkg) || pkg == packageName) {
            // Own confirmation UI may cover an accepted detail without changing its transaction.
            val ownConfirmation = pkg == packageName &&
                (event.className?.toString() == "io.github.mymoney.ui.ConfirmActivity" ||
                    io.github.mymoney.overlay.ConfirmOverlay.isShowing())
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !ownConfirmation) {
                clearPending()
                invalidateObservations()
            }
            return
        }

        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> AccessibilityCaptureSchedule.Event.WINDOW
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> AccessibilityCaptureSchedule.Event.CONTENT
            else -> return
        }
        val token = NotificationIngress.configurationToken(this)
        if (pendingPackage != pkg || pendingToken != token) clearPending()
        // A queued snapshot may not outlive a new target-window/content observation.
        invalidateObservations()
        pendingToken = token
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
        pendingToken = null
    }

    private fun captureAndOffer() {
        if (!settings.pageActive) { clearPending(); invalidateObservations(); return }
        val pkg = pendingPackage ?: return
        val token = pendingToken ?: return
        if (token != NotificationIngress.configurationToken(this)) { clearPending(); return }
        val root = rootInActiveWindow

        // 事件到了但无障碍树还没建好（支付宝这类自绘/WebView 页面很常见）：
        // 轻量重试一次，别把这一屏直接丢掉。
        if (root == null || root.packageName?.toString() != pkg) {
            root?.recycle()
            invalidateObservations()
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
            configurationToken = token,
            pageObservationId = UUID.randomUUID().toString(),
        )

        val windowId = root.windowId
        root.recycle()
        val admission = PageCaptureEligibility.evaluate(snapshot)
        if (!admission.allowed) {
            invalidateObservations()
            clearPending()
            DebugBus.publish(snapshot, "skip:scene:${admission.reason}")
            return
        }
        val candidate = BillPipeline().processForCapture(snapshot)
        if (candidate !is BillPipeline.Outcome.Draft) {
            invalidateObservations()
            clearPending()
            DebugBus.publish(snapshot, "skip:detail-not-recognized")
            return
        }
        val draft = candidate.draft
        val detailKey = listOf(pkg, windowId, draft.transactionKey ?: listOf(
            draft.type, draft.priceCents, if (draft.hasExplicitTime) draft.timeMillis else snapshot.items,
            draft.method).toString()).joinToString("|")
        observations.observe(snapshot.pageObservationId!!, detailKey)

        // Nodes establish a payment before any pixels are requested. OCR never discovers safety.
        if (Build.VERSION.SDK_INT >= 30 && io.github.mymoney.ocr.OcrPreferences(this).enabled &&
            !collected.truncated && safeOcrWindow(pkg, windowId)) {
            val result = candidate
            if (result is BillPipeline.Outcome.Draft) {
                io.github.mymoney.ocr.PageOcrAssist.request(this, snapshot, result.draft, windowId) {
                    safeOcrWindow(pkg, windowId, snapshot.items)
                }
            }
        }
        clearPending()
        worker.execute {
            if (settings.pageActive && token == NotificationIngress.configurationToken(this) &&
                observations.allows(snapshot.pageObservationId)) {
                BillKit.offer(snapshot)
            }
        }
    }

    /** Bounded traversal, unknown/over-budget/editable/sensitive windows fail closed. */
    private fun safeOcrWindow(pkg: String, windowId: Int, expectedItems: List<String>? = null): Boolean {
        if (!settings.pageActive || pkg !in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) return false
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != pkg || root.windowId != windowId) return false
            if (expectedItems != null) {
                val current = collector.collectResult(root)
                if (current.truncated || current.items != expectedItems) return false
            }
            var count = 0
            fun safe(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                if (++count > 400 || node.isPassword || node.isEditable) return false
                val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
                if (listOf("密码", "验证码", "指纹验证", "人脸验证", "身份验证", "确认付款", "立即付款").any(text::contains)) return false
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { if (!safe(child)) return false } finally { child.recycle() }
                }
                return true
            }
            return safe(root)
        } finally { root.recycle() }
    }

    override fun onInterrupt() {
        invalidateObservations()
        clearPending()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        invalidateObservations()
        clearPending()
        if (instance === this) { instance = null; mutableBound.value = false }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        invalidateObservations()
        clearPending()
        worker.shutdownNow()
        if (instance === this) { instance = null; mutableBound.value = false }
        super.onDestroy()
    }
}
