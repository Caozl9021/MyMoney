package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.ui.*
import org.junit.Assert.*
import org.junit.Test

class CaptureHealthTest {
    // 位置参数：mode, enabled, accessibilityAuthorized, accessibilityBound, overlayAllowed, autoConfirm
    // （rootRunning 已随 Root 页面轮询删除，不再是输入项）
    private val running = CaptureHealthInput(CaptureMode.ACCESSIBILITY, true, true, true, true, true)

    @Test fun modeChoicePrecedesPauseAndPermissions() {
        val result = captureHealth(running.copy(mode = null, enabled = false, overlayAllowed = false))
        assertEquals(CaptureHealthKind.MODE_REQUIRED, result.kind)
        assertEquals(listOf(CaptureAction.SELECT_MODE), result.actions)
    }
    @Test fun pausedNeverPretendsServiceFailure() {
        val result = captureHealth(running.copy(enabled = false, accessibilityBound = false))
        assertEquals(CaptureHealthKind.PAUSED, result.kind)
        assertEquals(listOf(CaptureAction.RESUME), result.actions)
    }
    @Test fun rootModeNeverNeedsAccessibilityOrNotificationPermission() {
        // root 模式只读通知栏：无障碍信号与通知使用权都不该影响健康判定。
        // 但通知是这个模式下唯一的来源，必须开着——关了就是真的没来源。
        val input = running.copy(mode = CaptureMode.ROOT, notificationsEnabled = true, rootNotificationReady = true,
            accessibilityAuthorized = false, accessibilityBound = false)
        assertNotEquals(CaptureHealthKind.ACCESSIBILITY_DISABLED, captureHealth(input).kind)
        assertNotEquals(CaptureHealthKind.ACCESSIBILITY_UNBOUND, captureHealth(input).kind)
        assertNotEquals(CaptureHealthKind.NOTIFICATION_DISABLED, captureHealth(input).kind)
        // root 模式下通知是唯一来源且已开着，健康卡报「正在读取通知」。
        assertEquals(CaptureHealthKind.NOTIFICATION_OBSERVING, captureHealth(input).kind)
        assertFalse(captureHealth(input).attention)
    }
    @Test fun checkedButUnboundDiffersFromMissingAuthorization() {
        val unbound = captureHealth(running.copy(accessibilityBound = false))
        val disabled = captureHealth(running.copy(accessibilityAuthorized = false, accessibilityBound = false))
        assertEquals(CaptureHealthKind.ACCESSIBILITY_UNBOUND, unbound.kind)
        assertEquals(CaptureHealthKind.ACCESSIBILITY_DISABLED, disabled.kind)
        assertTrue(unbound.detail.contains("已授权"))
        assertEquals(listOf(CaptureAction.ACCESSIBILITY), unbound.actions)
        assertEquals(unbound.actions, disabled.actions)
    }
    @Test fun bindingLossAndRecoveryDoNotNeedPreferenceChanges() {
        val samples = listOf(running, running.copy(accessibilityBound = false), running)
        assertEquals(listOf(CaptureHealthKind.RUNNING, CaptureHealthKind.ACCESSIBILITY_UNBOUND, CaptureHealthKind.RUNNING), samples.map { captureHealth(it).kind })
    }
    @Test fun missingOverlayPreservesFallbackAndPendingActions() {
        val result = captureHealth(running.copy(overlayAllowed = false))
        assertEquals(CaptureHealthKind.OVERLAY_MISSING, result.kind)
        assertEquals(listOf(CaptureAction.OVERLAY, CaptureAction.PENDING), result.actions)
        assertTrue(result.detail.contains("确认页"))
        assertTrue(result.detail.contains("待确认"))
        assertTrue(result.detail.contains("通知"))
    }
    @Test fun intentionalPendingModeNeedsNoOverlayAndIsNotFailure() {
        val result = captureHealth(running.copy(autoConfirm = false, overlayAllowed = false))
        assertEquals(CaptureHealthKind.PENDING_MODE, result.kind)
        assertFalse(result.attention)
        assertEquals(listOf(CaptureAction.PENDING), result.actions)
        assertEquals(CaptureHealthKind.ACCESSIBILITY_UNBOUND, captureHealth(running.copy(autoConfirm = false, accessibilityBound = false)).kind)
    }
    @Test fun runningHasNoRepairAction() {
        val result = captureHealth(running)
        assertEquals(CaptureHealthKind.RUNNING, result.kind)
        assertFalse(result.attention)
        assertTrue(result.actions.isEmpty())
    }
    @Test fun notificationOnlyNeedsNoAccessibilityAndReportsScopedTemplateCoverage() {
        val result = captureHealth(running.copy(mode = null, pageEnabled = false,
            notificationsEnabled = true, notificationAuthorized = true, notificationBound = true,
            accessibilityAuthorized = false, accessibilityBound = false))
        assertEquals(CaptureHealthKind.NOTIFICATION_OBSERVING, result.kind)
        assertFalse(result.attention)
        // 163 条规则接管后不再有「仅支付宝模板」这种覆盖面说法。
        assertTrue(result.detail.contains("交易提醒"))
    }

