package io.github.caozl9021.mymoney

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.github.caozl9021.mymoney.capture.HookIngress
import io.github.caozl9021.mymoney.data.*
import io.github.caozl9021.mymoney.lsp.HookProtocol
import io.github.caozl9021.mymoney.overlay.ConfirmOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 仅用于一次性 emulator-5586 的真实悬浮投递验证。
 * 合成四回调 → Global SpecRuleStore/HookIngress → Coordinator → ConfirmOverlay。
 * 不直接调用接受/claim/展示API，不读真机raw，不操作第三方App，不点击保存，不删除表或文件。
 */
class WechatPopupInstrumentation : Instrumentation() {
    private var disposable = false
    private var redpacket = false
    private var protectedPayment = false
    private var expectedSerial: String? = null
    private val observations = linkedMapOf<String, Any?>()
    private val snapshots = linkedMapOf<SharedPreferences, Map<String, Any?>>()
    private var entered = false

    override fun onCreate(arguments: Bundle?) {
        disposable = arguments?.getString("disposable") == "true"
        redpacket = arguments?.getString("scenario") == "redpacket"
        protectedPayment = arguments?.getString("scenario") == "protected-payment"
        expectedSerial = arguments?.getString("deviceSerial")
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try {
            requireDisposableEmulator()
            runBlocking(Dispatchers.IO) { exerciseRealDelivery() }
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (entered) {
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        ConfirmOverlay.dismiss()
                        withTimeout(10_000) { while (ConfirmOverlay.isShowing()) delay(50) }
                    }
                }.onFailure { if (failure == null) failure = it }
                for ((prefs, original) in snapshots) {
                    runCatching { restore(prefs, original) }.onFailure { if (failure == null) failure = it }
                }
            }
        }
        val passed = failure == null
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(linkedMapOf(
                "status" to if (passed) "passed" else "failed", "tests" to 1,
                "source" to if (protectedPayment) "synthetic-alipay-through-test-protected-window" else if (redpacket) "synthetic-redpacket-message-and-receipt" else "synthetic-sanitized-four-hook-callbacks", "deviceGuard" to "disposable-emulator-5586-only",
                "actualGlobalIngress" to (observations["callbacksOffered"] == 4),
                "actualUiDelivery" to (observations["visibleConfirmationWindows"] == 1),
                "assertions" to observations, "error" to failure?.stackTraceToString(),
            )))
        })
    }

    private fun requireDisposableEmulator() {
        check(disposable && expectedSerial == "emulator-5586") {
            "Requires explicit -e disposable true -e deviceSerial emulator-5586 and host adb -s emulator-5586"
        }
        check(listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).all { value ->
            listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "vbox").any { value.contains(it, true) }
        } && listOf("goldfish", "ranchu").any { Build.HARDWARE.contains(it, true) }) {
            "Refusing popup verification on a real device"
        }
        val qemu = uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }
        check(qemu == "1") { "Emulator qemu property is absent" }
        check((targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked.not()) {
            "Disposable emulator must be awake and unlocked"
        }
        check(ConfirmOverlay.canShow(targetContext)) { "Grant overlay permission on the disposable emulator before running" }
        check(!ConfirmOverlay.isShowing()) { "Refusing to replace an existing editor" }
    }

    private suspend fun exerciseRealDelivery() {
        val db = AppDb.get(targetContext)
        RecognitionStore.init(targetContext)
        RecognitionStore.awaitReady()
        check(db.bills().allOnce().isEmpty() && db.recognitions().allOnce().isEmpty()) {
            "Refusing to run against an instance containing bills or recognition history"
        }
        check(db.capture().deliveries().isEmpty() && db.rules().recentData(1).isEmpty()) {
            "Requires a fresh disposable instance with no prior capture data"
        }
        val prefs = targetContext.getSharedPreferences("mymoney", Context.MODE_PRIVATE)
        for (name in listOf("mymoney", "ledger_config", "hook_target_diagnostics")) {
            val storage = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            snapshots[storage] = storage.all.mapValues { (_, value) ->
                if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
            }
        }
        entered = true
        check(prefs.edit().putBoolean(Settings.KEY_ENABLED, true)
            .putString(Settings.KEY_CAPTURE_MODE, CaptureMode.ROOT.name)
            .putBoolean(Settings.KEY_ROOT_MODE, true)
            .putBoolean(Settings.KEY_HOOK_CAPTURE_ENABLED, true)
            .putBoolean(Settings.KEY_AUTO_CONFIRM, true)
            .putBoolean(Settings.KEY_NOTIFICATION_CAPTURE_ENABLED, false)
            .putBoolean(Settings.KEY_SMS_CAPTURE_ENABLED, false)
            .putBoolean(Settings.KEY_PAGE_FALLBACK_ENABLED, false)
            .putStringSet(Settings.KEY_WHITELIST, setOf(if (protectedPayment) HookProtocol.APP_ALIPAY else HookProtocol.APP_WECHAT)).commit())

        SpecRuleStore.init(targetContext)
        SpecRuleStore.ensureReady(targetContext)
        val appId = if (protectedPayment) HookProtocol.APP_ALIPAY else HookProtocol.APP_WECHAT
        check(SpecRuleStore.covers(appId, HookProtocol.TYPE)) { "Selected DATA rules are not enabled" }
        if (protectedPayment) {
            val windowInfo = uiAutomation.serviceInfo
            windowInfo.flags = windowInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = windowInfo
            check(context.applicationInfo.uid != targetContext.applicationInfo.uid) { "Protected window must belong to a different UID" }
            targetContext.startActivity(android.content.Intent().setClassName(context.packageName,
                "io.github.caozl9021.mymoney.ProtectedPaymentTestActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(15_000) { while (!protectedWindowVisible()) delay(100) }
        }
        val now = System.currentTimeMillis()
        val transferId = "SYNTH-POPUP-TRANSFER-$now"
        val callbacks = if (protectedPayment) List(4) { alipayPayload(transferId, now) } else if (redpacket) {
            val packetId = "SYNTH-REDPACKET-$now"
            listOf(packetMessage(packetId, now), packetMessage(packetId, now),
                packetReceipt(packetId, now), packetReceipt(packetId, now))
        } else listOf(1, 1, 0, 0).mapIndexed { index, direction ->
            payload(transferId, direction, now + index * 1000, now / 1000 - 5)
        }
        for ((index, raw) in callbacks.withIndex()) {
            check(HookIngress.offer(targetContext, HookProtocol.bundle(appId, raw))) {
                "HookIngress rejected synthetic callback $index"
            }
            if (redpacket && index == 1) {
                withTimeout(15_000) { while (db.rules().recentData(10).size < 2) delay(50) }
                check(db.recognitions().allOnce().isEmpty() && !ConfirmOverlay.isShowing()) {
                    "An amount-free redpacket message must not show confirmation"
                }
                observations["messageWithoutReceiptDidNotPopup"] = true
            }
        }
        observations["callbacksOffered"] = 4

        withTimeout(25_000) {
            while (db.rules().recentData(10).size < 4 ||
                db.capture().deliveries().none { it.state == CaptureDeliveryState.ACKNOWLEDGED }) delay(100)
        }
        val rows = db.recognitions().allOnce()
        val row = rows.single()
        check(row.status == RecognitionStatus.PENDING && row.type == "EXPENSE" && row.priceCents == 1L)
        check(row.transactionKey != null && row.billId == null)
        val deliveries = db.capture().deliveries()
        check(deliveries.single().recognitionId == row.id && deliveries.single().state == CaptureDeliveryState.ACKNOWLEDGED)
        val presented = db.capture().evidence().filter { it.source == "DELIVERY" }
        check(presented.single().outcome == "overlay" && presented.single().recognitionId == row.id) {
            "Actual ConfirmDelivery did not acknowledge the overlay path"
        }
        check(ConfirmOverlay.isShowing()) { "Overlay receipt exists but no editor is currently attached" }
        check(db.bills().allOnce().isEmpty() && db.assets().entries().isEmpty()) { "Presentation must not write a bill or asset entry" }

        val info = uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        uiAutomation.serviceInfo = info
        if (protectedPayment) {
            check(protectedWindowVisible()) { "Protected fixture is no longer foreground" }
            val until = SystemClock.uptimeMillis() + 1500
            while (SystemClock.uptimeMillis() < until) {
                check(visibleOverlayWindows().isEmpty()) { "Overlay must be hidden by the system's protected window" }
                delay(100)
            }
            val hidden = checkNotNull(uiAutomation.takeScreenshot())
            try { File(targetContext.filesDir, "payment-overlay-hidden.png").outputStream().use {
                check(hidden.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { hidden.recycle() }
            observations["protectedWindowHidAttachedOverlay"] = true
            observations["deliveryAcknowledgedBeforeLeavingProtectedWindow"] = true
            uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        }
        var visibleWindows: List<Pair<Int, Set<String>>> = emptyList()
        withTimeout(15_000) {
            do {
                visibleWindows = visibleOverlayWindows()
                if (visibleWindows.isEmpty()) delay(150)
            } while (visibleWindows.isEmpty())
        }
        check(visibleWindows.size == 1) { "Expected exactly one visible confirmation window" }
        val screenshot = checkNotNull(uiAutomation.takeScreenshot()) { "No compositor screenshot was available" }
        val screenshotFile = File(targetContext.filesDir, "wechat-popup-verification.png")
        try {
            check(screenshot.width > 0 && screenshot.height > 0)
            val colors = mutableSetOf<Int>()
            for (x in 0 until screenshot.width step maxOf(1, screenshot.width / 20)) {
                for (y in 0 until screenshot.height step maxOf(1, screenshot.height / 30)) colors += screenshot.getPixel(x, y)
            }
            check(colors.size > 4) { "Screenshot had no meaningful rendered pixels" }
            screenshotFile.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { screenshot.recycle() }
        // 队列四项均已落盘后再核对：只有一个真实呈现记录，无自动写账。
        delay(500)
        check(db.rules().recentData(10).size == 4 && db.recognitions().allOnce().size == 1)
        check(db.capture().deliveries().size == 1 && db.capture().evidence().count { it.source == "DELIVERY" } == 1)
        check(db.bills().allOnce().isEmpty())
        observations.putAll(linkedMapOf("callbacks" to 4, "pendingRecognitions" to 1, "type" to row.type,
            "amountCents" to row.priceCents, "acknowledgedDeliveries" to 1, "deliveryOutcome" to "overlay",
            "visibleConfirmationWindows" to visibleWindows.size, "visibleUiTokens" to visibleWindows.single().second.toList(),
            "screenshot" to screenshotFile.absolutePath, "billCount" to 0, "assetEntryCount" to 0,
            "ruleVersion" to SpecRuleStore.status.value.version, "verifiedAtUptimeMillis" to SystemClock.uptimeMillis()))
    }

    private fun visibleOverlayWindows(): List<Pair<Int, Set<String>>> = uiAutomation.windows.mapNotNull { window ->
        val root = window.root ?: return@mapNotNull null
        val tokens = mutableSetOf<String>()
        var targetPackage = false
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 80 || visited++ > 3000) return
            if (node.isVisibleToUser) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty) {
                    if (node.packageName?.toString() == targetContext.packageName) targetPackage = true
                    node.text?.toString()?.takeIf(String::isNotBlank)?.let(tokens::add)
                    node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(tokens::add)
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        if (targetPackage && tokens.any { it.contains("支出金额") } && tokens.any { it.contains("0.01") } &&
            tokens.any { it.contains("记一笔") } && tokens.any { it.contains("忽略") }) window.id to tokens
        else null
    }

    private fun payload(id: String, direction: Int, arrived: Long, businessSeconds: Long): String {
        val content = mapOf("msg" to mapOf("appmsg" to mapOf("type" to "2000", "title" to "微信转账",
            "des" to "合成转账记录", "wcpayinfo" to mapOf("paysubtype" to "1", "feedesc" to "￥0.01",
                "transferid" to id, "paymsgid" to id, "begintransfertime" to businessSeconds.toString()))))
        return MiniJson.encode(mapOf("type" to "transfer", "isSend" to direction, "content" to MiniJson.encode(content),
            "chatUser" to "synthetic-peer", "hookerUser" to "synthetic-peer", "hookUser" to "synthetic-peer",
            "t" to arrived, "cachedPayTools" to "", "cachedPayMoney" to "", "cachedPayShop" to ""))
    }

    private fun protectedWindowVisible(): Boolean = uiAutomation.windows.any { window ->
        val root = window.root ?: return@any false
        root.packageName?.toString() == context.packageName &&
            root.findAccessibilityNodeInfosByText("TEST PAYMENT WINDOW PROTECTION").isNotEmpty()
    }

    private fun alipayPayload(id: String, now: Long): String = MiniJson.encode(listOf(mapOf(
        "mct" to now / 1000, "pl" to MiniJson.encode(mapOf("title" to "付款成功", "money" to "0.01",
            "content" to MiniJson.encode(mapOf("money" to "0.01", "content" to listOf(
                mapOf("title" to "收款方：", "content" to "隔离测试商户"),
                mapOf("title" to "付款方式：", "content" to "余额"),
                mapOf("title" to "订单号：", "content" to id)))))))))

    private fun packetMessage(id: String, now: Long): String = MiniJson.encode(mapOf(
        "type" to "redPackage", "isSend" to 1, "t" to now,
        "cachedPayMoney" to "", "content" to MiniJson.encode(mapOf("msg" to mapOf("appmsg" to mapOf(
            "type" to "2001", "title" to "微信红包", "des" to "我给你发了一个红包，赶紧去拆!",
            "wcpayinfo" to mapOf("paymsgid" to id, "nativeurl" to
                "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=$id", "sendertitle" to "恭喜发财，大吉大利")
        ))))))

    private fun packetReceipt(id: String, now: Long): String = MiniJson.encode(mapOf(
        "type" to "redPacketReceipt", "packetId" to id, "amountFen" to 1,
        "role" to "sent_total", "phase" to "prepared", "success" to true,
        "observedAt" to now, "adapterVersion" to "wx-8.0.79-3200-v1"))

    private fun restore(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit().clear()
        for ((key, value) in values) when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported preference type for $key")
        }
        check(editor.commit()) { "Unable to restore disposable instance preferences" }
        check(prefs.all == values) { "Preference restoration differs from its snapshot" }
    }
}
