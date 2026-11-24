package io.github.mymoney

import android.app.Application
import io.github.mymoney.capture.AccessibilityGuardian
import io.github.mymoney.capture.ConfirmDelivery
import io.github.mymoney.data.CategorySuggester
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.NoteTemplate
import io.github.mymoney.data.RecognitionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import io.github.mymoney.data.Settings
import io.github.mymoney.capture.CaptureModeManager
import com.example.billkit.BillKit
import com.example.billkit.DebugBus

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        RecognitionStore.init(this)
        LedgerConfig.init(this)
        // 规则同步库是四类用户规则的新权威；先初始化它，备注缓存才能读到迁移后的结果。
        runCatching {
            runBlocking(Dispatchers.IO) { io.github.mymoney.data.rulesync.RuleSyncRepository.init(this@App) }
        }.onFailure {
            android.util.Log.e("MyMoney", "规则同步库初始化失败，暂用旧规则源", it)
        }
        io.github.mymoney.data.NoteFillStore.init()
        io.github.mymoney.data.update.AppUpdateStore.init(this)
        io.github.mymoney.data.LedgerPeriodStore.init(this)
        io.github.mymoney.data.AssetStore.init(this)
        io.github.mymoney.data.ledgersync.RealtimeSyncTrigger.init(this)
        io.github.mymoney.data.LocalRuleStore.init(this)
        ConfirmDelivery.ensureChannel(this)
        ensureGuardianChannel()

        io.github.mymoney.systems.SystemIntegration.init(this)
        CaptureModeManager.init(this)
        val settings = Settings(this)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 上一次进程被杀时 Outbox 里可能还有待推项：联网后补一次，不额外轮询。
        scope.launch {
            runCatching {
                if (io.github.mymoney.data.ledgersync.RealtimeSyncTrigger.enabled(this@App) &&
                    io.github.mymoney.data.ledgersync.LedgerSyncRepository.pendingCount(this@App) > 0
                ) {
                    io.github.mymoney.data.ledgersync.RealtimeSyncTrigger.flushNow(this@App)
                }
            }
        }
        scope.launch {
            runCatching { io.github.mymoney.data.BackupStore.completePendingConfiguration(this@App) }
                .onFailure { android.util.Log.e("MyMoney", "恢复配置尚未完成", it) }
        }
        // 规格的 64 条分类正则（js_category.js），只在项目内置分类认不出时补一刀。
        scope.launch { io.github.mymoney.data.SpecCategoryStore.init(this@App) }
        data class PageDelivery(val draft: com.example.billkit.BillDraft, val diagnostic: DebugBus.State?, val token: String, val receivedAt: Long)
        val deliveries = Channel<PageDelivery>(Channel.UNLIMITED)
        scope.launch {
            for ((draft, diagnostic, token, receivedAt) in deliveries) {
                try {
                    fun allowed(): Boolean = settings.enabled && settings.pageEnabled && draft.sourcePkg in settings.whitelist &&
                        token == io.github.mymoney.capture.CaptureConfigToken.of(this@App) &&
                        io.github.mymoney.capture.BillAccessibilityService.instance?.isObservationCurrent(draft.pageObservationId) == true
                    if (!allowed()) { BillKit.retryFailedDraft(draft); continue }
                    val suggestion = io.github.mymoney.data.SpecCategoryStore.fill(this@App, draft,
                        CategorySuggester.suggest(draft))
                    val named = draft.copy(category = suggestion.category, subCategory = suggestion.subCategory, account = suggestion.account)
                    // 账户映射与其它三条通道保持一致：规则/页面给出的是「招商银行信用卡(1234)」这类原文，
                    // 账本账户名是「信用卡」，不映射就挂不上资产。preferred 非空时直接采用，避免覆盖学习结果。
                    val accounted = io.github.mymoney.data.SpecAccountStore.fill(this@App, named, preferred = suggestion.account)
                    val enriched = io.github.mymoney.data.NoteFillStore.prepare(accounted)
                    val accepted = io.github.mymoney.capture.CaptureCoordinator.accept(this@App,
                        io.github.mymoney.capture.CaptureCoordinator.Input(enriched, "PAGE",
                            "page-visit:" + enriched.captureSessionId, receivedAt = receivedAt), allowed = { allowed() })
                    if (accepted == null) { BillKit.retryFailedDraft(draft); continue }
                    enriched.pageObservationId?.let { observation ->
                        io.github.mymoney.ocr.PageOcrAssist.bind(observation, accepted.row.id, enriched,
                            "page-visit:" + enriched.captureSessionId)
                    }
                    val delivery = io.github.mymoney.capture.CaptureCoordinator.deliver(this@App, accepted) { allowed() }
                    if (delivery == "pending-source-disabled" || delivery == "pending-locked")
                        BillKit.retryFailedDraft(draft)
                    if (DebugBus.state.value == diagnostic) DebugBus.annotateOutcome(buildString {
                        if (accepted.decision == "repeat-confirmation") append(" → repeat-confirmation（再次确认）")
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
                    android.util.Log.e("MyMoney", "识别历史或确认投递失败，可重试", error)
                }
            }
        }
        BillKit.install(
            config = settings.toKitConfig(),
            sink = { draft -> check(deliveries.trySend(PageDelivery(draft, DebugBus.state.value,
                draft.captureConfigurationToken ?: "missing-capture-token", System.currentTimeMillis())).isSuccess) },
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
