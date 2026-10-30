package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.withTransaction
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.LocalRuleStore
import io.github.mymoney.data.LocalRuleCodec
import io.github.mymoney.data.RecognitionEntity
import io.github.mymoney.data.RecognitionStatus
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.security.MessageDigest
import java.util.UUID
import java.io.File
import io.github.mymoney.overlay.ConfirmOverlay
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/** Opt-in, tagged synthetic Room checks. Never clears or restores the user's database. */
class UxBatch1Instrumentation : Instrumentation() {
    private var args = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val token = UUID.randomUUID().toString().replace("-", "")
        val prefix = "io.github.mymoney.qa.ux1.db.$token."
        val results = mutableListOf<Map<String, Any?>>()
        var failure: Throwable? = null
        var before: Pair<List<BillEntity>, List<RecognitionEntity>>? = null
        var beforeDigest: String? = null
        var afterDigest: String? = null
        var cleanupBills = 0
        var cleanupHistory = 0
        val allowed = args.getString("allowWrites") == "ux1-tagged-only"
        if (args.getString("mode") == "cleanup") {
            cleanupTaggedFixtures(allowed)
            return
        }
        try {
            check(allowed) { "Requires allowWrites=ux1-tagged-only" }
            runBlocking(Dispatchers.IO) {
                val db = AppDb.get(targetContext)
                suspend fun snapshot() = db.withTransaction {
                    db.bills().allOnce().sortedBy { it.id } to db.recognitions().allOnce().sortedBy { it.id }
                }
                before = snapshot()
                beforeDigest = digest(before!!)
                suspend fun test(name: String, action: suspend () -> Unit) {
                    val started = System.currentTimeMillis()
                    try {
                        action()
                        results += mapOf("name" to name, "status" to "passed", "durationMs" to System.currentTimeMillis() - started)
                    } catch (error: Throwable) {
                        results += mapOf("name" to name, "status" to "failed", "errorType" to error.javaClass.simpleName)
                        throw error
                    }
                }
                fun draft(tag: String, cents: Long = 2917) = BillDraft(
                    priceCents = cents, type = BillType.EXPENSE, timeMillis = System.currentTimeMillis(),
                    merchant = "UX1 synthetic $token $tag", method = "QA账户", orderTail = "$token-$tag",
                    sourcePkg = "$prefix$tag", strategy = "physical-device synthetic fixture",
                    category = "QA分类", account = "QA账户", note = "synthetic-$tag",
                    transactionKey = "$token-transaction-$tag", hasExplicitTime = true,
                    captureSessionId = "$token-session-$tag",
                )
                suspend fun history(draft: BillDraft): BillDraft {
                    check(draft.sourcePkg.startsWith(prefix))
                    val row = RecognitionEntity.fromDraft(draft)
                    db.recognitions().insert(row)
                    return row.toDraft()
                }
                suspend fun save(draft: BillDraft): BillWriter.SaveResult = suspendCoroutine { continuation ->
                    check(draft.sourcePkg.startsWith(prefix))
                    BillWriter.saveWithResult(targetContext, draft, original = null,
                        onDone = { continuation.resume(it) }, onError = { continuation.resumeWithException(it) })
                }
                suspend fun oldSave(draft: BillDraft): Boolean = suspendCoroutine { continuation ->
                    check(draft.sourcePkg.startsWith(prefix))
                    BillWriter.save(targetContext, draft, original = null,
                        onDone = { continuation.resume(it) }, onError = { continuation.resumeWithException(it) })
                }
                val mode = args.getString("mode")
                if (mode == "overlay-ui" || mode == "overlay-error") {
                    val finishMarker = File(targetContext.filesDir, "ux1-qa-finish")
                    if (finishMarker.exists()) check(finishMarker.delete()) { "Cannot remove old QA finish marker" }
                    val input = if (mode == "overlay-error") draft("overlay-error").copy(historyId = "UX1-missing-$token")
                        else history(draft("overlay-ui"))
                    var shown = false
                    runOnMainSync { shown = ConfirmOverlay.show(targetContext, input) }
                    check(shown) { "Overlay permission or attachment unavailable" }
                    sendStatus(1, Bundle().apply {
                        putString("phase", "overlay-ready")
                        putString("sourcePrefix", prefix)
                        putString("mode", mode)
                        putString("source", "physical-device synthetic fixtures")
                    })
                    try {
                        val deadline = System.currentTimeMillis() + 180_000L
                        while (ConfirmOverlay.isShowing() && !finishMarker.exists() && System.currentTimeMillis() < deadline) {
                            Thread.sleep(250)
                        }
                        results += mapOf("name" to mode, "status" to "passed", "verification" to "host shown; interactions require external review")
                    } finally {
                        runOnMainSync { ConfirmOverlay.dismiss() }
                        val closeDeadline = System.currentTimeMillis() + 10_000L
                        while (ConfirmOverlay.isShowing() && System.currentTimeMillis() < closeDeadline) Thread.sleep(100)
                        check(!ConfirmOverlay.isShowing()) { "Overlay did not close before cleanup" }
                        finishMarker.delete()
                    }
                    return@runBlocking
                }
                test("insert_returns_real_committed_entity") {
                    val input = history(draft("insert"))
                    val saved = save(input)
                    val bill = saved.bill ?: error("Missing saved entity")
                    check(saved.inserted && bill.id > 0)
                    check(bill == db.bills().byId(bill.id))
                    val record = db.recognitions().byId(input.historyId!!)!!
                    check(record.status == RecognitionStatus.RECORDED && record.billId == bill.id)
                }
                test("same_history_eight_concurrent_saves_insert_once_and_share_identity") {
                    val input = history(draft("concurrent"))
                    val saves = coroutineScope { (1..8).map { async { save(input) } }.awaitAll() }
                    check(saves.count { it.inserted } == 1)
                    val ids = saves.map { it.bill?.id }
                    check(ids.none { it == null } && ids.distinct().size == 1)
                    check(db.bills().allOnce().count { it.recognitionId == input.historyId } == 1)
                }
                test("duplicate_returns_original_fields_without_overwriting_edits") {
                    val input = history(draft("duplicate"))
                    val first = save(input).bill!!
                    val result = save(input.copy(priceCents = 9876, merchant = "unsaved synthetic edit",
                        category = "unsaved category", account = "unsaved account", note = "unsaved note"))
                    check(!result.inserted && result.bill == first)
                    check(db.bills().byId(first.id) == first)
                }
                test("recorded_deleted_association_returns_null_without_reinsert") {
                    val input = history(draft("deleted"))
                    val original = save(input).bill!!
                    // Delete only a positively identified fixture belonging to this run.
                    db.withTransaction {
                        check(db.bills().byId(original.id)?.sourcePkg?.startsWith(prefix) == true)
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM bills WHERE id = ? AND sourcePkg = ?", arrayOf(original.id, input.sourcePkg))
                    }
                    val beforeRetry = db.bills().allOnce().size
                    val result = save(input)
                    check(!result.inserted && result.bill == null)
                    check(db.bills().allOnce().size == beforeRetry)
                    check(db.recognitions().byId(input.historyId!!)!!.status == RecognitionStatus.RECORDED)
                }
                test("legacy_no_history_fingerprint_duplicate_returns_original_entity") {
                    val input = draft("legacy").copy(transactionKey = null, hasExplicitTime = false, captureSessionId = null)
                    val first = save(input)
                    val duplicate = save(input.copy(merchant = "unsaved legacy synthetic edit", note = "unsaved"))
                    check(first.inserted && !duplicate.inserted && duplicate.bill == first.bill)
                    check(first.bill?.recognitionId == null)
                }
                test("boolean_api_remains_true_for_insert_false_for_duplicate") {
                    val input = history(draft("boolean"))
                    check(oldSave(input))
                    check(!oldSave(input))
                    check(db.bills().byRecognitionId(input.historyId!!) != null)
                }
                test("missing_history_errors_then_valid_draft_can_retry") {
                    val input = draft("retry")
                    val count = db.bills().allOnce().size
                    val error = runCatching { save(input.copy(historyId = "ux1-missing-$token")) }.exceptionOrNull()
                    check(error != null)
                    check(db.bills().allOnce().size == count)
                    val recovered = save(history(input))
                    check(recovered.inserted && recovered.bill != null)
                }
                test("distinct_strong_identities_same_amount_are_both_saved") {
                    val raw = draft("strong-a", 3333)
                    val first = history(raw)
                    val second = history(raw.copy(transactionKey = "$token-distinct-strong-order", captureSessionId = "$token-distinct-session"))
                    check(first.transactionKey != second.transactionKey && first.fingerprint == second.fingerprint)
                    val a = save(first)
                    val b = save(second)
                    val aBill = a.bill ?: error("Missing first strong identity")
                    val bBill = b.bill ?: error("Missing second strong identity")
                    check(a.inserted && b.inserted)
                    check(aBill.id != bBill.id)
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (allowed && before != null) {
                try {
                    runBlocking(Dispatchers.IO) {
                        val db = AppDb.get(targetContext)
                        db.withTransaction {
                            // Bound exact prefix comparison: no SQL interpolation or wildcard broadening.
                            val sql = db.openHelper.writableDatabase
                            sql.query("SELECT COUNT(*) FROM bills WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix)).use {
                                check(it.moveToFirst()); cleanupBills = it.getInt(0)
                            }
                            sql.query("SELECT COUNT(*) FROM recognition_history WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix)).use {
                                check(it.moveToFirst()); cleanupHistory = it.getInt(0)
                            }
                            sql.execSQL("DELETE FROM bills WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix))
                            sql.execSQL("DELETE FROM recognition_history WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(prefix, prefix))
                        }
                        val after = db.withTransaction {
                            db.bills().allOnce().sortedBy { it.id } to db.recognitions().allOnce().sortedBy { it.id }
                        }
                        afterDigest = digest(after)
                        // Data-class equality compares every field, including original IDs and associations.
                        check(before == after) { "Original rows differ after tagged-only cleanup; no user rows were restored or overwritten" }
                    }
                    results += mapOf("name" to "finally_tagged_cleanup_and_all_original_fields_unchanged", "status" to "passed")
                } catch (error: Throwable) {
                    results += mapOf("name" to "finally_tagged_cleanup_and_all_original_fields_unchanged", "status" to "failed", "errorType" to error.javaClass.simpleName)
                    if (failure == null) failure = error
                }
            }
        }
        val status = if (failure == null) "passed" else "failed"
        val summary = linkedMapOf<String, Any?>(
            "source" to "physical-device synthetic fixtures", "realPayment" to false,
            "status" to status, "runToken" to token, "sourcePrefix" to prefix,
            "tests" to results.size, "results" to results,
            "originalBillCount" to before?.first?.size, "originalHistoryCount" to before?.second?.size,
            "originalSha256" to beforeDigest, "afterCleanupSha256" to afterDigest,
            "cleanedBillCount" to cleanupBills, "cleanedHistoryCount" to cleanupHistory,
            "errorType" to failure?.javaClass?.simpleName,
        )
        val report = Bundle().apply {
            putString("report", MiniJson.encode(summary))
            putString("status", status)
            putInt("tests", results.size)
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }

    /** Inventories UX1 rows and explicitly authorized synthetic merchant learning before precise cleanup. */
    private fun cleanupTaggedFixtures(allowed: Boolean) {
        val qaPrefix = "io.github.mymoney.qa.ux1."
        var error: Throwable? = null
        val candidates = mutableListOf<Map<String, Any?>>()
        var originalSha: String? = null
        var afterSha: String? = null
        var protectedBills = 0
        var protectedHistory = 0
        var learnedCandidates = 0
        var pendingCandidates = 0
        var rulesPreserved = false
        var protectedRulesSha256: String? = null
        var afterRulesSha256: String? = null
        try {
            check(allowed) { "Requires allowWrites=ux1-tagged-only" }
            runBlocking(Dispatchers.IO) {
                val db = AppDb.get(targetContext)
                val before = db.withTransaction {
                    db.bills().allOnce().sortedBy { it.id } to db.recognitions().allOnce().sortedBy { it.id }
                }
                val protected = before.first.filterNot { it.sourcePkg.startsWith(qaPrefix) } to
                    before.second.filterNot { it.sourcePkg.startsWith(qaPrefix) }
                protectedBills = protected.first.size
                protectedHistory = protected.second.size
                originalSha = digest(protected)
                before.first.filter { it.sourcePkg.startsWith(qaPrefix) }.forEach {
                    candidates += mapOf("table" to "bills", "id" to it.id, "sourcePkg" to it.sourcePkg)
                }
                before.second.filter { it.sourcePkg.startsWith(qaPrefix) }.forEach {
                    candidates += mapOf("table" to "recognition_history", "id" to it.id, "sourcePkg" to it.sourcePkg)
                }
                sendStatus(1, Bundle().apply {
                    putString("phase", "cleanup-candidates")
                    putString("report", MiniJson.encode(mapOf("candidates" to candidates, "count" to candidates.size)))
                })
                LocalRuleStore.init(targetContext)
                synchronized(LocalRuleStore) {
                    fun syntheticMerchant(value: String?) = value == "UX1测试咖啡" || value?.startsWith("UX1 synthetic ") == true
                    val originalRules = LocalRuleStore.snapshot()
                    val retainedRules = originalRules.copy(
                        learned = originalRules.learned.filterNot { syntheticMerchant(it.merchant) },
                        pending = originalRules.pending.filterNot { pending ->
                            // The stable learning key is JSON [type, merchant, kind, method].
                            // Malformed or unfamiliar keys are retained, never guessed from substrings.
                            val merchant = runCatching { (MiniJson.decode(pending.key) as? List<*>)?.getOrNull(1) as? String }.getOrNull()
                            syntheticMerchant(merchant)
                        },
                    )
                    learnedCandidates = originalRules.learned.size - retainedRules.learned.size
                    pendingCandidates = originalRules.pending.size - retainedRules.pending.size
                    protectedRulesSha256 = sha256(LocalRuleCodec.encode(retainedRules))
                    sendStatus(1, Bundle().apply {
                        putString("phase", "cleanup-learning-candidates")
                        putString("report", MiniJson.encode(mapOf(
                            "learnedCandidateCount" to learnedCandidates,
                            "pendingCandidateCount" to pendingCandidates,
                            "merchantMatch" to "exact UX1测试咖啡 or startsWith UX1 synthetic ",
                            "otherRulesUntouched" to true,
                        )))
                    })
                    if (learnedCandidates > 0 || pendingCandidates > 0) LocalRuleStore.restore(retainedRules)
                    val afterRules = LocalRuleStore.snapshot()
                    check(afterRules == retainedRules) { "Non-test learning fields changed" }
                    val persisted = targetContext.getSharedPreferences("ledger_config", 0).getString("local_rules_v1", null)
                    // A missing preference is valid only if there was no cleanup to persist.
                    if (persisted != null) check(LocalRuleCodec.decode(persisted) == retainedRules) { "Persisted learning differs" }
                    else check(learnedCandidates == 0 && pendingCandidates == 0)
                    afterRulesSha256 = sha256(LocalRuleCodec.encode(afterRules))
                    rulesPreserved = true
                }
                db.withTransaction {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("DELETE FROM bills WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(qaPrefix, qaPrefix))
                    sql.execSQL("DELETE FROM recognition_history WHERE substr(sourcePkg, 1, length(?)) = ?", arrayOf(qaPrefix, qaPrefix))
                }
                val after = db.withTransaction {
                    db.bills().allOnce().sortedBy { it.id } to db.recognitions().allOnce().sortedBy { it.id }
                }
                afterSha = digest(after)
                check(protected == after) { "Non-UX1 rows changed; no restore or overwrite attempted" }
            }
        } catch (failure: Throwable) { error = failure }
        val status = if (error == null) "passed" else "failed"
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", status)
            putString("report", MiniJson.encode(mapOf(
                "source" to "physical-device synthetic fixtures", "mode" to "cleanup", "status" to status,
                "candidates" to candidates, "candidateCount" to candidates.size,
                "protectedBillCount" to protectedBills, "protectedHistoryCount" to protectedHistory,
                "originalProtectedSha256" to originalSha, "afterSha256" to afterSha,
                "learnedCandidateCount" to learnedCandidates, "pendingCandidateCount" to pendingCandidates,
                "otherRuleFieldsPreserved" to rulesPreserved,
                "protectedRulesSha256" to protectedRulesSha256, "afterRulesSha256" to afterRulesSha256,
                "errorType" to error?.javaClass?.simpleName,
            )))
        })
    }

    private fun digest(snapshot: Pair<List<BillEntity>, List<RecognitionEntity>>): String = sha256(snapshot.toString())

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
