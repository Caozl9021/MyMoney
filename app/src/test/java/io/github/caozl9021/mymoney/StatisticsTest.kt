package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.data.StatisticsPeriod
import io.github.caozl9021.mymoney.data.StatisticsRange
import io.github.caozl9021.mymoney.data.statistics
import io.github.caozl9021.mymoney.ui.ledgerTotals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun range(date: String, period: StatisticsPeriod = StatisticsPeriod.MONTH, zone: ZoneId = shanghai) =
        StatisticsRange.containing(LocalDate.parse(date), period, zone)
    private fun bill(
        millis: Long,
        cents: Long = 1999L,
        type: String = "EXPENSE",
        category: String? = "餐饮",
        account: String? = "现金",
        id: Long = 1L,
    ) = BillEntity(
        id = id, priceCents = cents, type = type, timeMillis = millis,
        merchant = null, method = null, orderTail = null, sourcePkg = "manual",
        strategy = "MANUAL", fingerprint = "test-$id", createdAt = millis,
        category = category, account = account,
    )

    @Test fun mondayWeekCrossesYearAndSundayBelongsToSameWeek() {
        val week = range("2025-01-01", StatisticsPeriod.WEEK)
        assertEquals(LocalDate.parse("2024-12-30"), week.startDate)
        assertEquals(LocalDate.parse("2025-01-06"), week.endExclusiveDate)
        assertEquals(week, range("2025-01-05", StatisticsPeriod.WEEK))
        assertEquals(LocalDate.parse("2025-01-06"), week.shift(1).startDate)
        assertEquals(week, week.shift(-1).shift(1))
    }

    @Test fun weekCrossesMonthAndMonthUsesNaturalLeapCalendar() {
        val week = range("2024-03-01", StatisticsPeriod.WEEK)
        assertEquals(LocalDate.parse("2024-02-26"), week.startDate)
        assertEquals(LocalDate.parse("2024-03-04"), week.endExclusiveDate)
        val february = range("2024-02-29")
        assertEquals(29, statistics(emptyList(), february).days.size)
        assertEquals(LocalDate.parse("2024-03-01"), february.endExclusiveDate)
        assertEquals(LocalDate.parse("2024-01-01"), february.shift(-1).startDate)
        assertEquals(LocalDate.parse("2025-02-01"), february.shift(12).startDate)
    }

    @Test fun rangeIncludesExactStartAndLastMillisecondButExcludesNextPeriod() {
        val month = range("2026-10-24")
        val result = statistics(listOf(
            bill(month.fromMillis - 1, 1), bill(month.fromMillis, 200),
            bill(month.toMillis - 1, 300), bill(month.toMillis, 400),
        ), month)
        assertEquals(2, result.count)
        assertEquals(500L, result.totals.expense)
        assertEquals(200L, result.days.first().expenseCents)
        assertEquals(300L, result.days.last().expenseCents)
        assertEquals(31, result.days.size)
    }

    @Test fun localDatesUseConfiguredZoneRatherThanUtcDates() {
        val month = range("2026-10-24")
        val result = statistics(listOf(
            bill(Instant.parse("2026-09-30T16:00:00Z").toEpochMilli(), 123),
            bill(Instant.parse("2026-10-01T16:00:00Z").toEpochMilli(), 456),
        ), month)
        assertEquals(123L, result.days[0].expenseCents)
        assertEquals(456L, result.days[1].expenseCents)
    }

    @Test fun springDstWeekHas167HoursAndContinuousLocalDates() {
        val zone = ZoneId.of("America/New_York")
        val week = range("2026-03-08", StatisticsPeriod.WEEK, zone)
        assertEquals(167L, (week.toMillis - week.fromMillis) / 3_600_000L)
        val result = statistics(listOf(
            bill(Instant.parse("2026-03-08T06:59:59Z").toEpochMilli(), 100),
            bill(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), 200),
        ), week)
        assertEquals(7, result.days.size)
        assertEquals(300L, result.days.last().expenseCents)
        assertEquals(2, result.days.last().expenseCount)
        assertEquals((0L..6L).map { week.startDate.plusDays(it) }, result.days.map { it.date })
    }

    @Test fun fallDstRepeatedHourIsOneLocalDayAndWeekHas169Hours() {
        val week = range("2026-11-01", StatisticsPeriod.WEEK, ZoneId.of("America/New_York"))
        assertEquals(169L, (week.toMillis - week.fromMillis) / 3_600_000L)
        val result = statistics(listOf(
            bill(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), 100),
            bill(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), 200),
        ), week)
        assertEquals(300L, result.days.last().expenseCents)
        assertEquals(2, result.days.last().expenseCount)
        assertEquals(7, result.days.size)
    }

    @Test fun refundIsIndependentAndTransferDoesNotEnterIncomeExpenseOrShares() {
        val month = range("2026-10-24")
        val bills = listOf(
            bill(month.fromMillis, 1999),
            bill(month.fromMillis, 5000, "INCOME", "工资"),
            bill(month.fromMillis, 2999, "REFUND", "餐饮"),
            bill(month.fromMillis, 100_000, "TRANSFER", "转账"),
        )
        val result = statistics(bills, month)
        assertEquals(ledgerTotals(bills), result.totals)
        assertEquals(1999L, result.totals.expense)
        assertEquals(5000L, result.totals.income)
        assertEquals(2999L, result.totals.refund)
        assertEquals(6000L, result.totals.balance)
        assertEquals(100_000L, result.transferCents)
        assertEquals(1, result.transferCount)
        assertEquals(4, result.count)
        assertEquals(1999L, result.categories("EXPENSE").single().cents)
        assertEquals(2999L, result.categories("REFUND").single().cents)
        assertTrue(result.categories("TRANSFER").isEmpty())
        assertTrue(result.accounts("TRANSFER").isEmpty())
    }

    @Test fun emptyWeekHasSevenZeroDaysWithoutInvalidRatios() {
        val result = statistics(emptyList(), range("2026-10-24", StatisticsPeriod.WEEK))
        assertEquals(0, result.count)
        assertEquals(0L, result.totals.balance)
        assertEquals(7, result.days.size)
        for (type in listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")) {
            assertTrue(result.days.all { it.amount(type) == 0L && it.count(type) == 0 })
            assertTrue(result.categories(type).isEmpty())
            assertTrue(result.accounts(type).isEmpty())
        }
    }

    @Test fun singleRefundCategoryHas100PercentAndExpenseRemainsEmpty() {
        val month = range("2026-10-24")
        val result = statistics(listOf(bill(month.fromMillis, 100, "REFUND")), month)
        assertEquals(1.0, result.categories("REFUND").single().ratio, 0.0)
        assertEquals(1.0, result.accounts("REFUND").single().ratio, 0.0)
        assertTrue(result.categories("EXPENSE").isEmpty())
        assertEquals(100L, result.totals.balance)
    }

    @Test fun groupAmountsCountsAndRatiosShareOneTypeDenominator() {
        val month = range("2026-10-24")
        val result = statistics(listOf(
            bill(month.fromMillis, 100, category = "餐饮", account = "现金"),
            bill(month.fromMillis, 200, category = "餐饮", account = "银行卡"),
            bill(month.fromMillis, 100, category = "交通", account = "银行卡"),
            bill(month.fromMillis, 9999, "REFUND", "餐饮", "现金"),
        ), month)
        val categories = result.categories("EXPENSE")
        assertEquals(listOf("餐饮", "交通"), categories.map { it.name })
        assertEquals(listOf(300L, 100L), categories.map { it.cents })
        assertEquals(listOf(2, 1), categories.map { it.count })
        assertEquals(0.75, categories[0].ratio, 0.0)
        assertEquals(1.0, categories.sumOf { it.ratio }, 0.0)
        val accounts = result.accounts("EXPENSE")
        assertEquals("银行卡", accounts.first().name)
        assertEquals(300L, accounts.first().cents)
        assertEquals(2, accounts.first().count)
        assertEquals(400L, accounts.sumOf { it.cents })
    }

    @Test fun missingAndBlankLabelsUseStableFallbackAndZeroHasFiniteRatio() {
        val month = range("2026-10-24")
        val result = statistics(listOf(
            bill(month.fromMillis, 0, category = null, account = null),
            bill(month.fromMillis, 0, category = " ", account = ""),
        ), month)
        assertEquals("未分类", result.categories("EXPENSE").single().name)
        assertEquals("未知账户", result.accounts("EXPENSE").single().name)
        assertEquals(2, result.categories("EXPENSE").single().count)
        assertEquals(0.0, result.categories("EXPENSE").single().ratio, 0.0)
    }

    @Test fun moreThan500RecordsReconcileEveryCentDayTypeAndGroup() {
        val month = range("2026-10-24")
        val types = listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")
        val bills = (0 until 1205).map { index ->
            val day = month.startDate.plusDays((index % 31).toLong())
            bill(day.atStartOfDay(shanghai).toInstant().toEpochMilli(), (index + 1).toLong(),
                types[index % 4], "分类${index % 3}", "账户${index % 2}", index.toLong() + 1)
        }
        val result = statistics(bills, month)
        assertEquals(1205, result.count)
        assertEquals(ledgerTotals(bills), result.totals)
        types.forEach { type ->
            val expected = bills.filter { it.type == type }
            assertEquals(expected.sumOf { it.priceCents }, result.days.sumOf { it.amount(type) })
            assertEquals(expected.size, result.days.sumOf { it.count(type) })
            if (type != "TRANSFER") {
                assertEquals(expected.sumOf { it.priceCents }, result.categories(type).sumOf { it.cents })
                assertEquals(expected.size, result.categories(type).sumOf { it.count })
                assertEquals(expected.sumOf { it.priceCents }, result.accounts(type).sumOf { it.cents })
                assertEquals(1.0, result.categories(type).sumOf { it.ratio }, 0.0000001)
            }
        }
    }
}
