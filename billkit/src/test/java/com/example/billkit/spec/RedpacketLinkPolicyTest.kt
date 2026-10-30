package com.example.billkit.spec

import org.junit.Test
import kotlin.test.*

class RedpacketLinkPolicyTest {
    private val now = 1_793_289_600_000L
    private val id = "SYNTH-REDPACKET-00001"
    private fun message(packet: String = id, send: Any = 1, alias: String = packet, amount: String? = null, url: String? = null,
        description: String = "我给你发了一个红包"): String {
        val pay = mutableMapOf<String, Any?>("paymsgid" to alias,
            "nativeurl" to (url ?: "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=$packet&channelid=1"))
        if (amount != null) pay["feedesc"] = amount
        return SpecJson.encode(mapOf("type" to "redPackage", "isSend" to send,
            "content" to SpecJson.encode(mapOf("msg" to mapOf("appmsg" to mapOf("type" to "2001", "title" to "微信红包", "des" to description, "wcpayinfo" to pay)))),
            "cachedPayMoney" to "99999.99", "t" to now))
    }
    private fun receipt(packet: String = id, cents: Any = 1999L, role: String = "sent_total", phase: String = "prepared", adapter: String = RedpacketLinkPolicy.ADAPTER_VERSION) = SpecJson.encode(mapOf(
        "type" to "redPacketReceipt", "packetId" to packet, "amountFen" to cents, "role" to role,
        "phase" to phase, "success" to true, "adapterVersion" to adapter, "observedAt" to now,
    ))
    private fun evidence(raw: String, row: Long = 1L, time: Long = now) =
        assertIs<RedpacketLinkPolicy.Decode.Value>(RedpacketLinkPolicy.decode(raw, row, time)).evidence
    private fun reduce(trigger: RedpacketLinkPolicy.Evidence, vararg rest: RedpacketLinkPolicy.Evidence) =
        RedpacketLinkPolicy.reduce(trigger, rest.toList() + trigger, now)

