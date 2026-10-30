package io.github.caozl9021.mymoney.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.caozl9021.mymoney.capture.CaptureModeManager
import io.github.caozl9021.mymoney.capture.ConfirmDelivery
import io.github.caozl9021.mymoney.capture.PaymentNotificationListener
import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.data.Settings
import io.github.caozl9021.mymoney.ui.design.*

@Composable
internal fun OrdinarySetupPage(onBack: () -> Unit, onBackground: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val input = rememberCaptureHealthInput()
    val state = ordinarySetupState(input)
    val p = LocalAbPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var launching by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { launching = false; revision++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        launching = false
        revision++ // A refusal stays here; opening Settings always requires another click.
    }
    fun ordinary(): Boolean = CaptureModeManager.state.value.mode != CaptureMode.ROOT
    fun source(notification: Boolean, enabled: Boolean) {
        if (!ordinary()) return
        if (CaptureModeManager.state.value.mode == null) CaptureModeManager.select(context, CaptureMode.ACCESSIBILITY)
        val current = CaptureModeManager.state.value
        if (notification && current.notificationsEnabled != enabled) CaptureModeManager.setNotificationEnabled(context, enabled)
        else if (!notification && current.pageEnabled != enabled) CaptureModeManager.setPageEnabled(context, enabled)
    }
    fun open(action: CaptureAction) {
        if (launching || !ordinary()) return
        error = null
        if (action == CaptureAction.NOTIFICATION_RECONNECT) {
            PaymentNotificationListener.requestReconnect(context)
            return
        }
        val intents = when (action) {
            CaptureAction.NOTIFICATION_ACCESS -> notificationAccessIntents(context)
            CaptureAction.ACCESSIBILITY -> listOf(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
            CaptureAction.OVERLAY -> listOf(Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
            else -> return
        }
        launching = launchPermissionSettings(context, intents)
        if (!launching) error = "无法打开系统设置，请在系统设置中找到 麦米，手动开启对应权限。"
    }
    fun notification(enabled: Boolean) {
        if (launching || !ordinary()) return
        source(true, enabled)
        if (enabled && !input.notificationAuthorized) open(CaptureAction.NOTIFICATION_ACCESS)
        else if (enabled && !input.notificationBound) open(CaptureAction.NOTIFICATION_RECONNECT)
    }
    fun page(enabled: Boolean) {
        if (launching || !ordinary()) return
        source(false, enabled)
        if (enabled && (!input.accessibilityAuthorized || !input.accessibilityBound)) open(CaptureAction.ACCESSIBILITY)
    }
    fun start() {
        // Read once more at click time: lifecycle/navigation callbacks may outlive their old snapshot.
        if (ordinary() && ordinarySetupState(readCaptureHealthInput(context)).canStart) {
            if (!CaptureModeManager.state.value.enabled) CaptureModeManager.setEnabled(context, true)
            onBack()
        }
    }
    SettingsContentPage("开启自动记账", onBack) {
        if (!state.available) {
            GroupFooter("当前为 Root 模式，请返回使用对应设置。")
        } else {
            GroupFooter(when {
                state.complete -> if (input.enabled) "配置已就绪 · 自动记账已开启" else "配置已就绪 · 点击开始识别"
                state.canStart -> "部分来源可用，可补全设置或使用现有来源开始。"
                else -> "依次开启需要的权限，返回后会自动检查。"
            })
            InsetGroup {
                ListRow(title = "读取交易通知", subtitle = when {
                    !input.notificationsEnabled -> "未开启 · 点击设置"
                    !input.notificationAuthorized -> "待授权 · 读取所选应用的交易提醒"
                    !input.notificationBound -> "已授权，监听尚未连接 · 点击重新连接"
                    else -> "已就绪 · 通知监听已连接"
                }, leading = { SettingsIconBadge("读取交易通知", AbIcons.Note) },
                    trailing = { AbSwitch(input.notificationsEnabled) { notification(it) } },
                    onClick = if (launching || state.notificationReady) null else ({ notification(true) }))
                ListRow(title = "识别支付页面", subtitle = when {
                    !input.pageEnabled || input.mode == null -> "未开启 · 点击设置"
                    !input.accessibilityAuthorized -> "待授权 · 辅助识别支付结果和账单页面"
                    !input.accessibilityBound -> "已授权，服务尚未连接 · 点击重新开启"
                    else -> "已就绪 · 无障碍服务已连接"
                }, leading = { SettingsIconBadge("识别支付页面", AbIcons.Accessibility) }, dividerAbove = true,
                    trailing = { AbSwitch(input.mode == CaptureMode.ACCESSIBILITY && input.pageEnabled) { page(it) } },
                    onClick = if (launching || state.pageReady) null else ({ page(true) }))
                ListRow(title = "显示悬浮确认卡", subtitle = when {
                    !input.autoConfirm -> "无需授权 · 识别后到待确认处理"
                    input.overlayAllowed -> "已允许 · 可在支付页面上核对"
                    else -> "待允许 · 在其他应用上显示确认卡"
                }, leading = { SettingsIconBadge("悬浮确认卡", AbIcons.Layers) }, dividerAbove = true,
                    chevron = input.autoConfirm && !input.overlayAllowed,
                    onClick = if (!input.autoConfirm || input.overlayAllowed || launching) null
                        else ({ open(CaptureAction.OVERLAY) }))
            }
            error?.let { Text(it, style = AbType.footnote, color = p.red) }
            val action = state.nextAction
            PrimaryButton(when (action) {
                CaptureAction.NOTIFICATION_ACCESS -> "开启通知识别"
                CaptureAction.NOTIFICATION_RECONNECT -> "重新连接通知监听"
                CaptureAction.ACCESSIBILITY -> "开启页面辅助"
                CaptureAction.OVERLAY -> "允许悬浮确认卡"
                else -> if (state.canStart) {
                    if (input.enabled) "完成设置" else "开始识别"
                } else "使用推荐配置"
            }, onClick = {
                when {
                    action != null -> open(action)
                    state.canStart -> start()
                    else -> {
                        if (ordinary()) {
                            if (CaptureModeManager.state.value.mode == null) CaptureModeManager.select(context, CaptureMode.ACCESSIBILITY)
                            CaptureModeManager.useRecommendedOrdinarySources(context)
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !launching)
            if (state.canStart && action != null) {
                SecondaryButton("使用现有来源开始", ::start, Modifier.fillMaxWidth(), enabled = !launching)
            }
            if (action == CaptureAction.NOTIFICATION_ACCESS || action == CaptureAction.NOTIFICATION_RECONNECT ||
                action == CaptureAction.ACCESSIBILITY) {
                TextButton(onClick = {
                    source(action != CaptureAction.ACCESSIBILITY, false)
                }, enabled = !launching, modifier = Modifier.fillMaxWidth()) {
                    Text(if (action == CaptureAction.ACCESSIBILITY) "暂不开启页面辅助" else "暂不开启通知识别", color = p.secondaryLabel)
                }
            }
            if (input.autoConfirm && !input.overlayAllowed) {
                TextButton(onClick = { if (ordinary()) settings.autoConfirm = false }, Modifier.fillMaxWidth()) {
                    Text("先用待确认，不自动弹卡", color = p.secondaryLabel)
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(if (input.enabled) "返回" else "稍后设置，先记账", color = p.secondaryLabel)
            }
            InsetGroup {
                ListRow(title = "提醒与后台运行", subtitle = "可选", chevron = true,
                    onClick = { expanded = !expanded })
                if (expanded) {
                    // Include revision so permission-result-only changes also refresh these signals.
                    val reminders = remember(input, revision) { reminderStatus(context) }
                    ListRow(title = "发送待确认提醒", subtitle = reminders,
                        dividerAbove = true, chevron = true, onClick = if (launching) null else ({
                            if (ordinary()) {
                                val uiPrefs = context.getSharedPreferences("permission_ui", Context.MODE_PRIVATE)
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                val activity = context.setupActivity()
                                val mayRequest = Build.VERSION.SDK_INT >= 33 && !granted &&
                                    (!uiPrefs.getBoolean("notification_asked", false) ||
                                        (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)))
                                if (mayRequest) {
                                    uiPrefs.edit().putBoolean("notification_asked", true).apply()
                                    launching = true
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    val intent = if (ConfirmDelivery.hasNotificationPermission(context))
                                        Intent(SystemSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                            .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
                                            .putExtra(SystemSettings.EXTRA_CHANNEL_ID, ConfirmDelivery.CHANNEL_ID)
                                    else Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
                                    launching = launchPermissionSettings(context, listOf(intent))
                                    if (!launching) error = "无法打开通知设置，请在系统中手动检查 麦米 的通知。"
                                }
                            }
                        }))
                    ListRow(title = "后台运行设置", subtitle = "电池、自启动与服务检查", dividerAbove = true,
                        chevron = true, onClick = onBackground)
                    ListRow(title = "识别后弹出确认卡", subtitle = if (input.autoConfirm) "核对后保存" else "稍后到待确认处理",
                        dividerAbove = true, trailing = { AbSwitch(input.autoConfirm) { if (ordinary()) settings.autoConfirm = it } })
                }
            }
        }
    }
}

internal fun reminderStatus(context: Context): String {
    if (!ConfirmDelivery.hasNotificationPermission(context)) return "未允许 · 可稍后开启"
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager?.getNotificationChannel(ConfirmDelivery.CHANNEL_ID)
    return if (channel?.importance == NotificationManager.IMPORTANCE_NONE) "待确认通知渠道已关闭 · 点击设置"
        else "已允许 · 弹卡失败时发送待确认提醒"
}

private tailrec fun Context.setupActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.setupActivity()
    else -> null
}
