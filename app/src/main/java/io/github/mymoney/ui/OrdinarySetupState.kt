package io.github.mymoney.ui

import io.github.mymoney.data.NotificationPath

/** Raw readiness remains meaningful while capture is paused during first configuration. */
internal data class OrdinarySetupState(
    val notificationReady: Boolean,
    val pageReady: Boolean,
    val sourceReady: Boolean,
    val presentationReady: Boolean,
    val selectedSourcesReady: Boolean,
    val nextAction: CaptureAction?,
) {
    val canStart: Boolean get() = sourceReady && presentationReady
    val complete: Boolean get() = canStart && selectedSourcesReady
}

/**
 * 首次配置页的就绪判定。**不看采集模式**：这一页问的是「通知和页面两条通道各自好了没」，
 * 曾经那层 `mode != ROOT` 的守卫只是把 root 用户赶去另一个页面——
 * 而 root 用户同样可以用通知使用权，同样可以开无障碍。
 *
 * 通知那一路按**实际实现**判：跑 listener 就要求授权且已连接，跑 dumpsys 就要求 Root 读取就绪。
 */
internal fun ordinarySetupState(input: CaptureHealthInput): OrdinarySetupState = with(input) {
    val notification = notificationsEnabled && when (notificationPlan) {
        NotificationPath.LISTENER -> notificationAuthorized && notificationBound
        NotificationPath.DUMPSYS -> rootNotificationReady
        null -> false
    }
    val page = pageEnabled && accessibilityAuthorized && accessibilityBound
    val source = notification || page
    val presentation = !autoConfirm || overlayAllowed
    val next = when {
        notificationsEnabled && !notification -> when (notificationPlan) {
            NotificationPath.DUMPSYS -> CaptureAction.ROOT_STATUS
            else -> if (!notificationAuthorized) CaptureAction.NOTIFICATION_ACCESS
                else CaptureAction.NOTIFICATION_RECONNECT
        }
        pageEnabled && (!accessibilityAuthorized || !accessibilityBound) -> CaptureAction.ACCESSIBILITY
        !source -> null
        !presentation -> CaptureAction.OVERLAY
        else -> null
    }
    OrdinarySetupState(notification, page, source, presentation,
        (!notificationsEnabled || notification) && (!pageEnabled || page), next)
}
