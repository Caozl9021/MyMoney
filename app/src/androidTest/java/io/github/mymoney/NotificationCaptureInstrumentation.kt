package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.mymoney.capture.CaptureCoordinator
import io.github.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlinx.coroutines.*
import java.util.UUID

/** Synthetic transaction coordination only. Every case uses a fresh IN-MEMORY Room database.
 * This runner never calls AppDb.get(), RecognitionStore, BillWriter, or UI delivery and never
 * accesses production billing data. It does not establish real payment/notification coverage.
 */
class NotificationCaptureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        super.onStart()
        val results = mutableListOf<Map<String, Any?>>()
        var failure: Throwable? = null
        try {
            runBlocking(Dispatchers.IO) {
                suspend fun test(name: String, body: suspend (AppDb) -> Unit) {
                    val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                    val start = System.currentTimeMillis()
                    try {
                        withTimeout(30_000) { body(db) }
                        results += mapOf("name" to name, "status" to "passed", "durationMs" to System.currentTimeMillis() - start)
                    } catch (error: Throwable) {
                        results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
                        throw error
                    } finally { db.close() }
                }

                test("synthetic_page_then_notification_strong_id_one_main_record") { db ->
                    val first = accept(db, input("PAGE", "page-1", draft("order-a")))
                    val second = accept(db, input("NOTIFICATION", "notification-1", draft("order-a").copy(merchant = "合成补全商户")))
                    check(first.created && !second.created && first.row.id == second.row.id)
                    check(db.recognitions().allOnce().size == 1)
                    check(db.recognitions().byId(first.row.id)?.merchant == "合成补全商户")
                    check(db.capture().forRecord(first.row.id).size == 2)
                    check(db.bills().allOnce().isEmpty() && db.assets().entries().isEmpty())
                }

                test("synthetic_notification_then_page_strong_id_one_main_record") { db ->
                    val first = accept(db, input("NOTIFICATION", "notification-1", draft("order-a")))
                    val second = accept(db, input("PAGE", "page-1", draft("order-a").copy(method = "合成账户")))
                    check(first.created && !second.created && first.row.id == second.row.id)
                    check(db.recognitions().allOnce().size == 1)
                    check(db.recognitions().byId(first.row.id)?.method == "合成账户")
                }

                test("synthetic_distinct_strong_ids_same_amount_keep_two_transactions") { db ->
                    val first = accept(db, input("NOTIFICATION", "reused-key", draft("order-a")))
                    val second = accept(db, input("NOTIFICATION", "reused-key", draft("order-b")))
                    val repeat = accept(db, input("PAGE", "page-second", draft("order-b")))
                    check(first.created && second.created && !repeat.created)
                    check(first.row.id != second.row.id && repeat.row.id == second.row.id)
                    check(db.recognitions().allOnce().size == 2)
                    check(first.row.reviewOf == null && second.row.reviewOf == null)
                }

                test("synthetic_undelivered_cross_source_candidate_does_not_swallow_the_notification") { db ->
                    val first = accept(db, input("PAGE", "page-weak", draft(null).copy(captureSessionId = "page-weak")))
                    val second = accept(db, input("NOTIFICATION", "notification-weak", draft(null).copy(captureSessionId = "notification-weak")))
                    check(first.created && second.created && first.row.id != second.row.id)
                    // The page row never reached the screen, so the notification is still presented.
                    check(second.decision == "new" && second.row.reviewOf == null)
                    check(!second.reviewSuppressed && second.reviewReason == CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT)
                    check(second.row.status == RecognitionStatus.PENDING)
                    check(first.row.reviewOf == null && db.recognitions().byId(first.row.id)?.reviewOf == null)
                    check(db.recognitions().allOnce().size == 2)
                    check(db.bills().allOnce().isEmpty())
                }

                test("synthetic_delivered_candidate_keeps_second_source_in_review") { db ->
                    val first = accept(db, input("PAGE", "page-shown", draft(null).copy(captureSessionId = "page-shown")))
                    // The earlier row was actually presented: its receipt is what licenses suppression.
                    db.withTransaction { db.capture().putDelivery(CaptureDelivery(first.row.id,
                        CaptureDeliveryState.ACKNOWLEDGED, "attempt-shown", 1_700_000_000_500L)) }
                    val second = accept(db, input("NOTIFICATION", "notification-repeated", draft(null)
                        .copy(captureSessionId = "notification-repeated")))
                    check(second.created && second.row.id != first.row.id)
                    check(second.decision == "needs-review" && second.row.reviewOf == first.row.id)
                    check(second.reviewSuppressed && second.reviewReason == CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT)
                    check(db.recognitions().allOnce().size == 2)
                }

                test("synthetic_weak_notification_then_strong_page_requires_review") { db ->
                    val weak = accept(db, input("NOTIFICATION", "weak-notification", draft(null).copy(captureSessionId = "weak-notification")))
                    check(weak.created && weak.row.reviewOf == null)
                    val strong = accept(db, input("PAGE", "strong-page", draft("page-order")))
                    check(strong.created && weak.row.id != strong.row.id)
                    // Neither source has been presented yet, so the page delivery proceeds.
                    check(strong.decision == "new" && strong.row.reviewOf == null)
                    check(!strong.reviewSuppressed && strong.reviewReason == CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT)
                    check(db.recognitions().allOnce().size == 2)
                }

                test("synthetic_weak_page_then_strong_notification_requires_review") { db ->
                    val weak = accept(db, input("PAGE", "weak-page", draft(null).copy(captureSessionId = "weak-page")))
                    val strong = accept(db, input("NOTIFICATION", "strong-notification", draft("notification-order")))
                    check(weak.created && strong.created && weak.row.id != strong.row.id)
                    check(strong.decision == "new" && strong.row.reviewOf == null)
                    check(!strong.reviewSuppressed && strong.reviewReason == CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT)
                    check(db.recognitions().allOnce().size == 2)
                }

                test("synthetic_two_cross_source_distinct_strong_ids_are_independent") { db ->
                    val page = accept(db, input("PAGE", "page-order-a", draft("order-a")))
                    val notification = accept(db, input("NOTIFICATION", "notification-order-b", draft("order-b")))
                    check(page.created && notification.created && page.row.id != notification.row.id)
                    check(page.row.reviewOf == null && notification.row.reviewOf == null)
                    check(db.recognitions().allOnce().size == 2)
                }

                test("synthetic_conflicting_strong_id_update_reuses_existing_review") { db ->
                    val page = accept(db, input("PAGE", "original-page", draft("same-order")))
                    val conflicting = draft("same-order").copy(priceCents = 2999)
                    val firstNotification = accept(db, input("NOTIFICATION", "conflicting-notification", conflicting))
                    check(firstNotification.created && firstNotification.row.reviewOf == page.row.id)
                    check(firstNotification.decision == "needs-review")
                    val update = accept(db, input("NOTIFICATION", "conflicting-notification", conflicting))
                    check(!update.created && update.row.id == firstNotification.row.id)
                    check(update.row.reviewOf == page.row.id)
                    check(db.recognitions().allOnce().size == 2)
                    check(db.recognitions().byId(page.row.id)?.priceCents == 1999L)
                }

                test("synthetic_ignored_and_recorded_rows_are_not_enriched_or_reopened") { db ->
                    for ((index, status) in listOf(RecognitionStatus.IGNORED, RecognitionStatus.RECORDED).withIndex()) {
                        val original = accept(db, input("PAGE", "page-$index", draft("order-$index")))
                        val protected = original.row.copy(status = status, merchant = "人工商户", note = "人工备注",
                            billId = if (status == RecognitionStatus.RECORDED) 777L else null,
                            protectedFields = CaptureFields.ALL)
                        db.withTransaction { db.recognitions().update(protected) }
                        val later = accept(db, input("NOTIFICATION", "notification-$index", draft("order-$index").copy(merchant = "不得覆盖", note = "不得覆盖")))
                        check(!later.created && later.row == protected)
                        check(db.recognitions().byId(protected.id) == protected)
                    }
                    check(db.bills().allOnce().isEmpty() && db.assets().entries().isEmpty())
                }

                test("synthetic_concurrent_sources_and_claim_are_atomic_idempotent") { db ->
                    val accepted = coroutineScope {
                        (0 until 8).map { index -> async {
                            accept(db, input(if (index % 2 == 0) "PAGE" else "NOTIFICATION", "observation-$index", draft("one-order")))
                        } }.awaitAll()
                    }
                    check(accepted.map { it.row.id }.distinct().size == 1)
                    check(accepted.count { it.created } == 1)
                    val id = accepted.first().row.id
                    val claims = coroutineScope { (0 until 8).map { index -> async {
                        db.withTransaction {
                            db.capture().claim(CaptureDelivery(id, CaptureDeliveryState.CLAIMED, "attempt-$index", 2000L))
                        }
                    } }.awaitAll() }
                    check(claims.count { it != -1L } == 1)
                    check(db.capture().deliveries().size == 1)
                    check(db.capture().delivery(id)?.state == CaptureDeliveryState.CLAIMED)
                    check(db.recognitions().allOnce().size == 1 && db.bills().allOnce().isEmpty())
                }

                test("synthetic_duplicate_trace_does_not_append_or_duplicate_main_record") { db ->
                    val first = input("NOTIFICATION", "notification-1", draft("order-a")).copy(traceId = "fixed-trace")
                    val one = accept(db, first)
                    val two = accept(db, first)
                    check(one.row.id == two.row.id && !two.created && two.decision == "duplicate-event")
                    check(db.capture().forRecord(one.row.id).size == 1)
                    check(db.recognitions().allOnce().size == 1)
                }

                test("synthetic_same_notification_slot_new_instance_same_amount_requires_review") { db ->
                    val first = accept(db, input("NOTIFICATION", "slot-instance-a", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a"))
                    val update = accept(db, input("NOTIFICATION", "slot-instance-b", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a", receivedAt = first.row.recognizedAt + 1))
                    check(first.created && first.row.reviewOf == null)
                    check(update.created && update.row.id != first.row.id)
                    check(update.decision == "needs-review" && update.row.reviewOf == first.row.id)
                    check(db.recognitions().allOnce().size == 2)
                    val slot = "review-slot:" + CaptureMergePolicy.digest("notification-slot-a")
                    check(db.capture().alias(slot)?.recognitionId == update.row.id)
                    check(db.bills().allOnce().isEmpty() && db.assets().entries().isEmpty())
                }

                test("synthetic_same_notification_slot_distinct_strong_ids_stay_independent") { db ->
                    val first = accept(db, input("NOTIFICATION", "slot-strong-a", draft("order-a"))
                        .copy(reviewSlotKey = "notification-slot-a"))
                    val second = accept(db, input("NOTIFICATION", "slot-strong-b", draft("order-b"))
                        .copy(reviewSlotKey = "notification-slot-a", receivedAt = first.row.recognizedAt + 1))
                    check(first.created && second.created && first.row.id != second.row.id)
                    check(first.row.reviewOf == null && second.row.reviewOf == null)
                    check(first.decision == "new" && second.decision == "new")
                    check(db.recognitions().allOnce().size == 2)
                    check(db.bills().allOnce().isEmpty())
                }

                test("synthetic_same_observation_notification_update_still_uses_one_record") { db ->
                    val original = input("NOTIFICATION", "same-notification-instance", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a")
                    val first = accept(db, original)
                    val updated = accept(db, original.copy(traceId = "updated-same-instance",
                        draft = original.draft.copy(merchant = "合成更新商户"), receivedAt = original.receivedAt + 1))
                    check(first.created && !updated.created && first.row.id == updated.row.id)
                    check(updated.row.reviewOf == null)
                    check(db.recognitions().allOnce().size == 1)
                    check(db.recognitions().byId(first.row.id)?.merchant == "合成更新商户")
                }

                test("synthetic_review_slot_timeout_or_changed_amount_does_not_force_review") { db ->
                    val first = accept(db, input("NOTIFICATION", "slot-first-instance", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a"))
                    val afterWindow = accept(db, input("NOTIFICATION", "slot-after-window", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a", receivedAt = first.row.recognizedAt + 120_001))
                    val changedAmount = accept(db, input("NOTIFICATION", "slot-changed-amount", draft(null).copy(priceCents = 2999))
                        .copy(reviewSlotKey = "notification-slot-a", receivedAt = first.row.recognizedAt + 120_002))
                    check(first.created && afterWindow.created && changedAmount.created)
                    check(setOf(first.row.id, afterWindow.row.id, changedAmount.row.id).size == 3)
                    check(afterWindow.row.reviewOf == null && changedAmount.row.reviewOf == null)
                    check(afterWindow.decision == "new" && changedAmount.decision == "new")
                    check(db.recognitions().allOnce().size == 3)
                }

                test("synthetic_review_slot_repeated_review_instance_does_not_add_third_record_or_review_chain") { db ->
                    val first = accept(db, input("NOTIFICATION", "slot-primary", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a"))
                    val candidateInput = input("NOTIFICATION", "slot-review-instance", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a", receivedAt = first.row.recognizedAt + 1)
                    val review = accept(db, candidateInput)
                    val sameInstance = accept(db, candidateInput.copy(traceId = "review-update", receivedAt = candidateInput.receivedAt + 1))
                    check(review.created && review.row.reviewOf == first.row.id)
                    check(!sameInstance.created && sameInstance.row.id == review.row.id)
                    check(sameInstance.row.reviewOf == first.row.id)
                    check(db.recognitions().allOnce().size == 2)
                    val nextInstance = accept(db, input("NOTIFICATION", "slot-next-new-instance", draft(null))
                        .copy(reviewSlotKey = "notification-slot-a", receivedAt = first.row.recognizedAt + 3))
                    check(nextInstance.created && nextInstance.row.reviewOf == first.row.id)
                    check(nextInstance.row.reviewOf != review.row.id)
                    check(nextInstance.decision == "needs-review")
                    check(db.recognitions().allOnce().size == 3)
                    val slot = "review-slot:" + CaptureMergePolicy.digest("notification-slot-a")
                    check(db.capture().alias(slot)?.recognitionId == nextInstance.row.id)
                    // Review candidates cannot enter the automatic delivery branch; this runner
                    // only checks coordination state and never calls actual UI delivery.
                    check(db.capture().deliveries().isEmpty())
                }

                // ---- SPEC §7.4 第二层去重（阶段 5 验收：跨源同一笔只落一条） ----

                test("synthetic_spec_dedup_cross_source_same_amount_keeps_one_record") { db ->
                    // 微信支付：WCDB 抓到 mMap，通知栏抓到 title/text，两条都没有订单号。
                    val hook = accept(db, input("PAGE", "wechat-wcdb", dedupDraft()))
                    val notice = accept(db, input("NOTIFICATION", "wechat-notice",
                        dedupDraft().copy(merchant = "通知补全商户")))
                    check(hook.created && hook.row.reviewOf == null)
                    check(!notice.created && notice.row.id == hook.row.id)
                    check(notice.decision == "linked")
                    check(db.recognitions().allOnce().size == 1)
                    // 字段互补：通知给了商户名，落库记录应拿到它。
                    check(db.recognitions().byId(hook.row.id)?.merchant == "通知补全商户")
                    check(db.capture().forRecord(hook.row.id).size == 2)
                    check(db.bills().allOnce().isEmpty())
                }

                test("synthetic_spec_dedup_same_source_same_amount_stays_two_transactions") { db ->
                    // 同一通道窗口内报两次同额：可能是连买两杯同样的咖啡，不能吞第二笔。
                    val first = accept(db, input("NOTIFICATION", "notice-a", dedupDraft()))
                    val second = accept(db, input("NOTIFICATION", "notice-b", dedupDraft()))
                    check(first.created && second.created && first.row.id != second.row.id)
                    check(db.recognitions().allOnce().size == 2)
                }

                test("synthetic_spec_dedup_merge_still_delivers_when_target_never_presented") { db ->
                    // 先到的那条从未送到屏幕（没投递），合并后必须由本次输入负责弹框，
                    // 否则一笔真实付款会彻底消失——这正是 CaptureReviewPolicy 记下的旧坑。
                    val hook = accept(db, input("PAGE", "wechat-wcdb", dedupDraft()))
                    check(hook.created && hook.deliverable)
                    val notice = accept(db, input("NOTIFICATION", "wechat-notice", dedupDraft()))
                    check(!notice.created && notice.row.id == hook.row.id)
                    check(notice.deliverable)
                    check(db.capture().deliveries().isEmpty())
                }

                test("synthetic_spec_dedup_merge_suppresses_when_target_presented") { db ->
                    val hook = accept(db, input("PAGE", "wechat-wcdb", dedupDraft()))
                    db.withTransaction { db.capture().putDelivery(CaptureDelivery(hook.row.id,
                        CaptureDeliveryState.ACKNOWLEDGED, "attempt-shown", 1_700_000_000_500L)) }
                    val notice = accept(db, input("NOTIFICATION", "wechat-notice", dedupDraft()))
                    check(!notice.created && notice.row.id == hook.row.id)
                    // 已经弹过卡了，第二条来源不再重复弹。
                    check(!notice.deliverable)
                    check(db.recognitions().allOnce().size == 1)
                }

                test("synthetic_spec_dedup_real_order_id_wins_over_amount_time") { db ->
                    // 有真实订单号时以订单号为准：不同订单号即使同额同桶也是两笔。
                    val a = accept(db, input("PAGE", "order-a", dedupDraft().copy(transactionKey = "order-a")))
                    val b = accept(db, input("NOTIFICATION", "order-b", dedupDraft().copy(transactionKey = "order-b")))
                    check(a.created && b.created && a.row.id != b.row.id)
                    check(db.recognitions().allOnce().size == 2)
                }

                test("synthetic_spec_dedup_wechat_hook_and_notification_keep_one_record") { db ->
                    // SPEC §8 阶段 5 的验收口径：同一笔微信支付同时被 WCDB 通道和通知栏抓到，
                    // 只能落一条。两条来源的金额与交易时间完全相同，且都没有真实订单号
                    // （163 条规则都不产出订单号），所以只能靠 SpecDedup 的合成身份合并。
                    val hook = accept(db, input("HOOK", "wechat-wcdb", dedupDraft()))
                    check(hook.created && hook.deliverable)
                    val notice = accept(db, input("NOTIFICATION", "wechat-notice",
                        dedupDraft().copy(merchant = "微信通知补全的商户")))
                    check(!notice.created && notice.row.id == hook.row.id)
                    check(db.recognitions().allOnce().size == 1)
                    check(db.recognitions().byId(hook.row.id)?.merchant == "微信通知补全的商户")
                    // 两条来源都留下了证据，事后能查清这笔账是哪几路一起认出来的。
                    check(db.capture().forRecord(hook.row.id).size == 2)
                    check(db.bills().allOnce().isEmpty())
                }

                test("synthetic_spec_dedup_no_key_never_merges") { db ->
                    // 时间不可信时 SpecRuleStore 不给 dedupKey，此时必须完全退回旧行为。
                    val a = accept(db, input("PAGE", "page-no-key", draft(null).copy(captureSessionId = "page-no-key")))
                    val b = accept(db, input("NOTIFICATION", "notice-no-key", draft(null).copy(captureSessionId = "notice-no-key")))
                    check(a.created && b.created && a.row.id != b.row.id)
                    check(b.reviewReason == CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT)
                    check(db.recognitions().allOnce().size == 2)
                }

                test("room5_new_schema_tables_exist_only_in_isolated_memory") { db ->
                    check(db.openHelper.readableDatabase.version == 9)
                    val names = mutableSetOf<String>()
                    db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                        while (cursor.moveToNext()) names += cursor.getString(0)
                    }
                    check(names.containsAll(setOf("capture_aliases", "capture_evidence", "capture_deliveries", "recognition_history", "bills",
                        "rule_model", "app_data")))
                    check(db.recognitions().allOnce().isEmpty() && db.bills().allOnce().isEmpty())
                }
            }
        } catch (error: Throwable) { failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putInt("tests", results.size)
            putString("report", MiniJson.encode(mapOf("database" to "isolated-in-memory", "source" to "synthetic-contract-fixtures",
                "results" to results, "error" to failure?.stackTraceToString())))
        })
    }

    private fun draft(order: String?) = BillDraft(priceCents = 1999L, type = BillType.EXPENSE,
        timeMillis = 1_700_000_000_000L, merchant = null, method = null, orderTail = null,
        sourcePkg = "io.github.mymoney.synthetic-notification-contract", strategy = "synthetic-contract",
        transactionKey = order, captureSessionId = "synthetic-session")

    /**
     * 带合成去重身份的草稿：同包名 + 同金额 + 同时间桶。
     *
     * 与 [draft] 的区别只有 [BillDraft.dedupKey]——这正是 SPEC §7.4 第二层去重的输入。
     */
    private fun dedupDraft() = draft(null).copy(
        timeMillis = 1_791_372_831_000L,
        hasExplicitTime = true,
        captureSessionId = null,
        dedupKey = com.example.billkit.spec.SpecDedup.key(
            "io.github.mymoney.synthetic-notification-contract", BillType.EXPENSE, 1999L, 1_791_372_831_000L),
    )

    private fun input(source: String, observation: String, draft: BillDraft) = CaptureCoordinator.Input(
        draft = draft, source = source, observationKey = observation, traceId = UUID.randomUUID().toString(),
        receivedAt = 1_700_000_000_100L,
    )

    private suspend fun accept(db: AppDb, input: CaptureCoordinator.Input) =
        db.withTransaction { CaptureCoordinator.acceptInTransaction(db, input) }
}
