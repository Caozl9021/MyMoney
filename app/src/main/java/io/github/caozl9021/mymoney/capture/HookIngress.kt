package io.github.caozl9021.mymoney.capture

import android.content.Context
import android.util.Log
import io.github.caozl9021.mymoney.data.*
import io.github.caozl9021.mymoney.lsp.HookProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LSPosed 采集通道（SPEC §8 阶段 3/4）。
 *
 * 模块在微信/支付宝进程里只做一件事：把 App 自己已经拿到的结构化交易凭证原样交出来
 * （微信 WCDB 的 `ContentValues`、支付宝长连接的 `SyncMessage`）。这里负责剩下的全部判定：
 * 落 `app_data` 原始留档 → 原创规则求值 → 分类 → 账户映射 → 去重合并 → 投递确认。
 *
 * 为什么判定不放在目标进程里：规则求值、分类引擎和 Room 事务都由宿主负责，
 * 放进微信进程既拖慢它又拿不到宿主数据库；而且 `app_data` 必须是规则判定的唯一事实来源
 * （SPEC §7），只能由宿主写。
 *
 * 这条通道的价值在于**拿到 App 自己的账**：微信支付凭证的 `title/description` 是微信写的，
 * 比通知栏更完整（有商户全名、支付方式、订单尾号），也是 SPEC §0 里优先级最高的来源。
 */
object HookIngress {

    private const val TAG = "HookIngress"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(32)

    private val mutableLatest = MutableStateFlow<NotificationDiagnostic?>(null)
    val latest = mutableLatest.asStateFlow()
    private val mutableRecent = MutableStateFlow<List<NotificationDiagnostic>>(emptyList())
    val recent = mutableRecent.asStateFlow()

    private data class Event(val context: Context, val app: String, val type: String, val payload: String, val t: Long)

    init {
        scope.launch {
            for (event in events) {
                runCatching { process(event) }.onFailure {
                    Log.w(TAG, "LSPosed 采集处理失败：${it.message}")
                    publish(NotificationDiagnostic("hook-error", event.app, event.t, "storage-or-processing-failed",
                        "采集到的凭证处理失败；不影响其它通道"))
                }
            }
        }
    }

    @Synchronized
    private fun publish(diagnostic: NotificationDiagnostic) {
        mutableLatest.value = diagnostic
        mutableRecent.value = (listOf(diagnostic) + mutableRecent.value.filterNot { it.traceId == diagnostic.traceId }).take(20)
    }

    /**
     * provider 入口。门禁在入队前判完——队列里不该出现用户没开启的通道的数据。
     *
     * @return true 表示这条凭证已交给规则通道
     */
    fun offer(context: Context, extras: android.os.Bundle): Boolean {
        val app = context.applicationContext
        val appId = extras.getString(HookProtocol.EXTRA_APP) ?: return false
        val type = extras.getString(HookProtocol.EXTRA_TYPE) ?: HookProtocol.TYPE
        val payload = extras.getString(HookProtocol.EXTRA_PAYLOAD) ?: return false
        if (appId !in HookProtocol.hookedPackages) return false
        if (payload.length < 8) return false
        val settings = Settings(app)
        if (!settings.enabled || !settings.hookCaptureEnabled || settings.captureMode != CaptureMode.ROOT || appId !in settings.whitelist) return false
        if (!SpecRuleStore.covers(appId, type)) return false
        HookTargetMonitor.received(app, appId)
        return events.trySend(Event(app, appId, type, payload, System.currentTimeMillis())).isSuccess
    }

    private suspend fun process(event: Event) {
        val settings = Settings(event.context)
        // 入队到处理之间用户可能关了通道：和通知/短信通道一样，投递前再判一次。
        if (!settings.enabled || !settings.hookCaptureEnabled || settings.captureMode != CaptureMode.ROOT || event.app !in settings.whitelist) return
        val parsed = runCatching { SpecRuleStore.parseDetailed(event.context, event.app, event.type, event.payload) }.getOrNull()
        val hit = parsed?.hit
        // 凭证内容本身就是这条观测的身份：微信同一段描述在 TTL 内只会写一次，
        // 支付宝同一个 pl 重复下发也只该记一笔。用内容哈希而不是「当前时间」，
        // 这样补发/重放会被认成同一条观测。
        val observationKey = "hook:" + CaptureMergePolicy.digest(event.app + "|" + event.payload)
        val eventId = observationKey
        if (hit == null) {
            val reason = when (parsed?.outcome) {
                com.example.billkit.spec.SpecRuleEngine.Outcome.NO_MATCH -> "no-rule"
                com.example.billkit.spec.SpecRuleEngine.Outcome.REJECTED -> "rule-rejected"
                else -> "rule-error"
            }
            AppDb.get(event.context).capture().putEvidence(
                CaptureEvidence(eventId, null, "HOOK", event.app, event.t, reason,
                    "payloadHash=" + CaptureMergePolicy.digest(event.payload).take(16)),
            )
            publish(NotificationDiagnostic(eventId, event.app, event.t, reason,
                when (reason) {
                    "rule-rejected" -> "交易字段未通过安全校验；不生成账单"
                    "rule-error" -> "规则执行或原始记录保存失败；不生成账单"
                    else -> "采集到的凭证未命中本地规则；不生成账单"
                }))
            return
        }
        val suggestion = SpecCategoryStore.fill(event.context, hit.draft,
            CategorySuggester.suggest(hit.draft, candidates = ExternalRuleStore.candidates()))
        val named = hit.draft.copy(category = suggestion.category, subCategory = suggestion.subCategory,
            account = suggestion.account ?: hit.draft.account)
        // SPEC §8 阶段 6：账户原文（`招商银行信用卡(1234)`）留在 method，account 换成账本账户。
        val accounted = SpecAccountStore.fill(event.context, named, preferred = suggestion.account)
        val enriched = accounted.copy(note = NoteTemplate.render(accounted).ifBlank { null })
        val accepted = CaptureCoordinator.accept(event.context,
            CaptureCoordinator.Input(enriched, "HOOK", observationKey, eventId, event.t), allowed = {
                val s = Settings(event.context)
                s.enabled && s.hookCaptureEnabled && s.captureMode == CaptureMode.ROOT && event.app in s.whitelist
            }) ?: return
        val review = accepted.reviewReason?.let { CaptureReviewPolicy.explanation(it, accepted.reviewSuppressed) }
        val delivery = CaptureCoordinator.deliver(event.context, accepted) {
            val s = Settings(event.context)
            s.enabled && s.hookCaptureEnabled && s.captureMode == CaptureMode.ROOT && event.app in s.whitelist
        }
        publish(NotificationDiagnostic(eventId, event.app, event.t,
            if (review != null) "$delivery · 待核对" else delivery,
            if (review != null) review else "规则命中：${hit.ruleName}"))
    }
}
