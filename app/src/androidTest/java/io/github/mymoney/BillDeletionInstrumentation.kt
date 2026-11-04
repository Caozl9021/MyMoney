package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.mymoney.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Every case owns a new temporary database. Never reads/writes the user's AppDb singleton or preferences. */
class BillDeletionInstrumentation : Instrumentation() {
    private var allowed = false
    private val results = mutableListOf<String>()
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
            check(allowed && virtual) { "Requires an explicit isolated-db argument and Waydroid/emulator; no production DB is used" }
            runBlocking(Dispatchers.IO) { withTimeout(90_000) { cases() } }
        } catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putInt("tests", results.size)
            putString("report", MiniJson.encode(mapOf("results" to results, "error" to failure?.stackTraceToString(),
                "scope" to "isolated-temp-databases-only", "device" to Build.MODEL)))
        })
    }
    private fun fixture(id: Long = 0, key: String = UUID.randomUUID().toString()) = BillEntity(
        id = id, priceCents = 3200, type = "EXPENSE", timeMillis = 1700000000000,
        merchant = "SIM删除测试", method = null, orderTail = null, sourcePkg = "io.github.mymoney.test",
        strategy = "isolated-deletion", fingerprint = key, createdAt = 1700000000000, assetKey = key,
    )
    private suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try { body(db); results += name } finally { db.close() }
    }
    private suspend fun linked(db: AppDb): BillEntity {
        val account = AssetAccount("isolated-account", "测试账户", initialized = true, createdAt = 1, updatedAt = 1)
        db.assets().upsertAccount(account)
        return db.withTransaction {
            val bill = AssetStore.syncBill(db, null, fixture().copy(account = account.name, accountId = account.id, assetLinked = true))!!
            bill.copy(id = db.bills().insert(bill))
        }
    }
    private suspend fun preview(db: AppDb, id: Long) = db.withTransaction { BillWriter.deletionPreviewInTransaction(db, id) }
    private suspend fun delete(db: AppDb, expected: List<BillEntity>) = db.withTransaction { BillWriter.deleteConfirmedInTransaction(db, expected, "instrumentation") }
    private suspend fun cases() {
        test("confirmed_delete_atomic_balance_log_and_idempotent_retry") { db ->
            val bill = linked(db)
            val history = RecognitionEntity.fromDraft(BillWriter.toDraft(bill), "legacy:isolated-delete", 1700000000000)
                .copy(status = RecognitionStatus.RECORDED, billId = bill.id)
            db.recognitions().insert(history)
            db.bills().update(bill.copy(recognitionId = history.id))
            val expected = preview(db, bill.id)
            check(AssetMath.balance("isolated-account", db.assets().entries()) == -3200L)
            delete(db, expected)
            check(db.bills().byId(bill.id) == null)
            check(AssetMath.balance("isolated-account", db.assets().entries()) == 0L)
            check(BillDeletionCodec.bills(db.deletions().allOnce().single()) == expected)
            check(db.recognitions().byId(history.id) == history)
            val entries = db.assets().entries()
            delete(db, expected)
            check(db.deletions().allOnce().size == 1 && db.assets().entries() == entries)
        }
        test("changed_confirmation_rejected_without_writes") { db ->
            val bill = linked(db); val expected = preview(db, bill.id)
            db.bills().update(bill.copy(note = "确认期间变化"))
            val entries = db.assets().entries()
            check(runCatching { delete(db, expected) }.isFailure)
            check(db.bills().byId(bill.id)?.note == "确认期间变化")
            check(db.deletions().allOnce().isEmpty() && db.assets().entries() == entries)
        }
        for ((name, trigger) in listOf(
            "log_insert_failure_rolls_back_everything" to "CREATE TRIGGER fail_log BEFORE INSERT ON bill_deletion_log BEGIN SELECT RAISE(ABORT, 'injected log failure'); END",
            "asset_reversal_failure_rolls_back_log_and_bill" to "CREATE TRIGGER fail_reversal BEFORE INSERT ON asset_entries WHEN NEW.kind='REVERSAL' BEGIN SELECT RAISE(ABORT, 'injected reversal failure'); END",
            "bill_delete_failure_rolls_back_log_and_asset_reversal" to "CREATE TRIGGER fail_bill BEFORE DELETE ON bills BEGIN SELECT RAISE(ABORT, 'injected delete failure'); END",
        )) test(name) { db ->
            val bill = linked(db); val expected = preview(db, bill.id); val entries = db.assets().entries()
            db.openHelper.writableDatabase.execSQL(trigger)
            check(runCatching { delete(db, expected) }.isFailure)
            check(db.bills().byId(bill.id) == bill)
            check(db.deletions().allOnce().isEmpty() && db.assets().entries() == entries)
        }
        test("transfer_and_fee_delete_as_single_frozen_event") { db ->
            val from = AssetAccount("transfer-from", "转出账户", initialized = true, createdAt = 1, updatedAt = 1)
            val to = AssetAccount("transfer-to", "转入账户", initialized = true, createdAt = 1, updatedAt = 1)
            db.assets().upsertAccount(from); db.assets().upsertAccount(to)
            val rows = db.withTransaction {
                val principal = AssetStore.syncBill(db, null, fixture(key = "transfer-key").copy(type = "TRANSFER", priceCents = 10000,
                    transferFeeCents = 50, accountId = from.id, account = from.name, transferAccountId = to.id, transferAccount = to.name, assetLinked = true))!!
                val stored = principal.copy(id = db.bills().insert(principal))
                val fee = AssetStore.syncBill(db, null, fixture(key = "transfer-key-fee").copy(priceCents = 50,
                    accountId = from.id, account = from.name, feeParentKey = principal.assetKey, assetLinked = true))!!
                listOf(stored, fee.copy(id = db.bills().insert(fee)))
            }
            check(AssetMath.balance(from.id, db.assets().entries()) == -10050L)
            check(AssetMath.balance(to.id, db.assets().entries()) == 10000L)
            db.assets().upsertAccount(from.copy(name = "重命名账户", updatedAt = 2))
            val expected = preview(db, rows.first().id)
            check(expected.all { it.account == "重命名账户" })
            delete(db, expected)
            check(db.bills().allOnce().isEmpty() && db.deletions().allOnce().size == 1)
            check(AssetMath.balance(from.id, db.assets().entries()) == 0L && AssetMath.balance(to.id, db.assets().entries()) == 0L)
            check(BillDeletionCodec.bills(db.deletions().allOnce().single()) == expected)
        }
        migrationAndPersistence()
    }
    private fun tableContents(db: AppDb): Map<String, List<String>> {
        val names = mutableListOf<String>()
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata','bill_deletion_log') ORDER BY name").use {
            while (it.moveToNext()) names += it.getString(0)
        }
        return names.associateWith { name ->
            val rows = mutableListOf<String>()
            db.openHelper.readableDatabase.query("SELECT * FROM [$name]").use { cursor ->
                while (cursor.moveToNext()) rows += MiniJson.encode(cursor.columnNames.mapIndexed { index, column ->
                    listOf(column, if (cursor.isNull(index)) null else cursor.getString(index))
                })
            }
            rows.sorted()
        }
    }

    private suspend fun migrationAndPersistence() {
        val name = "isolated-bill-deletion-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(targetContext, AppDb::class.java, name).build()
        try {
            val base = linked(db)
            val history = RecognitionEntity.fromDraft(BillWriter.toDraft(base), "legacy:migration-delete", 1700000000000)
                .copy(status = RecognitionStatus.RECORDED, billId = base.id)
            db.recognitions().insert(history)
            val bill = base.copy(recognitionId = history.id)
            db.bills().update(bill)
            val entries = db.assets().entries()
            val beforeTables = tableContents(db)
            // Materialize the exact pre-feature schema by removing only the new table from a fresh test database.
            db.openHelper.writableDatabase.execSQL("DROP TABLE bill_deletion_log")
            db.openHelper.writableDatabase.execSQL("PRAGMA user_version=9")
            db.close()
            db = Room.databaseBuilder(targetContext, AppDb::class.java, name).addMigrations(AppDb.MIGRATION_9_10).build()
            check(db.openHelper.readableDatabase.version == 10)
            check(db.bills().byId(bill.id) == bill && db.assets().entries() == entries)
            check(db.deletions().allOnce().isEmpty())
            check(db.recognitions().byId(history.id) == history)
            check(tableContents(db) == beforeTables)
            results += "room9_to_10_preserves_every_existing_table_and_populated_bill_history_assets"
            val expected = preview(db, bill.id)
            delete(db, expected)
            val original = db.deletions().allOnce().single()
            db.close()
            db = Room.databaseBuilder(targetContext, AppDb::class.java, name).build()
            check(db.deletions().allOnce().single() == original && db.bills().byId(bill.id) == null)
            results += "deleted_event_survives_disk_database_reopen"
        } finally { db.close(); targetContext.deleteDatabase(name) }
    }
}