    @Test fun notificationAuthorizationAndBindingAreDistinct() {
        val only = running.copy(mode = null, pageEnabled = false, notificationsEnabled = true)
        assertEquals(CaptureHealthKind.NOTIFICATION_DISABLED, captureHealth(only).kind)
        assertEquals(CaptureHealthKind.NOTIFICATION_UNBOUND,
            captureHealth(only.copy(notificationAuthorized = true)).kind)
        assertEquals(CaptureHealthKind.PAUSED,
            captureHealth(only.copy(enabled = false)).kind)
    }

    @Test fun workingPageSurvivesNotificationFailureAndViceVersa() {
        val page = captureHealth(running.copy(notificationsEnabled = true))
        assertEquals(CaptureHealthKind.DEGRADED, page.kind)
        assertTrue(page.title.contains("页面辅助") && page.title.contains("可用"))
        val notification = captureHealth(running.copy(notificationsEnabled = true,
            notificationAuthorized = true, notificationBound = true, accessibilityBound = false))
        assertEquals(CaptureHealthKind.DEGRADED, notification.kind)
        assertTrue(notification.title.contains("通知识别") && notification.title.contains("可用"))
    }

    @Test fun intentionallyDisabledSourcesAreNotReportedAsLostPermissions() {
        val result = captureHealth(running.copy(pageEnabled = false))
        assertEquals(CaptureHealthKind.SOURCES_DISABLED, result.kind)
        assertFalse(result.kind == CaptureHealthKind.ACCESSIBILITY_DISABLED)
    }

    @Test fun enabledDoesNotMeanRootCanRead() {
        val root = running.copy(mode = CaptureMode.ROOT, notificationsEnabled = true)
        assertEquals(CaptureHealthKind.ROOT_UNREADY, captureHealth(root).kind)
        assertFalse(root.recognitionReady)
        val failure = captureHealth(root.copy(rootNotificationError = "Root 未授权"))
        assertTrue(failure.detail.contains("未授权"))
        assertEquals(listOf(CaptureAction.ROOT_STATUS), failure.actions)
        assertEquals(CaptureHealthKind.NOTIFICATION_OBSERVING,
            captureHealth(root.copy(rootNotificationReady = true)).kind)
    }

    @Test fun legacySmsOnlyCannotReportSelfAsReady() {
        val sms = running.copy(mode = null, pageEnabled = false,
            smsEnabled = true, smsGranted = true)
        assertFalse(sms.recognitionReady)
        assertEquals(CaptureHealthKind.MODE_REQUIRED, captureHealth(sms).kind)
        assertFalse(captureHealth(sms).title.contains("短信"))
        assertFalse(captureHealth(sms.copy(smsGranted = false)).actions.contains(CaptureAction.SMS_PERMISSION))
    }

    @Test fun legacySmsCannotMaskAnUnavailablePageSource() {
        val sms = running.copy(pageEnabled = true, accessibilityBound = false,
            smsEnabled = true, smsGranted = true)
        assertFalse(sms.recognitionReady)
        assertEquals(CaptureHealthKind.ACCESSIBILITY_UNBOUND, captureHealth(sms).kind)
        assertFalse(captureHealth(sms).title.contains("短信"))
    }

    @Test fun hookOnlyRequiresRootAndTargetSourceEvidence() {
        val hook = running.copy(mode = CaptureMode.ROOT, notificationsEnabled = false,
            hookEnabled = true, hookReady = false)
        assertFalse(hook.recognitionReady)
        assertEquals(CaptureHealthKind.HOOK_UNCONFIRMED, captureHealth(hook).kind)
        assertEquals(listOf(CaptureAction.LSP), captureHealth(hook).actions)
        val observed = hook.copy(hookReady = true)
        assertTrue(observed.recognitionReady)
        assertEquals(CaptureHealthKind.RUNNING, captureHealth(observed).kind)
        assertTrue(captureHealth(observed).title.contains("LSPosed"))
        assertFalse(observed.copy(mode = CaptureMode.ACCESSIBILITY, pageEnabled = false).recognitionReady)
        assertFalse(observed.copy(enabled = false).recognitionReady)
    }

    @Test fun noOverlayDoesNotEraseRecognitionReadiness() {
        val input = running.copy(overlayAllowed = false)
        assertTrue(input.recognitionReady)
        val result = captureHealth(input)
        assertEquals(CaptureHealthKind.OVERLAY_MISSING, result.kind)
        assertTrue(result.title.startsWith("已开启"))
    }

}
