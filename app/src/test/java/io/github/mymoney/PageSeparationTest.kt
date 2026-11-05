package io.github.mymoney

import com.example.billkit.*
import com.example.billkit.spec.PersonalPageRules
import io.github.mymoney.capture.PageObservationScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PageSeparationTest {
    private fun page(items: List<String>, pkg: String = Packages.WECHAT) = UiSnapshot(pkg, null, items)
    private val result = listOf("支付成功", "¥73.41", "完成", "付款方式", "零钱")
    @After fun cleanup() { BillKit.reset(); DebugBus.clear(); PersonalPageRules.install(emptyList()) }

    @Test fun paymentResultsCannotDispatchEvenWhenParserCanReadThem() {
        for (pkg in listOf(Packages.WECHAT, Packages.ALIPAY)) {
            val deliveries = mutableListOf<BillDraft>()
            BillKit.install(sink = { deliveries += it })
            val snapshot = page(result, pkg)
            assertEquals(PageCaptureEligibility.Scene.PAYMENT_RESULT, PageCaptureEligibility.evaluate(snapshot).scene)
            assertTrue(BillKit.offer(snapshot, 10000) is BillPipeline.Outcome.Skipped)
            assertTrue(deliveries.isEmpty())
        }
    }
    @Test fun suppliedNineDetailSamplesRemainAutomaticallyRecognizable() {
        val cases = listOf(
            Packages.WECHAT to Samples.wechatDetailMeituan, Packages.WECHAT to Samples.wechatDetailMeituanCard,
            Packages.WECHAT to Samples.wechatDetailMeituanRepay, Packages.WECHAT to Samples.wechatDetailDouyin,
            Packages.WECHAT to Samples.wechatDetailTaobao, Packages.WECHAT to Samples.wechatDetailQrBreakfast,
            Packages.ALIPAY to Samples.alipayDetailGaode, Packages.ALIPAY to Samples.alipayDetailTaobaoFlash,
            Packages.ALIPAY to Samples.alipayDetailTaobaoFlashRedPacket)
        for ((pkg, items) in cases) {
            assertTrue(items.toString(), PageCaptureEligibility.evaluate(page(items,pkg)).allowed)
            assertTrue(items.toString(), BillPipeline().processForCapture(page(items,pkg)) is BillPipeline.Outcome.Draft)
        }
    }
    @Test fun aDetailNavigationLinkOnResultsIsNotADetail() {
        assertFalse(PageCaptureEligibility.evaluate(page(result + "交易详情")).allowed)
    }
    @Test fun aKnownPaymentContainerWithMixedDetailFieldsFailsClosed() {
        val mixed = page(Samples.alipayDetailGaode, Packages.ALIPAY).copy(activityClass="com.alipay.MspContainerActivity")
        assertEquals(PageCaptureEligibility.Scene.UNKNOWN,PageCaptureEligibility.evaluate(mixed).scene)
        assertTrue(BillPipeline().processForCapture(mixed) is BillPipeline.Outcome.Skipped)
    }
    @Test fun detailsWithSuccessStatusAreNotRejectedAsResults() {
        assertTrue(PageCaptureEligibility.evaluate(page(Samples.wechatBill)).allowed)
    }
    @Test fun detailsWithHiddenOrderNumberRemainEligible() {
        val d = page(Samples.alipayDetailGaode, Packages.ALIPAY)
        assertTrue(PageCaptureEligibility.evaluate(d).allowed)
        val parsed = (BillPipeline().processForCapture(d) as BillPipeline.Outcome.Draft).draft
        assertNull(parsed.transactionKey)
        assertTrue(parsed.hasExplicitTime)
    }
    @Test fun failedPendingAndListPagesNeverBecomeDrafts() {
        for(items in listOf(Samples.wechatListPage, Samples.pendingPay,
            Samples.wechatBill.map { if(it=="支付成功") "支付失败" else it })) {
            assertTrue(BillPipeline().processForCapture(page(items)) is BillPipeline.Outcome.Skipped)
        }
    }
    @Test fun incompleteAndForeignPagesFailClosed() {
        assertFalse(PageCaptureEligibility.evaluate(page(Samples.wechatBill).copy(truncated=true)).allowed)
        assertFalse(PageCaptureEligibility.evaluate(page(Samples.wechatBill,"other.app")).allowed)
    }
    @Test fun oldObservationCannotSurviveLeavingThePageOrANewTransaction() {
        val scope=PageObservationScope()
        scope.observe("one","order-a"); scope.observe("two","order-a")
        assertTrue(scope.allows("one")); assertTrue(scope.allows("two"))
        scope.observe("three","order-b")
        assertFalse(scope.allows("one")); assertTrue(scope.allows("three"))
        scope.clear(); assertFalse(scope.allows("three")); assertFalse(scope.allows(null))
    }
    @Test fun scopeRetainsOnlyBoundedRecentObservations() {
        val scope=PageObservationScope()
        repeat(20) { scope.observe("$it","same-order") }
        assertFalse(scope.allows("0")); assertTrue(scope.allows("19"))
    }
    @Test fun resultPageNeverReachesPersonalRuleDispatch() {
        PersonalPageRules.install(listOf(com.example.billkit.spec.PersonalRule(
            "personal_gphase01", "结果页不能绕过", keyword="支付成功", merchant="测试商户")))
        assertNotNull(PersonalPageRules.match(page(result),10000))
        // The automatic gate precedes personal rules; preview can still parse result pages.
        assertTrue(BillPipeline().process(page(result)) is BillPipeline.Outcome.Draft)
        assertEquals("scene:payment-result-not-page-capture",
            (BillPipeline().processForCapture(page(result)) as BillPipeline.Outcome.Skipped).reason)
    }
}
