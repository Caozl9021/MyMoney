package com.example.billkit.spec

import org.junit.Test
import kotlin.test.*

/** 手写语义边界，独立于夹具生成器；这些输入均为合成，不能代替真机交易覆盖。 */
class RuleSemanticVariantsTest {
    private val engine = originalEngine()
    private val now = 1793289600000L
    private fun wechat(title: String, description: String) = SpecHookPayload.wechatMsgBox(description, title, "微信支付", "微信支付", now)
    private fun alipay(title: String, top: String?, nested: String?, vararg fields: Pair<String, String>): String {
        val content = mutableMapOf<String, Any?>("content" to fields.map { mapOf("title" to it.first, "content" to it.second) })
        if (nested != null) content["money"] = nested
        val pl = mutableMapOf<String, Any?>("title" to title, "content" to SpecJson.encode(content))
        if (top != null) pl["money"] = top
        return SpecJson.encode(listOf(mapOf("pl" to SpecJson.encode(pl), "mct" to now / 1000)))
    }
    private fun sms(body: String, sender: String = "95555") = SpecJson.encode(mapOf("sender" to sender, "body" to body, "t" to now))
    private fun notice(body: String) = SpecJson.encode(mapOf("title" to "交易提醒", "text" to body, "t" to now, "envelopeVerified" to true))

