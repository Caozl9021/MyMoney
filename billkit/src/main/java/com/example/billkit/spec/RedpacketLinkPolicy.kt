package com.example.billkit.spec

import java.net.URI
import java.net.URLDecoder

/** 原始消息与真实成功金额凭证的严格同单关联。单位固定分，不按时间/金额相似度匹配。 */
object RedpacketLinkPolicy {
    const val WINDOW_MS = 24 * 60 * 60 * 1000L
    private val ID = Regex("[A-Za-z0-9_-]{6,120}")
    sealed interface Evidence { val rowId: Long; val observedAt: Long; val packetId: String }
    data class Message(override val rowId: Long, override val observedAt: Long, override val packetId: String,
        val aliases: Set<String>, val isSend: Int, val raw: String, val statedFen: Long? = null) : Evidence
    data class Receipt(override val rowId: Long, override val observedAt: Long, override val packetId: String,
        val amountFen: Long, val role: String, val phase: String, val adapterVersion: String) : Evidence
    sealed interface Decode {
        data object Other : Decode
        data class Invalid(val reason: String) : Decode
        data class Value(val evidence: Evidence) : Decode
    }
    sealed interface Decision {
        data object Waiting : Decision
        data class Rejected(val reason: String) : Decision
        data class Linked(val message: Message, val receipt: Receipt) : Decision
    }

    fun decode(raw: String, rowId: Long, hostObservedAt: Long): Decode {
        if (raw.length > 65536) return Decode.Other
        val root = runCatching { SpecJson.decodeObject(raw) }.getOrNull() ?: return Decode.Other
        if (root.containsKey("linkedReceipt") || root.containsKey("hostCorrelationSchema")) return Decode.Invalid("external-linked-receipt")
        return when (root["type"]) {
            "redPacketReceipt" -> receipt(root, rowId, hostObservedAt)
            "redPackage" -> message(root, raw, rowId, hostObservedAt)
            else -> Decode.Other
        }
    }

    private fun receipt(root: Map<String, Any?>, rowId: Long, observedAt: Long): Decode {
        val id = (root["packetId"] as? String)?.takeIf(ID::matches) ?: return Decode.Invalid("invalid-packet-id")
        val cents = (root["amountFen"] as? Number)?.let { n ->
            n.toDouble().takeIf { it.isFinite() && it == n.toLong().toDouble() }?.let { n.toLong() }
        }?.takeIf { it in 1..100_000_000 } ?: return Decode.Invalid("invalid-personal-or-total-amount")
        val role = root["role"] as? String
        val phase = root["phase"] as? String
        if (root["success"] != true || !((role == "sent_total" && phase in setOf("prepared", "detail")) ||
                (role == "received_personal" && phase == "opened"))) return Decode.Invalid("unqualified-receipt-role-or-phase")
        val version = (root["adapterVersion"] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: return Decode.Invalid("missing-receipt-adapter")
        if (root.containsKey("currency") && root["currency"] != "CNY") return Decode.Invalid("unsupported-currency")
        return Decode.Value(Receipt(rowId, observedAt, id, cents, role!!, phase!!, version))
    }

    private fun message(root: Map<String, Any?>, raw: String, rowId: Long, observedAt: Long): Decode {
        val content = when (val body = root["content"]) {
            is String -> runCatching { SpecJson.decodeObject(body) }.getOrNull()
            is Map<*, *> -> body
            else -> null
        }
        val msg = content?.get("msg") as? Map<*, *>
        val app = msg?.get("appmsg") as? Map<*, *>
        val pay = app?.get("wcpayinfo") as? Map<*, *> ?: return Decode.Other
        if (app["type"]?.toString() != "2001") return Decode.Other
        val direction = (root["isSend"] as? Number)?.toInt()?.takeIf { it in 0..1 }
            ?: return Decode.Invalid("missing-message-direction")
        val paymsg = (pay["paymsgid"] as? String)?.takeIf(String::isNotBlank)
        val direct = (pay["sendid"] as? String)?.takeIf(String::isNotBlank)
        val native = (pay["nativeurl"] as? String)?.takeIf(String::isNotBlank)
        if (listOfNotNull(paymsg, direct).any { !ID.matches(it) }) return Decode.Invalid("invalid-message-id")
        val nativeId = if (native == null) null else nativeId(native) ?: return Decode.Invalid("invalid-native-packet-id")
        if (direct != null && nativeId != null && direct != nativeId) return Decode.Invalid("conflicting-native-packet-id")
        val id = nativeId ?: direct ?: paymsg ?: return Decode.Other
        val fee = pay["feedesc"] as? String
        val stated = fee?.takeIf(String::isNotBlank)?.let { value ->
            Regex("[¥￥]\\s*((?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?)").matchEntire(value.trim())
                ?.groupValues?.get(1)?.toBigDecimalOrNull()?.movePointRight(2)?.let {
                    runCatching { it.longValueExact() }.getOrNull()
                }
        }
        return Decode.Value(Message(rowId, observedAt, id, listOfNotNull(paymsg, direct, nativeId).toSet(), direction, raw, stated))
    }

    private fun nativeId(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme != "wxpay" || uri.host != "c2cbizmessagehandler" ||
            uri.path !in setOf("/hongbao/receivehongbao", "/hongbao/openhongbao")) return null
        val values = uri.rawQuery?.split('&').orEmpty().map { it.split('=', limit = 2) }
            .filter { URLDecoder.decode(it[0], "UTF-8") == "sendid" }
        if (values.size != 1 || values.single().size != 2) return null
        URLDecoder.decode(values.single()[1], "UTF-8").takeIf(ID::matches)
    }.getOrNull()

