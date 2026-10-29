package com.example.autobook.capture

import android.content.Context
import android.util.Log
import com.example.autobook.data.*
import com.example.billkit.sms.SmsDedup
import com.example.billkit.sms.SmsPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短信通道（SPEC §8 阶段 2）。
 *
 * 银行消费短信是免 root 就能拿到的结构化交易事实，而且比通知更不容易被厂商 ROM 拦：
 * `SMS_RECEIVED` 是系统级广播，只要授权就能收到。代价是它**只覆盖绑了手机号的账户**，
 * 且营销短信会污染输入——所以判定完全交给 24 条银行规则，这里只做搬运。
 *
 * 与通知通道的区别：
 * - 通知走「已验证模板优先，规则兜底」；短信没有已验证模板，直接走规则。
 * - 短信的 `source` 记为 `SMS`，参与跨源同额合并（同一笔刷卡可能同时来短信和 App 通知）。
 *
 * 诊断记录与通知通道同构，方便在同一个诊断页里看两类输入的处理结论。
 */
object SmsIngress {

    private const val TAG = "SmsIngress"

    /** 规则里的 app 值固定是 com.android.phone（SPEC §8 阶段 2），不是短信来源号码。 */
    const val APP = "com.android.phone"
    const val TYPE = "DATA"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(32)
    private val dedup = SmsDedup()

    private val mutableLatest = MutableStateFlow<NotificationDiagnostic?>(null)
    val latest = mutableLatest.asStateFlow()
    private val mutableRecent = MutableStateFlow<List<NotificationDiagnostic>>(emptyList())
    val recent = mutableRecent.asStateFlow()

    private data class Event(val context: Context, val sender: String, val body: String, val t: Long)

    init {
        scope.launch {
            for (event in events) {
                runCatching { process(event) }.onFailure {
                    Log.w(TAG, "短信处理失败：${it.message}")
                    publish(NotificationDiagnostic("sms-error", APP, event.t, "storage-or-processing-failed",
                        "短信已收到但处理失败；不影响其它通道"))
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
     * 接收器入口。所有门禁在入队前判完——队列里不该出现用户没开启的通道的数据。
     *
     * @return true 表示这条短信已交给规则通道（便于单测与诊断计数）
     */
    fun offer(context: Context, sender: String?, body: String?, t: Long): Boolean {
        val app = context.applicationContext
        val settings = Settings(app)
        if (!settings.enabled || !settings.smsCaptureEnabled) return false
        val payload = SmsPayload.build(sender, body, t) ?: return false
        if (!SpecRuleStore.covers(APP, TYPE)) return false
        if (!dedup.accept(sender, payload, System.currentTimeMillis())) return false
        return events.trySend(Event(app, sender.orEmpty(), payload, t)).isSuccess
    }

    private suspend fun process(event: Event) {
        val settings = Settings(event.context)
        // 入队到处理之间用户可能关了通道：和通知通道一样，投递前再判一次。
        if (!settings.enabled || !settings.smsCaptureEnabled) return
        val parsed = runCatching { SpecRuleStore.parseDetailed(event.context, APP, TYPE, event.body) }.getOrNull()
        val hit = parsed?.hit
        val observationKey = "sms:" + CaptureMergePolicy.digest(event.sender + "|" + event.body)
        val eventId = CaptureMergePolicy.digest(observationKey + ":" + event.t)
        if (hit == null) {
            val reason = when (parsed?.outcome) {
                com.example.billkit.spec.SpecRuleEngine.Outcome.NO_MATCH -> "no-rule"
                com.example.billkit.spec.SpecRuleEngine.Outcome.REJECTED -> "rule-rejected"
                else -> "rule-error"
            }
            AppDb.get(event.context).capture().putEvidence(
                CaptureEvidence(eventId, null, "SMS", APP, event.t, reason,
                    "senderHash=" + CaptureMergePolicy.digest(event.sender).take(16)),
            )
            publish(NotificationDiagnostic(eventId, APP, event.t, reason,
                when (reason) {
                    "rule-rejected" -> "短信交易字段未通过安全校验；不生成账单"
                    "rule-error" -> "规则执行或原始记录保存失败；不生成账单"
                    else -> "短信未命中银行规则；不生成账单"
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
            CaptureCoordinator.Input(enriched, "SMS", observationKey, eventId, event.t), allowed = {
                val s = Settings(event.context)
                s.enabled && s.smsCaptureEnabled
            }) ?: return
        val review = accepted.reviewReason?.let { CaptureReviewPolicy.explanation(it, accepted.reviewSuppressed) }
        val delivery = CaptureCoordinator.deliver(event.context, accepted) {
            val s = Settings(event.context)
            s.enabled && s.smsCaptureEnabled
        }
        publish(NotificationDiagnostic(eventId, APP, event.t,
            if (review != null) "$delivery · 待核对" else delivery,
            if (review != null) review else "规则命中：${hit.ruleName}"))
    }

    /** 通道开关变化时清掉去重表：用户关掉又打开，不该因为 TTL 内重复而漏掉新短信。 */
    fun resetDedup() = dedup.clear()
}
