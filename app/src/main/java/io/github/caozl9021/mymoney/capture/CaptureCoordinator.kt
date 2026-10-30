package io.github.caozl9021.mymoney.capture

import android.app.KeyguardManager
import android.content.Context
import androidx.room.withTransaction
import io.github.caozl9021.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.spec.SpecDedup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One transaction owner for both inputs; no UI or learning is performed by matching. */
object CaptureCoordinator {
    private val lock = Mutex()

    /**
     * 所有采集来源。跨源同额合并只在这些来源之间互相比较——
     * 新增通道（如短信）必须登记在这里，否则它跟其它通道的重复不会被发现。
     */
    val CAPTURE_SOURCES = setOf("PAGE", "NOTIFICATION", "SMS", "HOOK", "ROOT")

    data class Input(val draft: BillDraft, val source: String, val observationKey: String,
        val traceId: String = UUID.randomUUID().toString(), val receivedAt: Long = System.currentTimeMillis(), val reviewSlotKey: String? = null)
    /** [reviewReason] 是待核对的来源（见 [CaptureReviewPolicy]），为空表示不是待核对。
     * [reviewSuppressed] 为真表示被指向的那条已经呈现过，因此这次不再自动投递；
     * 只有被压制时才阻止弹框，避免"上一笔没弹过却把这一笔一起吞掉"。
     */
    data class Accepted(val row: RecognitionEntity, val created: Boolean, val decision: String,
        val reviewReason: String? = null, val reviewSuppressed: Boolean = false,
        /**
         * 这条输入是否还该走一次投递。
         *
         * 默认等于 [created]：新建的记录当然要投递，合并到已有记录的不再重复弹框。
         * 唯一的例外是 SPEC §7.4 的跨源合并——合并目标如果**从未呈现过**（投递被抑制、失败，
         * 或当时通道刚关），把它算成"已投递"会让一笔真实付款彻底从屏幕上消失，
         * 所以那种情况下合并后仍由本次输入负责投递。
         */
        val deliverable: Boolean = created)

    suspend fun accept(context: Context, input: Input, allowed: () -> Boolean = { true }): Accepted? = lock.withLock {
        if (!allowed()) return@withLock null
        RecognitionStore.init(context)
        RecognitionStore.awaitReady()
        val db = AppDb.get(context)
        db.withTransaction { if (allowed()) acceptInTransaction(db, input) else null }
    }

