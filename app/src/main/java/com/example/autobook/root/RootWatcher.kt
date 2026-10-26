package com.example.autobook.root

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.example.autobook.data.Settings
import com.example.autobook.data.CaptureMode
import com.example.billkit.BillKit
import com.example.billkit.root.RootShell
import com.example.billkit.root.RootScreenshot
import com.example.billkit.root.RootUiDumpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * root 轮询（宿主侧驱动）。真正"怎么抓"和"怎么判"都在 :billkit：
 * - 抓：`RootUiDumpSource`（uiautomator dump）/ `RootScreenshot`（screencap）
 * - 判：`BillKit.offer` / `BillKit.offerOcr`
 *
 * 这个类只负责节拍：看前台是哪个 App → 是否在白名单 → dump → 需要时再截图 OCR。
 *
 * 代价：dump 单次 200~500ms，建议间隔 ≥1000ms；OCR 再 +150~400ms，
 * 所以只在 `outcome.needsOcr` 时才截图。只适合自用，不要上架。
 *
 * **单例**：轮询要跨页面/跨进程状态常驻，由 [RootForegroundService] 启动和停止。
 * 设置页只订阅 [status]，不再自己 `new` 一个实例（过去的写法导致离开设置页就停）。
 */
object RootWatcher {

    private const val OCR_COOLDOWN_MS = 3_000L
    private const val STATUS_BAR_DP = 24

