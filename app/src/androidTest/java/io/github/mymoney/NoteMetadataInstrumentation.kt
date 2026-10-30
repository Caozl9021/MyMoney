package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import io.github.mymoney.data.*
import com.example.billkit.*
import kotlinx.coroutines.*

/** Isolated databases only. Does not modify the user's ledger or preferences. */
class NoteMetadataInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        super.onStart()
        val results = mutableListOf<String>()
        var failure: Throwable? = null
        try { runBlocking(Dispatchers.IO) {
            val current = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
            try {
                val draft = BillDraft(1999, BillType.EXPENSE, 1, "京东", null, null, "pkg", "test",
                    productName = "iPhone 16", productSource = "explicit-product-v1:ACCESSIBILITY",
                    noteOrigin = NoteTemplate.USER, noteTemplateSnapshot = "{商户}·{商品}")
                val row = RecognitionEntity.fromDraft(draft)
                current.recognitions().insert(row)
                check(current.recognitions().byId(row.id)!!.toDraft() == row.toDraft())
                results += "pending_metadata_room_roundtrip: passed"
                val backup = Backup.Snapshot(1, emptyList(), null, null, history = listOf(row))
                BackupStore.restoreDatabase(current, Backup.decode(Backup.encode(backup)), BackupStore.Mode.REPLACE)
                check(current.recognitions().byId(row.id)!!.noteOrigin == NoteTemplate.USER)
                check(current.recognitions().byId(row.id)!!.productName == "iPhone 16")
                results += "backup_metadata_restore: passed"
                // Derive the complete previous schema by removing only the four new nullable columns.
                // The remaining Room6 table/index definitions are otherwise unchanged.
                val schema = mutableListOf<String>()
                current.openHelper.writableDatabase.query("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY type DESC").use { cursor ->
                    while (cursor.moveToNext()) schema += cursor.getString(0).replace(
                        Regex(",\\s*[`\"]?(?:productName|productSource|noteOrigin|noteTemplateSnapshot)[`\"]?\\s+TEXT"), "")
                }
                val name = "note-migration-${System.nanoTime()}.db"
                try {
                    val raw = SQLiteDatabase.openOrCreateDatabase(targetContext.getDatabasePath(name), null)
                    raw.use { old ->
                        schema.filter { it.startsWith("CREATE TABLE", true) }.forEach(old::execSQL)
                        schema.filterNot { it.startsWith("CREATE TABLE", true) }.forEach(old::execSQL)
                        old.execSQL("INSERT INTO bills (id,priceCents,type,timeMillis,sourcePkg,strategy,fingerprint,createdAt,assetLinked,assetKey,transferFeeCents,note) VALUES (1,1999,'EXPENSE',1,'pkg','legacy','old',1,0,'',0,'原备注')")
                        old.version = 6
                    }
                    val upgraded = Room.databaseBuilder(targetContext, AppDb::class.java, name).addMigrations(AppDb.MIGRATION_6_7).build()
                    try {
                        val bill = upgraded.bills().allOnce().single()
                        check(bill.id == 1L && bill.note == "原备注" && bill.productName == null && bill.noteOrigin == null)
                        results += "room6_to7_preserves_legacy_bill_and_validates_schema: passed"
                    } finally { upgraded.close() }
                } finally { targetContext.deleteDatabase(name) }
            } finally { current.close() }
        } } catch (t: Throwable) { failure = t }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("results", results.joinToString("\n")); putString("error", failure?.stackTraceToString())
        })
    }
}
