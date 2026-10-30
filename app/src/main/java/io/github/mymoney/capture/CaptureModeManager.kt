package io.github.mymoney.capture

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.CaptureModeState
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.data.Settings
import io.github.mymoney.root.RootForegroundService
import com.example.billkit.BillKit
import com.example.billkit.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 所有通道的唯一启停协调点：任何入口（设置页、桌面磁贴、开机、进程重建）都走这里。
 *
 * 四条通道各自的活性判据都在 [CaptureModeState] 的派生属性里，本类只做「状态 → 副作用」映射：
 * - 通知通道：走哪种实现由 [ChannelSelection] 按**当时的可用性**判，不看 [CaptureModeState.mode]。
 *   `LISTENER` 由系统按「通知使用权」绑定 [PaymentNotificationListener]，不需要我们起停；
 *   `DUMPSYS` 需要本类起 [RootForegroundService] + [RootNotificationIngress]。
 * - 页面通道：只有无障碍一种实现（root 模式的 uiautomator 轮询已删除），靠 [AccessibilityGuardian]
 *   守护服务。它只看总开关与页面开关，与预设无关。
 * - 凭证通道：**不随模式启停**，宿主这边没有任何常驻服务——模块在微信/支付宝进程里，
 *   有凭证就调用 provider 回来。本类只负责在通道打开时探一次模块加载状态（[HookTargetMonitor.detect]）。
 */
