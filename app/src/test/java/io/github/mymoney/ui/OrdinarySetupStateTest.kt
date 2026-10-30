package io.github.mymoney.ui

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.data.CaptureModePolicy
import io.github.mymoney.data.CaptureModeState
import org.junit.Assert.*
import org.junit.Test

class OrdinarySetupStateTest {
    private val ready = CaptureHealthInput(CaptureMode.ACCESSIBILITY, false, true, true, true, true,
        notificationsEnabled = true, notificationAuthorized = true, notificationBound = true,
        notificationPlan = NotificationPath.LISTENER)

    @Test fun newConfigurationIsOnePausedSnapshotWithBothRecommendedSources() {
        val next = CaptureModePolicy.prepareOrdinary(CaptureModeState())
        assertEquals(CaptureMode.ACCESSIBILITY, next.mode)
        assertFalse(next.enabled)
        assertTrue(next.notificationsEnabled && next.pageEnabled)
        assertFalse(next.pageActive || next.notificationActive)
    }

    @Test fun enteringSetupNeverRewritesAnExistingChoiceIncludingSmsOnly() {
        val states = listOf(CaptureModeState(CaptureMode.ROOT, true, false, false, true, true),
            CaptureModeState(CaptureMode.ACCESSIBILITY, false, false, false),
            CaptureModeState(null, true, false, false, true))
        states.forEach { assertEquals(it, CaptureModePolicy.prepareOrdinary(it)) }
    }

    @Test fun pausedReadyPermissionsCanStartWithoutAnyRecognition() {
        val state = ordinarySetupState(ready)
        assertTrue(state.canStart && state.complete)
        assertNull(state.nextAction)
        assertFalse(ready.recognitionReady)
        assertTrue(FirstRunGuide.configurationComplete(ready.copy(enabled = true)))
        assertFalse(FirstRunGuide.steps(ready.copy(enabled = true), false).last().complete)
    }

    @Test fun authorizationAndBindingHaveSeparateNextActions() {
        assertEquals(CaptureAction.NOTIFICATION_ACCESS, ordinarySetupState(ready.copy(
            notificationAuthorized = false, notificationBound = true)).nextAction)
        val disconnected = ordinarySetupState(ready.copy(notificationBound = false))
        assertEquals(CaptureAction.NOTIFICATION_RECONNECT, disconnected.nextAction)
        assertFalse(disconnected.notificationReady)
        assertFalse(disconnected.complete)
    }

    @Test fun pageCannotBeReadyFromACheckedSystemSwitchAlone() {
        val input = ready.copy(notificationsEnabled = false, accessibilityBound = false)
        val state = ordinarySetupState(input)
        assertFalse(state.canStart || state.pageReady)
        assertEquals(CaptureAction.ACCESSIBILITY, state.nextAction)
    }

    @Test fun oneRealSourceCanStartWhileAnotherSelectedSourceNeedsRepair() {
        val state = ordinarySetupState(ready.copy(notificationAuthorized = false))
        assertTrue(state.pageReady && state.canStart)
        assertFalse(state.complete)
        assertEquals(CaptureAction.NOTIFICATION_ACCESS, state.nextAction)
    }

    @Test fun skippingPageDoesNotForceAccessibilityOrPretendItIsAvailable() {
        val state = ordinarySetupState(ready.copy(pageEnabled = false, accessibilityAuthorized = false,
            accessibilityBound = false))
        assertTrue(state.complete && state.notificationReady)
        assertFalse(state.pageReady)
        assertNull(state.nextAction)
    }

    @Test fun selectedSourceWithoutPermissionIsNeverReadyEvenWhenBoundSignalIsStale() {
        val state = ordinarySetupState(ready.copy(notificationAuthorized = false, accessibilityAuthorized = false))
        assertFalse(state.sourceReady || state.canStart)
    }

    @Test fun missingOverlayBlocksCardsButExplicitPendingModeCanStart() {
        val input = ready.copy(overlayAllowed = false)
        assertFalse(ordinarySetupState(input).canStart)
        assertEquals(CaptureAction.OVERLAY, ordinarySetupState(input).nextAction)
        assertTrue(ordinarySetupState(input.copy(autoConfirm = false)).canStart)
    }

    @Test fun noSourcesCannotStartRegardlessOfPermissions() {
        val state = ordinarySetupState(ready.copy(notificationsEnabled = false, pageEnabled = false))
        assertFalse(state.canStart || state.sourceReady || state.complete)
        assertNull(state.nextAction)
    }

    @Test fun legacySmsOnlyCannotStartSelfRecognition() {
        val state = ordinarySetupState(ready.copy(mode = null, notificationsEnabled = false, pageEnabled = false,
            smsEnabled = true, smsGranted = true, overlayAllowed = false, autoConfirm = false))
        assertFalse(state.canStart || state.complete || state.sourceReady)
        assertFalse(state.pageReady)
    }

    @Test fun rootPresetGetsTheSameGuidedSetupAsAnyOther() {
        // 阶段 4 拆掉了互斥：root 用户同样要补通知使用权与无障碍授权，这一页不该把他挡回去。
        val state = ordinarySetupState(ready.copy(mode = CaptureMode.ROOT))
        assertTrue(state.canStart && state.complete && state.notificationReady && state.pageReady)
        assertNull(state.nextAction)
    }

    @Test fun rootFallbackPointsAtRootStatusInsteadOfNotificationAccess() {
        // 走 dumpsys 时缺的不是通知使用权而是 Root 读取；给错去处等于让人白跑一趟系统设置。
        val unready = ready.copy(mode = CaptureMode.ROOT, notificationPlan = NotificationPath.DUMPSYS,
            notificationAuthorized = false, notificationBound = false, rootNotificationReady = false)
        assertEquals(CaptureAction.ROOT_STATUS, ordinarySetupState(unready).nextAction)
        assertFalse(ordinarySetupState(unready).notificationReady)
        assertTrue(ordinarySetupState(unready.copy(rootNotificationReady = true)).notificationReady)
    }
}
