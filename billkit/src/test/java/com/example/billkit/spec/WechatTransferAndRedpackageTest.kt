package com.example.billkit.spec

import org.junit.Test
import kotlin.test.*

/** 现场转账的脱敏结构及独立资金方向反例；红包当前仅合成成功凭证。 */
class WechatTransferAndRedpackageTest {
    private val engine = originalEngine()
    private val now = 1793289600000L
    private val id = "SYNTH-TRANSFER-COMPLETE-001"
    private fun transfer(send: Int, subtype: String = "1", description: String = "收到转账￥0.01元。如需收钱，请点此升级至最新版本", identifier: String = id, amount: String = "￥0.01", time: Any? = (now / 1000).toString(), payId: String = identifier): String {
        val pay = mapOf("paysubtype" to subtype, "feedesc" to amount, "transferid" to identifier, "paymsgid" to payId, "begintransfertime" to time)
        val app = mapOf("type" to "2000", "title" to "微信转账", "des" to description, "wcpayinfo" to pay)
        return SpecJson.encode(mapOf("type" to "transfer", "isSend" to send, "content" to SpecJson.encode(mapOf("msg" to mapOf("appmsg" to app))), "t" to now))
    }
    private fun packet(send: Int, title: String, description: String, money: String? = "￥19.99", identifier: String = "SYNTH-REDPACKET-001", cached: String = "999.99", time: Any? = null): String {
        val pay = mutableMapOf<String, Any?>("sendid" to identifier)
        if (money != null) pay["feedesc"] = money
        if (time != null) pay["transactionTime"] = time
        val app = mapOf("type" to "2001", "title" to title, "des" to description, "wcpayinfo" to pay)
        return SpecJson.encode(mapOf("type" to "redPackage", "isSend" to send, "content" to SpecJson.encode(mapOf("msg" to mapOf("appmsg" to app))), "t" to now, "cachedPayMoney" to cached))
    }
    private fun parse(raw: String) = engine.parse("com.tencent.mm", "DATA", raw)