    @Test fun messageAloneAndPreparedAloneWaitWithoutUsingCache() {
        assertIs<RedpacketLinkPolicy.Decision.Waiting>(reduce(evidence(message())))
        assertIs<RedpacketLinkPolicy.Decision.Waiting>(reduce(evidence(receipt())))
    }
    @Test fun exactPacketJoinsInBothArrivalOrders() {
        val msg = evidence(message(), 1)
        val amount = evidence(receipt(), 2)
        val first = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(amount, msg))
        val second = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(msg, amount))
        assertEquals(1999L, first.receipt.amountFen)
        assertEquals(first, second)
    }
    @Test fun sameAmountDifferentIdsNeverJoin() {
        assertIs<RedpacketLinkPolicy.Decision.Waiting>(reduce(evidence(receipt("SYNTH-OTHER-PACKET-001")), evidence(message())))
    }
    @Test fun senderTotalNeedsOutgoingMessage() {
        assertIs<RedpacketLinkPolicy.Decision.Waiting>(reduce(evidence(receipt()), evidence(message(send = 0))))
        val own = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(evidence(receipt(phase = "detail")), evidence(message())))
        assertEquals("sent_total", own.receipt.role)
    }
    @Test fun personalIncomeRequiresOpenedAndIncomingMessage() {
        val incoming = evidence(message(send = 0))
        val personal = evidence(receipt(cents = 50L, role = "received_personal", phase = "opened"), 2)
        val joined = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(personal, incoming))
        assertEquals(50L, joined.receipt.amountFen)
        assertIs<RedpacketLinkPolicy.Decision.Rejected>(reduce(personal, evidence(message())))
    }
    @Test fun personalIncomeNeverUsesTotalReceipt() {
        val incoming = evidence(message(send = 0), 1)
        val total = evidence(receipt(cents = 300L, phase = "detail"), 2)
        val own = evidence(receipt(cents = 50L, role = "received_personal", phase = "opened"), 3)
        val linked = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(own, incoming, total))
        assertEquals(50L, linked.receipt.amountFen)
    }
    @Test fun wrongPhaseRoleAndUnknownAdapterAreRejected() {
        for (raw in listOf(receipt(role = "received_personal"), receipt(phase = "opened"),
            receipt(role = "group_total", phase = "opened"), receipt(adapter = "unverified"))) {
            assertIs<RedpacketLinkPolicy.Decode.Invalid>(RedpacketLinkPolicy.decode(raw, 1, now))
        }
    }
    @Test fun zeroNegativeFractionalAndUnboundedCentsAreRejected() {
        for (amount in listOf<Any>(0L, -1L, 0.5, "100", 100_000_001L, true)) {
            assertIs<RedpacketLinkPolicy.Decode.Invalid>(RedpacketLinkPolicy.decode(receipt(cents = amount), 1, now))
        }
    }
    @Test fun fractionalDirectionIsNotTruncatedToOutgoing() {
        assertIs<RedpacketLinkPolicy.Decode.Invalid>(RedpacketLinkPolicy.decode(message(send = 1.5), 1, now))
    }
    @Test fun failedCancelledExpiredAndReturnedMessagesCannotBeResurrected() {
        for (description in listOf("红包发送失败", "红包已取消", "红包已撤销", "红包已退回", "红包已退还", "红包已退款", "红包已过期")) {
            assertIs<RedpacketLinkPolicy.Decode.Invalid>(RedpacketLinkPolicy.decode(message(description = description), 1, now))
        }
    }
    @Test fun conflictingAmountsRejectInsteadOfPickingLatest() {
        assertIs<RedpacketLinkPolicy.Decision.Rejected>(reduce(evidence(receipt(cents = 2000L), 3), evidence(message(), 1), evidence(receipt(), 2)))
    }
    @Test fun statedMessageAmountMustAgreeAndMalformedValuesReject() {
        assertIs<RedpacketLinkPolicy.Decision.Rejected>(reduce(evidence(receipt()), evidence(message(amount = "￥20.00"))))
        assertIs<RedpacketLinkPolicy.Decode.Invalid>(RedpacketLinkPolicy.decode(message(amount = "￥0.001"), 1, now))
    }
    @Test fun duplicateMessagesAndReceiptsChooseFirstOriginalRows() {
        val msg = evidence(message(), 1)
        val receipt = evidence(receipt(), 2)
        val linked = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(evidence(receipt(), 4), msg, receipt, evidence(message(), 3)))
        assertEquals(1L, linked.message.rowId)
        assertEquals(2L, linked.receipt.rowId)
    }
    @Test fun staleMessageDoesNotBecomeNewExpenseFromDetail() {
        assertIs<RedpacketLinkPolicy.Decision.Waiting>(reduce(evidence(receipt(phase = "detail")), evidence(message(), time = now - RedpacketLinkPolicy.WINDOW_MS - 1)))
    }
    @Test fun payMessageAliasOnlyWorksWhenSameOriginalProvesIt() {
        val alias = "SYNTH-PAY-MESSAGE-ALIAS"
        val joined = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(evidence(receipt(packet = alias)), evidence(message(alias = alias))))
        assertEquals(id, joined.receipt.packetId)
    }
    @Test fun sameAliasCannotConnectTwoNativePacketIds() {
        val alias = "SYNTH-SHARED-ALIAS-001"
        assertIs<RedpacketLinkPolicy.Decision.Rejected>(reduce(evidence(receipt(alias)), evidence(message(alias = alias), 1),
            evidence(message(packet = "SYNTH-OTHER-PACKET-001", alias = alias), 2)))
    }
    @Test fun duplicateNativeIdAndWrongNativeUrlReject() {
        for (url in listOf("wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=$id&sendid=$id",
            "https://example.com/?sendid=$id", "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=%ZZ")) {
            assertIs<RedpacketLinkPolicy.Decode.Invalid>(RedpacketLinkPolicy.decode(message(url = url), 1, now))
        }
    }
    @Test fun linkedRawKeepsMessageAndOriginalRowEvidenceButExternalReplayRejects() {
        val msg = evidence(message(), 7)
        val amount = evidence(receipt(), 8, now + 1000)
        val linked = assertIs<RedpacketLinkPolicy.Decision.Linked>(reduce(amount, msg))
        val raw = RedpacketLinkPolicy.linkedRaw(linked)
        val root = SpecJson.decodeObject(raw)
        assertEquals("redPackage", root["type"])
        val pair = root["linkedReceipt"] as Map<*, *>
        assertEquals(7L, pair["messageRowId"])
        assertEquals(8L, pair["receiptRowId"])
        assertEquals(now, pair["messageObservedAt"])
        assertIs<RedpacketLinkPolicy.Decode.Invalid>(RedpacketLinkPolicy.decode(raw, 9, now))
    }
}
