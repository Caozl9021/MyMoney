package com.example.autobook.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/** 草稿 → 账单的唯一落库入口（确认页 / 悬浮窗 / 待确认列表共用） */
object BillWriter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /**
     * 落库前的重复窗口。
     *
     * [com.example.billkit.BillKit] 的去重只在内存里（`LinkedHashMap`），进程一死就清空，
     * 所以「识别 → 杀掉 App → 再回到同一张账单页」会重复入库。这里用数据库的
     * `countRecentFingerprint` 再兜一层：同一个指纹在 10 分钟内已经存过就不再存。
     *
     * 选 10 分钟而不是 BillKit 的 90 秒：进程被回收、用户过几分钟再翻回同一页，
     * 是很常见的场景；同一个指纹本身已经包含「金额 + 分钟 + 单号尾号」，
     * 10 分钟内真正撞上的概率很低。
     */
    private const val DEDUPE_WINDOW_MS = 10 * 60 * 1000L

    data class SaveResult(val inserted: Boolean, val bill: BillEntity?)

    /**
     * 保存一条草稿。重复（同指纹 10 分钟内已存在）时静默跳过，但仍会把草稿
     * 从待确认队列里移除 —— 用户点过「记一笔」，就不该再留在队列里。
     *
     * @param onDone 落库结束后回调，`true` = 真的插入了；用于 UI 提示「这笔已经记过了」。
     *   回调在主线程执行。
     */
    fun save(context: Context, draft: BillDraft, original: BillDraft? = null, onDone: ((Boolean) -> Unit)? = null, onError: ((Throwable) -> Unit)? = null) =
        saveWithResult(context, draft, original, { onDone?.invoke(it.inserted) }, onError)

    /** Returns the entity selected by the transaction, including an existing record's real identity. */
    fun saveWithResult(context: Context, draft: BillDraft, original: BillDraft? = null,
        onDone: ((SaveResult) -> Unit)? = null, onError: ((Throwable) -> Unit)? = null) {
        val app = context.applicationContext
        scope.launch {
            try {
                require(draft.priceCents > 0L) { "账单金额必须大于0" }
                AssetStore.init(app)
                AssetStore.awaitReady()
                val result = writeLock.withLock {
                    val db = AppDb.get(app)
                    db.withTransaction {
                        val dao = db.bills()
                        val history = draft.historyId?.let { id ->
                            db.recognitions().byId(id) ?: error("识别记录不存在，请从历史重新打开")
                        }
                        require(history?.reviewOf == null) { "请先在识别历史核对是否为独立交易" }
                        if (history?.status == RecognitionStatus.RECORDED) {
                            // A deleted association is deliberately not resurrected or guessed by amount.
                            SaveResult(false, history.billId?.let { dao.byId(it) })
                        } else {
                            val since = System.currentTimeMillis() - DEDUPE_WINDOW_MS
                            var existing = if (history != null) dao.byRecognitionId(history.id)
                                ?: if (history.identityKey.startsWith("capture:") || !history.transactionKey.isNullOrBlank()) null else dao.legacyDuplicate(draft.fingerprint, draft.sourcePkg, draft.priceCents,
                                    draft.type.name, draft.orderTail, since)
                                else dao.recentDuplicate(draft.fingerprint, draft.sourcePkg, draft.priceCents,
                                    draft.type.name, draft.orderTail, since)
                            if (history != null && existing != null && existing.recognitionId == null) {
                                existing = existing.copy(recognitionId = history.id)
                                dao.update(existing)
                            }
                            val inserted = existing == null
                            val bill = existing ?: if (draft.type == BillType.TRANSFER) {
                                saveTransferDraftInTransaction(db, draft, null, history?.id ?: draft.sourcePkg + "|" + draft.fingerprint)
                            } else BillEntity(priceCents = draft.priceCents, type = draft.type.name,
                                timeMillis = draft.timeMillis, merchant = draft.merchant, method = draft.method,
                                orderTail = draft.orderTail, sourcePkg = draft.sourcePkg, strategy = draft.strategy,
                                fingerprint = draft.fingerprint, createdAt = System.currentTimeMillis(),
                                category = draft.category, subCategory = draft.subCategory,
                                account = draft.account, note = draft.note, productName = draft.productName, productSource = draft.productSource,
    noteOrigin = draft.noteOrigin, noteTemplateSnapshot = draft.noteTemplateSnapshot, recognitionId = history?.id).let {
                                    val linked = AssetStore.syncBill(db, null, it)!!
                                    linked.copy(id = dao.insert(linked))
                                }
                            history?.let {
                                db.recognitions().update(RecognitionEntity.fromDraft(draft.copy(historyId = it.id), it.identityKey, it.recognizedAt)
                                    .copy(status = RecognitionStatus.RECORDED, billId = bill.id,
                                        updatedAt = System.currentTimeMillis(), shareState = it.shareState,
                                        protectedFields = it.protectedFields or CaptureFields.changed(it.toDraft(), draft), reviewOf = it.reviewOf))
                            }
                            SaveResult(inserted, bill)
                        }
                    }
                }
                if (result.inserted) com.example.autobook.systems.SystemIntegration.refresh(app)
                if (draft.historyId == null) PendingStore.remove(original?.fingerprint ?: draft.fingerprint)
                if (result.inserted && original != null) runCatching { LocalRuleStore.recordConfirmation(original, draft) }
                withContext(Dispatchers.Main) { onDone?.invoke(result) }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { onError?.invoke(error) }
            }
        }
    }

    /** 以应用级作用域提交，切换页面不会取消写入；成功/失败均回到主线程。 */
    fun saveFromEditor(
        context: Context,
        draft: BillDraft,
        original: BillEntity?,
        onDone: (Result<Unit>) -> Unit,
    ) {
        val app = context.applicationContext
        scope.launch {
            val result = runCatching {
                AssetStore.init(app)
                AssetStore.awaitReady()
                writeLock.withLock {
                    val db = AppDb.get(app)
                    db.withTransaction { saveEditorInTransaction(db, draft, original) }
                }
                if (original != null) runCatching {
                    LocalRuleStore.recordConfirmation(toDraft(original), draft, allowFrequency = false)
                }
                Unit
            }
            if (result.isSuccess) com.example.autobook.systems.SystemIntegration.refresh(app)
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    data class UndoDeletion(val bill: BillEntity, val expiresAt: Long, val relatedBills: List<BillEntity> = emptyList())
    private val undoItems = MutableStateFlow<List<UndoDeletion>>(emptyList())
    val pendingUndo = undoItems.asStateFlow()

    /** 先真实删除，再在进程内提供5秒撤销；多笔独立过期，无延迟提交丢失风险。 */
    fun deleteUndoably(context: Context, id: Long, onError: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val result = runCatching {
                AssetStore.init(app)
                AssetStore.awaitReady()
                writeLock.withLock {
                    val db = AppDb.get(app)
                    val deleted = db.withTransaction { deleteGroupInTransaction(db, id) }
                    if (deleted.isNotEmpty()) {
                        undoItems.value = undoItems.value + UndoDeletion(deleted.first(), System.currentTimeMillis() + 5_000, deleted.drop(1))
                        com.example.autobook.systems.SystemIntegration.refresh(app)
                    }
                }
            }
            result.exceptionOrNull()?.let { error ->
                withContext(Dispatchers.Main) { onError(error.message ?: "删除失败，请重试") }
            }
            delay(5_000)
            writeLock.withLock { undoItems.value = undoItems.value.filter { it.expiresAt > System.currentTimeMillis() } }
        }
    }

    fun undoDelete(context: Context, id: Long, onDone: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val result = runCatching {
                writeLock.withLock {
                    val item = undoItems.value.firstOrNull { it.bill.id == id }
                    check(item != null && System.currentTimeMillis() <= item.expiresAt) { "撤销时间已过" }
                    val db = AppDb.get(app)
                    db.withTransaction { restoreGroupInTransaction(db, listOf(item.bill) + item.relatedBills) }
                    undoItems.value = undoItems.value.filterNot { it.bill.id == id }
                }
            }
            if (result.isSuccess) com.example.autobook.systems.SystemIntegration.refresh(app)
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** Explicitly include an older ordinary bill once. TRANSFER requires its two endpoints. */
    fun linkAsset(context: Context, id: Long, onDone: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val result = runCatching {
                AssetStore.init(app)
                AssetStore.awaitReady()
                writeLock.withLock {
                    val db = AppDb.get(app)
                    db.withTransaction {
                        val row = db.bills().byId(id) ?: error("这笔账单已不存在")
                        require(row.feeParentKey == null) { "手续费请从原转账操作" }
                        require(row.type != "TRANSFER") { "请先补全转出和转入账户" }
                        if (!row.assetLinked) {
                            val linked = AssetStore.syncBill(db, row, row, forceLink = true)!!
                            check(linked.assetLinked) { "请先设置该账户的当前余额" }
                            check(db.bills().update(linked) == 1) { "账单已不存在" }
                        }
                    }
                }
            }
            if (result.isSuccess) com.example.autobook.systems.SystemIntegration.refresh(app)
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** The form supplies one stable time for retries; reopening a new form creates a new operation. */
    fun saveTransfer(
        context: Context, fromId: String, toId: String, cents: Long, feeCents: Long, note: String,
        timeMillis: Long = System.currentTimeMillis(), original: BillEntity? = null,
        operationId: String = UUID.randomUUID().toString(), onDone: (Result<Unit>) -> Unit,
    ) {
        val app = context.applicationContext
        scope.launch {
            val result = runCatching {
                AssetStore.init(app)
                AssetStore.awaitReady()
                writeLock.withLock {
                    val db = AppDb.get(app)
                    db.withTransaction { saveTransferInTransaction(db, fromId, toId, cents, feeCents, note, timeMillis, original, operationId) }
                }
                Unit
            }
            if (result.isSuccess) com.example.autobook.systems.SystemIntegration.refresh(app)
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** Caller owns the Room transaction; exposed internally for real Room verification. */
    internal suspend fun saveEditorInTransaction(db: AppDb, draft: BillDraft, original: BillEntity?): BillEntity {
        require(draft.priceCents in 1..100_000_000L) { "金额必须大于0且不超过一百万元" }
        val dao = db.bills()
        if (draft.type == BillType.TRANSFER) return saveTransferDraftInTransaction(db, draft, original,
            original?.assetKey?.takeIf { it.isNotBlank() } ?: draft.sourcePkg)
        if (original == null) {
            val fresh = newManualEntity(draft, System.currentTimeMillis())
            // One editor source UUID is one save, even after a retry or process recreation.
            dao.allOnce().firstOrNull { it.sourcePkg == fresh.sourcePkg }?.let { saved ->
                require(saved.priceCents == fresh.priceCents && saved.type == fresh.type &&
                    saved.timeMillis == fresh.timeMillis && saved.merchant == fresh.merchant &&
                    saved.category == fresh.category && saved.subCategory == fresh.subCategory &&
                    saved.account == fresh.account && saved.note == fresh.note) {
                    "这笔账单已保存，请从原账单修改；未重复记账"
                }
                return saved
            }
            val linked = AssetStore.syncBill(db, null, fresh)!!
            return linked.copy(id = dao.insert(linked))
        }
        val current = dao.byId(original.id) ?: error("这笔账单已被删除，请重新打开明细")
        require(current.feeParentKey == null) { "手续费请从原转账一同修改" }
        require(current.type != "TRANSFER" || current.transferAccountId == null) { "请使用转账表单修改转出和转入账户" }
        // Existing one-sided transfers remain unlinked until deliberately completed.
        val edited = applyBillEdit(current, draft)
        val linked = AssetStore.syncBill(db, current, edited)!!
        check(dao.update(linked) == 1) { "这笔账单已被删除，请重新打开明细" }
        return linked
    }

    internal suspend fun saveTransferDraftInTransaction(db: AppDb, draft: BillDraft, original: BillEntity?, operationId: String): BillEntity {
        require(draft.type == BillType.TRANSFER) { "账单类型须为转账" }
        require(original?.feeParentKey == null) { "手续费请从原转账一同修改" }
        val from = AssetStore.resolveAccount(db, draft.account) ?: error("请选择转出账户")
        val to = draft.transferAccountId ?: error("请选择转入账户")
        val row = saveTransferInTransaction(db, from.id, to, draft.priceCents, original?.transferFeeCents ?: 0,
            draft.note.orEmpty(), draft.timeMillis, original, operationId, allowTypeConversion = true)
        val final = row.copy(sourcePkg = original?.sourcePkg ?: draft.sourcePkg,
            strategy = original?.strategy ?: draft.strategy, merchant = original?.merchant ?: draft.merchant,
            method = original?.method ?: draft.method, orderTail = original?.orderTail ?: draft.orderTail,
            fingerprint = original?.fingerprint ?: draft.fingerprint, recognitionId = original?.recognitionId ?: draft.historyId,
            category = null, subCategory = null, productName = draft.productName, productSource = draft.productSource,
            noteOrigin = draft.noteOrigin, noteTemplateSnapshot = draft.noteTemplateSnapshot)
        check(db.bills().update(final) == 1)
        return final
    }

    internal suspend fun saveTransferInTransaction(
        db: AppDb, fromId: String, toId: String, cents: Long, feeCents: Long, note: String,
        timeMillis: Long, original: BillEntity? = null, operationId: String? = null, allowTypeConversion: Boolean = false,
    ): BillEntity {
        require(fromId != toId) { "转出与转入账户不能相同" }
        require(cents in 1..100_000_000L && feeCents in 0..100_000_000L) { "金额必须大于0且不超过一百万元，手续费不能为负" }
        val from = db.assets().account(fromId) ?: error("转出账户不存在")
        val to = db.assets().account(toId) ?: error("转入账户不存在")
        require(from.initialized && to.initialized) { "请先设置两端账户的当前余额" }
        val dao = db.bills()
        val current = original?.let { dao.byId(it.id) ?: error("原转账已被删除") }
        require(current == null || current.type == "TRANSFER" || allowTypeConversion) { "仅转账账单可补全或修改两端" }
        // A historical transfer may be edited without losing an archived endpoint; new operations cannot use it.
        require(!from.archived || current?.accountId == fromId) { "转出账户已归档" }
        require(!to.archived || current?.transferAccountId == toId) { "转入账户已归档" }
        val operation = operationId ?: "$fromId|$toId|$cents|$feeCents|$note|$timeMillis"
        require(operation.isNotBlank()) { "转账操作编号无效" }
        val key = current?.assetKey?.takeIf { it.isNotBlank() }
            ?: UUID.nameUUIDFromBytes(operation.toByteArray(Charsets.UTF_8)).toString()
        if (current == null) dao.allOnce().firstOrNull { it.assetKey == key }?.let { saved ->
            require(saved.type == "TRANSFER" && saved.accountId == fromId && saved.transferAccountId == toId &&
                saved.priceCents == cents && saved.transferFeeCents == feeCents && saved.timeMillis == timeMillis && saved.note == note.takeIf(String::isNotBlank)) {
                "这次转账已保存，请从原账单修改；未重复转账"
            }
            return saved
        }
        val now = System.currentTimeMillis()
        val row = current?.copy(
            priceCents = cents, type = "TRANSFER", category = null, subCategory = null,
            timeMillis = timeMillis, account = from.name, accountId = fromId, transferAccountId = toId, transferAccount = to.name,
            merchant = if (current.sourcePkg.startsWith("com.example.autobook.manual.transfer.")) "${from.name} → ${to.name}" else current.merchant,
            note = note.takeIf(String::isNotBlank), transferFeeCents = feeCents, assetKey = key, assetLinked = true,
        ) ?: BillEntity(
            priceCents = cents, type = "TRANSFER", timeMillis = timeMillis, merchant = "${from.name} → ${to.name}",
            method = null, orderTail = null, sourcePkg = "com.example.autobook.manual.transfer.$key",
            strategy = "本地转账", fingerprint = "asset-transfer-$key", createdAt = now,
            account = from.name, accountId = fromId, transferAccountId = toId, transferAccount = to.name, assetLinked = true,
            assetKey = key, transferFeeCents = feeCents, note = note.takeIf(String::isNotBlank),
        )
        val finalRow = if (current != null && current.sourcePkg.startsWith("com.example.autobook.manual.") && current.recognitionId == null && !current.fingerprint.startsWith("asset-transfer-") && !current.fingerprint.startsWith("qianji:"))
            row.copy(fingerprint = toDraft(row).fingerprint) else row
        val linked = AssetStore.syncBill(db, current, finalRow, forceLink = true)!!
        val principal = if (current == null) linked.copy(id = dao.insert(linked)) else {
            check(dao.update(linked) == 1) { "原转账已被删除" }; linked
        }
        val previousFees = dao.allOnce().filter { it.feeParentKey == key }
        require(previousFees.size <= 1) { "手续费关联异常，请先恢复完整备份" }
        val previousFee = previousFees.singleOrNull()
        if (feeCents == 0L) {
            previousFee?.let { AssetStore.syncBill(db, it, null); dao.delete(it.id) }
        } else {
            val fee = previousFee?.copy(priceCents = feeCents, timeMillis = principal.timeMillis, account = from.name, accountId = fromId,
                note = note.takeIf(String::isNotBlank)) ?: BillEntity(
                priceCents = feeCents, type = "EXPENSE", timeMillis = principal.timeMillis,
                merchant = "转账手续费", method = null, orderTail = null,
                sourcePkg = "com.example.autobook.manual.transfer-fee.$key", strategy = "转账手续费",
                fingerprint = "asset-transfer-fee-$key", createdAt = now, category = "其他",
                account = from.name, accountId = fromId, assetLinked = true,
                assetKey = "$key-fee", feeParentKey = key, note = note.takeIf(String::isNotBlank),
            )
            val linkedFee = AssetStore.syncBill(db, previousFee, fee, forceLink = true)!!
            if (previousFee == null) dao.insert(linkedFee) else check(dao.update(linkedFee) == 1)
        }
        return principal
    }

    /** Principal followed by its fee: deletion and each independent undo are atomic. */
    internal suspend fun deleteGroupInTransaction(db: AppDb, id: Long): List<BillEntity> {
        val dao = db.bills()
        val principal = dao.byId(id) ?: return emptyList()
        require(principal.feeParentKey == null) { "手续费请从原转账一同删除" }
        val rows = listOf(principal) + if (principal.assetKey.isNotBlank())
            dao.allOnce().filter { it.feeParentKey == principal.assetKey } else emptyList()
        rows.forEach { row -> AssetStore.syncBill(db, row, null); dao.delete(row.id) }
        return rows
    }

    internal suspend fun restoreGroupInTransaction(db: AppDb, rows: List<BillEntity>) {
        val dao = db.bills()
        // Check all slots before generating postings, then rely on the outer transaction for races.
        rows.forEach { check(dao.byId(it.id) == null) { "记录编号已占用，未覆盖现有账单" } }
        rows.forEach { row ->
            val linked = AssetStore.syncBill(db, null, row)!!
            check(dao.restoreDeleted(linked) != -1L) { "记录编号已占用，未覆盖现有账单" }
        }
    }

    fun toDraft(entity: BillEntity): BillDraft = BillDraft(
        priceCents = entity.priceCents,
        type = runCatching { BillType.valueOf(entity.type) }.getOrDefault(BillType.EXPENSE),
        timeMillis = entity.timeMillis,
        merchant = entity.merchant,
        method = entity.method,
        orderTail = entity.orderTail,
        sourcePkg = entity.sourcePkg,
        strategy = entity.strategy,
        category = entity.category,
        subCategory = entity.subCategory,
        account = entity.account,
        note = entity.note, productName = entity.productName, productSource = entity.productSource,
        noteOrigin = entity.noteOrigin, noteTemplateSnapshot = entity.noteTemplateSnapshot, transferAccountId = entity.transferAccountId,
    )
}

/** Ledger time is editable; capture/import identity and actual creation time remain intact. */
internal fun applyBillEdit(original: BillEntity, edited: BillDraft): BillEntity {
    val updated = original.copy(
        priceCents = edited.priceCents, type = edited.type.name, timeMillis = edited.timeMillis, merchant = original.merchant,
        category = edited.category, subCategory = edited.subCategory, account = edited.account,
        note = edited.note, noteOrigin = edited.noteOrigin,
        accountId = original.accountId.takeIf { original.account == edited.account },
    )
    return updated.copy(fingerprint = if (original.sourcePkg.startsWith("com.example.autobook.manual.") && original.recognitionId == null && !original.fingerprint.startsWith("qianji:")) BillWriter.toDraft(updated).fingerprint else original.fingerprint)
}

internal fun newManualEntity(draft: BillDraft, createdAt: Long): BillEntity {
    val source = draft.sourcePkg.takeIf { it.startsWith("com.example.autobook.manual.") }
        ?: "com.example.autobook.manual.${java.util.UUID.randomUUID()}"
    return BillEntity(
    priceCents = draft.priceCents, type = draft.type.name, timeMillis = draft.timeMillis,
    merchant = draft.merchant, method = null, orderTail = null,
    sourcePkg = source, strategy = "手动记账",
    fingerprint = draft.copy(sourcePkg = source, orderTail = null).fingerprint,
    createdAt = createdAt, category = draft.category, subCategory = draft.subCategory,
    account = draft.account, note = draft.note, productName = draft.productName, productSource = draft.productSource,
    noteOrigin = draft.noteOrigin, noteTemplateSnapshot = draft.noteTemplateSnapshot,
)

}