    @Test fun `微信标题换行和正文空白不改变实付金额`() {
        val hit = assertNotNull(engine.parse("com.tencent.mm", "DATA", wechat("已支付\n￥ 19.99", "收款方：测试商户\n使用 零钱 支付 ￥ 19.99\n交易状态：支付成功")))
        assertEquals(1999L, hit.bill.priceCents)
        assertEquals("零钱", hit.bill.accountNameFrom)
        assertEquals("测试商户", hit.bill.shopName)
    }
    @Test fun `微信完成状态兼容冒号和半角标点`() {
        for (state in listOf("交易状态：支付成功", "交易状态 支付成功", "交易状态支付成功,对方已收款")) {
            assertNotNull(engine.parse("com.tencent.mm", "DATA", wechat("已支付¥0.01", "使用零钱支付¥0.01\n$state")))
        }
    }
    @Test fun `微信缺实际完成状态或金额冲突仍拒绝`() {
        assertNull(engine.parse("com.tencent.mm", "DATA", wechat("已支付¥19.99", "使用零钱支付¥19.99\n交易状态：处理中")))
        assertNull(engine.parse("com.tencent.mm", "DATA", wechat("已支付¥19.99", "使用零钱支付¥20.00\n交易状态：支付成功")))
    }
    @Test fun `支付宝主金额仅在嵌套字段也能识别`() {
        val hit = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", null, "10.25", "收款方：" to "测试商户")))
        assertEquals(1025L, hit.bill.priceCents)
        assertEquals("测试商户", hit.bill.shopName)
    }
    @Test fun `支付宝主金额仅在顶层且负号表支出可识别`() {
        val hit = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("支付成功", "-10.25", null, "收款方：" to "测试商户")))
        assertEquals(1025L, hit.bill.priceCents)
        assertEquals("Expend", hit.bill.type)
    }
    @Test fun `支付宝两处金额不一致或非法不降级选一个`() {
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "10.25", "10.26")))
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "10.250", "10.25")))
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "优惠10元", null)))
    }
    @Test fun `支付宝交易成功必须有明确且一致的收支方向`() {
        val expense = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("交易成功", "12.30", null, "收支类型：" to "支出")))
        assertEquals("Expend", expense.bill.type)
        val income = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("交易成功", null, "12.30", "交易类型：" to "收款")))
        assertEquals("Income", income.bill.type)
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("交易成功", "12.30", null)))
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "12.30", null, "收支类型：" to "收入")))
    }
    @Test fun `支付宝退款成功仍独立为退款不是收入`() {
        val hit = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("退款成功", "19.99", null)))
        assertEquals("Refund", hit.bill.type)
    }
    @Test fun `支付宝未知币种和未处理手续费不被静默丢失`() {
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "10.25", null, "币种：" to "JPY")))
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "10.25", null, "手续费：" to "1.00元")))
    }
    @Test fun `self范围外的旧招行消费工资模板均不生成交易`() {
        for (body in listOf(
            "【招商银行】您的信用卡1234于10月29日20:12在测试咖啡店消费人民币26.77元，交易后余额人民币1000.00元。",
            "您尾号1234的借记卡于2026年10月29日工资入账人民币8,000.00元，交易后余额9000.00元。【招商银行】",
            "【招商银行】您尾号1234的信用卡消费人民币19.99元，商户：测试店，余额100.00元。"))
            assertNull(engine.parse("com.android.phone", "DATA", sms(body)))
    }
    @Test fun `招行仅余额信用额度和未知发送方不生成交易`() {
        assertNull(engine.parse("com.android.phone", "DATA", sms("【招商银行】您的信用卡1234可用额度人民币1000.00元。")))
        assertNull(engine.parse("com.android.phone", "DATA", sms("【招商银行】您尾号1234的借记卡消费人民币19.99元。", "10695555")))
    }
    @Test fun `支付宝已知支出和收入句式按方向识别`() {
        val expense = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "NOTICE", notice("你有一笔19.99元的支出")))
        assertEquals(1999L, expense.bill.priceCents)
        assertEquals("Expend", expense.bill.type)
        assertFalse(expense.bill.hasExplicitTime)
        val income = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "NOTICE", notice("你有一笔12.30元的收入。")))
        assertEquals("Income", income.bill.type)
    }
    @Test fun `支付宝通知第二交易和奖励金额不被通用规则吞掉`() {
        assertNull(engine.parse("com.eg.android.AlipayGphone", "NOTICE", notice("你有一笔19.99元的支出，又有一笔3元的支出")))
        assertNull(engine.parse("com.eg.android.AlipayGphone", "NOTICE", notice("你有一笔19.99元的支出，领2元小荷包红包")))
    }
    @Test fun `支付宝明确未完成待确认和未知状态不能被成功标题掩盖`() {
        for (status in listOf("未完成", "待确认", "待完成", "尚未付款", "处理中", "未知状态", "", "收款成功")) {
            assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "19.99", null, "交易状态：" to status)), status)
        }
        for (status in listOf("支付成功", "付款成功", "已完成")) {
            assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "19.99", null, "交易状态：" to status)), status)
        }
    }
    @Test fun `微信未提供交易时间可缺省但提供非法时间必须拒绝`() {
        val valid = wechat("已支付¥19.99", "使用零钱支付¥19.99\n交易状态：支付成功")
        assertFalse(assertNotNull(engine.parse("com.tencent.mm", "DATA", valid)).bill.hasExplicitTime)
        for (bad in listOf(null, "not-a-time", "", "0", 0, -1, 1.5)) {
            val root = SpecJson.decodeObject(valid).toMutableMap()
            val m = (root["mMap"] as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
            m["transactionTime"] = bad
            root["mMap"] = m
            assertNull(engine.parse("com.tencent.mm", "DATA", SpecJson.encode(root)), "bad transactionTime=$bad")
        }
        val root = SpecJson.decodeObject(valid).toMutableMap()
        val m = (root["mMap"] as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
        m["transactionTime"] = now
        root["mMap"] = m
        val hit = assertNotNull(engine.parse("com.tencent.mm", "DATA", SpecJson.encode(root)))
        assertTrue(hit.bill.hasExplicitTime)
        assertEquals(now, hit.bill.time)
    }
    @Test fun `支付宝显式坏时间不得被吞掉变为到达时间`() {
        for (bad in listOf("not-a-time", "", "0", "-1", "1.5", "NaN")) {
            assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "19.99", null, "交易时间戳：" to bad)), bad)
        }
        val hit = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", alipay("付款成功", "19.99", null, "交易时间戳：" to now.toString())))
        assertTrue(hit.bill.hasExplicitTime)
        assertEquals(now, hit.bill.time)
    }
    @Test fun `支付宝已提供非法顶层金额不得用嵌套金额悄悄兜底`() {
        fun withTop(value: Any?): String {
            val payload = alipay("付款成功", null, "19.99")
            val row = (SpecJson.decodeArray(payload).single() as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
            val pl = SpecJson.decodeObject(row["pl"] as String).toMutableMap()
            pl["money"] = value
            row["pl"] = SpecJson.encode(pl)
            return SpecJson.encode(listOf(row))
        }
        for (bad in listOf(null, "", true, mapOf("amount" to "19.99"), "bad19.99", 19.999)) {
            assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", withTop(bad)), "bad money=$bad")
        }
        val hit = assertNotNull(engine.parse("com.eg.android.AlipayGphone", "DATA", withTop(19.99)))
        assertEquals(1999L, hit.bill.priceCents)
    }

    @Test fun `超长结构字段不能截掉尾部失败状态再生成交易`() {
        val raw = alipay("付款成功", "19.99", null)
        val row = (SpecJson.decodeArray(raw).single() as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
        val pl = SpecJson.decodeObject(row["pl"] as String).toMutableMap()
        val content = SpecJson.decodeObject(pl["content"] as String).toMutableMap()
        content["content"] = (0 until 64).map { mapOf("title" to "附加字段$it", "content" to "说明") } +
            mapOf("title" to "交易状态", "content" to "支付失败")
        pl["content"] = SpecJson.encode(content)
        row["pl"] = SpecJson.encode(pl)
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", SpecJson.encode(listOf(row))))
    }
}
