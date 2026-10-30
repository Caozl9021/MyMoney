package io.github.mymoney

import com.example.billkit.AmountParser
import com.example.billkit.BillDraft
import com.example.billkit.BillPipeline
import com.example.billkit.BillType
import com.example.billkit.Packages
import com.example.billkit.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-24 用户两张支付成功截图的人工转录（已脱敏）。
 *
 * 这里使用的是可见文本，不是无障碍节点 dump，也没有系统状态栏节点；
 * 通过只证明这些文本能走通规则，不代表真机付款瞬间已经采到这些节点或事件。
 */
class PaymentResultScreenshotTest {

    private val now = 1_792_713_600_000L

    /** 截图1：支付宝。实付16.38、商户原额17.00、优惠0.62与广告数字同屏。 */
    private val alipay = listOf(
        "支付成功",
        "¥16.38",
        "获得森林能量",
        "示例餐厅 ¥17.00",
        "碰友日立减 -¥0.62",
        "付款方式",
        "工商银行储蓄卡(0000)",
        "恭喜你获得600笔花籽",
        "疯狂碰友节分1亿红包",
        "今日合成最高6.6元红包",
        "27元外卖券",
        "去参与",
        "完成",
    )

    /** 截图2：微信极简支付成功页，没有支付方式、单号或时间字段。 */
    private val wechat = listOf("支付成功", "示例收款人（*某）", "¥0.01", "完成")

    private fun process(rows: List<String>, pkg: String = Packages.ALIPAY) =
        BillPipeline().process(UiSnapshot(pkg, null, rows), now)

    private fun draft(rows: List<String>, pkg: String = Packages.ALIPAY): BillDraft {
        val result = process(rows, pkg)
        assertTrue("$rows => $result", result is BillPipeline.Outcome.Draft)
        return (result as BillPipeline.Outcome.Draft).draft
    }

    private fun assertExpense(rows: List<String>, cents: Long, pkg: String = Packages.ALIPAY) {
        val bill = draft(rows, pkg)
        assertEquals("$rows => ${bill.priceCents}", cents, bill.priceCents)
        assertEquals("$rows", BillType.EXPENSE, bill.type)
        assertEquals("$rows", now, bill.timeMillis)
        assertFalse("$rows", bill.hasExplicitTime)
        assertNull("$rows", bill.orderTail)
        assertNull("$rows", bill.transactionKey)
    }

    private fun assertSkipped(rows: List<String>, pkg: String = Packages.ALIPAY) {
        val result = process(rows, pkg)
        assertTrue("$rows => $result", result is BillPipeline.Outcome.Skipped)
    }

    /** 方向不能是支出；保守跳过也允许。 */
    private fun assertNotExpense(rows: List<String>, pkg: String = Packages.ALIPAY) {
        val result = process(rows, pkg)
        assertTrue(
            "$rows => $result",
            result !is BillPipeline.Outcome.Draft || result.draft.type != BillType.EXPENSE,
        )
    }

    // ---------- 两张截图的主场景 ----------

    @Test
    fun alipayScreenshotUsesActualPaymentNotOriginalDiscountOrAds() {
        assertExpense(alipay, 1638L)
        val bill = draft(alipay)
        assertEquals("工商银行储蓄卡(0000)", bill.method)
    }

    @Test
    fun wechatScreenshotKeepsMissingMethodOrderAndTimeUnknown() {
        assertExpense(wechat, 1L, Packages.WECHAT)
        val bill = draft(wechat, Packages.WECHAT)
        assertNull(bill.method)
        assertNull(bill.orderTail)
        assertNull(bill.transactionKey)
        assertFalse(bill.hasExplicitTime)
        assertEquals(now, bill.timeMillis)
    }

    // ---------- 用户指定的高价值反例（全部走 BillPipeline 端到端） ----------

    @Test
    fun highValueCase1LabeledOriginalAmountAfterMainDoesNotWin() {
        assertExpense(
            listOf("支付成功", "¥16.38", "订单金额17.00", "付款方式", "银行卡", "完成"),
            1638L,
        )
        // 标签和原价拆行时，原价仍只是边界内的非主额，不得替代16.38。
        assertExpense(
            listOf("支付成功", "¥16.38", "订单金额", "17.00", "付款方式", "银行卡", "完成"),
            1638L,
        )
    }

    @Test
    fun highValueCase2MerchantOriginalWithoutActualPaymentIsSkipped() {
        assertSkipped(
            listOf("支付成功", "示例餐厅", "¥17.00", "碰友日立减 -¥0.62", "付款方式", "银行卡", "完成"),
        )
        assertSkipped(
            listOf("支付成功", "示例餐厅", "17.00", "付款方式", "银行卡", "完成"),
        )
    }

    @Test
    fun highValueCase3TwoAmountsInOneOrAdjacentNodesAreAmbiguous() {
        assertSkipped(listOf("支付成功", "¥16.38 ¥17.00", "付款方式", "银行卡", "完成"))
        assertSkipped(listOf("支付成功", "¥16.38", "¥17.00", "付款方式", "银行卡", "完成"))
        assertSkipped(listOf("支付成功", "¥16.38", "¥16.38", "付款方式", "银行卡", "完成"))
    }

    @Test
    fun highValueCase4AdAmountAfterMainCannotWinAndAdCannotSubstituteMissingMain() {
        assertExpense(
            listOf("支付成功", "¥16.38", "示例餐厅 ¥17.00", "实付款27元", "付款方式", "银行卡", "完成"),
            1638L,
        )
        assertSkipped(
            listOf("支付成功", "示例餐厅", "实付款27元", "付款方式", "银行卡", "完成"),
        )
        assertSkipped(
            listOf("支付成功", "示例餐厅", "实付款 ¥27.00", "付款方式", "银行卡", "完成"),
        )
    }

