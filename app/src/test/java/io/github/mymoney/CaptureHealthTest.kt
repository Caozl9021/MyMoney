package io.github.mymoney

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.ui.*
import org.junit.Assert.*
import org.junit.Test

class CaptureHealthTest {
    private val running = CaptureHealthInput(
        mode = CaptureMode.ACCESSIBILITY,
        enabled = true,
        accessibilityAuthorized = true,
        accessibilityBound = true,
        overlayAllowed = true,
        autoConfirm = true,
    )

    @Test fun unconfiguredPrecedesPauseAndPermissions() {
        val unconfigured = running.copy(
            mode = null,
            enabled = false,
            accessibilityAuthorized = false,
            overlayAllowed = false,
        )
        assertFalse(unconfigured.recognitionReady)
        assertFalse(running.copy(mode = null).recognitionReady)
        val result = captureHealth(unconfigured)
        assertEquals(CaptureHealthKind.MODE_REQUIRED, result.kind)
        assertTrue(result.attention)
        assertEquals("未开启", result.shortStatus)
        assertEquals("自动记账未开启", result.title)
        assertEquals(listOf(CaptureAction.OPEN_AUTOMATION), result.actions)
    }

    @Test fun pausedPrecedesServiceAndPermissionFailures() {
        val result = captureHealth(
            running.copy(
                enabled = false,
                accessibilityAuthorized = false,
                accessibilityBound = false,
                overlayAllowed = false,
            ),
        )
        assertEquals(CaptureHealthKind.PAUSED, result.kind)
        assertTrue(result.attention)
        assertEquals("已暂停", result.shortStatus)
        assertEquals("自动记账已暂停", result.title)
        assertEquals(listOf(CaptureAction.RESUME), result.actions)
    }

    @Test fun missingAccessibilityAuthorizationAndUnboundDiffer() {
        val disabled = captureHealth(running.copy(accessibilityAuthorized = false, accessibilityBound = false))
        val unbound = captureHealth(running.copy(accessibilityBound = false))

        assertEquals(CaptureHealthKind.ACCESSIBILITY_DISABLED, disabled.kind)
        assertTrue(disabled.attention)
        assertEquals("需授权", disabled.shortStatus)
        assertEquals("需开启无障碍服务", disabled.title)
        assertEquals(listOf(CaptureAction.ACCESSIBILITY), disabled.actions)

        assertEquals(CaptureHealthKind.ACCESSIBILITY_UNBOUND, unbound.kind)
        assertTrue(unbound.attention)
        assertEquals("未连接", unbound.shortStatus)
        assertEquals("无障碍服务未连接", unbound.title)
        assertEquals(listOf(CaptureAction.ACCESSIBILITY), unbound.actions)
    }

    @Test fun bindingLossAndRecoveryDoNotNeedPreferenceChanges() {
        val samples = listOf(running, running.copy(accessibilityBound = false), running)
        assertEquals(
            listOf(CaptureHealthKind.RUNNING, CaptureHealthKind.ACCESSIBILITY_UNBOUND, CaptureHealthKind.RUNNING),
            samples.map { captureHealth(it).kind },
        )
    }

    @Test fun rootPresetAlsoRequiresAccessibilityHealth() {
        val result = captureHealth(running.copy(mode = CaptureMode.ROOT, accessibilityAuthorized = false, accessibilityBound = false))
        assertEquals(CaptureHealthKind.ACCESSIBILITY_DISABLED, result.kind)
        assertEquals(listOf(CaptureAction.ACCESSIBILITY), result.actions)
    }

    @Test fun intentionalPendingModeNeedsNoOverlayAndIsNotFailure() {
        val result = captureHealth(running.copy(autoConfirm = false, overlayAllowed = false))
        assertEquals(CaptureHealthKind.PENDING_MODE, result.kind)
        assertFalse(result.attention)
        assertEquals("待确认", result.shortStatus)
        assertEquals("自动记账已开启", result.title)
        assertEquals(listOf(CaptureAction.PENDING), result.actions)

        // When accessibility itself drops, pending mode still surfaces the binding failure.
        assertEquals(
            CaptureHealthKind.ACCESSIBILITY_UNBOUND,
            captureHealth(running.copy(autoConfirm = false, accessibilityBound = false)).kind,
        )
    }

    @Test fun missingOverlayKeepsRecognitionReadyAndSurfacesFallbackActions() {
        val input = running.copy(overlayAllowed = false)
        assertTrue(input.recognitionReady)

        val result = captureHealth(input)
        assertEquals(CaptureHealthKind.OVERLAY_MISSING, result.kind)
        assertTrue(result.attention)
        assertEquals("需悬浮窗", result.shortStatus)
        assertEquals("需开启悬浮窗", result.title)
        assertEquals(listOf(CaptureAction.OVERLAY, CaptureAction.PENDING), result.actions)
    }

    @Test fun runningHasNoRepairActionAndEmptyDetail() {
        assertTrue(running.recognitionReady)
        val result = captureHealth(running)
        assertEquals(CaptureHealthKind.RUNNING, result.kind)
        assertFalse(result.attention)
        assertEquals("已开启", result.shortStatus)
        assertEquals("自动记账已开启", result.title)
        assertEquals("", result.detail)
        assertTrue(result.actions.isEmpty())
    }
}
