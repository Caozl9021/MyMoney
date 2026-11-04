package io.github.mymoney

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import io.github.mymoney.data.*
import kotlinx.coroutines.runBlocking

/** Only temporary in-memory databases; explicitly supports the authorized Waydroid test surface. */
class DeletionBackupInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            check(Build.MODEL.contains("WayDroid", true) || Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.FINGERPRINT.contains("generic")) { "Only the authorized Waydroid or isolated Android emulator is allowed" }
            runBlocking {
                val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                try {
                    fun bill(id: Long, note: String) = BillEntity(id, 1200, "EXPENSE", 1700000000000,
                        "模拟商家", null, null, "simulation", "manual", "backup-test-$id", 1700000000000,
                        account = "冻结账户名", note = note, assetKey = "backup-key-$id")
                    val old = BillDeletionCodec.create(listOf(bill(80, "本机删除快照")), "detail", 1700000000001)
                    val incoming = BillDeletionCodec.create(listOf(bill(81, "远端删除快照")), "ledger", 1700000000002)
                    db.deletions().insert(old)
                    val live = bill(1, "本机活跃账单")
                    db.bills().insert(live)
                    val remote = Backup.Snapshot(1700000000003, emptyList(), null, null, deletions = listOf(incoming))
                    check(BackupStore.restoreLedgerDatabase(db, remote, BackupStore.Mode.MERGE).first == 0)
                    check(db.bills().allOnce() == listOf(live))
                    check(db.deletions().allOnce().toSet() == setOf(old, incoming))
                    check(BackupStore.restoreLedgerDatabase(db, remote, BackupStore.Mode.MERGE).first == 0)
                    check(db.deletions().allOnce().size == 2)
                    checks += "JSON9-log-merge-retains-local-idempotent-and-no-snapshot-bill-insert"

                    val conflicting = BillDeletionCodec.create(listOf(bill(80, "冲突快照")), old.origin, old.deletedAt, old.eventId)
                    val bad = remote.copy(deletions = listOf(conflicting))
                    check(runCatching { BackupStore.restoreLedgerDatabase(db, bad, BackupStore.Mode.REPLACE) }.isFailure)
                    check(db.bills().allOnce() == listOf(live))
                    check(db.deletions().allOnce().toSet() == setOf(old, incoming))
                    checks += "conflicting-event-replacement-aborts-with-live-bills-and-audit-rows-unchanged"

                    for (version in 1..8) {
                        val legacy = Backup.decode("""{"format":$version,"bills":[]}""")
                        check(BackupStore.restoreLedgerDatabase(db, legacy, BackupStore.Mode.REPLACE).first == 0)
                        check(db.deletions().allOnce().toSet() == setOf(old, incoming))
                        check(db.bills().allOnce().isEmpty())
                    }
                    check(BackupStore.restoreLedgerDatabase(db, remote.copy(deletions = emptyList()), BackupStore.Mode.REPLACE).first == 0)
                    check(db.deletions().allOnce().toSet() == setOf(old, incoming))
                    check(db.assets().entries().isEmpty())
                    checks += "JSON1-through-8-and-empty-JSON9-replace-preserve-local-logs-and-never-post-balances"

                    val config = BackupStore.ExportConfiguration(
                        Backup.Ledger(emptyList(), emptyList(), emptyList(), ""),
                        Backup.SettingsSnapshot(emptyList(), 0, false), 0, 0)
                    val exported = BackupStore.readDatabaseSnapshot(db, config)
                    check(exported.deletions!!.toSet() == setOf(old, incoming))
                    val roundTrip = Backup.decode(Backup.encode(exported))
                    check(roundTrip.deletions == exported.deletions)
                    check(roundTrip.bills.isEmpty())
                    checks += "production-transaction-snapshot-export-and-JSON9-roundtrip-keep-audit-separate"
                } finally { db.close() }
            }
        } catch (t: Throwable) { failure = t }
        finish(if (failure == null) -1 else 0, Bundle().apply {
            putString("checks", checks.joinToString("\n"))
            putString("status", if (failure == null) "passed" else "failed")
            failure?.let { putString("error", android.util.Log.getStackTraceString(it)) }
        })
    }
}
