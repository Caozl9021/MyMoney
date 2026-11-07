package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.capture.island.IslandHost
import io.github.mymoney.data.MiniJson

/**
 * K4 超级岛确认宿主（方案A）真机专项验证。
 *
 * 证什么：在**真实目标机**上，生产代码 [IslandHost.show] 能把一条合成草稿发成超级岛焦点通知
 * ——通知确实进了 NotificationManager（用我们的 channel + 通知 id），且 extras 里带
 * `miui.focus.param`（岛 payload）。随后 [IslandHost.cancel] 能撤掉。
 *
 * 不证什么（明确边界）：
 * - **不代用户保存、不写任何账单**：全程不调用 BillWriter / ConfirmSaveOperation。草稿纯合成。
 * - 「岛是否真的在屏幕上可见」不在此断言：这需要人眼看屏幕。K2 探针 r9/r11 已实测同一 payload
 *   结构可上岛+大岛展开+双文字按钮渲染；本用例只保证生产侧 notify 成功、payload 完整。
 * - 「点岛→确认卡」不在本用例驱动（需要屏幕点击）：该路径复用 ConfirmIntents.intent→ConfirmActivity，
 *   是既有稳定链路；K2 探针已实测点击触发 contentIntent 打开 Activity。
 *
 * 本用例**故意不加 requireEmulator**：岛能力只在真实小米 OS4 上有意义，就是要在这台真机验证。
 * 但用 targetSdk 隔离：不改用户真实账本/偏好，只发一条合成通知并在 finally 撤掉。
 */
class IslandHostInstrumentation : Instrumentation() {

    private val results = mutableListOf<Map<String, Any?>>()
    private var failure: Throwable? = null

    override fun onStart() {
        android.util.Log.i("MyMoneyIsland", "onStart begin")
        try {
            run()
        } catch (error: Throwable) {
            failure = error
            android.util.Log.e("MyMoneyIsland", "onStart failed", error)
        }
        val passed = failure == null && results.isNotEmpty() && results.none { it["status"] == "failed" }
        val reportJson = MiniJson.encode(mapOf<String, Any?>(
            "status" to if (passed) "passed" else "failed",
            "tests" to results.size,
            "results" to results,
            "error" to failure?.stackTraceToString(),
        ))
        android.util.Log.i("MyMoneyIsland", "onStart done passed=$passed report=$reportJson")
        // 落盘一份，即使 am instrument -w 卡住也能取证
        runCatching {
            val f = java.io.File(targetContext.filesDir, "island_host_verify.json")
            f.writeText(reportJson)
            android.util.Log.i("MyMoneyIsland", "report written to ${f.absolutePath}")
        }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", reportJson)
        })
    }

    private fun test(name: String, body: () -> Unit) {
        val start = System.nanoTime()
        try {
            body()
            results += mapOf("name" to name, "status" to "passed", "millis" to (System.nanoTime() - start) / 1_000_000)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        }
    }

    private fun syntheticDraft(): BillDraft = BillDraft(
        priceCents = 1234,
        type = BillType.EXPENSE,
        timeMillis = System.currentTimeMillis(),
        merchant = "合成验证商户",
        method = "零钱",
        orderTail = null,
        sourcePkg = targetContext.packageName,
        strategy = "K4-instrumentation",
        account = "零钱",
        historyId = "k4-island-test-${System.currentTimeMillis()}",
        hasExplicitTime = true,
    )

    private fun run() {
        val ctx = targetContext.applicationContext
        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager

        test("canShow 前提成立（通知权限已授予）") {
            check(IslandHost.canShow(ctx)) { "IslandHost.canShow=false：通知权限未授予或 API<26" }
        }

        val draft = syntheticDraft()

        test("show 成功 post 一条岛通知") {
            val ok = IslandHost.show(ctx, draft, duplicateSuspected = false)
            check(ok) { "IslandHost.show 返回 false（notify 失败）" }
        }

        test("通知确实进 NotificationManager 且 channel 正确") {
            val active = nm.activeNotifications
            val hit = active.firstOrNull { it.packageName == ctx.packageName && it.notification.channelId == IslandHost.CHANNEL_ID }
            check(hit != null) { "未找到 channel=${IslandHost.CHANNEL_ID} 的活动通知；active=${active.map { it.notification.channelId }}" }
        }

        test("extras 带 miui.focus.param 且含 param_v2 结构") {
            val hit = nm.activeNotifications.firstOrNull { it.packageName == ctx.packageName && it.notification.channelId == IslandHost.CHANNEL_ID }
            check(hit != null) { "无岛通知可取 extras" }
            val ex = hit.notification.extras
            val param = ex.getString("miui.focus.param")
            check(!param.isNullOrBlank()) { "miui.focus.param 缺失" }
            check(param!!.contains("param_v2")) { "param 不含 param_v2: $param" }
            check(param.contains("\"scene\":\"template_v2\"")) { "scene 不是 template_v2: $param" }
            check(param.contains("textButton")) { "缺 textButton 双按钮: $param" }
            check(param.contains("mymoney_pay")) { "business 不是 mymoney_pay: $param" }
            // actions/pics bundle 也在
            check(ex.getBundle("miui.focus.actions") != null) { "miui.focus.actions 缺失" }
            check(ex.getBundle("miui.focus.pics") != null) { "miui.focus.pics 缺失" }
        }

        test("cancel 能撤掉该岛通知") {
            IslandHost.cancel(ctx, draft)
            Wait.until(1500) {
                nm.activeNotifications.none { it.packageName == ctx.packageName && it.notification.channelId == IslandHost.CHANNEL_ID }
            }
            check(nm.activeNotifications.none { it.packageName == ctx.packageName && it.notification.channelId == IslandHost.CHANNEL_ID }) {
                "cancel 后仍残留岛通知"
            }
        }
    }

    private object Wait {
        fun until(timeoutMs: Long, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (cond()) return
                Thread.sleep(50)
            }
        }
    }
}