    /** 给设置页看的一行状态：最近一轮发生了什么 */
    data class Status(
        val running: Boolean = false,
        val lastPackage: String? = null,
        val lastResult: String = "未启动",
        val lastTickAt: Long = 0L,
        val tickCount: Long = 0L,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val source = RootUiDumpSource()

    private var job: Job? = null
    private var lastHash: Int? = null
    private var lastOcrAt: Long = 0

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    val running: Boolean get() = job?.isActive == true

    /** 只持有 application context（单例不能抓 Activity） */
    private var appContext: Context? = null

    @Synchronized
    fun start(
        context: Context,
        settings: Settings,
        intervalMs: Long = 1_200L,
        onAuthorizationDenied: () -> Unit = {},
    ) {
        if (!settings.enabled || !settings.pageFallbackEnabled || settings.captureMode != CaptureMode.ROOT || running) return
        stop()
        appContext = context.applicationContext
        // 换来源/换白名单后旧的哈希会误判为"没变化"，重启轮询时清掉
        lastHash = null
        val app = appContext ?: return
        job = scope.launch {
            update { it.copy(running = false, lastResult = "正在请求 Root 授权") }
            if (!RootShell.isGranted()) {
                update { it.copy(running = false, lastResult = "Root 授权失败，请授权后重新选择 Root 或开启识别") }
                withContext(Dispatchers.Main) { onAuthorizationDenied() }
                return@launch
            }
            if (!isActive || !settings.enabled || !settings.pageFallbackEnabled || settings.captureMode != CaptureMode.ROOT) return@launch
            update { it.copy(running = true, lastResult = "运行中") }
            while (isActive && settings.enabled && settings.pageFallbackEnabled && settings.captureMode == CaptureMode.ROOT) {
                val interactive = isInteractive(app)
                if (!interactive) {
                    // 熄屏：什么都不做，长睡。醒来第一轮把 lastHash 清掉，
                    // 否则解锁后停在原页面会被判成"页面未变化"而漏抓。
                    lastHash = null
                    update { it.copy(lastResult = "息屏休眠中") }
                    delay(RootPollPolicy.delayMs(RootPollPolicy.Tick.ASLEEP, intervalMs))
                    continue
                }
                val tick = runCatching { tick(app, settings) }.getOrElse {
                    update { status -> status.copy(lastResult = "采集失败：${it.message.orEmpty()}") }
                    RootPollPolicy.Tick.IDLE
                }
                delay(RootPollPolicy.delayMs(tick, intervalMs))
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        // Do not issue su on stop: this is also reached when Accessibility is selected.
        // The next Root dump removes its previous temporary XML before writing.
        update { it.copy(running = false, lastResult = when {
            it.lastResult.startsWith("Root 授权失败") -> it.lastResult
            it.tickCount == 0L -> "未启动"
            else -> "已停止"
        }) }
    }

    fun release() = stop()

    // ------------------------------------------------------------------

    /** 息屏 + 锁屏都算"人不在"，两个都查：有的机型息屏但不锁屏（如投屏/充电常亮） */
    private fun isInteractive(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && !power.isInteractive) return false
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard != null && keyguard.isKeyguardLocked) return false
        return true
    }

    private fun update(block: (Status) -> Status) {
        _status.value = block(_status.value)
    }

    private suspend fun tick(context: Context, settings: Settings): RootPollPolicy.Tick {
        val captureToken = com.example.autobook.capture.NotificationIngress.configurationToken(context)
        fun currentConfiguration() = captureToken == com.example.autobook.capture.NotificationIngress.configurationToken(context)
        if (!settings.enabled || !settings.pageFallbackEnabled || settings.captureMode != CaptureMode.ROOT) {
            lastHash = null
            update { it.copy(lastResult = "识别已关闭") }
            return RootPollPolicy.Tick.IDLE
        }

        val fg = source.foreground() ?: run {
            lastHash = null
            update { it.copy(lastResult = "拿不到前台应用") }
            return RootPollPolicy.Tick.IDLE
        }
        if (!BillKit.isAccepted(fg.packageName)) {
            lastHash = null
            update { it.copy(lastPackage = fg.packageName, lastResult = "非白名单 ${fg.packageName}") }
            return RootPollPolicy.Tick.IDLE
        }

        // 前台已经查过了，直接传进去，省一次 dumpsys window
        if (!settings.enabled || !settings.pageFallbackEnabled || settings.captureMode != CaptureMode.ROOT || !currentConfiguration()) return RootPollPolicy.Tick.IDLE
        val snapshot = source.dump(fg)?.copy(configurationToken = captureToken) ?: run {
            update { it.copy(lastPackage = fg.packageName, lastResult = "dump 失败") }
            return RootPollPolicy.Tick.IDLE
        }
        if (snapshot.packageName != fg.packageName) {
            update { it.copy(lastPackage = fg.packageName, lastResult = "前台已切换") }
            return RootPollPolicy.Tick.IDLE
        }

        val hash = listOf(snapshot.packageName, snapshot.activityClass, snapshot.items, snapshot.truncated).hashCode()
        if (hash == lastHash) {
            update { it.copy(lastPackage = fg.packageName, lastResult = "页面未变化") }
            return RootPollPolicy.Tick.IDLE
        }
        lastHash = hash
        update { it.copy(lastPackage = fg.packageName, lastTickAt = System.currentTimeMillis(), tickCount = it.tickCount + 1) }

        currentCoroutineContext().ensureActive()
        if (!settings.enabled || !settings.pageFallbackEnabled || settings.captureMode != CaptureMode.ROOT || !currentConfiguration()) return RootPollPolicy.Tick.IDLE
        val outcome = BillKit.offer(snapshot)
        val label = when (outcome) {
            is com.example.billkit.BillPipeline.Outcome.Draft -> "识别到账单"
            is com.example.billkit.BillPipeline.Outcome.Skipped -> "跳过：${outcome.reason}"
        }
        update { it.copy(lastResult = label) }

        var result = if (outcome is com.example.billkit.BillPipeline.Outcome.Draft)
            RootPollPolicy.Tick.ACTIVE else RootPollPolicy.Tick.IDLE
        if (!outcome.needsOcr || !BillKit.currentConfig().ocrEnabled) return result
        if (System.currentTimeMillis() - lastOcrAt < OCR_COOLDOWN_MS) return result

        if (!settings.enabled || !settings.pageFallbackEnabled || settings.captureMode != CaptureMode.ROOT || !currentConfiguration()) return result
        lastOcrAt = System.currentTimeMillis()
        val bitmap = RootScreenshot.capture() ?: return result
        try {
            currentCoroutineContext().ensureActive()
            if (!settings.enabled || !settings.pageFallbackEnabled || settings.captureMode != CaptureMode.ROOT || !currentConfiguration()) return result
            val statusBarPx = (STATUS_BAR_DP * context.resources.displayMetrics.density).toInt()
            val ocrOutcome = BillKit.offerOcr(
                packageName = fg.packageName,
                activityClass = fg.activityClass,
                screenshot = bitmap,
                statusBarPx = statusBarPx,
                configurationToken = captureToken,
            )
            if (ocrOutcome is com.example.billkit.BillPipeline.Outcome.Draft) result = RootPollPolicy.Tick.ACTIVE
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        return result
    }
}
