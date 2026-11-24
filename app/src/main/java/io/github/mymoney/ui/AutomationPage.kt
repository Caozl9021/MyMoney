package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mymoney.ocr.LocalOcrEngine
import io.github.mymoney.ocr.OcrPreferences
import io.github.mymoney.ui.design.*

internal enum class AutomationRoute { BACKGROUND, APPS, LEARNING, MEALS }

/** 自动记账单页主控台：核心开关、权限保活、范围与偏好。 */
@Composable
internal fun AutomationPage(
    enabled: Boolean,
    health: CaptureHealth,
    autoConfirm: Boolean,
    islandConfirm: Boolean,
    islandSupported: Boolean,
    accessibilityAuthorized: Boolean,
    accessibilityBound: Boolean,
    overlayGranted: Boolean,
    appCount: Int,
    learnedCount: Int,
    onBack: () -> Unit,
    onAction: (CaptureAction) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleConfirm: (Boolean) -> Unit,
    onToggleIsland: (Boolean) -> Unit,
    onNavigate: (AutomationRoute) -> Unit,
) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val ocrPrefs = remember { OcrPreferences(context) }
    var ocrEnabled by remember { mutableStateOf(ocrPrefs.enabled) }
    var showOcrPrompt by remember { mutableStateOf(false) }

    SubPageScaffold(title = "自动记账", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbLayout.groupGap),
        ) {
            item {
                InsetGroup {
                    ListRow(
                        title = "自动记账",
                        leading = { SettingsIconBadge("自动记账", AbIcons.Accessibility) },
                        trailing = { AbSwitch(enabled, onToggleEnabled) },
                        onClick = { onToggleEnabled(!enabled) },
                    )
                    if (enabled && health.attention) {
                        health.actions.firstOrNull()?.let { action ->
                            ListRow(
                                title = health.detail,
                                dividerAbove = true,
                                titleStyle = AbType.footnote,
                                trailing = {
                                    AbTextButton(action.label, onClick = { onAction(action) })
                                },
                            )
                        }
                    }
                    ListRow(
                        title = "弹出确认卡",
                        leading = { SettingsIconBadge("弹出确认卡", AbIcons.Note) },
                        dividerAbove = true,
                        trailing = { AbSwitch(autoConfirm, onToggleConfirm) },
                        onClick = { onToggleConfirm(!autoConfirm) },
                    )
                    if (LocalOcrEngine.isSupported) {
                        ListRow(
                            title = "页面 OCR 补全",
                            leading = { SettingsIconBadge("页面 OCR 补全", AbIcons.Ocr) },
                            dividerAbove = true,
                            trailing = {
                                AbSwitch(ocrEnabled) { value ->
                                    if (value) showOcrPrompt = true
                                    else if (ocrPrefs.setEnabled(false)) ocrEnabled = false
                                }
                            },
                            onClick = {
                                if (!ocrEnabled) showOcrPrompt = true
                                else if (ocrPrefs.setEnabled(false)) ocrEnabled = false
                            },
                        )
                    }
                    if (islandSupported) {
                        ListRow(
                            title = "超级岛提醒",
                            leading = { SettingsIconBadge("超级岛提醒", AbIcons.Note) },
                            dividerAbove = true,
                            trailing = { AbSwitch(islandConfirm, onToggleIsland) },
                            onClick = { onToggleIsland(!islandConfirm) },
                        )
                    }
                }
            }

            item {
                InsetGroup {
                    ListRow(
                        title = "无障碍服务",
                        leading = { SettingsIconBadge("无障碍服务", AbIcons.Accessibility) },
                        trailing = {
                            Text(
                                text = when {
                                    accessibilityBound -> "已连接"
                                    accessibilityAuthorized -> "未连接"
                                    else -> "未开启"
                                },
                                style = AbType.footnote,
                                color = p.secondaryLabel,
                            )
                        },
                        chevron = true,
                        onClick = { onAction(CaptureAction.ACCESSIBILITY) },
                    )
                    ListRow(
                        title = "悬浮窗权限",
                        leading = { SettingsIconBadge("悬浮窗权限", AbIcons.Layers) },
                        trailing = {
                            Text(
                                text = if (overlayGranted) "已允许" else "未允许",
                                style = AbType.footnote,
                                color = p.secondaryLabel,
                            )
                        },
                        dividerAbove = true,
                        chevron = true,
                        onClick = { onAction(CaptureAction.OVERLAY) },
                    )
                    ListRow(
                        title = "后台保活",
                        leading = { SettingsIconBadge("后台保活", AbIcons.Shield) },
                        dividerAbove = true,
                        chevron = true,
                        onClick = { onNavigate(AutomationRoute.BACKGROUND) },
                    )
                }
            }

            item {
                InsetGroup {
                    ListRow(
                        title = "监控应用",
                        leading = { SettingsIconBadge("监控应用", AbIcons.Store) },
                        trailing = {
                            Text("$appCount 个", style = AbType.footnote, color = p.secondaryLabel)
                        },
                        chevron = true,
                        onClick = { onNavigate(AutomationRoute.APPS) },
                    )
                    ListRow(
                        title = "学习记忆",
                        leading = { SettingsIconBadge("学习记忆", AbIcons.Note) },
                        trailing = {
                            Text("$learnedCount 条", style = AbType.footnote, color = p.secondaryLabel)
                        },
                        dividerAbove = true,
                        chevron = true,
                        onClick = { onNavigate(AutomationRoute.LEARNING) },
                    )
                    ListRow(
                        title = "餐段设置",
                        leading = { SettingsIconBadge("餐段设置", AbIcons.Clock) },
                        dividerAbove = true,
                        chevron = true,
                        onClick = { onNavigate(AutomationRoute.MEALS) },
                    )
                }
            }
        }
    }

    if (showOcrPrompt) {
        AppAlertDialog(
            title = "开启页面 OCR 补全？",
            message = "仅在微信、支付宝交易详情页本地识别缺失字段，不上传图片。",
            onDismiss = { showOcrPrompt = false },
            confirmLabel = "开启",
            onConfirm = {
                if (ocrPrefs.setEnabled(true)) ocrEnabled = true
                showOcrPrompt = false
            },
            dismissLabel = "取消",
        )
    }
}
