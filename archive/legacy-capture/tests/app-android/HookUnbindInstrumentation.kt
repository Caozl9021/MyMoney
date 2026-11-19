package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import io.github.mymoney.capture.HookIngress
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.RecognitionEntity
import io.github.mymoney.data.RecognitionStatus
import io.github.mymoney.data.Settings
import io.github.mymoney.data.SpecRuleStore
import io.github.mymoney.lsp.HookProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 阶段 2 专项：**无障碍模式下凭证开关能开，且凭证真的被采到**。
 *
 * 与其它隔离用例的差别要说清楚：这条用例必须走真实的 `HookIngress.offer` → `process` →
 * `AppDb.get`，因为要证的正是「宿主没选 Root 也能采」这条生产路径——把库换成内存 Room
 * 就没意义了。因此它只在**模拟器**上跑（真机上是用户账本），跑完由调用方 `pm clear` 清掉。
 *
 * 另外把 `autoConfirm` 设成 false：这样投递停在「待确认」，不会弹悬浮窗或拉起确认页，
 * 用例不会制造任何前台干扰。
 *
 * 进不了 provider 那一层：`LspStatusProvider.call` 用 `Binder.getCallingUid()` 校验调用方
 * 名下必须有被 hook 的包，测试进程的 UID 不满足，这是刻意的安全设计。所以这里直接调用
 * `HookIngress.offer`——provider 除了这层 UID 校验之外的逻辑与它完全一致。
 */
class HookUnbindInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    private val now = System.currentTimeMillis()
    private val businessSeconds = now / 1000 - 5

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var failure: Throwable? = null
        val settings = Settings(targetContext)
        val saved = Snapshot(settings)
        try {
            requireEmulator()
            runBlocking(Dispatchers.IO) { runCases() }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { saved.restore(settings) }
        }
        val passed = failure == null && results.none { it["status"] == "failed" }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf(
                "status" to if (passed) "passed" else "failed",
                "tests" to results.size,
                "mode" to "ACCESSIBILITY",
                "delivery" to "pending-only (autoConfirm=false, no overlay or activity)",
                "providerUidCheck" to "not exercised; offer called directly",
                "results" to results,
                "error" to failure?.stackTraceToString(),
            )))
        })
    }

    private fun requireEmulator() {
        check(listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).all { value ->
            listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "vbox").any { value.contains(it, true) }
        }) { "Refusing unbind verification on a real device: it writes the installed app database" }
    }

    private suspend fun test(name: String, body: suspend () -> Unit) {
        val start = System.nanoTime()
        try {
            withTimeout(90_000) { body() }
            results += mapOf("name" to name, "status" to "passed", "millis" to (System.nanoTime() - start) / 1_000_000)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        }
    }

    private suspend fun runCases() {
        val context = targetContext
        val settings = Settings(context)
        // 普通模式 + 凭证开关：这两件事同时成立，在阶段 1 之前是不可能的组合。
        settings.selectCaptureMode(CaptureMode.ACCESSIBILITY)
        settings.enabled = true
        settings.autoConfirm = false
        settings.hookCaptureEnabled = false
        settings.whitelist = settings.whitelist + WECHAT

        SpecRuleStore.init(context)
        withTimeout(60_000) { while (!SpecRuleStore.isReady()) delay(100) }

        test("hook_switch_can_be_turned_on_in_accessibility_mode") {
            settings.hookCaptureEnabled = true
            check(settings.captureMode == CaptureMode.ACCESSIBILITY) { "Mode drifted away from ACCESSIBILITY" }
            check(SpecRuleStore.isReady())
            check(settings.hookCaptureEnabled) { "Hook switch did not persist outside ROOT mode" }
        }

        test("credential_is_captured_and_recorded_once_in_accessibility_mode") {
            val before = AppDb.get(context).recognitions().allOnce().size
            val raw = payload("SYNTH-UNBIND-01")
            check(HookIngress.offer(context, bundle(raw))) { "Credential was rejected in ACCESSIBILITY mode" }
            val rows = awaitRecords(before, 1)
            check(rows.size == before + 1) { "Expected exactly one new record, got ${rows.size - before}" }
            val fresh = rows.last()
            check(fresh.type == "EXPENSE" && fresh.priceCents == 1L) { "Unexpected record: $fresh" }
            check(fresh.status == RecognitionStatus.PENDING) { "Record should wait for confirmation" }
            check(AppDb.get(context).bills().allOnce().isEmpty()) { "No bill may be written without confirmation" }
            // 重复投递同一凭证：仍然只有一条。
            check(HookIngress.offer(context, bundle(raw)))
            delay(2_000)
            check(AppDb.get(context).recognitions().allOnce().size == before + 1) { "Duplicate delivery created a second record" }
        }

        test("turning_the_switch_off_stops_ingest_without_changing_mode") {
            settings.hookCaptureEnabled = false
            check(!HookIngress.offer(context, bundle(payload("SYNTH-UNBIND-OFF-01"))))
            check(settings.captureMode == CaptureMode.ACCESSIBILITY)
        }

        test("global_pause_also_stops_ingest") {
            settings.hookCaptureEnabled = true
            settings.enabled = false
            check(!HookIngress.offer(context, bundle(payload("SYNTH-UNBIND-PAUSED-01"))))
            settings.enabled = true
        }
    }

    private suspend fun awaitRecords(atLeast: Int, expectedNew: Int): List<RecognitionEntity> {
        withTimeout(30_000) {
            while (AppDb.get(targetContext).recognitions().allOnce().size < atLeast + expectedNew) delay(200)
        }
        return AppDb.get(targetContext).recognitions().allOnce()
    }

    private fun bundle(raw: String) = Bundle().apply {
        putString(HookProtocol.EXTRA_APP, WECHAT)
        putString(HookProtocol.EXTRA_TYPE, HookProtocol.TYPE)
        putString(HookProtocol.EXTRA_PAYLOAD, raw)
    }

    private fun payload(id: String): String {
        val content = mapOf("msg" to mapOf("appmsg" to mapOf(
            "type" to "2000", "title" to "微信转账", "des" to "合成转账记录",
            "wcpayinfo" to mapOf("paysubtype" to "1", "feedesc" to "￥0.01",
                "transferid" to id, "paymsgid" to id, "begintransfertime" to businessSeconds.toString()),
        )))
        return MiniJson.encode(mapOf("type" to "transfer", "isSend" to 1,
            "content" to MiniJson.encode(content), "chatUser" to "synthetic-peer",
            "hookerUser" to "synthetic-peer", "hookUser" to "synthetic-peer", "t" to now,
            "cachedPayTools" to "", "cachedPayMoney" to "", "cachedPayShop" to ""))
    }

    /** 只恢复本用例动过的几个键；用例失败也必须走这里，别把模拟器留在暂停态。 */
    private class Snapshot(private val settings: Settings) {
        private val mode = settings.captureMode
        private val enabled = settings.enabled
        private val autoConfirm = settings.autoConfirm
        private val hook = settings.hookCaptureEnabled
        private val whitelist = settings.whitelist

        fun restore(settings: Settings) {
            mode?.let { settings.selectCaptureMode(it) }
            settings.enabled = enabled
            settings.autoConfirm = autoConfirm
            settings.hookCaptureEnabled = hook
            settings.whitelist = whitelist
        }
    }

    private companion object {
        const val WECHAT = "com.tencent.mm"
    }
}
