package io.github.mymoney

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.CaptureModePolicy
import io.github.mymoney.data.CaptureModeState
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

    @Test fun switchingModeKeepsPauseAndNoLongerPicksTheNotificationImplementation() {
        // 通知实现改由 ChannelSelection 按当时的可用性判（见 ChannelSelectionTest）；
        // 模式只剩「页面通道要不要跑」和「界面按哪套预设展示」。这里只钉住切模式不动开关。
        for (enabled in listOf(false, true)) {
            for (mode in CaptureMode.entries) {
                val current = CaptureModeState(mode, enabled, notificationsEnabled = true)
                val next = CaptureModePolicy.select(current, CaptureMode.entries.first { it != mode })
                assertEquals(enabled, next.enabled)
                assertTrue(next.notificationsEnabled)
            }
        }
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
    @Test fun legacyFlagsAloneDoNotCountAsConfigured() {
        val legacyOnly = CaptureModeState(
            mode = null,
            enabled = true,
            pageEnabled = false,
            notificationsEnabled = true,
            smsEnabled = true,
            hookEnabled = true,
        )
        assertFalse(legacyOnly.configured)
        assertFalse(legacyOnly.pageActive)
    }

    @Test fun firstSelectionFromUnconfiguredEnablesRecognitionAndPage() {
        val next = CaptureModePolicy.select(
            CaptureModeState(mode = null, enabled = false, pageEnabled = false),
            CaptureMode.ACCESSIBILITY,
        )
        assertTrue(next.configured)
        assertTrue(next.enabled)
        assertTrue(next.pageEnabled)
        assertTrue(next.pageActive)
        assertEquals(CaptureMode.ACCESSIBILITY, next.mode)
    }

    @Test fun switchingBetweenConfiguredModesPreservesPause() {
        val current = CaptureModeState(mode = CaptureMode.ROOT, enabled = false, pageEnabled = true)
        val next = CaptureModePolicy.select(current, CaptureMode.ACCESSIBILITY)
        assertTrue(next.configured)
        assertFalse(next.enabled)
        assertFalse(next.pageActive)
        assertEquals(CaptureMode.ACCESSIBILITY, next.mode)
    }

    @Test fun disablingMasterSwitchStopsPageActivityWhileKeepingChoice() {
        val paused = CaptureModeState(mode = CaptureMode.ACCESSIBILITY, enabled = false, pageEnabled = true)
        assertTrue(paused.configured)
        assertFalse(paused.pageActive)
        assertTrue(paused.copy(enabled = true).pageActive)
    }

    @Test fun pageDefaultNeverFollowsARestoredRootMode() {
        // 键缺失时的默认值只看「是不是无障碍预设」。阶段 4 之前它是 captureMode != null || rootMode，
        // 于是恢复一份 rootMode=true 的旧备份（写进一个 capture_mode）就能把页面通道从关掰成开。
        assertFalse(CaptureModePolicy.defaultPageEnabled(null))
        assertFalse(CaptureModePolicy.defaultPageEnabled(CaptureMode.ROOT))
        assertTrue(CaptureModePolicy.defaultPageEnabled(CaptureMode.ACCESSIBILITY))
        // 备份恢复只可能写进 ROOT（见 restore），恢复前后默认值一致，不会自己变开。
        for (legacyRoot in listOf(true, false)) {
            assertEquals(
                CaptureModePolicy.defaultPageEnabled(null),
                CaptureModePolicy.defaultPageEnabled(CaptureModePolicy.restore(null, legacyRoot)),
            )
        }
    }

    @Test fun legacyRootNotificationMovesOnceAndNeverTouchesTheCredentialChannel() {
        // 旧 ROOT + 旧「root 读通知」开着 → 搬进统一通知开关。
        assertTrue(CaptureModePolicy.legacyRootNotificationMoves(true, CaptureMode.ROOT, false, false))
        // 搬过了、新开关已有值、不是 ROOT 预设、旧开关本来没开：都不搬。
        assertFalse(CaptureModePolicy.legacyRootNotificationMoves(true, CaptureMode.ROOT, false, true))
        assertFalse(CaptureModePolicy.legacyRootNotificationMoves(true, CaptureMode.ROOT, true, false))
        assertFalse(CaptureModePolicy.legacyRootNotificationMoves(true, CaptureMode.ACCESSIBILITY, false, false))
        assertFalse(CaptureModePolicy.legacyRootNotificationMoves(true, null, false, false))
        assertFalse(CaptureModePolicy.legacyRootNotificationMoves(false, CaptureMode.ROOT, false, false))
        // 搬迁只动通知这一条：凭证通道仍要用户自己给授权、自己打开。
        val upgraded = CaptureModeState(CaptureMode.ROOT, true, pageEnabled = false, notificationsEnabled = true)
        assertTrue(upgraded.notificationActive)
        assertFalse(upgraded.hookEnabled)
        assertFalse(upgraded.hookActive)
    }

    @Test fun noChannelIsScopedByThePreset() {
        val state = CaptureModeState(CaptureMode.ROOT, true, pageEnabled = true, hookEnabled = true)
        // 阶段 4 之后三条通道都不受预设约束：页面辅助只有无障碍一种实现，但有授权就能跑，
        // 不该因为「当初填的是 Root 预设」而被关掉。
        assertTrue(state.pageActive)
        assertTrue(state.copy(mode = CaptureMode.ACCESSIBILITY).pageActive)
        assertTrue(state.copy(mode = null).pageActive)
        // 凭证通道跑在目标进程里，宿主用哪种采集方式与它无关：三种模式下都应可用。
        assertTrue(state.hookActive)
        assertTrue(state.copy(mode = CaptureMode.ACCESSIBILITY).hookActive)
        assertTrue(state.copy(mode = null).hookActive)
        // 不受预设约束不等于不受开关约束。
        assertFalse(state.copy(enabled = false).pageActive)
        assertFalse(state.copy(pageEnabled = false).pageActive)
        assertFalse(state.copy(enabled = false).hookActive)
        assertFalse(state.copy(hookEnabled = false).hookActive)
    }

}
