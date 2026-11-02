package io.github.mymoney.data

import com.example.billkit.BillDraft

/** Rolling capture-time matching, independent of payment-time buckets and UI receipts. */
object CaptureShortWindowPolicy {
    const val WINDOW_MS = 60_000L
    const val RULE = "cross-channel-60s-v1"
    val sources = setOf("PAGE", "HOOK", "NOTIFICATION", "ROOT", "SMS")

    fun logicalSource(source: String): String = if (source == "ROOT") "NOTIFICATION" else source

    fun differentTransaction(row: RecognitionEntity, incoming: BillDraft, aliases: Collection<String>): Boolean {
        val incomingAlias = CaptureMergePolicy.strongAlias(incoming) ?: return false
        val known = aliases + listOfNotNull(CaptureMergePolicy.strongAlias(row.toDraft()))
        return known.isNotEmpty() && incomingAlias !in known
    }

    fun newChannel(source: String, existingSources: Collection<String>): Boolean {
        if (source !in sources) return false
        val existing = existingSources.filter { it in sources }.map(::logicalSource).toSet()
        return existing.isNotEmpty() && logicalSource(source) !in existing
    }

    fun withinWindow(a: Long, b: Long): Boolean =
        a > 0 && b > 0 && (if (a >= b) a - b else b - a) <= WINDOW_MS

    fun matches(row: RecognitionEntity, incoming: BillDraft, source: String,
                existingSources: Collection<String>, receivedAt: Long, aliases: Collection<String>): Boolean =
        row.reviewOf == null && row.sourcePkg == incoming.sourcePkg && row.type == incoming.type.name &&
            row.priceCents == incoming.priceCents &&
            row.protectedFields and (CaptureFields.AMOUNT or CaptureFields.TYPE) == 0L &&
            withinWindow(row.recognizedAt, receivedAt) && newChannel(source, existingSources) &&
            !differentTransaction(row, incoming, aliases) &&
            !(row.hasExplicitTime && incoming.hasExplicitTime && !withinWindow(row.timeMillis, incoming.timeMillis))

    /** UNKNOWN and orphaned CLAIMED receipts need manual recovery, not a second automatic card. */
    fun retryableDelivery(state: String?): Boolean =
        state == null || state == CaptureDeliveryState.SUPPRESSED || state == CaptureDeliveryState.FAILED
}
