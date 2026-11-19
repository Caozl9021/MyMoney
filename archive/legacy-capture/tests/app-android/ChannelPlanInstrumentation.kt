package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.capture.NotificationPlanState
import io.github.mymoney.capture.PaymentNotificationListener
import io.github.mymoney.capture.RootNotificationIngress
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.data.Settings
import io.github.mymoney.root.RootForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 阶段 3 专项：**通知实现由可用性决定，且 listener 能用时 root 那套一个都不起**。
 *
 * 能证什么、不能证什么要说清楚。模拟器上**没有 root**（AOSP 镜像里没有 `su`），所以
 * 「授权通知 + root 可用时 dumpsys 不启动」里「root 可用」那一半在这里造不出来。反过来，
 * 「root 不可用时绝不会以为有 root 而乱起服务」正好是这里能钉死的：
 *
 * - listener 授权并连通 → 实现必须是 LISTENER，`RootForegroundService` 不活跃，
 *   `RootNotificationIngress` 不轮询；
 * - 未授权 → 模拟器没有 root，实现只能是空，同样不起任何 root 侧的东西；
 * - 关掉通知开关 → 实现为空，仍然不起 root 服务。
 *
 * 迟滞本身（连续两次确认才降级）**不在这里证**：没有 root 就观察不到降级，判定表与状态机
 * 只能靠 `ChannelSelectionTest`。试过用 `NotificationListenerService.requestUnbind()` 真造一次
 * 「授权但掉线」，但它会立刻触发 `requestReconnect` 重绑，采样窗口里观察不到掉线
 * （`sawUnbound=false`），于是删掉了那一格——留一个观察不到现象的用例比没有更糟。
 *
 * 授权状态由宿主用 `adb shell settings put secure enabled_notification_listeners …` 在两次
 * 运行之间切换——测试进程里没有 WRITE_SECURE_SETTINGS，也不该为它往正式包塞权限。
 */
class ChannelPlanInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()

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
                "rootOnDevice" to false,
                "results" to results,
                "error" to failure?.stackTraceToString(),
            )))
        })
    }

    private fun requireEmulator() {
        check(listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).all { value ->
            listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "vbox").any { value.contains(it, true) }
        }) { "Refusing to touch capture wiring on a real device" }
    }

    private suspend fun test(name: String, body: suspend () -> Unit) {
        val start = System.nanoTime()
        try {
            withTimeout(120_000) { body() }
            results += mapOf("name" to name, "status" to "passed", "millis" to (System.nanoTime() - start) / 1_000_000)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        }
    }

    private suspend fun runCases() {
        val context = targetContext
        val settings = Settings(context)
        settings.autoConfirm = false
        settings.enabled = true
        settings.notificationCaptureEnabled = true

        val authorized = PaymentNotificationListener.isAuthorized(context)
        if (authorized) {
            // 宿主已经把我们的组件写进授权名单，系统会把它绑上；给它一点时间。
            withTimeout(60_000) { while (!PaymentNotificationListener.bound.value) delay(200) }
        }
        settle()

        test("listener_connected_means_listener_implementation_and_no_root_service") {
            CaptureModeManager.reconcile(context)
            settle()
            val plan = NotificationPlanState.value
            results += mapOf("observed" to mapOf(
                "authorized" to PaymentNotificationListener.isAuthorized(context),
                "bound" to PaymentNotificationListener.bound.value,
                "plan" to plan?.name,
            ))
            if (PaymentNotificationListener.bound.value) {
                // 这条路自己通着，就不该再起一个 root 前台服务、更不该 5 秒一次 su dumpsys。
                check(plan == NotificationPath.LISTENER) { "listener 连通时实现应为 LISTENER，实际 $plan" }
            } else {
                check(plan != NotificationPath.DUMPSYS) { "模拟器没有 root，不该给出 DUMPSYS" }
            }
            check(!RootForegroundService.active) { "listener 可用时不该有 root 前台服务" }
            check(!RootNotificationIngress.running) { "listener 可用时不该有 dumpsys 轮询" }
        }

        test("switching_the_channel_off_keeps_root_side_idle") {
            settings.notificationCaptureEnabled = false
            settle()
            CaptureModeManager.reconcile(context)
            settle()
            check(NotificationPlanState.value == null) { "关掉开关后不该还有实现：${NotificationPlanState.value}" }
            check(!RootForegroundService.active)
            check(!RootNotificationIngress.running)
            settings.notificationCaptureEnabled = true
            settle()
        }
    }

    private suspend fun settle() = delay(600)

    private class Snapshot(private val settings: Settings) {
        private val enabled = settings.enabled
        private val notifications = settings.notificationCaptureEnabled
        private val autoConfirm = settings.autoConfirm

        fun restore(settings: Settings) {
            settings.enabled = enabled
            settings.notificationCaptureEnabled = notifications
            settings.autoConfirm = autoConfirm
        }
    }
}
