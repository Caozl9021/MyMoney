package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.mymoney.capture.CaptureCoordinator
import io.github.mymoney.capture.replayPendingRows
import io.github.mymoney.data.*
import com.example.billkit.BillType
import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecRuleBundle
import com.example.billkit.spec.SpecRuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 阶段 1 专项：「规则未就绪 → 落 pending → 就绪后重放 → 只落一条记录」。
 *
 * 走的是**真实**链路：随包规则 bundle → `SpecRuleStore.parseStored` →
 * `HookIngress.replayPendingRows`（生产同一个循环）→ `CaptureCoordinator.acceptInTransaction`，
 * 只是把库换成独立内存 Room、把可用性/就绪判定换成可控入参。
 *
 * 不调用 `AppDb.get` / `RecognitionStore` / `BillWriter` / `ConfirmDelivery`，不碰实例账本，
 * 不发起真实支付。这里只证明「接受层与重放闸门」，不证明系统悬浮窗真实呈现。
 */
class PendingReplayInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    private val now = System.currentTimeMillis()
    private val businessSeconds = now / 1000 - 5
    private lateinit var engine: SpecRuleEngine
    private lateinit var version: String

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            requireEmulator()
            fun read(name: String) = targetContext.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            val bundle = SpecRuleBundle.load(::read)
            version = bundle.version
            engine = SpecRuleEngine().apply { rebuild(read(SpecAssets.COMMON_JS), bundle.rules) }
            runBlocking(Dispatchers.IO) { runCases() }
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (::engine.isInitialized) engine.close()
        }
        val passed = failure == null && results.none { it["status"] == "failed" }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf(
                "status" to if (passed) "passed" else "failed",
                "tests" to results.size,
                "source" to "synthetic-sanitized-transfer-payload",
                "ruleVersion" to if (::version.isInitialized) version else "unavailable",
                "database" to "fresh-in-memory-room9-per-case",
                "loopUnderTest" to "HookIngress.replayPendingRows",
                "actualUiDelivery" to "not-called",
                "results" to results,
                "error" to failure?.stackTraceToString(),
            )))
        })
    }

    private fun requireEmulator() {
        check(listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).all { value ->
            listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "vbox").any { value.contains(it, true) }
        }) { "Refusing pending-replay verification on a real device" }
    }

    private suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        val start = System.nanoTime()
        try {
            withTimeout(30_000) { body(db) }
            results += mapOf("name" to name, "status" to "passed", "millis" to (System.nanoTime() - start) / 1_000_000)
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        } finally {
            db.close()
        }
    }

    private suspend fun runCases() {
        test("rule_not_ready_persists_raw_as_pending_instead_of_dropping") { db ->
            val raw = payload("SYNTH-PENDING-COLD-01")
            val id = checkNotNull(SpecRuleStore.persistPendingStored(db, WECHAT, "DATA", raw, now)) {
                "Cold-start credential was not persisted"
            }
            val pending = SpecRuleStore.pendingData(db, now - WINDOW)
            check(pending.size == 1 && pending.single().id == id)
            check(pending.single().data == raw && pending.single().version == SpecRuleStore.PENDING_VERSION)
            // 规则仍未就绪：重放必须原样留着，既不能处理也不能清标记。
            replayPendingRows(pending,
                ready = { false }, usable = { true },
                process = { error("must not process while rules are not ready") },
                clear = { error("must not clear while rules are not ready") })
            check(SpecRuleStore.pendingData(db, now - WINDOW).size == 1)
            check(db.recognitions().allOnce().isEmpty() && db.bills().allOnce().isEmpty())
        }

        test("ready_replay_creates_exactly_one_record_and_clears_marker") { db ->
            val raw = payload("SYNTH-PENDING-READY-01")
            SpecRuleStore.persistPendingStored(db, WECHAT, "DATA", raw, now)
            replayPendingRows(SpecRuleStore.pendingData(db, now - WINDOW),
                ready = { true }, usable = { true },
                process = { row -> processRow(db, row) },
                clear = { id -> SpecRuleStore.clearPending(db, id) })
            check(db.recognitions().allOnce().size == 1) { "Replay did not produce exactly one record" }
            check(db.bills().allOnce().isEmpty()) { "Replay must not write a bill without confirmation" }
            check(SpecRuleStore.pendingData(db, now - WINDOW).isEmpty()) { "Marker was not cleared after replay" }
            // 原始行保留，作为「这条数据到达过」的诊断记录：pending 行 + 重放时落的那一行。
            check(db.rules().recentData(10).size == 2)
        }

        test("duplicate_replay_keeps_one_record_and_one_delivery") { db ->
            val raw = payload("SYNTH-PENDING-DUP-01")
            SpecRuleStore.persistPendingStored(db, WECHAT, "DATA", raw, now)
            val pending = SpecRuleStore.pendingData(db, now - WINDOW)
            val accepted = mutableListOf<CaptureCoordinator.Accepted>()
            // 两次就绪切换各重放一次：第二次必须被观测身份合并，而不是再开一张待确认卡。
            repeat(2) {
                replayPendingRows(pending,
                    ready = { true }, usable = { true },
                    process = { row -> processRow(db, row)?.let { accepted += it } },
                    clear = { id -> SpecRuleStore.clearPending(db, id) })
            }
            check(accepted.size == 2) { "Expected two accept calls, got ${accepted.size}" }
            check(accepted.count { it.created } == 1) { "Duplicate replay created a second record" }
            check(accepted.count { it.deliverable } == 1) { "Duplicate replay would show a second card" }
            check(accepted.map { it.row.id }.distinct().size == 1)
            check(db.recognitions().allOnce().size == 1 && db.bills().allOnce().isEmpty())
        }

        test("channel_disabled_clears_marker_without_creating_record") { db ->
            SpecRuleStore.persistPendingStored(db, WECHAT, "DATA", payload("SYNTH-PENDING-OFF-01"), now)
            replayPendingRows(SpecRuleStore.pendingData(db, now - WINDOW),
                ready = { true }, usable = { false },
                process = { error("must not process when the channel is off") },
                clear = { id -> SpecRuleStore.clearPending(db, id) })
            check(SpecRuleStore.pendingData(db, now - WINDOW).isEmpty())
            check(db.recognitions().allOnce().isEmpty() && db.bills().allOnce().isEmpty())
        }

        test("only_hook_credentials_get_a_pending_marker") { db ->
            for (type in listOf("NOTICE", "SMS", "")) {
                check(SpecRuleStore.persistPendingStored(db, WECHAT, type, payload("SYNTH-PENDING-TYPE-$type"), now) == null) {
                    "Type $type must not leave an unreplayable pending row"
                }
            }
            check(SpecRuleStore.pendingData(db, now - WINDOW).isEmpty())
        }

        test("pending_rows_outside_the_replay_window_are_not_replayed") { db ->
            val old = now - WINDOW - 5 * 60 * 1000L
            SpecRuleStore.persistPendingStored(db, WECHAT, "DATA", payload("SYNTH-PENDING-OLD-01"), old)
            check(SpecRuleStore.pendingData(db, now - WINDOW).isEmpty())
            // 行仍在库里：过期只是不重放，不是删除。
            check(db.rules().recentData(10).size == 1)
        }
    }

    /** 与生产 [HookIngress] 里 `process` 的判定段等价，只是投递换成隔离库里的真实接受事务。 */
    private suspend fun processRow(db: AppDb, row: AppDataEntity): CaptureCoordinator.Accepted? {
        val parsed = SpecRuleStore.parseStored(db, engine, row.app, row.type, row.data, version, now)
        val hit = parsed.hit ?: return null
        check(hit.draft.type == BillType.EXPENSE && hit.draft.priceCents == 1L)
        val observation = "hook:" + CaptureMergePolicy.digest(row.app + "|" + row.data)
        return db.withTransaction {
            CaptureCoordinator.acceptInTransaction(db,
                CaptureCoordinator.Input(hit.draft, "HOOK", observation, traceId = observation, receivedAt = now))
        }
    }

    private fun payload(id: String): String {
        val content = mapOf("msg" to mapOf("appmsg" to mapOf(
            "type" to "2000", "title" to "微信转账", "des" to "合成转账记录",
            "wcpayinfo" to mapOf("paysubtype" to "1", "feedesc" to "￥0.01",
                "transferid" to id, "paymsgid" to id, "begintransfertime" to businessSeconds.toString()),
        )))
        return MiniJson.encode(mapOf("type" to "transfer", "isSend" to 1,
            "content" to MiniJson.encode(content), "chatUser" to "synthetic-peer",
            "hookerUser" to "synthetic-peer", "hookUser" to "synthetic-peer", "t" to now,
            "cachedPayTools" to "", "cachedPayMoney" to "", "cachedPayShop" to ""))
    }

    private companion object {
        const val WECHAT = "com.tencent.mm"
        const val WINDOW = 15 * 60 * 1000L
    }
}
