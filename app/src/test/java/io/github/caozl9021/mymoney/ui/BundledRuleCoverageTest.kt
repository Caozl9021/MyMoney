package io.github.caozl9021.mymoney.ui

import io.github.caozl9021.mymoney.data.CaptureMode
import org.junit.Assert.*
import org.junit.Test

class BundledRuleCoverageTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private fun input() = CaptureHealthInput(
        mode = CaptureMode.ACCESSIBILITY, enabled = true,
        accessibilityAuthorized = false, accessibilityBound = false,
        overlayAllowed = true, autoConfirm = true, pageEnabled = false,
        notificationsEnabled = true, notificationAuthorized = true, notificationBound = true,
        ruleCapabilities = setOf("$wechat|NOTICE"), selectedPackages = setOf(wechat),
    )

    @Test fun unsupportedApplicationDoesNotClaimNotificationRecognition() {
        val pkg = "com.example.unsupported"
        val state = appChannelState(input(), pkg, setOf(pkg))
        assertFalse(state.notification.active)
        assertEquals(ChannelReason.NO_SUPPORTED_RULES, state.notification.reason)
    }

    @Test fun verifiedAlipayTemplateRemainsAvailableWhenRuleBundleFails() {
        val value = input().copy(ruleCapabilities = emptySet(), ruleEngineReady = false, selectedPackages = setOf(alipay))
        assertTrue(value.notificationReady)
        assertTrue(appChannelState(value, alipay, setOf(alipay)).notification.active)
    }

    @Test fun rulesForUnselectedApplicationDoNotMakeSourceReady() {
        val value = input().copy(selectedPackages = setOf("com.example.unsupported"))
        assertFalse(value.notificationReady)
        assertEquals(CaptureHealthKind.RULES_UNAVAILABLE, captureHealth(value).kind)
    }

    @Test fun loadedHookNeedsRulesForItsOwnApplication() {
        val value = input().copy(mode = CaptureMode.ROOT, notificationsEnabled = false,
            hookEnabled = true, hookReady = true, hookPackages = setOf(alipay),
            ruleCapabilities = setOf("$wechat|DATA"))
        assertFalse(value.lspReady)
        assertEquals(ChannelReason.NO_SUPPORTED_RULES, appChannelState(value, alipay, setOf(alipay)).hook?.reason)
        assertTrue(value.copy(hookPackages = setOf(wechat)).lspReady)
    }

    @Test fun pageRecognitionSurvivesUnavailableRuleBundle() {
        val value = input().copy(notificationsEnabled = false, pageEnabled = true,
            accessibilityAuthorized = true, accessibilityBound = true,
            ruleCapabilities = emptySet(), ruleEngineReady = false)
        assertTrue(value.recognitionReady)
        assertEquals(CaptureHealthKind.RUNNING, captureHealth(value).kind)
    }
}
