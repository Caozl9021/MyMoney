package com.example.billkit

import android.graphics.Bitmap
import com.example.billkit.ocr.OcrEngine
import com.example.billkit.ocr.OcrFallback

/**
 * 识别配置。宿主把自己的设置映射成它，然后 [BillKit.updateConfig]。
 */
data class BillKitConfig(
    /** 只处理这些包（微信/支付宝/…），其余一律不看 */
    val acceptedPackages: Set<String> = Packages.defaultWhitelist,
    /** 分类未命中 / 命中但无金额时，是否允许宿主再送一张截图进来做 OCR */
    val ocrEnabled: Boolean = true,
    /** 同一应用两次回调的最小间隔 */
    val minIntervalMs: Long = 1_500L,
    /** 同指纹在这个窗口内不重复回调（防重复弹窗/重复入账） */
    val dedupeWindowMs: Long = 90_000L,
)

/** 宿主实现的落点：落自己的库、发通知、导出 CSV、分享到别的 App…… */
fun interface BillSink {
    fun onDraft(bill: BillDraft)
}

/**
 * 模块入口。宿主要做的只有三步：
 *
 * ```kotlin
 * BillKit.install(config, sink = { draft -> /* 存库/通知 */ }, ocrEngine = MlKitOcrEngine())
 * BillKit.offer(UiSnapshot(pkg, activity, textItems))        // 无障碍 / uiautomator dump 送进来
 * BillKit.offerOcr(pkg, activity, screenshot, statusBarPx)   // 前一步 needsOcr 时才截图送我
 * ```
 *
 * 库内部负责：分类 → 状态/金额/方向校验 → 节流 → 去重 → 回调 sink → 写 DebugBus。
 * 库不持有 Context、不碰数据库、不发网络请求。
 */
object BillKit {

    private val pipeline = BillPipeline()

    @Volatile
    private var config: BillKitConfig = BillKitConfig()

    @Volatile
    private var sink: BillSink? = null

    @Volatile
    private var ocrFallback: OcrFallback? = null

    private val lastSeen = LinkedHashMap<String, Long>()
    private var lastAcceptedAt = 0L

    /**
     * 最近一次出草稿的那一屏（包名 + 有序文本，不含时间）。
     *
     * 确认卡关掉后，被盖住的成功页会重新获焦、再送进来一模一样的文本；页面没写时间时
     * 草稿时间 = 识别时刻，指纹按分钟变，90s/10min 去重都拦不住（F16/F9 实测重复入库）。
     * 同一屏再来直接跳过；白名单里任何一屏不同的文本（离开这页、进入下一次付款）就清掉。
     */
    private var lastDraftPage: List<Any>? = null

    fun install(
        config: BillKitConfig = BillKitConfig(),
        sink: BillSink,
        ocrEngine: OcrEngine? = null,
    ) {
        this.config = config
        this.sink = sink
        this.ocrFallback = ocrEngine?.let { OcrFallback(it) }
        reset()
    }

    fun updateConfig(config: BillKitConfig) {
        this.config = config
    }

    fun currentConfig(): BillKitConfig = config

    fun isAccepted(packageName: String): Boolean = packageName in config.acceptedPackages

    fun reset() {
        lastSeen.clear()
        lastAcceptedAt = 0L
        lastDraftPage = null
    }

    /** 主入口：一屏文本进去，命中草稿就回调 [sink]。返回值可直接用于调试展示。 */
    fun offer(snapshot: UiSnapshot, now: Long = System.currentTimeMillis()): BillPipeline.Outcome {
        val outcome = pipeline.process(snapshot, now)
        dispatch(outcome, snapshot, now)
        return outcome
    }

    /**
     * OCR 兜底：只有在 [offer] 返回 `needsOcr == true` 且配置允许时才值得调用，
     * 因为一次识别要 150~400ms。截图由宿主负责（库不申请任何截图权限）。
     */
    suspend fun offerOcr(
        packageName: String,
        activityClass: String?,
        screenshot: Bitmap,
        statusBarPx: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): BillPipeline.Outcome? {
        if (!config.ocrEnabled) return null
        val fallback = ocrFallback ?: return null

        val snapshot = fallback.snapshot(
            packageName = packageName,
            activityClass = activityClass,
            screenshot = screenshot,
            statusBarPx = statusBarPx,
        ) ?: return null

        val outcome = pipeline.process(snapshot, now)
        dispatch(outcome, snapshot, now)
        return outcome
    }

    private fun dispatch(outcome: BillPipeline.Outcome, snapshot: UiSnapshot, now: Long) {
        val page = listOf(snapshot.packageName, snapshot.items)
        if (page != lastDraftPage) lastDraftPage = null
        when (outcome) {
            is BillPipeline.Outcome.Skipped -> {
                DebugBus.publish(snapshot, snapshot.diagnostic("skip:${outcome.reason}"))
            }

            is BillPipeline.Outcome.Draft -> {
                if (page == lastDraftPage) {
                    DebugBus.publish(snapshot, snapshot.diagnostic("skip:same-page"))
                    return
                }
                if (now - lastAcceptedAt < config.minIntervalMs) {
                    DebugBus.publish(snapshot, snapshot.diagnostic("skip:throttled"))
                    return
                }
                if (isDuplicate(outcome.draft.fingerprint, now)) {
                    lastDraftPage = page
                    DebugBus.publish(snapshot, snapshot.diagnostic("skip:duplicate"))
                    return
                }
                lastDraftPage = page
                lastAcceptedAt = now
                lastSeen[outcome.draft.fingerprint] = now
                prune(now)

                // 截断时在结论后面标一下：规则没命中时，第一件要确认的事就是
                // "是不是这段文字根本没被读全"。
                val description = buildString {
                    append("draft:").append(outcome.draft.amountYuan).append(' ')
                    append(outcome.match.kind)
                    if (outcome.match.strategy.startsWith("JSON:")) append(" [").append(outcome.match.strategy).append(']')
                    if (snapshot.truncated) append(" [截断]")
                }
                DebugBus.publish(snapshot, description)
                sink?.onDraft(outcome.draft)
            }
        }
    }

    private fun isDuplicate(fingerprint: String, now: Long): Boolean {
        val seenAt = lastSeen[fingerprint] ?: return false
        return now - seenAt < config.dedupeWindowMs
    }

    private fun prune(now: Long) {
        if (lastSeen.size < 64) return
        val expired = lastSeen.filterValues { now - it > config.dedupeWindowMs }.keys
        expired.forEach { lastSeen.remove(it) }
    }
}
