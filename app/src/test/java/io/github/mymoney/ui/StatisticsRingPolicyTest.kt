package io.github.mymoney.ui

import io.github.mymoney.data.StatisticsGroup
import io.github.mymoney.data.StatisticsPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsRingPolicyTest {
    @Test fun weekMonthToggleWithSufficientOverlapUsesMorph() {
        val week = listOf(
            StatisticsGroup("餐饮", 60_00, 4, 0.6),
            StatisticsGroup("交通", 40_00, 2, 0.4),
        )
        val month = listOf(
            StatisticsGroup("餐饮", 120_00, 10, 0.4),
            StatisticsGroup("购物", 120_00, 3, 0.4),
            StatisticsGroup("交通", 60_00, 4, 0.2),
        )
        assertEquals(
            RingTransitionMode.MORPH,
            selectRingTransitionMode(week, month, StatisticsPeriod.WEEK, StatisticsPeriod.MONTH, "EXPENSE", "EXPENSE"),
        )
    }

    @Test fun weekMonthToggleWithSparseOverlapFallsBackToResweep() {
        val week = listOf(StatisticsGroup("娱乐", 50_00, 1, 1.0))
        val month = listOf(
            StatisticsGroup("餐饮", 180_00, 12, 0.8),
            StatisticsGroup("娱乐", 45_00, 1, 0.2),
        )
        assertEquals(
            RingTransitionMode.RESWEEP,
            selectRingTransitionMode(week, month, StatisticsPeriod.WEEK, StatisticsPeriod.MONTH, "EXPENSE", "EXPENSE"),
        )
    }

    @Test fun groupingOrTypeSwitchUsesResweep() {
        val categories = listOf(StatisticsGroup("餐饮", 100_00, 5, 1.0))
        val accounts = listOf(StatisticsGroup("微信零钱", 100_00, 5, 1.0))
        assertEquals(
            RingTransitionMode.RESWEEP,
            selectRingTransitionMode(categories, accounts, StatisticsPeriod.MONTH, StatisticsPeriod.MONTH, "EXPENSE", "EXPENSE"),
        )
        assertEquals(
            RingTransitionMode.RESWEEP,
            selectRingTransitionMode(categories, categories, StatisticsPeriod.WEEK, StatisticsPeriod.MONTH, "EXPENSE", "INCOME"),
        )
    }

    @Test fun emptySideUsesResweep() {
        val nonEmpty = listOf(StatisticsGroup("餐饮", 100_00, 5, 1.0))
        assertEquals(
            RingTransitionMode.RESWEEP,
            selectRingTransitionMode(emptyList(), nonEmpty, StatisticsPeriod.WEEK, StatisticsPeriod.MONTH, "EXPENSE", "EXPENSE"),
        )
        assertEquals(
            RingTransitionMode.RESWEEP,
            selectRingTransitionMode(nonEmpty, emptyList(), StatisticsPeriod.WEEK, StatisticsPeriod.MONTH, "EXPENSE", "EXPENSE"),
        )
    }

    @Test fun buildRingArcsSpansFullCircleFromTop() {
        val arcs = buildRingArcs(
            listOf(
                StatisticsGroup("餐饮", 75_00, 3, 0.75),
                StatisticsGroup("交通", 25_00, 1, 0.25),
            ),
        )
        assertEquals(2, arcs.size)
        assertEquals(-90f, arcs[0].startDeg, 0.01f)
        assertEquals(270f, arcs[0].sweepDeg, 0.01f)
        assertEquals(180f, arcs[1].startDeg, 0.01f)
        assertEquals(90f, arcs[1].sweepDeg, 0.01f)
    }
}
