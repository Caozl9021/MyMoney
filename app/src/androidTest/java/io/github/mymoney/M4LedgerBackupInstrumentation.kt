package io.github.mymoney

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.data.*
import io.github.mymoney.data.rulesync.LedgerBackupApi
import io.github.mymoney.data.rulesync.RuleSyncApi
import io.github.mymoney.data.rulesync.RuleSyncConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Synthetic data only: no AppDb singleton, no actual pairing or user preferences are changed. */
class M4LedgerBackupInstrumentation : Instrumentation() {
    private var serverUrl: String? = null
    private var pairingCode: String? = null
    override fun onCreate(arguments: Bundle?) {
        serverUrl = arguments?.getString("serverUrl")
        pairingCode = arguments?.getString("pairingCode")
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            runBlocking {
                val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                try {
                    val account = AssetAccount("m4-account", "测试账户", initialized = true, createdAt = 1, updatedAt = 1)
                    db.assets().upsertAccount(account)
                    db.assets().insertEntry(AssetEntry("opening", "opening", account.id, 100_000, "OPENING", "", 1))
                    val config = BackupStore.ExportConfiguration(
                        Backup.Ledger(emptyList(), emptyList(), emptyList(), "current-template"),
                        Backup.SettingsSnapshot(listOf("com.tencent.mm"), 1000, false), 7, 2,
                    )
                    coroutineScope {
                        val writer = launch(Dispatchers.IO) {
                            repeat(30) { index ->
                                db.withTransaction {
                                    val draft = BillDraft(100 + index.toLong(), BillType.EXPENSE, index + 2L,
                                        "测试商家", null, null, "manual:m4-$index", "手动",
                                        account = account.name, note = "人工备注$index", historyId = "m4-history-$index")
                                    val row = BillWriter.saveEditorInTransaction(db, draft, null)
                                    db.bills().update(row.copy(recognitionId = draft.historyId))
                                    db.recognitions().insert(RecognitionEntity.fromDraft(draft, at = index + 2L).copy(
                                        status = RecognitionStatus.RECORDED, billId = row.id))
                                    db.imports().insert(ImportIdentity("qianji:" + index.toString(16).padStart(64, '0'), "a".repeat(64)))
                                    yield()
                                }
                                yield()
                            }
                        }
                        repeat(30) {
                            val snapshot = BackupStore.readDatabaseSnapshot(db, config)
                            val decoded = Backup.decode(Backup.encode(snapshot))
                            check(decoded.bills.size == decoded.history!!.size)
                            check(decoded.bills.size == decoded.imports!!.size)
                            decoded.bills.forEach { bill ->
                                check(decoded.history!!.single { it.id == bill.recognitionId }.billId == bill.id)
                                check(bill.note!!.startsWith("人工备注"))
                            }
                            Backup.validateAssetBills(decoded.bills, decoded.assets)
                            check(AssetMath.balance(account.id, decoded.assets!!.entries) ==
                                100_000 - decoded.bills.sumOf { it.priceCents })
                        }
                        writer.join()
                    }
                    checks += "30-concurrent-BillWriter-transactions-and-30-consistent-JSON8-snapshots"
                    val source = BackupStore.readDatabaseSnapshot(db, config)
                    val target = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                    try {
                        target.assets().putMeta(AssetMeta("pending-restore-config", "preserve-existing-journal"))
                        val oldConfig = source.copy(ledger = config.ledger.copy(noteTemplate = "obsolete-template"),
                            settings = config.settings.copy(rootMode = true))
                        check(BackupStore.restoreLedgerDatabase(target, oldConfig, BackupStore.Mode.REPLACE).first == 30)
                        check(target.assets().meta("pending-restore-config") == "preserve-existing-journal")
                        check(target.imports().all().toSet() == source.imports!!.toSet())
                        check(target.assets().entries().toSet() == source.assets!!.entries.toSet())
                        check(target.bills().allOnce().map { it.note }.toSet() == source.bills.map { it.note }.toSet())
                        check(BackupStore.restoreLedgerDatabase(target, oldConfig, BackupStore.Mode.MERGE).first == 0)
                        check(target.assets().meta("pending-restore-config") == "preserve-existing-journal")
                        checks += "family-restore-preserves-config-journal-identities-assets-notes-and-merge-idempotence"
                        val before = target.bills().allOnce()
                        val beforeAssets = target.assets().entries()
                        val badHistory = source.history!!.first().copy(reviewOf = "missing-history")
                        check(runCatching { BackupStore.restoreLedgerDatabase(target,
                            source.copy(history = listOf(badHistory)), BackupStore.Mode.REPLACE) }.isFailure)
                        check(target.bills().allOnce() == before)
                        check(target.assets().entries() == beforeAssets)
                        check(target.assets().meta("pending-restore-config") == "preserve-existing-journal")
                        checks += "replace-failure-after-writes-rolls-back-all-data-and-preserves-config"
                        check(BackupStore.restoreLedgerDatabase(target, oldConfig, BackupStore.Mode.MERGE,
                            restoreConfiguration = true).first == 0)
                        val journalText = target.assets().meta("pending-restore-config")!!
                        val journal = MiniJson.decode(journalText) as Map<*, *>
                        check(journal["requireRuleRepository"] == true)
                        val pendingConfig = Backup.decode(journal["snapshot"] as String)
                        check(pendingConfig.ledger!!.noteTemplate == "obsolete-template")
                        check(pendingConfig.settings == null)
                        check(BackupStore.restoreLedgerDatabase(target, source, BackupStore.Mode.MERGE).first == 0)
                        check(target.assets().meta("pending-restore-config") == journalText)
                        checks += "explicit-config-restore-journals-repository-required-ledger-never-settings-default-remains-protected"
                    } finally { target.close() }
                    for (version in 1..8) {
                        val legacy = Backup.decode("""{"format":$version,"kind":"MyMoneyBackup","bills":[{"p":123,"ty":"EXPENSE","t":1,"me":"旧格式商家","fp":"m4-legacy-$version","no":"旧格式人工备注"}],"ledger":{"noteTemplate":"obsolete-$version"}}""")
                        val legacyDb = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                        try {
                            legacyDb.assets().putMeta(AssetMeta("pending-restore-config", "preserve-current-config"))
                            check(BackupStore.restoreLedgerDatabase(legacyDb, legacy, BackupStore.Mode.MERGE).first == 1)
                            val row = legacyDb.bills().allOnce().single()
                            check(row.priceCents == 123L && row.note == "旧格式人工备注" && row.merchant == "旧格式商家")
                            check(BackupStore.restoreLedgerDatabase(legacyDb, legacy, BackupStore.Mode.MERGE).first == 0)
                            check(legacyDb.bills().allOnce() == listOf(row))
                            check(legacyDb.assets().meta("pending-restore-config") == "preserve-current-config")
                        } finally { legacyDb.close() }
                    }
                    checks += "JSON1-through-JSON8-nonempty-legacy-restore-repeat-merge-and-old-config-protection"
                    val pendingDb = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                    try {
                        val pendingDraft = BillDraft(456, BillType.EXPENSE, 100,
                            "待处理商家", null, null, "synthetic:m4-pending", "test",
                            note = "备份中的人工备注", historyId = "m4-pending-history")
                        val pending = RecognitionEntity.fromDraft(pendingDraft, at = 100).copy(
                            protectedFields = CaptureFields.NOTE, updatedAt = 101)
                        val pendingSnapshot = Backup.decode(Backup.encode(Backup.Snapshot(102, emptyList(),
                            config.ledger.copy(noteTemplate = "obsolete-pending-template"), null, history = listOf(pending))))
                        pendingDb.assets().putMeta(AssetMeta("pending-restore-config", "pending-config-unchanged"))
                        BackupStore.restoreLedgerDatabase(pendingDb, pendingSnapshot, BackupStore.Mode.MERGE)
                        check(pendingDb.recognitions().allOnce().single() == pending)
                        check(pendingDb.bills().allOnce().isEmpty())
                        BackupStore.restoreLedgerDatabase(pendingDb, pendingSnapshot, BackupStore.Mode.MERGE)
                        check(pendingDb.recognitions().allOnce().single() == pending)
                        val localEdit = pending.copy(note = "本机后来手改", updatedAt = 200)
                        pendingDb.recognitions().update(localEdit)
                        val newerRemote = pending.copy(note = "更新的远程备注", updatedAt = 300)
                        BackupStore.restoreLedgerDatabase(pendingDb, pendingSnapshot.copy(history = listOf(newerRemote)), BackupStore.Mode.MERGE)
                        val protected = pendingDb.recognitions().allOnce().single()
                        check(protected.status == RecognitionStatus.PENDING && protected.billId == null)
                        check(protected.note == "本机后来手改" && (protected.protectedFields and CaptureFields.NOTE) != 0L)
                        pendingDb.recognitions().update(protected.copy(note = "", updatedAt = 400))
                        BackupStore.restoreLedgerDatabase(pendingDb, pendingSnapshot.copy(history = listOf(newerRemote.copy(updatedAt = 500))), BackupStore.Mode.MERGE)
                        check(pendingDb.recognitions().allOnce().single().note == "")
                        check(pendingDb.assets().meta("pending-restore-config") == "pending-config-unchanged")
                        checks += "JSON8-pending-protected-NOTE-manual-edit-and-manual-clear-preserved-through-real-restore-merge"
                    } finally { pendingDb.close() }
                    val privateRoot = File(targetContext.cacheDir, "m4-private-${UUID.randomUUID()}").apply { mkdirs() }
                    val wrapped = object : ContextWrapper(targetContext) { override fun getFilesDir(): File = privateRoot }
                    try {
                        val json = Backup.encode(source)
                        val first = BackupStore.persistPrivateSnapshot(wrapped, json, "pre-restore")
                        val second = BackupStore.persistPrivateSnapshot(wrapped, json, "pre-restore")
                        check(first != second && first.readText() == json && second.readText() == json)
                        check(first.parentFile!!.listFiles()!!.none { it.extension == "part" })
                        check(runCatching { BackupStore.persistPrivateSnapshot(wrapped, "invalid", "pre-restore") }.isFailure)
                        check(first.readText() == json)
                        checks += "durable-private-recovery-points-unique-validated-and-no-partial-files"
                        coroutineScope {
                            val recoveryPointRead = CompletableDeferred<Unit>()
                            val writeAttempted = CompletableDeferred<Unit>()
                            val writeFinished = CompletableDeferred<Unit>()
                            val writer = launch(Dispatchers.IO) {
                                recoveryPointRead.await()
                                writeAttempted.complete(Unit)
                                db.withTransaction {
                                    BillWriter.saveEditorInTransaction(db, BillDraft(888, BillType.EXPENSE, 99,
                                        "并发商家", null, null, "manual:m4-during-restore", "手动",
                                        account = account.name, note = "恢复期间提交"), null)
                                }
                                writeFinished.complete(Unit)
                            }
                            val (_, recoveryFile) = BackupStore.restoreWithRecoveryPoint(db, source, BackupStore.Mode.REPLACE) {
                                val prior = BackupStore.readDatabaseSnapshot(db, config)
                                recoveryPointRead.complete(Unit)
                                writeAttempted.await()
                                delay(30)
                                check(!writeFinished.isCompleted)
                                BackupStore.persistPrivateSnapshot(wrapped, Backup.encode(prior), "pre-restore")
                            }
                            writer.join()
                            check(Backup.decode(recoveryFile.readText()).bills.size == 30)
                            check(db.bills().allOnce().size == 31)
                            check(db.bills().allOnce().any { it.note == "恢复期间提交" })
                        }
                        checks += "replace-holds-accounting-transaction-across-recovery-point-and-concurrent-writer"
                        val beforeFailure = db.bills().allOnce()
                        check(runCatching {
                            BackupStore.restoreWithRecoveryPoint(db, source, BackupStore.Mode.REPLACE) {
                                error("synthetic recovery point write failure")
                            }
                        }.isFailure)
                        check(db.bills().allOnce() == beforeFailure)
                        checks += "recovery-point-save-failure-blocks-all-replacement-writes"
                        serverUrl?.let { url ->
                            withContext(Dispatchers.IO) {
                                val paired = RuleSyncApi.pair(url, pairingCode ?: error("missing isolated pairing code"), "M4隔离测试")
                                val pairedConfig = RuleSyncConfig(serverUrl = url, spaceId = paired.spaceId,
                                    epoch = paired.epoch, deviceId = paired.deviceId, deviceName = "M4隔离测试")
                                val id = UUID.randomUUID().toString()
                                val upload = File(privateRoot, "e2e-upload-$id.json").apply { writeText(json, Charsets.UTF_8) }
                                val uploaded = LedgerBackupApi.upload(pairedConfig, paired.token, id, upload, "M4-isolated-test")
                                check(uploaded.id == id && uploaded.billCount == source.bills.size.toLong())
                                check(uploaded.sha256 == LedgerBackupApi.sha256(upload))
                                var offset = 0
                                var listed: LedgerBackupApi.Entry? = null
                                do {
                                    val page = LedgerBackupApi.list(pairedConfig, paired.token, offset)
                                    listed = page.items.firstOrNull { it.id == id }
                                    offset = page.nextOffset
                                } while (listed == null && page.hasMore)
                                check(listed != null)
                                val download = File(privateRoot, "e2e-download-$id.json")
                                val remote = LedgerBackupApi.download(pairedConfig, paired.token, listed!!, download)
                                check(LedgerBackupApi.sha256(download) == LedgerBackupApi.sha256(upload))
                                check(remote.bills == source.bills)
                                check(remote.assets == source.assets)
                                check(remote.imports == source.imports)
                                check(remote.history == source.history)
                                val restored = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                                try {
                                    check(BackupStore.restoreLedgerDatabase(restored, remote, BackupStore.Mode.REPLACE).first == 30)
                                    check(BackupStore.restoreLedgerDatabase(restored, remote, BackupStore.Mode.MERGE).first == 0)
                                    check(restored.bills().allOnce().size == 30)
                                    check(restored.assets().accounts() == remote.assets!!.accounts)
                                    check(restored.assets().entries().toSet() == remote.assets!!.entries.toSet())
                                    check(restored.imports().all().toSet() == remote.imports!!.toSet())
                                    val histories = restored.recognitions().allOnce().associateBy { it.id }
                                    restored.bills().allOnce().forEach { row ->
                                        check(histories.getValue(row.recognitionId!!).billId == row.id)
                                        check(row.note!!.startsWith("人工备注"))
                                    }
                                } finally { restored.close() }
                                RuleSyncApi.revoke(url, paired.token)
                                checks += "Android-PG-file-volume-upload-list-download-SHA-and-isolated-Room-restore-end-to-end"
                            }
                        }
                        val blockedRoot = File(privateRoot, "blocked").apply { writeText("file, not directory") }
                        val blocked = object : ContextWrapper(targetContext) { override fun getFilesDir(): File = blockedRoot }
                        check(runCatching { BackupStore.persistPrivateSnapshot(blocked, json, "pre-restore") }.isFailure)
                        check(first.readText() == json && db.bills().allOnce().size == 31)
                        checks += "unwritable-recovery-point-fails-without-changing-existing-data"
                    } finally { privateRoot.deleteRecursively() }
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
