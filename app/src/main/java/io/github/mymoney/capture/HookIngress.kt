package io.github.mymoney.capture

import android.content.Context
import android.util.Log
import io.github.mymoney.data.*
import io.github.mymoney.lsp.HookProtocol
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

    private const val PENDING_REPLAY_WINDOW_MS = 15 * 60 * 1000L
    private var replayStarted = false
    private var wasReady = false

    /**
     * 应用启动时调用一次：规则就绪后重放「到达时规则还没就绪」的凭证。
     *
     * 只在就绪状态「从不就绪变成就绪」的那一次触发，避免状态流每次抖动都全量重放。
     * 重放走同一条 [process] 路径，观测身份仍由 payload 哈希决定，
     * 因此重复重放不会产生第二笔账单（去重在 [CaptureCoordinator] 那一层）。
     * 代价是重放会再写一条 `app_data`：原文只留在 `process` 里落库那一次，
     * 原始行保留为「这条数据到达过」的证据，不算重复入账。
     */
    fun start(context: Context) {
        if (replayStarted) return
        replayStarted = true
        val app = context.applicationContext
        scope.launch {
            SpecRuleStore.status.collect { s ->
                if (!s.ready) {
                    wasReady = false
                    return@collect
                }
                if (wasReady) return@collect
                wasReady = true
                runCatching { replayPending(app) }.onFailure { Log.w(TAG, "pending 重放失败：${it.message}") }
            }
        }
    }

    private suspend fun replayPending(context: Context) {
        val since = System.currentTimeMillis() - PENDING_REPLAY_WINDOW_MS
        replayPendingRows(
            rows = SpecRuleStore.pendingData(context, since),
            ready = { SpecRuleStore.isReady() },
            usable = { row ->
                val settings = Settings(context)
                settings.hookChannelOn() && row.app in settings.whitelist &&
                    row.app in HookProtocol.hookedPackages
            },
            process = { row -> process(Event(context, row.app, row.type, row.data, row.time)) },
            clear = { id -> SpecRuleStore.clearPending(context, id) },
        )
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
        if (payload.length < 8) return false
        val settings = Settings(app)
        if (!hookIngestAllowed(settings.enabled, settings.hookCaptureEnabled,
                whitelisted = appId in settings.whitelist,
                hooked = appId in HookProtocol.hookedPackages)) return false
        // 不在这里判 `covers()`：规则引擎可能还在冷启动初始化，此时 covers 恒为 false。
        // 用它当门禁等于「规则没就绪就把这笔凭证扔掉」——投递是一次性的，丢掉没有第二次机会。
        // 真正的判定在 [process] 里，由 `SpecRuleStore.parseDetailed` 先落盘再求值；
        // 仍然没就绪时它会把原始数据标成 pending，规则恢复后由 [replayPending] 重放。
        HookTargetMonitor.received(app, appId)
        val queued = events.trySend(Event(app, appId, type, payload, System.currentTimeMillis())).isSuccess
        if (queued) ChannelHeartbeat.record(ChannelHeartbeat.Channel.HOOK)
        return queued
    }

    private suspend fun process(event: Event) {
        val settings = Settings(event.context)
        // 入队到处理之间用户可能关了通道：和通知/短信通道一样，投递前再判一次。
        if (!settings.hookChannelOn() || event.app !in settings.whitelist) return
        val parsed = runCatching { SpecRuleStore.parseDetailed(event.context, event.app, event.type, event.payload) }.getOrNull()
        val hit = parsed?.hit
        // 凭证内容本身就是这条观测的身份：微信同一段描述在 TTL 内只会写一次，
        // 支付宝同一个 pl 重复下发也只该记一笔。用内容哈希而不是「当前时间」，
        // 这样补发/重放会被认成同一条观测。
        val observationKey = "hook:" + CaptureMergePolicy.digest(event.app + "|" + event.payload)
        val eventId = observationKey
        // 规则通道当时不可用：原始数据已标 pending 落库，等 [replayPending] 重放。
        // 这里**不写**「未命中」证据——否则等重放真的记了账，证据里还留着一条 rule-error。
        if (parsed?.pending == true) {
            publish(NotificationDiagnostic(eventId, event.app, event.t, "pending-eval",
                "识别规则尚未就绪，凭证已暂存，就绪后自动重试"))
            return
        }
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
                s.hookChannelOn() && event.app in s.whitelist
            }) ?: return
        val review = accepted.reviewReason?.let { CaptureReviewPolicy.explanation(it, accepted.reviewSuppressed) }
        val delivery = CaptureCoordinator.deliver(event.context, accepted) {
            val s = Settings(event.context)
            s.hookChannelOn() && event.app in s.whitelist
        }
        publish(NotificationDiagnostic(eventId, event.app, event.t,
            if (review != null) "$delivery · 待核对" else delivery,
            if (review != null) review else "规则命中：${hit.ruleName}"))
    }

    /**
     * 凭证通道是否可用：只看总开关与它自己的开关，**不再看 [CaptureMode]**。
     *
     * 这里原先还要求 `captureMode == ROOT`，把两件不相干的事绑成了一件：模块跑在微信/支付宝
     * 自己的进程里，经 provider 把凭证交回来，宿主这边用无障碍还是 root 都不影响它被注入。
     * 继续绑着的实际后果是——选了无障碍的用户永远打不开这条最准的通道，
     * 而已经 root 的人又没法同时用页面辅助。
     */
    private fun Settings.hookChannelOn(): Boolean = enabled && hookCaptureEnabled
}

