package io.github.caozl9021.mymoney.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 待核对闸门的纯策略契约：是否弹过只看投递回执，不看"是否存在同额记录"。 */
class CaptureReviewPolicyTest {

    @Test fun onlyDeliveredOrRecordedRowsCountAsPresented() {
        assertTrue(CaptureReviewPolicy.presented(RecognitionStatus.PENDING, CaptureDeliveryState.ACKNOWLEDGED))
        assertTrue(CaptureReviewPolicy.presented(RecognitionStatus.PENDING, CaptureDeliveryState.CLAIMED))
        assertTrue(CaptureReviewPolicy.presented(RecognitionStatus.RECORDED, null))
        assertTrue(CaptureReviewPolicy.presented(RecognitionStatus.RECORDED, CaptureDeliveryState.SUPPRESSED))
    }

    @Test fun undeliveredRowsNeverSuppressANewPayment() {
        // 快照里最常见的一种：记录存在、但从未真正弹给用户。
        assertFalse(CaptureReviewPolicy.presented(RecognitionStatus.PENDING, null))
        assertFalse(CaptureReviewPolicy.presented(RecognitionStatus.PENDING, CaptureDeliveryState.SUPPRESSED))
        assertFalse(CaptureReviewPolicy.presented(RecognitionStatus.PENDING, CaptureDeliveryState.FAILED))
        assertFalse(CaptureReviewPolicy.presented(RecognitionStatus.PENDING, CaptureDeliveryState.UNKNOWN))
        assertFalse(CaptureReviewPolicy.presented(RecognitionStatus.IGNORED, null))
        assertFalse(CaptureReviewPolicy.suppresses(weakGuess = true, targetPresented = false))
    }

    @Test fun presentedCandidateKeepsTheSecondDeliverySuppressed() {
        assertTrue(CaptureReviewPolicy.suppresses(weakGuess = true, targetPresented = true))
    }

    @Test fun knownSameConflictsAreAlwaysSuppressed() {
        // 同一交易身份 / 同一通知实例内容变化：那确实是同一笔，绝不能弹两次。
        assertTrue(CaptureReviewPolicy.suppresses(weakGuess = false, targetPresented = false))
        assertTrue(CaptureReviewPolicy.suppresses(weakGuess = false, targetPresented = true))
        assertTrue(CaptureReviewPolicy.isWeakGuess(CaptureReviewPolicy.SAME_SLOT_SAME_AMOUNT))
        assertTrue(CaptureReviewPolicy.isWeakGuess(CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT))
        assertFalse(CaptureReviewPolicy.isWeakGuess(CaptureReviewPolicy.FIELD_CONFLICT))
        assertFalse(CaptureReviewPolicy.isWeakGuess(null))
    }

    @Test fun explanationStatesWhetherTheCardWasShown() {
        val suppressed = CaptureReviewPolicy.explanation(CaptureReviewPolicy.SAME_SLOT_SAME_AMOUNT, suppressed = true)
        val delivered = CaptureReviewPolicy.explanation(CaptureReviewPolicy.SAME_SLOT_SAME_AMOUNT, suppressed = false)
        assertTrue(suppressed.contains("不重复弹框"))
        assertTrue(delivered.contains("已按独立记录投递"))
        assertTrue(suppressed.contains("同一通知槽位"))
    }

    @Test fun everyReasonHasReadableCopy() {
        val reasons = listOf(
            CaptureReviewPolicy.SAME_SLOT_SAME_AMOUNT,
            CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT,
            CaptureReviewPolicy.FIELD_CONFLICT,
            CaptureReviewPolicy.SAME_OBSERVATION,
        )
        for (reason in reasons) {
            val text = CaptureReviewPolicy.explanation(reason, suppressed = false)
            assertFalse("未知原因漏了文案：$reason", text.startsWith("存在同额弱候选"))
        }
        assertEquals("存在同额弱候选，但那条从未展示；本条已按独立记录投递，可在识别历史核对是否合并",
            CaptureReviewPolicy.explanation("unknown-reason", suppressed = false))
    }
}
