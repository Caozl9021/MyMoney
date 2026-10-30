package com.example.billkit.spec

/** Narrow field contract for the audited 8.0.79/3200 business callbacks; no UI amount guessing. */
object WechatRedpacketReceiptAdapter {
    const val VERSION = "wx-8.0.79-3200-v1"
    const val PREFIX = "com.tencent.mm.plugin.luckymoney.model."
    val classes = setOf("j6", "m6", "h6", "i6", "x5").map { PREFIX + it }.toSet()
    private val idPattern = Regex("[A-Za-z0-9_-]{6,120}")
    data class Request(val packetId: String?, val amountFen: Long?)

    fun request(className: String, fields: Map<*, *>): Request? {
        if (className !in classes) return null
        val id = fields["sendId"]?.let(::id)
        val amount = when (className) {
            PREFIX + "j6" -> positiveFen(fields["totalAmount"])
            PREFIX + "m6" -> positiveFen(fields["amount"])
            else -> null
        }
        return Request(id, amount)
    }

    /** A prepared amount remains evidence only. The host must also match an actual sent message. */
    fun response(className: String, errorCode: Int, fields: Map<String, Any?>,
                 request: Request?, observedAt: Long): String? {
        if (className !in classes || errorCode != 0 || observedAt <= 0) return null
        val responseId = fields["sendId"]?.let(::id)
        // Present but malformed identifiers never silently fall back to a different source.
        if (fields.containsKey("sendId") && responseId == null) return null
        if (responseId != null && request?.packetId != null && responseId != request.packetId) return null
        val packetId = responseId ?: request?.packetId ?: return null
        val (amount, role, phase) = when (className) {
            PREFIX + "j6", PREFIX + "m6" -> Triple(request?.amountFen, "sent_total", "prepared")
            PREFIX + "h6", PREFIX + "i6" -> Triple(positiveFen(fields["amount"]), "received_personal", "opened")
            PREFIX + "x5" -> {
                if (strictInteger(fields["isSender"]) != 1L) return null
                Triple(positiveFen(fields["totalAmount"]), "sent_total", "detail")
            }
            else -> return null
        }
        if (amount == null) return null
        return SpecJson.encode(mapOf("type" to "redPacketReceipt", "packetId" to packetId,
            "amountFen" to amount, "role" to role, "phase" to phase, "success" to true,
            "observedAt" to observedAt, "adapterVersion" to VERSION))
    }

    private fun id(value: Any): String? = (value as? String)?.takeIf(idPattern::matches)
    private fun positiveFen(value: Any?): Long? = strictInteger(value)?.takeIf { it in 1..100_000_000 }
    private fun strictInteger(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is String -> value.takeIf { Regex("(?:0|[1-9][0-9]{0,15})").matches(it) }?.toLongOrNull()
        else -> null
    }
}
