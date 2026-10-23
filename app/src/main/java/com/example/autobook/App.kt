package com.example.autobook

import android.app.Application
import com.example.autobook.capture.AccessibilityGuardian
import com.example.autobook.capture.ConfirmDelivery
import com.example.autobook.data.CategorySuggester
import com.example.autobook.data.LedgerConfig
import com.example.autobook.data.NoteTemplate
import com.example.autobook.data.PendingStore
import com.example.autobook.data.Settings
import com.example.autobook.ocr.MlKitOcrEngine
import com.example.autobook.capture.CaptureModeManager
import com.example.billkit.BillKit
import com.example.billkit.DebugBus

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        PendingStore.init(this)
        LedgerConfig.init(this)
        com.example.autobook.data.LocalRuleStore.init(this)
        com.example.autobook.data.ExternalRuleStore.init(this)
        com.example.autobook.data.CoverageStore.init(this)
        ConfirmDelivery.ensureChannel(this)
        ensureGuardianChannel()

        com.example.autobook.systems.SystemIntegration.init(this)
        CaptureModeManager.init(this)
        val settings = Settings(this)

        BillKit.install(
            config = settings.toKitConfig(),
            sink = { draft ->
                // ① 自动分类/账户（只在用户清单里选）
                val suggestion = CategorySuggester.suggest(draft, candidates = com.example.autobook.data.ExternalRuleStore.candidates())

                // ② 备注按模板渲染（模板里可能引用分类/账户，所以要在上一句之后）
                val withNames = draft.copy(
                    category = suggestion.category,
                    subCategory = suggestion.subCategory,
                    account = suggestion.account,
                )
                val note = NoteTemplate.render(withNames).ifBlank { null }
                val enriched = withNames.copy(note = note)

                val delivery = ConfirmDelivery.deliver(this, enriched, settings.autoConfirm)
                DebugBus.annotateOutcome(
                    buildString {
                        append(" → ").append(delivery)
                        suggestion.categoryHit?.let { append(" 分类依据：").append(it.explanation) }
                        suggestion.accountHit?.let { append(" 账户依据：").append(it.explanation) }
                        suggestion.category?.let {
                            append(" [").append(it)
                            suggestion.subCategory?.let { sub -> append('/').append(sub) }
                            append(']')
                        }
                        suggestion.account?.let { append(" (").append(it).append(')') }
                    }
                )
            },
            ocrEngine = MlKitOcrEngine(),
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
