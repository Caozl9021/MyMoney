package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.withTransaction
import io.github.mymoney.data.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Explicitly opted-in physical checks; only positively tagged synthetic bills are writable. */
class UxBatch3Instrumentation : Instrumentation() {
    private var args = Bundle()
    private val rootPrefix = "io.github.mymoney.qa.ux3."
    private data class Snapshot(val bills: List<BillEntity>, val history: List<RecognitionEntity>)
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        super.onStart()
        val results = mutableListOf<Map<String, Any?>>()
        var error: Throwable? = null
        val details = mutableMapOf<String, Any?>()
        try {
            check(args.getString("allowWrites") == "ux3-tagged-only")
            val token = args.getString("runToken") ?: error("runToken required")
            check(token.matches(Regex("[A-Za-z0-9]{1,64}")))
            val prefix = "$rootPrefix$token."
            val mode = args.getString("mode") ?: "tests"
            check(mode in listOf("tests", "seed-ui", "cleanup"))
            val marker = File(targetContext.filesDir, "ux3-seed-$token.json")
            runBlocking(Dispatchers.IO) {
                val db = AppDb.get(targetContext)
                val before = snapshot(db)
                if (mode == "cleanup") {
                    val candidates = before.bills.filter { it.sourcePkg.startsWith(prefix) }
                    val protected = before.copy(bills = before.bills.filterNot { it.sourcePkg.startsWith(prefix) })
                    details["candidateIds"] = candidates.map { it.id }
                    check(marker.exists()) { "Seed marker required; refusing unproven cleanup" }
                    val baseline = MiniJson.decode(marker.readText()) as Map<*, *>
                    check(baseline["originalSha256"] == digest(protected)) { "Protected data changed since seed; refusing cleanup" }
                    deletePrefix(db, prefix)
                    val after = snapshot(db)
                    check(after == protected)
                    check(marker.delete())
                    details.putAll(mapOf("cleanedBillCount" to candidates.size, "originalBillCount" to protected.bills.size,
                        "originalHistoryCount" to protected.history.size, "originalSha256" to digest(protected),
                        "afterSha256" to digest(after), "allOriginalFieldsEqual" to true))
                } else {
                    check(!marker.exists() && before.bills.none { it.sourcePkg.startsWith(prefix) })
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    val rows = (0 until 48).map { index ->
                        BillEntity(timeMillis = today.minusDays((index % 4).toLong()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                            priceCents = if (index == 0) 123456789L else (index + 1) * 123L,
                            type = listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")[index % 4],
                            merchant = "UX3商户${index + 1}" + if (index == 0) " 超长商户名称用于验证金额和摘要布局" else "",
                            method = "合成测试", orderTail = "UX3-${index + 1}",
                            category = "UX3分类${(index / 4) + 1}", subCategory = "合成子类",
                            account = if (index % 3 == 0) null else "UX3账户${index % 3}",
                            note = "UX3合成账单 $token；非真实支付", sourcePkg = "$prefix$index",
                            strategy = "UX3 synthetic fixture", fingerprint = "$prefix$index", createdAt = System.currentTimeMillis())
                    }
                    var retained = false
                    try {
                        rows.forEach { db.bills().insert(it) }
                        val seeded = snapshot(db)
                        check(seeded.bills.filterNot { it.sourcePkg.startsWith(prefix) } == before.bills && seeded.history == before.history)
                        for (period in StatisticsPeriod.entries) {
                            val range = StatisticsRange.containing(today, period, zone)
                            val all = db.bills().observeRange(range.fromMillis, range.toMillis).first()
                            val summary = statistics(all, range)
                            for (type in listOf("EXPENSE", "INCOME", "REFUND")) {
                                val typed = all.filter { it.type == type }
                                val cents = typed.sumOf { it.priceCents }
                                check(summary.days.sumOf { it.amount(type) } == cents)
                                check(summary.days.sumOf { it.count(type) } == typed.size)
                                for (groups in listOf(summary.categories(type), summary.accounts(type))) {
                                    check(groups.sumOf { it.cents } == cents && groups.sumOf { it.count } == typed.size)
                                    check(groups.isEmpty() || kotlin.math.abs(groups.sumOf { it.ratio } - 1.0) < 0.000001)
                                }
                                val sql = db.bills().observeFilteredSummary("%%", range.fromMillis, range.toMillis, null, type).first()
                                check(sql.count == typed.size)
                                check(when(type) { "EXPENSE" -> sql.expense; "INCOME" -> sql.income; else -> sql.refund } == cents)
                                results += mapOf("name" to "${period.name}_${type}_complete_range_daily_category_account_sql_equal", "status" to "passed", "cents" to cents, "count" to typed.size)
                            }
                            check(summary.totals.balance == summary.totals.income - summary.totals.expense + summary.totals.refund)
                            check(summary.transferCount == all.count { it.type == "TRANSFER" })
                            check(summary.transferCents == all.filter { it.type == "TRANSFER" }.sumOf { it.priceCents })
                            results += mapOf("name" to "${period.name}_refund_balance_transfer_semantics", "status" to "passed")
                            details[period.name.lowercase()] = mapOf("expense" to summary.totals.expense, "income" to summary.totals.income,
                                "refund" to summary.totals.refund, "balance" to summary.totals.balance, "transfer" to summary.transferCents,
                                "count" to summary.count, "start" to range.startDate.toString(), "endExclusive" to range.endExclusiveDate.toString())
                        }
                        details.putAll(mapOf("fixtureCount" to rows.size, "originalBillCount" to before.bills.size,
                            "originalHistoryCount" to before.history.size, "originalSha256" to digest(before), "sourcePrefix" to prefix))
                        if (mode == "seed-ui") {
                            marker.writeText(MiniJson.encode(mapOf("originalSha256" to digest(before), "sourcePrefix" to prefix)))
                            retained = true
                        }
                    } finally {
                        if (!retained) {
                            deletePrefix(db, prefix)
                            val after = snapshot(db)
                            check(after == before) { "Original data differs; no user data restored or overwritten" }
                            results += mapOf("name" to "finally_exact_tag_cleanup_original_fields_equal", "status" to "passed")
                            details["afterSha256"] = digest(after)
                        }
                    }
                }
                details["mode"] = mode
                details["results"] = results
            }
        } catch (failure: Throwable) { error = failure }
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (error == null) "passed" else "failed")
            putString("report", MiniJson.encode(details + mapOf("status" to if (error == null) "passed" else "failed",
                "reason" to error?.stackTraceToString(), "source" to "physical-device synthetic fixtures", "realPayment" to false)))
        })
    }
    private suspend fun snapshot(db: AppDb) = db.withTransaction {
        Snapshot(db.bills().allOnce().sortedBy { it.id }, db.recognitions().allOnce().sortedBy { it.id })
    }
    private suspend fun deletePrefix(db: AppDb, prefix: String) = db.withTransaction {
        check(prefix.startsWith(rootPrefix) && prefix.endsWith('.'))
        db.openHelper.writableDatabase.execSQL("DELETE FROM bills WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix))
    }
    private fun digest(value: Snapshot) = MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
