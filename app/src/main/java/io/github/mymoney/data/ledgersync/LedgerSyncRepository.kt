package io.github.mymoney.data.ledgersync

import android.content.Context
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.AssetAccount
import io.github.mymoney.data.AssetEntry
import io.github.mymoney.data.AssetStore
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.Reimburse
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
        val payload: Map<String, Any?> = if (deleted) linkedMapOf("syncId" to syncId) else LedgerPayloadCodec.encodeBill(bill)
        enqueueItem(context, "bill:$syncId", LedgerItemKind.BILL, operation, payload, bill.updatedAt)
    }

    suspend fun enqueueAccount(context: Context, account: AssetAccount, deleted: Boolean = false) {
        if (account.id.isBlank()) return
        enqueueItem(
            context, "account:${account.id}", LedgerItemKind.ACCOUNT,
            if (deleted) "delete" else "put",
            if (deleted) linkedMapOf("syncId" to account.id) else LedgerPayloadCodec.encodeAccount(account),
            account.updatedAt,
        )
    }

    suspend fun enqueueEntry(context: Context, entry: AssetEntry, deleted: Boolean = false) {
        if (entry.id.isBlank()) return
        enqueueItem(
            context, "entry:${entry.id}", LedgerItemKind.ENTRY,
            if (deleted) "delete" else "put",
            if (deleted) linkedMapOf("syncId" to entry.id) else LedgerPayloadCodec.encodeEntry(entry),
            entry.updatedAt,
        )
    }

    /** 记账周期起始日 + 支出/收入分类树，整体作为一个 meta:config 条目。 */
    suspend fun enqueueMeta(context: Context) {
        io.github.mymoney.data.LedgerConfig.init(context)
        val payload = LedgerPayloadCodec.encodeMeta(
            monthStartDay = io.github.mymoney.data.LedgerPeriodStore.read(context),
            expenseGroups = io.github.mymoney.data.LedgerConfig.expenseGroups,
            incomeGroups = io.github.mymoney.data.LedgerConfig.incomeGroups,
            updatedAt = System.currentTimeMillis(),
        )
        enqueueItem(
            context, "meta:config", LedgerItemKind.META, "put", payload,
            (payload["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        )
    }

    private suspend fun enqueueItem(
        context: Context,
        itemId: String,
        itemKind: String,
        operation: String,
        payload: Map<String, Any?>,
        contentUpdatedAt: Long,
    ) {
        val request = linkedMapOf<String, Any?>(
            "itemId" to itemId,
            "itemKind" to itemKind,
            "operation" to operation,
            "schemaVersion" to 1,
            "contentUpdatedAt" to contentUpdatedAt,
            "payload" to payload,
        )
        ledgerDb(context).outbox().replaceForItem(
            itemId,
            LedgerOutboxEntity(
                operationId = UUID.randomUUID().toString(),
                itemId = itemId,
                itemKind = itemKind,
                baseRevision = currentRevision(itemId),
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

    // ---------------- per-item revision 记录（itemId 维度） ----------------

    private val revisionCache = HashMap<String, Long>()

    private fun currentRevision(itemId: String): Long = revisionCache[itemId] ?: 0L

    fun noteRevision(itemId: String, revision: Long) {
        revisionCache[itemId] = revision
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
        val changed = current.spaceId != spaceId || current.epoch != epoch || current.ledgerId != ledgerId
        if (changed) {
            // 只有「换同步空间或 epoch」才丢弃旧 outbox/conflicts；仅 ledgerId 变化
            // （旧版随机 ledgerId → 云备份 deviceId 的升级迁移）不清空，已有待推记录
            // 继续按新 ledgerId 推送，避免升级时丢掉尚未上传的实时增量。
            val switchedSpace = current.spaceId != null && current.spaceId.isNotBlank() &&
                (current.spaceId != spaceId || current.epoch != epoch)
            if (switchedSpace) {
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
                reimburseStatus = bill.reimburseStatus, reimbursedAt = bill.reimbursedAt,
                reimburseAccountId = bill.reimburseAccountId,
            )
        } else {
            // 新条目：插入时保留其 syncId，id 由本机自增。deletedAt 的远端墓碑若本机没有对应账单则忽略。
            if (bill.deletedAt == null) {
                dao.insert(bill)
            }
        }
        val saved = dao.bySyncId(bill.syncId)
        if (saved != null) {
            val basis = if (saved.deletedAt != null) saved.copy(reimburseStatus = Reimburse.NONE) else saved
            AssetStore.syncReimbursement(appDb(context), basis)
        }
        noteRevision(remote.itemId, remote.revision)
    }

    suspend fun localBillSyncIds(context: Context): Set<String> =
        localBills(context).map { it.syncId }.filter { it.isNotBlank() }.toSet()

    /** 备份恢复/整包替换后，把本机全要素（账单/账户/流水/配置）重新入队，保证新数据能同步上去。 */
    suspend fun syncAllLocal(context: Context) {
        AssetStore.resetLedgerBaseline()
        localBills(context).forEach { bill ->
            if (bill.syncId.isNotBlank()) runCatching { enqueueBill(context, bill, deleted = false) }
        }
        runCatching { enqueueAllAssets(context) }
        runCatching { enqueueMeta(context) }
    }

    /** 把当前本机全部资产账户与流水入队（恢复/导入后使用；日常走增量 diff）。 */
    suspend fun enqueueAllAssets(context: Context) {
        val db = appDb(context)
        db.assets().accounts().forEach { account -> runCatching { enqueueAccount(context, account) } }
        db.assets().entries().forEach { entry -> runCatching { enqueueEntry(context, entry) } }
    }

    /**
     * 增量 diff 入队：只推本进程观察到发生变化的账户/流水。
     * 已删除（物理删除）的账户/流水写删除墓碑。
     */
    suspend fun enqueueAssetChanges(
        context: Context,
        changedAccounts: List<AssetAccount>,
        removedAccountIds: Collection<String>,
        changedEntries: List<AssetEntry>,
        removedEntryIds: Collection<String>,
    ): Int {
        var count = 0
        changedAccounts.forEach { account ->
            runCatching { enqueueAccount(context, account) }.onSuccess { count++ }
        }
        removedAccountIds.forEach { id ->
            val tomb = AssetAccount(id = id, name = "", createdAt = 0, updatedAt = System.currentTimeMillis())
            runCatching { enqueueAccount(context, tomb, deleted = true) }.onSuccess { count++ }
        }
        changedEntries.forEach { entry ->
            runCatching { enqueueEntry(context, entry) }.onSuccess { count++ }
        }
        removedEntryIds.forEach { id ->
            val tomb = AssetEntry(
                id = id, operationId = "", accountId = "", deltaCents = 0, kind = "REVERSAL",
                note = "", createdAt = 0, updatedAt = System.currentTimeMillis(),
            )
            runCatching { enqueueEntry(context, tomb, deleted = true) }.onSuccess { count++ }
        }
        return count
    }

    /** 手动全量备份成功后：清掉已被快照覆盖的待推项。 */
    suspend fun clearCoveredOutbox(context: Context, cutoffMillis: Long) {
        ledgerDb(context).outbox().deleteCreatedBefore(cutoffMillis)
    }
}
