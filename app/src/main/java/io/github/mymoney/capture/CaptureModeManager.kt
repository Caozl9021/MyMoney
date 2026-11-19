package io.github.mymoney.capture

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import com.example.billkit.BillKit
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.CaptureModeState
import io.github.mymoney.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 页面自动记账启停协调点：任何入口（设置页、桌面磁贴、开机、进程重建）都走这里。
 */
object CaptureModeManager {
    private val mutableState = MutableStateFlow(CaptureModeState())
    val state: StateFlow<CaptureModeState> = mutableState.asStateFlow()
    private var app: Context? = null
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
        val configured = accessibilityServiceEnabled(
            SystemSettings.Secure.getString(
                application.contentResolver,
                SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            application.packageName,
            BillAccessibilityService::class.java.name,
        )
        settings.migrateCaptureMode(configured)
        app = application
        mutableState.value = readState(settings)
        application.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    fun prepareOrdinarySetup(context: Context) {
        init(context)
        onMain {
            Settings(context).prepareOrdinarySetup()
            reconcile(context)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        init(context)
        onMain {
            val settings = Settings(context)
            if (enabled && settings.captureMode == null) {
                settings.captureMode = CaptureMode.ACCESSIBILITY
            }
            settings.enabled = enabled
            reconcile(context)
        }
    }

    fun reconcile(context: Context) {
        init(context)
        onMain {
            val settings = Settings(context)
            val next = readState(settings)
            mutableState.value = next
            io.github.mymoney.systems.SystemIntegration.refreshTile(context)
            BillKit.updateConfig(settings.toKitConfig())
            BillAccessibilityService.instance?.refreshServiceInfo()

            if (next.pageActive) {
                AccessibilityGuardian.ensureRunning(context)
            } else {
                AccessibilityGuardian.stop(context)
            }
        }
    }

    private fun readState(settings: Settings) = CaptureModeState(
        mode = settings.captureMode,
        enabled = settings.enabled,
        pageEnabled = true,
    )

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
