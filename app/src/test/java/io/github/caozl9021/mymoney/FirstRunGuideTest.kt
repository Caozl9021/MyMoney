package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.ui.*
import org.junit.Assert.*
import org.junit.Test

class FirstRunGuideTest {
    private val ready = CaptureHealthInput(CaptureMode.ACCESSIBILITY, true, true, true, true, true)

    @Test fun rootMasterAloneCannotCompleteSetup() {
        val input = ready.copy(mode = CaptureMode.ROOT, notificationsEnabled = true)
        assertFalse(FirstRunGuide.steps(input, true).first().complete)
        assertTrue(FirstRunGuide.steps(input.copy(rootNotificationReady = true), true).all { it.complete })
    }
    @Test fun notificationOnlyNeedsNoAccessibility() {
        val input = ready.copy(accessibilityAuthorized = false, accessibilityBound = false, pageEnabled = false,
            notificationsEnabled = true, notificationAuthorized = true, notificationBound = true)
        assertTrue(FirstRunGuide.steps(input, true).all { it.complete })
    }
    @Test fun legacyBankSmsCannotCompleteSelfSetup() {
        val input = ready.copy(mode = null, smsEnabled = true, smsGranted = true, autoConfirm = false, overlayAllowed = false)
        assertFalse(FirstRunGuide.steps(input, true).first().complete)
        assertFalse(FirstRunGuide.configurationComplete(input))
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
    @Test fun manualEntriesCannotCompleteRecognitionStep() {
        assertFalse(FirstRunGuide.hasRecognizedSource("io.github.caozl9021.mymoney.manual.123"))
        assertFalse(FirstRunGuide.hasRecognizedSource(""))
        assertTrue(FirstRunGuide.hasRecognizedSource("com.tencent.mm"))
    }
}
