package io.github.caozl9021.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.withTransaction
import io.github.caozl9021.mymoney.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Destructive integration checks: run only on an emulator with the explicit argument below. */
class Batch4Instrumentation : Instrumentation() {
    private var args = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val results = mutableListOf<Map<String, Any?>>()
        var original: String? = null
        var backupPath: String? = null
        var backupSha256: String? = null
        var retainFixture = false
        var failure: Throwable? = null
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
                if (args.getString("mode") == "restore") {
                    val file = File(args.getString("backupPath") ?: error("backupPath required")).canonicalFile
                    check(file.parentFile == targetContext.filesDir.canonicalFile && file.name.startsWith("batch4-instrumentation-") && file.name.endsWith("-original.json"))
                    BackupStore.restore(targetContext, file.readText(), BackupStore.Mode.REPLACE)
                    results += mapOf("name" to "explicit_ui_fixture_backup_restored", "status" to "passed", "backupPath" to file.absolutePath)
                    return@runBlocking
                }
                original = BackupStore.exportJson(targetContext)
                val file = File(targetContext.filesDir, "batch4-instrumentation-${System.currentTimeMillis()}-original.json")
                file.writeText(original!!)
                backupPath = file.absolutePath
                backupSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                val db = AppDb.get(targetContext)
                val dao = db.bills()
                suspend fun test(name: String, action: suspend () -> Unit) {
                    val started = System.currentTimeMillis()
                    try {
                        withTimeout(30_000) { action() }
                        results += mapOf("name" to name, "status" to "passed", "durationMs" to System.currentTimeMillis() - started)
                    } catch (error: Throwable) {
                        results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
                        throw error
                    }
                }
                fun fixture(index: Int, time: Long = 1_700_000_000_000L) = BillEntity(
                    priceCents = 100L + index, type = "EXPENSE", timeMillis = time,
                    merchant = "模拟器批次4测试$index", method = null, orderTail = null,
                    sourcePkg = "io.github.caozl9021.mymoney.instrumentation", strategy = "batch4-instrumentation",
                    fingerprint = "batch4-$index", createdAt = 1_700_000_000_000L + index,
                )
                if (args.getString("mode") == "seed-ui") {
                    val size = args.getString("fixtureCount")?.toInt() ?: 1205
                    require(size in 501..5000)
                    val time = System.currentTimeMillis()
                    db.withTransaction {
                        dao.deleteAll()
                        db.recognitions().deleteAll()
                        dao.insertAll((0 until size).map { index ->
                            fixture(index, time).copy(merchant = when (index) {
                                0 -> "批次4最旧第${size}笔"
                                size - 1 -> "批次4最新第1笔"
                                else -> "批次4第${size - index}笔"
                            })
                        })
                    }
                    retainFixture = true // Explicit seed mode only; default tests always restore.
                    results += mapOf("name" to "ui_fixture_seeded_original_json_retained", "status" to "passed", "rows" to size)
                    return@runBlocking
                }
                suspend fun edit(originalBill: BillEntity) = suspendCoroutine<Unit> { continuation ->
                    BillWriter.saveFromEditor(targetContext,
                        BillWriter.toDraft(originalBill).copy(priceCents = 1999, note = "批次4边界编辑"), originalBill) {
                        it.fold({ continuation.resume(Unit) }, { error -> continuation.resumeWithException(error) })
                    }
                }
                suspend fun undo(id: Long) = suspendCoroutine<Unit> { continuation ->
                    BillWriter.undoDelete(targetContext, id) {
                        it.fold({ continuation.resume(Unit) }, { error -> continuation.resumeWithException(error) })
                    }
                }
                db.withTransaction { dao.deleteAll(); db.recognitions().deleteAll() }
                val ids = dao.insertAll((0 until 1205).map { fixture(it) })
                val expected = ids.reversed()
                test("same_day_same_time_1205_progressive_windows_complete_stable") {
                    check(db.openHelper.readableDatabase.version == 3)
                    var previous = emptyList<Long>()
                    var pages = 0
                    for (limit in 100..1300 step 100) {
                        val window = dao.observeWindow(limit).first()
                        val actual = window.map { it.id }
                        check(actual == expected.take(limit)) { "Window $limit has missing or unstable rows" }
                        check(actual.distinct().size == actual.size)
                        check(actual.take(previous.size) == previous)
                        check(window.all { it.timeMillis == 1_700_000_000_000L })
                        previous = actual
                        pages++
                    }
                    check(previous == expected && previous.size == 1205)
                    check(dao.observeCount().first() == 1205)
                    results += mapOf("name" to "large_ledger_detail", "rows" to previous.size,
                        "windows" to pages, "firstId" to previous.first(), "lastId" to previous.last())
                }
                // These collectors remain active across writes: assertions exercise Room invalidations,
                // rather than merely creating a fresh query after each mutation.
                val observerJob = SupervisorJob()
                val observers = CoroutineScope(observerJob + Dispatchers.IO)
                try {
                    val window = dao.observeWindow(100).stateIn(observers, SharingStarted.Eagerly, emptyList())
                    val count = dao.observeCount().stateIn(observers, SharingStarted.Eagerly, -1)
                    val allIds = dao.observeIds().stateIn(observers, SharingStarted.Eagerly, emptyList())
                    val boundaryId = expected[99]
                    val selected = dao.observeById(boundaryId).stateIn(observers, SharingStarted.Eagerly, null)
                    test("live_window_count_byid_insert_delete_and_time_order") {
                        window.first { it.map { bill -> bill.id } == expected.take(100) }
                        count.first { it == 1205 }
                        selected.first { it?.id == boundaryId }
                        val newest = dao.insert(fixture(2000, 1_700_000_000_001L))
                        window.first { it.map { bill -> bill.id } == (listOf(newest) + expected).take(100) }
                        count.first { it == 1206 }
                        dao.delete(newest)
                        window.first { it.map { bill -> bill.id } == expected.take(100) }
                        count.first { it == 1205 }
                    }
                    test("boundary_edit_preserves_id_createdat_and_recognition_link") {
                        val originalBill = dao.byId(boundaryId)!!
                        val history = RecognitionEntity.fromDraft(BillWriter.toDraft(originalBill).copy(
                            captureSessionId = "batch4-boundary"))
                            .copy(status = RecognitionStatus.RECORDED, billId = boundaryId)
                        db.withTransaction {
                            db.recognitions().insert(history)
                            dao.update(originalBill.copy(recognitionId = history.id))
                        }
                        val linked = selected.first { it?.recognitionId == history.id }!!
                        edit(linked)
                        val updated = selected.first { it?.note == "批次4边界编辑" }!!
                        check(updated.id == linked.id && updated.createdAt == linked.createdAt)
                        check(updated.recognitionId == history.id && updated.timeMillis == linked.timeMillis)
                        check(updated.priceCents == 1999L)
                        check(db.recognitions().byId(history.id) == history)
                        window.first { it.lastOrNull()?.note == "批次4边界编辑" }
                        check(count.value == 1205)
                    }
                    test("boundary_delete_two_independent_undos_restore_original_id_and_history") {
                        val linked = selected.first { it?.note == "批次4边界编辑" }!!
                        val secondId = expected[100]
                        val second = dao.byId(secondId)!!
                        val deleteErrors = java.util.concurrent.CopyOnWriteArrayList<String>()
                        BillWriter.deleteUndoably(targetContext, linked.id) { deleteErrors += it }
                        selected.first { it == null }
                        BillWriter.pendingUndo.first { items -> items.any { it.bill.id == linked.id } }
                        window.first { it.map { bill -> bill.id } == expected.filterNot { id -> id == linked.id }.take(100) }
                        count.first { it == 1204 }
                        BillWriter.deleteUndoably(targetContext, secondId) { deleteErrors += it }
                        BillWriter.pendingUndo.first { items -> items.any { it.bill.id == secondId } }
                        count.first { it == 1203 }
                        val pending = BillWriter.pendingUndo.value.filter { it.bill.id == linked.id || it.bill.id == secondId }
                        check(pending.size == 2 && pending.all { it.expiresAt > System.currentTimeMillis() })
                        val history = db.recognitions().byId(linked.recognitionId!!)!!
                        check(history.status == RecognitionStatus.RECORDED && history.billId == linked.id)
                        check(RecognitionStore.reopen(history.id) == null)
                        undo(linked.id)
                        selected.first { it == linked }
                        count.first { it == 1204 }
                        check(BillWriter.pendingUndo.value.any { it.bill.id == secondId })
                        undo(secondId)
                        count.first { it == 1205 }
                        check(dao.byId(secondId) == second)
                        check(db.recognitions().byId(history.id) == history)
                        check(deleteErrors.isEmpty()) { deleteErrors.joinToString() }
                        check(BillWriter.pendingUndo.value.none { it.bill.id == linked.id || it.bill.id == secondId })
                        window.first { it.map { bill -> bill.id } == expected.take(100) }
                        check(dao.observeWindow(1300).first().map { it.id } == expected)
                    }
                    test("outside_500_delete_updates_all_ids_with_unchanged_recent_and_recorded_history") {
                        val old = dao.byId(expected[1000])!!
                        val history = RecognitionEntity.fromDraft(BillWriter.toDraft(old).copy(
                            captureSessionId = "batch4-outside-500"))
                            .copy(status = RecognitionStatus.RECORDED, billId = old.id)
                        db.withTransaction {
                            db.recognitions().insert(history)
                            dao.update(old.copy(recognitionId = history.id))
                        }
                        val linked = dao.byId(old.id)!!
                        val recentBefore = dao.observeRecent().first()
                        check(recentBefore.none { it.id == old.id })
                        allIds.first { it.size == 1205 && old.id in it }
                        dao.delete(old.id)
                        allIds.first { it.size == 1204 && old.id !in it }
                        check(dao.observeRecent().first() == recentBefore)
                        check(db.recognitions().byId(history.id) == history)
                        check(dao.restoreDeleted(linked) == old.id)
                        allIds.first { it.size == 1205 && old.id in it }
                    }
                    test("missing_byid_and_empty_ledger_emit_without_fake_rows") {
                        check(dao.observeById(Long.MAX_VALUE).first() == null)
                        dao.deleteAll()
                        window.first { it.isEmpty() }
                        count.first { it == 0 }
                        selected.first { it == null }
                    }
                } finally {
                    observerJob.cancelAndJoin()
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            original?.takeUnless { retainFixture }?.let { backup ->
                try {
                    runBlocking(Dispatchers.IO) {
                        withTimeout(30_000) {
                            BackupStore.restore(targetContext, backup, BackupStore.Mode.REPLACE)
                            val before = Backup.decode(backup)
                            val after = Backup.decode(BackupStore.exportJson(targetContext))
                            // JSON restore remaps primary keys; compare content and verify remapped links.
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
                    }
                    results += mapOf("name" to "finally_original_json_restored_and_verified", "status" to "passed")
                } catch (error: Throwable) {
                    results += mapOf("name" to "finally_original_json_restored_and_verified", "status" to "failed", "error" to error.stackTraceToString())
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
        }
        val summary = linkedMapOf<String, Any?>("status" to if (failure == null) "passed" else "failed",
            "tests" to results.count { it["status"] != null }, "results" to results,
            "backupPath" to backupPath, "backupSha256" to backupSha256,
            "fixtureRetained" to retainFixture, "error" to failure?.stackTraceToString(),
            "device" to "${Build.MANUFACTURER}/${Build.MODEL}/${Build.FINGERPRINT}")
        val report = Bundle().apply {
            putString("report", MiniJson.encode(summary))
            putString("status", summary["status"] as String)
            putString("error", failure?.stackTraceToString())
            putInt("tests", results.count { it["status"] != null })
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
