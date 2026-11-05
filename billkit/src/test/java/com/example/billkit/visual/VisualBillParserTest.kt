package com.example.billkit.visual

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test

/** Synthetic OCR block contracts, not model accuracy or real payment success claims. */
class VisualBillParserTest {
    private val now = 1_793_779_200_000L
    private fun text(vararg lines: String) = VisualText(900,1600,lines.map { VisualLine(it) },"synthetic")
    private fun paid(vararg extra: String) = text("支付成功","付款金额:28.50元","商户名称:合成早餐店",*extra)
    private fun expected() = BillDraft(2850,BillType.EXPENSE,now,null,null,null,"com.tencent.mm","synthetic-node")

    @Test fun completedSinglePaymentCreatesOnlyManualDraftWithNoStrongOcrIdentity() {
        val result = VisualBillParser.parse(paid("支付方式:零钱","交易单号:12345678901234567890","商品说明:鸡蛋面外卖订单"),now)
        assertNull(result.issue)
        val d = result.draft!!
        assertEquals(2850L,d.priceCents);assertEquals(BillType.EXPENSE,d.type)
        assertEquals("合成早餐店",d.merchant);assertEquals("零钱",d.method)
        assertEquals("鸡蛋面外卖订单",d.productName)
        assertTrue(d.sourcePkg.startsWith("io.github.mymoney.manual."))
        assertNull(d.transactionKey);assertNull(d.orderTail);assertNull(d.dedupKey)
        assertEquals("12345678901234567890",result.fields.orderCandidate)
        assertFalse(d.hasExplicitTime);assertEquals(now,d.timeMillis)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun failuresPendingPasswordsRefundsAndListsCannotBecomeDrafts() {
        for (bad in listOf("支付失败","待付款","未扣款","输入支付密码","验证码","交易已关闭","退款成功","累计退款:5.00元","全部账单","充值成功")) {
            val result=VisualBillParser.parse(paid(bad),now)
            assertNotNull(bad,result.issue);assertNull(bad,result.draft)
        }
    }

    @Test fun statusOnlyOrAmountOnlyCannotEstablishPayment() {
        assertNotNull(VisualBillParser.parse(text("支付成功","商户名称:合成店"),now).issue)
        assertNotNull(VisualBillParser.parse(text("付款金额:28.50元","商户名称:合成店"),now).issue)
        assertNotNull(VisualBillParser.parse(text("最高返现28.50元","领取优惠券","¥28.50"),now).issue)
    }

    @Test fun sameRowFieldsAreResolvedFromGeometryInShuffledOrder() {
        val result=VisualBillParser.parse(VisualText(900,1600,listOf(
            VisualLine("零钱",450,900,600,940),VisualLine("合成咖啡店",400,800,700,840),
            VisualLine("支付方式",80,900,250,940),VisualLine("商户名称",80,800,250,840),
            VisualLine("支付成功",300,150,600,195),VisualLine("¥28.50",300,400,600,490),
            VisualLine("付款金额",80,700,250,740),VisualLine("28.50元",450,700,650,740),
        ),"synthetic"),now)
        assertNull(result.issue);assertEquals(2850L,result.amountCents)
        assertEquals("合成咖啡店",result.fields.merchant);assertEquals("零钱",result.fields.method)
    }

    @Test fun conflictingExplicitAndHeaderAmountsAreRejectedRatherThanChoosingOne() {
        for (input in listOf(paid("付款金额:18.00元"),paid("¥88.00"),text("支付成功","¥18.00","¥28.50"))) {
            assertNotNull(VisualBillParser.parse(input,now).issue)
            assertNull(VisualBillParser.parse(input,now).draft)
        }
    }

    @Test fun unrelatedCouponAndBalanceRowsDoNotReplaceMainAmount() {
        val result=VisualBillParser.parse(VisualText(900,1600,listOf(
            VisualLine("支付成功",300,150,600,195),VisualLine("¥28.50",300,400,600,490),
            VisualLine("余额",80,800,220,840),VisualLine("¥200.00",450,800,650,840),
            VisualLine("优惠金额",80,900,260,940),VisualLine("¥5.00",450,900,650,940),
        ),"synthetic"),now)
        assertNull(result.issue);assertEquals(2850L,result.amountCents)
        val linear=VisualBillParser.parse(paid("余额","¥200.00","优惠金额","¥5.00"),now)
        assertNull(linear.issue);assertEquals(2850L,linear.amountCents)
    }

    @Test fun onlyExplicitTransactionTimeIsUsedAndOrderCreationTimeIgnored() {
        val absent=VisualBillParser.parse(paid("创建时间:2026-11-01 08:00:00"),now)
        assertFalse(absent.explicitTime);assertNull(absent.fields.timeMillis)
        val known=VisualBillParser.parse(paid("付款时间:2026-11-01 08:00:00"),now)
        assertNull(known.issue);assertTrue(known.explicitTime);assertTrue(known.fields.timeMillis!!<now)
        val future=VisualBillParser.parse(paid("付款时间:2030-11-01 08:00:00"),now)
        assertFalse(future.explicitTime)
    }

    @Test fun sameObservationSupplementRequiresImageAmountButNotRepeatedSuccessWords() {
        val result=VisualBillParser.supplement(text("¥28.50","支付方式:零钱"),expected(),now)
        assertNull(result.issue);assertEquals("零钱",result.fields.method)
        assertNull(result.draft)
        assertNotNull(VisualBillParser.supplement(text("支付方式:零钱"),expected(),now).issue)
        assertNotNull(VisualBillParser.supplement(text("¥18.50","支付方式:零钱"),expected(),now).issue)
    }

    @Test fun oppositeDirectionAndExplicitTimeConflictNeverSupplement() {
        val income=VisualBillParser.supplement(text("收款成功","到账金额:28.50元"),expected(),now)
        assertNotNull(income.issue)
        val changedTime=VisualBillParser.supplement(paid("付款时间:2026-11-01 08:00:00"),expected().copy(hasExplicitTime=true),now)
        assertNotNull(changedTime.issue)
    }

    @Test fun incomeAndOutgoingTransferHaveSeparateSemantics() {
        assertEquals(BillType.INCOME,VisualBillParser.parse(text("收款成功","到账金额:28.50元"),now).draft!!.type)
        val result=VisualBillParser.parse(text("支付成功","¥28.50","待合成对象确认收款","完成"),now)
        assertNull(result.issue);assertEquals(BillType.EXPENSE,result.draft!!.type)
        assertNull(result.fields.merchant)
        assertNotNull(VisualBillParser.parse(paid("收款成功"),now).issue)
    }

    @Test fun genericSuccessNeedsDirectionEvidenceAndIncomeLabelsNeverDefaultToExpense() {
        val income=VisualBillParser.parse(text("交易成功", "到账金额:28.50元"),now)
        assertNull(income.issue);assertEquals(BillType.INCOME,income.draft!!.type)
        val expense=VisualBillParser.parse(text("交易成功", "付款金额:28.50元"),now)
        assertNull(expense.issue);assertEquals(BillType.EXPENSE,expense.draft!!.type)
        assertNotNull(VisualBillParser.parse(text("交易成功", "¥28.50"),now).issue)
        assertEquals(BillType.EXPENSE,VisualBillParser.parse(text("交易成功", "-28.50"),now).draft!!.type)
        assertEquals(BillType.INCOME,VisualBillParser.parse(text("交易成功", "+28.50"),now).draft!!.type)
    }

    @Test fun explicitAmountSignAndDirectionConflictCannotBecomeDraftOrSupplement() {
        for (input in listOf(text("收款成功", "-28.50"),text("付款成功", "+28.50"),
            text("交易成功", "到账金额:-28.50元"),text("付款成功", "到账金额:28.50元"))) {
            assertNotNull(VisualBillParser.parse(input,now).issue)
            assertNotNull(VisualBillParser.supplement(input,expected(),now).issue)
        }
    }

    @Test fun fieldLabelsNearHeaderAreNotInventedAsMerchant() {
        val result=VisualBillParser.parse(VisualText(900,1600,listOf(
            VisualLine("支付成功",300,150,600,195),VisualLine("¥28.50",300,400,600,490),
            VisualLine("商品说明:拿铁咖啡",200,510,650,550),
        ),"synthetic"),now)
        assertNull(result.issue);assertNull(result.fields.merchant)
        assertEquals("拿铁咖啡",result.fields.productName)
    }

    @Test fun explicitForeignOrUnknownCurrencyCannotBeTreatedAsCny() {
        for (currency in listOf("USD", "HKD", "美元", "未知")) {
            assertNotNull(VisualBillParser.parse(paid("币种:$currency"),now).issue)
        }
        assertNull(VisualBillParser.parse(paid("币种:CNY"),now).issue)
        assertNotNull(VisualBillParser.parse(paid("币种USD"),now).issue)
    }

    @Test fun invalidDigitsFractionalCentZeroAndExcessAmountAreNotRepaired() {
        for (amount in listOf("28.O0","28.500","0.00","1000000.01","1,23.00","12345678901234567890")) {
            assertNotNull(amount,VisualBillParser.parse(text("支付成功","付款金额:$amount"),now).issue)
        }
        assertEquals(100_000_000L,VisualBillParser.parse(text("支付成功","付款金额:1000000.00"),now).amountCents)
    }

    @Test fun unknownConfidenceIsRetainedAsUnknownAndLowAmountConfidenceCannotAuthorize() {
        val low = VisualText(900,1600,listOf(VisualLine("支付成功"),VisualLine("付款金额:28.50元",confidence=.4f)),"synthetic")
        assertNotNull(VisualBillParser.parse(low,now).issue)
        assertNull(VisualBillParser.parse(paid(),now).issue)
    }

    @Test fun excessiveResultsFailClosed() {
        assertNotNull(VisualBillParser.parse(text(*Array(501){"支付成功"}),now).issue)
        assertNotNull(VisualBillParser.parse(VisualText(0,0,emptyList(),"synthetic"),now).issue)
    }
}