object CaptureModeManager {
    private val mutableState = MutableStateFlow(CaptureModeState())
    val state: StateFlow<CaptureModeState> = mutableState.asStateFlow()
    private var app: Context? = null
    private var rootRequested = false
    private val handler = Handler(Looper.getMainLooper())
    // Keep a strong reference: SharedPreferences stores weak listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        app?.let { context -> onMain { reconcile(context) } }
    }

    /**
     * 「授权了但没连上」已经确认了几次。只在确认点推进（见 [ChannelSelection.nextStreak]），
     * 平时 reconcile 只读它。
     */
    private var listenerDownStreak = 0

    /** 迟滞到点复查。掉线回调只算第一次确认，第二次靠这个。 */
    private val recheck = Runnable { app?.let { confirmListenerState(it) } }

    /**
     * 通知使用权变化（系统设置里的「通知使用权」开关）。挂在应用生命周期上，不注销。
     *
     * 为什么非得有这一路：授权是在**系统设置**里做的，不经过我们的偏好；而 listener 的绑定回调
     * 与授权写入可能抢先后——回调先到、那一刻读到「未授权」，就会判成走 dumpsys，之后没有任何
     * 事件再触发重算。真机实测：授予后一直 5 秒一次 su，直到改别的开关才切回 listener。
     */
    private val notificationAccessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            app?.let { context -> onNotificationAccessChanged(context) }
        }
    }

    /**
     * root 能力缓存。`su` 是外部进程调用，不能在主线程做，也不该每次冷启动就问一遍
     * （未授权时会弹 Magisk 授权框）。所以：异步探、结果缓存、只在真的要用 root 兜底时才探。
     */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var rootGranted = false
    private var rootProbedAt = Long.MIN_VALUE
    private var rootProbing = false

    /**
     * 界面用的 root 能力快照：`null` = 还没探过。
     *
     * 单独挂出来是因为界面要**先知道有没有 root，再决定渲不渲染 root 入口**——不能先画出来
     * 再抽掉，也不能为了一次渲染就在主线程跑 `su`。
     */
    private val mutableRootCapability = MutableStateFlow<Boolean?>(null)
    val rootCapability: StateFlow<Boolean?> = mutableRootCapability.asStateFlow()

    /** 界面进入相关页面时调一次；已有结论且未过期就不重复探（避免反复弹 Magisk 授权框）。 */
    fun ensureRootProbed(context: Context) {
        init(context)
        considerRootProbe(context)
    }

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val application = context.applicationContext
        val settings = Settings(application)
        val configured = accessibilityServiceEnabled(
            SystemSettings.Secure.getString(
                application.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            application.packageName,
            BillAccessibilityService::class.java.name,
        )
        settings.migrateCaptureMode(configured)
        // 通知通道归一：把旧的「root 读通知」独立开关搬进统一的通知开关，只做一次。
        settings.migrateLegacyRootNotification()
        app = application
        mutableState.value = readState(settings)
        application.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
        // 通知使用权是**系统设置**，不是我们的偏好：用户在系统设置里授权/撤销时，上面的偏好
        // 监听不会响。少了这一路，真机上出现过「已授权但仍按无授权跑 root 读通知」——授予之后
        // 十分钟里一直 5 秒一次 su，直到碰巧改了别的开关才切回 listener。
        application.contentResolver.registerContentObserver(
            SystemSettings.Secure.getUriFor(PaymentNotificationListener.AUTHORIZATION_SETTING),
            false,
            notificationAccessObserver,
        )
        // listener 的连通性是异步来的，只能订阅。StateFlow 会立刻发一次当前值，正好当作
        // 冷启动时的第一次确认：此刻还没连上就计一格，等宽限期复查再定夺。
        probeScope.launch {
            PaymentNotificationListener.bound.collect { bound ->
                handler.post { app?.let { onListenerBoundChanged(it, bound) } }
            }
        }
    }

    // 过去还有一个 select(mode)：先把「使用方式」写进去，采集行为再跟着模式走。
    // 阶段 4 之后写模式的唯一入口是 applyPreset —— 它同时决定各通道开不开，
    // 而「谁在真的跑」由 reconcile 按可用性算。留两个写入口只会让人以为模式仍然是决定性的。

    fun prepareOrdinarySetup(context: Context) {
        init(context)
        onMain {
            Settings(context).prepareOrdinarySetup()
            reconcile(context)
        }
    }

    fun useRecommendedOrdinarySources(context: Context) {
        init(context)
        onMain {
            Settings(context).useRecommendedOrdinarySources()
            reconcile(context)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            Settings(context).enabled = enabled
            reconcile(context)
            if (enabled && state.value.hookEnabled) HookTargetMonitor.detect(context)
        }
    }

    /**
     * 通知通道总开关。走哪种实现由 [reconcile] 里的 [ChannelSelection] 现算，
     * 切换实现时去重表在那里统一清——不再由这里额外清一遍兜底。
     */
    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            Settings(context).notificationCaptureEnabled = enabled
            reconcile(context)
        }
    }

    /**
     * 应用一份推荐配置。**这不是选模式**：它只写各通道的开关，写完之后谁在跑仍由各自的
     * 可用性决定。之所以还记一个 `captureMode`，只是为了界面高亮和阶段 5 的迁移/备份兼容。
     *
     * @param lspInstalled 本机是否真的装了 LSPosed。没装就不把凭证开关打开——开一个永远
     *   「模块加载待确认」的通道，只会让人以为哪儿坏了。
     */
    fun applyPreset(context: Context, preset: CaptureMode, lspInstalled: Boolean) {
        init(context)
        onMain {
            Settings(context).applyPreset(preset, hookWanted = preset == CaptureMode.ROOT && lspInstalled)
            reconcile(context)
            if (preset == CaptureMode.ROOT) considerRootProbe(context, force = true)
            if (state.value.hookEnabled) HookTargetMonitor.detect(context)
        }
    }

    /** LSPosed 采集通道开关。 */
    fun setHookEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            Settings(context).hookCaptureEnabled = enabled
            reconcile(context)
            if (enabled) HookTargetMonitor.detect(context)
        }
    }

    /** 页面通道开关。两种模式下的实现不同，但「开不开」是同一个开关。 */
    fun setPageEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain { Settings(context).pageEnabled = enabled; reconcile(context) }
    }

    fun reconcile(context: Context) {
        init(context)
        onMain {
            val settings = Settings(context)
            val next = readState(settings)
            mutableState.value = next
            NotificationIngress.clearInvalidSample(context)
            io.github.mymoney.systems.SystemIntegration.refreshTile(context)
            BillKit.updateConfig(settings.toKitConfig())
            BillAccessibilityService.instance?.refreshServiceInfo()

            val capabilities = capabilities(context, next)
            // 装包、进程重启之后系统不一定把监听重新绑回来，界面就会一直停在「已授权，但监听服务
            // 尚未连接」。reconcile 是最常走的入口（进前台、改开关、授权变化都过这里），顺手补一次
            // 重绑：requestReconnect 自己有限流，且只在「授权了但没连上」时才真的动作。
            if (ChannelSelection.shouldRequestReconnect(capabilities)) {
                PaymentNotificationListener.requestReconnect(context)
            }
            val plan = ChannelSelection.decide(capabilities, listenerDownStreak)
            val previous = NotificationPlanState.value
            if (previous != plan) {
                NotificationPlanState.set(plan)
                // 换实现时清一次去重表：两种实现看到的通知对象不同（Listener 有 extras、
                // dumpsys 只有渲染后的文本），不清的话切换瞬间会把同一条当成新通知再记一遍。
                // 换实现有两个触发点——用户改开关，以及授权/连通性变化——都收敛在这里。
                RootNotificationIngress.resetDedup()
                NotificationIngress.resetDedup()
            }

            // root 常驻服务只服务 dumpsys 通知通道：没有 dumpsys 读取需求就不该占一个前台通知。
            if (plan == NotificationPath.DUMPSYS) {
                if (!rootRequested) {
                    rootRequested = true
                    runCatching { RootForegroundService.start(context) }
                }
                RootNotificationIngress.start(context)
            } else {
                if (rootRequested || RootForegroundService.active) {
                    rootRequested = false
                    RootForegroundService.stop(context)
                }
                RootNotificationIngress.stop()
            }

            if (needsRootAnswer(capabilities)) considerRootProbe(context)
            scheduleRecheck(capabilities)

            // 无障碍守护：页面通道开着就跑，关掉立刻停，不留后台空转。
            // 不再要求「无障碍模式」——页面通道只有这一种实现，要不要它是开关的事（见 pageActive）。
            // 停止不依赖 running 标记（进程重启后该标记可能是 false，但服务仍在），
            // stopService 对没在跑的服务是安全的空操作。
            if (next.pageActive) {
                AccessibilityGuardian.ensureRunning(context)
            } else {
                AccessibilityGuardian.stop(context)
            }
        }
    }

    private fun capabilities(context: Context, state: CaptureModeState) = ChannelCapabilities(
        enabled = state.enabled,
        notificationsEnabled = state.notificationsEnabled,
        listenerAuthorized = PaymentNotificationListener.isAuthorized(context),
        listenerBound = PaymentNotificationListener.bound.value,
        rootAvailable = rootGranted,
    )

    /** listener 连通性变化：连上立刻清零并撤掉复查；断开设为第一次确认。 */
    private fun onListenerBoundChanged(context: Context, bound: Boolean) {
        listenerDownStreak =
            if (bound) 0 else ChannelSelection.nextStreak(capabilities(context, readState(Settings(context))), 0)
        reconcile(context)
    }

    /**
     * 通知使用权变了：授权了就主动要一次重绑（系统偶尔会慢半拍或干脆没绑），然后重算通道。
     * 撤销时不需要重绑——`decide` 会直接判到 dumpsys 或停掉。
     */
    private fun onNotificationAccessChanged(context: Context) {
        onMain {
            val current = app ?: return@onMain
            val capabilities = capabilities(current, readState(Settings(current)))
            if (ChannelSelection.shouldRequestReconnect(capabilities)) {
                PaymentNotificationListener.requestReconnect(current)
            }
            reconcile(current)
        }
    }

    /** 宽限期到点：再确认一次。 */
    private fun confirmListenerState(context: Context) {
        listenerDownStreak =
            ChannelSelection.nextStreak(capabilities(context, readState(Settings(context))), listenerDownStreak)
        reconcile(context)
    }

    private fun scheduleRecheck(capabilities: ChannelCapabilities) {
        handler.removeCallbacks(recheck)
        val delay = ChannelSelection.recheckDelayMs(capabilities, listenerDownStreak) ?: return
        handler.postDelayed(recheck, delay)
    }

    /**
     * 只有「listener 这条路已经指望不上」时才值得到去问一次 root。
     *
     * 普通用户（授权了、连得上）永远走不到这里，冷启动也就不会冒出一个 Magisk 授权框。
     * 反过来，listener 从来没授权、或者已经确认掉线时，不探 root 就无从知道还有没有别的路。
     */
    private fun needsRootAnswer(capabilities: ChannelCapabilities): Boolean =
        capabilities.enabled && capabilities.notificationsEnabled &&
            (!capabilities.listenerAuthorized ||
                listenerDownStreak >= ChannelSelection.LISTENER_DOWN_CONFIRMATIONS)

    private fun considerRootProbe(context: Context, force: Boolean = false) {
        if (!beginRootProbe(force)) return
        val application = context.applicationContext
        probeScope.launch {
            val granted = runCatching { RootShell.isGranted() }.getOrDefault(false)
            handler.post {
                if (!endRootProbe(granted)) return@post
                reconcile(application)
            }
        }
    }

    /**
     * 别处（例如设置页的「重新检测」）已经探出结果时，把结论并进这里的缓存。
     *
     * 否则两条探测路径各说各话：用户在 Root 状态页刚看到「已获得」，回到自动记账页
     * 却因为这里是 `null` 而看不到「推荐配置」。
     */
    fun rememberRootGranted() {
        onMain {
            if (!endRootProbe(true)) return@onMain
            app?.let { reconcile(it) }
        }
    }

    /** 收尾并返回结论是否变了；变了才需要重算一遍计划。 */
    @Synchronized
    private fun endRootProbe(granted: Boolean): Boolean {
        rootProbing = false
        rootProbedAt = SystemClock.elapsedRealtime()
        mutableRootCapability.value = granted
        if (granted == rootGranted) return false
        rootGranted = granted
        return true
    }

    @Synchronized
    private fun beginRootProbe(force: Boolean): Boolean {
        if (rootProbing) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && rootProbedAt != Long.MIN_VALUE && now - rootProbedAt < ROOT_PROBE_TTL_MS) return false
        rootProbing = true
        return true
    }

    /** root 被撤销不会自己通知我们，只能靠缓存过期后重探；期间 dumpsys 失败会体现在健康页。 */
    private const val ROOT_PROBE_TTL_MS = 5 * 60_000L

    private fun readState(settings: Settings) = CaptureModeState(
        mode = settings.captureMode,
        enabled = settings.enabled,
        pageEnabled = settings.pageEnabled,
        notificationsEnabled = settings.notificationCaptureEnabled,
        smsEnabled = settings.smsCaptureEnabled,
        hookEnabled = settings.hookCaptureEnabled,
    )

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
