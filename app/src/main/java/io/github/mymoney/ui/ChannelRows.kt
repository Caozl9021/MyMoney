package io.github.mymoney.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.ui.design.*

/**
 * 三条采集通道各一行：开关、它依赖的那一个系统授权、以及此刻真实在跑什么。
 *
 * 列哪几条由 [channelList] 决定——凭证渠道只在设备真的具备 LSPosed 能力时出现。
 * 这里**不看采集模式**：模式在阶段 4 已经退化成界面预设，通道在不在跑只取决于
 * 它自己的开关和授权。
 */
@Composable
internal fun ChannelRows(
    context: Context = LocalContext.current,
    state: CaptureModeStateSnapshot,
    showsHook: Boolean,
    onAction: (CaptureAction) -> Unit,
    onOpenHook: () -> Unit,
) {
    InsetGroup {
        channelList(showsHook).forEachIndexed { index, channel ->
            when (channel) {
                CaptureChannel.NOTIFICATION -> ListRow(
                    title = "通知识别",
                    subtitle = notificationRowStatus(state),
                    leading = { SettingsIconBadge("通知识别", AbIcons.Note) },
                    dividerAbove = index > 0,
                    trailing = { AbSwitch(state.notificationsEnabled) { CaptureModeManager.setNotificationEnabled(context, it) } },
                    onClick = {
                        // 按**实际实现**决定点哪里：跑的是 dumpsys 就去看 Root，跑的是 listener
                        // 就去补通知使用权。用模式判断会在「root 装机但 listener 正常」时指错地方。
                        when {
                            !state.notificationsEnabled -> CaptureModeManager.setNotificationEnabled(context, true)
                            state.notificationPlan == NotificationPath.DUMPSYS && !state.rootNotificationReady ->
                                onAction(CaptureAction.ROOT_STATUS)
                            !state.notificationAuthorized -> onAction(CaptureAction.NOTIFICATION_ACCESS)
                            !state.notificationBound -> onAction(CaptureAction.NOTIFICATION_RECONNECT)
                            else -> CaptureModeManager.setNotificationEnabled(context, false)
                        }
                    },
                )
                CaptureChannel.SMS -> Unit // Legacy enum only; self has no SMS row.
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
                CaptureChannel.HOOK -> ListRow(
                    title = "凭证采集",
                    subtitle = when {
                        !state.hookEnabled -> "已关闭"
                        !state.enabled -> "已开启 · 自动记账已暂停"
                        !state.hookReady -> "已开启 · 模块加载待确认"
                        else -> "已就绪 · 读取微信与支付宝的交易凭证"
                    },
                    leading = { SettingsIconBadge("凭证采集", AbIcons.Layers) },
                    dividerAbove = index > 0, chevron = true,
                    trailing = { AbSwitch(state.hookEnabled) { CaptureModeManager.setHookEnabled(context, it) } },
                    // 点行进模块页：那一页才有作用域、加载状态与重启，开关本身留在这一行。
                    onClick = onOpenHook,
                )
            }
        }
    }
}

/**
 * 通知通道这一行的状态。按**实际实现**说，不按采集模式：
 * root 模式但已授权通知时，跑的是 listener，还拿「Root 待检测」去催用户就是误报。
 * `notificationPlan == null` 说明这一刻两套实现都不可用，于是报最省事的那条缺（通知使用权）。
 */
internal fun notificationRowStatus(state: CaptureModeStateSnapshot): String = when {
    !state.notificationsEnabled -> "已关闭"
    !state.enabled -> "已开启 · 自动记账已暂停"
    state.notificationPlan == NotificationPath.DUMPSYS && !state.rootGranted && !state.rootNotificationReady ->
        "Root 授权待检测 · 点击处理"
    state.notificationPlan == NotificationPath.DUMPSYS && !state.rootNotificationReady ->
        "尚未就绪 · 点击查看 Root 状态"
    !state.notificationAuthorized -> "还差通知使用权 · 点击授权"
    !state.notificationBound -> "已授权但未连接 · 点击重新连接"
    else -> "已就绪 · 等待交易通知"
}
