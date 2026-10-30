package io.github.caozl9021.mymoney.notification

import com.example.billkit.BillType
import com.example.billkit.notification.*
import org.junit.Assert.*
import org.junit.Test

/** Fixtures are synthetic contract examples, NOT captures from WeChat or Alipay. */
class NotificationParserTest {
    private fun observation(
        title: String = "支付成功", text: String = "向测试商户付款 ¥19.99", postTime: Long = 1000L,
    ) = NotificationObservation(
        eventId = "event-1", packageName = "com.eg.android.AlipayGphone", deviceScope = "test-install",
        notificationKey = "test-key", notificationId = 7, notificationTag = "payment", userScope = "0",
        postTime = postTime, receivedAt = 1100L, title = title, text = text, bigText = null,
        channelId = "synthetic-reviewed-channel", category = "status", template = "synthetic-reviewed-style",
    )

    private fun fixtureTemplate(amount: String = "19.99", transactionTime: Long? = null) = object : VerifiedExpenseNotificationTemplate {
        override val id = "synthetic-consumption-contract"
        override val evidenceReference = "test-fixture-only-not-real-payment"
        override val packageName = "com.eg.android.AlipayGphone"
        override val channelId = "synthetic-reviewed-channel"
        override val platformTemplate = "synthetic-reviewed-style"
        override fun matches(observation: NotificationObservation) = observation.title == "支付成功"
        override fun extract(observation: NotificationObservation) = NotificationExpenseFields(
            amount = amount, merchant = "测试商户", orderId = "synthetic-independent-order",
            transactionTimeMillis = transactionTime,
        )
    }

    @Test fun productionHasNoUnverifiedAutomaticTemplate() {
        val result = NotificationParser().parse(observation())
        assertEquals(NotificationParseResult.NeedsEvidence("unverified-template"), result)
    }

    @Test fun reviewedFixtureConvertsCentsExactlyAndDoesNotInventPaymentTime() {
        val result = NotificationParser(listOf(fixtureTemplate())).parse(observation()) as NotificationParseResult.Candidate
        assertEquals(1999L, result.draft.priceCents)
        assertEquals(BillType.EXPENSE, result.draft.type)
        assertEquals(1100L, result.draft.timeMillis)
        assertFalse(result.draft.hasExplicitTime)
        assertEquals(com.example.billkit.TransactionIdentity.key(result.draft.sourcePkg, com.example.billkit.BillType.EXPENSE, "synthetic-independent-order"), result.draft.transactionKey)
        assertEquals("测试商户", result.draft.merchant)
        assertEquals(observation().observationKey, result.draft.captureSessionId)
    }

    @Test fun explicitPaymentTimeIsOnlyTakenFromReviewedTemplate() {
        val result = NotificationParser(listOf(fixtureTemplate(transactionTime = 950L))).parse(observation()) as NotificationParseResult.Candidate
        assertEquals(950L, result.draft.timeMillis)
        assertTrue(result.draft.hasExplicitTime)
    }

    @Test fun ordinaryChatCannotBePromotedByPaymentWords() {
        val parser = NotificationParser(listOf(fixtureTemplate()))
        assertEquals(NotificationParseResult.Ignored("chat-notification"), parser.parse(observation().copy(isMessagingStyle = true)))
        assertEquals(NotificationParseResult.Ignored("chat-notification"), parser.parse(observation().copy(category = "msg")))
        assertTrue(parser.parse(observation().copy(packageName = "com.tencent.mm", title = "微信支付")) is NotificationParseResult.NeedsEvidence)
    }

    @Test fun summaryReconnectAndOngoingAreNotTransactions() {
        val parser = NotificationParser(listOf(fixtureTemplate()))
        assertEquals(NotificationParseResult.Ignored("group-summary"), parser.parse(observation().copy(isGroupSummary = true)))
        assertEquals(NotificationParseResult.Ignored("reconnect-baseline"), parser.parse(observation().copy(isReconnectBaseline = true)))
        assertEquals(NotificationParseResult.Ignored("ongoing-notification"), parser.parse(observation().copy(isOngoing = true)))
    }