    fun reduce(trigger: Evidence, evidence: List<Evidence>, now: Long): Decision {
        val recent = evidence.filter { it.observedAt in (now - WINDOW_MS)..(now + 60_000) }
        if (trigger.observedAt !in (now - WINDOW_MS)..(now + 60_000)) return Decision.Rejected("stale-packet-evidence")
        val messages = recent.filterIsInstance<Message>()
        // 一个paymsg别名不能把两个不同native packet绑成一笔。
        val aliases = messages.flatMap { message -> message.aliases.map { it to message.packetId } }
            .groupBy({ it.first }, { it.second })
        val relevant = messages.filter { it.packetId == trigger.packetId || trigger.packetId in it.aliases }
        if (relevant.isEmpty()) return Decision.Waiting
        val packetIds = relevant.map { it.packetId }.toSet()
        if (packetIds.size != 1 || relevant.any { m -> m.aliases.any { aliases[it].orEmpty().distinct().size > 1 } }) {
            return Decision.Rejected("ambiguous-packet-alias")
        }
        val canonical = packetIds.single()
        val ids = relevant.flatMap { it.aliases + it.packetId }.toSet()
        val receipts = recent.filterIsInstance<Receipt>().filter { it.packetId in ids }
        if (receipts.isEmpty()) return Decision.Waiting
        val role = if (trigger is Receipt) trigger.role else receipts.last().role
        val qualified = receipts.filter { it.role == role }
        if (qualified.map { it.amountFen }.distinct().size != 1) return Decision.Rejected("conflicting-receipt-amount")
        val message = if (role == "sent_total") relevant.filter { it.isSend == 1 }.minByOrNull { it.rowId }
            else {
                if (relevant.any { it.isSend == 1 }) return Decision.Rejected("self-packet-receive-not-supported")
                relevant.filter { it.isSend == 0 }.minByOrNull { it.rowId }
            } ?: return Decision.Waiting
        val receipt = qualified.minByOrNull { it.rowId } ?: return Decision.Waiting
        if (role == "sent_total" && message.statedFen != null && message.statedFen != receipt.amountFen) {
            return Decision.Rejected("message-receipt-amount-conflict")
        }
        return Decision.Linked(message.copy(packetId = canonical), receipt.copy(packetId = canonical))
    }

    fun linkedRaw(linked: Decision.Linked): String {
        val root = SpecJson.decodeObject(linked.message.raw)!!.toMutableMap()
        root["hostCorrelationSchema"] = 1
        root["linkedReceipt"] = mapOf("packetId" to linked.message.packetId, "amountFen" to linked.receipt.amountFen,
            "role" to linked.receipt.role, "phase" to linked.receipt.phase, "success" to true,
            "observedAt" to linked.receipt.observedAt, "messageObservedAt" to linked.message.observedAt,
            "adapterVersion" to linked.receipt.adapterVersion,
            "messageRowId" to linked.message.rowId, "receiptRowId" to linked.receipt.rowId)
        return SpecJson.encode(root)
    }
}
