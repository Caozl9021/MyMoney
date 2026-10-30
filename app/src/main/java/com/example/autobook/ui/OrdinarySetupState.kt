package com.example.autobook.ui

import com.example.autobook.data.CaptureMode

/** Raw readiness remains meaningful while capture is paused during first configuration. */
internal data class OrdinarySetupState(
    val available: Boolean,
    val notificationReady: Boolean,
    val pageReady: Boolean,
    val sourceReady: Boolean,
    val presentationReady: Boolean,
    val selectedSourcesReady: Boolean,
    val nextAction: CaptureAction?,
) {
    val canStart: Boolean get() = available && sourceReady && presentationReady
    val complete: Boolean get() = canStart && selectedSourcesReady
}

internal fun ordinarySetupState(input: CaptureHealthInput): OrdinarySetupState = with(input) {
    val available = mode != CaptureMode.ROOT
    val notification = available && notificationsEnabled && notificationAuthorized && notificationBound
    val page = mode == CaptureMode.ACCESSIBILITY && pageEnabled && accessibilityAuthorized && accessibilityBound
    val source = notification || page
    val presentation = !autoConfirm || overlayAllowed
    val next = if (!available) null else when {
        notificationsEnabled && !notificationAuthorized -> CaptureAction.NOTIFICATION_ACCESS
        notificationsEnabled && !notificationBound -> CaptureAction.NOTIFICATION_RECONNECT
        mode == CaptureMode.ACCESSIBILITY && pageEnabled && (!accessibilityAuthorized || !accessibilityBound) -> CaptureAction.ACCESSIBILITY
        !source -> null
        !presentation -> CaptureAction.OVERLAY
        else -> null
    }
    OrdinarySetupState(available, notification, page, source, presentation,
        (!notificationsEnabled || notification) && (mode != CaptureMode.ACCESSIBILITY || !pageEnabled || page), next)
}

internal fun ordinarySetupDestination(mode: CaptureMode?, action: CaptureAction): Boolean =
    mode == CaptureMode.ACCESSIBILITY && action in setOf(CaptureAction.NOTIFICATION_ACCESS,
        CaptureAction.NOTIFICATION_RECONNECT, CaptureAction.ACCESSIBILITY, CaptureAction.OVERLAY,
        CaptureAction.SELECT_MODE, CaptureAction.RESUME)
