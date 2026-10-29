package com.example.autobook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.autobook.data.CaptureMode
import com.example.autobook.ui.design.*

/** The overview answers whether capture is ready; source details live one level below. */
@Composable
internal fun AutomationPage(
    mode: CaptureMode?,
    enabled: Boolean,
    health: CaptureHealth,
    autoConfirm: Boolean,
    onBack: () -> Unit,
    onAction: (CaptureAction) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleConfirm: (Boolean) -> Unit,
    onNavigate: (Route) -> Unit,
    gate: ModeGate = ModeGate.READY,
    checkingRoot: Boolean = false,
) {
    val p = LocalAbPalette.current
    SubPageScaffold(title = "自动记账", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(AbLayout.groupGap),
        ) {
            item {
                InsetGroup {
                    ListRow(title = "启用自动记账", subtitle = health.title,
                        leading = { SettingsIconBadge("启用自动记账", AbIcons.Accessibility) },
                        trailing = { AbSwitch(enabled, onToggleEnabled) },
                        onClick = { onToggleEnabled(!enabled) })
                    if (enabled && health.attention) {
                        health.actions.firstOrNull()?.let { action ->
                            ListRow(title = health.detail, dividerAbove = true,
                                titleStyle = AbType.footnote,
                                trailing = { TextButton(onClick = { onAction(action) }) { Text(if (health.kind == CaptureHealthKind.SOURCES_DISABLED) "开启识别来源" else action.label, color = p.accent) } })
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AbLayout.headingGap)) {
                    GroupHeader("使用方式")
                    CaptureModeSwitch(mode = mode, onSelect = onSelectMode, gate = gate,
                        denied = gate.denial(), onRootBlocked = { onSelectMode(CaptureMode.ROOT) },
                        onGrant = onAction)
                }
            }
            item {
                InsetGroup {
                    ListRow(title = "识别设置",
                        subtitle = if (mode == CaptureMode.ROOT) "Root 通知识别" else "通知识别、页面辅助",
                        leading = { SettingsIconBadge("识别设置", AbIcons.Filter) },
                        chevron = true, onClick = { onNavigate(Route.SOURCES) })
                    if (showsLspSettings(mode)) {
                        ListRow(title = "LSPosed 模块", subtitle = "微信与支付宝交易采集",
                            leading = { SettingsIconBadge("LSPosed 模块", AbIcons.Layers) },
                            dividerAbove = true, chevron = true, onClick = { onNavigate(Route.LSP) })
                    }
                }
            }
            item {
                InsetGroup {
                    ListRow(title = "识别后弹出确认卡",
                        subtitle = if (autoConfirm) "核对后保存到账本" else "不弹卡，稍后到待确认中处理",
                        leading = { SettingsIconBadge("确认卡", AbIcons.Note) },
                        trailing = { AbSwitch(autoConfirm, onToggleConfirm) },
                        onClick = { onToggleConfirm(!autoConfirm) })
                }
            }
            item {
                InsetGroup {
                    ListRow(title = "更多设置", leading = { SettingsIconBadge("更多设置", AbIcons.Settings) },
                        chevron = true, onClick = { onNavigate(Route.MORE) })
                }
            }
        }
    }
}

internal enum class Route { SOURCES, LSP, MORE, SUPPORTED_APPS, LEARNING, TEMPLATES, RULE_FILES, MEALS, STATISTICS }

/** Uses raw permission/binding signals, including while the master switch is paused. */
internal data class CaptureModeStateSnapshot(
    val notificationsEnabled: Boolean,
    val notificationPath: com.example.autobook.data.NotificationPath?,
    val notificationAuthorized: Boolean,
    val notificationBound: Boolean,
    val smsEnabled: Boolean,
    val smsGranted: Boolean,
    val hookEnabled: Boolean,
    val pageEnabled: Boolean,
    val accessibilityAuthorized: Boolean,
    val accessibilityBound: Boolean,
    val overlayAllowed: Boolean,
    val notifyGranted: Boolean,
    val rootGranted: Boolean,
    val enabled: Boolean = true,
    val rootNotificationReady: Boolean = false,
)

internal fun captureSettingsSnapshot(input: CaptureHealthInput, notifyGranted: Boolean, rootGranted: Boolean?) =
    CaptureModeStateSnapshot(input.notificationsEnabled,
        if (input.mode == CaptureMode.ROOT) com.example.autobook.data.NotificationPath.DUMPSYS
        else com.example.autobook.data.NotificationPath.LISTENER,
        input.notificationAuthorized, input.notificationBound,
        input.smsEnabled, input.smsGranted, input.hookEnabled, input.pageEnabled,
        input.accessibilityAuthorized, input.accessibilityBound, input.overlayAllowed, notifyGranted,
        rootGranted == true, input.enabled, input.rootNotificationReady)
