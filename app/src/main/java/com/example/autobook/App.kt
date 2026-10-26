package com.example.autobook

import android.app.Application
import com.example.autobook.capture.AccessibilityGuardian
import com.example.autobook.capture.ConfirmDelivery
import com.example.autobook.data.CategorySuggester
import com.example.autobook.data.LedgerConfig
import com.example.autobook.data.NoteTemplate
import com.example.autobook.data.RecognitionStore
import com.example.autobook.capture.PageSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import com.example.autobook.data.Settings
import com.example.autobook.ocr.MlKitOcrEngine
import com.example.autobook.capture.CaptureModeManager
import com.example.billkit.BillKit
import com.example.billkit.DebugBus

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        RecognitionStore.init(this)
        LedgerConfig.init(this)
        com.example.autobook.data.AssetStore.init(this)
        com.example.autobook.data.LocalRuleStore.init(this)
        com.example.autobook.data.ExternalRuleStore.init(this)
        com.example.autobook.data.CoverageStore.init(this)
        ConfirmDelivery.ensureChannel(this)
        ensureGuardianChannel()

        com.example.autobook.systems.SystemIntegration.init(this)
        CaptureModeManager.init(this)
        val settings = Settings(this)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            runCatching { com.example.autobook.data.BackupStore.completePendingConfiguration(this@App) }
                .onFailure { android.util.Log.e("AutoBook", "恢复配置尚未完成", it) }
        }
        data class PageDelivery(val draft: com.example.billkit.BillDraft, val diagnostic: DebugBus.State?, val token: String)
        val deliveries = Channel<PageDelivery>(Channel.UNLIMITED)
        scope.launch {
            for ((draft, diagnostic, token) in deliveries) {
                try {
                    fun allowed(): Boolean = settings.enabled && settings.pageFallbackEnabled && draft.sourcePkg in settings.whitelist &&
                        token == com.example.autobook.capture.NotificationIngress.configurationToken(this@App)
                    if (!allowed()) continue
                    val suggestion = CategorySuggester.suggest(draft, candidates = com.example.autobook.data.ExternalRuleStore.candidates())
                    val named = draft.copy(category = suggestion.category, subCategory = suggestion.subCategory, account = suggestion.account)
                    val enriched = named.copy(note = NoteTemplate.render(named).ifBlank { null })
                    val accepted = com.example.autobook.capture.CaptureCoordinator.accept(this@App,
                        com.example.autobook.capture.CaptureCoordinator.Input(enriched, "PAGE",
                            "page:" + com.example.autobook.data.RecognitionIdentity.key(enriched)), allowed = { allowed() }) ?: continue
                    val delivery = com.example.autobook.capture.CaptureCoordinator.deliver(this@App, accepted) { allowed() }
                    if (DebugBus.state.value == diagnostic) DebugBus.annotateOutcome(buildString {
                        append(" → ").append(delivery)
                        suggestion.categoryHit?.let { append(" 分类依据：").append(it.explanation) }
                        suggestion.accountHit?.let { append(" 账户依据：").append(it.explanation) }
                        suggestion.category?.let {
                            append(" [").append(it)
                            suggestion.subCategory?.let { sub -> append('/').append(sub) }
                            append(']')
                        }
                        suggestion.account?.let { append(" (").append(it).append(')') }
                    })
                } catch (error: Exception) {
                    BillKit.retryFailedDraft(draft)
                    if (DebugBus.state.value == diagnostic) DebugBus.annotateOutcome(" → history-failed:可重试")
                    android.util.Log.e("AutoBook", "识别历史或确认投递失败，可重试", error)
                }
            }
        }
        val pageSessions = PageSessionStore(this)
        BillKit.install(
            config = settings.toKitConfig(),
            sink = { draft -> check(deliveries.trySend(PageDelivery(draft, DebugBus.state.value,
                draft.captureConfigurationToken ?: "missing-capture-token")).isSuccess) },
            ocrEngine = MlKitOcrEngine(),
            pageSessionProvider = pageSessions::sessionFor,
        )

        CaptureModeManager.reconcile(this)
    }

    /**
     * 创建守护服务的通知渠道。
     *
     * 渠道定义收敛在 [AccessibilityGuardian.ensureChannel] 里（服务自己启动时也会兜底建一次），
     * 这里只负责在 App 启动时提前建好，避免第一次拉起服务时渠道还没就绪。
     */
    private fun ensureGuardianChannel() {
        AccessibilityGuardian.ensureChannel(this)
    }
}
