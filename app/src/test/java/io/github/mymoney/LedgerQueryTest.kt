package io.github.mymoney

import io.github.mymoney.data.BillQuerySummary
import io.github.mymoney.data.LedgerQuery
import io.github.mymoney.data.ledgerDateWindow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LedgerQueryTest {
    @Test fun literalLikeMetacharactersAreEscapedAfterTrimming() {
        assertEquals("%咖啡\\%\\_\\\\店%", LedgerQuery("  咖啡%_\\店\n").likePattern)
        assertEquals("%a\\\\\\%b\\_c%", LedgerQuery("a\\%b_c").likePattern)
    }

    @Test fun emptyAndWhitespaceSearchBothMatchAllIncludingMissingFields() {
        assertEquals("%%", LedgerQuery().likePattern)
        assertEquals("%%", LedgerQuery(" \t\n ").likePattern)
        assertEquals("%a b%", LedgerQuery(" a b ").likePattern)
    }

    @Test fun categoriesKeepAllDistinctFromUnclassifiedAndTypeIsPreserved() {
        assertNull(LedgerQuery().category)
        assertEquals("", LedgerQuery(category = "").category)
        assertEquals("历史分类", LedgerQuery(category = "历史分类").category)
        assertEquals("REFUND", LedgerQuery(type = "REFUND").type)
        assertEquals(Long.MIN_VALUE, LedgerQuery().fromMillis)
        assertEquals(Long.MAX_VALUE, LedgerQuery().toMillis)
    }

    @Test fun springDstDayUsesTwentyThreeHours() {
        val day = LocalDate.of(2026, 3, 8)
        val range = ledgerDateWindow(day, day, ZoneId.of("America/New_York"))
        assertEquals(23 * 3_600_000L, range.toMillis - range.fromMillis)
    }

    @Test fun autumnDstDayUsesTwentyFiveHours() {
        val day = LocalDate.of(2026, 11, 1)
        val range = ledgerDateWindow(day, day, ZoneId.of("America/New_York"))
        assertEquals(25 * 3_600_000L, range.toMillis - range.fromMillis)
    }

    @Test fun leapDayAndMonthCrossingUseNextLocalMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val range = ledgerDateWindow(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1), zone)
        assertEquals(LocalDate.of(2024, 2, 29).atStartOfDay(zone).toInstant().toEpochMilli(), range.fromMillis)
        assertEquals(LocalDate.of(2024, 3, 2).atStartOfDay(zone).toInstant().toEpochMilli(), range.toMillis)
        assertEquals(48 * 3_600_000L, range.toMillis - range.fromMillis)
    }

    @Test fun sameInstantCanFallOnDifferentLocalDays() {
        val day = LocalDate.of(2026, 10, 25)
        val shanghai = ledgerDateWindow(day, day, ZoneId.of("Asia/Shanghai"))
        val utc = ledgerDateWindow(day, day, ZoneId.of("UTC"))
        assertEquals(8 * 3_600_000L, utc.fromMillis - shanghai.fromMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invertedDateRangeIsRejected() {
        ledgerDateWindow(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 1))
    }

    @Test fun balancePreservesRefundAsIndependentOfIncome() {
        val summary = BillQuerySummary(count = 4, expense = 10000, income = 2000, refund = 1500)
        assertEquals(-6500L, summary.balance)
        assertEquals(2000L, summary.income)
        assertEquals(1500L, summary.refund)
        assertEquals(0L, BillQuerySummary(1, 0, 0, 0).balance)
    }
}
