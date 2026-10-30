package io.github.caozl9021.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.caozl9021.mymoney.capture.CaptureCoordinator
import io.github.caozl9021.mymoney.data.*
import com.example.billkit.BillType
import com.example.billkit.spec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 合成同单红包消息/回执，实际JS→独立Room→Coordinator/claim，不操作用户或真实交易。 */
class WechatRedpacketInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    private val now = System.currentTimeMillis()
    private lateinit var engine: SpecRuleEngine
    private lateinit var bundle: SpecRuleBundle
    private lateinit var common: String

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try {
            check(listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).all { value ->
                listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "vbox").any { value.contains(it, true) }
            }) { "Refusing redpacket verification on a real device" }
            fun read(name: String) = targetContext.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            bundle = SpecRuleBundle.load(::read)
            common = read(SpecAssets.COMMON_JS)
            engine = SpecRuleEngine().apply { rebuild(common, bundle.rules) }
            runBlocking(Dispatchers.IO) { runCases() }
        } catch (error: Throwable) { failure = error }
        finally { if (::engine.isInitialized) engine.close() }
        val passed = failure == null && results.none { it["status"] == "failed" }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf("status" to if (passed) "passed" else "failed",
                "tests" to results.size, "source" to "synthetic-sanitized-message-and-receipt-pairs",
                "database" to "isolated-room9", "actualUiDelivery" to "not-called", "actualRuntimeAndCoordinator" to true,
                "ruleVersion" to if (::bundle.isInitialized) bundle.version else "unavailable",
                "results" to results, "error" to failure?.stackTraceToString())))
        })
    }

    private suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        val start = System.nanoTime()
        try {
            withTimeout(30_000) { body(db) }
            check(db.assets().entries().isEmpty())
            results += mapOf("name" to name, "status" to "passed", "millis" to (System.nanoTime() - start) / 1_000_000)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        } finally { db.close() }
    }

    private suspend fun runCases() {
        test("message_then_prepared_receipt_keeps_originals_and_produces_one_expense") { db ->
            val message = message("SYNTH-REDPACKET-MESSAGE-FIRST")
            check(parse(db, message).hit == null)
            val hit = checkNotNull(parse(db, receipt("SYNTH-REDPACKET-MESSAGE-FIRST"), 1).hit)
            check(hit.draft.type == BillType.EXPENSE && hit.draft.priceCents == 1999L)
            check(!hit.draft.hasExplicitTime && hit.draft.timeMillis == now)
            val accepted = accept(db, hit, "message-first")
            check(accepted.created && claim(db, accepted, "first"))
            val originals = db.rules().recentData(10)
            check(originals.size == 2 && originals.any { it.data == message })
            check(originals.none { it.data.contains("linkedReceipt") })
            check(originals.all { it.match })
            check(db.bills().allOnce().isEmpty())
        }
        test("prepared_receipt_then_message_only_joins_same_packet") { db ->
            check(parse(db, receipt("SYNTH-REDPACKET-RECEIPT-FIRST")).hit == null)
            check(parse(db, message("SYNTH-REDPACKET-UNRELATED"), 1).hit == null)
            val hit = checkNotNull(parse(db, message("SYNTH-REDPACKET-RECEIPT-FIRST"), 2).hit)
            check(hit.draft.priceCents == 1999L && hit.draft.type == BillType.EXPENSE)
            check(accept(db, hit, "receipt-first").created)
            check(db.recognitions().allOnce().size == 1)
        }
        test("duplicate_callbacks_and_detail_share_one_history_and_claim") { db ->
            val packet = "SYNTH-REDPACKET-REPEATS"
            check(parse(db, message(packet)).hit == null)
            val first = accept(db, checkNotNull(parse(db, receipt(packet), 1).hit), "first")
            check(claim(db, first, "shown"))
            val repeats = listOf(message(packet), receipt(packet), receipt(packet, phase = "detail"))
            for ((index, raw) in repeats.withIndex()) {
                val repeat = accept(db, checkNotNull(parse(db, raw, index + 2).hit), "repeat-$index")
                check(!repeat.created && !repeat.deliverable && repeat.row.id == first.row.id)
                check(!claim(db, repeat, "repeat-$index"))
            }
            check(db.recognitions().allOnce().size == 1 && db.capture().deliveries().size == 1)
            check(db.bills().allOnce().isEmpty())
        }
        test("opened_personal_amount_does_not_use_group_total") { db ->
            val packet = "SYNTH-REDPACKET-PERSONAL"
            check(parse(db, message(packet, send = 0)).hit == null)
            check(parse(db, receipt(packet, cents = 300L, phase = "detail"), 1).hit == null)
            val hit = checkNotNull(parse(db, receipt(packet, cents = 50L, role = "received_personal", phase = "opened"), 2).hit)
            check(hit.draft.type == BillType.INCOME && hit.draft.priceCents == 50L)
            check(accept(db, hit, "personal").created)
            check(db.recognitions().allOnce().single().priceCents == 50L)
        }
        test("wrong_amount_conflict_rejected_without_second_review_draft") { db ->
            val packet = "SYNTH-REDPACKET-CONFLICT"
            parse(db, message(packet))
            val first = accept(db, checkNotNull(parse(db, receipt(packet), 1).hit), "before-conflict")
            check(claim(db, first, "shown"))
            val conflict = parse(db, receipt(packet, cents = 2000L), 2)
            check(conflict.hit == null && conflict.outcome == SpecRuleEngine.Outcome.REJECTED)
            check(db.recognitions().allOnce().single().priceCents == 1999L)
        }
        test("external_linked_receipt_cannot_bypass_host_pair_validation") { db ->
            val root = MiniJson.decode(message("SYNTH-REDPACKET-SPOOF")) as Map<*, *>
            val forged = root.entries.associate { it.key.toString() to it.value }.toMutableMap()
            forged["hostCorrelationSchema"] = 1
            forged["linkedReceipt"] = mapOf("packetId" to "SYNTH-REDPACKET-SPOOF", "amountFen" to 1999L,
                "success" to true, "role" to "sent_total", "phase" to "prepared", "adapterVersion" to RedpacketLinkPolicy.ADAPTER_VERSION,
                "messageObservedAt" to now, "messageRowId" to 1L, "receiptRowId" to 2L)
            val parsed = parse(db, MiniJson.encode(forged))
            check(parsed.hit == null && parsed.outcome == SpecRuleEngine.Outcome.REJECTED)
            check(db.rules().recentData(10).size == 1 && db.recognitions().allOnce().isEmpty())
        }
        test("raw_insert_failure_stops_join_and_engine") { db ->
            val packet = "SYNTH-REDPACKET-WRITE-FAIL"
            parse(db, message(packet))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_packet_input BEFORE INSERT ON app_data BEGIN SELECT RAISE(ABORT, 'synthetic-insert-failure'); END")
            val parsed = parse(db, receipt(packet), 1)
            check(parsed.hit == null && parsed.outcome == SpecRuleEngine.Outcome.ERROR)
            check(db.rules().recentData(10).size == 1 && db.recognitions().allOnce().isEmpty())
        }
        test("disabled_redpacket_rule_does_not_get_bypassed_by_correlation") { db ->
            val disabled = SpecRuleEngine().apply { rebuild(common, bundle.rules.map {
                if (it.systemRuleName == "maimi_wechat_redpackage_expense_v1") it.copy(enabled = false) else it
            }) }
            try {
                val packet = "SYNTH-REDPACKET-DISABLED"
                SpecRuleStore.parseStored(db, disabled, "com.tencent.mm", "DATA", message(packet), bundle.version, now)
                val parsed = SpecRuleStore.parseStored(db, disabled, "com.tencent.mm", "DATA", receipt(packet), bundle.version, now + 1000)
                check(parsed.hit == null && db.recognitions().allOnce().isEmpty())
            } finally { disabled.close() }
        }
        test("different_packet_ids_same_amount_keep_two_transactions") { db ->
            for (packet in listOf("SYNTH-REDPACKET-DISTINCT-ONE", "SYNTH-REDPACKET-DISTINCT-TWO")) {
                parse(db, message(packet))
                val accepted = accept(db, checkNotNull(parse(db, receipt(packet), 1).hit), packet)
                check(accepted.created && accepted.row.reviewOf == null)
            }
            check(db.recognitions().allOnce().size == 2)
            check(db.recognitions().allOnce().map { it.transactionKey }.distinct().size == 2)
        }
        test("ignored_packet_repeats_remain_ignored") { db ->
            val packet = "SYNTH-REDPACKET-IGNORED"
            parse(db, message(packet))
            val first = accept(db, checkNotNull(parse(db, receipt(packet), 1).hit), "before-ignore")
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            val repeat = accept(db, checkNotNull(parse(db, receipt(packet, phase = "detail"), 2).hit), "after-ignore")
            check(!repeat.created && !repeat.deliverable && repeat.row.status == RecognitionStatus.IGNORED)
            check(db.recognitions().allOnce().size == 1 && db.bills().allOnce().isEmpty())
        }
        test("recorded_packet_repeats_preserve_bill_and_do_not_reopen") { db ->
            val packet = "SYNTH-REDPACKET-RECORDED"
            parse(db, message(packet))
            val first = accept(db, checkNotNull(parse(db, receipt(packet), 1).hit), "before-record")
            val billId = db.bills().insert(BillEntity(priceCents = 1999L, type = "EXPENSE", timeMillis = first.row.timeMillis,
                merchant = "合成红包", method = null, orderTail = null, sourcePkg = "com.tencent.mm", strategy = first.row.strategy,
                fingerprint = first.row.fingerprint, createdAt = now, recognitionId = first.row.id))
            db.recognitions().update(first.row.copy(status = RecognitionStatus.RECORDED, billId = billId))
            val repeat = accept(db, checkNotNull(parse(db, receipt(packet, phase = "detail"), 2).hit), "after-record")
            check(!repeat.created && !repeat.deliverable && repeat.row.billId == billId)
            check(db.recognitions().allOnce().size == 1 && db.bills().allOnce().single().priceCents == 1999L)
        }
        test("persisted_original_receipt_survives_database_close_reopen") { _ ->
            val name = "redpacket-verification-${System.nanoTime()}.db"
            var persistent = Room.databaseBuilder(targetContext, AppDb::class.java, name).build()
            try {
                val packet = "SYNTH-REDPACKET-REOPEN"
                check(parse(persistent, receipt(packet)).hit == null)
                val originalId = persistent.rules().recentData(10).single().id
                persistent.close()
                persistent = Room.databaseBuilder(targetContext, AppDb::class.java, name).build()
                val hit = checkNotNull(parse(persistent, message(packet), 1).hit)
                check(accept(persistent, hit, "after-reopen").created)
                check(persistent.rules().dataById(originalId)?.match == true)
                check(persistent.rules().recentData(10).size == 2 && persistent.bills().allOnce().isEmpty())
            } finally { persistent.close(); targetContext.deleteDatabase(name) }
        }
    }

    private fun message(packet: String, send: Int = 1) = MiniJson.encode(mapOf("type" to "redPackage", "isSend" to send,
        "content" to MiniJson.encode(mapOf("msg" to mapOf("appmsg" to mapOf("type" to "2001", "title" to "微信红包",
            "des" to "我给你发了一个红包，赶紧去拆!", "wcpayinfo" to mapOf("paymsgid" to packet,
                "nativeurl" to "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=$packet&channelid=1"))))),
        "t" to now, "cachedPayMoney" to "", "cachedPayTools" to "", "cachedPayShop" to ""))
    private fun receipt(packet: String, cents: Long = 1999L, role: String = "sent_total", phase: String = "prepared") =
        MiniJson.encode(mapOf("type" to "redPacketReceipt", "packetId" to packet, "amountFen" to cents,
            "role" to role, "phase" to phase, "success" to true, "adapterVersion" to RedpacketLinkPolicy.ADAPTER_VERSION, "observedAt" to now))
    private suspend fun parse(db: AppDb, raw: String, index: Int = 0) =
        SpecRuleStore.parseStored(db, engine, "com.tencent.mm", "DATA", raw, bundle.version, now + index * 1000)
    private suspend fun accept(db: AppDb, hit: SpecRuleStore.Hit, observation: String) = db.withTransaction {
        CaptureCoordinator.acceptInTransaction(db, CaptureCoordinator.Input(hit.draft, "HOOK", observation,
            traceId = observation, receivedAt = now))
    }
    private suspend fun claim(db: AppDb, accepted: CaptureCoordinator.Accepted, attempt: String): Boolean {
        if (!accepted.deliverable || accepted.row.reviewOf != null || accepted.row.status != RecognitionStatus.PENDING) return false
        return db.withTransaction {
            val latest = db.recognitions().byId(accepted.row.id) ?: return@withTransaction false
            if (latest.status != RecognitionStatus.PENDING || latest.reviewOf != null) return@withTransaction false
            db.capture().claim(CaptureDelivery(latest.id, CaptureDeliveryState.CLAIMED, attempt, now)) != -1L
        }
    }
}
