package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import io.github.mymoney.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Exercises the real SQLite predicates with fresh memory databases only; never opens AppDb.get(). */
class StatisticsDrilldownInstrumentation : Instrumentation() {
    private var allowed = false
    private val results = mutableListOf<String>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val range = StatisticsRange.containing(LocalDate.of(2026, 11, 4), StatisticsPeriod.MONTH, zone)
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        allowed = arguments?.getString("allowIsolated") == "bill-deletion-temp-db"
        start()
    }
    override fun onStart() {
        var failure: Throwable? = null
        try {
            val virtual = Build.PRODUCT.contains("waydroid", true) || Build.MODEL.contains("waydroid", true) ||
                Build.PRODUCT.contains("sdk", true) || Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true)
            check(allowed && virtual) { "Explicit isolated-db flag and Waydroid/emulator required; production DB is never used" }
            runBlocking(Dispatchers.IO) { withTimeout(90_000) { cases() } }
        } catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putInt("tests", results.size)
            putString("report", MiniJson.encode(mapOf("results" to results, "error" to failure?.stackTraceToString(), "scope" to "isolated-memory-databases-only")))
        })
    }
    private fun fixture(index: Int, category: String? = "餐饮", account: String? = "微信") = BillEntity(
        priceCents = 100L + index, type = "EXPENSE", timeMillis = range.fromMillis + (index % 3) * 86_400_000L + index,
        merchant = "SIM统计$index", method = null, orderTail = null, sourcePkg = "io.github.mymoney.test",
        strategy = "isolated-statistics", fingerprint = UUID.randomUUID().toString(), createdAt = 1700000000000,
        category = category, account = account,
    )
    private suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try { body(db); results += name } finally { db.close() }
    }
    private suspend fun window(db: AppDb, category: String? = null, account: String? = null, limit: Int = 1000,
        from: Long = range.fromMillis, to: Long = range.toMillis, type: String = "EXPENSE") =
        db.bills().observeFilteredWindow("%%", from, to, null, type, limit, category, account).first()
    private suspend fun summary(db: AppDb, category: String? = null, account: String? = null,
        from: Long = range.fromMillis, to: Long = range.toMillis, type: String = "EXPENSE") =
        db.bills().observeFilteredSummary("%%", from, to, null, type, category, account).first()
    private suspend fun currentStatistics(db: AppDb): StatisticsSummary {
        val names = db.assets().accounts().associate { it.id to it.name }
        return statistics(db.bills().allOnce().map { bill -> names[bill.accountId]?.let { bill.copy(account = it) } ?: bill }, range)
    }
    private suspend fun cases() {
        test("over500_group_count_amount_period_type_and_daily_totals_match") { db ->
            val rows = (0 until 610).map { fixture(it) }
            db.bills().insertAll(rows + listOf(
                fixture(800).copy(timeMillis = range.fromMillis - 1),
                fixture(801).copy(timeMillis = range.toMillis),
                fixture(802).copy(type = "INCOME"), fixture(803).copy(type = "REFUND"), fixture(804).copy(type = "TRANSFER"),
            ))
            check(window(db, category = "餐饮", limit = 100).size == 100)
            val full = window(db, category = "餐饮")
            val group = summary(db, category = "餐饮")
            val stats = currentStatistics(db)
            check(full.size == 610 && group.count == 610 && group.expense == rows.sumOf { it.priceCents })
            check(group.income == 0L && group.refund == 0L)
            check(stats.categories("EXPENSE").single().count == group.count && stats.categories("EXPENSE").single().cents == group.expense)
            check(window(db, account = "微信").map { it.id } == full.map { it.id })
            check(summary(db, account = "微信") == group)
            for (day in stats.days) {
                val from = day.date.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = day.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val daily = summary(db, category = "餐饮", from = from, to = to)
                check(daily.count == day.expenseCount && daily.expense == day.expenseCents)
            }
            check(full.any { it.timeMillis == range.fromMillis })
            check(full.none { it.timeMillis >= range.toMillis || it.timeMillis < range.fromMillis })
            check(summary(db, category = "餐饮", type = "INCOME").count == 1)
        }
        test("category_null_blank_unicode_and_literal_fallback_share_statistics_group") { db ->
            val names = listOf(null, "", " ", "\t\n", "\u00a0", "\u3000", "未分类", " 未分类 ", "餐饮")
            db.bills().insertAll(names.mapIndexed { index, name -> fixture(index, category = name) })
            val expected = names.count { it.isNullOrBlank() || it == "未分类" }
            val rows = window(db, category = "未分类")
            val sum = summary(db, category = "未分类")
            val group = currentStatistics(db).categories("EXPENSE").first { it.name == "未分类" }
            check(rows.size == expected && sum.count == expected && sum.expense == rows.sumOf { it.priceCents })
            check(group.count == sum.count && group.cents == sum.expense)
            check(rows.none { it.category == " 未分类 " || it.category == "餐饮" })
        }
        test("account_null_blank_unicode_and_literal_fallback_share_statistics_group") { db ->
            val names = listOf(null, "", " ", "\t\n", "\u00a0", "\u3000", "未知账户", " 未知账户 ", "微信")
            db.bills().insertAll(names.mapIndexed { index, name -> fixture(index, account = name) })
            val rows = window(db, account = "未知账户")
            val sum = summary(db, account = "未知账户")
            val group = currentStatistics(db).accounts("EXPENSE").first { it.name == "未知账户" }
            check(rows.size == 7 && sum.count == 7 && sum.expense == rows.sumOf { it.priceCents })
            check(group.count == sum.count && group.cents == sum.expense)
            check(rows.none { it.account == " 未知账户 " || it.account == "微信" })
        }
        test("renamed_archived_linked_accounts_use_current_name_and_merge_literal_matches") { db ->
            val account = AssetAccount("linked-account", "原账户", initialized = false, createdAt = 1, updatedAt = 1)
            db.assets().upsertAccount(account)
            db.bills().insertAll(listOf(fixture(0, account = "原账户").copy(accountId = account.id),
                fixture(1, account = "新账户"), fixture(2, account = "原账户")))
            check(summary(db, account = "原账户").count == 2)
            db.assets().upsertAccount(account.copy(name = "新账户", archived = true, updatedAt = 2))
            val rows = window(db, account = "新账户")
            val sum = summary(db, account = "新账户")
            val stats = currentStatistics(db).accounts("EXPENSE").first { it.name == "新账户" }
            check(rows.size == 2 && sum.count == 2 && sum.expense == rows.sumOf { it.priceCents })
            check(stats.count == sum.count && stats.cents == sum.expense)
            check(summary(db, account = "原账户").count == 1)
        }
    }
}
