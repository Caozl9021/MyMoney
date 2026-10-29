package com.example.autobook.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.data.CaptureMode
import com.example.autobook.ui.design.*

/** Source switch and its actual permission live in the same row. */
@Composable
internal fun ChannelRows(
    context: Context = LocalContext.current,
    mode: CaptureMode?,
    state: CaptureModeStateSnapshot,
    onAction: (CaptureAction) -> Unit,
    channels: List<CaptureChannel> = channelsFor(mode),
) {
    InsetGroup {
        channels.forEachIndexed { index, channel ->
            when (channel) {
                CaptureChannel.NOTIFICATION -> ListRow(
                    title = "通知识别",
                    subtitle = notificationRowStatus(mode, state),
                    leading = { SettingsIconBadge("通知识别", AbIcons.Note) },
                    dividerAbove = index > 0,
                    trailing = { AbSwitch(state.notificationsEnabled) { CaptureModeManager.setNotificationEnabled(context, it) } },
                    onClick = {
                        when {
                            !state.notificationsEnabled -> CaptureModeManager.setNotificationEnabled(context, true)
                            mode == CaptureMode.ROOT && !state.rootNotificationReady -> onAction(CaptureAction.ROOT_STATUS)
                            mode != CaptureMode.ROOT && !state.notificationAuthorized -> onAction(CaptureAction.NOTIFICATION_ACCESS)
                            mode != CaptureMode.ROOT && !state.notificationBound -> onAction(CaptureAction.NOTIFICATION_RECONNECT)
                            else -> CaptureModeManager.setNotificationEnabled(context, false)
                        }
                    },
                )
                CaptureChannel.SMS -> ListRow(
                    title = "银行短信",
                    subtitle = when {
                        !state.smsEnabled -> "已关闭"
                        !state.smsGranted -> "还差短信权限 · 点击授权"
                        !state.enabled -> "已开启 · 自动记账已暂停"
                        else -> "已就绪 · 识别新收到的银行短信"
                    },
                    leading = { SettingsIconBadge("银行短信", AbIcons.Note) }, dividerAbove = index > 0,
                    trailing = { AbSwitch(state.smsEnabled) { CaptureModeManager.setSmsEnabled(context, it) } },
                    onClick = {
                        if (state.smsEnabled && !state.smsGranted) onAction(CaptureAction.SMS_PERMISSION)
                        else CaptureModeManager.setSmsEnabled(context, !state.smsEnabled)
                    },
                )
                CaptureChannel.PAGE -> ListRow(
                    title = "页面辅助",
                    subtitle = when {
                        !state.pageEnabled -> "已关闭"
                        !state.accessibilityAuthorized -> "还差无障碍授权 · 点击开启"
                        !state.accessibilityBound -> "已授权但未连接 · 点击重新开启"
                        !state.enabled -> "已开启 · 自动记账已暂停"
                        else -> "已就绪 · 识别支付结果和账单页面"
                    },
                    leading = { SettingsIconBadge("页面辅助", AbIcons.Accessibility) }, dividerAbove = index > 0,
                    trailing = { AbSwitch(state.pageEnabled) { CaptureModeManager.setPageEnabled(context, it) } },
                    onClick = {
                        if (state.pageEnabled && !state.accessibilityBound) onAction(CaptureAction.ACCESSIBILITY)
                        else CaptureModeManager.setPageEnabled(context, !state.pageEnabled)
                    },
                )
                CaptureChannel.HOOK -> Unit // LSPosed has one dedicated Root-only page.
            }
        }
    }
}

internal fun notificationRowStatus(mode: CaptureMode?, state: CaptureModeStateSnapshot): String = when {
    !state.notificationsEnabled -> "已关闭"
    !state.enabled -> "已开启 · 自动记账已暂停"
    mode == CaptureMode.ROOT && !state.rootGranted && !state.rootNotificationReady -> "Root 授权待检测 · 点击处理"
    mode != CaptureMode.ROOT && !state.notificationAuthorized -> "还差通知使用权 · 点击授权"
    mode != CaptureMode.ROOT && !state.notificationBound -> "已授权但未连接 · 点击重新连接"
    mode == CaptureMode.ROOT && !state.rootNotificationReady -> "尚未就绪 · 点击查看 Root 状态"
    else -> "已就绪 · 等待交易通知"
}
