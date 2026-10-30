package com.example.mymoney.data

import com.example.billkit.spec.RedpacketLinkPolicy

/** 只在新红包原始事件到达时，同本App24小时内已留存的原始事件做精确ID关联。 */
internal object RedpacketCorrelationStore {
    sealed interface Result {
        data object Other : Result
        data object Waiting : Result
        data class Rejected(val reason: String) : Result
        data class Linked(val raw: String, val messageId: Long, val receiptId: Long) : Result
    }

    suspend fun prepare(db: AppDb, app: String, raw: String, rowId: Long, now: Long): Result {
        if (app != "com.tencent.mm") return Result.Other
        val decoded = RedpacketLinkPolicy.decode(raw, rowId, now)
        val trigger = when (decoded) {
            RedpacketLinkPolicy.Decode.Other -> return Result.Other
            is RedpacketLinkPolicy.Decode.Invalid -> return Result.Rejected(decoded.reason)
            is RedpacketLinkPolicy.Decode.Value -> decoded.evidence
        }
        val evidence = mutableListOf<RedpacketLinkPolicy.Evidence>()
        var after = 0L
        var rows = 0
        var characters = 0L
        while (true) {
            val page = db.rules().correlationData(app, now - RedpacketLinkPolicy.WINDOW_MS, now + 60_000, after, 128)
            for (row in page) {
                characters += row.data.length
                if (characters > 4 * 1024 * 1024) return Result.Rejected("packet-evidence-size-limit")
                val item = RedpacketLinkPolicy.decode(row.data, row.id, row.time)
                if (item is RedpacketLinkPolicy.Decode.Value) evidence += item.evidence
            }
            rows += page.size
            if (page.size < 128) break
            if (rows >= 8192) return Result.Rejected("packet-evidence-scan-limit")
            after = page.last().id
        }
        return when (val decision = RedpacketLinkPolicy.reduce(trigger, evidence, now)) {
            RedpacketLinkPolicy.Decision.Waiting -> Result.Waiting
            is RedpacketLinkPolicy.Decision.Rejected -> Result.Rejected(decision.reason)
            is RedpacketLinkPolicy.Decision.Linked -> Result.Linked(RedpacketLinkPolicy.linkedRaw(decision),
                decision.message.rowId, decision.receipt.rowId)
        }
    }
}
