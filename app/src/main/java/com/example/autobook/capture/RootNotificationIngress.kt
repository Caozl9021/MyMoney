package com.example.autobook.capture

import android.content.Context
import android.util.Log
import com.example.autobook.data.*
import com.example.billkit.root.NotificationDumpDedup
import com.example.billkit.root.RootNotificationSource
import com.example.billkit.root.RootNotificationAdmission
import com.example.billkit.notification.NotificationEnvelopeGuard
import com.example.billkit.notification.NotificationParseResult
import com.example.billkit.notification.NotificationParser
import com.example.billkit.spec.SpecRuleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * root 模式下用 `dumpsys notification` 读通知，把「通知通道」从运行时授权里解耦出来。
 *
 * 为什么需要它：通知是 SPEC §0 里唯一免 hook 的采集源，但它挂在 `NotificationListenerService` 上——
 * 用户可能根本不给「通知使用权」，厂商 ROM 也会在省电时静默解绑（本项目为此专门写了
 * `AccessibilityGuardian` 之外的连接巡检）。root 模式本来就有常驻前台服务，顺手把同一批通知
 * 读出来，这条通道就不再依赖任何运行时授权。
 *
 * 与另外两条通道的关系：
 * - 与 [NotificationIngress]：**同一条物理通知可能被两边都读到**。所以这里用独立的 source
 *   `"ROOT"`（而不是复用 `"NOTIFICATION"`），交给 `SpecDedup` 的跨来源合并判定 —— 它本来就是为
 *   「同一笔付款被两条通道各报一次」设计的（SPEC §8 阶段 5 验收口径）。
 * - 与 [HookIngress]：hook 拿到的是凭证原文（更准），dumpsys 拿到的是通知文案（更广）。
 *   两者都开时，凭证先到就由 hook 落账，通知后到会被合并掉。
 *
 * 节拍取 5 秒而不是页面轮询的 1.2 秒：`dumpsys notification` 比 `dumpsys window` 重得多，
 * 而通知本来就是秒级容忍的（用户在付款后几秒内看到确认卡即可）。
 */
object RootNotificationIngress {

    private const val TAG = "RootNotificationIngress"
    private const val INTERVAL_MS = 5_000L
    /** dumpsys 输出可能上万行，超过这个长度说明系统在刷屏，这一轮直接跳过。 */
    private const val MAX_NOTIFICATIONS = 64

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dedup = NotificationDumpDedup()
    private val parser = NotificationParser()
    private var job: Job? = null
    private var tickCount = 0
    private var generation = 0L

    data class RuntimeState(val ready: Boolean = false, val error: String? = null)
    private val mutableRuntime = MutableStateFlow(RuntimeState())
    val runtime = mutableRuntime.asStateFlow()

    @Synchronized
    private fun publishRuntime(token: Long, value: RuntimeState) {
        if (generation == token) mutableRuntime.value = value
    }

    val running: Boolean get() = job?.isActive == true

