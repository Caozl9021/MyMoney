package com.example.autobook.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.capture.AccessibilityGuardian
import com.example.autobook.capture.BillAccessibilityService
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.capture.PaymentNotificationListener
import com.example.autobook.capture.RootNotificationIngress
import com.example.autobook.capture.HookTargetMonitor
import com.example.autobook.lsp.HookLoadState
import com.example.autobook.root.RootForegroundService
import com.example.autobook.data.Settings
import com.example.autobook.ui.design.*

/** Observes concrete binding and preferences; system permissions are refreshed on return. No polling. */
@Composable
internal fun rememberCaptureHealthInput(): CaptureHealthInput {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val capture by CaptureModeManager.state.collectAsStateWithLifecycle()
    val bound by BillAccessibilityService.bound.collectAsStateWithLifecycle()
    val notificationBound by PaymentNotificationListener.bound.collectAsStateWithLifecycle()
    val rootRuntime by RootNotificationIngress.runtime.collectAsStateWithLifecycle()
    val rootServiceActive by RootForegroundService.activeState.collectAsStateWithLifecycle()
    val hookTargets by HookTargetMonitor.state.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, lifecycle) {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        val secureObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        context.contentResolver.registerContentObserver(SystemSettings.Secure.getUriFor(SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, secureObserver)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(secureObserver)
        }
    }
    return remember(capture, bound, notificationBound, rootRuntime, rootServiceActive, hookTargets, revision) {
        readCaptureHealthInput(context)
    }
}

/** Also used to recheck a user-triggered start against the current signals. */
internal fun readCaptureHealthInput(context: Context): CaptureHealthInput {
    val capture = CaptureModeManager.state.value
    val bound = BillAccessibilityService.bound.value
    val notificationBound = PaymentNotificationListener.bound.value
    val rootRuntime = RootNotificationIngress.runtime.value
    val rootServiceActive = RootForegroundService.activeState.value
    val selectedHookPackages = HookTargetMonitor.state.value.filterValues { it.state == HookLoadState.READY }.keys.intersect(Settings(context).whitelist)
    return CaptureHealthInput(capture.mode, capture.enabled,
        AccessibilityGuardian.isEnabledInSettings(context), bound,
        SystemSettings.canDrawOverlays(context), Settings(context).autoConfirm,
        capture.pageEnabled, capture.notificationsEnabled,
        PaymentNotificationListener.isAuthorized(context), notificationBound,
        smsEnabled = capture.smsEnabled,
        smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
        hookEnabled = capture.hookEnabled,
        hookReady = selectedHookPackages.isNotEmpty(),
        rootNotificationReady = rootRuntime.ready && rootServiceActive,
        rootNotificationError = rootRuntime.error ?: if (rootServiceActive) null else "Root 后台服务尚未连接，请检查状态后重试。",
        hookPackages = selectedHookPackages)
}

internal fun performCaptureAction(context: Context, action: CaptureAction,
    onSettings: (CaptureSettingsDestination) -> Unit, onPending: () -> Unit) {
    if (ordinarySetupDestination(CaptureModeManager.state.value.mode, action)) {
        onSettings(CaptureSettingsDestination.SETUP)
        return
    }
    performDirectCaptureAction(context, action, onSettings, onPending)
}

internal fun performDirectCaptureAction(context: Context, action: CaptureAction,
    onSettings: (CaptureSettingsDestination) -> Unit, onPending: () -> Unit) {
    when (action) {
        CaptureAction.NOTIFICATION_ACCESS -> launchPermissionSettings(context, notificationAccessIntents(context))
        CaptureAction.NOTIFICATION_RECONNECT -> PaymentNotificationListener.requestReconnect(context)
        CaptureAction.SELECT_MODE -> onSettings(CaptureSettingsDestination.MODE)
        CaptureAction.ROOT_STATUS -> onSettings(CaptureSettingsDestination.ROOT_STATUS)
        CaptureAction.SUPPORTED_APPS -> onSettings(CaptureSettingsDestination.SUPPORTED_APPS)
        CaptureAction.RESUME -> CaptureModeManager.setEnabled(context, true)
        CaptureAction.PENDING -> onPending()
        CaptureAction.ACCESSIBILITY -> launchCaptureSettings(context, Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
        CaptureAction.OVERLAY -> launchCaptureSettings(context, Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        // 短信权限是运行时权限，必须走应用自己的请求才能弹出系统对话框；
        // 直接跳系统设置页在 MIUI 上会被折叠进「其他权限」深处。
        CaptureAction.SMS_PERMISSION -> onSettings(CaptureSettingsDestination.SMS)
        CaptureAction.NOTIFY -> launchCaptureSettings(context, Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName))
        CaptureAction.ROOT -> onSettings(CaptureSettingsDestination.ROOT_STATUS)
        // Manager navigation is separate from module enablement and loading probes.
        CaptureAction.LSP -> com.example.autobook.lsp.LsposedManagerLauncher.open(context)
    }
}

private fun launchCaptureSettings(context: Context, intent: Intent) {
    val launched = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!launched) runCatching { context.startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
internal fun CaptureHealthCard(health: CaptureHealth, onAction: (CaptureAction) -> Unit) {
    val p = LocalAbPalette.current
    Column(Modifier.fillMaxWidth().padding(AbSpacing.m), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
        Text(health.title, style = if (health.attention) AbType.headline else AbType.footnote,
            color = if (health.attention) p.label else p.secondaryLabel)
        if (health.kind != CaptureHealthKind.RUNNING) {
            Text(health.detail, style = AbType.footnote, color = p.secondaryLabel)
        }
        // Vertical actions remain reachable with large text and narrow windows.
        health.actions.forEach { action -> SecondaryButton(action.label, { onAction(action) }, Modifier.fillMaxWidth()) }
    }
}
