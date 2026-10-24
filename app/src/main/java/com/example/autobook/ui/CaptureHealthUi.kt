package com.example.autobook.ui

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
import com.example.autobook.data.Settings
import com.example.autobook.root.RootWatcher
import com.example.autobook.ui.design.*

/** Observes concrete binding and preferences; system permissions are refreshed on return. No polling. */
@Composable
internal fun rememberCaptureHealthInput(): CaptureHealthInput {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val capture by CaptureModeManager.state.collectAsStateWithLifecycle()
    val root by RootWatcher.status.collectAsStateWithLifecycle()
    val bound by BillAccessibilityService.bound.collectAsStateWithLifecycle()
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
    return remember(capture, root.running, bound, revision) {
        CaptureHealthInput(capture.mode, capture.enabled, root.running,
            AccessibilityGuardian.isEnabledInSettings(context), bound,
            SystemSettings.canDrawOverlays(context), Settings(context).autoConfirm)
    }
}

internal fun performCaptureAction(context: Context, action: CaptureAction,
    onSettings: (CaptureSettingsDestination) -> Unit, onPending: () -> Unit) {
    when (action) {
        CaptureAction.SELECT_MODE -> onSettings(CaptureSettingsDestination.MODE)
        CaptureAction.ROOT_STATUS -> onSettings(CaptureSettingsDestination.ROOT_STATUS)
        CaptureAction.SUPPORTED_APPS -> onSettings(CaptureSettingsDestination.SUPPORTED_APPS)
        CaptureAction.RESUME -> CaptureModeManager.setEnabled(context, true)
        CaptureAction.PENDING -> onPending()
        CaptureAction.ACCESSIBILITY -> launchCaptureSettings(context, Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
        CaptureAction.OVERLAY -> launchCaptureSettings(context, Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
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