    @Test fun `脱敏现场发送转账是支出而收款邀请不是收入`() {
        val captured = SpecSamples.load().first { it.evidence == "local-device-20261029-app-data-27" }
        val hit = assertNotNull(parse(captured.data))
        assertEquals("Expend", hit.bill.type)
        assertEquals(1L, hit.bill.priceCents)
        assertTrue(hit.bill.hasExplicitTime)
        for (s in SpecSamples.load().filter { it.evidence in setOf("local-device-20261029-app-data-29", "local-device-20261029-app-data-33") }) assertNull(parse(s.data))
    }
    @Test fun `两笔转账每笔四回调不串成收入且身份相同`() {
        val keys = mutableSetOf<String>()
        for (identifier in listOf("SYNTH-TRANSFER-ONE-001", "SYNTH-TRANSFER-TWO-002")) {
            val hits = listOf(1, 1, 0, 0).mapNotNull { parse(transfer(it, identifier = identifier)) }
            assertEquals(2, hits.size)
            assertTrue(hits.all { it.bill.type == "Expend" && it.bill.priceCents == 1L })
            assertEquals(1, hits.map { it.bill.orderId }.toSet().size)
            keys += hits.first().bill.orderId
        }
        assertEquals(2, keys.size)
    }
    @Test fun `本人确认收入镜像副本不会变成支出`() {
        val owner = assertNotNull(parse(transfer(1, "3", "本人已确认收款")))
        assertEquals("Income", owner.bill.type)
        assertNull(parse(transfer(0, "3", "本人已确认收款")))
    }
    @Test fun `对方确认沿用发送支出身份和业务时间`() {
        val sent = assertNotNull(parse(transfer(1)))
        val confirmed = assertNotNull(parse(transfer(0, "3", "对方已确认收款")))
        assertEquals("Expend", confirmed.bill.type)
        assertEquals("wxtransfer_$id", sent.bill.orderId)
        assertEquals(sent.bill.orderId, confirmed.bill.orderId)
        assertEquals(sent.bill.time, confirmed.bill.time)
        assertNull(parse(transfer(1, "3", "对方已确认收款")))
    }
    @Test fun `无主体或双主体的收款完成语不猜方向`() {
        for (description in listOf("已收款", "已确认收款", "本人已收款\n对方已收款")) for (send in listOf(0, 1)) assertNull(parse(transfer(send, "3", description)))
    }
    @Test fun `转账失败过期及未知阶段拒绝`() {
        for (state in listOf("转账失败", "已取消", "转账已退回", "转账过期", "交易尚未完成")) assertNull(parse(transfer(1, description = state)), state)
        for (subtype in listOf("", "0", "2", "4", "9")) assertNull(parse(transfer(1, subtype)))
    }
    @Test fun `转账非法金额正文冲突和坏时间拒绝`() {
        for (amount in listOf("￥0.00", "￥0.001", "￥-0.01", "USD0.01", "￥0.02")) assertNull(parse(transfer(1, amount = amount)), amount)
        for (time in listOf(null, "", "not-a-time", "0", "-1", 1.5)) assertNull(parse(transfer(1, time = time)), "time=$time")
    }
    @Test fun `转账单号缺失冲突或过长拒绝`() {
        assertNull(parse(transfer(1, identifier = "")))
        assertNull(parse(transfer(1, identifier = "contains space")))
        assertNull(parse(transfer(1, identifier = "1".repeat(118))))
        assertNull(parse(transfer(1, payId = "SYNTH-OTHER-TRANSFER")))
    }
    @Test fun `红包发出和本人领取成功按明确证据分开`() {
        val sent = assertNotNull(parse(packet(1, "红包发送成功", "红包已发出")))
        assertEquals("Expend", sent.bill.type)
        assertEquals(1999L, sent.bill.priceCents)
        assertEquals("hongbao_SYNTH-REDPACKET-001", sent.bill.orderId)
        val received = assertNotNull(parse(packet(0, "红包领取成功", "本人已领取红包")))
        assertEquals("Income", received.bill.type)
        assertEquals(1999L, received.bill.priceCents)
        assertFalse(received.bill.hasExplicitTime)
    }
    @Test fun `红包普通祝福待领取或对方领取不是成功收入`() {
        for (description in listOf("恭喜发财，大吉大利", "红包未领取", "红包待领取", "对方已领取红包", "红包领取失败", "红包已退回")) assertNull(parse(packet(0, "红包领取成功", description)), description)
        assertNull(parse(packet(1, "红包领取成功", "本人已领取红包")))
        assertNull(parse(packet(0, "红包发送成功", "红包已发出")))
    }
    @Test fun `红包不以无关联缓存金额补齐缺失金额`() {
        assertNull(parse(packet(1, "红包发送成功", "红包已发出", money = null, cached = "19.99")))
        val hit = assertNotNull(parse(packet(1, "红包发送成功", "红包已发出", money = "￥0.01", cached = "999.99")))
        assertEquals(1L, hit.bill.priceCents)
    }
    @Test fun `红包金额单号和明确时间也受严格校验`() {
        assertNull(parse(packet(1, "红包发送成功", "红包已发出", money = "￥19.999")))
        assertNull(parse(packet(1, "红包发送成功", "红包已发出", identifier = "")))
        assertNull(parse(packet(1, "红包发送成功", "红包已发出", time = "not-a-time")))
        val hit = assertNotNull(parse(packet(1, "红包发送成功", "红包已发出", time = now)))
        assertEquals(now, hit.bill.time)
        assertTrue(hit.bill.hasExplicitTime)
    }
    @Test fun `可信红包mMap成功凭证无需缓存也可识别`() {
        for ((title, description, type) in listOf(Triple("红包发送成功¥19.99", "红包已发出", "Expend"), Triple("已领取红包¥19.99", "本人已领取红包", "Income"))) {
            val raw = SpecHookPayload.wechatMsgBox(description, title, "微信红包", "微信支付", now)
            assertEquals(type, assertNotNull(parse(raw)).bill.type)
        }
    }
    @Test fun `标准已支付红包凭证需要独立类型证据不误认同名商户`() {
        val paid = "使用零钱支付¥19.99\n交易状态支付成功，对方已收款"
        val typeReceipt = SpecHookPayload.wechatMsgBox("$paid\n交易类型：微信红包", "已支付¥19.99", "微信支付", "微信支付", now)
        assertEquals("maimi_wechat_redpackage_expense_v1", assertNotNull(parse(typeReceipt)).ruleId)
        val displayReceipt = SpecHookPayload.wechatMsgBox(paid, "已支付¥19.99", "微信红包", "微信支付", now)
        assertEquals("maimi_wechat_redpackage_expense_v1", assertNotNull(parse(displayReceipt)).ruleId)
        val ordinaryMerchant = SpecHookPayload.wechatMsgBox(paid, "已支付¥19.99", "红包餐馆", "微信支付", now)
        assertEquals("maimi_wechat_data_expense_v1", assertNotNull(parse(ordinaryMerchant)).ruleId)
        val conflict = SpecHookPayload.wechatMsgBox("使用零钱支付¥20.00\n交易状态支付成功，对方已收款\n用途：发红包", "已支付¥19.99", "微信支付", "微信支付", now)
        assertNull(parse(conflict))
    }

}
