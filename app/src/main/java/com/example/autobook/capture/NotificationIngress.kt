package com.example.autobook.capture

import android.content.Context
import com.example.autobook.data.*
import com.example.billkit.notification.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No raw notification body is persisted; only an explicitly previewed redacted sample may be saved. */
data class NotificationDiagnostic(val traceId: String, val packageName: String, val receivedAt: Long,
    val outcome: String, val summary: String)

data class NotificationSampleOption(val id: String, val packageName: String, val receivedAt: Long,
    val outcome: String, val diagnosticOnly: Boolean)

object NotificationIngress {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parser = NotificationParser() // G0: no production template until real-source review.
    private val events = Channel<Pair<Context, NotificationObservation>>(64)
    private val mutableLatest = MutableStateFlow<NotificationDiagnostic?>(null)
    val latest = mutableLatest.asStateFlow()
    private val mutableRecent = MutableStateFlow<List<NotificationDiagnostic>>(emptyList())
    val recent = mutableRecent.asStateFlow()
    private val buffer = NotificationSampleBuffer()
    private val mutableSampleOptions = MutableStateFlow<List<NotificationSampleOption>>(emptyList())
    val sampleOptions = mutableSampleOptions.asStateFlow()
    @Volatile private var sampleContext: Context? = null

    init { scope.launch { for ((context, observation) in events) {
        runCatching { process(context, observation) }.onFailure {
            publish(NotificationDiagnostic(observation.eventId, observation.packageName,
                observation.receivedAt, "storage-or-processing-failed", "保留页面采集；请检查本地存储和识别历史"))
        }
    } } }

