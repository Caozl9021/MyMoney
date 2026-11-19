package com.example.billkit.spec

import org.junit.Assert.*
import org.junit.Test

class WechatRedpacketReceiptAdapterTest {
    private val prefix = WechatRedpacketReceiptAdapter.PREFIX
    private val packet = "packet_123456"
    private fun response(name: String, fields: Map<String, Any?>, request: WechatRedpacketReceiptAdapter.Request? = null,
                         code: Int = 0): Map<String, Any?>? = WechatRedpacketReceiptAdapter.response(
        prefix + name, code, fields, request, 1_800_000_000_000)?.let(SpecJson::decodeObject)

    @Test fun preparedCopiesOnlyItsOwnIntegerFenAndCannotAssertCompletion() {
        val request = WechatRedpacketReceiptAdapter.request(prefix + "j6", mapOf("totalAmount" to "1999", "amount" to "999999"))
        val result = response("j6", mapOf("sendId" to packet), request)!!
        assertEquals(1999L, result["amountFen"])
        assertEquals("prepared", result["phase"])
        assertEquals("sent_total", result["role"])
        assertFalse(result.containsKey("isPaid"))
        assertNull(response("j6", mapOf("sendId" to packet, "totalAmount" to 1000)))
    }

    @Test fun openedUsesPersonalTopAmountNeverGroupTotalOrRecipientRecord() {
        val result = response("h6", mapOf("sendId" to packet, "amount" to 50L, "totalAmount" to 300L,
            "record" to listOf(mapOf("receiveAmount" to 250))))!!
        assertEquals(50L, result["amountFen"])
        assertEquals("received_personal", result["role"])
        assertEquals("opened", result["phase"])
        assertNull(response("i6", mapOf("sendId" to packet, "totalAmount" to 300L,
            "record" to listOf(mapOf("receiveAmount" to 50)))))
    }

    @Test fun detailRequiresSenderAndExactPacketIdentity() {
        val req = WechatRedpacketReceiptAdapter.request(prefix + "x5", mapOf("sendId" to packet))
        val fields = mapOf("isSender" to 1, "totalAmount" to 300L)
        val result = response("x5", fields, req)!!
        assertEquals(packet, result["packetId"])
        assertEquals(300L, result["amountFen"])
        assertEquals("sent_total", result["role"])
        assertNull(response("x5", fields + ("isSender" to 0), req))
        assertNull(response("x5", fields + ("sendId" to "packet_654321"), req))
        assertNull(response("x5", fields + ("sendId" to ""), req))
        assertNull(response("x5", fields))
    }

    @Test fun errorsUnknownUnitsMalformedAndOutOfRangeAmountsAreRejected() {
        val valid = mapOf("sendId" to packet, "amount" to 1)
        assertNull(response("h6", valid, code = 1))
        assertNull(response("unknown", valid))
        for (amount in listOf(-1, 0, 100_000_001, "1.5", "01", "1e2", 1.0, Double.NaN, "¥1.00", "100元")) {
            assertNull("must reject $amount", response("h6", valid + ("amount" to amount)))
        }
        assertNull(response("h6", valid + ("sendId" to "invalid id")))
        assertNull(WechatRedpacketReceiptAdapter.request(prefix + "unknown", mapOf("amount" to "1")))
    }

    @Test fun callbackPayloadHasOnlyExplicitAuditedFields() {
        val result = response("i6", mapOf("sendId" to packet, "amount" to "1", "nickName" to "private", "record" to emptyList<Any>()))!!
        assertEquals(setOf("type", "packetId", "amountFen", "role", "phase", "success", "observedAt", "adapterVersion"), result.keys)
        assertEquals(1L, result["amountFen"])
        assertEquals(WechatRedpacketReceiptAdapter.VERSION, result["adapterVersion"])
    }
}