    internal suspend fun acceptInTransaction(db: AppDb, input: Input): Accepted {
        val dao = db.capture()
        val draft = input.draft
        require(draft.priceCents > 0)
        val eventId = input.traceId
        dao.evidenceById(eventId)?.recognitionId?.let { id ->
            db.recognitions().byId(id)?.let { return Accepted(it, false, "duplicate-event") }
        }
        val observation = "obs:" + CaptureMergePolicy.digest(input.observationKey)
        val reviewSlot = input.reviewSlotKey?.takeIf { input.source == "NOTIFICATION" && it.isNotBlank() }
            ?.let { "review-slot:" + CaptureMergePolicy.digest(it) }
        val strong = CaptureMergePolicy.strongAlias(draft)
        val strongRow = strong?.let { dao.alias(it)?.recognitionId }?.let { db.recognitions().byId(it) }
            ?: if (strong != null) db.recognitions().byIdentity(RecognitionIdentity.key(draft)) else null
        val observedRow = dao.alias(observation)?.recognitionId?.let { db.recognitions().byId(it) }
        // An explicit distinct transaction wins over notification-key reuse or amount similarity.
        val distinctStrong = observedRow?.transactionKey?.let { old ->
            !draft.transactionKey.isNullOrBlank() && old != draft.transactionKey
        } == true
        var matched = if (strongRow != null && observedRow?.reviewOf == strongRow.id) observedRow
            else strongRow ?: observedRow?.takeUnless { distinctStrong }
        // A repeated callback for the same observation instance with the same amount is the same
        // draft, even when a strong alias points at another row: never append a second record just
        // because that other row was not presented.
        if (strongRow != null && observedRow != null && !distinctStrong && matched === strongRow &&
            observedRow.priceCents == draft.priceCents && observedRow.type == draft.type.name) matched = observedRow
        // A row that only exists as a known-same review candidate (same identity or same instance)
        // may still collect fields while the conflict keeps it pending.
        if (matched == null && observedRow?.reviewOf != null && observedRow.status == RecognitionStatus.PENDING &&
            observedRow.priceCents == draft.priceCents && observedRow.type == draft.type.name) matched = observedRow
        if (matched == null && input.source != "NOTIFICATION") matched = db.recognitions().byIdentity(RecognitionIdentity.key(draft))
        var review: RecognitionEntity? = null
        var reviewReason: String? = null
        if (matched != null && matched.status == RecognitionStatus.PENDING && matched.reviewOf == null) {
            val original = matched.toDraft()
            val protected = matched.protectedFields
            val conflict = (protected and CaptureFields.AMOUNT == 0L && original.priceCents != draft.priceCents) ||
                (protected and CaptureFields.TYPE == 0L && original.type != draft.type) ||
                (protected and CaptureFields.TIME == 0L && original.hasExplicitTime && draft.hasExplicitTime && original.timeMillis != draft.timeMillis)
            if (conflict) {
                reviewReason = if (dao.alias(observation)?.recognitionId == matched.id) CaptureReviewPolicy.SAME_OBSERVATION
                    else CaptureReviewPolicy.FIELD_CONFLICT
                review = matched; matched = null
            }
        }
        // SPEC §7.4 第二层去重：同包名 + 同金额 + 同时间桶的**跨来源**重复。
        //
        // 这条判定必须放在强身份之后、待核对之前：有真实订单号时以订单号为准（那是确定的同一笔），
        // 而这里只是"金额和时间撞上了"，只有另一个来源也报了同一笔才成立。
        // 同一来源在窗口内报两次同额（连买两杯同样的咖啡）是两笔真实消费，靠 SpecDedup.shouldMerge
        // 里的来源比较排除掉。
        var dedupMerged = false
        var dedupTargetPresented = false
        if (matched == null && review == null) {
            val dedupAlias = draft.dedupKey?.takeIf(String::isNotBlank)?.let { "dedup:" + it }
            val dedupRow = dedupAlias?.let { dao.alias(it)?.recognitionId }?.let { db.recognitions().byId(it) }
            if (dedupRow != null && dedupRow.status == RecognitionStatus.PENDING && dedupRow.reviewOf == null &&
                dedupRow.priceCents == draft.priceCents && dedupRow.type == draft.type.name &&
                SpecDedup.shouldMerge(draft.dedupKey, input.source,
                    dao.forRecord(dedupRow.id).map { it.source })) {
                matched = dedupRow
                dedupMerged = true
                dedupTargetPresented = CaptureReviewPolicy.presented(dedupRow.status, dao.delivery(dedupRow.id)?.state)
            }
        }
        if (matched == null && review == null && reviewSlot != null) {
            val previous = dao.alias(reviewSlot)?.recognitionId?.let { db.recognitions().byId(it) }
            if (previous != null && previous.sourcePkg == draft.sourcePkg && previous.type == draft.type.name &&
                previous.priceCents == draft.priceCents &&
                kotlin.math.abs(previous.recognizedAt - input.receivedAt) <= 120_000 &&
                (strong == null || previous.transactionKey.isNullOrBlank())) {
                review = previous.reviewOf?.let { db.recognitions().byId(it) } ?: previous
                reviewReason = CaptureReviewPolicy.SAME_SLOT_SAME_AMOUNT
            }
        }
        if (matched == null && review == null) {
            val recent = db.recognitions().captureCandidates(draft.sourcePkg, draft.type.name, draft.priceCents,
                input.receivedAt - 120_000, input.receivedAt + 120_000)
            for (candidate in recent) {
                // Amount/time only identify a review suggestion. They never authorize a merge.
                if ((strong == null || candidate.transactionKey.isNullOrBlank()) &&
                    dao.forRecord(candidate.id).any { it.source in CAPTURE_SOURCES && it.source != input.source }) {
                    review = candidate.reviewOf?.let { db.recognitions().byId(it) } ?: candidate
                    reviewReason = CaptureReviewPolicy.CROSS_SOURCE_SAME_AMOUNT
                    break
                }
            }
        }
        // Two kinds of review candidates:
        //  - weak guesses (same slot / other source, same amount): only suppress this delivery when
        //    the earlier record was actually shown. Suppressing on "a same-amount record exists"
        //    alone swallowed real payments whose predecessor never reached the screen.
        //  - known-same conflicts (same transaction identity or the same notification instance with
        //    changed content): always suppressed, because presenting them would show one payment twice.
        val reviewPresented = review?.let { CaptureReviewPolicy.presented(it.status, dao.delivery(it.id)?.state) } == true
        val reviewSuppressed = review != null &&
            CaptureReviewPolicy.suppresses(CaptureReviewPolicy.isWeakGuess(reviewReason), reviewPresented)
        val reviewLink = if (reviewSuppressed) review else null
        val created = matched == null
        val row = if (matched != null) {
            CaptureMergePolicy.enrich(matched, draft, CaptureEditLocks.contains(matched.id)).also {
                if (it != matched) db.recognitions().update(it.copy(updatedAt = input.receivedAt))
            }
        } else {
            val identity = if (reviewLink == null && strong != null) RecognitionIdentity.key(draft)
                else "capture:" + UUID.randomUUID().toString()
            RecognitionEntity.fromDraft(draft.copy(historyId = null), identity, input.receivedAt)
                .copy(reviewOf = reviewLink?.id).also { db.recognitions().insert(it) }
        }
        // A candidate that never reached the screen stays an independent pending row: this one is
        // presented normally, and the evidence below records that the pair may be the same bill.
        val decision = when {
            reviewSuppressed -> "needs-review"
            created -> "new"
            else -> "linked"
        }
        // A conflicting alias remains attached to its original record; evidence explains the review.
        if (dao.alias(observation) == null) dao.putAlias(CaptureAlias(observation, row.id))
        if (strong != null && dao.alias(strong) == null && reviewLink == null) dao.putAlias(CaptureAlias(strong, row.id))
        if (reviewSlot != null && created) dao.putReviewSlot(CaptureAlias(reviewSlot, row.id))
        // 合并与新建都要登记合成身份：下一个来源（第三条通道）也要能找回这条记录。
        draft.dedupKey?.takeIf(String::isNotBlank)?.let { key ->
            val alias = "dedup:" + key
            if (dao.alias(alias) == null) dao.putAlias(CaptureAlias(alias, row.id))
        }
        dao.putEvidence(CaptureEvidence(eventId, row.id, input.source, draft.sourcePkg,
            input.receivedAt, decision, buildString {
                append("trace=").append(CaptureMergePolicy.digest(input.traceId).take(16))
                if (reviewReason != null) append(";review=").append(reviewReason)
                    .append(";reviewOf=").append(if (reviewSuppressed) "presented" else "not-presented")
                if (dedupMerged) append(";dedup=merged").append(if (dedupTargetPresented) ";dedupTarget=presented" else ";dedupTarget=not-presented")
            }))
        val deliverable = if (dedupMerged) !dedupTargetPresented else created
        return Accepted(row, created, decision, reviewReason, reviewSuppressed, deliverable)
    }

