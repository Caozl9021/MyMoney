package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test

class CaptureShortWindowPolicyTest {
    private val at = 1_793_577_599_000L
    private val draft = BillDraft(400, BillType.EXPENSE, at, "早餐店", null, null,
        "com.tencent.mm", "test", hasExplicitTime = false)
    private val row = RecognitionEntity.fromDraft(draft, "capture:test", at)
    private fun matches(incoming: BillDraft = draft, target: RecognitionEntity = row,
                        offset: Long = 7000, source: String = "HOOK", sources: List<String> = listOf("PAGE"),
                        aliases: List<String> = emptyList()) =
        CaptureShortWindowPolicy.matches(target, incoming, source, sources, at + offset, aliases)

    @Test fun rollingBoundaryIncludesSixtySecondsAndCrossesMinuteBoundary() {
        for (offset in listOf(0L, 2000L, 59_999L, 60_000L, -60_000L)) assertTrue(matches(offset = offset))
        for (offset in listOf(60_001L, -60_001L, 100_000L)) assertFalse(matches(offset = offset))
        assertFalse(CaptureShortWindowPolicy.withinWindow(0, 1))
        assertFalse(CaptureShortWindowPolicy.withinWindow(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun amountAppAndDirectionMustAgree() {
        assertFalse(matches(incoming = draft.copy(priceCents = 401)))
        assertFalse(matches(incoming = draft.copy(sourcePkg = "com.eg.android.AlipayGphone")))
        assertFalse(matches(incoming = draft.copy(type = BillType.INCOME)))
        assertFalse(matches(incoming = draft.copy(type = BillType.REFUND)))
    }

    @Test fun merchantMethodAndNoteDifferencesDoNotBlockMerge() {
        assertTrue(matches(incoming = draft.copy(merchant = "早餐店（*伟）", method = "零钱", note = "本次早餐")))
    }

    @Test fun distinctOrderAndAliasesOnRecordedRowsPreventMerge() {
        assertFalse(matches(incoming = draft.copy(transactionKey = "order-b"), target = row.copy(transactionKey = "order-a")))
        val alias = CaptureMergePolicy.strongAlias(draft.copy(transactionKey = "order-a"))!!
        assertFalse(matches(incoming = draft.copy(transactionKey = "order-b"), aliases = listOf(alias)))
        assertTrue(matches(incoming = draft.copy(transactionKey = "order-a"), aliases = listOf(alias)))
        assertTrue(matches(incoming = draft.copy(transactionKey = "order-a")))
    }

    @Test fun bothExplicitTransactionTimesMustBeWithinWindow() {
        val explicit = row.copy(hasExplicitTime = true)
        assertTrue(matches(target = explicit, incoming = draft.copy(hasExplicitTime = true, timeMillis = at + 60_000)))
        assertFalse(matches(target = explicit, incoming = draft.copy(hasExplicitTime = true, timeMillis = at + 60_001)))
        assertTrue(matches(target = explicit, incoming = draft.copy(hasExplicitTime = false, timeMillis = at + 500_000)))
    }

    @Test fun sameLogicalChannelNewEventsAreIndependent() {
        assertFalse(matches(source = "PAGE"))
        assertFalse(matches(sources = listOf("PAGE", "HOOK", "DELIVERY")))
        assertFalse(matches(source = "ROOT", sources = listOf("NOTIFICATION")))
        assertFalse(matches(source = "NOTIFICATION", sources = listOf("ROOT")))
        assertFalse(matches(source = "DELIVERY"))
        assertFalse(matches(sources = listOf("DELIVERY")))
        assertTrue(matches(source = "ROOT"))
    }

    @Test fun terminalRowsAreMatchableButOldReviewCandidatesAreNot() {
        for (status in listOf(RecognitionStatus.PENDING, RecognitionStatus.RECORDED, RecognitionStatus.IGNORED))
            assertTrue(matches(target = row.copy(status = status)))
        assertFalse(matches(target = row.copy(reviewOf = "old-parent")))
    }

    @Test fun editedAmountAndTypePreventWeakMatchingButOtherEditsDoNot() {
        assertFalse(matches(target = row.copy(protectedFields = CaptureFields.AMOUNT)))
        assertFalse(matches(target = row.copy(protectedFields = CaptureFields.TYPE)))
        assertTrue(matches(target = row.copy(protectedFields = CaptureFields.NOTE or CaptureFields.CATEGORY)))
    }

    @Test fun onlyFailedSuppressedOrAbsentReceiptsCanRetry() {
        for (state in listOf(null, CaptureDeliveryState.FAILED, CaptureDeliveryState.SUPPRESSED))
            assertTrue(CaptureShortWindowPolicy.retryableDelivery(state))
        for (state in listOf(CaptureDeliveryState.CLAIMED, CaptureDeliveryState.ACKNOWLEDGED,
            CaptureDeliveryState.UNKNOWN, CaptureDeliveryState.RESTORED))
            assertFalse(CaptureShortWindowPolicy.retryableDelivery(state))
    }
}
