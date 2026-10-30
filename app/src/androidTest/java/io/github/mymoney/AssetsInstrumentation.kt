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

/** Tests use only in-memory Room. They never replace or seed the user's database. */
class AssetsInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try { runBlocking(Dispatchers.IO) { runTests() } } catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf("database" to "in-memory", "results" to results,
                "status" to if (failure == null) "passed" else "failed", "error" to failure?.stackTraceToString())))
            putInt("tests", results.size)
        })
    }

    private suspend fun test(name: String, block: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try {
            withTimeout(30_000) { block(db) }
            results += mapOf("name" to name, "status" to "passed")
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        } finally { db.close() }
    }

    private suspend fun account(db: AppDb, name: String, balance: Long?, kind: String = "PAYMENT"): AssetAccount {
        val now = System.currentTimeMillis()
        val row = AssetAccount(id = UUID.randomUUID().toString(), name = name, kind = kind,
            initialized = balance != null, createdAt = now, updatedAt = now)
        db.assets().upsertAccount(row)
        if (balance != null) db.assets().insertEntry(AssetEntry(id = UUID.randomUUID().toString(),
            operationId = UUID.randomUUID().toString(), accountId = row.id, deltaCents = balance,
            kind = "OPENING", note = "测试当前余额", createdAt = now))
        return row
    }

    private fun draft(name: String, cents: Long = 1999, type: BillType = BillType.EXPENSE): BillDraft = BillDraft(
        priceCents = cents, type = type, timeMillis = 1_700_000_000_000,
        sourcePkg = "io.github.mymoney.manual.${UUID.randomUUID()}", strategy = "测试手动",
        method = null, orderTail = null,
        account = name, merchant = "资产测试", category = "其他",
    )
    private suspend fun balance(db: AppDb, account: AssetAccount) = AssetMath.balance(account.id, db.assets().entries())
    private suspend fun save(db: AppDb, draft: BillDraft, original: BillEntity? = null) =
        db.withTransaction { BillWriter.saveEditorInTransaction(db, draft, original) }
    private suspend fun transfer(db: AppDb, from: AssetAccount, to: AssetAccount, amount: Long,
        fee: Long = 0, original: BillEntity? = null, time: Long = 1_700_000_000_001) = db.withTransaction {
        BillWriter.saveTransferInTransaction(db, from.id, to.id, amount, fee, "测试转账", time, original)
    }

    private suspend fun runTests() {
        test("expense_edit_delete_undo_refund_exact_cents") { db ->
            val a = account(db, "现金", 100_000)
            val initialDraft = draft(a.name)
            var row = save(db, initialDraft)
            check(balance(db, a) == 98_001L && row.assetLinked)
            row = save(db, BillWriter.toDraft(row).copy(priceCents = 2999), row)
            check(balance(db, a) == 97_001L)
            val deleted = db.withTransaction { BillWriter.deleteGroupInTransaction(db, row.id) }
            check(balance(db, a) == 100_000L && db.bills().byId(row.id) == null)
            db.withTransaction { BillWriter.restoreGroupInTransaction(db, deleted) }
            check(balance(db, a) == 97_001L && db.bills().byId(row.id)?.assetKey == row.assetKey)
            save(db, draft(a.name, 999, BillType.REFUND))
            check(balance(db, a) == 98_000L)
        }
        test("change_account_and_type_restore_old_posting") { db ->
            val a = account(db, "卡", 100_000); val b = account(db, "钱包", 20_000)
            var row = save(db, draft(a.name, 1000))
            row = save(db, BillWriter.toDraft(row).copy(account = b.name), row)
            check(balance(db, a) == 100_000L && balance(db, b) == 19_000L && row.accountId == b.id)
            row = save(db, BillWriter.toDraft(row).copy(type = BillType.INCOME), row)
            check(balance(db, b) == 21_000L && row.assetLinked)
        }
        test("credit_spend_and_refund_and_overpayment") { db ->
            val credit = account(db, "信用卡", -30_000, "CREDIT")
            save(db, draft(credit.name, 10_000))
            check(balance(db, credit) == -40_000L)
            save(db, draft(credit.name, 5000, BillType.REFUND))
            check(balance(db, credit) == -35_000L)
            save(db, draft(credit.name, 40_000, BillType.REFUND))
            check(balance(db, credit) == 5000L)
            val summary = AssetMath.summary(db.assets().accounts(), db.assets().entries())
            check(summary.assets == 5000L && summary.debts == 0L && summary.net == 5000L)
        }
        test("legacy_and_uninitialized_never_implicitly_post") { db ->
            val a = account(db, "现金", 100_000); val unset = account(db, "未知余额", null)
            val old = newManualEntity(draft(a.name), 1).copy(assetKey = "legacy-fixture")
            val oldRow = old.copy(id = db.bills().insert(old))
            val edited = save(db, BillWriter.toDraft(oldRow).copy(priceCents = 2999), oldRow)
            check(!edited.assetLinked && balance(db, a) == 100_000L)
            val removed = db.withTransaction { BillWriter.deleteGroupInTransaction(db, edited.id) }
            db.withTransaction { BillWriter.restoreGroupInTransaction(db, removed) }
            check(balance(db, a) == 100_000L)
            val unknown = save(db, draft(unset.name))
            check(!unknown.assetLinked && db.assets().entriesForBill(unknown.assetKey).isEmpty())
            val linked = db.withTransaction {
                AssetStore.syncBill(db, edited, edited, forceLink = true)!!.also { db.bills().update(it) }
            }
            check(linked.assetLinked && balance(db, a) == 97_001L)
            db.withTransaction { AssetStore.syncBill(db, linked, linked, forceLink = true) }
            check(balance(db, a) == 97_001L)
        }
        test("manual_same_editor_retry_is_idempotent") { db ->
            val a = account(db, "现金", 100_000); val d = draft(a.name)
            val one = save(db, d); val two = save(db, d)
            check(one.id == two.id && balance(db, a) == 98_001L && db.bills().allOnce().size == 1)
            check(runCatching { save(db, d.copy(priceCents = 2999)) }.isFailure)
            check(balance(db, a) == 98_001L && db.bills().allOnce().size == 1)
            val before = db.assets().entries().size
            save(db, BillWriter.toDraft(one), one)
            check(balance(db, a) == 98_001L && db.assets().entries().size == before)
        }
        test("transfer_fee_atomic_edit_delete_undo_and_retry") { db ->
            val a = account(db, "储蓄", 100_000); val b = account(db, "微信", 20_000)
            var row = transfer(db, a, b, 10_000, 200)
            check(balance(db, a) == 89_800L && balance(db, b) == 30_000L)
            check(db.bills().allOnce().count { it.type == "TRANSFER" } == 1)
            check(db.bills().allOnce().single { it.type == "EXPENSE" }.priceCents == 200L)
            val repeated = transfer(db, a, b, 10_000, 200)
            check(repeated.id == row.id && balance(db, a) == 89_800L)
            row = transfer(db, a, b, 5000, 100, row)
            check(balance(db, a) == 94_900L && balance(db, b) == 25_000L)
            val deleted = db.withTransaction { BillWriter.deleteGroupInTransaction(db, row.id) }
            check(deleted.size == 2 && balance(db, a) == 100_000L && balance(db, b) == 20_000L)
            db.withTransaction { BillWriter.restoreGroupInTransaction(db, deleted) }
            check(balance(db, a) == 94_900L && balance(db, b) == 25_000L)
            row = transfer(db, a, b, 5000, 0, row)
            check(balance(db, a) == 95_000L && balance(db, b) == 25_000L)
            check(db.bills().allOnce().none { it.feeParentKey == row.assetKey })
        }
        test("repayment_net_asset_invariant_and_old_transfer_completion") { db ->
            val a = account(db, "储蓄", 100_000); val b = account(db, "信用卡", -30_000, "CREDIT")
            val old = newManualEntity(draft(a.name, 10_000, BillType.TRANSFER), 1).copy(assetKey = "legacy-transfer")
            val oldRow = old.copy(id = db.bills().insert(old))
            val row = transfer(db, a, b, 10_000, original = oldRow)
            check(row.id == oldRow.id && balance(db, a) == 90_000L && balance(db, b) == -20_000L)
            check(AssetMath.summary(db.assets().accounts(), db.assets().entries()).net == 70_000L)
            transfer(db, a, b, 10_000, original = row)
            check(balance(db, a) == 90_000L && balance(db, b) == -20_000L)
        }
        test("invalid_endpoints_and_failure_roll_back_both_sides") { db ->
            val a = account(db, "储蓄", 100_000); val b = account(db, "微信", 20_000)
            val unset = account(db, "未设置", null)
            check(runCatching { transfer(db, a, a, 1000) }.isFailure)
            check(runCatching { transfer(db, a, unset, 1000) }.isFailure)
            check(runCatching {
                db.withTransaction {
                    BillWriter.saveTransferInTransaction(db, a.id, b.id, 1000, 200, "失败注入", 1234)
                    error("故障注入：分录及账单必须全部回滚")
                }
            }.isFailure)
            check(balance(db, a) == 100_000L && balance(db, b) == 20_000L && db.bills().allOnce().isEmpty())
        }
        test("stable_form_operation_rejects_changed_replay_and_keeps_edit_identity") { db ->
            val a = account(db, "储蓄", 100_000); val b = account(db, "微信", 20_000)
            val op = UUID.randomUUID().toString()
            val row = db.withTransaction {
                BillWriter.saveTransferInTransaction(db, a.id, b.id, 10_000, 200, "操作ID", 1, operationId = op)
            }
            val same = db.withTransaction {
                BillWriter.saveTransferInTransaction(db, a.id, b.id, 10_000, 200, "操作ID", 1, operationId = op)
            }
            check(row.id == same.id)
            val entries = db.assets().entries()
            check(runCatching { db.withTransaction {
                BillWriter.saveTransferInTransaction(db, a.id, b.id, 20_000, 200, "操作ID", 1, operationId = op)
            } }.isFailure)
            check(db.assets().entries() == entries && balance(db, a) == 89_800L)
            val edited = db.withTransaction {
                BillWriter.saveTransferInTransaction(db, b.id, a.id, 5000, 0, "改两端", 999, row, UUID.randomUUID().toString())
            }
            check(edited.id == row.id && edited.assetKey == row.assetKey && edited.createdAt == row.createdAt)
            check(edited.timeMillis == 999L && balance(db, a) == 105_000L && balance(db, b) == 15_000L)
        }
        test("complete_605_row_postings_and_uuid_account_filter") { db ->
            val a = account(db, "主卡", 1_000_000); val b = account(db, "副卡", 100_000)
            db.withTransaction {
                repeat(605) { index ->
                    val source = draft(if (index % 2 == 0) a.name else b.name, 100)
                    val fresh = newManualEntity(source, index.toLong())
                    val row = AssetStore.syncBill(db, null, fresh)!!
                    db.bills().insert(row)
                }
            }
            val rows = db.bills().allOnce()
            check(rows.size == 605)
            check(rows.count { it.accountId == a.id } == 303 && rows.count { it.accountId == b.id } == 302)
            check(db.assets().entries().count { it.billKey != null } == 605)
            check(balance(db, a) == 969_700L && balance(db, b) == 69_800L)
            val renamed = a.copy(name = "改名主卡", updatedAt = System.currentTimeMillis())
            db.assets().upsertAccount(renamed)
            check(db.bills().allOnce().count { it.accountId == renamed.id } == 303)
            check(db.bills().allOnce().none { it.account == renamed.name })
            check(AssetMath.summary(db.assets().accounts(), db.assets().entries()).net == 1_039_500L)
        }
        test("archived_account_delete_and_historical_undo_preserve_balance") { db ->
            val a = account(db, "现金", 100_000)
            val row = save(db, draft(a.name))
            db.assets().upsertAccount(a.copy(archived = true))
            val deleted = db.withTransaction { BillWriter.deleteGroupInTransaction(db, row.id) }
            check(balance(db, a) == 100_000L)
            db.withTransaction { BillWriter.restoreGroupInTransaction(db, deleted) }
            check(balance(db, a) == 98_001L && db.bills().byId(row.id)?.assetLinked == true)
            check(db.assets().account(a.id)?.archived == true)
        }
        test("concurrent_same_editor_and_transfer_replay_commit_once") { db ->
            val a = account(db, "主卡", 100_000); val b = account(db, "钱包", 20_000)
            val d = draft(a.name)
            val saved = coroutineScope { (0 until 8).map { async(Dispatchers.IO) { save(db, d) } }.awaitAll() }
            check(saved.map { it.id }.distinct().size == 1)
            check(db.bills().allOnce().size == 1 && balance(db, a) == 98_001L)
            val transferred = coroutineScope {
                (0 until 8).map { async(Dispatchers.IO) { transfer(db, a, b, 10_000, 200) } }.awaitAll()
            }
            check(transferred.map { it.id }.distinct().size == 1)
            check(db.bills().allOnce().size == 3 && balance(db, a) == 87_801L && balance(db, b) == 30_000L)
        }
        test("deleting_post_adjustment_extreme_balance_rolls_back_on_overflow") { db ->
            val a = account(db, "极值账户", Long.MAX_VALUE)
            val row = save(db, draft(a.name, 1))
            check(balance(db, a) == Long.MAX_VALUE - 1)
            db.assets().insertEntry(AssetEntry(id = UUID.randomUUID().toString(),
                operationId = UUID.randomUUID().toString(), accountId = a.id, deltaCents = 1,
                kind = "ADJUSTMENT", note = "调回最大合法值", createdAt = db.assets().latestEntryTime()!! + 1))
            check(balance(db, a) == Long.MAX_VALUE)
            val before = db.assets().entries()
            check(runCatching { db.withTransaction { BillWriter.deleteGroupInTransaction(db, row.id) } }.isFailure)
            check(db.bills().byId(row.id) == row && db.assets().entries() == before)
            check(balance(db, a) == Long.MAX_VALUE)
        }
        test("independent_deletion_undo_collision_is_all_or_nothing") { db ->
            val a = account(db, "储蓄", 100_000); val b = account(db, "微信", 20_000)
            val transfer = transfer(db, a, b, 10_000, 200)
            val second = save(db, draft(a.name, 1999))
            val group = db.withTransaction { BillWriter.deleteGroupInTransaction(db, transfer.id) }
            val other = db.withTransaction { BillWriter.deleteGroupInTransaction(db, second.id) }
            check(balance(db, a) == 100_000L)
            db.withTransaction { BillWriter.restoreGroupInTransaction(db, other) }
            check(balance(db, a) == 98_001L)
            db.bills().insert(second.copy(id = group.last().id, assetKey = "collision-only", assetLinked = false))
            val before = db.assets().entries()
            check(runCatching { db.withTransaction { BillWriter.restoreGroupInTransaction(db, group) } }.isFailure)
            check(db.assets().entries() == before && db.bills().byId(group.first().id) == null)
        }
    }
}
