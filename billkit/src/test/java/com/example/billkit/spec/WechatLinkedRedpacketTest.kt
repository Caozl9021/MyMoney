package com.example.billkit.spec

import org.junit.Test
import kotlin.test.*

/** 联合输入由宿主验证后构造；成功金额仍为独立合成值。 */
class WechatLinkedRedpacketTest {
    private val engine = originalEngine()
    private val now = 1793289600000L
    private val id = "SYNTH-LINKED-PACKET-001"
    private fun raw(send: Int = 1, modify: (MutableMap<String, Any?>, MutableMap<String, Any?>) -> Unit = { _, _ -> }): String {
        val pay = mutableMapOf<String, Any?>("nativeurl" to "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=$id&channelid=1", "paymsgid" to id)
        val receipt = mutableMapOf<String, Any?>("packetId" to id, "amountFen" to 1999, "role" to if (send == 1) "sent_total" else "received_personal", "phase" to if (send == 1) "prepared" else "opened", "success" to true, "observedAt" to now + 5000, "messageObservedAt" to now, "adapterVersion" to "wx-8.0.79-3200-v1", "messageRowId" to 1, "receiptRowId" to 2)
        modify(pay, receipt)
        val app = mapOf("type" to "2001", "title" to "微信红包", "des" to "我给你发了一个红包，赶紧去拆!", "wcpayinfo" to pay)
        return SpecJson.encode(mapOf("type" to "redPackage", "isSend" to send, "content" to SpecJson.encode(mapOf("msg" to mapOf("appmsg" to app))), "hostCorrelationSchema" to 1, "linkedReceipt" to receipt, "cachedPayMoney" to "999.99", "t" to now + 99000))
    }
    private fun parse(raw: String) = engine.parse("com.tencent.mm", "DATA", raw)

    @Test fun `prepared及detail同单输出相同支出身份且不用后到观察时间改期`() {
        val prepared = assertNotNull(parse(raw()))
        val detail = assertNotNull(parse(raw { _, r -> r["phase"] = "detail"; r["observedAt"] = now + 60000 }))
        assertEquals("Expend", prepared.bill.type)
        assertEquals(1999L, prepared.bill.priceCents)
        assertEquals("hongbao_$id", prepared.bill.orderId)
        assertEquals(prepared.bill.orderId, detail.bill.orderId)
        assertEquals(now, prepared.bill.observedAt)
        assertFalse(prepared.bill.hasExplicitTime)
        assertEquals(0L, prepared.bill.time)
    }
    @Test fun `个人领取只采用个人额不被原消息缓存或总额污染`() {
        val received = assertNotNull(parse(raw(0) { pay, r -> pay["totalAmount"] = 30000; r["amountFen"] = 50 }))
        assertEquals("Income", received.bill.type)
        assertEquals(50L, received.bill.priceCents)
        assertNull(parse(raw(0) { _, r -> r["role"] = "sent_total" }))
        assertNull(parse(raw(0) { _, r -> r["phase"] = "detail" }))
    }
    @Test fun `native单号重复或与direct冲突拒绝`() {
        assertNull(parse(raw { pay, _ -> pay["nativeurl"] = "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=$id&sendid=$id" }))
        assertNull(parse(raw { pay, _ -> pay["sendid"] = "SYNTH-DIFFERENT-ID" }))
        assertNull(parse(raw { pay, _ -> pay["nativeurl"] = "https://example.com/?sendid=$id" }))
        assertNull(parse(raw { pay, _ -> pay["nativeurl"] = "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=%ZZ" }))
    }
    @Test fun `联合金额必须整数分且与可见发送金额一致`() {
        for (amount in listOf(0, -1, 100000001, 0.01, "1999")) assertNull(parse(raw { _, r -> r["amountFen"] = amount }))
        assertNull(parse(raw { pay, _ -> pay["feedesc"] = "￥20.00" }))
        assertNull(parse(raw { pay, _ -> pay["feedesc"] = "USD19.99" }))
        assertNotNull(parse(raw { pay, _ -> pay["feedesc"] = "￥19.99" }))
    }
    @Test fun `来源行及适配身份缺失或未知拒绝`() {
        assertNull(parse(raw { _, r -> r.remove("messageRowId") }))
        assertNull(parse(raw { _, r -> r["messageRowId"] = 2 }))
        assertNull(parse(raw { _, r -> r["messageObservedAt"] = 1.5 }))
        assertNull(parse(raw { _, r -> r["adapterVersion"] = "unknown" }))
    }
}
