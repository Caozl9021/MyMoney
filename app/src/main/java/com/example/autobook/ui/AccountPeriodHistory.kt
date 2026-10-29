package com.example.autobook.ui

import com.example.autobook.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal data class AccountHistoryRow(val key: String, val at: Long, val bill: BillEntity? = null,
    val entry: AssetEntry? = null, val balanceAfter: Long? = null)
internal data class AccountHistoryPeriod(val period: LedgerMonthPeriod, val rows: List<AccountHistoryRow>,
    val outflow: Long, val inflow: Long)

/** Visible bill totals exclude principal transfers; the account balance still uses every posting. */
internal fun accountPeriodHistory(account: AssetAccount, bills: List<BillEntity>, entries: List<AssetEntry>,
    today: LocalDate, startDay: Int, zone: ZoneId): List<AccountHistoryPeriod> {
    val current = LedgerMonthPeriod.containing(today, startDay, zone)
    val postings = entries.filter { it.accountId == account.id }.sortedWith(compareBy<AssetEntry> { it.createdAt }.thenBy { it.id })
    val running = mutableMapOf<String, Long>()
    var balance = 0L
    postings.forEach { balance = Math.addExact(balance, it.deltaCents); running[it.id] = balance }
    val byId = postings.associateBy { it.id }
    val reversed = postings.mapNotNull { it.reversalOf }.toSet()
    val activeBills = postings.filter { it.billKey != null && it.id !in reversed && it.reversalOf == null }.associateBy { it.billKey }
    val names = setOf(account.name) + AssetMath.aliases(account)
    val rows = bills.filter { it.type != "TRANSFER" &&
        (it.accountId == account.id || it.accountId == null && it.account in names) }.map {
        AccountHistoryRow("bill-${it.id}", it.timeMillis, bill = it,
            balanceAfter = activeBills[it.assetKey]?.let { posting -> running[posting.id] })
    } + postings.filter { it.kind in setOf("OPENING", "ADJUSTMENT") ||
        it.kind == "REVERSAL" && byId[it.reversalOf]?.kind in setOf("OPENING", "ADJUSTMENT") }.map {
        AccountHistoryRow("entry-${it.id}", it.createdAt, entry = it, balanceAfter = running[it.id])
    }
    val grouped = rows.groupBy { LedgerMonthPeriod.containing(Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate(), startDay, zone).month }
    return (listOf(current.month) + grouped.keys.filter { it != current.month }.sortedDescending()).map { month ->
        val periodRows = grouped[month].orEmpty().sortedWith(compareByDescending<AccountHistoryRow> { it.at }.thenByDescending { it.key })
        val visibleBills = periodRows.mapNotNull { it.bill }
        val out = visibleBills.filter { it.type == "EXPENSE" }.fold(0L) { total, bill -> Math.addExact(total, bill.priceCents) }
        val incoming = visibleBills.filter { it.type == "INCOME" || it.type == "REFUND" }.fold(0L) { total, bill -> Math.addExact(total, bill.priceCents) }
        AccountHistoryPeriod(LedgerMonthPeriod(month, startDay, zone), periodRows, out, incoming)
    }
}
