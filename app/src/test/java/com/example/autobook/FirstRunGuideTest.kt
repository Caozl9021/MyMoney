package com.example.autobook

import com.example.autobook.data.CaptureMode
import com.example.autobook.ui.FirstRunGuide
import org.junit.Assert.*
import org.junit.Test

class FirstRunGuideTest {
    @Test fun noModeCannotCompleteEvenIfServicesLookReady() {
        val steps = FirstRunGuide.steps(null, true, true, true, true, true)
        assertFalse(steps.first().complete)
        assertFalse(steps.all { it.complete })
    }

    @Test fun rootRequiresActualRunningAndDoesNotRequireAccessibility() {
        assertTrue(FirstRunGuide.steps(CaptureMode.ROOT, true, true, false, true, true).all { it.complete })
        assertFalse(FirstRunGuide.steps(CaptureMode.ROOT, true, false, true, true, true).first().complete)
    }

    @Test fun accessibilityRequiresBoundServiceAndDoesNotRequireRoot() {
        assertTrue(FirstRunGuide.steps(CaptureMode.ACCESSIBILITY, true, false, true, true, true).all { it.complete })
        assertFalse(FirstRunGuide.steps(CaptureMode.ACCESSIBILITY, true, true, false, true, true).first().complete)
    }

    @Test fun enabledOrPermissionsAloneNeverPretendRecognition() {
        val steps = FirstRunGuide.steps(CaptureMode.ROOT, true, true, false, true, false)
        assertFalse(steps.last().complete)
        assertFalse(steps.all { it.complete })
        assertFalse(FirstRunGuide.steps(CaptureMode.ROOT, false, true, true, true, true).first().complete)
        assertFalse(FirstRunGuide.steps(CaptureMode.ROOT, true, true, true, false, true)[1].complete)
    }

    @Test fun manualEntriesCannotCompleteRecognitionStep() {
        assertFalse(FirstRunGuide.hasRecognizedSource("com.example.autobook.manual.123"))
        assertFalse(FirstRunGuide.hasRecognizedSource(""))
        assertTrue(FirstRunGuide.hasRecognizedSource("com.tencent.mm"))
    }
}
