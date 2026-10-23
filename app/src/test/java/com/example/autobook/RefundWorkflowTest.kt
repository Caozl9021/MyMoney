package com.example.autobook

import com.example.billkit.AmountParser
import com.example.billkit.BillDraft
import com.example.billkit.BillPipeline
import com.example.billkit.BillType
import com.example.billkit.Packages
import com.example.autobook.data.BillEntity
import com.example.autobook.data.CategoryGroup
import com.example.autobook.data.CategorySuggester
import com.example.autobook.ui.ledgerTotals
import com.example.autobook.ui.design.formatAmount
import org.junit.Assert.*
import org.junit.Test

class RefundWorkflowTest {
    private fun page(status: String, amount: String = "+19.99") = listOf(
        "账单详情", "当前状态", status, amount, "交易时间", "2026-10-22 12:00:00", "交易单号", "123456789",
    )
    private fun process(items: List<String>) = BillPipeline().process(Samples.snapshot(Packages.ALIPAY, items))

    @Test fun `成功退款优先于正号收入且使用退款金额而非原付款`() {
        val result = process(page("退款成功") + listOf("实付款100元", "退款金额19.99元")) as BillPipeline.Outcome.Draft
        assertEquals(BillType.REFUND, result.draft.type)
        assertEquals(1999L, result.draft.priceCents)
    }

    @Test fun `明确成功退款也优先于原负号支出`() {
        val result = process(page("退款成功", "-19.99")) as BillPipeline.Outcome.Draft
        assertEquals(BillType.REFUND, result.draft.type)
        assertEquals(1999L, result.draft.priceCents)
    }

    @Test fun `退款到账时间字段本身不能冒充成功退款状态`() {
        assertFalse(AmountParser.isCompletedRefund(listOf("退款到账时间", "2026-11-01 09:00:00")))
        assertEquals(AmountParser.Status.UNKNOWN,
            AmountParser.status(listOf("退款到账时间", "2026-11-01 09:00:00")))
    }

    @Test fun `退款时间同行或邻接均优先于原支付时间`() {
        listOf(listOf("退款时间", "2026-11-01 09:00:00"),
            listOf("退款到账时间：2026-11-01 09:00:00")).forEach { refundTime ->
            val result = process(page("退款成功") + refundTime) as BillPipeline.Outcome.Draft
            assertEquals(AmountParser.findTime(listOf("2026-11-01 09:00:00")), result.draft.timeMillis)
        }
    }

    @Test fun `退款金额标签分行仍优先于原付款`() {
        val result = process(page("已退款") + listOf("实付款100元", "退款金额", "19.99")) as BillPipeline.Outcome.Draft
        assertEquals(1999L, result.draft.priceCents)
    }

    @Test fun `订单关闭但明确退款完成仍识别退款`() {
        val result = process(page("交易已关闭 已全额退款")) as BillPipeline.Outcome.Draft
        assertEquals(BillType.REFUND, result.draft.type)
    }

    @Test fun `退款申请处理中失败即使保留原支付成功也不能入账`() {
        listOf("申请退款", "退款申请已提交", "退款处理中", "退款失败", "退款审核中", "取消退款", "退款已取消").forEach { status ->
            assertTrue(status, process(page("支付成功 $status")) is BillPipeline.Outcome.Skipped)
            assertNotEquals(AmountParser.Status.SUCCESS, AmountParser.status(listOf("当前状态", "支付成功 $status")))
        }
    }

    @Test fun `转账退回及普通付款里的申请退款入口不能误识别退款`() {
        val returned = process(page("转账成功 已退回", "19.99")) as BillPipeline.Outcome.Draft
        assertEquals(BillType.TRANSFER, returned.draft.type)
        val ordinary = process(page("支付成功", "-19.99") + listOf("支持退款", "申请退款")) as BillPipeline.Outcome.Draft
        assertEquals(BillType.EXPENSE, ordinary.draft.type)
    }

    private fun draft(type: BillType) = BillDraft(1999, type, 0, "餐厅退款", null, "123456", "pkg", "test")

    @Test fun `原账与同金额退款指纹不同`() {
        assertNotEquals(draft(BillType.EXPENSE).fingerprint, draft(BillType.REFUND).fingerprint)
    }

    @Test fun `退款使用支出分类不增加收入分类`() {
        val suggestion = CategorySuggester.suggest(draft(BillType.REFUND),
            expenseGroups = listOf(CategoryGroup("餐饮", listOf("三餐"))),
            incomeGroups = listOf(CategoryGroup("退款")), accounts = emptyList())
        assertEquals("餐饮", suggestion.category)
    }

    private fun entity(type: String, cents: Long) = BillEntity(priceCents = cents, type = type,
        timeMillis = 0, merchant = null, method = null, orderTail = null, sourcePkg = "pkg",
        strategy = "test", fingerprint = type, createdAt = 0)

    @Test fun `首页与统计共用口径保留原支出退款独立结余抵减`() {
        val totals = ledgerTotals(listOf(entity("EXPENSE", 10000), entity("INCOME", 2000),
            entity("REFUND", 1999), entity("TRANSFER", 99999)))
        assertEquals(10000L, totals.expense)
        assertEquals(2000L, totals.income)
        assertEquals(1999L, totals.refund)
        assertEquals(-6001L, totals.balance)
        assertTrue(formatAmount(1999, true, "REFUND").startsWith("退款"))
        assertFalse(formatAmount(1999, true, "REFUND").startsWith("+"))
    }
}
