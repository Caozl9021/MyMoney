package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.ui.design.*

/**
 * 自动记账的总览页。回答「现在能不能记账」，通道细节都在下一层。
 *
 * 这里的每一块都按**设备具备什么**决定出不出：没有 root 就没有推荐配置，没有 LSPosed 就没有
 * 凭证入口。普通用户看到的只有「开没开、还缺哪个授权」，看不到任何自己用不上的名词。
 */
@Composable
internal fun AutomationPage(
    mode: CaptureMode?,
    enabled: Boolean,
    health: CaptureHealth,
    autoConfirm: Boolean,
    capabilities: DeviceCapabilities,
    showsLsp: Boolean,
    notice: CapabilityNotice?,
    onBack: () -> Unit,
    onAction: (CaptureAction) -> Unit,
    onApplyPreset: (CaptureMode) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleConfirm: (Boolean) -> Unit,
    onNavigate: (Route) -> Unit,
    onDismissNotice: () -> Unit = {},
) {
    val p = LocalAbPalette.current
    SubPageScaffold(title = "自动记账", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(AbLayout.groupGap),
        ) {
            if (notice != null) {
                item {
                    InsetGroup {
                        ListRow(
                            title = notice.title(),
                            subtitle = notice.detail(),
                            leading = { SettingsIconBadge(notice.title(), AbIcons.Layers) },
                            trailing = { TextButton(onClick = onDismissNotice) { Text("知道了", color = p.accent) } },
                            onClick = onDismissNotice,
                        )
                    }
                }
            }
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
            // 推荐配置只在真有 root 的机器上出现：没有 root 时它的一项永远是死选项。
            if (capabilities.rootGranted) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AbLayout.headingGap)) {
                        GroupHeader("推荐配置")
                        PresetSwitch(mode = mode, lspInstalled = capabilities.lspInstalled, onApply = onApplyPreset)
                    }
                }
            }
            item {
                InsetGroup {
                    ListRow(
                        title = if (health.attention) "完成设置" else "检查授权",
                        subtitle = if (showsLsp) "通知识别、页面辅助与凭证采集" else "通知识别、页面辅助",
                        leading = { SettingsIconBadge("识别设置", AbIcons.Filter) },
                        chevron = true, onClick = { onNavigate(Route.SOURCES) })
                    if (showsLsp) {
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
    /** 此刻实际在跑的实现；null 表示这一刻没有可用实现。 */
    val notificationPlan: io.github.mymoney.data.NotificationPath?,
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
    /** 模块加载是否已确认；凭证行要据此区分「开着」和「开着但没确认加载」。 */
    val hookReady: Boolean = false,
)

internal fun captureSettingsSnapshot(input: CaptureHealthInput, notifyGranted: Boolean, rootGranted: Boolean?) =
    CaptureModeStateSnapshot(input.notificationsEnabled, input.notificationPlan,
        input.notificationAuthorized, input.notificationBound,
        input.smsEnabled, input.smsGranted, input.hookEnabled, input.pageEnabled,
        input.accessibilityAuthorized, input.accessibilityBound, input.overlayAllowed, notifyGranted,
        rootGranted == true, input.enabled, input.rootNotificationReady, input.hookReady)
