package io.github.mymoney.data

import io.github.mymoney.ui.LedgerTotals
import io.github.mymoney.ui.ledgerTotals
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatisticsPeriod { WEEK, MONTH }

/** Local calendar boundaries, never a fixed number of milliseconds per day. */
data class StatisticsRange(
    val period: StatisticsPeriod,
    val startDate: LocalDate,
    val endExclusiveDate: LocalDate,
    val zone: ZoneId,
    val monthStartDay: Int = 1,
) {
    init { require(startDate < endExclusiveDate) }

    val fromMillis: Long get() = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val toMillis: Long get() = endExclusiveDate.atStartOfDay(zone).toInstant().toEpochMilli()

    fun shift(amount: Long): StatisticsRange = when (period) {
        StatisticsPeriod.WEEK -> containing(startDate.plusWeeks(amount), period, zone, monthStartDay)
        StatisticsPeriod.MONTH -> LedgerMonthPeriod.containing(startDate, monthStartDay, zone).shift(amount).let {
            StatisticsRange(period, it.startDate, it.endExclusiveDate, zone, monthStartDay)
        }
    }

    companion object {
        fun containing(
            date: LocalDate,
            period: StatisticsPeriod,
            zone: ZoneId = ZoneId.systemDefault(),
            monthStartDay: Int = 1,
        ): StatisticsRange {
            require(monthStartDay in 1..31)
            if (period == StatisticsPeriod.MONTH) {
                val month = LedgerMonthPeriod.containing(date, monthStartDay, zone)
                return StatisticsRange(period, month.startDate, month.endExclusiveDate, zone, monthStartDay)
            }
            val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val end = start.plusWeeks(1)
            return StatisticsRange(period, start, end, zone)
        }
    }
}

data class StatisticsDay(
    val date: LocalDate,
    val expenseCents: Long,
    val incomeCents: Long,
    val refundCents: Long,
    val transferCents: Long,
    val expenseCount: Int,
    val incomeCount: Int,
    val refundCount: Int,
    val transferCount: Int,
) {
    fun amount(type: String): Long = when (type) {
        "EXPENSE" -> expenseCents
        "INCOME" -> incomeCents
        "REFUND" -> refundCents
        "TRANSFER" -> transferCents
        else -> 0L
    }

    fun count(type: String): Int = when (type) {
        "EXPENSE" -> expenseCount
        "INCOME" -> incomeCount
        "REFUND" -> refundCount
        "TRANSFER" -> transferCount
        else -> 0
    }
}

data class StatisticsGroup(val name: String, val cents: Long, val count: Int, val ratio: Double)

class StatisticsSummary internal constructor(
    val range: StatisticsRange,
    val totals: LedgerTotals,
    val transferCents: Long,
    val transferCount: Int,
    val count: Int,
    val days: List<StatisticsDay>,
    private val categoryGroups: Map<String, List<StatisticsGroup>>,
    private val accountGroups: Map<String, List<StatisticsGroup>>,
) {
    /** Separate type denominators keep refunds and transfers out of expense shares. */
    fun categories(type: String): List<StatisticsGroup> = categoryGroups[type].orEmpty()
    fun accounts(type: String): List<StatisticsGroup> = accountGroups[type].orEmpty()
}

/** Defensively apply the same half-open interval as the DAO, including in standalone callers. */
fun statistics(bills: List<BillEntity>, range: StatisticsRange): StatisticsSummary {
    val from = range.fromMillis
    val to = range.toMillis
    val included = bills.filter { it.timeMillis >= from && it.timeMillis < to }
    val dated = included.groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(range.zone).toLocalDate() }
    val days = generateSequence(range.startDate) { it.plusDays(1) }
        .takeWhile { it < range.endExclusiveDate }
        .map { date ->
            val entries = dated[date].orEmpty()
            val totals = ledgerTotals(entries)
            StatisticsDay(
                date = date,
                expenseCents = totals.expense,
                incomeCents = totals.income,
                refundCents = totals.refund,
                transferCents = entries.filter { it.type == "TRANSFER" }.sumOf { it.priceCents },
                expenseCount = entries.count { it.type == "EXPENSE" },
                incomeCount = entries.count { it.type == "INCOME" },
                refundCount = entries.count { it.type == "REFUND" },
                transferCount = entries.count { it.type == "TRANSFER" },
            )
        }.toList()
    val chartTypes = listOf("EXPENSE", "INCOME", "REFUND")
    fun groups(type: String, label: (BillEntity) -> String): List<StatisticsGroup> {
        val entries = included.filter { it.type == type }
        val total = entries.sumOf { it.priceCents }
        return entries.groupBy(label).map { (name, group) ->
            val cents = group.sumOf { it.priceCents }
            StatisticsGroup(name, cents, group.size, if (total > 0L) cents.toDouble() / total.toDouble() else 0.0)
        }.sortedWith(compareByDescending<StatisticsGroup> { it.cents }.thenBy { it.name })
    }
    return StatisticsSummary(
        range = range,
        totals = ledgerTotals(included),
        transferCents = days.sumOf { it.transferCents },
        transferCount = days.sumOf { it.transferCount },
        count = included.size,
        days = days,
        categoryGroups = chartTypes.associateWith { type -> groups(type) { it.category?.takeIf(String::isNotBlank) ?: "未分类" } },
        accountGroups = chartTypes.associateWith { type -> groups(type) { it.account?.takeIf(String::isNotBlank) ?: "未知账户" } },
    )
}
