package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlinx.coroutines.*
import java.util.UUID

/** Real Room transactions in an isolated in-memory database; never writes the installed ledger. */
class ConfirmRefinementInstrumentation : Instrumentation() {
    private var style = false
    private var edge = false
    private var visual = false
    private var ui = false
    private val results = mutableListOf<Map<String, Any?>>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); ui = arguments?.getString("ui") == "true"; visual = arguments?.getString("visual") == "true"; edge = arguments?.getString("edge") == "true"; style = arguments?.getString("style") == "true"; start() }
    override fun onStart() {
        if (ui) {
            var failure: Throwable? = null
            var checked: List<String> = emptyList()
            try { checked = if (style) ConfirmRefinementUiChecks(this).bubbleStyle() else if (edge) ConfirmRefinementUiChecks(this).edge() else if (visual) ConfirmRefinementUiChecks(this).visual() else ConfirmRefinementUiChecks(this).run() } catch (error: Throwable) { failure = error }
            finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("report", MiniJson.encode(mapOf("status" to if (failure == null) "passed" else "failed", "results" to checked, "error" to failure?.stackTraceToString())))
            })
            return
        }
        var failure: Throwable? = null
        try { runBlocking(Dispatchers.IO) { tests() } } catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("report", MiniJson.encode(mapOf("database" to "in-memory", "results" to results,
                "status" to if (failure == null) "passed" else "failed", "error" to failure?.stackTraceToString())))
            putInt("tests", results.size)
        })
    }
    private suspend fun test(name: String, action: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try { withTimeout(30_000) { action(db) }; results += mapOf("name" to name, "passed" to true) }
        catch (error: Throwable) { results += mapOf("name" to name, "passed" to false); throw error }
        finally { db.close() }
    }
    private suspend fun account(db: AppDb, name: String): AssetAccount {
        val account = AssetAccount(id = UUID.randomUUID().toString(), name = name, kind = "PAYMENT", initialized = true,
            createdAt = 1, updatedAt = 1)
        db.assets().upsertAccount(account)
        db.assets().insertEntry(AssetEntry(UUID.randomUUID().toString(), UUID.randomUUID().toString(), account.id, 100_000,
            "OPENING", "test", System.currentTimeMillis()))
        return account
    }
    private fun draft(name: String) = BillDraft(1999, BillType.EXPENSE, 1_700_000_012_345, "来源商户", "零钱", "123",
        "io.github.mymoney.manual.${UUID.randomUUID()}", "test", category = "餐饮", subCategory = "午餐", account = name, note = "工作午餐")
    private suspend fun save(db: AppDb, draft: BillDraft, original: BillEntity? = null) = db.withTransaction {
        BillWriter.saveEditorInTransaction(db, draft, original)
    }
    private suspend fun tests() {
        test("date_only_edit_moves_range_without_postings_and_preserves_metadata") { db ->
            val a = account(db, "现金")
            var original = save(db, draft(a.name))
            original = original.copy(sourcePkg = "com.tencent.mm", recognitionId = "history-fixed", fingerprint = "capture-fixed")
            db.bills().update(original)
            val entries = db.assets().entries()
            val changedTime = original.timeMillis + 40L * 86400000
            val changed = save(db, BillWriter.toDraft(original).copy(timeMillis = changedTime, note = null, noteOrigin = NoteTemplate.USER), original)
            check(changed.id == original.id && changed.createdAt == original.createdAt)
            check(changed.fingerprint == original.fingerprint && changed.recognitionId == original.recognitionId)
            check(changed.merchant == original.merchant && changed.note == null)
            check(changed.accountId == original.accountId && changed.assetKey == original.assetKey)
            check(db.assets().entries() == entries && AssetMath.balance(a.id, entries) == 98_001L)
            check(db.bills().range(original.timeMillis - 1, original.timeMillis + 1).isEmpty())
            check(db.bills().range(changedTime - 1, changedTime + 1).single().id == original.id)
            val exported = io.github.mymoney.export.CostCsv.build(listOf(BillWriter.toDraft(changed)))
            check(!exported.contains("来源商户") && !exported.contains("capture-fixed"))
        }
        test("manual_datetime_retry_is_idempotent_and_changes_do_not_double_post") { db ->
            val a = account(db, "卡")
            val d = draft(a.name).copy(merchant = null, note = null)
            val first = save(db, d); val entries = db.assets().entries()
            check(save(db, d).id == first.id && db.assets().entries() == entries)
            check(runCatching { save(db, d.copy(timeMillis = d.timeMillis + 60000)) }.isFailure)
            check(db.bills().allOnce().size == 1 && db.assets().entries() == entries)
            val changed = save(db, d.copy(timeMillis = d.timeMillis + 60000), first)
            check(changed.timeMillis == d.timeMillis + 60000 && changed.id == first.id)
            check(db.assets().entries() == entries)
        }
        test("transfer_date_and_fee_move_together_without_balance_or_entry_changes") { db ->
            val a = account(db, "转出"); val b = account(db, "转入")
            val original = db.withTransaction { BillWriter.saveTransferInTransaction(db, a.id, b.id, 10000, 200, "备注", 1234567) }
            val fee = db.bills().allOnce().single { it.feeParentKey == original.assetKey }
            val entries = db.assets().entries()
            val changed = db.withTransaction { BillWriter.saveTransferInTransaction(db, a.id, b.id, 10000, 200, "备注", 99999999, original) }
            val changedFee = db.bills().byId(fee.id)!!
            check(changed.timeMillis == 99999999L && changedFee.timeMillis == changed.timeMillis)
            check(changed.id == original.id && changed.createdAt == original.createdAt && changed.fingerprint == original.fingerprint)
            check(changedFee.id == fee.id && changedFee.createdAt == fee.createdAt && changedFee.fingerprint == fee.fingerprint)
            check(db.assets().entries() == entries)
            check(AssetMath.balance(a.id, entries) == 89_800L && AssetMath.balance(b.id, entries) == 110_000L)
        }
        test("failed_edit_rolls_back_bill_and_balance_then_retry_succeeds") { db ->
            val a = account(db, "余额账户"); val original = save(db, draft(a.name))
            val entries = db.assets().entries()
            check(runCatching { save(db, BillWriter.toDraft(original).copy(priceCents = -1, timeMillis = 9), original) }.isFailure)
            check(db.bills().byId(original.id) == original && db.assets().entries() == entries)
            check(save(db, BillWriter.toDraft(original).copy(timeMillis = 9), original).timeMillis == 9L)
            check(db.assets().entries() == entries)
        }
    }
}
