package com.example.autobook.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.capture.HookTargetMonitor
import com.example.autobook.lsp.HookLoadState
import com.example.autobook.lsp.HookTargetStatus
import com.example.autobook.data.CaptureMode
import com.example.autobook.data.Settings
import com.example.autobook.ui.design.*

@Composable
internal fun RecognitionSettingsPage(mode: CaptureMode?, snapshot: CaptureModeStateSnapshot,
    autoConfirm: Boolean, onBack: () -> Unit, onAction: (CaptureAction) -> Unit,
    onPermissions: () -> Unit, onRootStatus: () -> Unit) {
    SettingsContentPage("识别设置", onBack) {
        ChannelRows(mode = mode, state = snapshot, onAction = onAction,
            channels = channelsFor(mode).filter { it != CaptureChannel.SMS })
        InsetGroup {
            if (mode == CaptureMode.ROOT) ListRow(title = "Root 状态", chevron = true, onClick = onRootStatus)
            if (autoConfirm && !snapshot.overlayAllowed) ListRow(title = "允许悬浮确认卡",
                subtitle = "授权后可直接显示在支付页面上", chevron = true,
                dividerAbove = mode == CaptureMode.ROOT, onClick = { onAction(CaptureAction.OVERLAY) })
            ListRow(title = "权限与后台运行", chevron = true,
                dividerAbove = mode == CaptureMode.ROOT || (autoConfirm && !snapshot.overlayAllowed), onClick = onPermissions)
        }
    }
}

@Composable
internal fun LspSettingsPage(mode: CaptureMode?, hookEnabled: Boolean, enabled: Boolean,
    onBack: () -> Unit, onAction: (CaptureAction) -> Unit) {
    val context = LocalContext.current
    val targets by HookTargetMonitor.state.collectAsStateWithLifecycle()
    val restartState by com.example.autobook.lsp.ScopeAppRestarter.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && mode == CaptureMode.ROOT) {
                HookTargetMonitor.detect(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(mode) { if (mode == CaptureMode.ROOT) {
        HookTargetMonitor.detect(context)
    } }
    SettingsContentPage("LSPosed 模块", onBack) {
        if (!showsLspSettings(mode)) {
            GroupFooter("请先切换到 Root 模式。")
        } else {
            InsetGroup {
                ListRow(title = "交易采集", subtitle = when {
                    !hookEnabled -> "已关闭"
                    !enabled -> "已开启 · 自动记账已暂停"
                    targets.values.none { it.state == HookLoadState.READY } -> "已开启 · 模块加载待确认"
                    else -> "已开启"
                }, leading = { SettingsIconBadge("交易采集", AbIcons.Note) },
                    trailing = { AbSwitch(hookEnabled) { CaptureModeManager.setHookEnabled(context, it) } },
                    onClick = { CaptureModeManager.setHookEnabled(context, !hookEnabled) })
                listOf("com.tencent.mm" to "微信", "com.eg.android.AlipayGphone" to "支付宝").forEach { (app, label) ->
                    ListRow(title = label,
                        subtitle = targetHookSubtitle(targets[app] ?: HookTargetStatus(), enabled, hookEnabled),
                        dividerAbove = true)
                }
                ListRow(title = "重新检测应用模块", dividerAbove = true,
                    onClick = { HookTargetMonitor.detect(context) })
                ListRow(title = if (restartState.busy) "正在重启…" else "重启微信与支付宝",
                    subtitle = restartState.message, dividerAbove = true,
                    onClick = if (restartState.busy) null else ({ com.example.autobook.lsp.ScopeAppRestarter.restart(context) }))
            }
            InsetGroup {
                ListRow(title = "打开 LSPosed 管理器", chevron = true, onClick = { onAction(CaptureAction.LSP) })
            }
        }
    }
}
