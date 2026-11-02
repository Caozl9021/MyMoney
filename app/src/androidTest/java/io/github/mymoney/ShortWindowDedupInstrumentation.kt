package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.room.withTransaction
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.capture.CaptureCoordinator
import io.github.mymoney.data.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Synthetic fixtures only. The optional UI case requires an emulator and a fresh test ledger. */
class ShortWindowDedupInstrumentation : Instrumentation() {
    private val automation get() = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val results = mutableListOf<String>()
    private var ui = false
    private var writerRace = false
    private val at = 1_793_577_599_000L
    private val base = BillDraft(400, BillType.EXPENSE, at, "早餐店", null, null,
        "com.tencent.mm", "synthetic-short-window", hasExplicitTime = false)
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); ui = arguments?.getString("ui") == "true"; writerRace = arguments?.getString("writerRace") == "true"; start() }
    override fun onStart() {
        var failure: Throwable? = null
        try { runBlocking(Dispatchers.IO) { if (ui) uiCase() else if (writerRace) writerRaceCases() else cases() } }
        catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putInt("tests", results.size)
            putString("report", MiniJson.encode(mapOf("results" to results, "error" to failure?.stackTraceToString(),
                "source" to "synthetic-fixtures", "ui" to ui)))
        })
    }
    private fun input(source: String, key: String, offset: Long = 0, draft: BillDraft = base) =
        CaptureCoordinator.Input(draft, source, key, UUID.randomUUID().toString(), at + offset)
    private suspend fun accept(db: AppDb, value: CaptureCoordinator.Input) =
        db.withTransaction { CaptureCoordinator.acceptInTransaction(db, value) }
    private suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try { withTimeout(30_000) { body(db) }; results += name } finally { db.close() }
    }
    private suspend fun cases() {
        for ((first, second) in listOf("PAGE" to "HOOK", "HOOK" to "PAGE", "PAGE" to "NOTIFICATION",
            "NOTIFICATION" to "PAGE", "HOOK" to "NOTIFICATION", "NOTIFICATION" to "HOOK", "ROOT" to "PAGE")) {
            test("${first}_then_${second}_same_amount_no_payment_time") { db ->
                val a = accept(db, input(first, "first"))
                val b = accept(db, input(second, "second", 7000, base.copy(merchant = "早餐店（*伟）")))
                check(a.created && !b.created && a.row.id == b.row.id && b.deliverable)
                check(db.recognitions().allOnce().size == 1 && b.row.reviewOf == null)
                check(db.capture().forRecord(a.row.id).any { "merge=cross-channel-60s-v1;intervalMs=7000" in it.detail })
            }
        }
        for (offset in listOf(0L, 59_999L, 60_000L, 60_001L, 120_001L)) {
            test("window_boundary_$offset") { db ->
                val a = accept(db, input("PAGE", "first"))
                val b = accept(db, input("HOOK", "second", offset))
                check((a.row.id == b.row.id) == (offset <= 60_000))
                check(db.recognitions().allOnce().size == if (offset <= 60_000) 1 else 2)
            }
        }
        test("same_bucket_cannot_bypass_60_seconds") { db ->
            val d = base.copy(hasExplicitTime = true, dedupKey = "dup:synthetic-same-bucket")
            val a = accept(db, input("PAGE", "first", draft = d))
            val b = accept(db, input("HOOK", "second", 60_001, d))
            check(a.row.id != b.row.id && b.created)
        }
        test("fixed_anchor_three_sources_do_not_extend_window") { db ->
            val a = accept(db, input("PAGE", "first"))
            val b = accept(db, input("HOOK", "second", 50_000))
            val c = accept(db, input("NOTIFICATION", "third", 100_000))
            check(a.row.id == b.row.id && c.row.id != a.row.id)
            check(db.recognitions().byId(a.row.id)!!.recognizedAt == at)
        }
        test("three_sources_and_many_repeated_events_one_record") { db ->
            val a = accept(db, input("PAGE", "first"))
            val b = accept(db, input("HOOK", "second", 7000))
            val c = accept(db, input("ROOT", "third", 9000))
            check(setOf(a.row.id, b.row.id, c.row.id).size == 1)
            repeat(25) { check(accept(db, input("HOOK", "second", 10_000 + it.toLong())).row.id == a.row.id) }
            // Source matching must use the complete persisted source set, not the last 20 events.
            val next = accept(db, input("PAGE", "new-page-instance", 15_000))
            check(next.created && next.row.id != a.row.id && next.row.reviewOf == null)
        }
        for ((label, d) in listOf("amount" to base.copy(priceCents = 401), "application" to base.copy(sourcePkg = "com.eg.android.AlipayGphone"),
            "type" to base.copy(type = BillType.INCOME))) {
            test("different_${label}_keeps_two") { db ->
                val a = accept(db, input("PAGE", "first"))
                val b = accept(db, input("HOOK", "second", 1000, d))
                check(a.row.id != b.row.id && b.row.reviewOf == null)
            }
        }
        test("different_order_ids_same_amount_and_bucket_are_independent") { db ->
            val a = accept(db, input("PAGE", "first", draft = base.copy(transactionKey = "order-a")))
            val b = accept(db, input("HOOK", "second", 1000, base.copy(transactionKey = "order-b")))
            check(a.row.id != b.row.id && b.row.reviewOf == null)
        }
        test("same_source_and_notification_implementations_keep_new_instances") { db ->
            val a = accept(db, input("ROOT", "first"))
            val b = accept(db, input("NOTIFICATION", "second", 1000))
            val c = accept(db, input("ROOT", "third", 2000))
            check(setOf(a.row.id, b.row.id, c.row.id).size == 3)
        }
        test("ambiguous_two_real_payments_are_not_arbitrarily_merged") { db ->
            val a = accept(db, input("PAGE", "first", draft = base.copy(transactionKey = "order-a")))
            val b = accept(db, input("PAGE", "second", 1000, base.copy(transactionKey = "order-b")))
            db.capture().putDelivery(CaptureDelivery(a.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown-a", at))
            db.capture().putDelivery(CaptureDelivery(b.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown-b", at))
            val c = accept(db, input("HOOK", "third", 2000))
            check(c.created && c.reviewReason == CaptureReviewPolicy.AMBIGUOUS_CROSS_SOURCE && c.row.reviewOf != null)
        }
        for (status in listOf(RecognitionStatus.RECORDED, RecognitionStatus.IGNORED)) {
            test("${status}_adds_only_evidence_and_alias_including_later_order_conflict") { db ->
                val a = accept(db, input("PAGE", "first"))
                val terminal = a.row.copy(status = status, note = "手工备注", merchant = "手工商户")
                db.recognitions().update(terminal)
                val b = accept(db, input("HOOK", "second", 7000, base.copy(transactionKey = "late-order")))
                check(b.row == terminal && !b.created && !b.deliverable)
                val c = accept(db, input("NOTIFICATION", "third", 9000, base.copy(transactionKey = "another-order")))
                check(c.created && c.row.reviewOf == null && c.row.id != a.row.id)
                check(db.recognitions().byId(a.row.id) == terminal && db.bills().allOnce().isEmpty())
            }
        }
        test("edit_lock_and_manual_clear_survive_weak_merge") { db ->
            val a = accept(db, input("PAGE", "first"))
            val edited = a.row.copy(note = null, noteOrigin = NoteTemplate.USER, protectedFields = CaptureFields.NOTE)
            db.recognitions().update(edited)
            CaptureEditLocks.acquire(a.row.id)
            try {
                val b = accept(db, input("HOOK", "second", 7000, base.copy(merchant = "不覆盖", note = "不覆盖")))
                check(b.row == edited && !b.created)
            } finally { CaptureEditLocks.release(a.row.id) }
        }
        for (state in listOf(null, CaptureDeliveryState.FAILED, CaptureDeliveryState.SUPPRESSED,
            CaptureDeliveryState.CLAIMED, CaptureDeliveryState.ACKNOWLEDGED, CaptureDeliveryState.UNKNOWN, CaptureDeliveryState.RESTORED)) {
            test("delivery_receipt_${state}_retries_at_most_one_claim") { db ->
                val a = accept(db, input("PAGE", "first"))
                if (state != null) db.capture().putDelivery(CaptureDelivery(a.row.id, state, "first-attempt", at))
                val b = accept(db, input("HOOK", "second", 7000))
                val retry = CaptureShortWindowPolicy.retryableDelivery(state)
                check(b.deliverable == retry && b.row.id == a.row.id)
                val claims = coroutineScope { (0..7).map { index -> async {
                    db.withTransaction { CaptureCoordinator.claimInTransaction(db, a.row.id, "retry-$index") }
                } }.awaitAll() }
                check(claims.count { it } == if (retry) 1 else 0)
            }
        }
        test("concurrent_three_channels_one_row_and_one_claim") { db ->
            val rows = coroutineScope { listOf("PAGE", "HOOK", "ROOT").map { source -> async {
                accept(db, input(source, "event-$source"))
            } }.awaitAll() }
            check(rows.map { it.row.id }.distinct().size == 1 && rows.count { it.created } == 1)
            val claims = coroutineScope { rows.mapIndexed { index, row -> async {
                db.withTransaction { CaptureCoordinator.claimInTransaction(db, row.row.id, "claim-$index") }
            } }.awaitAll() }
            check(claims.count { it } == 1)
        }
        test("original_old_review_remains_unchanged") { db ->
            val a = accept(db, input("PAGE", "old-primary"))
            val old = RecognitionEntity.fromDraft(base, "capture:old-review", at).copy(reviewOf = a.row.id)
            db.recognitions().insert(old)
            accept(db, input("HOOK", "new-hook", 7000))
            check(db.recognitions().byId(old.id) == old)
        }
        val name = "short-window-reopen-${UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(targetContext, AppDb::class.java, name).build()
        try {
            val a = accept(disk, input("PAGE", "before-restart"))
            disk.close()
            disk = Room.databaseBuilder(targetContext, AppDb::class.java, name).build()
            val b = accept(disk, input("HOOK", "after-restart", 7000))
            check(b.row.id == a.row.id && !b.created && disk.recognitions().allOnce().size == 1)
            results += "persistent_database_reopen_retains_anchor_sources_and_aliases"
        } finally { disk.close(); targetContext.deleteDatabase(name) }
    }

    private suspend fun uiCase() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "UI fixture is emulator-only" }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val launch = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)!!
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(launch)
        delay(2500)
        val db = AppDb.get(targetContext)
        check(db.bills().allOnce().isEmpty() && db.recognitions().allOnce().isEmpty()) { "Requires a fresh isolated emulator ledger" }
        AssetStore.init(targetContext); AssetStore.awaitReady()
        val now = System.currentTimeMillis()
        val account = AssetAccount(UUID.randomUUID().toString(), "去重测试钱包", "PAYMENT", initialized = true, createdAt = now, updatedAt = now)
        db.assets().upsertAccount(account)
        db.assets().insertEntry(AssetEntry(UUID.randomUUID().toString(), "opening", account.id, 10000, "OPENING", "synthetic", now))
        Settings(targetContext).autoConfirm = true
        val draft = base.copy(timeMillis = now, account = account.name, category = "餐饮", subCategory = "早餐", note = "合成早餐")
        val first = CaptureCoordinator.accept(targetContext,
            CaptureCoordinator.Input(draft, "PAGE", "ui-page", receivedAt = now))!!
        val outcome = CaptureCoordinator.deliver(targetContext, first) { true }
        check(outcome == "overlay") { "Expected real overlay, got $outcome" }
        delay(1000)
        screenshot("dedup-confirm-card.png")
        val button = try { withTimeout(20_000) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) {
                if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                found = findSaveButton()
                if (found == null) delay(200)
            }
            found
        } } catch (error: Throwable) { screenshot("dedup-ui-timeout.png"); throw error }
        screenshot("dedup-confirm-card.png")
        var clickable: AccessibilityNodeInfo? = button
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        withTimeout(10_000) { while (db.recognitions().byId(first.row.id)?.status != RecognitionStatus.RECORDED) delay(100) }
        val saved = db.bills().allOnce().single()
        val beforeEntries = db.assets().entries()
        check(beforeEntries.filter { it.accountId == account.id }.sumOf { it.deltaCents } == 9600L)
        val late = CaptureCoordinator.accept(targetContext,
            CaptureCoordinator.Input(draft.copy(merchant = "早餐店（*伟）", transactionKey = "ui-late-order"),
                "HOOK", "ui-hook", receivedAt = now + 7000))!!
        check(!late.created && late.row.id == first.row.id && !late.deliverable)
        check(CaptureCoordinator.deliver(targetContext, late) { true } == "linked")
        // Exercise the real writer, including concurrent double-save callbacks for the shared ID.
        val repeats = coroutineScope { (0..5).map { async {
            val done = CompletableDeferred<BillWriter.SaveResult>()
            BillWriter.saveWithResult(targetContext, late.row.toDraft(), onDone = { done.complete(it) }, onError = { done.completeExceptionally(it) })
            withTimeout(10000) { done.await() }
        } }.awaitAll() }
        check(repeats.none { it.inserted } && repeats.all { it.bill?.id == saved.id })
        check(db.bills().allOnce() == listOf(saved) && db.assets().entries() == beforeEntries)
        check(db.recognitions().allOnce().size == 1 && db.recognitions().allOnce().none { it.status == RecognitionStatus.PENDING })
        check(db.capture().forRecord(first.row.id).count { it.source == "DELIVERY" } == 1)
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)!!
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        delay(1500)
        screenshot("dedup-after-save-home.png")
        File(targetContext.filesDir, "dedup-accounting.json").writeText(MiniJson.encode(mapOf(
            "bills" to 1, "history" to 1, "pending" to 0, "balanceCents" to 9600,
            "saveRetries" to repeats.size, "deliveryEvents" to 1, "billId" to saved.id)))
        results += "real_overlay_click_save_late_hook_no_pending_one_bill_one_balance_change_six_idempotent_saves"
    }
    private suspend fun writerRaceCases() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(File(targetContext.filesDir, "dedup-accounting.json").exists()) { "Requires the isolated UI fixture" }
        val db = AppDb.get(targetContext)
        AssetStore.init(targetContext); AssetStore.awaitReady()
        val account = db.assets().accounts().single { it.name == "去重测试钱包" }
        val beforeBills = db.bills().allOnce().size
        val beforeBalance = db.assets().entries().filter { it.accountId == account.id }.sumOf { it.deltaCents }
        var spent = 0L
        for ((index, order) in listOf("save-first", "merge-first", "concurrent-page-first", "concurrent-hook-first").withIndex()) {
            val now = System.currentTimeMillis()
            val value = base.copy(priceCents = 777L + index, timeMillis = now, account = account.name,
                note = "人工备注-$index", noteOrigin = NoteTemplate.USER)
            val firstSource = if (index == 3) "HOOK" else "PAGE"
            val secondSource = if (index == 3) "PAGE" else "HOOK"
            val first = CaptureCoordinator.accept(targetContext, CaptureCoordinator.Input(value, firstSource, "race-first-$index", receivedAt = now))!!
            suspend fun save(): BillWriter.SaveResult {
                val done = CompletableDeferred<BillWriter.SaveResult>()
                BillWriter.saveWithResult(targetContext, first.row.toDraft(), onDone = { done.complete(it) }, onError = { done.completeExceptionally(it) })
                return withTimeout(10000) { done.await() }
            }
            suspend fun merge() = CaptureCoordinator.accept(targetContext, CaptureCoordinator.Input(
                value.copy(note = "迟到来源备注", transactionKey = "race-late-order-$index"), secondSource,
                "race-second-$index", receivedAt = now + 1000))!!
            val pair = when (index) {
                0 -> { val saved = save(); saved to merge() }
                1 -> { val merged = merge(); save() to merged }
                else -> coroutineScope { val a = async { save() }; val b = async { merge() }; a.await() to b.await() }
            }
            check(pair.first.inserted && pair.second.row.id == first.row.id && !pair.second.created)
            check(db.recognitions().byId(first.row.id)?.status == RecognitionStatus.RECORDED)
            check(db.bills().allOnce().single { it.recognitionId == first.row.id }.note == value.note)
            check(db.capture().transactionAliases(first.row.id).contains(CaptureMergePolicy.strongAlias(value.copy(transactionKey = "race-late-order-$index"))))
            spent += value.priceCents
            check(db.bills().allOnce().size == beforeBills + index + 1)
            check(db.assets().entries().filter { it.accountId == account.id }.sumOf { it.deltaCents } == beforeBalance - spent)
            check(db.recognitions().allOnce().none { it.status == RecognitionStatus.PENDING })
            results += "real_writer_${order}_one_bill_one_balance_entry_preserved_manual_note"
        }
        File(targetContext.filesDir, "dedup-writer-races.json").writeText(MiniJson.encode(mapOf(
            "raceCases" to results.size, "newBills" to 4, "spentCents" to spent,
            "pending" to 0, "balanceCents" to beforeBalance - spent)))
    }

    private fun findSaveButton(): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == "记一笔" || node.contentDescription?.toString() == "记一笔") return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        for (window in automation.windows) find(window.root)?.let { return it }
        return find(automation.rootInActiveWindow)
    }

    private fun screenshot(name: String) {
        val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
        File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