/** 一条 pending 凭证重放时该做什么。 */
internal enum class ReplayAction { CLEAR, KEEP, PROCESS }

/**
 * 重放判定单独提成纯函数，是因为这三条分支决定了「凭证会不会被悄悄丢掉」：
 * 通道被关就清标记（用户明确不要了），规则没就绪就留着（等下次），否则处理并清标记。
 * 任何一条写反，代价都是一笔真实账单失踪——值得用单测钉住而不是靠读代码。
 */
internal fun replayAction(usable: Boolean, ready: Boolean): ReplayAction = when {
    !usable -> ReplayAction.CLEAR
    !ready -> ReplayAction.KEEP
    else -> ReplayAction.PROCESS
}

/**
 * 凭证入口门禁，纯函数便于按住这条矩阵。
 *
 * 旧实现里还藏着一个 `captureMode == ROOT`：那是把「模块怎么被注入」和「宿主选哪种采集方式」
 * 混成了一件，结果是选了无障碍的用户永远打不开这条通道。现在只剩四个真正的条件。
 */
internal fun hookIngestAllowed(
    enabled: Boolean, hookEnabled: Boolean, whitelisted: Boolean, hooked: Boolean,
): Boolean = enabled && hookEnabled && whitelisted && hooked

/**
 * 重放循环本身。叶子动作（判可用、处理、清标记）由调用方给，
 * 这样隔离内存 Room 能跑**同一段**循环，而不是在测试里重写一份再自称验证。
 */
internal suspend fun replayPendingRows(
    rows: List<AppDataEntity>,
    ready: () -> Boolean,
    usable: (AppDataEntity) -> Boolean,
    process: suspend (AppDataEntity) -> Unit,
    clear: suspend (Long) -> Unit,
) {
    for (row in rows) {
        when (replayAction(usable(row), ready())) {
            // 用户关掉通道或把应用移出白名单：不再重试，清掉标记。
            ReplayAction.CLEAR -> clear(row.id)
            // 规则又没就绪：留着，等下一次就绪再试，不要把它算作已处理。
            ReplayAction.KEEP -> Unit
            ReplayAction.PROCESS -> {
                process(row)
                clear(row.id)
            }
        }
    }
}
