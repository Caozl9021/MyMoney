package io.github.mymoney.ui

import io.github.mymoney.data.StatisticsPeriod
import io.github.mymoney.data.StatisticsRange
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsPeriodLabelTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test fun weekLabelUsesMonthAndOrdinalWithoutYear() {
        // 10/19 是周一，落在 10 月第 4 周（按周一所在月份与月内 7 天序号）。
        val range = StatisticsRange.containing(LocalDate.of(2026, 10, 24), StatisticsPeriod.WEEK, zone)
        assertEquals("10月第4周", statisticsPeriodLabel(StatisticsPeriod.WEEK, range))
    }

    @Test fun weekLabelForWeekStartingOnFirstOfMonthIsFirstWeek() {
        val range = StatisticsRange.containing(LocalDate.of(2026, 10, 1), StatisticsPeriod.WEEK, zone)
        assertEquals(LocalDate.of(2026, 9, 28), range.startDate)
        // 跨月周以周一所在月份（9 月）计。
        assertEquals("9月第5周", statisticsPeriodLabel(StatisticsPeriod.WEEK, range))
    }

    @Test fun monthLabelKeepsYear() {
        val range = StatisticsRange.containing(LocalDate.of(2026, 10, 24), StatisticsPeriod.MONTH, zone)
        assertEquals("2026年10月", statisticsPeriodLabel(StatisticsPeriod.MONTH, range))
    }

    @Test fun dayLabelHasMonthDayAndWeekdayWithoutYear() {
        assertEquals("10月24日 周六", statisticsDayLabel(LocalDate.of(2026, 10, 24)))
        assertEquals("1月1日 周四", statisticsDayLabel(LocalDate.of(2026, 1, 1)))
    }

    @Test fun heatLevelIsZeroWithoutAmountAndScalesByPeak() {
        assertEquals(0, heatLevel(0L, 1000L))
        assertEquals(0, heatLevel(100L, 0L))
        // 任何非零金额至少落在第一档，避免小额被峰值压成全白。
        assertEquals(1, heatLevel(1L, 100000L))
        assertEquals(2, heatLevel(500L, 1000L))
        assertEquals(3, heatLevel(750L, 1000L))
        assertEquals(4, heatLevel(1000L, 1000L))
    }
}