    @Synchronized private fun publish(diagnostic: NotificationDiagnostic) {
        mutableLatest.value = diagnostic
        mutableRecent.value = (listOf(diagnostic) + mutableRecent.value.filterNot { it.traceId == diagnostic.traceId }).take(20)
    }
    suspend fun restoreLatestDiagnostic(context: Context) {
        if (mutableRecent.value.isNotEmpty()) return
        val saved = AppDb.get(context).capture().evidence().filter { it.source == "NOTIFICATION" }.take(20)
        synchronized(this) {
            if (mutableRecent.value.isEmpty()) {
                mutableRecent.value = saved.map { NotificationDiagnostic(it.eventId, it.packageName,
                    it.receivedAt, it.outcome, "历史诊断元数据；不重播、不恢复正文") }
                mutableLatest.value = mutableRecent.value.firstOrNull()
            }
        }
    }
    private fun refreshSamples(context: Context) {
        val settings = Settings(context)
        if (!settings.enabled || !settings.notificationCaptureEnabled || !PaymentNotificationListener.isAuthorized(context)) {
            buffer.clear()
        }
        val available = buffer.entries(System.currentTimeMillis(), configurationToken(context))
            .filter { it.observation.packageName in settings.whitelist }
        mutableSampleOptions.value = available.map { NotificationSampleOption(it.id, it.observation.packageName,
            it.observation.receivedAt, it.outcome, it.diagnosticOnly) }
    }
    /** User gesture only. Historical active notifications are NEVER offered to the transaction pipeline. */
    suspend fun captureCurrentDiagnostics(context: Context): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val observations = PaymentNotificationListener.currentDiagnosticSamples(app)
        val db = AppDb.get(app)
        for (o in observations.asReversed()) {
            if (!allowed(app, o)) continue
            val result = parser.parse(o.copy(isReconnectBaseline = false))
            val reason = when (result) {
                is NotificationParseResult.Candidate -> "candidate-not-executed"
                is NotificationParseResult.Ignored -> result.reason
                is NotificationParseResult.NeedsEvidence -> result.reason
            }
            val eventId = "manual:" + java.util.UUID.randomUUID().toString()
            buffer.add(o, "manual:" + reason, diagnosticOnly = true)
            sampleContext = app
            db.capture().putEvidence(CaptureEvidence(eventId, null, "NOTIFICATION", o.packageName,
                o.receivedAt, "manual:" + reason, "diagnosticOnly=true;summary=" + o.isGroupSummary))
            publish(NotificationDiagnostic(eventId, o.packageName, o.receivedAt, "manual:" + reason,
                "用户主动读取的现存通知；仅诊断，不新建交易或投递确认卡"))
        }
        db.capture().pruneDiagnostics()
        refreshSamples(app)
        observations.size
    }
    fun configurationToken(context: Context): String {
        val s = Settings(context)
        return CaptureMergePolicy.digest(MiniJson.encode(listOf(s.captureRevision, s.enabled,
            s.notificationCaptureEnabled, s.pageFallbackEnabled, s.captureMode?.name, s.autoConfirm, s.whitelist.sorted())))
    }
    fun offer(context: Context, observation: NotificationObservation) {
        if (!events.trySend(context.applicationContext to observation).isSuccess) publish(
            NotificationDiagnostic(observation.eventId, observation.packageName, observation.receivedAt,
                "queue-full", "通知输入过多，本次未处理；页面辅助仍可用"))
    }
    private fun allowed(context: Context, o: NotificationObservation): Boolean {
        val s = Settings(context)
        return s.enabled && s.notificationCaptureEnabled && o.packageName in s.whitelist &&
            o.configurationToken == configurationToken(context) && PaymentNotificationListener.isAuthorized(context)
    }
    private suspend fun process(context: Context, observation: NotificationObservation) {
        if (!allowed(context, observation)) return
        val result = parser.parse(observation)
        val reason = when (result) {
            is NotificationParseResult.Candidate -> "candidate:${result.templateId}"
            is NotificationParseResult.Ignored -> result.reason
            is NotificationParseResult.NeedsEvidence -> result.reason
        }
        // Summaries, chats and empty updates never erase the still-valid child samples.
        buffer.add(observation, reason)
        sampleContext = context.applicationContext
        refreshSamples(context)
        if (!allowed(context, observation)) return
        val db = AppDb.get(context)
        val eventId = CaptureMergePolicy.digest(observation.observationKey + ":" + observation.contentHash)
        var reviewSummary: String? = null
        val outcome = if (result is NotificationParseResult.Candidate) {
            val suggestion = CategorySuggester.suggest(result.draft, candidates = ExternalRuleStore.candidates())
            val named = result.draft.copy(category = suggestion.category, subCategory = suggestion.subCategory, account = suggestion.account)
            val enriched = named.copy(note = NoteTemplate.render(named).ifBlank { null })
            val accepted = CaptureCoordinator.accept(context, CaptureCoordinator.Input(enriched, "NOTIFICATION",
                observation.observationKey, eventId, observation.receivedAt, observation.reviewSlotKey), allowed = { allowed(context, observation) }) ?: return
            // Say why a real notification produced no card: the review gate is invisible otherwise.
            reviewSummary = accepted.reviewReason?.let { CaptureReviewPolicy.explanation(it, accepted.reviewSuppressed) }
            val delivery = CaptureCoordinator.deliver(context, accepted) { allowed(context, observation) }
            // Replace the entry already buffered above so the sample list shows the review outcome.
            if (accepted.reviewReason != null) {
                buffer.add(observation, "$reason · 待核对")
                refreshSamples(context)
            }
            if (reviewSummary != null) "$delivery · 待核对：$reviewSummary" else delivery
        } else {
            db.capture().putEvidence(CaptureEvidence(eventId, null, "NOTIFICATION", observation.packageName,
                observation.receivedAt, reason, "contentHash=" + observation.contentHash.take(16) + ";truncated=" + observation.truncated))
            db.capture().pruneDiagnostics()
            reason
        }
        publish(NotificationDiagnostic(eventId, observation.packageName, observation.receivedAt,
            outcome, when {
                observation.isGroupSummary -> "分组汇总已跳过；不代表交易子通知未到，可查看近期事件和有效子通知样本"
                result is NotificationParseResult.NeedsEvidence -> "此通知未匹配已支持模板；未生成账单或弹框"
                reviewSummary != null -> reviewSummary
                else -> "通知输入已处理，正文不写入诊断记录"
            }))
    }

    /** Keep only format words and currency amounts, not free-form names, chats or order numbers. */
    private fun redacted(value: String?): String? = value?.let { text ->
        val tokens = Regex("你有一笔\\s*\\d{1,7}(?:\\.\\d{1,2})?\\s*元的支出|交易提醒|支付成功|付款成功|交易成功|支出|付款|支付|金额|人民币|失败|取消|待支付|微信支付|支付宝|红包|小荷包|奖励|优惠|[¥￥]\\s*\\d{1,7}(?:\\.\\d{1,2})?(?![\\d.])|(?<![\\d.])\\d{1,7}(?:\\.\\d{1,2})?\\s*元")
        tokens.findAll(text.take(2048)).joinToString(" … ") { it.value }.ifBlank { "[自由文本已隐藏]" }
    }
    fun clearInvalidSample(context: Context) = refreshSamples(context)
    fun sampleForExport(id: String? = null): String? {
        val context = sampleContext ?: return null
        refreshSamples(context)
        val candidates = buffer.entries(System.currentTimeMillis(), configurationToken(context))
        val chosen = if (id == null) candidates.firstOrNull { !it.observation.isGroupSummary } ?: candidates.firstOrNull()
            else candidates.firstOrNull { it.id == id }
        val entry = chosen ?: return null
        val old = entry.observation
        if (!allowed(context, old)) { refreshSamples(context); return null }
        return old.let { o -> MiniJson.encode(linkedMapOf(
        "schema" to "AutoBookNotificationSample-v2", "realSourceTemplateVerified" to false,
        "diagnosticOnly" to entry.diagnosticOnly, "outcome" to entry.outcome, "postTime" to o.postTime,
        "package" to o.packageName, "receivedAt" to o.receivedAt,
        "observationKeyHash" to CaptureMergePolicy.digest(o.observationKey),
        "titleFormat" to redacted(o.title), "textFormat" to redacted(o.text), "bigTextFormat" to redacted(o.bigText),
        "channelIdHash" to o.channelId?.let(CaptureMergePolicy::digest),
        "category" to o.category?.takeIf { it in setOf("msg", "service", "status", "promo", "event", "progress", "call", "email", "reminder") },
        "templateHash" to o.template?.let(CaptureMergePolicy::digest), "groupSummary" to o.isGroupSummary, "messagingStyle" to o.isMessagingStyle,
        "truncated" to o.truncated, "note" to "仅格式词和币种金额；不证明付款成功或模板可信。原文仅内存且不自动保存。"
    )) }
    }
}