    @Synchronized
    fun start(context: Context) {
        val settings = Settings(context)
        if (!settings.enabled || !settings.notificationCaptureEnabled) {
            Log.i(TAG, "跳过启动：总开关=${settings.enabled} 通知开关=${settings.notificationCaptureEnabled}")
            return
        }
        if (settings.captureMode != CaptureMode.ROOT) {
            Log.i(TAG, "跳过启动：当前模式=${settings.captureMode}")
            return
        }
        if (running) return
        val app = context.applicationContext
        dedup.clear()
        val token = ++generation
        mutableRuntime.value = RuntimeState()
        job = scope.launch {
            try {
                Log.i(TAG, "root 读通知轮询已启动，节拍 ${INTERVAL_MS}ms")
                // 首轮当基线：把「启动时就已经躺在通知栏里」的通知全部登记进去重表，但不投递。
                //
                // 不这么做会重复落账：`dedup` 是内存态，进程一重启（装包、被系统回收）就空，
                // 而通知栏里几十小时前的旧通知还在，于是被当成新通知重新走一遍规则。
                // 实测就是这样多出一次同额同内容的记账（app_data #19 与 #20 的 t 完全相同）。
                // 语义与 NotificationListenerService 的 baseline 实例集合一致。
                //
                // 基线不能「只跑一轮就作废」：规则引擎是异步初始化的（[SpecRuleStore.init] 要读
                // 读取资产、验证完整包、必要时更新规则再求值整个 bundle），首轮轮询时
                // `covers()` 多半还是空集，于是所有通知都被 `continue` 掉、一条也没登记进去，
                // 下一轮引擎就绪后同样的旧通知被当成新通知投递——实测 app_data #21 就是这么来的。
                // 所以这里一直保持在基线态，直到引擎真正就绪，再把「这一轮看到的」当作已见收工。
                var baseline = true
                var rounds = 0
                while (isActive) {
                    val current = Settings(app)
                    if (!current.enabled || !current.notificationCaptureEnabled || current.captureMode != CaptureMode.ROOT) {
                        Log.i(TAG, "轮询退出：配置已不满足")
                        break
                    }
                    val ready = SpecRuleStore.isReady()
                    // 轮询节拍实测应约等于 INTERVAL_MS；若明显更长，说明 tick 本身慢，
                    // 而不是 delay 不准——两者在日志里的区别只有这一条耗时。
                    val startedAt = System.currentTimeMillis()
                    val readOk = runCatching { tick(app, current, token, baseline = baseline || !ready) }.onFailure {
                        if (it is CancellationException) throw it
                        publishRuntime(token, RuntimeState(error = "通知读取或识别处理失败，请检查 Root 后重试。"))
                        Log.w(TAG, "读取通知失败：${it.message}")
                    }.getOrDefault(false)
                    val cost = System.currentTimeMillis() - startedAt
                    if (cost > 2_000L) Log.w(TAG, "本轮 tick 耗时 ${cost}ms")
                    // 前几轮打点，用来区分「没启动」和「启动了但一直读不到通知」——两者在
                    // 数据库里都是「没有新证据」，不上日志分不开。
                    if (rounds < 5 || rounds % 60 == 0) {
                        Log.i(TAG, "轮询第 ${rounds + 1} 轮：引擎就绪=$ready 基线=$baseline")
                    }
                    rounds++
                    if (ready && readOk) baseline = false
                    delay(INTERVAL_MS)
                }
            } finally {
                Log.i(TAG, "root 读通知轮询已停止")
                synchronized(this@RootNotificationIngress) {
                    if (generation == token) {
                        job = null
                        mutableRuntime.value = RuntimeState()
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        generation++
        mutableRuntime.value = RuntimeState()
        job?.cancel()
        job = null
        dedup.clear()
    }

    fun resetDedup() = dedup.clear()

    private suspend fun tick(context: Context, settings: Settings, token: Long, baseline: Boolean = false): Boolean {
        val result = RootNotificationSource.read()
        currentCoroutineContext().ensureActive()
        if (!result.successful) {
            publishRuntime(token, RuntimeState(error = result.error))
            return false
        }
        if (!SpecRuleStore.isReady()) {
            publishRuntime(token, RuntimeState(error = "识别规则正在准备，请稍候。"))
        } else {
            publishRuntime(token, RuntimeState(ready = true))
        }
        val notifications = result.notifications.take(MAX_NOTIFICATIONS)
        if (notifications.isEmpty()) {
            Log.i(TAG, "本轮通知列表为空")
            return true
        }
        // 每轮汇总一次：没有这段，「读到 40 条但全被白名单挡掉」和「读到 0 条」在外部
        // 表现完全一样——都是「没有新证据」，只能靠反复猜。
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val whitelisted = notifications.count { it.packageName in settings.whitelist }
            Log.d(TAG, "本轮解析 ${notifications.size} 条，白名单内 $whitelisted 条")
        } else if (tickCount % 12 == 0) {
            val whitelisted = notifications.count { it.packageName in settings.whitelist }
            Log.i(TAG, "本轮解析 ${notifications.size} 条，白名单内 $whitelisted 条")
        }
        tickCount++
        for (notification in notifications) {
            val pkg = notification.packageName
            if (pkg !in settings.whitelist) continue
            // 基线轮必须排在 covers() 之前：引擎没就绪时 covers() 恒 false，
            // 若先按它过滤，基线轮就一条都登记不到，旧通知会在引擎就绪后被重新投递。
            if (baseline) { dedup.accept(notification); continue }
            if (!SpecRuleStore.covers(pkg, NOTICE) && pkg !in NotificationParser.TEMPLATE_PACKAGES) continue
            val current = Settings(context)
            if (!current.enabled || !current.notificationCaptureEnabled || current.captureMode != CaptureMode.ROOT) return false
            if (pkg !in current.whitelist) continue
            // 不完整的首帧只留诊断，不提前占用15分钟槽位；补齐后的同槽位仍可处理。
            val admission = RootNotificationAdmission.admitLive(notification, dedup, System.currentTimeMillis())
            if (admission == RootNotificationAdmission.Result.Duplicate) continue
            process(context, notification)
        }
        return true
    }

    private suspend fun process(context: Context, notification: com.example.billkit.root.NotificationDumpParser.Dumped) {
        val pkg = notification.packageName
        val receivedAt = System.currentTimeMillis()
        val observation = notification.observation(receivedAt)
        val result = observation?.let(parser::parse)
        val eventId = "root-notification:" + CaptureMergePolicy.digest(
            "$pkg|${notification.slot}|${notification.postTime}|${notification.title}|${notification.text}")
        val rejection = if (observation == null) "missing-envelope-metadata"
            else NotificationEnvelopeGuard.rejection(observation)
        if (rejection != null) {
            AppDb.get(context).capture().putEvidence(CaptureEvidence(eventId, null, SOURCE, pkg,
                receivedAt, rejection, "root-envelope-rejected"))
            return
        }
        val payload = MiniJson.encode(mapOf<String, Any?>(
            "title" to notification.title,
            "text" to notification.text,
            "t" to notification.postTime,
            "observedAt" to receivedAt,
            "envelopeVerified" to true,
            "channelId" to notification.channelId,
            "category" to notification.category,
            "template" to notification.template,
        ))
        val candidate = (result as? NotificationParseResult.Candidate)?.draft
        val parsed = if (candidate == null && observation != null && result != null &&
            NotificationEnvelopeGuard.mayTryRule(observation, result)) {
            runCatching { SpecRuleStore.parseDetailed(context, pkg, NOTICE, payload) }
                .getOrElse { SpecRuleStore.ParseResult(null, SpecRuleEngine.Outcome.ERROR) }
        } else null
        val hit = parsed?.hit
        val draft = candidate ?: hit?.draft
        val observationKey = eventId
        if (draft == null) {
            val reason = when (parsed?.outcome) {
                SpecRuleEngine.Outcome.NO_MATCH -> "no-rule"
                SpecRuleEngine.Outcome.REJECTED -> "rule-rejected"
                SpecRuleEngine.Outcome.ERROR -> "rule-error"
                else -> when (result) {
                    is NotificationParseResult.Ignored -> result.reason
                    is NotificationParseResult.NeedsEvidence -> result.reason
                    else -> "no-rule"
                }
            }
            AppDb.get(context).capture().putEvidence(
                CaptureEvidence(eventId, null, SOURCE, pkg, receivedAt, reason,
                    "payloadHash=" + CaptureMergePolicy.digest(payload).take(16)),
            )
            return
        }
        val suggestion = SpecCategoryStore.fill(context, draft,
            CategorySuggester.suggest(draft, candidates = ExternalRuleStore.candidates()))
        val named = draft.copy(category = suggestion.category, subCategory = suggestion.subCategory,
            account = suggestion.account ?: draft.account)
        val accounted = SpecAccountStore.fill(context, named, preferred = suggestion.account)
        val enriched = accounted.copy(note = NoteTemplate.render(accounted).ifBlank { null })
        val accepted = CaptureCoordinator.accept(
            context,
            CaptureCoordinator.Input(enriched, SOURCE, observationKey, eventId, System.currentTimeMillis()),
            allowed = { val s = Settings(context); s.enabled && s.notificationCaptureEnabled && s.captureMode == CaptureMode.ROOT && pkg in s.whitelist },
        ) ?: return
        CaptureCoordinator.deliver(context, accepted) {
            val s = Settings(context); s.enabled && s.notificationCaptureEnabled && s.captureMode == CaptureMode.ROOT && pkg in s.whitelist
        }
    }

    private const val NOTICE = "NOTICE"

    /** 独立的采集来源：同一条通知被 listener 也读到时要能被跨来源合并掉。 */
    const val SOURCE = "ROOT"
}
