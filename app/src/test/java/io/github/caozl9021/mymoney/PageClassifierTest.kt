package io.github.caozl9021.mymoney

import com.example.billkit.BillPipeline
import com.example.billkit.PageClassifier
import com.example.billkit.PageKind
import com.example.billkit.Packages
import com.example.billkit.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则回归。每个 App 一屏真实页面文本（见 [Samples]），断言：
 *   1. 分类结果（PageKind）
 *   2. 端到端草稿（金额 / 方向 / 商户 / 支付方式 / 单号尾号）
 *   3. 反例（列表页、确认下单页、进行中）必须被拦下
 */
class PageClassifierTest {

    private val classifier = PageClassifier()
    private val pipeline = BillPipeline()

    // ---------- 分类 ----------

    @Test
    fun `微信扫码付款交易详情（真机页面）`() {
        // 回归：金额是 -3.50、没有 ¥；商户只在备注里；不能把"收款方备注"当商户
        assertEquals(
            PageKind.WECHAT_BILL,
            classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.wechatQrPay))?.kind,
        )
        val draft = draftOf(Packages.WECHAT, Samples.wechatQrPay)
        assertEquals(350L, draft.priceCents)
        assertEquals(BillType.EXPENSE, draft.type)
        assertEquals("早餐店", draft.merchant)
        assertEquals("工商银行储蓄卡(5821)", draft.method)
        assertEquals("445553", draft.orderTail)
    }

    @Test
    fun `微信账单详情`() {
        val match = classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.wechatBill))
        assertEquals(PageKind.WECHAT_BILL, match?.kind)
    }

    @Test
    fun `微信转账成功页`() {
        val match = classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.wechatTransfer))
        assertEquals(PageKind.WECHAT_TRANSFER, match?.kind)
    }

    @Test
    fun `微信收款详情`() {
        val match = classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.wechatReceive))
        assertEquals(PageKind.WECHAT_RECEIVE, match?.kind)
    }

    @Test
    fun `微信零钱通`() {
        val match = classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.wechatLqt))
        assertEquals(PageKind.WECHAT_LQT, match?.kind)
    }

    @Test
    fun `微信红包`() {
        val match = classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.wechatRedPacket))
        assertEquals(PageKind.WECHAT_RED_PACKET, match?.kind)
    }

    @Test
    fun `支付宝账单详情`() {
        val match = classifier.classify(Samples.snapshot(Packages.ALIPAY, Samples.alipayBill))
        assertEquals(PageKind.ALIPAY_BILL, match?.kind)
    }

    @Test
    fun `支付宝转账`() {
        val match = classifier.classify(Samples.snapshot(Packages.ALIPAY, Samples.alipayTransfer))
        assertEquals(PageKind.ALIPAY_TRANSFER, match?.kind)
    }

    @Test
    fun `云闪付支付页与账单详情`() {
        assertEquals(
            PageKind.UNIONPAY_PAY,
            classifier.classify(Samples.snapshot(Packages.UNIONPAY, Samples.unionPayPay))?.kind,
        )
        assertEquals(
            PageKind.UNIONPAY_BILL,
            classifier.classify(Samples.snapshot(Packages.UNIONPAY, Samples.unionPayBill))?.kind,
        )
    }

    @Test
    fun `京东三种页面`() {
        assertEquals(PageKind.JD_BILL, classifier.classify(Samples.snapshot(Packages.JD, Samples.jdBill))?.kind)
        assertEquals(
            PageKind.JD_BILL,
            classifier.classify(Samples.snapshot(Packages.JD_FINANCE, Samples.jdFinanceBill))?.kind,
        )
        assertEquals(PageKind.JD_PAY, classifier.classify(Samples.snapshot(Packages.JD, Samples.jdPay))?.kind)
    }

    @Test
    fun `美团订单详情`() {
        assertEquals(
            PageKind.MEITUAN_BILL,
            classifier.classify(Samples.snapshot(Packages.MEITUAN, Samples.meituanBill))?.kind,
        )
    }

    @Test
    fun `淘宝支付成功`() {
        assertEquals(
            PageKind.TAOBAO_PAY,
            classifier.classify(Samples.snapshot(Packages.TAOBAO, Samples.taobaoPay))?.kind,
        )
    }

    @Test
    fun `拼多多订单详情`() {
        assertEquals(PageKind.PDD_BILL, classifier.classify(Samples.snapshot(Packages.PDD, Samples.pddBill))?.kind)
    }

    @Test
    fun `抖音消费详情`() {
        assertEquals(
            PageKind.DOUYIN_BILL,
            classifier.classify(Samples.snapshot(Packages.DOUYIN, Samples.douyinBill))?.kind,
        )
    }

    @Test
    fun `数字人民币交易详情`() {
        assertEquals(PageKind.DCEP_BILL, classifier.classify(Samples.snapshot(Packages.DCEP, Samples.dcepBill))?.kind)
    }

    // ---------- 反例 ----------

    @Test
    fun `账单列表页不识别`() {
        assertNull(classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.wechatListPage)))
    }

    @Test
    fun `确认下单页不识别`() {
        assertNull(classifier.classify(Samples.snapshot(Packages.WECHAT, Samples.orderConfirmPage)))
    }

    @Test
    fun `不认识的包但页面不是账单时不识别`() {
        // 白名单（acceptedPackages）由 BillKit 把关；分类器只对"能进来"的页面做内容判断。
        // 但内容不像账单时也必须返回 null，否则用户误加一个 App 就会乱记账。
        assertNull(classifier.classify(Samples.snapshot("com.example.other", Samples.wechatListPage)))
        assertNull(classifier.classify(Samples.snapshot("com.example.other", Samples.orderConfirmPage)))
    }

    // ---------- 支付宝真机（新版账单详情） ----------

    @Test
    fun `支付宝详情页顶部有全部账单也不是列表页`() {
        // 以前 "全部账单" 一条弱信号就把整屏判成列表页 → skip:not-a-bill-page
        val match = classifier.classify(Samples.snapshot(Packages.ALIPAY, Samples.alipaySpendDetail))
        assertEquals(PageKind.ALIPAY_BILL, match?.kind)
    }

    @Test
    fun `支出19元这种写法能抽出草稿`() {
        val draft = draftOf(Packages.ALIPAY, Samples.alipaySpendDetail)
        assertEquals(1900L, draft.priceCents)
        assertEquals(BillType.EXPENSE, draft.type)
        assertEquals("东富路家味餐厅", draft.merchant)
        assertEquals("工商银行储蓄卡(5821)", draft.method)
    }

    @Test
    fun `白名单里加进来的陌生包按页面内容兜底`() {
        // 用户这台机器上支付宝的包名是 com.tentent.mm（分身/宿主包），不在默认白名单里，
        // 但只要页面是完整的账单详情，就应该照常识别。
        val draft = draftOf("com.tentent.mm", Samples.alipaySpendDetail)
        assertEquals(1900L, draft.priceCents)
        assertEquals(BillType.EXPENSE, draft.type)
        assertEquals("东富路家味餐厅", draft.merchant)

        // 不能冒充支付宝：来源要如实标成通用账单
        val match = classifier.classify(Samples.snapshot("com.tentent.mm", Samples.alipaySpendDetail))
        assertEquals(PageKind.GENERIC_BILL, match?.kind)
    }

    // ---------- 端到端 ----------

    @Test
    fun `微信账单抽出完整草稿`() {
        val draft = draftOf(Packages.WECHAT, Samples.wechatBill)
        assertEquals(2580L, draft.priceCents)
        assertEquals(BillType.EXPENSE, draft.type)
        assertEquals("星巴克咖啡", draft.merchant)
        assertEquals("零钱", draft.method)
        assertEquals("567890", draft.orderTail)
    }

    @Test
    fun `微信转账是转账方向且取到收款方`() {
        val draft = draftOf(Packages.WECHAT, Samples.wechatTransfer)
        assertEquals(20000L, draft.priceCents)
        assertEquals(BillType.TRANSFER, draft.type)
        assertEquals("张三", draft.merchant)
    }

    @Test
    fun `微信收款是收入方向`() {
        val draft = draftOf(Packages.WECHAT, Samples.wechatReceive)
        assertEquals(8800L, draft.priceCents)
        assertEquals(BillType.INCOME, draft.type)
    }

    @Test
    fun `微信红包是收入，余额行不能当金额`() {
        val draft = draftOf(Packages.WECHAT, Samples.wechatRedPacket)
        assertEquals(666L, draft.priceCents)
        assertEquals(BillType.INCOME, draft.type)
    }

    @Test
    fun `零钱通按转账处理，余额行被排除`() {
        val draft = draftOf(Packages.WECHAT, Samples.wechatLqt)
        assertEquals(100000L, draft.priceCents)
        assertEquals(BillType.TRANSFER, draft.type)
    }

    @Test
    fun `支付宝账单取花呗为支付方式`() {
        val draft = draftOf(Packages.ALIPAY, Samples.alipayBill)
        assertEquals(12800L, draft.priceCents)
        assertEquals("花呗", draft.method)
        assertEquals(BillType.EXPENSE, draft.type)
    }

    @Test
    fun `京东账单取实付款而不是订单号`() {
        val draft = draftOf(Packages.JD, Samples.jdBill)
        assertEquals(19900L, draft.priceCents)
        assertEquals("京东白条", draft.method)
        assertEquals("345678", draft.orderTail)
    }

    @Test
    fun `美团实付款在下一行也能取到`() {
        val draft = draftOf(Packages.MEITUAN, Samples.meituanBill)
        assertEquals(3250L, draft.priceCents)
        assertEquals("黄焖鸡米饭", draft.merchant)
    }

    @Test
    fun `拼多多拼单成功页`() {
        val draft = draftOf(Packages.PDD, Samples.pddBill)
        assertEquals(1990L, draft.priceCents)
    }

    @Test
    fun `进行中的页面不产生草稿`() {
        val outcome = pipeline.process(Samples.snapshot(Packages.WECHAT, Samples.pendingPay))
        assertTrue(outcome is BillPipeline.Outcome.Skipped)
        assertTrue((outcome as BillPipeline.Outcome.Skipped).reason.startsWith("pending"))
    }

    @Test
    fun `分类未命中时给出 not-a-bill-page`() {
        val outcome = pipeline.process(Samples.snapshot(Packages.WECHAT, Samples.wechatListPage))
        assertEquals("not-a-bill-page", (outcome as BillPipeline.Outcome.Skipped).reason)
    }

    @Test
    fun `同一页面指纹稳定可用于去重`() {
        assertEquals(
            draftOf(Packages.WECHAT, Samples.wechatBill).fingerprint,
            draftOf(Packages.WECHAT, Samples.wechatBill).fingerprint,
        )
    }

    // ---------- 含页面噪音的文本路径 ----------

    @Test
    fun `带推荐位噪音的页面文本仍能走同一套规则`() {
        val raw = listOf(
            "9:50",
            "5G 92%",
            "支付成功",
            "¥",
            "25.80",
            "商户全称：星巴克咖啡",
            "支付方式",
            "零钱",
            "支付时间",
            "2024年10月21日 09:50:12",
            "交易单号",
            "4200001234202410211234567890",
            "相关推荐",
            "星巴克优惠券",
        )
        // OcrNormalizer 已随 OCR 删除，这里直接喂原始文本：
        // 下游的 PageClassifier/AmountParser 本来就要能容忍「¥」与「25.80」分行的情况。
        val items = raw
        assertTrue(items.contains("¥"))
        assertTrue(items.contains("25.80"))

        val outcome = pipeline.process(
            Samples.snapshot(Packages.WECHAT, items)
        )
        assertTrue(outcome is BillPipeline.Outcome.Draft)
        assertEquals(2580L, (outcome as BillPipeline.Outcome.Draft).draft.priceCents)
    }

    private fun draftOf(pkg: String, items: List<String>) =
        (pipeline.process(Samples.snapshot(pkg, items)) as BillPipeline.Outcome.Draft).draft
}
