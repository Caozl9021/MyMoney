package com.example.autobook

import com.example.billkit.BillPipeline
import com.example.billkit.BillType
import com.example.billkit.PageClassifier
import com.example.billkit.PageKind
import com.example.billkit.Packages
import com.example.billkit.UiSnapshot
import org.junit.Assert.*
import org.junit.Test

/**
 * Screenshot-derived fixture: manually transcribed visible fields from the user's 2026-10-23
 * screenshot, with recipient redacted. This is NOT a real accessibility node dump, and does not
 * establish that the installed app received these fields or events on the device.
 */
class WechatMinimalSuccessTest {
    private val screenshotTranscription = listOf("支付成功", "测试商户（*某）", "¥0.01", "完成")
    private val classifier = PageClassifier()
    private val pipeline = BillPipeline()
    private val now = 1_792_713_600_000L

    private fun snapshot(items: List<String>, pkg: String = Packages.WECHAT) =
        UiSnapshot(pkg, null, items, source = UiSnapshot.Source.ACCESSIBILITY)

    private fun draft(items: List<String>) =
        (pipeline.process(snapshot(items), now) as BillPipeline.Outcome.Draft).draft

    private fun assertSkipped(items: List<String>, pkg: String = Packages.WECHAT) {
        assertNull(classifier.classify(snapshot(items, pkg)))
        assertTrue(pipeline.process(snapshot(items, pkg), now) is BillPipeline.Outcome.Skipped)
    }

    @Test fun screenshotTranscriptionProducesOneCentExpenseWithoutInventedMethodOrOrder() {
        assertEquals(PageKind.WECHAT_PAY, classifier.classify(snapshot(screenshotTranscription))?.kind)
        val bill = draft(screenshotTranscription)
        assertEquals(1L, bill.priceCents)
        assertEquals(BillType.EXPENSE, bill.type)
        assertEquals("测试商户（*某）", bill.merchant)
        assertNull(bill.method)
        assertNull(bill.orderTail)
        assertEquals(now, bill.timeMillis)
    }

    @Test fun splitCurrencyNodeAndAlternativePaymentTitleAreSupported() {
        val rows = listOf("付款成功", "测试商户（*某）", "￥", "0.01", "完成")
        assertEquals(PageKind.WECHAT_PAY, classifier.classify(snapshot(rows))?.kind)
        assertEquals(1L, draft(rows).priceCents)
        assertEquals(BillType.EXPENSE, draft(rows).type)
        assertNull(draft(rows).method)
    }

    @Test fun nonAdjacentRepeatedSuccessTitleDoesNotInvalidatePayment() {
        // Container contentDescription and a child title may both expose the same success text.
        val rows = listOf("支付成功", "测试商户（*某）", "支付成功", "¥0.01", "完成")
        assertEquals(PageKind.WECHAT_PAY, classifier.classify(snapshot(rows))?.kind)
        assertEquals(1L, draft(rows).priceCents)
        assertEquals(BillType.EXPENSE, draft(rows).type)
        assertNull(draft(rows).method)
    }

    @Test fun merchantCanRemainAbsent() {
        val bill = draft(listOf("支付成功", "¥0.01", "完成"))
        assertNull(bill.merchant)
        assertNull(bill.method)
    }

    @Test fun missingCompletionOrStandaloneSuccessCannotUseMinimalRule() {
        assertSkipped(screenshotTranscription.dropLast(1))
        assertSkipped(screenshotTranscription.map { if (it == "支付成功") "提示：支付成功" else it })
        assertSkipped(screenshotTranscription.map { if (it == "完成") "未完成" else it })
    }

    @Test fun failedCancelledAndPendingStatesNeverBecomeExpenses() {
        listOf("支付失败", "交易已关闭", "正在付款", "请输入支付密码").forEach { status ->
            assertSkipped(screenshotTranscription + status)
        }
        assertSkipped(listOf("支付失败", "测试商户", "¥0.01", "完成"))
        assertSkipped(listOf("向商家付钱", "测试商户", "¥0.01", "确认付款"))
    }

    @Test fun listsAndOtherPackagesCannotUseMinimalRule() {
        assertSkipped(screenshotTranscription + "全部账单")
        assertSkipped(screenshotTranscription + "筛选")
        assertSkipped(screenshotTranscription, "com.example.unrelated")
        assertSkipped(screenshotTranscription, "com.tencent.mm.clone")
    }

    @Test fun transferAndIncomeRetainTheirExistingDirections() {
        val transfer = screenshotTranscription + "转账成功"
        assertEquals(PageKind.WECHAT_TRANSFER, classifier.classify(snapshot(transfer))?.kind)
        assertEquals(BillType.TRANSFER, draft(transfer).type)
        val income = screenshotTranscription + "你已收款"
        assertEquals(PageKind.WECHAT_RECEIVE, classifier.classify(snapshot(income))?.kind)
        assertEquals(BillType.INCOME, draft(income).type)
        assertSkipped(screenshotTranscription + "收入")
        assertSkipped(screenshotTranscription + "转账")
    }

    @Test fun multiplePricesAndAdvertisementAmountsAreRejected() {
        assertSkipped(screenshotTranscription + listOf("推荐商品", "¥9.99"))
        assertSkipped(screenshotTranscription + listOf("优惠券", "2.00元"))
        assertSkipped(screenshotTranscription + "金额0.02")
        assertSkipped(screenshotTranscription + "¥0.01")
        assertSkipped(screenshotTranscription + listOf("￥", "0.02"))
    }

    @Test fun missingCurrencyMalformedOrSignedAmountsAreRejected() {
        listOf("0.01", "¥0.00", "¥-0.01", "+¥0.01", "¥0.0.1", "¥0.001", "¥01.00", "¥1,00.00").forEach { amount ->
            assertSkipped(screenshotTranscription.map { if (it == "¥0.01") amount else it })
        }
        assertSkipped(listOf("支付成功", "测试商户", "¥", "0", ".", "01", "完成"))
    }
}
