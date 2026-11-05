package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import com.example.billkit.visual.VisualBillResult
import com.example.billkit.visual.VisualFields
import io.github.mymoney.capture.CaptureCoordinator
import io.github.mymoney.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** No production AppDb singleton, settings, files or ledger fixtures are used by these tests. */
class OcrEvidenceInstrumentation : Instrumentation() {
    private var allowed = false
    private val results = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        allowed = arguments?.getString("isolated") == "ocr-evidence-temp"
        start()
    }
    override fun onStart() {
        var failure: Throwable? = null
        try {
            val virtual = Build.PRODUCT.contains("waydroid", true) || Build.MODEL.contains("waydroid", true) ||
                Build.PRODUCT.contains("sdk", true) || Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true)
            check(allowed && virtual) { "Explicit temporary-db argument and emulator/Waydroid required" }
            runBlocking(Dispatchers.IO) { withTimeout(90_000) { cases() } }
        } catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putInt("tests", results.size)
            putString("report", MiniJson.encode(mapOf("results" to results, "error" to failure?.stackTraceToString(),
                "scope" to "isolated-in-memory-databases-only", "device" to Build.MODEL)))
        })
    }
    private val draft = BillDraft(350, BillType.EXPENSE, 1_793_777_000_000L, null, null, null,
        "com.tencent.mm", "synthetic-ocr-evidence")
    private val result = VisualBillResult(amountCents=350, type=BillType.EXPENSE,
        fields=VisualFields(merchant="SIM商户", method="零钱", productName="SIM商品",
            orderCandidate="999999999999999999999999"))
    private suspend fun test(name: String, action: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try { action(db); results += name } finally { db.close() }
    }
    private suspend fun first(db: AppDb, source: String="PAGE", key: String="original-page") = db.withTransaction {
        CaptureCoordinator.acceptInTransaction(db, CaptureCoordinator.Input(draft,source,key,receivedAt=draft.timeMillis))
    }.row
    private suspend fun supplement(db: AppDb, row: RecognitionEntity, candidate: VisualBillResult=result,
                                   observation: String="original-page", manual: Boolean=false,
                                   guard: () -> Boolean={true}) = db.withTransaction {
        CaptureCoordinator.supplementOcrInTransaction(db,row.id,row.toDraft(),candidate,observation,
            manualTarget=manual,allowed=guard,engineId="synthetic-test")
    }
    private suspend fun cases() {
        test("same_page_supplement_does_not_create_identity_delivery_or_posting") { db ->
            val row=first(db)
            db.assets().upsertAccount(AssetAccount("wallet","微信",createdAt=1,updatedAt=1))
            val aliases=db.capture().aliases()
            val outcome=supplement(db,row)!!
            check(outcome.outcome=="added" && outcome.row.method=="零钱" && outcome.row.account=="微信")
            check(outcome.row.transactionKey==null && outcome.row.orderTail==null)
            check(db.capture().aliases()==aliases && db.recognitions().allOnce().size==1)
            check(db.capture().deliveries().isEmpty() && db.bills().allOnce().isEmpty() && db.assets().entries().isEmpty())
            val evidence=db.capture().forRecord(row.id).single { it.outcome=="ocr-added" }
            check(evidence.source=="PAGE" && evidence.detail.contains("method=ocr") && !evidence.detail.contains("SIM商户"))
            check(supplement(db,row)!!.outcome=="duplicate")
        }
        test("unbound_observation_cannot_target_a_same_amount_record") { db ->
            val row=first(db)
            check(supplement(db,row,observation="unbound")!!.outcome=="observation-mismatch")
            check(db.recognitions().byId(row.id)==row && db.capture().forRecord(row.id).size==1)
        }
        test("hook_observation_is_not_a_page_capture_authorization") { db ->
            val row=first(db,source="HOOK")
            check(supplement(db,row)!!.outcome=="observation-mismatch")
            check(db.recognitions().byId(row.id)==row)
        }
        test("explicit_image_target_does_not_claim_a_logical_page_channel") { db ->
            val row=first(db,source="HOOK")
            check(supplement(db,row,observation="user-selected-image",manual=true)!!.outcome=="added")
            check(db.capture().sourcesForRecord(row.id).toSet()==setOf("HOOK","IMAGE"))
            check("IMAGE" !in CaptureShortWindowPolicy.sources)
        }
        test("conflicting_ocr_writes_diagnostic_only") { db ->
            val row=first(db)
            val aliases=db.capture().aliases()
            val outcome=supplement(db,row,result.copy(amountCents=351))!!
            check(outcome.outcome=="conflict" && outcome.row==row)
            check(db.recognitions().byId(row.id)==row && db.capture().aliases()==aliases)
            check(db.capture().forRecord(row.id).any { it.outcome=="ocr-conflict" })
        }
        test("live_manual_clear_blocks_method_and_account_but_allows_merchant") { db ->
            val row=first(db)
            CaptureEditLocks.acquire(row.id)
            try {
                CaptureEditLocks.touch(row.id,CaptureFields.METHOD or CaptureFields.ACCOUNT or CaptureFields.NOTE)
                val outcome=supplement(db,row)!!
                check(outcome.row.method==null && outcome.row.account==null && outcome.row.productName==null)
                check(outcome.row.merchant=="SIM商户")
            } finally { CaptureEditLocks.release(row.id) }
        }
        for(status in listOf(RecognitionStatus.RECORDED,RecognitionStatus.IGNORED)) {
            test("${status}_is_not_reopened_or_enriched") { db ->
                val row=first(db).copy(status=status)
                db.recognitions().update(row)
                check(supplement(db,row)!!.outcome=="terminal")
                check(db.recognitions().byId(row.id)==row && db.capture().forRecord(row.id).size==1)
            }
        }
        test("source_disabled_before_submission_does_not_write") { db ->
            val row=first(db)
            check(supplement(db,row,guard={false})==null)
            check(db.recognitions().byId(row.id)==row && db.capture().forRecord(row.id).size==1)
        }
        test("source_disabled_during_transaction_rolls_back_evidence_and_fields") { db ->
            val row=first(db)
            var checks=0
            check(runCatching { supplement(db,row,guard={++checks<3}) }.isFailure)
            check(db.recognitions().byId(row.id)==row && db.capture().forRecord(row.id).size==1)
        }
        test("existing_same_amount_other_record_is_not_touched") { db ->
            val row=first(db)
            val other=first(db,key="second-page")
            check(other.id!=row.id)
            check(supplement(db,other,observation="original-page")!!.outcome=="observation-mismatch")
            check(db.recognitions().byId(row.id)==row && db.recognitions().byId(other.id)==other)
        }
    }
}
