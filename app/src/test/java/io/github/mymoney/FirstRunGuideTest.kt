package io.github.mymoney

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.ui.*
import org.junit.Assert.*
import org.junit.Test

class FirstRunGuideTest {
    private val ready = CaptureHealthInput(CaptureMode.ACCESSIBILITY, true, true, true, true, true,
        notificationPlan = NotificationPath.LISTENER)

    @Test fun rootMasterAloneCannotCompleteSetup() {
        // 只靠 root 读通知：页面辅助关着，Root 读取没就绪时首启那一步不能算完成。
        val input = ready.copy(mode = CaptureMode.ROOT, notificationsEnabled = true,
            pageEnabled = false, accessibilityAuthorized = false, accessibilityBound = false,
            notificationPlan = NotificationPath.DUMPSYS)
        assertFalse(FirstRunGuide.steps(input, true).first().complete)
        assertTrue(FirstRunGuide.steps(input.copy(rootNotificationReady = true), true).all { it.complete })
    }
    @Test fun notificationOnlyNeedsNoAccessibility() {
        val input = ready.copy(accessibilityAuthorized = false, accessibilityBound = false, pageEnabled = false,
            notificationsEnabled = true, notificationAuthorized = true, notificationBound = true)
        assertTrue(FirstRunGuide.steps(input, true).all { it.complete })
    }
    @Test fun legacyBankSmsCannotCompleteSelfSetup() {
        val input = ready.copy(mode = null, notificationsEnabled = false, pageEnabled = false,
            smsEnabled = true, smsGranted = true, autoConfirm = false, overlayAllowed = false)
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
        assertFalse(FirstRunGuide.hasRecognizedSource("io.github.mymoney.manual.123"))
        assertFalse(FirstRunGuide.hasRecognizedSource(""))
        assertTrue(FirstRunGuide.hasRecognizedSource("com.tencent.mm"))
    }
}
