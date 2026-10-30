package io.github.caozl9021.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import io.github.caozl9021.mymoney.data.*
import io.github.caozl9021.mymoney.export.ExportRange
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import io.github.caozl9021.mymoney.ui.HomeDailyBoundary
import io.github.caozl9021.mymoney.ui.homeDailySnapshots
import io.github.caozl9021.mymoney.ui.nextHomeDailyAnchorLimit
import java.time.*

/** Compiled verification runner; each case uses an isolated in-memory database, no preference writes. */
class LedgerPeriodInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        val results=mutableListOf<String>()
        try { runBlocking(Dispatchers.IO) {
            suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
                val db=Room.inMemoryDatabaseBuilder(targetContext,AppDb::class.java).build()
                try { withTimeout(30000) { body(db) };results+="$name: passed" } finally { db.close() }
            }
            fun snapshot(day: Int?) = Backup.Snapshot(1,emptyList(),Backup.Ledger(emptyList(),emptyList(),emptyList(),"",monthStartDay=day),null)
            suspend fun journal(db: AppDb): Backup.Snapshot {
                val value=MiniJson.decode(db.assets().meta("pending-restore-config")!!) as Map<*,*>
                return Backup.decode(value["snapshot"] as String)
            }
            test("explicit_period_in_atomic_configuration_journal") { db ->
                BackupStore.restoreDatabase(db,snapshot(25),BackupStore.Mode.MERGE)
                check(journal(db).ledger!!.monthStartDay==25)
                BackupStore.restoreDatabase(db,snapshot(25),BackupStore.Mode.MERGE)
                check(journal(db).ledger!!.monthStartDay==25)
                check(db.bills().allOnce().isEmpty() && db.assets().entries().isEmpty())
            }
            test("missing_period_is_not_replaced_by_implicit_one") { db ->
                BackupStore.restoreDatabase(db,snapshot(null),BackupStore.Mode.REPLACE)
                check(journal(db).ledger!!.monthStartDay==null)
            }
            test("explicit_one_preserved_in_journal") { db ->
                BackupStore.restoreDatabase(db,snapshot(1),BackupStore.Mode.MERGE)
                check(journal(db).ledger!!.monthStartDay==1)
            }
            test("invalid_configuration_rolls_back_database_and_journal") { db ->
                BackupStore.restoreDatabase(db,snapshot(25),BackupStore.Mode.MERGE)
                val original=db.assets().meta("pending-restore-config")
                db.bills().insert(BillEntity(priceCents=1999,type="EXPENSE",timeMillis=1,merchant="合成原记录",method=null,orderTail=null,
                    sourcePkg="synthetic",strategy="test",fingerprint="rollback-existing",createdAt=1))
                val originalBills=db.bills().allOnce()
                check(runCatching { BackupStore.restoreDatabase(db,snapshot(32),BackupStore.Mode.REPLACE) }.isFailure)
                check(db.assets().meta("pending-restore-config")==original)
                check(db.bills().allOnce()==originalBills)
            }
            test("full_room_range_and_aggregate_match_with_over_500_rows") { db ->
                val zone=ZoneId.of("Asia/Shanghai");val today=LocalDate.of(2026,10,27)
                val p=LedgerMonthPeriod.containing(today,25,zone)
                for (i in 1..1205) db.bills().insert(BillEntity(priceCents=1,type="EXPENSE",timeMillis=p.fromMillis+i,
                    merchant=null,method=null,orderTail=null,sourcePkg="synthetic",strategy="test",fingerprint="period-$i",createdAt=1))
                val span=ExportRange.span(ExportRange.Kind.THIS_MONTH,today,zone,25)
                val rows=db.bills().range(span.from,span.to)
                check(rows.size==1205)
                val stats=statistics(rows,StatisticsRange.containing(today,StatisticsPeriod.MONTH,zone,25))
                check(stats.totals.expense==1205L && stats.days.sumOf { it.expenseCents }==1205L)
                check(db.assets().entries().isEmpty() && db.imports().all().isEmpty() && db.recognitions().allOnce().isEmpty())
            }
            test("home_history_empty_and_return_match_summary_range") { db ->
                val zone=ZoneId.of("Asia/Shanghai")
                val current=LedgerMonthPeriod.containing(LocalDate.of(2026,1,15),25,zone)
                val previous=current.shift(-1)
                for ((key,time,cents) in listOf(Triple(1,current.fromMillis,120L),Triple(2,current.toMillis-1,230L),
                    Triple(3,previous.fromMillis,340L),Triple(4,previous.toMillis-1,450L),Triple(5,current.toMillis,990L))) {
                    db.bills().insert(BillEntity(priceCents=cents,type="EXPENSE",timeMillis=time,
                        merchant=null,method=null,orderTail=null,sourcePkg="synthetic",strategy="test",
                        fingerprint="browse-$key",createdAt=1))
                }
                for ((offset,expected) in listOf(0L to 350L,1L to 790L,2L to 0L,0L to 350L)) {
                    val p=current.shift(-offset)
                    val snapshot=homeDailySnapshots(db.bills().observeHomeWindow(p.fromMillis,p.toMillis,50),
                        db.bills().observeHomeCount(p.fromMillis,p.toMillis),zone,HomeDailyBoundary(),
                        { from -> db.bills().observeRange(from,p.toMillis) },p.fromMillis,p.toMillis).first()
                    val summary=db.bills().observeFilteredSummary("%%",p.fromMillis,p.toMillis,null,null).first()
                    check(summary.expense==expected && snapshot.days.sumOf { it.expenseCents }==expected)
                    check(snapshot.loadedCount==summary.count && !snapshot.hasMore)
                }
            }
            test("home_queries_limit_anchor_count_and_complete_days_to_current_period") { db ->
                val zone=ZoneId.of("Asia/Shanghai")
                val p=LedgerMonthPeriod.containing(LocalDate.of(2026,10,27),25,zone)
                fun row(id: Long, time: Long)=BillEntity(priceCents=1,type="EXPENSE",timeMillis=time,
                    merchant=null,method=null,orderTail=null,sourcePkg="synthetic",strategy="test",fingerprint="home-period-$id",createdAt=1)
                for(i in 1..1000) db.bills().insert(row(i.toLong(),p.fromMillis+86400000L+i))
                db.bills().insert(row(1001,p.fromMillis))
                db.bills().insert(row(1002,p.fromMillis-1))
                db.bills().insert(row(1003,p.toMillis))
                check(db.bills().observeHomeCount(p.fromMillis,p.toMillis).first()==1001)
                val boundary=HomeDailyBoundary()
                val first=homeDailySnapshots(db.bills().observeHomeWindow(p.fromMillis,p.toMillis,50),
                    db.bills().observeHomeCount(p.fromMillis,p.toMillis),zone,boundary,
                    { from -> db.bills().observeRange(from,p.toMillis) },p.fromMillis,p.toMillis).first()
                check(first.loadedCount==1000 && first.hasMore && first.days.single().expenseCents==1000L)
                val last=homeDailySnapshots(db.bills().observeHomeWindow(p.fromMillis,p.toMillis,nextHomeDailyAnchorLimit(50,first.loadedCount)),
                    db.bills().observeHomeCount(p.fromMillis,p.toMillis),zone,boundary,
                    { from -> db.bills().observeRange(from,p.toMillis) },p.fromMillis,p.toMillis).first()
                check(last.loadedCount==1001 && !last.hasMore)
                check(last.days.flatMap { it.bills }.all { it.timeMillis>=p.fromMillis && it.timeMillis<p.toMillis })
            }
        } } catch(e: Throwable) { failure=e }
        finish(if(failure==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("results",results.joinToString("\n"));putString("error",failure?.stackTraceToString())
        })
    }
}
