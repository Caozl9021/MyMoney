package com.example.autobook

import com.example.autobook.data.CaptureMode
import com.example.autobook.data.CaptureModePolicy
import com.example.autobook.data.CaptureModeState
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
            assertEquals(CaptureModeState(mode, true), CaptureModePolicy.select(CaptureModeState(), mode))
        }
    }

    @Test fun switchingPreservesPauseAndNeverActivatesBothSources() {
        for (enabled in listOf(false, true)) {
            for (mode in CaptureMode.entries) {
                val current = CaptureModeState(mode, enabled)
                val next = CaptureModePolicy.select(current, CaptureMode.entries.first { it != mode })
                assertEquals(enabled, next.enabled)
                assertFalse(next.rootActive && next.accessibilityActive)
                assertEquals(enabled, next.rootActive || next.accessibilityActive)
            }
        }
        assertFalse(CaptureModeState(null, true).rootActive)
        assertFalse(CaptureModeState(null, true).accessibilityActive)
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
}
