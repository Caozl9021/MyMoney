package io.github.mymoney

import io.github.mymoney.data.*
import io.github.mymoney.export.ExportRange
import io.github.mymoney.systems.SystemEntryPolicy
import io.github.mymoney.ui.ledgerFilterWindow
import io.github.mymoney.ui.ledgerDateFilterLabel
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LedgerMonthPeriodTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun period(date: String, day: Int) = LedgerMonthPeriod.containing(LocalDate.parse(date),day,zone)
    @Test fun defaultOneMatchesNaturalCalendarAcrossYears() {
        for (year in 2024..2028) for (month in 1..12) {
            val key=YearMonth.of(year,month)
            for (day in listOf(1,key.lengthOfMonth())) {
                val p=LedgerMonthPeriod.containing(key.atDay(day),1,zone)
                assertEquals(key.atDay(1),p.startDate);assertEquals(key.atEndOfMonth(),p.endDate)
            }
        }
    }
    @Test fun twentyFifthBoundaryBelongsToNewPeriod() {
        assertEquals(LocalDate.parse("2026-10-25"),period("2026-10-27",25).startDate)
        assertEquals(LocalDate.parse("2026-11-24"),period("2026-10-27",25).endDate)
        assertEquals(LocalDate.parse("2026-09-25"),period("2026-10-24",25).startDate)
        assertEquals(LocalDate.parse("2026-10-25"),period("2026-10-25",25).startDate)
    }
    @Test fun thirtyFirstClampsOnlyShortMonthAndShiftNeverDrifts() {
        val january=period("2026-02-27",31)
        assertEquals(LocalDate.parse("2026-01-31"),january.startDate)
        assertEquals(LocalDate.parse("2026-02-28"),january.endExclusiveDate)
        assertEquals(LocalDate.parse("2026-03-31"),january.shift(2).startDate)
        assertEquals(january,january.shift(2).shift(-2))
        val stats=StatisticsRange.containing(LocalDate.parse("2026-02-28"),StatisticsPeriod.MONTH,zone,31)
        assertEquals(LocalDate.parse("2026-03-31"),stats.shift(1).startDate)
        assertEquals(stats,stats.shift(1).shift(-1))
    }
    @Test fun leapFebruaryAndYearBoundaryHaveNoGap() {
        assertEquals(LocalDate.parse("2028-02-29"),period("2028-02-29",31).startDate)
        val p=period("2026-12-31",31)
        assertEquals(LocalDate.parse("2027-01-31"),p.endExclusiveDate)
        assertEquals(p.endExclusiveDate,p.shift(1).startDate)
    }
    @Test fun everySupportedDayPartitionsAllDatesWithoutOverlap() {
        var date=LocalDate.of(2024,1,1)
        while(date < LocalDate.of(2029,1,1)) {
            for(day in 1..31) {
                val p=LedgerMonthPeriod.containing(date,day,zone)
                assertFalse(date.isBefore(p.startDate));assertTrue(date < p.endExclusiveDate)
                assertEquals(p.endExclusiveDate,p.shift(1).startDate)
                assertEquals(p,p.shift(1).shift(-1))
            }
            date=date.plusDays(1)
        }
    }
    @Test fun dstUsesLocalMidnightInsteadOfFixedDays() {
        val ny=ZoneId.of("America/New_York")
        val p=LedgerMonthPeriod.containing(LocalDate.of(2026,3,20),8,ny)
        val days=java.time.temporal.ChronoUnit.DAYS.between(p.startDate,p.endExclusiveDate)
        assertEquals(days*86_400_000L-3_600_000L,p.toMillis-p.fromMillis)
        assertEquals(p.endExclusiveDate,p.shift(1).startDate)
    }
    @Test fun allMonthlyConsumersShareExactBounds() {
        for(day in listOf(1,25,28,29,30,31)) for(date in listOf("2026-02-28","2028-02-29","2026-12-31")) {
            val today=LocalDate.parse(date);val p=LedgerMonthPeriod.containing(today,day,zone)
            val stats=StatisticsRange.containing(today,StatisticsPeriod.MONTH,zone,day)
            val filter=ledgerFilterWindow("MONTH","","",today,zone,day)
            val export=ExportRange.span(ExportRange.Kind.THIS_MONTH,today,zone,day)
            val widget=SystemEntryPolicy.monthWindow(today.atTime(12,0).atZone(zone).toInstant(),zone,day)
            assertEquals(listOf(p.fromMillis,p.toMillis),listOf(stats.fromMillis,stats.toMillis))
            assertEquals(listOf(p.fromMillis,p.toMillis),listOf(filter.fromMillis,filter.toMillis))
            assertEquals(listOf(p.fromMillis,p.toMillis),listOf(export.from,export.to))
            assertEquals(listOf(p.fromMillis,p.toMillis),listOf(widget.from,widget.to))
            assertEquals(p.shortLabel(),widget.shortLabel)
            assertEquals(p.label(),widget.rangeLabel)
            assertEquals(p.shift(-1).fromMillis,ledgerFilterWindow("LAST_MONTH","","",today,zone,day).fromMillis)
            assertEquals(p.shift(-2).fromMillis,ExportRange.span(ExportRange.Kind.LAST_3_MONTHS,today,zone,day).from)
        }
    }
    private fun bill(id: Long, at: Long, type: String="EXPENSE", cents: Long=1) = BillEntity(id,cents,type,at,null,null,null,"synthetic","fixture","fp-$id",at)
    @Test fun boundaryAmountsAndLargeLedgerMatchStatisticsExportAndWidget() {
        val p=period("2026-10-27",25)
        val rows=(1L..1205L).map { bill(it,p.fromMillis+it) } + listOf(
            bill(1300,p.fromMillis-1,cents=900),bill(1301,p.toMillis-1,cents=77),bill(1302,p.toMillis,cents=800),
            bill(1303,p.fromMillis,"INCOME",500),bill(1304,p.fromMillis,"REFUND",30),bill(1305,p.fromMillis,"TRANSFER",1000))
        val summary=statistics(rows,StatisticsRange.containing(LocalDate.of(2026,10,27),StatisticsPeriod.MONTH,zone,25))
        assertEquals(1282L,summary.totals.expense);assertEquals(500L,summary.totals.income);assertEquals(30L,summary.totals.refund)
        val widget=SystemEntryPolicy.monthWindow(Instant.ofEpochMilli(p.fromMillis),zone,25)
        assertEquals(summary.totals.expense,SystemEntryPolicy.expenseCents(rows,widget))
        assertEquals(31,summary.days.size)
        assertEquals(summary.totals.expense,summary.days.sumOf { it.expenseCents })
        val span=ExportRange.span(ExportRange.Kind.THIS_MONTH,LocalDate.of(2026,10,27),zone,25)
        assertEquals(1209,rows.count { it.timeMillis>=span.from && it.timeMillis<span.to })
    }
    @Test fun naturalDayWeekAndCustomFiltersDoNotChange() {
        val today=LocalDate.of(2026,10,27)
        for(day in 1..31) {
            assertEquals(ledgerFilterWindow("TODAY","","",today,zone),ledgerFilterWindow("TODAY","","",today,zone,day))
            assertEquals(ledgerFilterWindow("CUSTOM","2026-01-01","2026-01-02",today,zone),ledgerFilterWindow("CUSTOM","2026-01-01","2026-01-02",today,zone,day))
            assertEquals(StatisticsRange.containing(today,StatisticsPeriod.WEEK,zone),StatisticsRange.containing(today,StatisticsPeriod.WEEK,zone,day))
        }
        assertEquals("本期",ledgerDateFilterLabel("MONTH","","",25))
        assertEquals("本月",ledgerDateFilterLabel("MONTH","","",1))
    }
    @Test fun missingBackupRetainsLocalAndExplicitOneRestoresCalendar() {
        assertEquals(25,LedgerMonthPeriod.restoredDay(25,null))
        assertEquals(1,LedgerMonthPeriod.restoredDay(25,1))
        assertTrue(runCatching { period("2026-10-27",0) }.isFailure)
        assertTrue(runCatching { period("2026-10-27",32) }.isFailure)
    }
    @Test fun backupSixRoundTripAndMissingOldField() {
        val ledger=Backup.Ledger(emptyList(),emptyList(),emptyList(),"",monthStartDay=31)
        val snap=Backup.Snapshot(1,emptyList(),ledger,null)
        assertEquals(snap,Backup.decode(Backup.encode(snap)))
        for(format in 1..5) assertNull(Backup.decode("""{"format":$format,"bills":[],"ledger":{}}""").ledger!!.monthStartDay)
        for(raw in listOf("0","32","1.5","null","\"25\"")) {
            assertTrue(runCatching { Backup.decode("""{"format":6,"bills":[],"ledger":{"monthStartDay":$raw}}""") }.isFailure)
        }
        assertEquals(1,Backup.decode(Backup.encode(snap.copy(ledger=ledger.copy(monthStartDay=1)))).ledger!!.monthStartDay)
    }
}
