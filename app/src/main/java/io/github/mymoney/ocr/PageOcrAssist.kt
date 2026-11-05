package io.github.mymoney.ocr

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import com.example.billkit.UiSnapshot
import com.example.billkit.visual.VisualBillParser
import io.github.mymoney.capture.CaptureCoordinator
import io.github.mymoney.capture.NotificationIngress
import io.github.mymoney.data.Settings
import io.github.mymoney.data.CaptureMergePolicy
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Q1 only: screenshot evidence can enrich its exact accepted page observation, never dispatch a bill. */
object PageOcrAssist {
    private data class Binding(val id: String, val expected: BillDraft, val key: String)
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    fun cancelAll() { epoch.incrementAndGet(); pending.values.forEach { it.cancel() }; pending.clear() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Binding>>()
    private val recent = linkedMapOf<String, Long>()
    @Synchronized private fun reserve(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        recent.entries.removeAll { now - it.value > 60_000 }
        if (key in recent || pending.size >= 3) return false
        recent[key] = now
        if (recent.size > 64) recent.remove(recent.keys.first())
        return true
    }
    fun bind(observation: String, recognitionId: String, expected: BillDraft, key: String) {
        pending[observation]?.complete(Binding(recognitionId, expected, key))
    }
    fun request(service: AccessibilityService, snapshot: UiSnapshot, expected: BillDraft, windowId: Int,
                safeWindow: () -> Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 30 || expected.type != BillType.EXPENSE ||
            !com.example.billkit.visual.VisualCaptureGate.allows(snapshot.packageName, snapshot.items, snapshot.truncated)) return false
        val observation = snapshot.pageObservationId ?: return false
        val prefs = OcrPreferences(service)
        if (!prefs.enabled || expected.priceCents <= 0 ||
            (!expected.merchant.isNullOrBlank() && !expected.method.isNullOrBlank() && !expected.productName.isNullOrBlank())) return false
        val generation = prefs.generation
        val serviceEpoch = epoch.get()
        val token = snapshot.configurationToken ?: return false
        val started = SystemClock.elapsedRealtime()
        fun allowed(): Boolean = serviceEpoch == epoch.get() &&
            io.github.mymoney.capture.BillAccessibilityService.instance === service && prefs.enabled && prefs.generation == generation &&
            Settings(service).let { it.enabled && it.pageEnabled && snapshot.packageName in it.whitelist } &&
            token == NotificationIngress.configurationToken(service) &&
            (service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != true
        if (!allowed() || !safeWindow()) return false
        val key = CaptureMergePolicy.digest("${snapshot.packageName}|$windowId|${snapshot.items}")
        if (!reserve(key)) return false
        val binding = CompletableDeferred<Binding>()
        pending[observation] = binding
        val pixels = CompletableDeferred<ByteArray>()
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                if (!allowed() || SystemClock.elapsedRealtime() - started > 1_000 ||
                    (Build.VERSION.SDK_INT < 34 && !safeWindow())) {
                    buffer.close(); pixels.completeExceptionally(IllegalStateException("页面已切换")); return
                }
                scope.launch {
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("系统截图不可用")
                        try {
                            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截图转换失败")
                            try { pixels.complete(OcrWorkQueue.prepare(software)) } finally { software.recycle() }
                        } finally { hardware.recycle() }
                    } catch (error: Exception) { pixels.completeExceptionally(error) }
                    finally { buffer.close() }
                }
            }
            override fun onFailure(errorCode: Int) { pixels.completeExceptionally(IllegalStateException("系统截图不可用：$errorCode")) }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(windowId, service.mainExecutor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (error: Exception) { pixels.completeExceptionally(error) }
        scope.launch {
            try {
                withTimeout(3_000) {
                    val bytes = withTimeout(2_000) { pixels.await() }
                    val accepted = binding.await()
                    if (!allowed()) return@withTimeout
                    val visual = OcrWorkQueue.recognize(service, bytes, ::allowed)
                    val parsed = VisualBillParser.supplement(visual, accepted.expected, System.currentTimeMillis())
                    val applied = CaptureCoordinator.supplementOcr(service, accepted.id, accepted.expected, parsed,
                        accepted.key, allowed = ::allowed, engineId = visual.engine)
                    if (applied != null && (applied.outcome == "conflict" ||
                        (parsed.issue != null && parsed.issue!!.contains("不一致")))) {
                        OcrReviewNotices.set(accepted.id, "补充截图信息与当前账单不一致，请核对后保存")
                    }
                }
            } catch (_: Exception) {
                // Failure never delays or replaces the original node/notification/credential path.
            } finally { pending.remove(observation, binding); binding.cancel(); pixels.cancel() }
        }
        return true
    }
}
