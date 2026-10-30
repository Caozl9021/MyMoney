package io.github.caozl9021.mymoney.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LedgerFiltersTest {
    @Test fun allDatesUsesUnboundedInterval() {
        val result = ledgerFilterWindow("ALL", "", "", LocalDate.of(2026, 10, 25), ZoneId.of("Asia/Shanghai"))
        assertEquals(Long.MIN_VALUE, result.fromMillis)
        assertEquals(Long.MAX_VALUE, result.toMillis)
    }

    @Test fun lastMonthAtYearBoundaryUsesPreviousDecember() {
        val result = ledgerFilterWindow("LAST_MONTH", "", "", LocalDate.of(2026, 1, 1), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2025-12-01T00:00:00Z").toEpochMilli(), result.fromMillis)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), result.toMillis)
    }

    @Test fun leapFebruaryIncludesLastDayAndEndsAtMarch() {
        val result = ledgerFilterWindow("MONTH", "", "", LocalDate.of(2024, 2, 10), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2024-02-01T00:00:00Z").toEpochMilli(), result.fromMillis)
        assertEquals(Instant.parse("2024-03-01T00:00:00Z").toEpochMilli(), result.toMillis)
    }

    @Test fun todayFollowsLocalMidnightAcrossDaylightSaving() {
        val result = ledgerFilterWindow("TODAY", "", "", LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), result.fromMillis)
        assertEquals(23L * 60 * 60 * 1000, result.toMillis - result.fromMillis)
    }

    @Test fun customRangeIncludesBothSelectedLocalDates() {
        val result = ledgerFilterWindow("CUSTOM", "2026-10-24", "2026-10-25", LocalDate.of(2026, 11, 1), ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2026-10-23T16:00:00Z").toEpochMilli(), result.fromMillis)
        assertEquals(Instant.parse("2026-10-25T16:00:00Z").toEpochMilli(), result.toMillis)
    }
}
