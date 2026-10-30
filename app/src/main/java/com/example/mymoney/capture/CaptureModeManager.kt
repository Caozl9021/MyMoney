package com.example.mymoney.capture

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import com.example.mymoney.data.CaptureMode
import com.example.mymoney.data.CaptureModeState
import com.example.mymoney.data.NotificationPath
import com.example.mymoney.data.Settings
import com.example.mymoney.root.RootForegroundService
import com.example.billkit.BillKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 所有通道的唯一启停协调点：任何入口（设置页、桌面磁贴、开机、进程重建）都走这里。
 *
 * 四条通道各自的活性判据都在 [CaptureModeState] 的派生属性里，本类只做「状态 → 副作用」映射：
 * - 通知通道：`ROOT` 模式下起 [RootForegroundService] + [RootNotificationIngress]；
 *   `ACCESSIBILITY` 模式下由系统按「通知使用权」绑定 [PaymentNotificationListener]，不需要我们起停。
 * - 页面通道：`ACCESSIBILITY` 模式靠 [AccessibilityGuardian] 守护服务；
 *   `ROOT` 模式已无页面通道（uiautomator 轮询已删除）。
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

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val application = context.applicationContext
        val settings = Settings(application)
        val component = ComponentName(application, BillAccessibilityService::class.java)
        val configured = SystemSettings.Secure.getString(
            application.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
        settings.migrateCaptureMode(configured)
        // 通知通道归一：把旧的「root 读通知」独立开关搬进统一的通知开关，只做一次。
        settings.migrateLegacyRootNotification()
        app = application
        mutableState.value = readState(settings)
        application.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    fun select(context: Context, mode: CaptureMode) {
        init(context)
        // Selecting Root again is an explicit retry after a denied authorization.
        onMain {
            rootRequested = false
            Settings(context).selectCaptureMode(mode)
            reconcile(context)
            if (mode == CaptureMode.ROOT) HookTargetMonitor.detect(context)
        }
    }

    fun startWithNotifications(context: Context) {
        init(context)
        onMain {
            Settings(context).startWithNotifications()
            reconcile(context)
        }
    }

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
            if (enabled && state.value.mode == CaptureMode.ROOT && state.value.hookEnabled)
                HookTargetMonitor.detect(context)
        }
    }

    /**
     * 通知通道总开关。实际走哪条路由 [CaptureModeState.mode] 决定，见 [CaptureModeState.notificationPath]。
     * 从 `ACCESSIBILITY` 切到 `ROOT`（或反过来）时去重表要清一次：
     * 两种实现看到的通知对象不同（Listener 有 extras、dumpsys 只有渲染后的文本），
     * 不清的话切换瞬间会把同一条当成新通知再记一遍。
     */
    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            Settings(context).notificationCaptureEnabled = enabled
            RootNotificationIngress.resetDedup()
            NotificationIngress.resetDedup()
            reconcile(context)
        }
    }

    /** 短信通道开关。关掉时清一次去重表：重新打开后同一条短信应当还能被处理。 */
    fun setSmsEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            Settings(context).smsCaptureEnabled = enabled
            SmsIngress.resetDedup()
            reconcile(context)
        }
    }

    /** LSPosed 采集通道开关。 */
    fun setHookEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            Settings(context).hookCaptureEnabled = enabled
            reconcile(context)
            if (enabled && state.value.mode == CaptureMode.ROOT) HookTargetMonitor.detect(context)
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
            com.example.mymoney.systems.SystemIntegration.refreshTile(context)
            BillKit.updateConfig(settings.toKitConfig())
            BillAccessibilityService.instance?.refreshServiceInfo()

            // root 常驻服务只服务通知通道：没有 dumpsys 读取需求就不该占一个前台通知。
            val needRootService = next.notificationPath == NotificationPath.DUMPSYS
            if (needRootService) {
                if (!rootRequested) {
                    rootRequested = true
                    runCatching { RootForegroundService.start(context) }
                }
            } else if (rootRequested || RootForegroundService.active) {
                rootRequested = false
                RootForegroundService.stop(context)
            }

            // dumpsys 轮询自己起协程，节拍 5 秒，与页面通道无关。
            if (needRootService) {
                RootNotificationIngress.start(context)
            } else {
                RootNotificationIngress.stop()
            }

            // 无障碍守护：只在「无障碍模式 + 页面通道开启」时跑，切走或关掉立刻停，不留后台空转。
            // 停止不依赖 running 标记（进程重启后该标记可能是 false，但服务仍在），
            // stopService 对没在跑的服务是安全的空操作。
            if (next.pageActive && next.mode == CaptureMode.ACCESSIBILITY) {
                AccessibilityGuardian.ensureRunning(context)
            } else {
                AccessibilityGuardian.stop(context)
            }
        }
    }

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
