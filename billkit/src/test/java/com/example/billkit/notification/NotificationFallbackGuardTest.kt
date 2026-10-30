package com.example.billkit.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationFallbackGuardTest {
    private fun observation() = NotificationObservation(
        eventId = "synthetic", packageName = "com.tencent.mm", deviceScope = "test", notificationKey = "key",
        notificationId = 1, notificationTag = null, userScope = "0", postTime = 1000, receivedAt = 1100,
        title = "微信支付", text = "成功付款19.99元", bigText = null, channelId = "synthetic",
        category = null, template = null,
    )

    @Test fun `明确拒绝不能被规则复活`() {
        val o = observation()
        for (reason in listOf("not-successful-expense", "chat-notification", "unsupported-currency")) {
            assertFalse(NotificationEnvelopeGuard.mayTryRule(o, NotificationParseResult.Ignored(reason)))
        }
        for (reason in listOf("truncated-content", "conflicting-amount", "ambiguous-multiple-amounts", "missing-visible-amount", "invalid-amount")) {
            assertFalse(NotificationEnvelopeGuard.mayTryRule(o, NotificationParseResult.NeedsEvidence(reason)))
        }
    }

    @Test fun `规则只接收可补证的未适配输入`() {
        val o = observation()
        for (reason in listOf("unverified-template", "missing-explicit-success", "non-expense-transaction")) {
            assertTrue(NotificationEnvelopeGuard.mayTryRule(o, NotificationParseResult.NeedsEvidence(reason)))
            assertFalse(NotificationEnvelopeGuard.mayTryRule(o.copy(isGroupSummary = true), NotificationParseResult.NeedsEvidence(reason)))
            assertFalse(NotificationEnvelopeGuard.mayTryRule(o.copy(truncated = true), NotificationParseResult.NeedsEvidence(reason)))
            assertFalse(NotificationEnvelopeGuard.mayTryRule(o.copy(isMessagingStyle = true), NotificationParseResult.NeedsEvidence(reason)))
            assertFalse(NotificationEnvelopeGuard.mayTryRule(o.copy(isReconnectBaseline = true), NotificationParseResult.NeedsEvidence(reason)))
        }
    }
}
