package io.github.mymoney

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.ui.*
import org.junit.Assert.*
import org.junit.Test

class CaptureHealthTest {
    // 位置参数：mode, enabled, accessibilityAuthorized, accessibilityBound, overlayAllowed, autoConfirm
    // （rootRunning 已随 Root 页面轮询删除，不再是输入项）
    private val running = CaptureHealthInput(CaptureMode.ACCESSIBILITY, true, true, true, true, true,
        notificationPlan = NotificationPath.LISTENER)

    @Test fun unconfiguredPrecedesPauseAndPermissions() {
        // 一条通道都没配过时，先说的是「还没开启自动记账」，动作指向自动记账页而不是某个系统设置。
        val result = captureHealth(running.copy(mode = null, notificationsEnabled = false,
            enabled = false, overlayAllowed = false))
        assertEquals(CaptureHealthKind.MODE_REQUIRED, result.kind)
        assertEquals(listOf(CaptureAction.OPEN_AUTOMATION), result.actions)
        assertFalse(result.title.contains("模式"))
    }
    @Test fun pausedNeverPretendsServiceFailure() {
        val result = captureHealth(running.copy(enabled = false, accessibilityBound = false))
        assertEquals(CaptureHealthKind.PAUSED, result.kind)
        assertEquals(listOf(CaptureAction.RESUME), result.actions)
    }
    @Test fun dumpsysNotificationNeedsNeitherAccessibilityNorNotificationAccess() {
        // 走 dumpsys 时无障碍信号与通知使用权都不影响**通知**这条路；页面辅助关着，
        // 所以也就不该冒出无障碍的待办。这条与「选了哪个预设」无关，只看此刻的实现。
        val input = running.copy(mode = CaptureMode.ROOT, notificationsEnabled = true, pageEnabled = false,
            rootNotificationReady = true, notificationPlan = NotificationPath.DUMPSYS,
            accessibilityAuthorized = false, accessibilityBound = false)
        assertNotEquals(CaptureHealthKind.ACCESSIBILITY_DISABLED, captureHealth(input).kind)
        assertNotEquals(CaptureHealthKind.ACCESSIBILITY_UNBOUND, captureHealth(input).kind)
        assertNotEquals(CaptureHealthKind.NOTIFICATION_DISABLED, captureHealth(input).kind)
        assertEquals(CaptureHealthKind.NOTIFICATION_OBSERVING, captureHealth(input).kind)
        assertFalse(captureHealth(input).attention)
    }

    @Test fun pageChannelProblemIsReportedEvenUnderTheRootPreset() {
        // 拆掉互斥后的直接后果：Root 预设 + 页面辅助开着却没授权，健康卡必须报，
        // 而不是像以前那样因为「不是无障碍模式」而闭嘴。
        val input = running.copy(mode = CaptureMode.ROOT, notificationsEnabled = true,
            rootNotificationReady = true, notificationPlan = NotificationPath.DUMPSYS,
            accessibilityAuthorized = false, accessibilityBound = false)
        assertEquals(CaptureHealthKind.DEGRADED, captureHealth(input).kind)
        assertEquals(listOf(CaptureAction.ACCESSIBILITY), captureHealth(input).actions)
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
        val root = running.copy(mode = CaptureMode.ROOT, notificationsEnabled = true, pageEnabled = false,
            notificationPlan = NotificationPath.DUMPSYS)
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

    @Test fun hookOnlyRequiresTargetSourceEvidenceNotACaptureMode() {
        val hook = running.copy(mode = CaptureMode.ROOT, notificationsEnabled = false, pageEnabled = false,
            hookEnabled = true, hookReady = false)
        assertFalse(hook.recognitionReady)
        assertEquals(CaptureHealthKind.HOOK_UNCONFIRMED, captureHealth(hook).kind)
        assertEquals(listOf(CaptureAction.LSP), captureHealth(hook).actions)
        val observed = hook.copy(hookReady = true)
        assertTrue(observed.recognitionReady)
        assertEquals(CaptureHealthKind.RUNNING, captureHealth(observed).kind)
        assertTrue(captureHealth(observed).title.contains("LSPosed"))
        // 凭证通道跑在目标进程里，宿主用无障碍也照样就绪——曾在这里断言 false，
        // 那正是「无障碍用户永远开不了最准的通道」的由来。
        val accessible = observed.copy(mode = CaptureMode.ACCESSIBILITY, pageEnabled = false)
        assertTrue(accessible.recognitionReady)
        assertTrue(accessible.lspReady)
        assertEquals(CaptureHealthKind.RUNNING, captureHealth(accessible).kind)
        assertFalse(observed.copy(enabled = false).recognitionReady)
    }

    @Test fun hookOnlyConfigurationDoesNotDemandAPresetChoice() {
        // 只开了凭证通道、没选模式：不该被要求去选「使用方式」，它已经能采了。
        val hook = running.copy(mode = null, notificationsEnabled = false, pageEnabled = false,
            hookEnabled = true, hookReady = true)
        assertTrue(hook.recognitionReady)
        assertEquals(CaptureHealthKind.RUNNING, captureHealth(hook).kind)
    }

    @Test fun noOverlayDoesNotEraseRecognitionReadiness() {
        val input = running.copy(overlayAllowed = false)
        assertTrue(input.recognitionReady)
        val result = captureHealth(input)
        assertEquals(CaptureHealthKind.OVERLAY_MISSING, result.kind)
        assertTrue(result.title.startsWith("已开启"))
    }

}
