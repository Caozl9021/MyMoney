package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.withTransaction
import io.github.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Explicitly destructive, emulator-only Room integration suite; no external test runner needed. */
class HistoryInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val results = mutableListOf<Map<String, Any?>>()
        val report = Bundle()
        var original: String? = null
        var error: Throwable? = null
        var backupPath: String? = null
        try {
            val emulator = Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
                Build.MODEL.contains("sdk", true) || Build.MODEL.contains("emulator", true) ||
                Build.PRODUCT.contains("sdk", true) || Build.HARDWARE.contains("goldfish", true) || Build.HARDWARE.contains("ranchu", true)
            check(emulator && args.getString("allowDestructive") == "emulator-only") {
                "Refusing database writes: emulator and allowDestructive=emulator-only are required"
            }
            runBlocking(Dispatchers.IO) {
                RecognitionStore.init(targetContext)
                withTimeout(30_000) { RecognitionStore.awaitReady() }
                original = BackupStore.exportJson(targetContext)
                val file = File(targetContext.filesDir, "history-instrumentation-${System.currentTimeMillis()}-original.json")
                file.writeText(original!!)
                backupPath = file.absolutePath
                val db = AppDb.get(targetContext)
                suspend fun test(name: String, action: suspend () -> Unit) {
                    val start = System.currentTimeMillis()
                    try {
                        withTimeout(30_000) { action() }
                        results += linkedMapOf("name" to name, "status" to "passed", "durationMs" to System.currentTimeMillis() - start)
                    } catch (failure: Throwable) {
                        results += linkedMapOf("name" to name, "status" to "failed", "error" to failure.stackTraceToString())
                        throw failure
                    }
                }
                fun draft(tag: String, cents: Long = 1999) = BillDraft(
                    priceCents = cents, type = BillType.EXPENSE, timeMillis = 1_700_000_000_000,
                    merchant = "模拟器历史测试", method = "测试账户", orderTail = null,
                    sourcePkg = "io.github.mymoney.instrumentation", strategy = "instrumentation",
                    captureSessionId = tag)
                suspend fun save(draft: BillDraft): Boolean = withTimeout(15_000) {
                    suspendCoroutine { continuation ->
                        BillWriter.save(targetContext, draft, onDone = { continuation.resume(it) },
                            onError = { continuation.resumeWithException(it) })
                    }
                }
                suspend fun dataSnapshot(): Pair<List<BillEntity>, List<RecognitionEntity>> =
                    db.withTransaction { db.bills().allOnce() to db.recognitions().allOnce() }
                test("room_v3_and_legacy_queue_migration") {
                    check(db.openHelper.readableDatabase.version == 3)
                    val expected = args.getString("expectedLegacyCount")?.toInt() ?: 0
                    val migrated = db.recognitions().allOnce().filter { it.identityKey.startsWith("legacy:") }
                    check(migrated.size >= expected) { "Expected $expected migrated drafts, found ${migrated.size}" }
                    check(!targetContext.getSharedPreferences("pending_drafts", 0).contains("items")) { "Old queue was not cleared after migration" }
                    results += mapOf("name" to "legacy_migration_detail", "migratedCount" to migrated.size, "expectedMinimum" to expected)
                }
                db.withTransaction { db.bills().deleteAll(); db.recognitions().deleteAll() }
                test("thirty_five_records_not_truncated") {
                    repeat(35) { RecognitionStore.record(draft("bulk-$it")) }
                    check(db.recognitions().allOnce().size == 35)
                }
                test("stable_session_across_minutes_and_distinct_sessions_same_fingerprint") {
                    val a = RecognitionStore.record(draft("stable"))
                    val b = RecognitionStore.record(draft("stable").copy(timeMillis = draft("stable").timeMillis + 60_000))
                    check(a.id == b.id)
                    val c = RecognitionStore.record(draft("other-session"))
                    check(a.fingerprint == c.fingerprint && a.id != c.id)
                }
                test("concurrent_record_and_double_save_atomic_idempotence") {
                    val draft = draft("concurrent", 3001)
                    val ids = coroutineScope { (1..8).map { async { RecognitionStore.record(draft).id } }.awaitAll() }
                    check(ids.distinct().size == 1)
                    val history = RecognitionStore.find(ids.first())!!
                    val saved = coroutineScope { (1..8).map { async { save(history.toDraft()) } }.awaitAll() }
                    check(saved.count { it } == 1)
                    val recorded = RecognitionStore.find(history.id)!!
                    check(recorded.status == RecognitionStatus.RECORDED)
                    check(db.bills().byId(recorded.billId!!)?.recognitionId == recorded.id)
                    check(db.bills().allOnce().count { it.recognitionId == history.id } == 1)
                }
                test("legacy_bill_is_linked_without_reinsertion") {
                    val d = draft("legacy-link", 3011)
                    val id = db.bills().insert(BillEntity(priceCents = d.priceCents, type = d.type.name,
                        timeMillis = d.timeMillis, merchant = d.merchant, method = d.method, orderTail = null,
                        sourcePkg = d.sourcePkg, strategy = d.strategy, fingerprint = d.fingerprint,
                        createdAt = System.currentTimeMillis()))
                    val row = RecognitionStore.record(d)
                    val before = db.bills().allOnce().size
                    check(!save(row.toDraft()))
                    check(db.bills().allOnce().size == before)
                    check(RecognitionStore.find(row.id)!!.billId == id)
                    check(db.bills().byId(id)!!.recognitionId == row.id)
                }
                test("ignore_reopen_preserve_edits_and_identity") {
                    val row = RecognitionStore.record(draft("edit-ignore", 3021))
                    RecognitionStore.ignore(row.id)
                    check(RecognitionStore.find(row.id)!!.status == RecognitionStatus.IGNORED)
                    val reopened = RecognitionStore.reopen(row.id)!!
                    RecognitionStore.preserve(reopened.copy(merchant = "编辑商户", category = "测试分类", note = "编辑备注"))
                    val edited = RecognitionStore.find(row.id)!!
                    check(edited.identityKey == row.identityKey && edited.id == row.id)
                    check(edited.status == RecognitionStatus.PENDING && edited.note == "编辑备注")
                    check(RecognitionStore.record(draft("edit-ignore", 3021)).note == "编辑备注")
                }
                test("deleted_link_remains_recorded_and_restore_retains_id") {
                    val row = RecognitionStore.record(draft("delete-link", 3031))
                    check(save(row.toDraft()))
                    val recorded = RecognitionStore.find(row.id)!!
                    val bill = db.bills().byId(recorded.billId!!)!!
                    db.bills().delete(bill.id)
                    check(!save(row.toDraft()))
                    check(RecognitionStore.reopen(row.id) == null)
                    check(RecognitionStore.find(row.id)!!.status == RecognitionStatus.RECORDED)
                    check(db.bills().byRecognitionId(row.id) == null)
                    check(db.bills().restoreDeleted(bill) == bill.id)
                    check(db.bills().byId(recorded.billId!!)!!.recognitionId == row.id)
                }
                test("share_failure_and_request_are_durable_separate_states") {
                    val row = db.recognitions().allOnce().first { it.status == RecognitionStatus.RECORDED }
                    RecognitionStore.markShare(row.id, false)
                    check(db.recognitions().byId(row.id)!!.shareState == RecognitionShareState.FAILED)
                    RecognitionStore.markShare(row.id, true)
                    check(db.recognitions().byId(row.id)!!.shareState == RecognitionShareState.REQUESTED)
                    check(db.recognitions().byId(row.id)!!.status == RecognitionStatus.RECORDED)
                }
                test("json2_merge_idempotent_and_never_downgrades_recorded") {
                    val json = BackupStore.exportJson(targetContext)
                    val snap = Backup.decode(json)
                    val before = dataSnapshot()
                    repeat(2) { BackupStore.restore(targetContext, json, BackupStore.Mode.MERGE) }
                    check(dataSnapshot() == before)
                    val downgraded = snap.copy(history = snap.history!!.map { it.copy(status = RecognitionStatus.PENDING, billId = null, updatedAt = it.updatedAt + 10000) })
                    BackupStore.restore(targetContext, Backup.encode(downgraded), BackupStore.Mode.MERGE)
                    before.second.filter { it.status == RecognitionStatus.RECORDED }.forEach {
                        check(RecognitionStore.find(it.id)!!.status == RecognitionStatus.RECORDED)
                    }
                }
                test("json2_replace_remaps_links_and_keeps_two_same_fingerprint_bills") {
                    val a = RecognitionStore.record(draft("two-a", 3041))
                    val b = RecognitionStore.record(draft("two-b", 3041))
                    check(a.fingerprint == b.fingerprint && a.id != b.id)
                    check(save(a.toDraft()) && save(b.toDraft()))
                    val json = BackupStore.exportJson(targetContext)
                    val count = db.bills().allOnce().size
                    BackupStore.restore(targetContext, json, BackupStore.Mode.REPLACE)
                    check(db.bills().allOnce().size == count)
                    listOf(a.id, b.id).forEach { id ->
                        val row = RecognitionStore.find(id)!!
                        check(db.bills().byId(row.billId!!)!!.recognitionId == id)
                    }
                    check(RecognitionStore.find(a.id)!!.billId != RecognitionStore.find(b.id)!!.billId)
                }
                test("json1_replace_retains_history_unlinks_and_does_not_reinsert") {
                    val originalHistory = db.recognitions().allOnce()
                    val recorded = originalHistory.first { it.status == RecognitionStatus.RECORDED }
                    BackupStore.restore(targetContext, """{"kind":"MyMoneyBackup","format":1,"bills":[]}""", BackupStore.Mode.REPLACE)
                    check(db.recognitions().allOnce().size == originalHistory.size)
                    check(db.recognitions().allOnce().all { it.billId == null })
                    check(!save(recorded.toDraft()))
                    check(db.bills().allOnce().isEmpty())
                    check(RecognitionStore.find(recorded.id)!!.status == RecognitionStatus.RECORDED)
                }
                test("invalid_restore_rejected_before_any_database_change") {
                    val before = dataSnapshot()
                    listOf("not-json", """{"format":99,"bills":[]}""", """{"format":2,"bills":[],"history":[{"status":"bad"}]}""").forEach { bad ->
                        var rejected = false
                        try { BackupStore.restore(targetContext, bad, BackupStore.Mode.REPLACE) } catch (_: Exception) { rejected = true }
                        check(rejected)
                        check(dataSnapshot() == before)
                    }
                }
                test("clear_ignored_never_deletes_ledger") {
                    val pending = RecognitionStore.record(draft("clear-ignored", 3051))
                    RecognitionStore.ignore(pending.id)
                    val recorded = RecognitionStore.record(draft("keep-bill", 3061))
                    check(save(recorded.toDraft()))
                    val bills = db.bills().allOnce()
                    val historySize = db.recognitions().allOnce().size
                    check(RecognitionStore.clearIgnored() == 1)
                    check(db.recognitions().allOnce().size == historySize - 1)
                    check(RecognitionStore.find(pending.id) == null)
                    check(db.bills().allOnce() == bills)
                }
            }
        } catch (failure: Throwable) {
            error = failure
        } finally {
            original?.let { backup ->
                try {
                    runBlocking(Dispatchers.IO) {
                        withTimeout(30_000) { BackupStore.restore(targetContext, backup, BackupStore.Mode.REPLACE) }
                    }
                    results += mapOf("name" to "finally_original_backup_restored", "status" to "passed")
                } catch (restoreError: Throwable) {
                    results += mapOf("name" to "finally_original_backup_restored", "status" to "failed", "error" to restoreError.stackTraceToString())
                    if (error == null) error = restoreError else error!!.addSuppressed(restoreError)
                }
            }
        }
        val summary = linkedMapOf<String, Any?>("status" to if (error == null) "passed" else "failed",
            "tests" to results.size, "results" to results, "backupPath" to backupPath,
            "error" to error?.stackTraceToString(), "device" to "${Build.MANUFACTURER}/${Build.MODEL}/${Build.FINGERPRINT}")
        report.putString("report", MiniJson.encode(summary))
        report.putString("status", summary["status"] as String)
        report.putString("error", error?.stackTraceToString())
        report.putInt("tests", results.size)
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
