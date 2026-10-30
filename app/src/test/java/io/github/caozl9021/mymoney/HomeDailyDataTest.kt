package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.ui.HomeDailyBoundary
import io.github.caozl9021.mymoney.ui.homeDailyGroups
import io.github.caozl9021.mymoney.ui.homeDailySnapshots
import io.github.caozl9021.mymoney.ui.nextHomeDailyAnchorLimit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeDailyDataTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private fun bill(id: Long, time: Long, cents: Long = 100, type: String = "EXPENSE") = BillEntity(
        id = id, priceCents = cents, type = type, timeMillis = time, merchant = null,
        method = null, orderTail = null, sourcePkg = "manual", strategy = "MANUAL",
        fingerprint = "home-test-$id", createdAt = time,
    )

    @Test fun midnightBelongsToTheNewLocalDayRatherThanUtcDay() {
        val groups = homeDailyGroups(listOf(
            bill(1, at("2026-10-25T15:59:59.999Z")),
            bill(2, at("2026-10-25T16:00:00Z")),
        ), shanghai)
        assertEquals(listOf(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-10-25")), groups.map { it.date })
        assertEquals(listOf(2L, 1L), groups.flatMap { it.bills }.map { it.id })
    }

    @Test fun unorderedRowsAndEqualTimestampsHaveStableDescendingIdentityOrder() {
        val same = at("2026-10-26T01:00:00Z")
        val groups = homeDailyGroups(listOf(bill(2, same), bill(1, same + 1), bill(3, same)), shanghai)
        assertEquals(listOf(1L, 3L, 2L), groups.single().bills.map { it.id })
    }

    @Test fun expenseTotalKeepsAllFourTypesVisibleButNeverNetsRefundsOrTransfers() {
        val time = at("2026-10-26T01:00:00Z")
        val group = homeDailyGroups(listOf(
            bill(1, time, 101, "EXPENSE"), bill(2, time, 199, "EXPENSE"),
            bill(3, time, 900, "INCOME"), bill(4, time, 250, "REFUND"), bill(5, time, 600, "TRANSFER"),
        ), shanghai).single()
        assertEquals(300L, group.expenseCents)
        assertEquals(5, group.bills.size)
    }

    @Test fun truncatedFiftyRowAnchorLoadsTheEntireThousandRowDayAndPaginationSkipsIt() = runBlocking {
        val time = at("2026-10-26T08:00:00Z")
        val all = (1L..1000L).map { bill(it, time + it) } + bill(1001, time - 86_400_000)
        val ordered = all.sortedWith(compareByDescending<BillEntity> { it.timeMillis }.thenByDescending { it.id })
        var queriedFrom: Long? = null
        val snapshot = homeDailySnapshots(
            anchor = flowOf(ordered.take(50)), totalCount = flowOf(all.size), zone = shanghai,
            boundary = HomeDailyBoundary(), observeFrom = { from ->
                queriedFrom = from
                flowOf(all.filter { it.timeMillis >= from })
            },
        ).first()
        assertEquals(at("2026-10-25T16:00:00Z"), queriedFrom)
        assertEquals(1000, snapshot.loadedCount)
        assertEquals(100_000L, snapshot.days.single().expenseCents)
        assertTrue(snapshot.hasMore)
        val nextLimit = nextHomeDailyAnchorLimit(50, snapshot.loadedCount)
        assertEquals(1050, nextLimit)
        val olderSnapshot = homeDailySnapshots(flowOf(ordered.take(nextLimit)), flowOf(all.size), shanghai,
            HomeDailyBoundary(), { from -> flowOf(all.filter { it.timeMillis >= from }) }).first()
        assertEquals(1001, olderSnapshot.loadedCount)
        assertEquals(2, olderSnapshot.days.size)
        assertFalse(olderSnapshot.hasMore)
    }

    @Test fun boundaryCompletesTheOldestOfSeveralDaysRatherThanOnlyTheNewestDay() = runBlocking {
        val newer = (1L..30L).map { bill(it, at("2026-10-26T01:00:00Z") + it) }
        val older = (31L..130L).map { bill(it, at("2026-10-25T01:00:00Z") + it) }
        val all = newer + older
        val snapshot = homeDailySnapshots(flowOf(all.take(50)), flowOf(all.size), shanghai,
            HomeDailyBoundary(), { from -> flowOf(all.filter { it.timeMillis >= from }) }).first()
        assertEquals(listOf(30, 100), snapshot.days.map { it.bills.size })
        assertEquals(listOf(3000L, 10_000L), snapshot.days.map { it.expenseCents })
        assertFalse(snapshot.hasMore)
    }

    @Test fun newTransactionsCannotShrinkTheAlreadyLoadedCalendarWindow() {
        val boundary = HomeDailyBoundary()
        val original = boundary.include(listOf(bill(1, at("2026-10-24T01:00:00Z"))), shanghai)
        val afterNewerAnchor = boundary.include(listOf(bill(2, at("2026-10-26T01:00:00Z"))), shanghai)
        assertEquals(original, afterNewerAnchor)
        assertEquals(original, boundary.include(emptyList(), shanghai))
    }

    @Test fun editingAcrossMidnightDeletionAndUndoRegroupWholeDaysAndTotals() {
        val original = listOf(bill(1, at("2026-10-25T15:59:59Z"), 200), bill(2, at("2026-10-25T16:00:00Z"), 300))
        val edited = original.map { if (it.id == 1L) it.copy(timeMillis = at("2026-10-25T16:00:01Z")) else it }
        assertEquals(2, homeDailyGroups(original, shanghai).size)
        assertEquals(500L, homeDailyGroups(edited, shanghai).single().expenseCents)
        val deleted = edited.filterNot { it.id == 2L }
        assertEquals(200L, homeDailyGroups(deleted, shanghai).single().expenseCents)
        assertEquals(homeDailyGroups(edited, shanghai), homeDailyGroups(deleted + edited.last(), shanghai))
    }

    @Test fun dstRepeatedHourIsOneCompleteLocalDayWithCalendarMidnightBoundary() {
        val ny = ZoneId.of("America/New_York")
        val rows = listOf(bill(1, at("2026-11-01T05:30:00Z")), bill(2, at("2026-11-01T06:30:00Z")))
        assertEquals(200L, homeDailyGroups(rows, ny).single().expenseCents)
        assertEquals(at("2026-11-01T04:00:00Z"), HomeDailyBoundary().include(rows, ny))
        val skippedMidnight = ZoneId.of("America/Sao_Paulo")
        assertEquals(at("2018-11-04T03:00:00Z"), HomeDailyBoundary().include(
            listOf(bill(1, at("2018-11-04T04:30:00Z"))), skippedMidnight))
    }

    @Test fun changingZoneRegroupsDatesAndUsesAFreshCalendarBoundary() {
        val rows = listOf(bill(1, at("2026-10-25T18:00:00Z")))
        val utc = ZoneId.of("UTC")
        assertEquals(LocalDate.parse("2026-10-26"), homeDailyGroups(rows, shanghai).single().date)
        assertEquals(LocalDate.parse("2026-10-25"), homeDailyGroups(rows, utc).single().date)
        assertEquals(at("2026-10-25T00:00:00Z"), HomeDailyBoundary().include(rows, utc))
    }

    @Test fun successfulEmptyDatabaseDoesNotInventADayOrRequestAnUnboundedQuery() = runBlocking {
        val empty = homeDailySnapshots(flowOf(emptyList()), flowOf(0), shanghai, HomeDailyBoundary(), {
            error("An empty initial anchor must not request rows")
        }).first()
        assertTrue(empty.days.isEmpty())
        assertEquals(0, empty.loadedCount)
        assertFalse(empty.hasMore)
    }

    @Test fun currentPeriodUsesBothBoundsEvenWhenSourceIncludesOutsideRows() = runBlocking {
        val period=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(LocalDate.parse("2026-10-27"),25,shanghai)
        val all=listOf(bill(1,period.fromMillis-1),bill(2,period.fromMillis),bill(3,period.toMillis-1),bill(4,period.toMillis))
        val snapshot=homeDailySnapshots(flowOf(all),flowOf(2),shanghai,HomeDailyBoundary(),
            { flowOf(all) },period.fromMillis,period.toMillis).first()
        assertEquals(setOf(2L,3L),snapshot.days.flatMap { it.bills }.map { it.id }.toSet())
        assertEquals(2,snapshot.loadedCount)
        assertFalse(snapshot.hasMore)
    }

    @Test fun periodPaginationCompletesThousandRowDayWithoutLoadingPreviousPeriod() = runBlocking {
        val period=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(LocalDate.parse("2026-10-27"),25,shanghai)
        val newer=(1L..1000L).map { bill(it,period.fromMillis+86_400_000L+it) }
        val within=newer+bill(1001,period.fromMillis)
        val history=(1002L..1205L).map { bill(it,period.fromMillis-it) }
        val all=within+history
        val ordered=within.sortedByDescending { it.timeMillis }
        val boundary=HomeDailyBoundary()
        fun source(from: Long)=flowOf(all.filter { it.timeMillis>=from })
        val first=homeDailySnapshots(flowOf(ordered.take(50)),flowOf(within.size),shanghai,boundary,::source,
            period.fromMillis,period.toMillis).first()
        assertEquals(1000,first.loadedCount);assertEquals(100_000L,first.days.single().expenseCents);assertTrue(first.hasMore)
        val limit=nextHomeDailyAnchorLimit(50,first.loadedCount)
        val last=homeDailySnapshots(flowOf(ordered.take(limit)),flowOf(within.size),shanghai,boundary,::source,
            period.fromMillis,period.toMillis).first()
        assertEquals(1001,last.loadedCount);assertFalse(last.hasMore)
        assertTrue(last.days.flatMap { it.bills }.all { it.timeMillis>=period.fromMillis })
    }

    @Test fun emptyCurrentPeriodNeverFallsBackToHistoricalRows() = runBlocking {
        val period=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(LocalDate.parse("2026-10-27"),25,shanghai)
        val snapshot=homeDailySnapshots(flowOf(listOf(bill(1,period.fromMillis-1))),flowOf(0),shanghai,HomeDailyBoundary(),
            { error("Outside-period rows must not trigger a display query") },period.fromMillis,period.toMillis).first()
        assertTrue(snapshot.days.isEmpty());assertEquals(0,snapshot.loadedCount);assertFalse(snapshot.hasMore)
    }

    @Test fun previousLoadedBoundaryCannotExpandBeforeCurrentPeriod() = runBlocking {
        val period=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(LocalDate.parse("2026-10-27"),25,shanghai)
        val boundary=HomeDailyBoundary()
        boundary.include(listOf(bill(1,period.fromMillis-1)),shanghai)
        val all=listOf(bill(1,period.fromMillis-1),bill(2,period.fromMillis),bill(3,period.fromMillis+86_400_000L))
        var from: Long?=null
        val snapshot=homeDailySnapshots(flowOf(listOf(all.last())),flowOf(2),shanghai,boundary,
            { value -> from=value;flowOf(all) },period.fromMillis,period.toMillis).first()
        assertEquals(period.fromMillis,from);assertEquals(2,snapshot.loadedCount)
        assertEquals(setOf(2L,3L),snapshot.days.flatMap { it.bills }.map { it.id }.toSet())
    }

    @Test fun changingSavedDayResetsRangeAndDoesNotReuseOldPeriodRows() = runBlocking {
        val today=LocalDate.parse("2026-10-27")
        val calendar=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(today,1,shanghai)
        val custom=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(today,25,shanghai)
        val all=listOf(bill(1,calendar.fromMillis),bill(2,custom.fromMillis),bill(3,custom.toMillis))
        suspend fun snapshot(p: io.github.caozl9021.mymoney.data.LedgerMonthPeriod): io.github.caozl9021.mymoney.ui.HomeDailySnapshot {
            val within=all.filter { it.timeMillis>=p.fromMillis && it.timeMillis<p.toMillis }
            return homeDailySnapshots(flowOf(within),flowOf(within.size),shanghai,HomeDailyBoundary(),
                { flowOf(all) },p.fromMillis,p.toMillis).first()
        }
        assertEquals(setOf(1L,2L),snapshot(calendar).days.flatMap { it.bills }.map { it.id }.toSet())
        assertEquals(listOf(2L),snapshot(custom).days.flatMap { it.bills }.map { it.id })
    }

    @Test fun shortMonthBoundaryAndDstDayStayCompleteWithinPeriod() = runBlocking {
        val period=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(LocalDate.parse("2026-02-28"),31,shanghai)
        val rows=listOf(bill(1,period.fromMillis-1),bill(2,period.fromMillis),bill(3,period.toMillis-1),bill(4,period.toMillis))
        val snap=homeDailySnapshots(flowOf(rows),flowOf(2),shanghai,HomeDailyBoundary(),{ flowOf(rows) },period.fromMillis,period.toMillis).first()
        assertEquals(listOf(LocalDate.parse("2026-03-30"),LocalDate.parse("2026-02-28")),snap.days.map { it.date })
        val ny=ZoneId.of("America/New_York")
        val dst=io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(LocalDate.parse("2026-11-02"),1,ny)
        val folded=listOf(bill(1,at("2026-11-01T05:30:00Z")),bill(2,at("2026-11-01T06:30:00Z")),bill(3,dst.fromMillis-1))
        val day=homeDailySnapshots(flowOf(folded),flowOf(2),ny,HomeDailyBoundary(),{ flowOf(folded) },dst.fromMillis,dst.toMillis).first()
        assertEquals(2,day.loadedCount);assertEquals(200L,day.days.single().expenseCents)
    }
    @Test fun browsingPreviousAndEmptyPeriodsThenReturningKeepsOnlyTheSelectedRange() = runBlocking {
        val current = io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(LocalDate.parse("2026-01-15"), 25, shanghai)
        val previous = current.shift(-1)
        val all = listOf(bill(1, current.fromMillis, 120), bill(2, current.toMillis - 1, 230),
            bill(3, previous.fromMillis, 340), bill(4, previous.toMillis - 1, 450),
            bill(5, current.toMillis, 990))
        for ((back, expectedIds, expectedExpense) in listOf(
            Triple(0L, setOf(1L, 2L), 350L), Triple(1L, setOf(3L, 4L), 790L),
            Triple(2L, emptySet(), 0L), Triple(0L, setOf(1L, 2L), 350L))) {
            val period = current.shift(-back)
            val within = all.filter { it.timeMillis >= period.fromMillis && it.timeMillis < period.toMillis }
            val snapshot = homeDailySnapshots(flowOf(within.take(50)), flowOf(within.size), shanghai,
                HomeDailyBoundary(), { flowOf(all) }, period.fromMillis, period.toMillis).first()
            assertEquals(expectedIds, snapshot.days.flatMap { it.bills }.map { it.id }.toSet())
            assertEquals(expectedExpense, snapshot.days.sumOf { it.expenseCents })
            assertEquals(within.sumOf { it.priceCents }, snapshot.days.sumOf { it.expenseCents })
            assertFalse(snapshot.hasMore)
        }
    }

}