    @Test fun failuresCancellationsPendingRefundAndMarketingDoNotBecomeExpense() {
        val parser = NotificationParser(listOf(fixtureTemplate()))
        for (suffix in listOf("支付失败", "付款已取消", "待支付", "未付款", "处理中", "优惠红包", "验证码")) {
            assertEquals(suffix, NotificationParseResult.Ignored("not-successful-expense"), parser.parse(observation(text = "付款 ¥19.99；$suffix")))
        }
        for (suffix in listOf("退款成功", "收到收款")) {
            assertEquals(suffix, NotificationParseResult.NeedsEvidence("non-expense-transaction"),
                parser.parse(observation(text = "金额 ¥19.99；$suffix")))
        }
    }

    @Test fun multipleAmountsOrConflictingValuesNeedReview() {
        val parser = NotificationParser(listOf(fixtureTemplate()))
        assertEquals(NotificationParseResult.NeedsEvidence("ambiguous-multiple-amounts"), parser.parse(observation(text = "原价 ¥20.00 实付 ¥19.99")))
        assertEquals(NotificationParseResult.NeedsEvidence("ambiguous-multiple-amounts"), parser.parse(observation(title = "支付成功 ¥20.00")))
        assertEquals(NotificationParseResult.NeedsEvidence("conflicting-amount"), NotificationParser(listOf(fixtureTemplate("20.00"))).parse(observation()))
    }

    @Test fun incompleteAndTruncatedInputNeverClaimsReliablePayment() {
        val parser = NotificationParser(listOf(fixtureTemplate()))
        assertEquals(NotificationParseResult.NeedsEvidence("truncated-content"), parser.parse(observation().copy(truncated = true)))
        assertEquals(NotificationParseResult.NeedsEvidence("empty-or-hidden-content"), parser.parse(observation().copy(title = null, text = null)))
        assertEquals(NotificationParseResult.NeedsEvidence("missing-observation-time"), parser.parse(observation(postTime = 0)))
        assertEquals(NotificationParseResult.NeedsEvidence("missing-explicit-success"), parser.parse(observation(title = "服务提醒")))
    }

    @Test fun metadataMustMatchReviewedChannelAndPlatformStyle() {
        val parser = NotificationParser(listOf(fixtureTemplate()))
        assertEquals(NotificationParseResult.NeedsEvidence("unverified-template"), parser.parse(observation().copy(channelId = "chat-channel")))
        assertEquals(NotificationParseResult.NeedsEvidence("unverified-template"), parser.parse(observation().copy(template = "arbitrary-style")))
        assertEquals(NotificationParseResult.Ignored("unsupported-package"), parser.parse(observation().copy(packageName = "com.example.other")))
        assertEquals(NotificationParseResult.NeedsEvidence("ambiguous-template"), NotificationParser(listOf(fixtureTemplate(), fixtureTemplate())).parse(observation()))
    }

    @Test fun invalidOrOverflowAmountsAreRejected() {
        for (amount in listOf("0", "-1.00", "19.999", "1e3", "92233720368547758.08")) {
            assertEquals(amount, NotificationParseResult.NeedsEvidence("invalid-amount"),
                NotificationParser(listOf(fixtureTemplate(amount))).parse(observation(text = "向测试商户付款")))
        }
    }

    @Test fun reviewedTemplateCannotInventAmountAbsentFromVisibleAllowedFields() {
        assertEquals(NotificationParseResult.NeedsEvidence("missing-visible-amount"),
            NotificationParser(listOf(fixtureTemplate())).parse(observation(text = "向测试商户付款")))
    }

    @Test fun observingUpdatesAndReusedKeysDoesNotCreateFakeTransactionIdentity() {
        val original = observation()
        val updated = original.copy(text = "更完整的正文", eventId = "event-2")
        assertEquals(original.observationKey, updated.observationKey)
        assertNotEquals(original.contentHash, updated.contentHash)
        assertNotEquals(original.observationKey, original.copy(postTime = 2000).observationKey)
        assertNotEquals(original.observationKey, original.copy(deviceScope = "other-install").observationKey)
        assertNotEquals(original.observationKey, original.copy(userScope = "10").observationKey)
        assertNotEquals(original.observationKey, original.copy(packageName = "com.tencent.mm").observationKey)
        assertFalse(original.observationKey.contains("19.99"))
        assertFalse(original.contentHash.contains("测试商户"))
    }

    @Test fun longerNotificationBodyOverridesItsShortPreviewWithoutFalseAmbiguity() {
        val input = observation().copy(bigText = "向测试商户付款 ¥19.99")
        assertTrue(NotificationParser(listOf(fixtureTemplate())).parse(input) is NotificationParseResult.Candidate)
    }
}
