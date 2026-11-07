package io.github.mymoney.capture

import android.app.KeyguardManager
import android.content.Context
import androidx.room.withTransaction
import io.github.mymoney.data.*
import com.example.billkit.BillDraft
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One transaction owner for both inputs; no UI or learning is performed by matching. */
object CaptureCoordinator {
    private val lock = Mutex()

    val CAPTURE_SOURCES = CaptureShortWindowPolicy.sources

    data class Input(val draft: BillDraft, val source: String, val observationKey: String,
        val traceId: String = UUID.randomUUID().toString(), val receivedAt: Long = System.currentTimeMillis(), val reviewSlotKey: String? = null)
    /** [reviewReason] 是待核对的来源（见 [CaptureReviewPolicy]），为空表示不是待核对。
     * [reviewSuppressed] 为真表示被指向的那条已经呈现过，因此这次不再自动投递；
     * 只有被压制时才阻止弹框，避免"上一笔没弹过却把这一笔一起吞掉"。
     */
    data class Accepted(val row: RecognitionEntity, val created: Boolean, val decision: String,
        val reviewReason: String? = null, val reviewSuppressed: Boolean = false,
        /** Merged pending rows may retry delivery only when no successful/uncertain claim exists. */
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
        val stableIdentity = if ((input.source == "PAGE" && draft.hasExplicitTime) || strong != null) "identity:" + RecognitionIdentity.key(draft) else null
        val timeIdentity = if (input.source == "PAGE" && draft.hasExplicitTime)
            RecognitionIdentity.key(draft.copy(transactionKey = null)) else null
        val timeRow = timeIdentity?.let { key ->
            val id = dao.alias("identity:" + key)?.recognitionId
                ?: dao.alias("obs:" + CaptureMergePolicy.digest("page:" + key))?.recognitionId
            id?.let { db.recognitions().byId(it) } ?: db.recognitions().byIdentity(key)
        }?.takeUnless { CaptureShortWindowPolicy.differentTransaction(it, draft, dao.transactionAliases(it.id)) }
        val strongRow = strong?.let { dao.alias(it)?.recognitionId }?.let { db.recognitions().byId(it) }
            ?: stableIdentity?.let { dao.alias(it)?.recognitionId }?.let { db.recognitions().byId(it) }
            ?: (if (stableIdentity != null) db.recognitions().byIdentity(RecognitionIdentity.key(draft)) else null)
            ?: (if (input.source == "PAGE") dao.alias("obs:" + CaptureMergePolicy.digest("page:" + RecognitionIdentity.key(draft)))
                ?.recognitionId?.let { db.recognitions().byId(it) } else null)
            ?: timeRow
        val observedRow = dao.alias(observation)?.recognitionId?.let { db.recognitions().byId(it) }
        // An explicit distinct transaction wins over notification-key reuse or amount similarity.
        val distinctStrong = observedRow?.let {
            CaptureShortWindowPolicy.differentTransaction(it, draft, dao.transactionAliases(it.id))
        } == true
        // A later source belongs to the open confirmation, including a repeated round whose
        // strong transaction aliases deliberately remain owned by the original record.
        val activeFamilyEditor = strongRow?.let { original ->
            val rootId = dao.alias("repeat-parent:" + original.id)?.recognitionId ?: original.id
            CaptureEditLocks.activeIds().firstOrNull { id ->
                id == rootId || dao.alias("repeat-parent:" + id)?.recognitionId == rootId
            }?.let { db.recognitions().byId(it) }?.takeIf {
                it.status == RecognitionStatus.PENDING && it.priceCents == draft.priceCents &&
                    it.type == draft.type.name &&
                    !CaptureShortWindowPolicy.differentTransaction(it, draft, dao.transactionAliases(it.id))
            }
        }
        var matched = activeFamilyEditor ?: if (strongRow != null && observedRow?.reviewOf == strongRow.id) observedRow
            else strongRow ?: observedRow?.takeUnless { distinctStrong }
        // A repeated callback for the same observation instance with the same amount is the same
        // draft, even when a strong alias points at another row: never append a second record just
        // because that other row was not presented.
        if (strongRow != null && observedRow != null && !distinctStrong && matched === strongRow &&
            observedRow.priceCents == draft.priceCents && observedRow.type == draft.type.name) matched = observedRow
        // A row that only exists as a known-same review candidate (same identity or same instance)
        // may still collect fields while the conflict keeps it pending.
        if (matched == null && !distinctStrong && observedRow?.reviewOf != null && observedRow.status == RecognitionStatus.PENDING &&
            observedRow.priceCents == draft.priceCents && observedRow.type == draft.type.name) matched = observedRow
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
        // All weak cross-channel matching uses the same fixed capture-time window. In particular,
        // legacy SPEC three-minute bucket aliases must not authorize a wider automatic merge.
        var shortWindowMerged = false
        var mergeIntervalMs: Long? = null
        val recent = if (matched == null && review == null) db.recognitions().captureCandidates(
            draft.sourcePkg, draft.type.name, draft.priceCents,
            (input.receivedAt - 120_000).coerceAtLeast(0), input.receivedAt + 120_000) else emptyList()
        if (matched == null && review == null) {
            val candidates = recent.filter { candidate ->
                CaptureShortWindowPolicy.matches(candidate, draft, input.source,
                    dao.sourcesForRecord(candidate.id), input.receivedAt, dao.transactionAliases(candidate.id))
            }
            if (candidates.size == 1) {
                matched = candidates.single()
                shortWindowMerged = true
                mergeIntervalMs = kotlin.math.abs(matched.recognizedAt - input.receivedAt)
            } else if (candidates.size > 1) {
                // No arbitrary nearest-record choice when several real transactions are possible.
                review = candidates.firstOrNull {
                    CaptureReviewPolicy.presented(it.status, dao.delivery(it.id)?.state)
                } ?: candidates.first()
                reviewReason = CaptureReviewPolicy.AMBIGUOUS_CROSS_SOURCE
            }
        }
        if (matched == null && review == null && reviewSlot != null) {
            val previous = dao.alias(reviewSlot)?.recognitionId?.let { db.recognitions().byId(it) }
            if (previous != null && previous.sourcePkg == draft.sourcePkg && previous.type == draft.type.name &&
                previous.priceCents == draft.priceCents &&
                kotlin.math.abs(previous.recognizedAt - input.receivedAt) <= 120_000 &&
                !CaptureShortWindowPolicy.differentTransaction(previous, draft, dao.transactionAliases(previous.id))) {
                review = previous.reviewOf?.let { db.recognitions().byId(it) } ?: previous
                reviewReason = CaptureReviewPolicy.SAME_SLOT_SAME_AMOUNT
            }
        }
        if (matched == null && review == null) {
            for (candidate in recent) {
                // Outside the automatic window, retain the existing weak review fallback.
                if (!CaptureShortWindowPolicy.differentTransaction(candidate, draft, dao.transactionAliases(candidate.id)) &&
                    CaptureShortWindowPolicy.newChannel(input.source, dao.sourcesForRecord(candidate.id))) {
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
        // A fresh observation is a new user decision, even for a known transaction.
        // Never reopen the old terminal row or steal its transaction alias. The observation
        // owns a separate pending confirmation so explicit save can really add another bill.
        val repeatParent = matched?.takeIf {
            observedRow == null && !reviewSuppressed && !CaptureEditLocks.contains(it.id) &&
                (it.status != RecognitionStatus.PENDING ||
                    dao.delivery(it.id)?.state == CaptureDeliveryState.ACKNOWLEDGED)
        }
        if (repeatParent != null) matched = null
        val created = matched == null
        val row = if (matched != null) {
            CaptureMergePolicy.enrich(matched, draft, CaptureEditLocks.contains(matched.id),
                replaceAutomaticAccount = mayUpgradeAccount(matched, draft)).also {
                if (it != matched) db.recognitions().update(it.copy(updatedAt = input.receivedAt))
            }
        } else {
            val identity = when {
                repeatParent != null -> "repeat:" + UUID.randomUUID().toString()
                reviewLink == null && strong != null -> RecognitionIdentity.key(draft)
                else -> "capture:" + UUID.randomUUID().toString()
            }
            val confirmation = repeatParent?.let {
                CaptureMergePolicy.enrich(it.copy(status = RecognitionStatus.PENDING), draft, false)
                    .toDraft().copy(captureSessionId = draft.captureSessionId)
            } ?: draft
            RecognitionEntity.fromDraft(confirmation.copy(historyId = null), identity, input.receivedAt)
                .copy(reviewOf = reviewLink?.id, protectedFields = repeatParent?.protectedFields ?: 0L)
                .also { db.recognitions().insert(it) }
        }
        // A candidate that never reached the screen stays an independent pending row: this one is
        // presented normally, and the evidence below records that the pair may be the same bill.
        val decision = when {
            reviewSuppressed -> "needs-review"
            repeatParent != null -> "repeat-confirmation"
            created -> "new"
            else -> "linked"
        }
        // A conflicting alias remains attached to its original record; evidence explains the review.
        if (dao.alias(observation) == null) dao.putAlias(CaptureAlias(observation, row.id))
        val identityOwner = repeatParent?.let { dao.alias("repeat-parent:" + it.id)?.recognitionId ?: it.id }
            ?: dao.alias("repeat-parent:" + row.id)?.recognitionId ?: row.id
        if (repeatParent != null) dao.putAlias(CaptureAlias("repeat-parent:" + row.id, identityOwner))
        if (strong != null && dao.alias(strong) == null && reviewLink == null)
            dao.putAlias(CaptureAlias(strong, identityOwner))
        if (stableIdentity != null && dao.alias(stableIdentity) == null && reviewLink == null)
            dao.putAlias(CaptureAlias(stableIdentity, identityOwner))
        if (reviewSlot != null && created) dao.putReviewSlot(CaptureAlias(reviewSlot, row.id))
        dao.putEvidence(CaptureEvidence(eventId, row.id, input.source, draft.sourcePkg,
            input.receivedAt, decision, buildString {
                append("trace=").append(CaptureMergePolicy.digest(input.traceId).take(16))
                if (repeatParent != null) append(";repeatOf=").append(repeatParent.id)
                if (reviewReason != null) append(";review=").append(reviewReason)
                    .append(";reviewOf=").append(if (reviewSuppressed) "presented" else "not-presented")
                if (shortWindowMerged) append(";merge=").append(CaptureShortWindowPolicy.RULE)
                    .append(";intervalMs=").append(mergeIntervalMs).append(";main=").append(row.id)
            }))
        // 是否允许这次投递：
        //  - 新建（created）→ 投；
        //  - 合并进已存在记录（linked）：**只有原记录从没被 ACKNOWLEDGED（从没上过屏）且仍 PENDING 才补投**。
        //    已经上过屏（ACKNOWLEDGED）的才压制，避免同一笔弹两次；但绝不把「从没弹过」的真实付款永久吞掉。
        val retryable = row.status == RecognitionStatus.PENDING &&
            CaptureShortWindowPolicy.retryableDelivery(dao.delivery(row.id)?.state)
        val deliverable = if (created) row.status == RecognitionStatus.PENDING
            else shortWindowMerged && retryable || (!shortWindowMerged && !reviewSuppressed && retryable)
        // The shared confirmation card probes the ledger once when it is displayed.
        return Accepted(row, created, decision, reviewReason, reviewSuppressed, deliverable)
    }

    data class OcrSupplementResult(val row: RecognitionEntity, val outcome: String, val changedFields: List<String> = emptyList()) {
        val message: String get() = when (outcome) {
            "added" -> "已补充识别信息，请核对后保存"
            "no-change", "duplicate" -> "没有可补充的新信息"
            "conflict" -> "截图与当前账单信息不一致，未自动修改"
            "unusable" -> "截图信息不明确，未自动修改"
            "terminal" -> "该记录已处理或需要核对，未修改"
            else -> "页面已变化，本次结果未采用"
        }
    }

    /** Explicitly attached visual evidence. Never creates a row, aliases, delivery or asset posting. */
    suspend fun supplementOcr(context: Context, recognitionId: String, expected: BillDraft,
                             result: com.example.billkit.visual.VisualBillResult, observationId: String,
                             allowed: () -> Boolean = { true }, manualTarget: Boolean = false, engineId: String = "local"): OcrSupplementResult? = lock.withLock {
        if (!allowed()) return@withLock null
        RecognitionStore.init(context)
        RecognitionStore.awaitReady()
        val db = AppDb.get(context)
        db.withTransaction {
            if (!allowed()) null else supplementOcrInTransaction(db, recognitionId, expected, result, observationId,
                manualTarget = manualTarget, allowed = allowed, engineId = engineId)
        }
    }

    internal suspend fun supplementOcrInTransaction(db: AppDb, recognitionId: String, expected: BillDraft,
                                                   result: com.example.billkit.visual.VisualBillResult, observationId: String,
                                                   manualTarget: Boolean = false,
                                                   allowed: () -> Boolean = { true }, engineId: String = "local"): OcrSupplementResult? {
        if (!allowed()) return null
        val row = db.recognitions().byId(recognitionId) ?: return null
        if (row.status != RecognitionStatus.PENDING || row.reviewOf != null)
            return OcrSupplementResult(row, "terminal")
        if (observationId.isBlank() || (!manualTarget &&
                (db.capture().alias("obs:" + CaptureMergePolicy.digest(observationId))?.recognitionId != recognitionId ||
                    "PAGE" !in db.capture().sourcesForRecord(recognitionId))))
            return OcrSupplementResult(row, "observation-mismatch")
        val eventId = "ocr:" + CaptureMergePolicy.digest(recognitionId + "|" + observationId)
        if (db.capture().evidenceById(eventId) != null) return OcrSupplementResult(row, "duplicate")
        val resolved = OcrEvidencePolicy.resolveAccount(result.fields.method, db.assets().accounts())
        val decision = OcrEvidencePolicy.apply(row, expected, result, CaptureEditLocks.fields(row.id), resolved)
        if (!allowed()) return null
        val updated = if (decision.row != row) decision.row.copy(updatedAt = System.currentTimeMillis()) else row
        if (updated != row) db.recognitions().update(updated)
        db.capture().putEvidence(CaptureEvidence(eventId, row.id, if (manualTarget) "IMAGE" else "PAGE", row.sourcePkg, System.currentTimeMillis(),
            "ocr-" + decision.outcome, "method=ocr;observation=" + CaptureMergePolicy.digest(observationId).take(16) +
                ";engine=" + engineId.take(80).replace(Regex("[^a-zA-Z0-9._:-]"), "_") +
                ";target=" + (if (manualTarget) "explicit" else "page") + ";fields=" + decision.changedFields.joinToString(",")))
        if (!allowed()) throw kotlinx.coroutines.CancellationException("OCR source disabled")
        return OcrSupplementResult(updated, decision.outcome, decision.changedFields)
    }

    /** Recompute provenance conservatively: only a still-identical builtin suggestion may yield. */
    private fun mayUpgradeAccount(row: RecognitionEntity, incoming: BillDraft): Boolean {
        if (!row.method.isNullOrBlank() || incoming.method.isNullOrBlank() || incoming.account.isNullOrBlank()) return false
        if (row.account.isNullOrBlank()) return true
        return runCatching {
            val unique = LedgerConfig.accounts.distinct().filter { name ->
                CategorySuggester.matchAccount(incoming.method, listOf(name)) == name
            }.singleOrNull()
            val old = CategorySuggester.suggest(row.toDraft(), candidates = ExternalRuleStore.candidates()).accountHit
            unique == incoming.account && old?.source == RuleSource.BUILTIN && old.account == row.account
        }.getOrDefault(false)
    }

    /** A persistent claim is at-most-once for automatic UI, not an exactly-once OS transaction. */
    suspend fun deliver(context: Context, accepted: Accepted, allowed: () -> Boolean): String {
        val row = accepted.row
        if (!accepted.deliverable || row.reviewOf != null || row.status != RecognitionStatus.PENDING)
            // 不投递时区分原因，避免 linked 把人误导成「已记过、什么都没发生」：
            //  - 已入账/已核对：直接返回原决策；
            //  - 已存在但这次不再弹：写成「已存在记录，未重复弹卡」。
            return if (accepted.decision == "linked") "linked-existing" else accepted.decision
        val db = AppDb.get(context)
        val attempt = UUID.randomUUID().toString()
        val claimed = db.withTransaction { claimInTransaction(db, row.id, attempt) }
        if (!claimed) return "already-claimed"
        val outcome = try {
            val settings = Settings(context)
            val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
            when {
                !allowed() -> "pending-source-disabled"
                locked -> "pending-locked"
                else -> {
                    // Saving/editing may have completed since accept(); never reopen a stale draft.
                    val latest = db.recognitions().byId(row.id)
                    if (latest == null || latest.status != RecognitionStatus.PENDING || latest.reviewOf != null)
                        "pending-source-disabled"
                    else ConfirmDelivery.deliver(context, latest.toDraft(), settings.autoConfirm, allowPresentation = allowed)
                }
            }
        } catch (error: Exception) {
            db.capture().putDelivery(CaptureDelivery(row.id, CaptureDeliveryState.UNKNOWN, attempt, System.currentTimeMillis()))
            throw error
        }
        val state = when {
            outcome in setOf("overlay", "confirm", "confirm-bg", "island") -> CaptureDeliveryState.ACKNOWLEDGED
            outcome in setOf("pending-only", "pending-locked", "pending-source-disabled") -> CaptureDeliveryState.SUPPRESSED
            else -> CaptureDeliveryState.FAILED
        }
        db.capture().putDelivery(CaptureDelivery(row.id, state, attempt, System.currentTimeMillis()))
        db.capture().putEvidence(CaptureEvidence(UUID.randomUUID().toString(), row.id, "DELIVERY", row.sourcePkg,
            System.currentTimeMillis(), outcome, "attempt=" + attempt))
        return outcome
    }

    /** Caller owns the Room transaction; serializes retries with all other claims and bill saves. */
    internal suspend fun claimInTransaction(db: AppDb, id: String, attempt: String): Boolean {
        val latest = db.recognitions().byId(id) ?: return false
        if (latest.status != RecognitionStatus.PENDING || latest.reviewOf != null ||
            !CaptureShortWindowPolicy.retryableDelivery(db.capture().delivery(id)?.state)) return false
        db.capture().putDelivery(CaptureDelivery(id, CaptureDeliveryState.CLAIMED, attempt, System.currentTimeMillis()))
        return true
    }

}
