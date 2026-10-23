package com.example.autobook

import com.example.billkit.AmountParser
import com.example.billkit.AmountParser.Status
import com.example.billkit.BillPipeline
import com.example.billkit.Packages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F17: nouns such as "处理中心" are service entries, not an in-progress status. */
class StatusBoundaryTest {
    private fun process(items: List<String>) = BillPipeline().process(Samples.snapshot(Packages.WECHAT, items))

    @Test fun `service center entries do not turn a successful bill into pending`() {
        listOf("售后处理中心", "退款中心", "支付中心", "客服处理中心入口").forEach { entry ->
            val page = Samples.wechatBill + entry
            assertEquals(entry, Status.SUCCESS, AmountParser.status(page))
            assertTrue(entry, process(page) is BillPipeline.Outcome.Draft)
        }
    }

    @Test fun `real in-progress states are still blocked`() {
        listOf("处理中", "退款处理中", "支付中", "退款中", "处理中，请稍候", "处理中心 处理中").forEach { state ->
            val page = Samples.wechatBill + state
            assertNotEquals(state, Status.SUCCESS, AmountParser.status(page))
            assertTrue(state, process(page) is BillPipeline.Outcome.Skipped)
        }
        assertEquals(Status.PENDING, AmountParser.status(listOf("当前状态", "支付成功 退款处理中")))
    }

    @Test fun `failure and cancellation precedence is unchanged next to a center entry`() {
        assertEquals(Status.FAILED, AmountParser.status(listOf("支付失败", "售后处理中心")))
        assertEquals(Status.CANCELLED, AmountParser.status(listOf("订单已取消", "退款中心")))
    }
}
