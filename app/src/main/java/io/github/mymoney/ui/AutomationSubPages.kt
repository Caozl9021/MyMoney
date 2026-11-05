package io.github.mymoney.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.capture.HookTargetMonitor
import io.github.mymoney.lsp.HookLoadState
import io.github.mymoney.lsp.HookTargetStatus
import io.github.mymoney.data.Settings
import io.github.mymoney.ui.design.*

/**
 * 识别设置：三条通道各一行，下面挂它们共用的几项系统授权。
 *
 * [showsHook] 与 [showsRoot] 都来自设备能力而不是采集模式：没有 root 的机器上
 * 「Root 状态」那一页没有任何内容可看，没有 LSPosed 的机器上凭证通道也开不起来。
 */
@Composable
internal fun RecognitionSettingsPage(showsHook: Boolean, showsRoot: Boolean,
    snapshot: CaptureModeStateSnapshot, autoConfirm: Boolean, onBack: () -> Unit,
    onAction: (CaptureAction) -> Unit, onOpenHook: () -> Unit,
    onPermissions: () -> Unit, onRootStatus: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    var scopeNoticeSeen by remember { mutableStateOf(settings.pageRecognitionScopeNoticeSeen) }
    SettingsContentPage("识别方式与增强", onBack) {
        if (!scopeNoticeSeen) InsetGroup {
            ListRow(title = "页面识别范围调整",
                subtitle = "打开交易详情时自动识别；付款成功页由通知监听处理。点击知道了。",
                onClick = { settings.pageRecognitionScopeNoticeSeen = true; scopeNoticeSeen = true })
        }
        ChannelRows(state = snapshot, showsHook = showsHook, onAction = onAction, onOpenHook = onOpenHook)
        ScreenTextConsentRow()
        val overlay = autoConfirm && !snapshot.overlayAllowed
        InsetGroup {
            if (showsRoot) ListRow(title = "Root 状态", chevron = true, onClick = onRootStatus)
            if (overlay) ListRow(title = "允许悬浮确认卡",
                subtitle = "授权后可直接显示在支付页面上", chevron = true,
                dividerAbove = showsRoot, onClick = { onAction(CaptureAction.OVERLAY) })
            ListRow(title = "权限与后台运行", chevron = true,
                dividerAbove = showsRoot || overlay, onClick = onPermissions)
        }
    }
}

@Composable
internal fun LspSettingsPage(hookEnabled: Boolean, enabled: Boolean, showsLsp: Boolean,
    onBack: () -> Unit, onAction: (CaptureAction) -> Unit) {
    val context = LocalContext.current
    val targets by HookTargetMonitor.state.collectAsStateWithLifecycle()
    val restartState by io.github.mymoney.lsp.ScopeAppRestarter.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 打开这一页、或从别处回到前台时都重探一次模块状态：这一页存在的意义就是看加载状态，
    // 而它现在与采集模式无关，没有理由再按模式决定探不探。
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) HookTargetMonitor.detect(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 拉起管理器要用 root shell 还是寄生 Activity，取决于这台机器有没有 root——先把能力探一次。
    LaunchedEffect(Unit) {
        CaptureModeManager.ensureRootProbed(context)
        HookTargetMonitor.detect(context)
    }
    SettingsContentPage("LSPosed 模块", onBack) {
        if (!showsLsp) {
            GroupFooter("本机未检测到 LSPosed，也没有打开过凭证采集。")
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
                    onClick = if (restartState.busy) null else ({ io.github.mymoney.lsp.ScopeAppRestarter.restart(context) }))
            }
            InsetGroup {
                ListRow(title = "打开 LSPosed 管理器", chevron = true, onClick = { onAction(CaptureAction.LSP) })
            }
        }
    }
}


@Composable
private fun ScreenTextConsentRow() {
    val context = LocalContext.current
    val prefs = remember { io.github.mymoney.ocr.OcrPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var explain by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    InsetGroup {
        ListRow(title = "页面文字补全", subtitle = if (!io.github.mymoney.ocr.LocalOcrEngine.isSupported)
            "当前OCR需要Android 11及以上和64位进程" else if (enabled)
            "已同意 · 必要时在本机读取支付截图" else "可选 · 补充节点未读到的字段",
            trailing = { AbSwitch(enabled) { value ->
                if (value && io.github.mymoney.ocr.LocalOcrEngine.isSupported) explain = true else if (!value && prefs.setEnabled(false)) enabled = false else error = "保存失败，请重试"
            } }, onClick = if (!io.github.mymoney.ocr.LocalOcrEngine.isSupported) null else ({ explain = true }))
        error?.let { ListRow(title = it) }
    }
    if (explain) androidx.compose.material3.AlertDialog(onDismissRequest = { explain = false },
        title = { Text("允许页面文字补全？") },
        text = { Text("仅在微信、支付宝可确认的付款或账单页面，必要时读取截图并在本机识别。不上传、不永久保存原图，也不自动保存账单。密码和验证页面会跳过；已有识别方式继续可用。") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            if (io.github.mymoney.ocr.LocalOcrEngine.isSupported && prefs.setEnabled(true)) { enabled = true; explain = false }
            else { error = "当前无法启用，请检查系统版本或稍后重试"; explain = false }
        }) { Text("同意并开启") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { explain = false }) { Text("暂不开启") } })
}
