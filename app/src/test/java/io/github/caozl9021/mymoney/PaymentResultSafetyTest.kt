package io.github.caozl9021.mymoney

import com.example.billkit.*
import org.junit.Assert.*
import org.junit.Test

/** Independent negative cases from screenshot structure; not captured accessibility nodes. */
class PaymentResultSafetyTest {
    private fun process(rows: List<String>) = BillPipeline().process(
        UiSnapshot(Packages.ALIPAY, null, rows), 1_792_713_600_000L)
    private val tail = listOf("付款方式", "银行卡", "完成")
    private fun reject(rows: List<String>) {
        val result = process(rows)
        assertTrue("Unexpected draft: $rows => $result", result is BillPipeline.Outcome.Skipped)
    }
    private fun amount(rows: List<String>, expected: Long) {
        val result = process(rows)
        assertTrue("Expected expense: $rows => $result", result is BillPipeline.Outcome.Draft)
        val bill = (result as BillPipeline.Outcome.Draft).draft
        assertEquals(expected, bill.priceCents)
        assertEquals(BillType.EXPENSE, bill.type)
    }
    @Test fun absentMainMustNotFallBackToMerchantPriceOrAdvertisement() {
        reject(listOf("支付成功", "示例餐厅", "¥17.00", "碰友日立减-¥0.62") + tail)
        reject(listOf("支付成功", "获得森林能量", "¥17.00") + tail)
        reject(listOf("支付成功", "推荐商品", "实付款27元") + tail)
        reject(listOf("支付成功", "商户原价", "¥17.00") + tail)
        reject(listOf("支付成功", "商户优惠", "¥0.62") + tail)
        reject(listOf("支付成功", "¥", "示例餐厅", "17.00") + tail)
    }
    @Test fun allSameNodeCandidatesMustBeConsidered() {
        reject(listOf("支付成功", "¥16.38 ¥17.00") + tail)
        reject(listOf("支付成功 ¥16.38 ¥17.00") + tail)
        reject(listOf("支付成功", "¥16.38", "¥12.00 ¥17.00") + tail)
        reject(listOf("支付成功", "¥16.38", "+¥12.00") + tail)
    }
    @Test fun originalPriceAndAdLabelsCannotOverrideTheResultAmount() {
        amount(listOf("支付成功", "¥16.38", "订单金额17.00") + tail, 1638)
        amount(listOf("支付成功", "¥16.38", "获得森林能量") + tail +
            listOf("推荐商品", "实付款27元", "领6.6元红包", "600花籽", "1亿补贴"), 1638)
    }
    @Test fun mixedRealFailureAndQuestionMustRemainBlocked() {
        val success = listOf("支付成功", "¥16.38") + tail
        listOf(
            listOf("当前状态", "支付失败怎么办"),
            listOf("当前状态：支付失败"),
            listOf("支付失败怎么办 支付失败"),
            listOf("支付失败", "支付失败怎么办"),
            listOf("当前状态", "处理中"),
        ).forEach { reject(success + it) }
    }
    @Test fun successfulPageWithOnlyHelpRemainsAnExpense() {
        amount(listOf("支付成功", "¥16.38") + tail + "支付失败怎么办", 1638)
    }
    @Test fun refundStateCannotBeMaskedByPaymentResultBranch() {
        val rows = listOf("支付成功", "¥16.38") + tail +
            listOf("当前状态", "退款成功", "退款金额", "0.62")
        val result = process(rows)
        if (result is BillPipeline.Outcome.Draft) {
            assertEquals(BillType.REFUND, result.draft.type)
            assertEquals(62L, result.draft.priceCents)
        }
    }
    @Test fun promotionalDirectionWordsCannotInventIncomeRefundOrTransfer() {
        val success = listOf("支付成功", "¥16.38") + tail
        listOf("退款成功帮助", "推荐收益1元", "转账优惠活动").forEach { promo ->
            val result = process(success + promo)
            if (result is BillPipeline.Outcome.Draft) {
                assertEquals("$promo => $result", BillType.EXPENSE, result.draft.type)
                assertEquals(1638L, result.draft.priceCents)
            }
        }
    }

}
