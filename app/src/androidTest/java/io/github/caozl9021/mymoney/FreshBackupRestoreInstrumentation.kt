package io.github.caozl9021.mymoney

import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import io.github.caozl9021.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlinx.coroutines.runBlocking
import java.io.File

/** Only isolated in-memory databases; the target application's persistent database is never used. */
class FreshBackupRestoreInstrumentation : Instrumentation() {
    private var backupFile: String? = null
    override fun onCreate(arguments: Bundle?) {
        backupFile = arguments?.getString("backupFile")
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            runBlocking {
                suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
                    val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                    try {
                        AssetStore.migrateAccounts(db, LedgerConfig.DEFAULT_ACCOUNTS)
                        check(AssetBackupCodec.isUntouchedDefaultSnapshot(
                            AssetSnapshot(db.assets().accounts(), db.assets().entries()), LedgerConfig.DEFAULT_ACCOUNTS))
                        body(db)
                        checks += name
                    } finally { db.close() }
                }
                fun source(): Backup.Snapshot {
                    val name = "支付宝"
                    val id = java.util.UUID.nameUUIDFromBytes(("legacy-account:" + name).toByteArray(Charsets.UTF_8)).toString()
                    val account = AssetAccount(id, name, initialized = true, createdAt = 1, updatedAt = 2)
                    val opening = AssetEntry("opening", "opening-op", id, 100_000, "OPENING", "余额", 1)
                    val expense = AssetEntry("expense", "expense-op", id, -1999, "BILL", "早餐", 2, "bill-key")
                    val bill = Backup.Bill(1999, "EXPENSE", 2, "早餐", null, null, "manual", "手动", "fp", 2,
                        "餐饮", null, name, null, id = 7, accountId = id, assetLinked = true, assetKey = "bill-key")
                    return Backup.Snapshot(3, listOf(bill), null, null, history = emptyList(),
                        assets = AssetSnapshot(listOf(account), listOf(opening, expense)))
                }
                suspend fun digest(db: AppDb): List<Any> = listOf(db.bills().allOnce(), db.recognitions().allOnce(),
                    db.assets().accounts(), db.assets().entries(), db.imports().all(),
                    db.assets().meta("pending-restore-config").orEmpty(), db.assets().meta("legacy-accounts-v1").orEmpty())
                val incoming = Backup.decode(Backup.encode(source()))
                test("fresh-production-defaults-merge-restores-and-repeats-idempotently") { db ->
                    check(BackupStore.restoreDatabase(db, incoming, BackupStore.Mode.MERGE).first == 1)
                    check(db.assets().accounts() == incoming.assets!!.accounts)
                    check(db.assets().entries().toSet() == incoming.assets!!.entries.toSet())
                    check(AssetMath.balance(incoming.assets!!.accounts.single().id, db.assets().entries()) == 98_001L)
                    check(db.bills().allOnce().single().accountId == incoming.assets!!.accounts.single().id)
                    val before = digest(db)
                    check(BackupStore.restoreDatabase(db, incoming, BackupStore.Mode.MERGE).first == 0)
                    check(digest(db) == before)
                    val conflict = incoming.copy(assets = incoming.assets!!.copy(accounts =
                        listOf(incoming.assets!!.accounts.single().copy(updatedAt = 3))))
                    check(runCatching { BackupStore.restoreDatabase(db, conflict, BackupStore.Mode.MERGE) }.isFailure)
                    check(digest(db) == before)
                }
                test("edited-default-protected-without-business-records") { db ->
                    val account = db.assets().accounts().first { it.name == "支付宝" }
                    db.assets().upsertAccount(account.copy(included = false))
                    val before = digest(db)
                    check(runCatching { BackupStore.restoreDatabase(db, incoming, BackupStore.Mode.MERGE) }.isFailure)
                    check(digest(db) == before)
                }
                test("local-recognition-blocks-default-initialization") { db ->
                    db.recognitions().insert(RecognitionEntity.fromDraft(BillDraft(100, BillType.EXPENSE, 1,
                        null, null, null, "synthetic", "test", historyId = "local-history"), at = 1))
                    val before = digest(db)
                    check(runCatching { BackupStore.restoreDatabase(db, incoming, BackupStore.Mode.MERGE) }.isFailure)
                    check(digest(db) == before)
                }
                test("local-bill-blocks-default-initialization") { db ->
                    db.bills().insert(BillEntity(priceCents = 100, type = "EXPENSE", timeMillis = 1, merchant = null, method = null,
                        orderTail = null, sourcePkg = "synthetic", strategy = "test", fingerprint = "local-fp", createdAt = 1))
                    val before = digest(db)
                    check(runCatching { BackupStore.restoreDatabase(db, incoming, BackupStore.Mode.MERGE) }.isFailure)
                    check(digest(db) == before)
                }
                test("import-tombstone-blocks-default-initialization") { db ->
                    db.imports().insert(ImportIdentity("qianji:" + "a".repeat(64), "b".repeat(64)))
                    val before = digest(db)
                    check(runCatching { BackupStore.restoreDatabase(db, incoming, BackupStore.Mode.MERGE) }.isFailure)
                    check(digest(db) == before)
                }
                test("empty-assets-and-legacy-merge-preserve-defaults") { db ->
                    val defaults = db.assets().accounts()
                    BackupStore.restoreDatabase(db, Backup.Snapshot(3, emptyList(), null, null,
                        assets = AssetSnapshot(emptyList(), emptyList())), BackupStore.Mode.MERGE)
                    check(db.assets().accounts() == defaults)
                    BackupStore.restoreDatabase(db, Backup.decode("""{"format":1,"bills":[]}"""), BackupStore.Mode.MERGE)
                    check(db.assets().accounts() == defaults)
                }
                test("failure-after-default-replacement-rolls-back-all-writes") { db ->
                    val before = digest(db)
                    // A dangling reviewOf fails after asset and bill writes, within the same transaction.
                    val history = RecognitionEntity.fromDraft(BillDraft(100, BillType.EXPENSE, 1,
                        null, null, null, "synthetic", "test", historyId = "bad-history"), at = 1).copy(reviewOf = "missing")
                    check(runCatching { BackupStore.restoreDatabase(db, incoming.copy(history = listOf(history)),
                        BackupStore.Mode.MERGE) }.isFailure)
                    check(digest(db) == before)
                }
                backupFile?.let { path ->
                    val snapshot = Backup.decode(File(path).readText())
                    test("user-json8-production-restore-exact-assets-history-identities-and-repeat") { db ->
                        check(BackupStore.restoreDatabase(db, snapshot, BackupStore.Mode.MERGE).first == snapshot.bills.size)
                        check(db.bills().allOnce().size == snapshot.bills.size)
                        check(db.assets().accounts().toSet() == snapshot.assets!!.accounts.toSet())
                        check(db.assets().entries().toSet() == snapshot.assets!!.entries.toSet())
                        check(db.imports().all().toSet() == snapshot.imports!!.toSet())
                        val bills = db.bills().allOnce().associateBy { it.fingerprint }
                        snapshot.bills.forEach { b ->
                            val row = bills.getValue(b.fingerprint)
                            check(row.priceCents == b.priceCents && row.type == b.type && row.timeMillis == b.timeMillis)
                            check(row.accountId == b.accountId && row.assetKey == b.assetKey && row.assetLinked == b.assetLinked)
                            check(row.recognitionId == b.recognitionId && row.note == b.note)
                        }
                        val billIdMap = snapshot.bills.associate { it.id to bills.getValue(it.fingerprint).id }
                        val history = db.recognitions().allOnce().associateBy { it.id }
                        snapshot.history!!.forEach { h -> check(history[h.id] == h.copy(billId = h.billId?.let(billIdMap::get))) }
                        check(AssetMath.summary(db.assets().accounts(), db.assets().entries()) ==
                            AssetMath.summary(snapshot.assets!!.accounts, snapshot.assets!!.entries))
                        val before = digest(db)
                        check(BackupStore.restoreDatabase(db, snapshot, BackupStore.Mode.MERGE).first == 0)
                        check(digest(db) == before)
                        checks += "user-counts-${snapshot.bills.size}-${snapshot.history!!.size}-${snapshot.assets!!.accounts.size}-${snapshot.assets!!.entries.size}"
                    }
                }
            }
        } catch (t: Throwable) { failure = t }
        finish(if (failure == null) -1 else 0, Bundle().apply {
            putString("checks", checks.joinToString("\n"))
            putString("status", if (failure == null) "passed" else "failed")
            failure?.let { putString("error", android.util.Log.getStackTraceString(it)) }
        })
    }
}
