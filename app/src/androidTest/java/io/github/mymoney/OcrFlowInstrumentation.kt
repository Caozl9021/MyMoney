package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.room.withTransaction
import com.example.billkit.BillPipeline
import com.example.billkit.DebugBus
import com.example.billkit.TextCollector
import com.example.billkit.UiSnapshot
import io.github.mymoney.capture.BillAccessibilityService
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.data.*
import io.github.mymoney.ocr.OcrPreferences
import io.github.mymoney.ocr.OcrWorkQueue
import io.github.mymoney.ocr.PageOcrAssist
import kotlinx.coroutines.*
import java.util.UUID

/** Real accessibility -> screenshot -> native OCR -> original record, exclusively on Waydroid.
 * Requires the optional com.tencent.mm fixture and an already enabled accessibility service.
 * Never grants permissions, installs packages, saves bills, or clears production preferences.
 */
class OcrFlowInstrumentation : Instrumentation() {
    private var admitted = false
    private var originalPageSessionWasEmpty = false
    private val results = mutableListOf<String>()
    private val diagnostics = mutableMapOf<String, Any?>()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        admitted = arguments?.getString("isolated") == "waydroid-ocr-flow"
        originalPageSessionWasEmpty = arguments?.getString("originalPageSession") == "empty"
        start()
    }
    override fun onStart() {
        var failure: Throwable? = null
        try {
            check(admitted && (Build.MODEL.contains("waydroid", true) || Build.PRODUCT.contains("waydroid", true))) {
                "Requires isolated=waydroid-ocr-flow on Waydroid"
            }
            check(Build.VERSION.SDK_INT >= 30)
            // Preserve accessibility while instrumentation is active.
            getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            runBlocking(Dispatchers.IO) { withTimeout(100_000) { runCases() } }
        } catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putInt("tests", results.size)
            putString("report", MiniJson.encode(mapOf("results" to results,
                "error" to failure?.stackTraceToString(), "diagnostics" to diagnostics,
                "scope" to "waydroid-real-automatic-screenshot-chain")))
        })
    }

    private suspend fun runCases() {
        val fixture = ComponentName("com.tencent.mm", "io.github.mymoney.ocrfixture.PaymentFixtureActivity")
        check(targetContext.packageManager.getActivityInfo(fixture, 0).exported)
        sendStatus(0, Bundle().apply { putString("phase", "await-service") })
        withTimeout(15_000) {
            while (BillAccessibilityService.instance == null) delay(100)
        }
        RecognitionStore.init(targetContext)
        RecognitionStore.awaitReady()
        val db = AppDb.get(targetContext)
        val baseline = db.recognitions().allOnce().associateBy { it.id }
        val bills = db.bills().allOnce().toSet()
        val accounts = db.assets().accounts().toSet()
        val entries = db.assets().entries().toSet()
        val aliases = db.capture().aliases().toSet()
        val evidence = db.capture().exportEvidence().toSet()
        val deliveries = db.capture().deliveries().toSet()
        val prefs = targetContext.getSharedPreferences("mymoney", Context.MODE_PRIVATE)
        val consentPrefs = targetContext.getSharedPreferences("screen_text_consent", Context.MODE_PRIVATE)
        val settingsKeys = setOf(Settings.KEY_ENABLED, Settings.KEY_PAGE_FALLBACK_ENABLED,
            Settings.KEY_NOTIFICATION_CAPTURE_ENABLED, Settings.KEY_HOOK_CAPTURE_ENABLED,
            Settings.KEY_AUTO_CONFIRM, Settings.KEY_CAPTURE_REVISION, Settings.KEY_WHITELIST)
        val oldSettings = settingsKeys.associateWith { prefs.all[it] }
        val consentKeys = setOf("local_screenshot_v1", "generation")
        val oldConsent = consentKeys.associateWith { consentPrefs.all[it] }
        val pagePrefs = targetContext.getSharedPreferences("capture_page_session", Context.MODE_PRIVATE)
        val oldPage = setOf("digest", "token").associateWith { if (originalPageSessionWasEmpty) null else pagePrefs.all[it] }
        val letters = UUID.randomUUID().toString().take(8).map { if (it in '0'..'9') 'k' + (it - '0') else it }.joinToString("")
        val prefix = "SIM-OCR-$letters"
        val tags = listOf("$prefix-NO", "$prefix-YES", "$prefix-WAIT", "$prefix-SECURE")
        check(baseline.values.none { it.merchant in tags })
        try {
            defaultsAndQueue()
            val settings = Settings(targetContext)
            settings.enabled = true
            settings.pageEnabled = true
            settings.notificationCaptureEnabled = false
            settings.hookCaptureEnabled = false
            settings.autoConfirm = false
            settings.whitelist = settings.whitelist + "com.tencent.mm"
            runOnMainSync { CaptureModeManager.reconcile(targetContext) }
            delay(800)
            val consent = OcrPreferences(targetContext)
            check(consent.setEnabled(false))
            launch(fixture, tags[0])
            val noConsent = awaitRecord(db, tags[0])
            delay(4_000)
            check(db.recognitions().byId(noConsent.id)?.let { it.method.isNullOrBlank() && it.productName.isNullOrBlank() } == true)
            check(db.capture().forRecord(noConsent.id).none { it.detail.contains("method=ocr") })
            results += "no_consent_keeps_node_record_without_screenshot_evidence"

            check(consent.setEnabled(true))
            launch(fixture, tags[1])
            val enriched = withTimeout(20_000) {
                var row: RecognitionEntity
                do { delay(150); row = awaitRecord(db, tags[1]) }
                while (row.method != "零钱" || row.productName != "豆浆包子")
                row
            }
            check(enriched.priceCents == 350L && enriched.type == "EXPENSE" && enriched.status == RecognitionStatus.PENDING)
            check(db.capture().forRecord(enriched.id).count { it.detail.contains("method=ocr") && it.source == "PAGE" && it.outcome == "ocr-added" } == 1)
            delay(2_000)
            check(db.recognitions().allOnce().count { it.merchant == tags[1] } == 1)
            check(db.bills().allOnce().toSet() == bills)
            results += "real_screenshot_native_ocr_enriches_one_original_page_record_without_posting"

            launch(fixture, tags[2], status = "待付款")
            delay(5_000)
            check(db.recognitions().allOnce().none { it.merchant == tags[2] }) { "Pending payment must not become a recognition" }
            results += "pending_payment_is_not_captured_or_supplemented"

            launch(fixture, tags[3], secure = true)
            val secure = awaitRecord(db, tags[3])
            delay(5_000)
            check(db.recognitions().byId(secure.id)?.let { it.method.isNullOrBlank() && it.productName.isNullOrBlank() } == true)
            // API30–33 display capture may return a black protected surface instead of an error.
            // It may record a refusal diagnostic, but must never adopt visual fields.
            check(db.capture().forRecord(secure.id).filter { it.detail.contains("method=ocr") }
                .all { it.outcome == "ocr-unusable" })
            results += "secure_window_keeps_node_record_without_adopting_protected_pixels"
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    val state = DebugBus.state.value
                    diagnostics["debugBus"] = state?.let {
                        mapOf("package" to it.packageName, "activity" to it.activityClass,
                            "source" to it.source, "outcome" to it.outcome.take(500),
                            "text" to if (it.packageName == "com.tencent.mm" && it.text.contains(prefix)) it.text.take(2000) else "[outside current synthetic fixture]",
                            "atMillis" to it.atMillis)
                    }
                    diagnostics["newFixtureRecords"] = db.recognitions().allOnce()
                        .filter { it.id !in baseline && it.sourcePkg == "com.tencent.mm" }.take(12).map {
                            mapOf("id" to it.id, "merchant" to if (it.merchant?.contains(prefix) == true || it.merchant?.contains("模拟") == true) it.merchant else "[non-fixture merchant redacted]",
                                "status" to it.status, "amountCents" to it.priceCents,
                                "hasMethod" to !it.method.isNullOrBlank(), "hasProduct" to !it.productName.isNullOrBlank())
                        }
                    runOnMainSync {
                        val root = BillAccessibilityService.instance?.rootInActiveWindow
                        if (root?.packageName?.toString() == "com.tencent.mm") {
                            val collected = TextCollector().collectResult(root)
                            if (collected.items.any { it.contains(prefix) }) {
                                diagnostics["fixtureItems"] = collected.items
                                diagnostics["fixtureTruncated"] = collected.truncated
                                diagnostics["fixturePipeline"] = BillPipeline().process(UiSnapshot("com.tencent.mm",
                                    "io.github.mymoney.ocrfixture.PaymentFixtureActivity", collected.items, collected.truncated)).toString().take(2500)
                            }
                        }
                    }
                }.onFailure { diagnostics["diagnosticError"] = it.toString().take(300) }
            }
            throw error
        } finally {
            // Stop all in-flight OCR before removing only exact test-owned records.
            withContext(NonCancellable) {
                try {
                    check(OcrPreferences(targetContext).setEnabled(false))
                    PageOcrAssist.cancelAll()
                    // Leave the fixture before restoring the user's original capture settings.
                    targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    delay(700)
                    db.withTransaction {
                        db.recognitions().allOnce().filter { it.id !in baseline && it.merchant in tags && it.sourcePkg == "com.tencent.mm" }
                            .forEach { row ->
                                check(row.billId == null && row.status == RecognitionStatus.PENDING) { "Refuse to remove a posted test record" }
                                db.openHelper.writableDatabase.execSQL("DELETE FROM recognition_history WHERE id = ?", arrayOf(row.id))
                            }
                    }
                } finally {
                    // Restore both stores even if fixture cleanup or one restore fails.
                    try { restore(prefs, oldSettings) }
                    finally {
                        restore(consentPrefs, oldConsent)
                        restore(pagePrefs, oldPage)
                        runOnMainSync { CaptureModeManager.reconcile(targetContext) }
                    }
                }
                check(db.recognitions().allOnce().associateBy { it.id } == baseline) { "History preservation check failed" }
                check(db.bills().allOnce().toSet() == bills && db.assets().accounts().toSet() == accounts && db.assets().entries().toSet() == entries)
                check(db.capture().aliases().toSet() == aliases && db.capture().exportEvidence().toSet() == evidence && db.capture().deliveries().toSet() == deliveries)
                check(oldSettings.all { prefs.all[it.key] == it.value } && oldConsent.all { consentPrefs.all[it.key] == it.value } && oldPage.all { pagePrefs.all[it.key] == it.value })
                results += "exact_tag_cleanup_and_original_business_data_and_preference_keys_restored"
            }
        }
    }

    private suspend fun defaultsAndQueue() {
        val name = "ocr_flow_test_" + UUID.randomUUID().toString()
        val isolatedContext = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = targetContext.getSharedPreferences(name, mode)
        }
        try {
            val consent = OcrPreferences(isolatedContext)
            check(!consent.enabled && consent.generation == 0L)
            check(consent.setEnabled(true) && consent.enabled && consent.generation == 1L)
            check(consent.setEnabled(false) && !consent.enabled && consent.generation == 2L)
            results += "fresh_install_default_off_and_explicit_consent_generation"
        } finally { check(targetContext.deleteSharedPreferences(name)) }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bytes = try { OcrWorkQueue.prepare(bitmap) } finally { bitmap.recycle() }
        check(runCatching { OcrWorkQueue.recognize(targetContext, bytes) { false } }.isFailure)
        check(runCatching { OcrWorkQueue.recognize(targetContext, ByteArray(OcrWorkQueue.MAX_BYTES + 1)) }.isFailure)
        check(runCatching { OcrWorkQueue.recognize(targetContext, byteArrayOf(1, 2, 3)) }.isFailure)
        results += "queue_rejects_revoked_oversized_and_invalid_images_before_native_ocr"
    }

    private fun launch(component: ComponentName, tag: String, status: String = "支付成功", secure: Boolean = false) {
        targetContext.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("tag", tag).putExtra("status", status).putExtra("secure", secure))
    }
    private suspend fun awaitRecord(db: AppDb, tag: String): RecognitionEntity = withTimeout(15_000) {
        var row: RecognitionEntity?
        do { delay(100); row = db.recognitions().allOnce().singleOrNull { it.merchant == tag } } while (row == null)
        row
    }
    private fun restore(prefs: SharedPreferences, old: Map<String, Any?>) {
        val edit = prefs.edit()
        old.forEach { (key, value) ->
            when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported test preference type: $key")
            }
        }
        check(edit.commit()) { "Cannot restore exact preference keys" }
    }
}
