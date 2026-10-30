package io.github.caozl9021.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.withTransaction
import io.github.caozl9021.mymoney.data.AppDb
import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.data.BillQuerySummary
import io.github.caozl9021.mymoney.data.LedgerQuery
import io.github.caozl9021.mymoney.data.MiniJson
import io.github.caozl9021.mymoney.data.RecognitionEntity
import io.github.caozl9021.mymoney.data.ledgerDateWindow
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Opt-in synthetic checks. Every write is limited to a positively tagged UX2 fixture. */
class UxBatch2Instrumentation : Instrumentation() {
    private var args = Bundle()
    private val qaPrefix = "io.github.caozl9021.mymoney.qa.ux2."
    private data class Snapshot(val bills: List<BillEntity>, val history: List<RecognitionEntity>)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        if (args.getString("mode") == "cleanup") {
            cleanup()
            return
        }
        val suppliedToken = args.getString("runToken")
        val token = suppliedToken ?: UUID.randomUUID().toString().replace("-", "")
        val prefix = "$qaPrefix$token."
        val seedMode = args.getString("mode") == "seed-ui"
        val results = mutableListOf<Map<String, Any?>>()
        var failure: Throwable? = null
        var before: Snapshot? = null
        var afterDigest: String? = null
        var fixtureIds = emptyList<Long>()
        var fixtureSummary: BillQuerySummary? = null
        var retained = false
        var writesStarted = false
        var cleaned = 0
        try {
            requireAuthorization()
            check(token.matches(Regex("[A-Za-z0-9]{1,64}"))) { "runToken must be 1..64 letters or digits" }
            check(args.getString("mode") in listOf(null, "tests", "seed-ui")) { "Unsupported mode" }
            runBlocking(Dispatchers.IO) {
                val db = AppDb.get(targetContext)
                val dao = db.bills()
                before = snapshot(db)
                check(before!!.bills.none { it.sourcePkg.startsWith(prefix) }) { "Token already has fixtures; cleanup first" }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val day = ledgerDateWindow(today, today, zone)
                val millis = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                fun fixture(index: Int): BillEntity {
                    val rowName = "UX2-row-${String.format(Locale.US, "%04d", index)}"
                    return BillEntity(
                        priceCents = 100L + index,
                        type = if (index == 1 || index == 6) "REFUND" else
                            listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")[(index - 1) % 4],
                        timeMillis = millis,
                        merchant = when (index) {
                            1 -> "$rowName UX2-needle-$token"
                            3 -> "UX2-literal-%_\\-$token"
                            4 -> "UX2-literal-xy-$token"
                            5, 6 -> null
                            else -> rowName
                        },
                        method = "UX2合成账户", orderTail = null, sourcePkg = "$prefix$index",
                        strategy = "UX2 physical-device synthetic fixture", fingerprint = "$prefix$index",
                        createdAt = millis, category = when {
                            index == 5 -> null
                            index == 7 -> ""
                            index % 3 == 0 -> "UX2历史分类"
                            else -> "餐饮"
                        },
                        note = when (index) {
                            5 -> null
                            6 -> "UX2-note-needle-$token"
                            else -> "UX2合成数据 $token"
                        },
                    )
                }
                val inputs = (1..605).map(::fixture)
                check(inputs.all { it.sourcePkg.startsWith(prefix) })
                writesStarted = true
                fixtureIds = db.withTransaction { dao.insertAll(inputs) }
                val fixtures = fixtureIds.map { dao.byId(it)!! }
                fixtureSummary = totals(fixtures)
                suspend fun window(query: LedgerQuery, limit: Int = 10_000) = withTimeout(15_000) {
                    dao.observeFilteredWindow(query.likePattern, query.fromMillis, query.toMillis,
                        query.category, query.type, limit).first()
                }
                suspend fun summary(query: LedgerQuery) = withTimeout(15_000) {
                    dao.observeFilteredSummary(query.likePattern, query.fromMillis, query.toMillis,
                        query.category, query.type).first()
                }
                suspend fun expected(query: LedgerQuery): List<BillEntity> {
                    val needle = query.keyword.trim()
                    return dao.allOnce().filter { row ->
                        (needle.isEmpty() || row.merchant.orEmpty().contains(needle, ignoreCase = true) ||
                            row.note.orEmpty().contains(needle, ignoreCase = true)) &&
                            row.timeMillis >= query.fromMillis && row.timeMillis < query.toMillis &&
                            (query.category == null || row.category.orEmpty() == query.category) &&
                            (query.type == null || row.type == query.type)
                    }.sortedWith(compareByDescending<BillEntity> { it.timeMillis }.thenByDescending { it.id })
                }
                suspend fun assertQuery(query: LedgerQuery) {
                    val expected = expected(query)
                    check(window(query) == expected) { "Window fields/order differ" }
                    check(summary(query) == totals(expected)) { "Complete count/type totals differ" }
                }
                suspend fun test(name: String, action: suspend () -> Unit) {
                    val started = System.currentTimeMillis()
                    try {
                        action()
                        results += mapOf("name" to name, "status" to "passed", "durationMs" to System.currentTimeMillis() - started)
                    } catch (error: Throwable) {
                        results += mapOf("name" to name, "status" to "failed", "errorType" to error.javaClass.simpleName,
                            "reason" to error.message)
                        throw error
                    }
                }
                val todayQuery = LedgerQuery(fromMillis = day.fromMillis, toMillis = day.toMillis)
                if (!seedMode) {
                    test("605_same_day_same_time_stable_window_and_complete_daily_totals") {
                        val all = expected(todayQuery)
                        val first = window(todayQuery, 100)
                        check(first == all.take(100) && first.size == 100)
                        check(summary(todayQuery) == totals(all) && summary(todayQuery).count >= 605)
                        val expanded = window(todayQuery, 700)
                        check(expanded == all.take(700))
                        check(fixtures.sortedByDescending { it.id }.map { it.id } ==
                            expanded.filter { it.sourcePkg.startsWith(prefix) }.map { it.id })
                    }
                    test("keyword_finds_unique_refund_outside_first_100_and_500") {
                        val target = fixtures.first()
                        check(window(todayQuery, 500).none { it.id == target.id })
                        val query = todayQuery.copy(keyword = "  UX2-needle-$token  ", category = "餐饮", type = "REFUND")
                        check(window(query).single() == target)
                        assertQuery(query)
                    }
                    test("literal_percent_underscore_and_backslash_do_not_expand_like") {
                        for (keyword in listOf("%", "_", "\\", "UX2-literal-%_\\-$token")) {
                            val query = todayQuery.copy(keyword = keyword)
                            assertQuery(query)
                            val found = window(query)
                            check(found.any { it.id == fixtures[2].id })
                            check(found.none { it.id == fixtures[3].id })
                        }
                    }
                    test("null_merchant_note_match_empty_search_and_note_only_search_works") {
                        check(window(todayQuery).any { it.id == fixtures[4].id })
                        check(window(todayQuery.copy(keyword = "UX2-note-needle-$token")).single() == fixtures[5])
                        assertQuery(todayQuery.copy(keyword = " \t "))
                    }
                    test("category_all_unclassified_and_historical_chinese_names") {
                        assertQuery(todayQuery.copy(category = ""))
                        val unclassified = window(todayQuery.copy(category = ""))
                        check(unclassified.any { it.id == fixtures[4].id })
                        check(unclassified.any { it.id == fixtures[6].id })
                        assertQuery(todayQuery.copy(category = "UX2历史分类"))
                        val categories = withTimeout(15_000) { dao.observeCategories().first() }
                        check("" in categories && "UX2历史分类" in categories && "餐饮" in categories)
                        check(categories.distinct() == categories)
                    }
                    test("combined_keyword_date_category_type_and_empty_summary") {
                        assertQuery(todayQuery.copy(keyword = "UX2", category = "UX2历史分类", type = "REFUND"))
                        val empty = todayQuery.copy(keyword = "UX2-absent-$token")
                        check(window(empty).isEmpty())
                        check(summary(empty) == BillQuerySummary(0, 0, 0, 0))
                        assertQuery(LedgerQuery())
                    }
                    test("each_type_counts_separately_transfer_has_no_monetary_totals") {
                        for (type in listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")) {
                            val query = todayQuery.copy(type = type)
                            assertQuery(query)
                            val value = summary(query)
                            check(value.count > 0)
                            if (type == "TRANSFER") check(value.expense == 0L && value.income == 0L && value.refund == 0L)
                            if (type == "REFUND") check(value.income == 0L && value.expense == 0L && value.refund > 0L)
                        }
                    }
                    test("date_left_closed_right_open_at_exact_local_boundaries") {
                        val boundaryInputs = listOf(
                            fixture(606).copy(timeMillis = day.fromMillis - 1),
                            fixture(607).copy(timeMillis = day.fromMillis),
                            fixture(608).copy(timeMillis = day.toMillis - 1),
                            fixture(609).copy(timeMillis = day.toMillis),
                        )
                        val ids = dao.insertAll(boundaryInputs)
                        check(window(todayQuery).map { it.id }.containsAll(listOf(ids[1], ids[2])))
                        check(window(todayQuery).none { it.id == ids[0] || it.id == ids[3] })
                        assertQuery(todayQuery)
                    }
                    test("active_summary_flow_reacts_to_edit_delete_and_identity_restore") {
                        val original = fixtures.first()
                        val query = todayQuery.copy(keyword = "UX2-needle-$token")
                        coroutineScope {
                            val changes = Channel<BillQuerySummary>(Channel.UNLIMITED)
                            val windows = Channel<List<BillEntity>>(Channel.UNLIMITED)
                            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                                dao.observeFilteredSummary(query.likePattern, query.fromMillis, query.toMillis,
                                    query.category, query.type).collect { changes.send(it) }
                            }
                            val windowCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                                dao.observeFilteredWindow(query.likePattern, query.fromMillis, query.toMillis,
                                    query.category, query.type, 100).collect { windows.send(it) }
                            }
                            suspend fun awaitCount(count: Int) = withTimeout(15_000) {
                                var value = changes.receive()
                                while (value.count != count) value = changes.receive()
                                value
                            }
                            suspend fun awaitWindow(count: Int) = withTimeout(15_000) {
                                var value = windows.receive()
                                while (value.size != count) value = windows.receive()
                                value
                            }
                            try {
                                check(awaitCount(1) == totals(listOf(original)))
                                check(awaitWindow(1).single() == original)
                                requireFixture(db, original.id, prefix)
                                check(dao.update(original.copy(merchant = "UX2-edited-$token")) == 1)
                                awaitCount(0)
                                awaitWindow(0)
                                requireFixture(db, original.id, prefix)
                                check(dao.update(original) == 1)
                                awaitCount(1)
                                check(awaitWindow(1).single() == original)
                                deleteFixture(db, original.id, prefix)
                                awaitCount(0)
                                awaitWindow(0)
                                check(original.sourcePkg.startsWith(prefix))
                                check(dao.restoreDeleted(original) == original.id)
                                awaitCount(1)
                                check(awaitWindow(1).single() == original)
                                check(dao.byId(original.id) == original)
                            } finally {
                                collector.cancelAndJoin()
                                windowCollector.cancelAndJoin()
                                changes.close()
                                windows.close()
                            }
                        }
                    }
                } else {
                    val protected = withoutQa(before!!)
                    val marker = File(targetContext.filesDir, "ux2-seed-$token.json")
                    check(!marker.exists()) { "Seed marker already exists" }
                    marker.writeText(MiniJson.encode(mapOf(
                        "runToken" to token, "sourcePrefix" to prefix,
                        "originalNonQaSha256" to digest(protected),
                        "originalNonQaBillCount" to protected.bills.size,
                        "originalNonQaHistoryCount" to protected.history.size,
                        "candidateBillIds" to fixtureIds, "fixtureCount" to 605,
                        "fixtureDate" to today.toString(), "zone" to zone.id,
                        "fixtureExpense" to fixtureSummary!!.expense,
                        "fixtureIncome" to fixtureSummary!!.income,
                        "fixtureRefund" to fixtureSummary!!.refund,
                    )))
                    val after = snapshot(db)
                    check(after.bills.filterNot { it.sourcePkg.startsWith(prefix) } == before!!.bills)
                    check(after.history == before!!.history)
                    afterDigest = digest(withoutQa(after))
                    check(afterDigest == digest(protected))
                    retained = true
                    results += mapOf("name" to "seed_605_tagged_ui_fixtures_preserving_all_existing_rows", "status" to "passed",
                        "markerFile" to marker.name, "date" to today.toString(), "zone" to zone.id)
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (before != null && writesStarted && !retained) {
                try {
                    runBlocking(Dispatchers.IO) {
                        val db = AppDb.get(targetContext)
                        cleaned = deletePrefix(db, prefix)
                        val after = snapshot(db)
                        afterDigest = digest(after)
                        check(before == after) { "Original fields differ; no user rows restored or overwritten" }
                    }
                    results += mapOf("name" to "finally_exact_tag_cleanup_all_original_fields_unchanged", "status" to "passed")
                } catch (error: Throwable) {
                    results += mapOf("name" to "finally_exact_tag_cleanup_all_original_fields_unchanged", "status" to "failed",
                        "errorType" to error.javaClass.simpleName)
                    if (failure == null) failure = error
                }
            }
        }
        finishReport(failure, mapOf(
            "mode" to if (seedMode) "seed-ui" else "tests", "runToken" to token, "sourcePrefix" to prefix,
            "results" to results, "tests" to results.size, "fixtureBillIds" to fixtureIds,
            "fixtureCount" to fixtureIds.size, "fixtureExpense" to fixtureSummary?.expense,
            "fixtureIncome" to fixtureSummary?.income, "fixtureRefund" to fixtureSummary?.refund,
            "originalBillCount" to before?.bills?.size, "originalHistoryCount" to before?.history?.size,
            "originalSha256" to before?.let { digest(if (seedMode) withoutQa(it) else it) },
            "afterSha256" to afterDigest, "retainedForUi" to retained, "cleanedBillCount" to cleaned,
        ))
    }

    /** Requires a run token, or explicit cleanupScope=all-ux2; inventories candidates before deletion. */
    private fun cleanup() {
        var failure: Throwable? = null
        val candidates = mutableListOf<Map<String, Any?>>()
        var beforeDigest: String? = null
        var afterDigest: String? = null
        var seedBaselineMatches: Boolean? = null
        var protectedCount = 0
        var protectedHistoryCount = 0
        var cleaned = 0
        var prefix: String? = null
        try {
            requireAuthorization()
            val token = args.getString("runToken")
            prefix = if (args.getString("cleanupScope") == "all-ux2") qaPrefix else {
                check(token != null && token.matches(Regex("[A-Za-z0-9]{1,64}"))) { "Cleanup requires runToken or cleanupScope=all-ux2" }
                "$qaPrefix$token."
            }
            val cleanupPrefix = prefix!!
            runBlocking(Dispatchers.IO) {
                val db = AppDb.get(targetContext)
                val before = snapshot(db)
                val protected = Snapshot(before.bills.filterNot { it.sourcePkg.startsWith(cleanupPrefix) },
                    before.history.filterNot { it.sourcePkg.startsWith(cleanupPrefix) })
                protectedCount = protected.bills.size
                protectedHistoryCount = protected.history.size
                beforeDigest = digest(protected)
                before.bills.filter { it.sourcePkg.startsWith(cleanupPrefix) }.forEach {
                    candidates += mapOf("table" to "bills", "id" to it.id, "sourcePkg" to it.sourcePkg)
                }
                before.history.filter { it.sourcePkg.startsWith(cleanupPrefix) }.forEach {
                    candidates += mapOf("table" to "recognition_history", "id" to it.id, "sourcePkg" to it.sourcePkg)
                }
                sendStatus(1, Bundle().apply {
                    putString("phase", "cleanup-candidates")
                    putString("report", MiniJson.encode(mapOf("candidates" to candidates, "count" to candidates.size)))
                })
                cleaned = deletePrefix(db, cleanupPrefix)
                val after = snapshot(db)
                afterDigest = digest(after)
                check(protected == after) { "Protected original fields differ; no restore or overwrite attempted" }
                if (token != null) {
                    val marker = File(targetContext.filesDir, "ux2-seed-$token.json")
                    if (marker.exists()) {
                        val baseline = MiniJson.decode(marker.readText()) as? Map<*, *> ?: error("Invalid seed marker")
                        seedBaselineMatches = baseline["originalNonQaSha256"] == digest(withoutQa(after))
                        check(seedBaselineMatches == true) { "Non-QA data changed since UI seed; no user data restored" }
                        check(marker.delete()) { "Cannot remove completed seed marker" }
                    }
                }
            }
        } catch (error: Throwable) { failure = error }
        finishReport(failure, mapOf(
            "mode" to "cleanup", "sourcePrefix" to prefix, "candidates" to candidates,
            "candidateCount" to candidates.size, "cleanedBillCount" to cleaned,
            "protectedBillCount" to protectedCount, "protectedHistoryCount" to protectedHistoryCount,
            "originalProtectedSha256" to beforeDigest, "afterSha256" to afterDigest,
            "seedNonQaBaselineMatches" to seedBaselineMatches,
        ))
    }

    private fun requireAuthorization() {
        check(args.getString("allowWrites") == "ux2-tagged-only") { "Requires allowWrites=ux2-tagged-only" }
    }

    private suspend fun snapshot(db: AppDb) = db.withTransaction {
        Snapshot(db.bills().allOnce().sortedBy { it.id }, db.recognitions().allOnce().sortedBy { it.id })
    }

    private fun withoutQa(snapshot: Snapshot) = Snapshot(
        snapshot.bills.filterNot { it.sourcePkg.startsWith(qaPrefix) },
        snapshot.history.filterNot { it.sourcePkg.startsWith(qaPrefix) },
    )

    private suspend fun requireFixture(db: AppDb, id: Long, prefix: String) {
        check(db.bills().byId(id)?.sourcePkg?.startsWith(prefix) == true) { "Write refused: row is not this run's fixture" }
    }

    private suspend fun deleteFixture(db: AppDb, id: Long, prefix: String) = db.withTransaction {
        requireFixture(db, id, prefix)
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM bills WHERE id = ? AND substr(sourcePkg, 1, length(?)) = ?", arrayOf(id, prefix, prefix))
    }

    private suspend fun deletePrefix(db: AppDb, prefix: String): Int = db.withTransaction {
        check(prefix.startsWith(qaPrefix) && prefix.endsWith('.'))
        val sql = db.openHelper.writableDatabase
        var count = 0
        sql.query("SELECT COUNT(*) FROM bills WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix)).use {
            check(it.moveToFirst()); count = it.getInt(0)
        }
        sql.execSQL("DELETE FROM bills WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix))
        sql.execSQL("DELETE FROM recognition_history WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix))
        count
    }

    private fun totals(rows: List<BillEntity>) = BillQuerySummary(rows.size,
        rows.filter { it.type == "EXPENSE" }.sumOf { it.priceCents },
        rows.filter { it.type == "INCOME" }.sumOf { it.priceCents },
        rows.filter { it.type == "REFUND" }.sumOf { it.priceCents })

    private fun digest(snapshot: Snapshot): String = MessageDigest.getInstance("SHA-256")
        .digest(snapshot.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun finishReport(error: Throwable?, details: Map<String, Any?>) {
        val status = if (error == null) "passed" else "failed"
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", status)
            putString("report", MiniJson.encode(details + mapOf(
                "source" to "physical-device synthetic fixtures", "realPayment" to false,
                "status" to status, "errorType" to error?.javaClass?.simpleName, "reason" to error?.message,
            )))
        })
    }
}
