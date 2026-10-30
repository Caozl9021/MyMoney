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
import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecRuleBundle
import com.example.billkit.spec.SpecRuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 0.33.1 结构化微信转账专项：完全合成、脱敏的方向/阶段/重复回调夹具。
 * 实际随包JS → SpecRuleStore.parseStored → 独立内存Room → 真正Coordinator事务接受与认领DAO。
 * 不读现场原始输入，不调用AppDb.get/RecognitionStore/BillWriter/ConfirmDelivery或真实UI投递。
 * 这里只证明接受层与持久化投递闸门，不证明系统悬浮窗真实呈现或真实支付覆盖。
 */
class WechatTransferInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    private val now = System.currentTimeMillis()
    private val businessSeconds = now / 1000 - 5
    private lateinit var engine: SpecRuleEngine
    private lateinit var version: String

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try {
            requireEmulator()
            fun read(name: String) = targetContext.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            val bundle = SpecRuleBundle.load(::read)
            version = bundle.version
            engine = SpecRuleEngine().apply { rebuild(read(SpecAssets.COMMON_JS), bundle.rules) }
            runBlocking(Dispatchers.IO) { runCases() }
        } catch (error: Throwable) { failure = error }
        finally { if (::engine.isInitialized) engine.close() }
        val passed = failure == null && results.none { it["status"] == "failed" }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf(
                "status" to if (passed) "passed" else "failed", "tests" to results.size,
                "source" to "synthetic-sanitized-contract-fixtures", "ruleVersion" to if (::version.isInitialized) version else "unavailable",
                "database" to "fresh-in-memory-room9-per-case", "actualUiDelivery" to "not-called",
                "actualRuntimeAndCoordinator" to true, "results" to results, "error" to failure?.stackTraceToString(),
            )))
        })
    }

    private fun requireEmulator() {
        check(listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).all { value ->
            listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "vbox").any { value.contains(it, true) }
        }) { "Refusing transfer verification on a real device" }
    }

    private suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        val start = System.nanoTime()
        try {
            withTimeout(30_000) { body(db) }
            check(db.assets().entries().isEmpty()) { "Tests must not create asset entries" }
            results += mapOf("name" to name, "status" to "passed", "millis" to (System.nanoTime() - start) / 1_000_000)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        } finally { db.close() }
    }

    private suspend fun runCases() {
        test("subtype1_incoming_invitation_is_not_income_or_pending_bill") { db ->
            val result = parse(db, payload("SYNTH-TRANSFER-INVITE-01", stage = 1, isSend = 0), 0)
            check(result.hit == null)
            check(db.recognitions().allOnce().isEmpty() && db.bills().allOnce().isEmpty())
            check(db.rules().recentData(10).size == 1)
        }
        test("four_callbacks_1_1_0_0_yield_one_expense_and_one_claim") { db ->
            val accepted = mutableListOf<CaptureCoordinator.Accepted>()
            for ((index, direction) in listOf(1, 1, 0, 0).withIndex()) {
                val raw = payload("SYNTH-TRANSFER-FOUR-01", 1, direction, arrived = now + index * 1000)
                val parsed = parse(db, raw, index)
                if (direction == 0) check(parsed.hit == null) { "Invitation callback became income" }
                else {
                    val hit = checkNotNull(parsed.hit) { "Outgoing transfer was not parsed" }
                    check(hit.draft.type == BillType.EXPENSE && hit.draft.priceCents == 1L)
                    accepted += accept(db, raw, hit, index)
                }
            }
            check(accepted.size == 2 && accepted.count { it.created } == 1)
            check(accepted.map { it.row.id }.distinct().size == 1)
            check(accepted.count { it.deliverable } == 1)
            check(accepted.map { it.row.transactionKey }.distinct().single() != null)
            check(db.recognitions().allOnce().single().type == "EXPENSE")
            check(db.rules().recentData(10).size == 4 && db.bills().allOnce().isEmpty())
            check(claimEligible(db, accepted[0], "first-claim"))
            check(!claimEligible(db, accepted[1], "repeat-claim"))
            check(!claimEligible(db, accepted[0], "repeated-first-object"))
            check(db.capture().deliveries().size == 1)
        }
        test("sender_stage1_then_receiver_confirmation_stays_same_expense_and_business_time") { db ->
            val firstRaw = payload("SYNTH-TRANSFER-PHASE-01", 1, 1)
            val first = accept(db, firstRaw, checkNotNull(parse(db, firstRaw, 0).hit), 0)
            check(claimEligible(db, first, "shown-first"))
            db.capture().putDelivery(CaptureDelivery(first.row.id, CaptureDeliveryState.ACKNOWLEDGED, "shown-first", now))
            val confirmationRaw = payload("SYNTH-TRANSFER-PHASE-01", 3, 0, arrived = now + 30_000)
            val hit = checkNotNull(parse(db, confirmationRaw, 1).hit)
            check(hit.draft.type == BillType.EXPENSE)
            check(hit.draft.timeMillis == businessSeconds * 1000 && hit.draft.hasExplicitTime)
            val confirmed = accept(db, confirmationRaw, hit, 1)
            check(confirmed.row.id == first.row.id && !confirmed.created && !confirmed.deliverable)
            check(confirmed.row.transactionKey == first.row.transactionKey)
            check(!claimEligible(db, confirmed, "confirmation-claim"))
            check(db.recognitions().allOnce().size == 1 && db.bills().allOnce().isEmpty())
        }
        test("receiver_invitation_then_explicit_self_confirmation_yields_one_income") { db ->
            val invite = payload("SYNTH-TRANSFER-RECEIVE-01", 1, 0)
            check(parse(db, invite, 0).hit == null)
            val confirm = payload("SYNTH-TRANSFER-RECEIVE-01", 3, 1)
            val hit = checkNotNull(parse(db, confirm, 1).hit)
            check(hit.draft.type == BillType.INCOME)
            val first = accept(db, confirm, hit, 1)
            val repeatRaw = payload("SYNTH-TRANSFER-RECEIVE-01", 3, 1, arrived = now + 5000)
            val repeat = accept(db, repeatRaw, checkNotNull(parse(db, repeatRaw, 2).hit), 2)
            check(first.created && first.deliverable && !repeat.created && !repeat.deliverable)
            check(first.row.id == repeat.row.id)
            check(db.recognitions().allOnce().single().type == "INCOME" && db.bills().allOnce().isEmpty())
        }
        test("confirmation_direction_requires_completion_not_just_isSend") { db ->
            for ((index, isSend) in listOf(0, 1).withIndex()) {
                val raw = payload("SYNTH-TRANSFER-NOT-COMPLETE-$index", 3, isSend, completed = false)
                check(parse(db, raw, index).hit == null)
            }
            check(db.recognitions().allOnce().isEmpty() && db.bills().allOnce().isEmpty())
        }
        test("one_self_confirmation_with_isSend1_and_0_mirrors_never_creates_expense") { db ->
            val valid = payload("SYNTH-TRANSFER-SELF-MIRROR-01", 3, 1, completionText = "本人已确认收款")
            val first = accept(db, valid, checkNotNull(parse(db, valid, 0).hit), 0)
            check(first.row.type == "INCOME")
            val mirror = payload("SYNTH-TRANSFER-SELF-MIRROR-01", 3, 0,
                arrived = now + 1000, completionText = "本人已确认收款")
            check(parse(db, mirror, 1).hit == null)
            check(db.recognitions().allOnce().single().type == "INCOME")
            check(db.bills().allOnce().isEmpty())
        }
        test("generic_confirmation_without_actor_is_not_inferred_from_isSend") { db ->
            for (direction in listOf(0, 1)) {
                val raw = payload("SYNTH-TRANSFER-NO-ACTOR-$direction", 3, direction,
                    completionText = "已确认收款")
                check(parse(db, raw, direction).hit == null)
            }
            check(db.recognitions().allOnce().isEmpty() && db.bills().allOnce().isEmpty())
        }
        test("structured_redpackage_completed_expense_and_income_reach_host_adapter") { db ->
            for ((index, outgoing) in listOf(true, false).withIndex()) {
                val raw = redpackage("SYNTH-REDPACKAGE-COMPLETE-$index", outgoing)
                val hit = checkNotNull(parse(db, raw, index).hit) { "Completed redpackage rejected by runtime/adapter" }
                check(hit.draft.type == if (outgoing) BillType.EXPENSE else BillType.INCOME)
                check(hit.draft.priceCents == 1L && hit.draft.transactionKey != null)
                val accepted = accept(db, raw, hit, index)
                check(accepted.created && accepted.deliverable)
                check(claimEligible(db, accepted, "redpackage-$index"))
            }
            check(db.recognitions().allOnce().size == 2 && db.bills().allOnce().isEmpty())
        }
        test("redpackage_repeated_completion_keeps_one_record_and_invitation_never_becomes_income") { db ->
            val invite = redpackage("SYNTH-REDPACKAGE-INVITE-01", false, completed = false)
            check(parse(db, invite, 0).hit == null)
            val firstRaw = redpackage("SYNTH-REDPACKAGE-REPEAT-01", false)
            val first = accept(db, firstRaw, checkNotNull(parse(db, firstRaw, 1).hit), 1)
            val repeatRaw = redpackage("SYNTH-REDPACKAGE-REPEAT-01", false, arrived = now + 4000)
            val repeat = accept(db, repeatRaw, checkNotNull(parse(db, repeatRaw, 2).hit), 2)
            check(first.created && first.deliverable && !repeat.created && !repeat.deliverable)
            check(first.row.id == repeat.row.id)
            check(db.recognitions().allOnce().single().type == "INCOME" && db.bills().allOnce().isEmpty())
        }
        test("same_amount_second_transfer_id_keeps_two_same_source_transactions") { db ->
            val rows = listOf("SYNTH-TRANSFER-DISTINCT-01", "SYNTH-TRANSFER-DISTINCT-02").mapIndexed { index, id ->
                val raw = payload(id, 1, 1, arrived = now + index)
                accept(db, raw, checkNotNull(parse(db, raw, index).hit), index)
            }
            check(rows.all { it.created && it.deliverable && it.row.reviewOf == null })
            check(rows.map { it.row.id }.distinct().size == 2)
            check(rows.map { it.row.transactionKey }.distinct().size == 2)
            check(db.recognitions().allOnce().size == 2 && db.bills().allOnce().isEmpty())
        }
        test("recorded_same_transfer_repeat_does_not_create_draft_or_eligible_delivery") { db ->
            val raw = payload("SYNTH-TRANSFER-RECORDED-01", 1, 1)
            val first = accept(db, raw, checkNotNull(parse(db, raw, 0).hit), 0)
            val billId = db.bills().insert(BillEntity(priceCents = 1, type = "EXPENSE", timeMillis = first.row.timeMillis,
                merchant = "合成转账对象", method = null, orderTail = null, sourcePkg = "com.tencent.mm",
                strategy = first.row.strategy, fingerprint = first.row.fingerprint, createdAt = now,
                recognitionId = first.row.id))
            db.recognitions().update(first.row.copy(status = RecognitionStatus.RECORDED, billId = billId))
            val repeatRaw = payload("SYNTH-TRANSFER-RECORDED-01", 3, 0, arrived = now + 20_000)
            val repeat = accept(db, repeatRaw, checkNotNull(parse(db, repeatRaw, 1).hit), 1)
            check(!repeat.created && !repeat.deliverable && repeat.row.status == RecognitionStatus.RECORDED)
            check(repeat.row.id == first.row.id && repeat.row.billId == billId)
            check(db.recognitions().allOnce().size == 1 && db.bills().allOnce().size == 1)
            check(!claimEligible(db, repeat, "recorded-repeat"))
        }
        test("ignored_same_transfer_repeat_keeps_ignored_without_automatic_delivery") { db ->
            val raw = payload("SYNTH-TRANSFER-IGNORED-01", 1, 1)
            val first = accept(db, raw, checkNotNull(parse(db, raw, 0).hit), 0)
            db.recognitions().update(first.row.copy(status = RecognitionStatus.IGNORED))
            val repeatRaw = payload("SYNTH-TRANSFER-IGNORED-01", 1, 1, arrived = now + 10_000)
            val repeat = accept(db, repeatRaw, checkNotNull(parse(db, repeatRaw, 1).hit), 1)
            check(!repeat.created && !repeat.deliverable && repeat.row.status == RecognitionStatus.IGNORED)
            check(db.recognitions().allOnce().size == 1 && db.bills().allOnce().isEmpty())
            check(!claimEligible(db, repeat, "ignored-repeat"))
        }
    }

    private fun payload(id: String, stage: Int, isSend: Int, arrived: Long = now, completed: Boolean = true,
        completionText: String? = null): String {
        val confirmed = stage == 3 && completed
        val content = mapOf("msg" to mapOf("appmsg" to mapOf(
            "type" to "2000", "title" to if (confirmed) "已收款" else "微信转账",
            "des" to if (confirmed) completionText ?: if (isSend == 1) "本人已确认收款" else "对方已确认收款"
                else "合成转账记录",
            "wcpayinfo" to mapOf("paysubtype" to stage.toString(), "feedesc" to "￥0.01",
                "transferid" to id, "paymsgid" to id, "begintransfertime" to businessSeconds.toString()),
        )))
        return MiniJson.encode(mapOf("type" to "transfer", "isSend" to isSend,
            "content" to MiniJson.encode(content), "chatUser" to "synthetic-peer",
            "hookerUser" to "synthetic-peer", "hookUser" to "synthetic-peer", "t" to arrived,
            "cachedPayTools" to "", "cachedPayMoney" to "", "cachedPayShop" to ""))
    }

    private fun redpackage(id: String, outgoing: Boolean, arrived: Long = now, completed: Boolean = true): String {
        val content = mapOf("msg" to mapOf("appmsg" to mapOf(
            "type" to "2001",
            "title" to if (!completed) "微信红包" else if (outgoing) "红包发送成功" else "红包领取成功",
            "des" to if (!completed) "恭喜发财，尚未领取" else if (outgoing) "红包已发出" else "本人已领取红包",
            "wcpayinfo" to mapOf("feedesc" to "￥0.01", "sendid" to id,
                "transactionTime" to businessSeconds),
        )))
        return MiniJson.encode(mapOf("type" to "redPackage", "isSend" to if (outgoing) 1 else 0,
            "content" to MiniJson.encode(content), "chatUser" to "synthetic-peer", "t" to arrived,
            "cachedPayTools" to "", "cachedPayMoney" to "0.01", "cachedPayShop" to ""))
    }

    private suspend fun parse(db: AppDb, raw: String, index: Int) = SpecRuleStore.parseStored(
        db, engine, "com.tencent.mm", "DATA", raw, version, now + index * 1000,
    )

    private suspend fun accept(db: AppDb, raw: String, hit: SpecRuleStore.Hit, index: Int): CaptureCoordinator.Accepted {
        val observation = "hook:" + CaptureMergePolicy.digest("com.tencent.mm|$raw")
        return db.withTransaction { CaptureCoordinator.acceptInTransaction(db,
            CaptureCoordinator.Input(hit.draft, "HOOK", observation, traceId = observation,
                receivedAt = now + index * 1000)) }
    }

    /** 与真实deliver相同的持久化claim条件；不调用UI/目标实例AppDb。 */
    private suspend fun claimEligible(db: AppDb, accepted: CaptureCoordinator.Accepted, attempt: String): Boolean {
        if (!accepted.deliverable || accepted.row.reviewOf != null || accepted.row.status != RecognitionStatus.PENDING) return false
        return db.withTransaction {
            val latest = db.recognitions().byId(accepted.row.id) ?: return@withTransaction false
            if (latest.status != RecognitionStatus.PENDING || latest.reviewOf != null) return@withTransaction false
            db.capture().claim(CaptureDelivery(latest.id, CaptureDeliveryState.CLAIMED, attempt, now)) != -1L
        }
    }
}
