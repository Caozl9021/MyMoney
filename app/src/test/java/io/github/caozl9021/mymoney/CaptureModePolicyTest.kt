package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.data.CaptureModePolicy
import io.github.caozl9021.mymoney.data.CaptureModeState
import io.github.caozl9021.mymoney.data.NotificationPath
import org.junit.Assert.*
import org.junit.Test

class CaptureModePolicyTest {
    @Test fun freshInstallRemainsUnselected() {
        assertNull(CaptureModePolicy.migrate(null, false, false))
    }

    @Test fun legacyRootWinsOverBoundAccessibility() {
        assertEquals(CaptureMode.ROOT, CaptureModePolicy.migrate(null, true, true))
    }

    @Test fun legacyAccessibilityNeedsReliableSystemConfiguration() {
        assertEquals(CaptureMode.ACCESSIBILITY, CaptureModePolicy.migrate(null, false, true))
        assertNull(CaptureModePolicy.migrate(null, false, false))
    }

    @Test fun explicitChoiceWinsOverLegacyFlags() {
        assertEquals(CaptureMode.ACCESSIBILITY, CaptureModePolicy.migrate("ACCESSIBILITY", true, true))
        assertEquals(CaptureMode.ROOT, CaptureModePolicy.migrate("ROOT", false, false))
    }

    @Test fun firstChoiceEnablesRecognition() {
        for (mode in CaptureMode.entries) {
            assertEquals(CaptureModeState(mode, true, notificationsEnabled = true), CaptureModePolicy.select(CaptureModeState(), mode))
        }
    }

    @Test fun switchingPreservesPauseAndNeverActivatesBothSources() {
        // rootActive / accessibilityActive 随 Root 页面轮询一起删除：模式不再直接决定"在跑"，
        // 只决定通知通道的实现（Listener 还是 dumpsys）。这里改成校验同一件事：
        // 非 root 模式的通知通道必须是 LISTENER，root 模式必须是 DUMPSYS，两者不可能同时成立。
        for (enabled in listOf(false, true)) {
            for (mode in CaptureMode.entries) {
                val current = CaptureModeState(mode, enabled, notificationsEnabled = true)
                val next = CaptureModePolicy.select(current, CaptureMode.entries.first { it != mode })
                assertEquals(enabled, next.enabled)
                val path = CaptureModeState(next.mode, next.enabled, notificationsEnabled = true).notificationPath
                assertEquals(if (enabled) {
                    if (next.mode == CaptureMode.ROOT) NotificationPath.DUMPSYS else NotificationPath.LISTENER
                } else null, path)
            }
        }
        // 没选模式时通知通道没有实现可走。
        assertNull(CaptureModeState(null, true, notificationsEnabled = true).notificationPath)
    }

    @Test fun oldBackupCannotOverrideCurrentMode() {
        for (mode in CaptureMode.entries) {
            assertEquals(mode, CaptureModePolicy.restore(mode, true))
            assertEquals(mode, CaptureModePolicy.restore(mode, false))
        }
    }

    @Test fun backupOnlyMigratesAnExplicitLegacyRootChoice() {
        assertEquals(CaptureMode.ROOT, CaptureModePolicy.restore(null, true))
        assertNull(CaptureModePolicy.restore(null, false))
    }
    @Test fun notificationOnlyDoesNotNeedPageModeAndPauseStopsAllSources() {
        val only = CaptureModeState(mode = null, enabled = true, pageEnabled = false, notificationsEnabled = true)
        assertTrue(only.configured)
        // 通知开关开着但没选方式：没有实现可走，所以通知通道不算在跑。
        assertFalse(only.notificationActive)
        assertNull(only.notificationPath)
        assertFalse(only.copy(enabled = false).notificationActive)
    }

    @Test fun disablingPageKeepsChoiceAndNotificationActivity() {
        for (mode in CaptureMode.entries) {
            val state = CaptureModeState(mode, true, pageEnabled = false, notificationsEnabled = true)
            assertEquals(mode, state.mode)
            assertTrue(state.notificationActive)
            assertFalse(state.pageActive)
        }
    }

    @Test fun selectingPageFromPausedNotificationOnlyPreservesPause() {
        val current = CaptureModeState(null, false, false, true)
        val next = CaptureModePolicy.select(current, CaptureMode.ACCESSIBILITY)
        assertFalse(next.enabled)
        assertTrue(next.notificationsEnabled)
        assertFalse(next.pageEnabled)
        assertEquals(CaptureMode.ACCESSIBILITY, next.mode)
        assertFalse(next.pageActive)
    }

    @Test fun switchingPreservesExplicitChannelChoices() {
        val current = CaptureModeState(CaptureMode.ROOT, false, pageEnabled = false,
            notificationsEnabled = false, smsEnabled = true, hookEnabled = true)
        val next = CaptureModePolicy.select(current, CaptureMode.ACCESSIBILITY)
        assertFalse(next.enabled)
        assertFalse(next.pageEnabled)
        assertFalse(next.notificationsEnabled)
        assertTrue(next.smsEnabled)
        assertTrue(next.hookEnabled)
        assertFalse(next.hookActive)
        assertTrue(next.copy(mode = CaptureMode.ROOT, enabled = true).hookActive)
    }

    @Test fun alreadyConfiguredModeNullKeepsIndependentChannels() {
        val smsOnly = CaptureModeState(null, false, pageEnabled = false, smsEnabled = true)
        val next = CaptureModePolicy.select(smsOnly, CaptureMode.ACCESSIBILITY)
        assertFalse(next.enabled)
        assertFalse(next.pageEnabled)
        assertFalse(next.notificationsEnabled)
        assertTrue(next.smsEnabled)
    }

    @Test fun pageAndHookAreStrictlyModeScoped() {
        val state = CaptureModeState(CaptureMode.ROOT, true, pageEnabled = true, hookEnabled = true)
        assertFalse(state.pageActive)
        assertTrue(state.hookActive)
        assertTrue(state.copy(mode = CaptureMode.ACCESSIBILITY).pageActive)
        assertFalse(state.copy(mode = CaptureMode.ACCESSIBILITY).hookActive)
        assertFalse(state.copy(mode = null).pageActive)
        assertFalse(state.copy(mode = null).hookActive)
    }

}
