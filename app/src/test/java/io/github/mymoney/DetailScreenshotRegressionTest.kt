package io.github.mymoney

import com.example.billkit.BillPipeline
import com.example.billkit.BillType
import com.example.billkit.Packages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 9 张真机「交易详情」截图的固化回归（2026-11-05 P0）。
 *
 * 背景：普通用户（未 root）支付后确认卡不稳定——有的页面压根识别不出（支付宝带红包行 → no-amount），
 * 有的商户取错（微信交易详情把「服务/交易详情」tab 当成商户）。这两类都是页面识别（F 阶段）的真 bug。
 *
 * 这批用例是"截图即用例"的落地：截图改版就改这里的样本，改识别逻辑就跑这里兜底，
 * 防止把新逻辑搅进旧大 when 变成屎山还没人发现退化。
 */
class DetailScreenshotRegressionTest {

    private val pipeline = BillPipeline()

    private fun draftOf(pkg: String, items: List<String>) =
        (pipeline.process(Samples.snapshot(pkg, items)) as BillPipeline.Outcome.Draft).draft

    // ---------- 微信 6 张 ----------

    @Test
    fun `微信 美团-23点00 商户取金额下方大字且不再读服务tab`() {
        val d = draftOf(Packages.WECHAT, Samples.wechatDetailMeituan)
        assertEquals(2300L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("美团", d.merchant)
        assertEquals("零钱", d.method)
        assertEquals("630496", d.orderTail)
    }

    @Test
    fun `微信 美团-16点80 换卡支付`() {
        val d = draftOf(Packages.WECHAT, Samples.wechatDetailMeituanCard)
        assertEquals(1680L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("工商银行储蓄卡(5821)", d.method)
        assertEquals("823449", d.orderTail)
    }

    @Test
    fun `微信 美团金融还款-26点77 商户取金额下方大字`() {
        val d = draftOf(Packages.WECHAT, Samples.wechatDetailMeituanRepay)
        assertEquals(2677L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("美团金融还款", d.merchant)
        assertEquals("零钱", d.method)
        assertEquals("903244", d.orderTail)
    }

    @Test
    fun `微信 抖音电商-1点98`() {
        val d = draftOf(Packages.WECHAT, Samples.wechatDetailDouyin)
        assertEquals(198L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("零钱", d.method)
        assertEquals("512760", d.orderTail)
    }

    @Test
    fun `微信 淘宝-28点98 商户简称`() {
        val d = draftOf(Packages.WECHAT, Samples.wechatDetailTaobao)
        assertEquals(2898L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("淘宝平台商户", d.merchant)
        assertEquals("工商银行储蓄卡(5821)", d.method)
        assertEquals("288787", d.orderTail)
    }

    @Test
    fun `微信 早餐店扫码-3点50 转账单号`() {
        val d = draftOf(Packages.WECHAT, Samples.wechatDetailQrBreakfast)
        assertEquals(350L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("早餐店", d.merchant)
        assertEquals("零钱", d.method)
        assertEquals("212635", d.orderTail)
    }

    // ---------- 支付宝 3 张 ----------

    @Test
    fun `支付宝 高德打车-10点67`() {
        val d = draftOf(Packages.ALIPAY, Samples.alipayDetailGaode)
        assertEquals(1067L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("高德打车", d.merchant)
        assertEquals("工商银行储蓄卡(5821)", d.method)
        assertEquals("高德打车订单", d.productName)
    }

    @Test
    fun `支付宝 淘宝闪购-25点44`() {
        val d = draftOf(Packages.ALIPAY, Samples.alipayDetailTaobaoFlash)
        assertEquals(2544L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("淘宝闪购", d.merchant)
        assertEquals("工商银行储蓄卡(5821)", d.method)
    }

    @Test
    fun `支付宝 淘宝闪购-26点29 带红包行不漏弹取主金额`() {
        // 回归：页面同时有主金额 -26.29 和「闪购支付红包 -0.60」，之前会被判成 no-amount 整体跳过。
        // 现在应取主金额 2629，而不是被红包行干扰。
        val d = draftOf(Packages.ALIPAY, Samples.alipayDetailTaobaoFlashRedPacket)
        assertEquals(2629L, d.priceCents)
        assertEquals(BillType.EXPENSE, d.type)
        assertEquals("淘宝闪购", d.merchant)
        assertEquals("工商银行储蓄卡(5821)", d.method)
        assertEquals("华莱士·全鸡汉堡顺达店外卖订单", d.productName)
    }
}