    @Test
    fun highValueCase5CurrencyCannotBeJoinedAcrossAnotherNode() {
        assertSkipped(listOf("支付成功", "¥", "示例餐厅", "17.00", "付款方式", "银行卡", "完成"))
        assertSkipped(listOf("支付成功\n¥\n示例餐厅\n17.00\n付款方式\n银行卡\n完成"))
    }

    // ---------- 同节点换行、拆币种 ----------

    @Test
    fun splitCurrencyNodeAndMultilineContainerAreSupported() {
        val split = alipay.flatMap { if (it == "¥16.38") listOf("¥", "16.38") else listOf(it) }
        assertExpense(split, 1638L)

        assertExpense(listOf(alipay.joinToString("\n")), 1638L)
        assertExpense(listOf(alipay.joinToString("\n").replace("¥16.38", "¥\n16.38")), 1638L)

        assertExpense(listOf(wechat.joinToString("\n")), 1L, Packages.WECHAT)
        assertExpense(listOf(wechat.joinToString("\n").replace("¥0.01", "¥\n0.01")), 1L, Packages.WECHAT)
    }

    // ---------- 帮助入口与真实状态 ----------

    @Test
    fun helpQuestionsDoNotTurnSuccessIntoFailure() {
        listOf("支付失败怎么办", "支付失败怎么办？", "付款失败怎么办").forEach { help ->
            assertExpense(alipay + help, 1638L)
            assertExpense(wechat + help, 1L, Packages.WECHAT)
            assertEquals(AmountParser.Status.SUCCESS, AmountParser.status(listOf("支付成功", help)))
        }
    }

    @Test
    fun realStatesStillBlockEvenWhenHelpTextIsAlsoPresent() {
        val states = listOf(
            "支付失败",
            "付款失败",
            "已取消",
            "交易已关闭",
            "处理中",
            "正在支付",
            "当前状态：支付失败",
            "支付失败怎么办 支付失败",
            "当前状态：支付失败怎么办",
        )
        states.forEach { state ->
            assertSkipped(alipay + listOf("支付失败怎么办", state))
            assertSkipped(wechat + listOf("支付失败怎么办", state), Packages.WECHAT)
        }
        assertSkipped(alipay + listOf("当前状态", "支付失败怎么办"))
        assertEquals(AmountParser.Status.SUCCESS, AmountParser.status(listOf("支付成功", "售后处理中心")))
        assertEquals(AmountParser.Status.PENDING, AmountParser.status(listOf("支付成功", "处理中心 处理中")))
    }

    @Test
    fun missingSuccessOrMissingActualPaymentIsRejectedWithoutArithmetic() {
        assertSkipped(alipay.filterNot { it == "¥16.38" })
        assertSkipped(alipay.map { if (it == "¥16.38") "16.38" else it })
        assertSkipped(alipay.filterNot { it == "支付成功" })
        assertSkipped(listOf("支付成功", "示例餐厅 ¥17.00", "碰友日立减 -¥0.62", "付款方式", "银行卡", "完成"))
        assertSkipped(listOf("支付成功", "订单金额", "17.00", "付款方式", "银行卡", "完成"))
    }

    @Test
    fun malformedOrAmbiguousMainAmountsAreRejected() {
        listOf("¥16.380", "+¥16.38", "-¥16.38", "¥01.00", "¥16.3.8", "¥16.38 ¥17.00", "16.38").forEach { amount ->
            assertSkipped(alipay.map { if (it == "¥16.38") amount else it })
        }
        assertSkipped(alipay.toMutableList().apply { add(2, "¥12.00") })
        assertSkipped(alipay.toMutableList().apply { add(2, "¥16.38") })
    }

    @Test
    fun wrongPackageListPageAndMissingResultBoundaryAreRejected() {
        assertSkipped(alipay, "com.example.unrelated")
        assertSkipped(alipay + "筛选")
        assertSkipped(listOf("支付成功", "¥16.38", "示例餐厅 ¥17.00"))
        assertSkipped(listOf("支付成功", "获得森林能量", "¥17.00", "付款方式", "银行卡", "完成"))
        assertSkipped(emptyList())
    }

    // ---------- 方向与既有详情回归 ----------

    @Test
    fun mixedRefundTransferOrIncomePageIsNeverForcedToExpense() {
        // 混合“支付成功 + 退款成功/退款金额”保守跳过，不把16.38冒充退款金额。
        assertNotExpense(listOf("支付成功", "¥16.38", "退款成功", "退款金额0.62", "付款方式", "银行卡", "完成"))
        assertNotExpense(alipay + "退款成功")
        assertNotExpense(alipay + "转账成功")
        assertNotExpense(alipay + "收款成功")
        assertNotExpense(alipay + listOf("广告", "收益到账"))
    }

    @Test
    fun existingTransferRefundAndWechatDetailsKeepTheirDirections() {
        val transfer = draft(Samples.alipayTransfer)
        assertEquals(BillType.TRANSFER, transfer.type)
        assertEquals(5000L, transfer.priceCents)

        val refundPage = listOf(
            "账单详情", "当前状态", "退款成功", "实付款100元", "退款金额19.99元",
            "交易时间", "2026-10-22 12:00:00", "交易单号", "123456789",
        )
        val refund = draft(refundPage)
        assertEquals(BillType.REFUND, refund.type)
        assertEquals(1999L, refund.priceCents)

        val wechatTransfer = draft(Samples.wechatTransfer, Packages.WECHAT)
        assertEquals(BillType.TRANSFER, wechatTransfer.type)
    }
}
