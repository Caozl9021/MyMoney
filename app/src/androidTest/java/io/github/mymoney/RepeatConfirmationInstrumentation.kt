package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.capture.CaptureCoordinator
import io.github.mymoney.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Default cases use only in-memory Room. Explicit ui=waydroid-only adds two temporary
 * recognition fixtures to the Waydroid DB, opens the actual card, then removes exactly those IDs.
 * Neither mode proves real WeChat/Alipay automatic presentation.
 */
class RepeatConfirmationInstrumentation : Instrumentation() {
    private var ui = false
    private val results = mutableListOf<Map<String, Any?>>()
    private val at = 1_793_876_963_000L
    private val draft = BillDraft(1890, BillType.EXPENSE, at, "合成重复确认商户", "合成支付方式", null,
        "com.tencent.mm", "synthetic-repeat-confirmation", transactionKey = "synthetic-repeat-order",
        hasExplicitTime = true)

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); ui = arguments?.getString("ui") == "waydroid-only"; start() }

    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try { runBlocking(Dispatchers.IO) { if (ui) uiCase() else cases() } }
        catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putInt("tests", results.size)
            putString("report", MiniJson.encode(mapOf("results" to results,
                "error" to failure?.stackTraceToString(), "source" to if (ui) "synthetic-waydroid-confirm-activity" else "synthetic-in-memory-room",
                "productionDbAccess" to ui)))
        })
    }

    private fun input(observation: String, value: BillDraft = draft, source: String = "PAGE",
                      offset: Long = 0, trace: String = UUID.randomUUID().toString()) =
        CaptureCoordinator.Input(value, source, observation, trace, at + offset)

    private suspend fun accept(db: AppDb, value: CaptureCoordinator.Input) =
        db.withTransaction { CaptureCoordinator.acceptInTransaction(db, value) }

    private suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        val started = System.currentTimeMillis()
        try {
            withTimeout(30_000) { body(db) }
            results += mapOf("name" to name, "status" to "passed",
                "durationMs" to System.currentTimeMillis() - started)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        } finally { db.close() }
    }

    private suspend fun uiCase() {
        check(android.os.Build.MODEL.contains("WayDroid", ignoreCase = true)) { "UI fixtures require explicit Waydroid" }
        val automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        RecognitionStore.init(targetContext); RecognitionStore.awaitReady()
        val db = AppDb.get(targetContext)
        fun dataSnapshot(): Map<String, String> {
            val snapshot = sortedMapOf<String, String>()
            val sql = db.openHelper.readableDatabase
            val names = mutableListOf<String>()
            sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0)
            }
            for (name in names) {
                val rows = mutableListOf<String>()
                sql.query("SELECT * FROM [" + name.replace("]", "]]") + "]").use { cursor ->
                    while (cursor.moveToNext()) rows += MiniJson.encode((0 until cursor.columnCount).map { index ->
                        if (cursor.getType(index) == android.database.Cursor.FIELD_TYPE_BLOB)
                            cursor.getBlob(index).joinToString("") { "%02x".format(it) }
                        else cursor.getString(index)
                    })
                }
                snapshot["table:$name"] = CaptureMergePolicy.digest(MiniJson.encode(rows.sorted()))
            }
            java.io.File(targetContext.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.extension == "xml" }?.forEach {
                snapshot["prefs:${it.name}"] = CaptureMergePolicy.digest(it.readText())
            }
            return snapshot
        }
        val snapshotBefore = dataSnapshot()
        val before = db.recognitions().allOnce()
        val billsBefore = db.bills().allOnce()
        val unique = UUID.randomUUID().toString()
        val value = draft.copy(sourcePkg = "io.github.mymoney.synthetic.repeat", transactionKey = unique,
            merchant = "SIM已忽略后再确认", timeMillis = System.currentTimeMillis())
        val first = CaptureCoordinator.accept(targetContext, input("ui-first-$unique", value))!!
        var second: CaptureCoordinator.Accepted? = null
        var fixtureBillId: Long? = null
        try {
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            second = CaptureCoordinator.accept(targetContext, input("ui-second-$unique", value))!!
            check(second.created && second.deliverable && second.row.identityKey.startsWith("repeat:"))
            targetContext.startActivity(io.github.mymoney.capture.ConfirmIntents.intent(targetContext, second.row.toDraft()))
            fun hasText(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
                if (node == null) return false
                if (node.text?.toString() == text) return true
                return (0 until node.childCount).any { hasText(node.getChild(it), text) }
            }
            withTimeout(15_000) {
                while (true) {
                    if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                    if (hasText(automation.rootInActiveWindow, "记一笔")) break
                    kotlinx.coroutines.delay(100)
                }
            }
            check(!hasText(automation.rootInActiveWindow, "（可能重复）"))
            val bitmap = automation.takeScreenshot() ?: error("No screenshot")
            java.io.File(targetContext.getExternalFilesDir(null), "repeat-confirmation-ui.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            check(db.bills().allOnce() == billsBefore)
            results += mapOf("name" to "actual_confirm_activity_reopens_ignored_without_false_duplicate_label", "status" to "passed")
            repeat(3) {
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                kotlinx.coroutines.delay(350)
            }
            // Only an actual ledger match may turn the badge on. This is a tagged temporary
            // synthetic row, not a save operation or a change to any existing bill/account.
            fixtureBillId = db.bills().insert(BillEntity(priceCents = value.priceCents, type = value.type.name,
                timeMillis = value.timeMillis, merchant = value.merchant, method = value.method,
                orderTail = null, sourcePkg = value.sourcePkg, strategy = value.strategy,
                fingerprint = value.fingerprint, createdAt = System.currentTimeMillis(), account = value.account))
            targetContext.startActivity(io.github.mymoney.capture.ConfirmIntents.intent(targetContext, second.row.toDraft()))
            withTimeout(15_000) {
                while (true) {
                    if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                    if (hasText(automation.rootInActiveWindow, "（可能重复）")) break
                    kotlinx.coroutines.delay(100)
                }
            }
            val matchedBitmap = automation.takeScreenshot() ?: error("No matched screenshot")
            java.io.File(targetContext.getExternalFilesDir(null), "repeat-confirmation-ledger-match-ui.png").outputStream().use {
                matchedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            matchedBitmap.recycle()
            results += mapOf("name" to "actual_card_marks_duplicate_only_after_a_matching_ledger_bill_exists", "status" to "passed")
        } finally {
            repeat(3) {
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                kotlinx.coroutines.delay(350)
            }
            db.withTransaction {
                fixtureBillId?.let { db.openHelper.writableDatabase.execSQL("DELETE FROM bills WHERE id=?", arrayOf(it)) }
                for (id in listOfNotNull(second?.row?.id, first.row.id)) {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM recognition_history WHERE id=?", arrayOf(id))
                }
            }
            check(db.recognitions().allOnce() == before && db.bills().allOnce() == billsBefore)
            val snapshotAfter = dataSnapshot()
            java.io.File(targetContext.getExternalFilesDir(null), "repeat-ui-data-comparison.json").writeText(
                MiniJson.encode(mapOf("before" to snapshotBefore, "after" to snapshotAfter, "equal" to (snapshotBefore == snapshotAfter))))
            check(snapshotBefore == snapshotAfter) { "Existing tables or preferences changed" }
            results += mapOf("name" to "exact_fixture_cleanup_preserves_all_tables_and_preferences", "status" to "passed")
        }
    }

    private suspend fun cases() {
        for (status in listOf(RecognitionStatus.RECORDED, RecognitionStatus.IGNORED, RecognitionStatus.PENDING)) {
            test("new_visit_after_${status.lowercase()}_creates_independent_confirmation") { db ->
                val first = accept(db, input("visit-one"))
                val old = first.row.copy(status = status, billId = if (status == RecognitionStatus.RECORDED) 41 else null)
                db.recognitions().update(old)
                db.capture().putDelivery(CaptureDelivery(old.id, CaptureDeliveryState.ACKNOWLEDGED, "shown", at))
                val aliases = db.capture().transactionAliases(old.id)
                val second = accept(db, input("visit-two", offset = 2_000))
                check(second.created && second.deliverable)
                check(second.decision == "repeat-confirmation")
                check(second.row.id != old.id && second.row.identityKey.startsWith("repeat:"))
                check(second.row.status == RecognitionStatus.PENDING && second.row.reviewOf == null)
                check(second.row.billId == null)
                check(db.recognitions().byId(old.id) == old)
                check(db.capture().transactionAliases(old.id) == aliases)
                check(db.capture().forRecord(second.row.id).any { old.id in it.detail })
                check(db.bills().allOnce().isEmpty() && db.assets().entries().isEmpty())
            }
        }

        test("same_visit_replay_reuses_confirmation_then_does_not_redeliver_acknowledged_card") { db ->
            val first = accept(db, input("visit-one"))
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            val second = accept(db, input("visit-two"))
            val repeated = accept(db, input("visit-two", offset = 500))
            check(!repeated.created && repeated.row.id == second.row.id)
            check(db.recognitions().allOnce().size == 2)
            db.capture().putDelivery(CaptureDelivery(second.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown", at))
            val afterPresentation = accept(db, input("visit-two", offset = 1_000))
            check(!afterPresentation.created && !afterPresentation.deliverable)
            check(afterPresentation.row.id == second.row.id && db.recognitions().allOnce().size == 2)
        }

        test("same_visit_after_ignoring_repeat_does_not_create_another_confirmation") { db ->
            val first = accept(db, input("visit-one"))
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            val second = accept(db, input("visit-two"))
            val ignored = second.row.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(ignored)
            val repeated = accept(db, input("visit-two", offset = 2_000))
            check(!repeated.created && !repeated.deliverable && repeated.row.id == ignored.id)
            check(db.recognitions().byId(ignored.id) == ignored)
            check(db.recognitions().allOnce().size == 2)
        }

        test("third_visit_can_confirm_again_without_changing_previous_ignored_rows") { db ->
            val first = accept(db, input("visit-one"))
            val original = first.row.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(original)
            val second = accept(db, input("visit-two"))
            val ignored = second.row.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(ignored)
            val third = accept(db, input("visit-three", offset = 4_000))
            check(third.created && third.deliverable && third.row.identityKey.startsWith("repeat:"))
            check(third.row.id !in setOf(first.row.id, second.row.id))
            check(db.recognitions().byId(first.row.id) == original)
            check(db.recognitions().byId(second.row.id) == ignored)
            check(db.recognitions().allOnce().size == 3)
        }

        for (state in listOf<String?>(null, CaptureDeliveryState.FAILED, CaptureDeliveryState.SUPPRESSED)) {
            test("undelivered_${state ?: "none"}_reuses_original_pending_row") { db ->
                val first = accept(db, input("visit-one"))
                if (state != null) db.capture().putDelivery(CaptureDelivery(first.row.id, state, "not-shown", at))
                val next = accept(db, input("visit-two", offset = 2_000))
                check(!next.created && next.deliverable && next.row.id == first.row.id)
                check(!next.row.identityKey.startsWith("repeat:") && db.recognitions().allOnce().size == 1)
            }
        }

        test("different_order_with_equal_amount_remains_a_new_nonrepeat_transaction") { db ->
            val first = accept(db, input("visit-one"))
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            val next = accept(db, input("visit-two", draft.copy(transactionKey = "different-synthetic-order")))
            check(next.created && next.deliverable && next.row.id != first.row.id)
            check(!next.row.identityKey.startsWith("repeat:") && next.row.reviewOf == null)
            check(next.decision == "new")
        }

        test("same_trace_is_idempotent_even_after_original_is_ignored") { db ->
            val event = input("visit-one", trace = "synthetic-stable-event")
            val first = accept(db, event)
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            val replay = accept(db, event)
            check(!replay.created && !replay.deliverable && replay.row.id == first.row.id)
            check(db.recognitions().allOnce().size == 1)
        }

        test("active_editor_receives_enrichment_without_stacking_another_card") { db ->
            val first = accept(db, input("visit-one"))
            db.capture().putDelivery(CaptureDelivery(first.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown", at))
            CaptureEditLocks.acquire(first.row.id)
            try {
                val next = accept(db, input("visit-two", offset = 2_000))
                check(!next.created && !next.deliverable && next.row.id == first.row.id)
                check(db.recognitions().allOnce().size == 1)
            } finally { CaptureEditLocks.release(first.row.id) }
        }

        test("time_only_identity_survives_three_visits_without_a_transaction_key") { db ->
            val timeOnly = draft.copy(transactionKey = null)
            val first = accept(db, input("time-visit-one", timeOnly))
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            val second = accept(db, input("time-visit-two", timeOnly, offset = 2_000))
            check(second.created && second.deliverable && second.row.identityKey.startsWith("repeat:"))
            db.recognitions().update(second.row.copy(status = RecognitionStatus.IGNORED))
            val third = accept(db, input("time-visit-three", timeOnly, offset = 4_000))
            check(third.created && third.deliverable && third.row.identityKey.startsWith("repeat:"))
            check(third.row.id !in setOf(first.row.id, second.row.id))
            check(db.recognitions().allOnce().size == 3)
        }

        test("legacy_page_observation_alias_can_start_a_repeat_confirmation") { db ->
            val timeOnly = draft.copy(transactionKey = null)
            // Older PAGE records could use capture: identities and only a page-identity alias.
            val old = RecognitionEntity.fromDraft(timeOnly, "capture:synthetic-legacy", at)
                .copy(status = RecognitionStatus.IGNORED)
            db.recognitions().insert(old)
            val legacyAlias = "obs:" + CaptureMergePolicy.digest("page:" + RecognitionIdentity.key(timeOnly))
            db.capture().putAlias(CaptureAlias(legacyAlias, old.id))
            val next = accept(db, input("time-visit-after-upgrade", timeOnly, offset = 2_000))
            check(next.created && next.deliverable && next.row.identityKey.startsWith("repeat:"))
            check(next.decision == "repeat-confirmation")
            check(db.recognitions().byId(old.id) == old)
            check(db.capture().alias(legacyAlias)?.recognitionId == old.id)
        }

        test("same_visit_time_then_order_enriches_the_presented_record_without_another_card") { db ->
            val timeOnly = draft.copy(transactionKey = null)
            val first = accept(db, input("page-visit:one", timeOnly))
            db.capture().putDelivery(CaptureDelivery(first.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown", at))
            val withOrder = accept(db, input("page-visit:one", draft, offset = 2_000))
            check(!withOrder.created && !withOrder.deliverable && withOrder.row.id == first.row.id)
            check(withOrder.row.transactionKey == draft.transactionKey)
            check(db.recognitions().allOnce().size == 1)
            check(db.capture().alias(CaptureMergePolicy.strongAlias(draft)!!)?.recognitionId == first.row.id)
        }

        test("a_later_visit_can_add_an_order_to_a_legacy_time_only_parent") { db ->
            val timeOnly = draft.copy(transactionKey = null)
            val first = accept(db, input("page-visit:one", timeOnly))
            val ignored = first.row.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(ignored)
            val withOrder = accept(db, input("page-visit:two", draft, offset = 2_000))
            check(withOrder.created && withOrder.deliverable && withOrder.decision == "repeat-confirmation")
            check(withOrder.row.identityKey.startsWith("repeat:") && withOrder.row.transactionKey == draft.transactionKey)
            check(db.recognitions().byId(first.row.id) == ignored)
            check(db.capture().alias(CaptureMergePolicy.strongAlias(draft)!!)?.recognitionId == first.row.id)
        }

        test("known_different_order_wins_over_the_same_time_only_fallback") { db ->
            val first = accept(db, input("page-visit:one", draft.copy(transactionKey = null)))
            accept(db, input("page-visit:one", draft, offset = 2_000))
            val original = db.recognitions().byId(first.row.id)!!.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(original)
            val other = draft.copy(transactionKey = "synthetic-second-order-at-same-time")
            val second = accept(db, input("page-visit:two", other, offset = 4_000))
            check(second.created && second.deliverable && second.decision == "new")
            check(second.row.id != first.row.id && !second.row.identityKey.startsWith("repeat:"))
            check(db.recognitions().byId(first.row.id) == original)
        }

        test("late_order_on_repeat_child_protects_time_parent_from_a_different_order") { db ->
            val timeOnly = draft.copy(transactionKey = null)
            val first = accept(db, input("page-visit:one", timeOnly))
            val ignored = first.row.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(ignored)
            val second = accept(db, input("page-visit:two", timeOnly, offset = 2_000))
            check(second.created && second.row.identityKey.startsWith("repeat:"))
            db.capture().putDelivery(CaptureDelivery(second.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown", at))
            val withOrder = accept(db, input("page-visit:two", draft, offset = 4_000))
            check(!withOrder.created && !withOrder.deliverable && withOrder.row.id == second.row.id)
            val other = draft.copy(transactionKey = "synthetic-independent-order-after-child-enrichment")
            val third = accept(db, input("page-visit:three", other, offset = 6_000))
            check(third.created && third.deliverable && third.decision == "new")
            check(!third.row.identityKey.startsWith("repeat:") && third.row.id !in setOf(first.row.id, second.row.id))
            check(db.recognitions().byId(first.row.id) == ignored)
        }

        test("late_source_enriches_the_active_repeat_child_without_stacking_another_card") { db ->
            val first = accept(db, input("page-visit:one"))
            val ignored = first.row.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(ignored)
            val second = accept(db, input("page-visit:two", offset = 2_000))
            db.capture().putDelivery(CaptureDelivery(second.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown", at))
            CaptureEditLocks.acquire(second.row.id)
            try {
                val late = accept(db, input("late-notification", draft.copy(productName = "合成迟到商品"),
                    source = "NOTIFICATION", offset = 3_000))
                check(!late.created && !late.deliverable && late.row.id == second.row.id)
                check(late.row.productName == "合成迟到商品")
                check(db.recognitions().byId(first.row.id) == ignored)
                check(db.recognitions().allOnce().size == 2)
            } finally { CaptureEditLocks.release(second.row.id) }
        }

        test("explicit_repeat_save_adds_one_bill_and_one_debit_while_preserving_old_bill") { db ->
            val account = AssetAccount("synthetic-repeat-account", "合成账户", initialized = true,
                createdAt = at, updatedAt = at)
            db.assets().upsertAccount(account)
            db.assets().insertEntry(AssetEntry("synthetic-opening", "synthetic-opening-op", account.id,
                10000, "OPENING", "synthetic", at))
            val value = draft.copy(account = account.name)
            val first = accept(db, input("visit-one", value))
            val oldSave = db.withTransaction { BillWriter.saveInTransaction(db, first.row.toDraft()) }
            check(oldSave.inserted && oldSave.bill != null)
            val oldBill = db.bills().byId(oldSave.bill!!.id)!!
            val oldRecord = db.recognitions().byId(first.row.id)!!
            val oldEntries = db.assets().entries()
            val second = accept(db, input("visit-two", value, offset = 2_000))
            check(second.created && second.row.identityKey.startsWith("repeat:"))
            check(db.bills().allOnce() == listOf(oldBill) && db.assets().entries() == oldEntries)
            val saved = db.withTransaction { BillWriter.saveInTransaction(db, second.row.toDraft()) }
            val entriesAfterSave = db.assets().entries()
            val retried = db.withTransaction { BillWriter.saveInTransaction(db, second.row.toDraft()) }
            check(saved.inserted && !retried.inserted)
            check(saved.bill?.id == retried.bill?.id && saved.bill?.id != oldBill.id)
            check(db.bills().allOnce().size == 2 && db.bills().byId(oldBill.id) == oldBill)
            check(db.recognitions().byId(first.row.id) == oldRecord)
            check(db.recognitions().byId(second.row.id)?.status == RecognitionStatus.RECORDED)
            check(db.assets().entries() == entriesAfterSave)
            check(entriesAfterSave.count { it.kind == "BILL" } == 2)
            check(AssetMath.balance(account.id, entriesAfterSave) == 10000 - value.priceCents * 2)
        }

        test("explicit_save_after_ignored_original_records_only_the_new_confirmation") { db ->
            val first = accept(db, input("visit-one"))
            val ignored = first.row.copy(status = RecognitionStatus.IGNORED)
            db.recognitions().update(ignored)
            val second = accept(db, input("visit-two", offset = 2_000))
            val saved = db.withTransaction { BillWriter.saveInTransaction(db, second.row.toDraft()) }
            val retried = db.withTransaction { BillWriter.saveInTransaction(db, second.row.toDraft()) }
            check(saved.inserted && !retried.inserted && saved.bill?.id == retried.bill?.id)
            check(db.bills().allOnce().size == 1)
            check(db.recognitions().byId(first.row.id) == ignored)
            check(db.recognitions().byId(second.row.id)?.billId == saved.bill?.id)
        }
    }
}
