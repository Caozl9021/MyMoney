package io.github.caozl9021.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.caozl9021.mymoney.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/** Default mode only touches an in-memory Room database. UI fixtures require explicit emulator consent. */
class StatisticsInstrumentation : Instrumentation() {
    private var args = Bundle()
    private val results = mutableListOf<Map<String, Any?>>()
    private var backupPath: String? = null
    private var backupSha256: String? = null
    private var fixtureRetained = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try {
            runBlocking(Dispatchers.IO) {
                when (val mode = args.getString("mode") ?: "test") {
                    "test" -> checkInMemory()
                    "seed-ui", "restore" -> {
                        requireEmulatorConsent()
                        withTimeout(60_000) { if (mode == "seed-ui") seedUi() else restoreUi() }
                    }
                    else -> error("Unknown mode: $mode")
                }
            }
        } catch (error: Throwable) {
            failure = error
        }
        val summary = linkedMapOf<String, Any?>(
            "status" to if (failure == null) "passed" else "failed",
            "mode" to (args.getString("mode") ?: "test"),
            "database" to if ((args.getString("mode") ?: "test") == "test") "in-memory" else "app-emulator",
            "tests" to results.count { it["status"] != null }, "results" to results,
            "backupPath" to backupPath, "backupSha256" to backupSha256,
            "fixtureRetained" to fixtureRetained, "error" to failure?.stackTraceToString(),
            "device" to "${Build.MANUFACTURER}/${Build.MODEL}/${Build.FINGERPRINT}",
        )
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("report", MiniJson.encode(summary))
            putString("status", summary["status"] as String)
            putString("error", failure?.stackTraceToString())
            putInt("tests", results.count { it["status"] != null })
        })
    }

    private suspend fun test(name: String, action: suspend () -> Unit) {
        val started = System.currentTimeMillis()
        try {
            withTimeout(30_000) { action() }
            results += mapOf("name" to name, "status" to "passed", "durationMs" to System.currentTimeMillis() - started)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        }
    }

    private fun fixture(index: Int, time: Long, type: String = listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")[index % 4]) = BillEntity(
        priceCents = 100L + index * 7, type = type, timeMillis = time,
        merchant = "统计模拟数据$index", method = null, orderTail = null,
        sourcePkg = "io.github.caozl9021.mymoney.instrumentation", strategy = "statistics-instrumentation",
        fingerprint = "statistics-$index-$time", createdAt = time,
        category = when (index % 3) { 0 -> "餐饮"; 1 -> "交通"; else -> "超长分类名称用于检查大字号下的完整文字与布局" },
        account = if (index % 2 == 0) "现金" else "银行卡",
    )

    /** Independent per-type sums and local-day partitions, including shares and account groups. */
    private fun assertSummary(all: List<BillEntity>, range: StatisticsRange): StatisticsSummary {
        val rows = all.filter { it.timeMillis >= range.fromMillis && it.timeMillis < range.toMillis }
        val summary = statistics(all, range)
        fun amount(type: String) = rows.filter { it.type == type }.sumOf { it.priceCents }
        check(summary.count == rows.size)
        check(summary.totals.expense == amount("EXPENSE"))
        check(summary.totals.income == amount("INCOME"))
        check(summary.totals.refund == amount("REFUND"))
        check(summary.totals.balance == amount("INCOME") - amount("EXPENSE") + amount("REFUND"))
        check(summary.transferCents == amount("TRANSFER"))
        check(summary.transferCount == rows.count { it.type == "TRANSFER" })
        val dates = generateSequence(range.startDate) { it.plusDays(1) }.takeWhile { it < range.endExclusiveDate }.toList()
        check(summary.days.map { it.date } == dates)
        for (type in listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")) {
            check(summary.days.sumOf { it.amount(type) } == amount(type))
            check(summary.days.sumOf { it.count(type) } == rows.count { it.type == type })
            summary.days.forEach { day ->
                val daily = rows.filter { it.type == type && Instant.ofEpochMilli(it.timeMillis).atZone(range.zone).toLocalDate() == day.date }
                check(day.amount(type) == daily.sumOf { it.priceCents } && day.count(type) == daily.size)
            }
            if (type == "TRANSFER") {
                check(summary.categories(type).isEmpty() && summary.accounts(type).isEmpty())
            } else {
                val typed = rows.filter { it.type == type }
                for ((groups, label) in listOf(
                    summary.categories(type) to { b: BillEntity -> b.category?.takeIf { it.isNotBlank() } ?: "未分类" },
                    summary.accounts(type) to { b: BillEntity -> b.account?.takeIf { it.isNotBlank() } ?: "未知账户" },
                )) {
                    val expected = typed.groupBy(label)
                    check(groups.map { it.name }.toSet() == expected.keys)
                    check(groups.sumOf { it.cents } == amount(type) && groups.sumOf { it.count } == typed.size)
                    groups.forEach { group ->
                        val members = expected.getValue(group.name)
                        val cents = members.sumOf { it.priceCents }
                        check(group.cents == cents && group.count == members.size)
                        val ratio = if (amount(type) > 0) cents.toDouble() / amount(type) else 0.0
                        check(abs(group.ratio - ratio) < 1e-12)
                    }
                }
            }
        }
        return summary
    }

    private suspend fun checkInMemory() {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try {
            val dao = db.bills()
            test("room3_local_half_open_week_month_year_and_dst_boundaries") {
                check(db.openHelper.readableDatabase.version == 3)
                val cases = listOf(
                    StatisticsRange.containing(LocalDate.of(2026, 1, 1), StatisticsPeriod.WEEK, ZoneId.of("Asia/Shanghai")),
                    StatisticsRange.containing(LocalDate.of(2025, 12, 31), StatisticsPeriod.MONTH, ZoneId.of("Asia/Shanghai")),
                    StatisticsRange.containing(LocalDate.of(2026, 3, 8), StatisticsPeriod.WEEK, ZoneId.of("America/New_York")),
                    StatisticsRange.containing(LocalDate.of(2026, 11, 1), StatisticsPeriod.WEEK, ZoneId.of("America/New_York")),
                )
                for ((index, range) in cases.withIndex()) {
                    dao.deleteAll()
                    val samples = listOf(range.fromMillis - 1, range.fromMillis, range.toMillis - 1, range.toMillis)
                        .mapIndexed { i, time -> fixture(index * 10 + i, time) }
                    val ids = dao.insertAll(samples)
                    val rows = dao.observeRange(range.fromMillis, range.toMillis).first()
                    check(rows.map { it.id }.toSet() == setOf(ids[1], ids[2]))
                    assertSummary(samples, range)
                    check(range.shift(1).fromMillis == range.toMillis)
                    check(range.shift(-1).toMillis == range.fromMillis)
                }
                check(cases[0].startDate == LocalDate.of(2025, 12, 29))
                check(cases[2].toMillis - cases[2].fromMillis == 167L * 3_600_000L)
                check(cases[3].toMillis - cases[3].fromMillis == 169L * 3_600_000L)
            }
            val range = StatisticsRange.containing(LocalDate.of(2026, 10, 15), StatisticsPeriod.MONTH, ZoneId.of("Asia/Shanghai"))
            test("complete_1205_rows_refund_transfer_daily_category_account_exact_cents") {
                dao.deleteAll()
                val samples = (0 until 1205).map { index ->
                    fixture(index, range.startDate.plusDays((index % 31).toLong()).atTime(12, 0).atZone(range.zone).toInstant().toEpochMilli())
                }
                dao.insertAll(samples)
                dao.insertAll(listOf(fixture(1300, range.fromMillis - 1), fixture(1301, range.toMillis)))
                val rows = dao.observeRange(range.fromMillis, range.toMillis).first()
                check(rows.size == 1205)
                check(dao.observeRecent().first().size == 500)
                check(rows.map { it.id }.distinct().size == 1205)
                check(rows == rows.sortedWith(compareByDescending<BillEntity> { it.timeMillis }.thenByDescending { it.id }))
                val expected = assertSummary(samples, range)
                val actual = assertSummary(rows, range)
                check(actual.totals == expected.totals && actual.days == expected.days)
                results += mapOf("name" to "complete_range_evidence", "rows" to rows.size,
                    "expenseCents" to actual.totals.expense, "incomeCents" to actual.totals.income,
                    "refundCents" to actual.totals.refund, "transferCents" to actual.transferCents,
                    "days" to actual.days.size)
            }
            test("active_room_flow_insert_edit_move_outside_and_delete_recompute") {
                dao.deleteAll()
                val job = SupervisorJob()
                val scope = CoroutineScope(job + Dispatchers.IO)
                try {
                    val live = dao.observeRange(range.fromMillis, range.toMillis).stateIn(scope, SharingStarted.Eagerly, null)
                    live.first { it != null && it.isEmpty() }
                    val id = dao.insert(fixture(2000, range.fromMillis, "EXPENSE"))
                    val added = live.first { it?.size == 1 }!!
                    check(assertSummary(added, range).totals.expense == 14100L)
                    val old = added.single()
                    dao.update(old.copy(priceCents = 1999, type = "REFUND", category = "退款"))
                    val edited = live.first { it?.singleOrNull()?.priceCents == 1999L }!!
                    val updated = assertSummary(edited, range)
                    check(updated.totals.expense == 0L && updated.totals.refund == 1999L)
                    dao.update(edited.single().copy(timeMillis = range.toMillis))
                    live.first { it != null && it.isEmpty() }
                    dao.update(old.copy(timeMillis = range.toMillis - 1))
                    live.first { it?.singleOrNull()?.id == id }
                    dao.delete(id)
                    val empty = live.first { it != null && it.isEmpty() }!!
                    check(assertSummary(empty, range).count == 0)
                } finally { job.cancelAndJoin() }
            }
            test("empty_continuous_days_and_single_category_full_share") {
                dao.deleteAll()
                val empty = assertSummary(dao.observeRange(range.fromMillis, range.toMillis).first(), range)
                check(empty.days.size == 31 && empty.days.all { it.expenseCents == 0L })
                check(empty.categories("EXPENSE").isEmpty())
                dao.insertAll((0 until 3).map { fixture(3000 + it, range.fromMillis, "EXPENSE").copy(priceCents = 1999, category = "唯一分类", account = "唯一账户") })
                val single = assertSummary(dao.observeRange(range.fromMillis, range.toMillis).first(), range)
                check(single.categories("EXPENSE").single() == StatisticsGroup("唯一分类", 5997, 3, 1.0))
                check(single.accounts("EXPENSE").single() == StatisticsGroup("唯一账户", 5997, 3, 1.0))
            }
        } finally { db.close() }
    }

    private fun requireEmulatorConsent() {
        val emulator = Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
            Build.MODEL.contains("sdk", true) || Build.MODEL.contains("emulator", true) ||
            Build.PRODUCT.contains("sdk", true) || Build.HARDWARE.contains("goldfish", true) || Build.HARDWARE.contains("ranchu", true)
        check(emulator && args.getString("allowDestructive") == "emulator-only") {
            "Refusing app database writes: emulator and allowDestructive=emulator-only required"
        }
    }

    private suspend fun seedUi() {
        val original = BackupStore.exportJson(targetContext)
        val file = File(targetContext.filesDir, "statistics-instrumentation-${System.currentTimeMillis()}-${UUID.randomUUID()}-original.json")
        check(file.createNewFile())
        file.writeText(original)
        check(file.readText() == original)
        backupPath = file.absolutePath
        backupSha256 = sha256(file)
        try {
            val db = AppDb.get(targetContext)
            val zone = ZoneId.systemDefault()
            val range = StatisticsRange.containing(LocalDate.now(zone), StatisticsPeriod.MONTH, zone)
            val days = range.endExclusiveDate.toEpochDay() - range.startDate.toEpochDay()
            val samples = (0 until 605).map { index ->
                fixture(index, range.startDate.plusDays(index % days).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
            }
            db.withTransaction {
                db.bills().deleteAll()
                db.recognitions().deleteAll()
                db.bills().insertAll(samples)
            }
            val rows = db.bills().observeRange(range.fromMillis, range.toMillis).first()
            check(rows.size == 605)
            val summary = assertSummary(rows, range)
            fixtureRetained = true
            results += mapOf("name" to "current_month_605_multiday_ui_fixture", "status" to "passed", "rows" to 605,
                "fromDate" to range.startDate.toString(), "toExclusiveDate" to range.endExclusiveDate.toString(),
                "expenseCents" to summary.totals.expense, "incomeCents" to summary.totals.income,
                "refundCents" to summary.totals.refund, "transferCents" to summary.transferCents,
                "backupPath" to backupPath, "backupSha256" to backupSha256)
        } catch (error: Throwable) {
            try { restoreVerified(original) } catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            throw error
        }
    }

    private suspend fun restoreUi() {
        val supplied = args.getString("backupPath") ?: error("backupPath required")
        val file = File(supplied).canonicalFile
        check(file.parentFile == targetContext.filesDir.canonicalFile &&
            Regex("statistics-instrumentation-[0-9]+-[0-9a-f-]{36}-original\\.json").matches(file.name) && file.isFile) {
            "Restore accepts only this module's original JSON in the app files directory"
        }
        backupPath = file.absolutePath
        backupSha256 = sha256(file)
        args.getString("backupSha256")?.let { check(it == backupSha256) { "Backup digest mismatch" } }
        test("explicit_ui_fixture_original_backup_restored_and_verified") { restoreVerified(file.readText()) }
    }

    private suspend fun restoreVerified(json: String) {
        val before = Backup.decode(json)
        BackupStore.restore(targetContext, json, BackupStore.Mode.REPLACE)
        val after = Backup.decode(BackupStore.exportJson(targetContext))
        check(before.bills.map { it.copy(id = null) }.sortedBy { it.fingerprint } ==
            after.bills.map { it.copy(id = null) }.sortedBy { it.fingerprint })
        check(before.history.orEmpty().map { it.copy(billId = null) }.sortedBy { it.id } ==
            after.history.orEmpty().map { it.copy(billId = null) }.sortedBy { it.id })
        val db = AppDb.get(targetContext)
        after.bills.filter { it.recognitionId != null }.forEach { bill ->
            check(db.recognitions().byId(bill.recognitionId!!)!!.billId == bill.id)
        }
        check(before.ledger == after.ledger && before.settings == after.settings)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
