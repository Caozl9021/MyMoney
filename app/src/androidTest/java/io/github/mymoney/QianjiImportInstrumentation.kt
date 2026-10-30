package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.mymoney.data.*
import io.github.mymoney.importer.*
import kotlinx.coroutines.*
import java.time.ZoneId

/** Isolated in-memory databases only; never touches the user's ledger or settings. */
class QianjiImportInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun doc(amount: String = "19.99"): QianjiXlsx.Document {
        val cells = QianjiRow.requiredHeaders.associateWith { "" }.toMutableMap().apply {
            put("ID", "synthetic-qj"); put("时间", "2026-10-26 12:00:00"); put("币种", "CNY")
            put("类型", "转账"); put("金额", amount); put("账户1", "合成甲"); put("账户2", "合成乙")
        }
        return QianjiXlsx.Document(listOf(QianjiXlsx.Row(2,cells,QianjiRow.map(cells, ZoneId.of("Asia/Shanghai")))),"Asia/Shanghai")
    }
    override fun onStart() {
        super.onStart()
        val results = mutableListOf<String>()
        var failure: Throwable? = null
        try { runBlocking(Dispatchers.IO) {
            suspend fun test(name: String, block: suspend (AppDb) -> Unit) {
                val db = Room.inMemoryDatabaseBuilder(targetContext,AppDb::class.java).build()
                try { withTimeout(30_000) { block(db) }; results += "$name: passed" } finally { db.close() }
            }
            test("append_and_delete_tombstone") { db ->
                val a = QianjiImport.commit(db,doc())
                check(a.count(QianjiImport.State.READY)==1)
                val b = db.bills().allOnce().single()
                check(b.transferAccount=="合成乙" && !b.assetLinked)
                check(db.assets().entries().isEmpty() && db.recognitions().allOnce().isEmpty())
                check(QianjiImport.commit(db,doc()).count(QianjiImport.State.DUPLICATE)==1)
                db.bills().delete(b.id)
                check(QianjiImport.commit(db,doc()).count(QianjiImport.State.DUPLICATE)==1)
                check(db.bills().allOnce().isEmpty())
            }
            test("conflict_does_not_overwrite") { db ->
                QianjiImport.commit(db,doc())
                check(QianjiImport.commit(db,doc("20")).count(QianjiImport.State.CONFLICT)==1)
                check(db.bills().allOnce().single().priceCents==1999L)
            }
            test("outer_transaction_failure_rolls_back_identity_and_bill") { db ->
                runCatching { db.withTransaction { QianjiImport.commit(db,doc()); error("synthetic failure") } }
                check(db.imports().all().isEmpty() && db.bills().allOnce().isEmpty())
            }
            test("concurrent_confirm_only_inserts_once") { db ->
                coroutineScope { listOf(async { QianjiImport.commit(db,doc()) },async { QianjiImport.commit(db,doc()) }).awaitAll() }
                check(db.bills().allOnce().size==1 && db.imports().all().size==1)
            }
            test("backup_merge_preserves_tombstone_and_replace_restores_snapshot") { db ->
                QianjiImport.commit(db,doc())
                val b = db.bills().allOnce().single()
                val model = Backup.Bill(b.priceCents,b.type,b.timeMillis,b.merchant,b.method,b.orderTail,b.sourcePkg,b.strategy,b.fingerprint,b.createdAt,b.category,b.subCategory,b.account,b.note,
                    accountId=b.accountId,transferAccountId=b.transferAccountId,assetKey=b.assetKey,transferAccount=b.transferAccount)
                val snap = Backup.decode(Backup.encode(Backup.Snapshot(1,listOf(model),null,null,
                    assets=AssetSnapshot(emptyList(),emptyList()),imports=db.imports().all())))
                db.bills().delete(b.id)
                BackupStore.restoreDatabase(db,snap,BackupStore.Mode.MERGE)
                check(db.bills().allOnce().isEmpty())
                BackupStore.restoreDatabase(db,snap,BackupStore.Mode.REPLACE)
                check(db.bills().allOnce().single().transferAccount=="合成乙")
                check(QianjiImport.commit(db,doc()).count(QianjiImport.State.DUPLICATE)==1)
            }
        } } catch (e: Throwable) { failure=e }
        finish(if(failure==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("results",results.joinToString("\n")); putString("error",failure?.stackTraceToString())
        })
    }
}
