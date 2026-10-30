package com.example.billkit.spec

import org.junit.Test
import kotlin.test.*

/** 本App采集契约和原创规则的独立合成回放，不表示真实目标App Hook已实测。 */
class SpecHookPayloadTest {
    private val engine = originalEngine()
    private val t = 1793289600000L
    private fun payment(source: String = "微信支付", description: String = "收款方：测试店\n使用零钱支付¥19.99\n交易状态支付成功，对方已收款") =
        SpecHookPayload.wechatMsgBox(description, "已支付¥19.99", "微信支付", source, t)

    @Test fun `明确成功付款保留金额支付方式且不伪造交易时间`() {
        val hit = assertNotNull(engine.parse("com.tencent.mm", "DATA", payment()))
        assertEquals(1999L, hit.bill.priceCents)
        assertEquals("测试店", hit.bill.shopName)
        assertEquals("零钱", hit.bill.accountNameFrom)
        assertEquals(0L, hit.bill.time)
        assertEquals(t, hit.bill.observedAt)
        assertFalse(hit.bill.hasExplicitTime)
    }
    @Test fun `缺少可信来源或完成状态不命中`() {
        assertNull(engine.parse("com.tencent.mm", "DATA", payment(source = "其他公众号")))
        assertNull(engine.parse("com.tencent.mm", "DATA", payment(description = "使用零钱支付¥19.99")))
        assertNull(engine.parse("com.tencent.mm", "DATA", payment(description = "支付失败，金额¥19.99")))
    }
    @Test fun `商户必须有明确分隔符不把收款方备注当商户`() {
        val hit = assertNotNull(engine.parse("com.tencent.mm", "DATA", payment(description = "收款方备注测试\n使用零钱支付¥19.99\n交易状态支付成功，对方已收款")))
        assertEquals("", hit.bill.shopName)
    }
    @Test fun `标题和正文金额冲突拒绝`() {
        assertNull(engine.parse("com.tencent.mm", "DATA", payment(description = "使用零钱支付¥20.00\n交易状态支付成功，对方已收款")))
    }
    @Test fun `mMap组装保留输入字段`() {
        val m = SpecJson.decodeObject(payment())["mMap"] as Map<*, *>
        assertEquals("微信支付", m["source"])
        assertEquals(t, (m["t"] as Number).toLong())
        assertTrue(m.containsKey("cachedPayShop"))
    }
    @Test fun `结构化转账和红包保留形态但不冒充已支持`() {
        for (type in listOf("transfer", "redPackage")) {
            val raw = SpecHookPayload.wechatStructured(type, 1, "{}", "测试用户", t, cachedPayMoney = "100.00")
            assertTrue(SpecJson.decodeObject(raw)["content"] is String)
            assertNull(engine.parse("com.tencent.mm", "DATA", raw))
        }
    }
    @Test fun `群收款结构原样保留但不猜金额`() {
        val raw = SpecHookPayload.wechatGroupCollection("测试群收款", 1, "group", t)
        assertTrue(SpecJson.decodeObject(raw)["content"] is Map<*, *>)
        assertNull(engine.parse("com.tencent.mm", "DATA", raw))
    }
    @Test fun `支付宝拆包保留每条而坏JSON不抛`() {
        assertEquals(2, SpecHookPayload.alipaySplit("[{\"pl\":\"one\"},{\"pl\":\"two\"}]").size)
        assertTrue(SpecHookPayload.alipaySplit("bad").isEmpty())
        assertTrue(SpecHookPayload.alipaySplit(null).isEmpty())
        assertEquals(1, SpecHookPayload.alipaySplit("[{},{},{}]", maxItems = 1).size)
    }
    @Test fun `去重摘要稳定且不等于原文`() {
        val key = SpecHookPayload.wechatDedupKey("合成付款说明")
        assertEquals(64, key.length)
        assertEquals(key, SpecHookPayload.wechatDedupKey("合成付款说明"))
        assertNotEquals(key, SpecHookPayload.wechatDedupKey("另一笔说明"))
    }
}
