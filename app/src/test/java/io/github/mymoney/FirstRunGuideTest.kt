package io.github.mymoney

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.ui.*
import org.junit.Assert.*
import org.junit.Test

class FirstRunGuideTest {
    private val ready = CaptureHealthInput(
        mode = CaptureMode.ACCESSIBILITY,
        enabled = true,
        accessibilityAuthorized = true,
        accessibilityBound = true,
        overlayAllowed = true,
        autoConfirm = true,
    )

    @Test fun unconfiguredOrUnauthorizedAccessibilityCannotCompleteSetup() {
        assertFalse(FirstRunGuide.steps(ready.copy(mode = null), true).first().complete)
        assertFalse(FirstRunGuide.configurationComplete(ready.copy(mode = null)))
        assertFalse(FirstRunGuide.steps(ready.copy(accessibilityAuthorized = false, accessibilityBound = false), true).first().complete)
        assertFalse(FirstRunGuide.configurationComplete(ready.copy(accessibilityAuthorized = false, accessibilityBound = false)))
        assertTrue(FirstRunGuide.steps(ready, true).all { it.complete })
    }

    @Test fun pausedOrUnconnectedSourceCannotCompleteSetup() {
        assertFalse(FirstRunGuide.steps(ready.copy(enabled = false), true).first().complete)
        assertFalse(FirstRunGuide.steps(ready.copy(accessibilityBound = false), true).first().complete)
        assertFalse(FirstRunGuide.steps(ready, false).last().complete)
    }

    @Test fun optionalCardsNeedNoOverlayButEnabledCardsDo() {
        assertFalse(FirstRunGuide.steps(ready.copy(overlayAllowed = false), true)[1].complete)
        assertTrue(FirstRunGuide.steps(ready.copy(overlayAllowed = false, autoConfirm = false), true)[1].complete)
    }

    @Test fun sourceConfigurationCompletesWithoutForcingOptionalCardPermission() {
        assertTrue(FirstRunGuide.configurationComplete(ready.copy(overlayAllowed = false)))
        assertFalse(FirstRunGuide.steps(ready.copy(overlayAllowed = false), false)[1].complete)
    }

    @Test fun manualEntriesCannotCompleteRecognitionStep() {
        assertFalse(FirstRunGuide.hasRecognizedSource("io.github.mymoney.manual.123"))
        assertFalse(FirstRunGuide.hasRecognizedSource(""))
        assertTrue(FirstRunGuide.hasRecognizedSource("com.tencent.mm"))
    }
}
