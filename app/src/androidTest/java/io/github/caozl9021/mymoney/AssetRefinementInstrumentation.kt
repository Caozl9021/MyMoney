package io.github.caozl9021.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.caozl9021.mymoney.data.*
import com.example.billkit.*
import kotlinx.coroutines.*
import java.time.*
import java.util.UUID

/** Default mode uses isolated databases; UI seed requires explicit argument and an emulator. */
class AssetRefinementInstrumentation : Instrumentation() {
    private var mode: String? = null
    private val results = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode=arguments?.getString("mode");start() }
    override fun onStart() {
        var failure: Throwable?=null
        try { runBlocking(Dispatchers.IO) { withTimeout(60000) {
            if (mode=="ui_seed") seedUi() else tests()
        } } } catch (e:Throwable) { failure=e }
        finish(if(failure==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("results",results.joinToString("\n"));putString("error",failure?.stackTraceToString())
        })
    }
    private suspend fun test(name:String, body:suspend(AppDb)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(targetContext,AppDb::class.java).build()
        try { db.withTransaction { body(db) };results+="$name: passed" } finally { db.close() }
    }
    private suspend fun account(db:AppDb,id:String,balance:Long=100000,initialized:Boolean=true):AssetAccount {
        val a=AssetAccount(id,id,initialized=initialized,createdAt=1,updatedAt=1)
        db.assets().upsertAccount(a)
        if(initialized)db.assets().insertEntry(AssetEntry("opening-$id","opening-$id",id,balance,"OPENING","fixture",1))
        return a
    }
    private fun draft(from:AssetAccount,to:AssetAccount)=BillDraft(1234,BillType.TRANSFER,1700000000000,
        "来源商户","来源方式","1234","io.github.caozl9021.mymoney.manual.${UUID.randomUUID()}","fixture",
        account=from.name,note="转账备注",transferAccountId=to.id)
    private suspend fun tests() {
        test("manual_transfer_two_ends_retry_edit_and_delete_undo") { db ->
            val a=account(db,"a");val b=account(db,"b",20000)
            val d=draft(a,b)
            val one=BillWriter.saveEditorInTransaction(db,d,null)
            val repeat=BillWriter.saveEditorInTransaction(db,d,null)
            check(one.id==repeat.id && db.bills().allOnce().size==1)
            check(one.category==null && one.subCategory==null && one.transferAccountId==b.id && one.assetLinked)
            check(AssetMath.balance(a.id,db.assets().entries())==98766L && AssetMath.balance(b.id,db.assets().entries())==21234L)
            val edit=BillWriter.saveEditorInTransaction(db,d.copy(priceCents=2000,timeMillis=d.timeMillis+1000),one)
            check(edit.id==one.id && edit.createdAt==one.createdAt)
            check(AssetMath.balance(a.id,db.assets().entries())==98000L && AssetMath.balance(b.id,db.assets().entries())==22000L)
            val deleted=BillWriter.deleteGroupInTransaction(db,edit.id)
            check(AssetMath.balance(a.id,db.assets().entries())==100000L)
            BillWriter.restoreGroupInTransaction(db,deleted)
            check(AssetMath.balance(a.id,db.assets().entries())==98000L)
        }
        test("ordinary_bill_conversion_restores_old_expense_and_preserves_identity") { db ->
            val a=account(db,"a");val b=account(db,"b",20000)
            val d=draft(a,b).copy(type=BillType.EXPENSE,transferAccountId=null,category="其他")
            val expense=BillWriter.saveEditorInTransaction(db,d,null).copy(recognitionId="capture-fixed",fingerprint="identity-fixed",sourcePkg="synthetic.capture")
            db.bills().update(expense)
            val transfer=BillWriter.saveEditorInTransaction(db,d.copy(type=BillType.TRANSFER,transferAccountId=b.id,priceCents=2000),expense)
            check(transfer.id==expense.id && transfer.createdAt==expense.createdAt && transfer.recognitionId=="capture-fixed" && transfer.fingerprint=="identity-fixed")
            check(AssetMath.balance(a.id,db.assets().entries())==98000L && AssetMath.balance(b.id,db.assets().entries())==22000L)
        }
        test("recognized_transfer_keeps_source_and_draft_destination_roundtrip") { db ->
            val a=account(db,"a");val b=account(db,"b",20000)
            val d=draft(a,b).copy(sourcePkg="synthetic.capture",historyId="capture-fixed",hasExplicitTime=true)
            val history=RecognitionEntity.fromDraft(d,"synthetic:identity",1)
            db.recognitions().insert(history)
            val read=db.recognitions().byId(history.id)!!.toDraft()
            check(read.transferAccountId==b.id)
            val saved=BillWriter.saveTransferDraftInTransaction(db,read,null,history.id)
            check(saved.sourcePkg==d.sourcePkg && saved.merchant==d.merchant && saved.method==d.method && saved.orderTail==d.orderTail)
            check(saved.recognitionId==history.id && saved.transferAccountId==b.id)
            val again=BillWriter.saveTransferDraftInTransaction(db,read,null,history.id)
            check(saved.id==again.id && db.bills().allOnce().size==1)
            val encoded=Backup.encode(Backup.Snapshot(1,emptyList(),null,null,history=listOf(history)))
            check(Backup.decode(encoded).history!!.single().transferAccountId==b.id)
        }
        test("invalid_endpoints_leave_bills_and_balances_unchanged") { db ->
            val a=account(db,"a");val b=account(db,"b",initialized=false)
            val before=db.assets().entries()
            for(d in listOf(draft(a,a),draft(a,b),draft(a,b).copy(transferAccountId=null))) {
                check(runCatching { BillWriter.saveEditorInTransaction(db,d,null) }.isFailure)
                check(db.bills().allOnce().isEmpty() && db.assets().entries()==before)
            }
        }
        test("pending_destination_survives_replace_and_merge_with_local_protection") { db ->
            val d=draft(account(db,"a"),account(db,"b")).copy(historyId="pending")
            val pending=RecognitionEntity.fromDraft(d,"synthetic:pending",1).copy(protectedFields=CaptureFields.ACCOUNT)
            val snapshot=Backup.Snapshot(1,emptyList(),null,null,history=listOf(pending))
            BackupStore.restoreDatabase(db,snapshot,BackupStore.Mode.MERGE)
            val other=pending.copy(transferAccountId="other",updatedAt=10)
            BackupStore.restoreDatabase(db,snapshot.copy(history=listOf(other)),BackupStore.Mode.MERGE)
            check(db.recognitions().byId("pending")!!.transferAccountId=="b")
        }
        val name="asset-refinement-v8-${UUID.randomUUID()}.db"
        val file=targetContext.getDatabasePath(name);file.parentFile!!.mkdirs()
        context.assets.open("asset-refinement-v8.db").use { input -> file.outputStream().use { input.copyTo(it) } }
        val migrated=Room.databaseBuilder(targetContext,AppDb::class.java,name).addMigrations(AppDb.MIGRATION_8_9).build()
        try {
            val row=migrated.recognitions().byId("migration-transfer-draft")!!
            check(migrated.openHelper.readableDatabase.version==9 && row.priceCents==1234L && row.account=="旧账户" && row.transferAccountId==null)
            results+="real_room_v8_fixture_to_v9_non_destructive: passed"
        } finally { migrated.close();targetContext.deleteDatabase(name) }
    }
    private suspend fun seedUi() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "UI fixture is emulator-only" }
        val db=AppDb.get(targetContext)
        check(db.assets().accounts().none { it.id.startsWith("qa28-") }) { "fixture already exists" }
        val a=account(db,"qa28-cash");val b=account(db,"qa28-wallet",20000)
        db.assets().upsertAccount(a.copy(name="QA28现金",sortOrder=0));db.assets().upsertAccount(b.copy(name="QA28钱包",sortOrder=1))
        val date=LocalDate.now();val zone=ZoneId.systemDefault();val p=LedgerMonthPeriod.containing(date,25,zone)
        for ((index,offset) in listOf(0L,-1L,-2L).withIndex()) {
            val time=p.shift(offset).fromMillis+3600000
            val draft=BillDraft(if(index==1)2500 else 1234,BillType.EXPENSE,time,"QA28账期",null,null,
                "io.github.caozl9021.mymoney.manual.qa28-$index","fixture",account="QA28现金",category="餐饮",subCategory="午餐",note="QA28账期$index")
            db.withTransaction { BillWriter.saveEditorInTransaction(db,draft,null) }
        }
        db.withTransaction { BillWriter.saveEditorInTransaction(db,draft(a.copy(name="QA28现金"),b).copy(
            timeMillis=p.shift(-3).fromMillis+3600000,sourcePkg="io.github.caozl9021.mymoney.manual.qa28-hidden",note="QA28隐藏本金"),null) }
        val pending=RecognitionEntity.fromDraft(draft(a.copy(name="QA28现金"),b).copy(historyId="qa28-pending",sourcePkg="synthetic.qa28",transferAccountId=null),"synthetic:qa28-pending")
        db.recognitions().insert(pending)
        LedgerPeriodStore.save(targetContext,25)
        results+="explicit_emulator_ui_seed: passed"
    }
}
