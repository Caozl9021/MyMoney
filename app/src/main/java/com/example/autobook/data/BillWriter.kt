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
                val result = writeLock.withLock {
                    val db = AppDb.get(app)
                    db.withTransaction {
                        val dao = db.bills()
                        val history = draft.historyId?.let { id ->
                            db.recognitions().byId(id) ?: error("识别记录不存在，请从历史重新打开")
                        }
                        if (history?.status == RecognitionStatus.RECORDED) {
                            // A deleted association is deliberately not resurrected or guessed by amount.
                            SaveResult(false, history.billId?.let { dao.byId(it) })
                        } else {
                            val since = System.currentTimeMillis() - DEDUPE_WINDOW_MS
                            var existing = if (history != null) dao.byRecognitionId(history.id)
                                ?: dao.legacyDuplicate(draft.fingerprint, draft.sourcePkg, draft.priceCents,
                                    draft.type.name, draft.orderTail, since)
                                else dao.recentDuplicate(draft.fingerprint, draft.sourcePkg, draft.priceCents,
                                    draft.type.name, draft.orderTail, since)
                            if (history != null && existing != null && existing.recognitionId == null) {
                                existing = existing.copy(recognitionId = history.id)
                                dao.update(existing)
                            }
                            val inserted = existing == null
                            val bill = existing ?: BillEntity(priceCents = draft.priceCents, type = draft.type.name,
                                timeMillis = draft.timeMillis, merchant = draft.merchant, method = draft.method,
                                orderTail = draft.orderTail, sourcePkg = draft.sourcePkg, strategy = draft.strategy,
                                fingerprint = draft.fingerprint, createdAt = System.currentTimeMillis(),
                                category = draft.category, subCategory = draft.subCategory,
                                account = draft.account, note = draft.note, recognitionId = history?.id).let {
                                    it.copy(id = dao.insert(it))
                                }
                            history?.let {
                                db.recognitions().update(RecognitionEntity.fromDraft(draft.copy(historyId = it.id), it.identityKey, it.recognizedAt)
                                    .copy(status = RecognitionStatus.RECORDED, billId = bill.id,
                                        updatedAt = System.currentTimeMillis(), shareState = it.shareState))
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
                require(draft.priceCents in 1..100_000_000L) { "金额必须大于0且不超过一百万元" }
                writeLock.withLock {
                    val dao = AppDb.get(app).bills()
                    if (original == null) {
                        dao.insert(newManualEntity(draft, System.currentTimeMillis()))
                    } else {
                        check(dao.update(applyBillEdit(original, draft)) == 1) { "这笔账单已被删除，请重新打开明细" }
                        runCatching { LocalRuleStore.recordConfirmation(toDraft(original), draft, allowFrequency = false) }
                    }
                }
                Unit
            }
            if (result.isSuccess) com.example.autobook.systems.SystemIntegration.refresh(app)
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    data class UndoDeletion(val bill: BillEntity, val expiresAt: Long)
    private val undoItems = MutableStateFlow<List<UndoDeletion>>(emptyList())
    val pendingUndo = undoItems.asStateFlow()

    /** 先真实删除，再在进程内提供5秒撤销；多笔独立过期，无延迟提交丢失风险。 */
    fun deleteUndoably(context: Context, id: Long, onError: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val result = runCatching {
                writeLock.withLock {
                    val db = AppDb.get(app)
                    val deleted = db.withTransaction {
                        db.bills().byId(id)?.also { db.bills().delete(id) }
                    }
                    if (deleted != null) {
                        undoItems.value = undoItems.value + UndoDeletion(deleted, System.currentTimeMillis() + 5_000)
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
                    check(AppDb.get(app).bills().restoreDeleted(item.bill) != -1L) { "记录编号已占用，未覆盖现有账单" }
                    undoItems.value = undoItems.value.filterNot { it.bill.id == id }
                }
            }
            if (result.isSuccess) com.example.autobook.systems.SystemIntegration.refresh(app)
            withContext(Dispatchers.Main) { onDone(result) }
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
        note = entity.note,
    )
}

/** 只应用允许编辑的字段，识别来源、时间和身份保持不变。 */
internal fun applyBillEdit(original: BillEntity, edited: BillDraft): BillEntity {
    val updated = original.copy(
        priceCents = edited.priceCents, type = edited.type.name, merchant = edited.merchant,
        category = edited.category, subCategory = edited.subCategory, account = edited.account,
        note = edited.note,
    )
    return updated.copy(fingerprint = BillWriter.toDraft(updated).fingerprint)
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
    account = draft.account, note = draft.note,
)

}
