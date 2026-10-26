package com.example.autobook.capture

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import com.example.autobook.data.CaptureMode
import com.example.autobook.data.CaptureModeState
import com.example.autobook.data.Settings
import com.example.autobook.root.RootForegroundService
import com.example.billkit.BillKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single coordinator for every lifecycle entry point and preference mutation. */
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
        app = application
        mutableState.value = CaptureModeState(settings.captureMode, settings.enabled, settings.pageFallbackEnabled, settings.notificationCaptureEnabled)
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
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            Settings(context).enabled = enabled
            reconcile(context)
        }
    }

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain { Settings(context).notificationCaptureEnabled = enabled; reconcile(context) }
    }

    fun setPageFallbackEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain { Settings(context).pageFallbackEnabled = enabled; reconcile(context) }
    }

    fun reconcile(context: Context) {
        init(context)
        onMain {
            val settings = Settings(context)
            val next = CaptureModeState(settings.captureMode, settings.enabled, settings.pageFallbackEnabled, settings.notificationCaptureEnabled)
            mutableState.value = next
            NotificationIngress.clearInvalidSample(context)
            com.example.autobook.systems.SystemIntegration.refreshTile(context)
            BillKit.updateConfig(settings.toKitConfig())
            BillAccessibilityService.instance?.refreshServiceInfo()
            if (next.rootActive) {
                if (!rootRequested) {
                    rootRequested = true
                    runCatching { RootForegroundService.start(context) }
                }
            } else if (rootRequested || RootForegroundService.active) {
                rootRequested = false
                RootForegroundService.stop(context)
            }

            // 无障碍守护：只在「无障碍模式 + 识别开启」时跑，切走或暂停立刻停掉，不留后台空转。
            // 停止不依赖 running 标记（进程重启后该标记可能是 false，但服务仍在），
            // stopService 对没在跑的服务是安全的空操作。
            if (next.accessibilityActive) {
                AccessibilityGuardian.ensureRunning(context)
            } else {
                AccessibilityGuardian.stop(context)
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
