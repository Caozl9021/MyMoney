package io.github.caozl9021.mymoney.notification

import com.example.billkit.notification.*
import org.junit.Assert.*
import org.junit.Test

/** Exported metadata and screenshot content are separate evidence; raw channel ID is unknown. */
class AlipayExpenseNotificationTemplateTest {
    private val template = AlipayExpenseNotificationTemplate()
    private fun input(body: String = "你有一笔0.01元的支出，领2元小荷包支付红包。") = NotificationObservation(
        "synthetic-event", AlipayExpenseNotificationTemplate.ALIPAY_PACKAGE, "test-install", "slot", 1, null,
        "0", 1000, 1100, "交易提醒", body, null, null, null, null)

    @Test fun realExportMetadataSignatureIsSupportedButRawChannelIsNotInvented() {
        val hash = AlipayExpenseNotificationTemplate.OBSERVED_CHANNEL_SHA256
        assertTrue(template.matchesMetadataSignature(input().packageName, hash, null, null))
        assertFalse(template.matchesMetadata(input()))
        assertFalse(template.matchesMetadata(input().copy(channelId = hash))) // hash is not the raw ID
        assertFalse(template.matchesMetadataSignature(input().packageName, "other", null, null))
        assertFalse(template.matchesMetadataSignature(input().packageName, hash, "status", null))
        assertFalse(template.matchesMetadataSignature(input().packageName, hash, null, "style"))
        assertFalse(template.matchesMetadataSignature("com.tencent.mm", hash, null, null))
    }

    @Test fun screenshotTranscriptionKeepsExpenseSeparateFromRewardAndMissingFields() {
        for (tail in listOf("，领2元小荷包支付红包。", "，最高18元红包", "")) {
            val fields = template.extract(input("你有一笔0.01元的支出$tail"))!!
            assertEquals("0.01", fields.amount)
            assertNull(fields.merchant); assertNull(fields.method); assertNull(fields.orderId)
            assertNull(fields.transactionTimeMillis)
        }
    }

    @Test fun negativesAndAmbiguousTransactionsAreRejected() {
        for (tail in listOf("，支付失败", "，已取消", "，待付款", "，退款成功", "，收入2元", "，另有一笔2元的支出", "，付款2元")) {
            assertNull(tail, template.extract(input("你有一笔0.01元的支出$tail")))
        }
        for (amount in listOf("0", "-1", "1.001", "1e3", "92233720368547758.08", "01.00")) {
            assertNull(amount, template.extract(input("你有一笔${amount}元的支出")))
        }
        assertNull(template.extract(input().copy(title = "优惠提醒")))
        assertNull(template.extract(input("你有一笔0.01元的支出失败")))
    }

    /** Synthetic channel adapter exercises parser routing; does not claim real callback replay. */
    private fun syntheticAdapter(structured: Boolean = true) = object : VerifiedExpenseNotificationTemplate {
        override val id = "synthetic-structured-routing"
        override val evidenceReference = "synthetic-contract-only"
        override val packageName = template.packageName
        override val channelId = "synthetic-channel"
        override val platformTemplate = "synthetic-style"
        override val structuredExpense = structured
        override fun matches(observation: NotificationObservation) = template.matches(observation)
        override fun extract(observation: NotificationObservation) = template.extract(observation)
    }
    private fun syntheticInput() = input().copy(channelId = "synthetic-channel", template = "synthetic-style")

    @Test fun structuredRoutingAllowsRewardWithoutChangingGenericRules() {
        val result = NotificationParser(listOf(syntheticAdapter())).parse(syntheticInput()) as NotificationParseResult.Candidate
        assertEquals(1L, result.draft.priceCents)
        assertNull(result.draft.method); assertNull(result.draft.merchant); assertNull(result.draft.transactionKey)
        assertFalse(result.draft.hasExplicitTime)
        assertTrue(NotificationParser(listOf(syntheticAdapter(false))).parse(syntheticInput()) is NotificationParseResult.Ignored)
        assertFalse(NotificationParser().parse(syntheticInput()) is NotificationParseResult.Candidate)
    }

    @Test fun envelopeGuardsRemainBeforeStructuredRouting() {
        val parser = NotificationParser(listOf(syntheticAdapter()))
        for (observation in listOf(syntheticInput().copy(isGroupSummary = true), syntheticInput().copy(isReconnectBaseline = true),
            syntheticInput().copy(isOngoing = true), syntheticInput().copy(isMessagingStyle = true), syntheticInput().copy(truncated = true))) {
            assertFalse(parser.parse(observation) is NotificationParseResult.Candidate)
        }
    }

    @Test fun reusedSlotIsOnlyAStableReviewHintAcrossPostTimeUpdates() {
        assertEquals(input().reviewSlotKey, input().copy(postTime = 2000).reviewSlotKey)
        assertNotEquals(input().observationKey, input().copy(postTime = 2000).observationKey)
        assertNotEquals(input().reviewSlotKey, input().copy(notificationKey = "other").reviewSlotKey)
        assertNotEquals(input().reviewSlotKey, input().copy(userScope = "10").reviewSlotKey)
    }
}
