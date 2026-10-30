package io.github.mymoney.data

import java.time.LocalDate
import java.time.ZoneId

/** A literal merchant/note search combined with an inclusive/exclusive time interval. */
data class LedgerQuery(
    val keyword: String = "",
    val fromMillis: Long = Long.MIN_VALUE,
    val toMillis: Long = Long.MAX_VALUE,
    val category: String? = null,
    val type: String? = null,
) {
    val likePattern: String
        get() = buildString {
            append('%')
            keyword.trim().forEach { character ->
                if (character == '\\' || character == '%' || character == '_') append('\\')
                append(character)
            }
            append('%')
        }
}

/** Transfers count as entries but never contribute to income, expense or refund. */
data class BillQuerySummary(val count: Int, val expense: Long, val income: Long, val refund: Long) {
    val balance: Long get() = income - expense + refund
}

data class LedgerDateWindow(val fromMillis: Long, val toMillis: Long)

fun ledgerDateWindow(
    start: LocalDate,
    endInclusive: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): LedgerDateWindow {
    require(!endInclusive.isBefore(start)) { "End date must not precede start date" }
    return LedgerDateWindow(
        fromMillis = start.atStartOfDay(zone).toInstant().toEpochMilli(),
        toMillis = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}
