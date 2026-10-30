package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.github.mymoney.data.*
import kotlinx.coroutines.*
import java.util.UUID

/** An isolated file database verifies the actual 3 -> 4 -> 5 Room migration on a device. */
class AssetMigrationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        super.onStart()
        val name = "asset-migration-${UUID.randomUUID()}.db"
        var opened: AppDb? = null
        var failure: Throwable? = null
        val results = mutableListOf<String>()
        try { runBlocking(Dispatchers.IO) {
            val path = targetContext.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("""CREATE TABLE bills (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, priceCents INTEGER NOT NULL,
                    type TEXT NOT NULL, timeMillis INTEGER NOT NULL, merchant TEXT, method TEXT,
                    orderTail TEXT, sourcePkg TEXT NOT NULL, strategy TEXT NOT NULL, fingerprint TEXT NOT NULL,
                    createdAt INTEGER NOT NULL, category TEXT, subCategory TEXT, account TEXT, note TEXT, recognitionId TEXT)""")
                old.execSQL("CREATE INDEX index_bills_fingerprint ON bills(fingerprint)")
                old.execSQL("CREATE INDEX index_bills_timeMillis ON bills(timeMillis)")
                old.execSQL("CREATE UNIQUE INDEX index_bills_recognitionId ON bills(recognitionId)")
                old.execSQL("""CREATE TABLE recognition_history (
                    id TEXT NOT NULL PRIMARY KEY, identityKey TEXT NOT NULL,
                    priceCents INTEGER NOT NULL, type TEXT NOT NULL, timeMillis INTEGER NOT NULL,
                    merchant TEXT, method TEXT, orderTail TEXT, sourcePkg TEXT NOT NULL,
                    strategy TEXT NOT NULL, fingerprint TEXT NOT NULL, category TEXT, subCategory TEXT,
                    account TEXT, note TEXT, transactionKey TEXT, hasExplicitTime INTEGER NOT NULL,
                    captureSessionId TEXT, recognizedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    status TEXT NOT NULL, billId INTEGER, shareState TEXT NOT NULL)""")
                old.execSQL("CREATE UNIQUE INDEX index_recognition_history_identityKey ON recognition_history(identityKey)")
                old.execSQL("CREATE INDEX index_recognition_history_recognizedAt ON recognition_history(recognizedAt)")
                old.execSQL("INSERT INTO bills VALUES(7,1999,'EXPENSE',1700000000000,'旧商户','微信',NULL,'fixture','old','fp',1700000000000,'餐饮',NULL,'零钱','旧备注','history-id')")
                old.execSQL("INSERT INTO bills VALUES(8,999,'REFUND',1700000000001,NULL,NULL,NULL,'fixture','old','fp-refund',1700000000001,NULL,NULL,'已删账户',NULL,NULL)")
                old.execSQL("INSERT INTO recognition_history VALUES('history-id','identity',1999,'EXPENSE',1700000000000,'旧商户','微信',NULL,'fixture','old','fp','餐饮',NULL,'零钱','旧备注',NULL,1,NULL,1700000000000,1700000000000,'RECORDED',7,'NONE')")
                old.version = 3
            }
            val db = Room.databaseBuilder(targetContext, AppDb::class.java, name).addMigrations(AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5, AppDb.MIGRATION_5_6, AppDb.MIGRATION_6_7).build()
            opened = db
            val rows = db.bills().allOnce()
            check(rows.size == 2 && rows.first().priceCents == 1999L && rows.first().note == "旧备注")
            check(rows.first().recognitionId == "history-id" && rows.first().assetKey == "legacy-7" && !rows.first().assetLinked)
            check(db.recognitions().byId("history-id")?.billId == 7L)
            results += "room_3_to_4_schema_and_original_fields"
            AssetStore.migrateAccounts(db, listOf("零钱", "现金"))
            val accounts = db.assets().accounts()
            check(accounts.map { it.name } == listOf("零钱", "现金", "已删账户"))
            check(accounts.last().archived && accounts.all { !it.initialized })
            check(db.bills().byId(7)?.accountId == accounts.first().id && db.bills().byId(8)?.accountId == accounts.last().id)
            check(db.assets().entries().isEmpty())
            results += "legacy_candidates_and_archived_names_without_guessed_balance"
            AssetStore.migrateAccounts(db, listOf("不可重复迁入"))
            check(db.assets().accounts() == accounts && db.assets().entries().isEmpty())
            check(db.recognitions().byId("history-id")?.billId == 7L)
            results += "migration_reentrant_preserves_history_and_zero_postings"
        } } catch (e: Throwable) { failure = e }
        finally { opened?.close(); targetContext.deleteDatabase(name) }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf("database" to "isolated-file", "results" to results, "error" to failure?.stackTraceToString())))
            putInt("tests", results.size)
        })
    }
}
