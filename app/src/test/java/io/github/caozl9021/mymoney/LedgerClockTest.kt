package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.ui.nextLedgerMidnightDelay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerClockTest {
    private fun midnight(date: String, zone: ZoneId) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun ordinaryDayWaitsUntilCalendarMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-24T15:59:59.500Z").toEpochMilli()
        assertEquals(500L, nextLedgerMidnightDelay(now, zone))
    }

    @Test fun daylightSavingSpringDayHasTwentyThreeHours() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(23L * 60 * 60 * 1000, nextLedgerMidnightDelay(midnight("2026-03-08", zone), zone))
    }

    @Test fun daylightSavingFallDayHasTwentyFiveHours() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(25L * 60 * 60 * 1000, nextLedgerMidnightDelay(midnight("2026-11-01", zone), zone))
    }

    @Test fun zoneChangeRecomputesTheLocalBoundaryAtTheSameInstant() {
        val now = Instant.parse("2026-10-24T15:00:00Z").toEpochMilli()
        assertEquals(60L * 60 * 1000, nextLedgerMidnightDelay(now, ZoneId.of("Asia/Shanghai")))
        assertEquals(9L * 60 * 60 * 1000, nextLedgerMidnightDelay(now, ZoneId.of("UTC")))
    }

    @Test fun zoneWithSkippedMidnightUsesItsFirstValidTime() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = Instant.parse("2018-11-04T02:30:00Z").toEpochMilli()
        assertEquals(30L * 60 * 1000, nextLedgerMidnightDelay(now, zone))
    }

    @Test fun exactMidnightSchedulesTheFollowingDayWithoutBusyLoop() {
        val zone = ZoneId.of("UTC")
        assertEquals(24L * 60 * 60 * 1000, nextLedgerMidnightDelay(midnight("2026-10-25", zone), zone))
    }
}
