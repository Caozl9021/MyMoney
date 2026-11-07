package io.github.mymoney.data.ledgersync

import android.content.Context
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.MiniJson
import java.util.UUID

/**
 * W2 账本同步的本地仓库：outbox 入队、读取待推送、应用远端、冲突存取、状态游标。
 *
 * 关键语义：
 * - 入队发生在主库业务写入**之后**（[enqueueBill] 等），跨库无法共用同一事务；因此 outbox
 *   只是前台快路径，真正收敛靠 [LedgerSyncCoordinator] 的 snapshot 差量兜底，单条入队丢失不丢数据。
 * - 应用远端用 syncId 定位（[applyRemoteBill]），本机自增 id 始终不变。
 */
object LedgerSyncRepository {

    private fun ledgerDb(context: Context) = LedgerSyncDb.get(context)
    private fun appDb(context: Context): AppDb = AppDb.get(context)

    // ---------------- outbox ----------------

    suspend fun enqueue(context: Context, entity: LedgerOutboxEntity) {
        ledgerDb(context).outbox().upsert(entity)
    }

    /** 在业务写入后调用：把一张账单的变化放入 outbox（幂等，按 itemId 覆盖旧待推送）。 */
    suspend fun enqueueBill(context: Context, bill: BillEntity, deleted: Boolean) {
        val syncId = bill.syncId
        if (syncId.isBlank()) return
        val operation = if (deleted) "delete" else "put"
        val payload = if (deleted) emptyMap<String, Any?>() else LedgerPayloadCodec.encodeBill(bill)
        val baseRevision = currentRevision(context, syncId)
        val request = linkedMapOf<String, Any?>(
            "itemId" to "bill:$syncId",
            "itemKind" to LedgerItemKind.BILL,
            "operation" to operation,
            "schemaVersion" to 1,
            "contentUpdatedAt" to bill.updatedAt,
            "payload" to payload,
        )
        ledgerDb(context).outbox().upsert(
            LedgerOutboxEntity(
                operationId = UUID.randomUUID().toString(),
                itemId = "bill:$syncId",
                itemKind = LedgerItemKind.BILL,
                baseRevision = baseRevision,
                requestJson = MiniJson.encode(request),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun pendingOutbox(context: Context): List<LedgerOutboxEntity> =
        ledgerDb(context).outbox().byStatus(LedgerOutboxEntity.STATUS_PENDING)

    suspend fun pendingCount(context: Context): Int =
        ledgerDb(context).outbox().count(LedgerOutboxEntity.STATUS_PENDING)

    suspend fun markSynced(context: Context, operationId: String) {
        ledgerDb(context).outbox().delete(operationId)
    }

    suspend fun markFailed(context: Context, operationId: String, error: String) {
        ledgerDb(context).outbox().updateStatus(operationId, LedgerOutboxEntity.STATUS_FAILED, error.take(500))
    }

    suspend fun clearOutboxForItem(context: Context, itemId: String) {
        ledgerDb(context).outbox().deleteByItem(itemId)
    }

    suspend fun pendingForItem(context: Context, itemId: String): Boolean =
        ledgerDb(context).outbox().byItem(itemId) != null

    // ---------------- per-item revision 记录（保存在 state 表外：用 outbox base 即可，
    // 这里用一张内存映射不合适；改为从本地 ledger_revision 推断。）
    // 简化：每条本地写入时按已知远端 revision 递增；未知时用 0，由 snapshot 差量纠正。

    private val revisionCache = HashMap<String, Long>()

    private fun currentRevision(context: Context, syncId: String): Long = revisionCache[syncId] ?: 0L

    fun noteRevision(syncId: String, revision: Long) {
        revisionCache[syncId] = revision
    }

    // ---------------- 冲突 ----------------

    suspend fun saveConflict(context: Context, conflict: LedgerConflictEntity) {
        ledgerDb(context).conflicts().upsert(conflict)
    }

    suspend fun conflicts(context: Context): List<LedgerConflictEntity> = ledgerDb(context).conflicts().all()

    suspend fun conflictCount(context: Context): Int = ledgerDb(context).conflicts().count()

    suspend fun clearConflict(context: Context, itemId: String) {
        ledgerDb(context).conflicts().delete(itemId)
    }

    // ---------------- 状态 ----------------

    suspend fun state(context: Context): LedgerSyncStateEntity =
        ledgerDb(context).state().get() ?: LedgerSyncStateEntity()

    suspend fun saveState(context: Context, state: LedgerSyncStateEntity) {
        ledgerDb(context).state().upsert(state)
    }

    suspend fun setCursor(context: Context, cursor: Long, lastPulledRevision: Long, lastSyncAt: Long) {
        ledgerDb(context).state().updateCursor(cursor, lastPulledRevision, lastSyncAt)
    }

    suspend fun setInitialMergeDone(context: Context, done: Boolean) {
        ledgerDb(context).state().setInitialMergeDone(done)
    }

    /** 空间/epoch 变化时重置本地同步状态与 outbox（换家庭服务/重配对场景）。 */
    suspend fun bindSpace(context: Context, spaceId: String, ledgerId: String, epoch: String) {
        val current = state(context)
        if (current.spaceId != spaceId || current.epoch != epoch || current.ledgerId != ledgerId) {
            // 只有「之前已经绑定过别的 space/epoch/ledger」（换家庭/换账本）才丢弃旧 outbox/conflicts。
            // 首次绑定（spaceId 为空）不清空：配对前先记的账已在 outbox 里，正是首次要上传的内容，
            // 清掉会导致第一笔账单在首同步时丢失（真机踩到的 bug）。
            if (current.spaceId != null && current.spaceId.isNotBlank()) {
                ledgerDb(context).outbox().clearAll()
                ledgerDb(context).conflicts().clearAll()
                revisionCache.clear()
            }
            saveState(
                context,
                LedgerSyncStateEntity(spaceId = spaceId, ledgerId = ledgerId, epoch = epoch, cursor = 0, lastPulledRevision = 0, initialMergeDone = false),
            )
        }
    }

    // ---------------- 本机数据快照（供差量比对） ----------------

    suspend fun localBills(context: Context): List<BillEntity> = appDb(context).bills().listAllForSync()

    // ---------------- 应用远端 ----------------

    /** 应用一条远端账单：按 syncId 找本机记录；存在则整条覆盖，不存在则插入（新 syncId）。 */
    suspend fun applyRemoteBill(context: Context, remote: LedgerRemoteItem) {
        val payload = LedgerPayloadCodec.decodeBillPayload(remote.payloadJson)
        val bill = LedgerPayloadCodec.decodeBill(payload, remote.contentUpdatedAt ?: 0L)
        val dao = appDb(context).bills()
        val existing = dao.bySyncId(bill.syncId)
        if (existing != null) {
            dao.applyRemoteBySyncId(
                syncId = bill.syncId, type = bill.type, priceCents = bill.priceCents, timeMillis = bill.timeMillis,
                merchant = bill.merchant, method = bill.method, orderTail = bill.orderTail, sourcePkg = bill.sourcePkg,
                strategy = bill.strategy, fingerprint = bill.fingerprint, createdAt = bill.createdAt, category = bill.category,
                subCategory = bill.subCategory, account = bill.account, note = bill.note, recognitionId = bill.recognitionId,
                accountId = bill.accountId, transferAccountId = bill.transferAccountId, assetLinked = bill.assetLinked,
                assetKey = bill.assetKey, transferFeeCents = bill.transferFeeCents, feeParentKey = bill.feeParentKey,
                transferAccount = bill.transferAccount, productName = bill.productName, productSource = bill.productSource,
                noteOrigin = bill.noteOrigin, noteTemplateSnapshot = bill.noteTemplateSnapshot,
                updatedAt = bill.updatedAt, deletedAt = bill.deletedAt,
            )
        } else {
            // 新条目：插入时保留其 syncId，id 由本机自增。deletedAt 的远端墓碑若本机没有对应账单则忽略。
            if (bill.deletedAt == null) {
                dao.insert(bill)
            }
        }
        noteRevision(bill.syncId, remote.revision)
    }

    suspend fun localBillSyncIds(context: Context): Set<String> =
        localBills(context).map { it.syncId }.filter { it.isNotBlank() }.toSet()

    /** 备份恢复/整包替换后，把本机全部账单重新入队，保证新数据能同步上去。 */
    suspend fun syncAllLocal(context: Context) {
        localBills(context).forEach { bill ->
            if (bill.syncId.isNotBlank()) runCatching { enqueueBill(context, bill, deleted = false) }
        }
    }
}