    /** A persistent claim is at-most-once for automatic UI, not an exactly-once OS transaction. */
    suspend fun deliver(context: Context, accepted: Accepted, allowed: () -> Boolean): String {
        val row = accepted.row
        if (!accepted.deliverable || row.reviewOf != null || row.status != RecognitionStatus.PENDING) return accepted.decision
        val db = AppDb.get(context)
        val attempt = UUID.randomUUID().toString()
        val claimed = db.withTransaction {
            val latest = db.recognitions().byId(row.id) ?: return@withTransaction false
            if (latest.status != RecognitionStatus.PENDING || latest.reviewOf != null) return@withTransaction false
            db.capture().claim(CaptureDelivery(row.id, CaptureDeliveryState.CLAIMED, attempt, System.currentTimeMillis())) != -1L
        }
        if (!claimed) return "already-claimed"
        val outcome = try {
            val settings = Settings(context)
            val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
            when {
                !allowed() -> "pending-source-disabled"
                locked -> "pending-locked"
                else -> ConfirmDelivery.deliver(context, row.toDraft(), settings.autoConfirm, allowPresentation = allowed)
            }
        } catch (error: Exception) {
            db.capture().putDelivery(CaptureDelivery(row.id, CaptureDeliveryState.UNKNOWN, attempt, System.currentTimeMillis()))
            throw error
        }
        val state = when {
            outcome in setOf("overlay", "confirm", "confirm-bg") -> CaptureDeliveryState.ACKNOWLEDGED
            outcome in setOf("pending-only", "pending-locked", "pending-source-disabled") -> CaptureDeliveryState.SUPPRESSED
            else -> CaptureDeliveryState.FAILED
        }
        db.capture().putDelivery(CaptureDelivery(row.id, state, attempt, System.currentTimeMillis()))
        db.capture().putEvidence(CaptureEvidence(UUID.randomUUID().toString(), row.id, "DELIVERY", row.sourcePkg,
            System.currentTimeMillis(), outcome, "attempt=" + attempt))
        return outcome
    }
}
