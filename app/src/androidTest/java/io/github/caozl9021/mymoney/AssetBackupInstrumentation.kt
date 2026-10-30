package io.github.caozl9021.mymoney

import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import io.github.caozl9021.mymoney.data.*
import kotlinx.coroutines.runBlocking

/** Exercises the production restore transaction against an isolated Room database only. */
class AssetBackupInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try {
            runBlocking {
                fun source(): Backup.Snapshot {
                    val account = AssetAccount("backup-cash", "备份现金", kind = "CASH", initialized = true, createdAt = 1, updatedAt = 1)
                    val opening = AssetEntry("backup-opening", "opening-op", account.id, 100_000, "OPENING", "当前余额", 1)
                    val expense = AssetEntry("backup-expense", "expense-op", account.id, -1999, "BILL", "早餐", 2, "backup-bill")
                    val bill = Backup.Bill(1999, "EXPENSE", 2, "早餐", null, null, "manual-backup", "手动", "backup-fp", 2,
                        "餐饮", null, account.name, null, id = 7, accountId = account.id, assetLinked = true, assetKey = "backup-bill")
                    return Backup.Snapshot(3, listOf(bill), null, null, assets = AssetSnapshot(listOf(account), listOf(opening, expense)))
                }
                suspend fun digest() = Triple(db.bills().allOnce(), db.assets().accounts(), db.assets().entries())
                val decoded = Backup.decode(Backup.encode(source()))
                check(BackupStore.restoreDatabase(db, decoded, BackupStore.Mode.MERGE).first == 1)
                check(AssetMath.balance("backup-cash", db.assets().entries()) == 98_001L)
                check(db.bills().allOnce().single().assetLinked)
                checks += "json3-production-merge-empty-room-preserves-balance-and-link"
                val initial = digest()
                check(BackupStore.restoreDatabase(db, decoded, BackupStore.Mode.MERGE).first == 0)
                check(digest() == initial)
                checks += "repeated-production-merge-no-double-postings"

                val conflict = decoded.copy(assets = decoded.assets!!.copy(accounts = listOf(decoded.assets!!.accounts.single().copy(name = "冲突名称"))))
                check(runCatching { BackupStore.restoreDatabase(db, conflict, BackupStore.Mode.MERGE) }.isFailure)
                check(digest() == initial)
                checks += "uuid-account-conflict-rejects-without-mutation"

                val malformed = decoded.copy(assets = decoded.assets!!.copy(entries = decoded.assets!!.entries.dropLast(1)))
                check(runCatching { BackupStore.restoreDatabase(db, malformed, BackupStore.Mode.REPLACE) }.isFailure)
                check(digest() == initial)
                checks += "bad-linked-postings-reject-before-replace"

                // A failure after asset writes must also roll back account and ledger changes.
                val conflictingFingerprint = decoded.copy(bills = listOf(decoded.bills.single().copy(assetKey = "other-key", assetLinked = false)),
                    assets = decoded.assets!!.copy(entries = listOf(decoded.assets!!.entries.first(),
                        AssetEntry("new-adjustment", "new-adjustment-op", "backup-cash", 1, "ADJUSTMENT", "冲突回滚测试", 3))))
                check(runCatching { BackupStore.restoreDatabase(db, conflictingFingerprint, BackupStore.Mode.MERGE) }.isFailure)
                check(digest() == initial)
                checks += "identity-conflict-after-postings-rolls-back-all-tables"

                val legacy = Backup.decode("""{"format":1,"bills":[{"p":100,"t":10,"fp":"old-import"}]}""")
                BackupStore.restoreDatabase(db, legacy, BackupStore.Mode.REPLACE)
                check(db.assets().entries() == initial.third)
                check(db.assets().accounts() == initial.second)
                check(!db.bills().allOnce().single().assetLinked)
                check(db.bills().allOnce().single().accountId == null)
                checks += "json1-replace-preserves-asset-ledger-imports-unlinked"

                BackupStore.restoreDatabase(db, decoded, BackupStore.Mode.REPLACE)
                val restored = db.bills().allOnce().single()
                check(restored.id != decoded.bills.single().id)
                check(restored.assetKey == "backup-bill" && restored.assetLinked)
                check(db.assets().entries().last().billKey == restored.assetKey)
                check(AssetMath.balance("backup-cash", db.assets().entries()) == 98_001L)
                checks += "replace-remaps-local-bill-id-with-stable-posting-key"

                BackupStore.restoreDatabase(db, Backup.Snapshot(4, emptyList(), null, null,
                    assets = AssetSnapshot(emptyList(), emptyList())), BackupStore.Mode.REPLACE)
                check(db.assets().accounts().isEmpty() && db.assets().entries().isEmpty() && db.bills().allOnce().isEmpty())
                check(db.assets().meta("legacy-accounts-v1") != null)
                checks += "explicit-empty-assets-replace-clears-only-when-present"
            }
        } catch (t: Throwable) { failure = t }
        finally { db.close() }
        val result = Bundle().apply {
            putString("checks", checks.joinToString("\n"))
            putString("status", if (failure == null) "passed" else "failed")
            failure?.let { putString("error", android.util.Log.getStackTraceString(it)) }
        }
        finish(if (failure == null) -1 else 0, result)
    }
}
