package io.github.mymoney.data.ledgersync

import android.content.Context
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.rulesync.FamilyTransport
import io.github.mymoney.data.rulesync.FamilyTransportProvider
import io.github.mymoney.data.rulesync.RuleSyncApiException
import io.github.mymoney.data.rulesync.RuleSyncConfig
import io.github.mymoney.data.rulesync.RuleSyncSecrets
import io.github.mymoney.data.rulesync.RuleSyncSettings
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LedgerSyncUiState(
    val busy: Boolean = false,
    val message: String = "尚未连接家庭服务",
    val pending: Int = 0,
    val conflicts: Int = 0,
    val lastSyncAt: Long? = null,
    val initialMergeDone: Boolean = true,
)

/**
 * W2 本机账本副本同步协调器。只做「本机账本 ↔ 家庭服务里本机副本」的同步：
 * 不写别的设备账本（那需要 W3 主动导入）。前台触发（按钮/连接后自动），后台任务属 M5。
 */
object LedgerSyncCoordinator {
    /** 单次 push 请求的条目上限（与服务端 max_push_items 一致）。 */
    private const val PUSH_BATCH = 50

    /** 一次 syncNow 最多推多少批，防止异常情况下无限循环（50 × 200 = 10000 条）。 */
    private const val MAX_PUSH_ROUNDS = 200

    private val mutex = Mutex()
    private val _state = MutableStateFlow(LedgerSyncUiState())
    val state: StateFlow<LedgerSyncUiState> = _state.asStateFlow()

    private fun transport(context: Context, config: RuleSyncConfig): FamilyTransport =
        FamilyTransportProvider.select(context, config)

    suspend fun refreshStatus(context: Context) {
        val pending = runCatching { LedgerSyncRepository.pendingCount(context) }.getOrDefault(0)
        val conflicts = runCatching { LedgerSyncRepository.conflictCount(context) }.getOrDefault(0)
        val st = runCatching { LedgerSyncRepository.state(context) }.getOrNull()
        _state.value = _state.value.copy(
            pending = pending,
            conflicts = conflicts,
            lastSyncAt = st?.lastSyncAt,
            initialMergeDone = st?.initialMergeDone ?: true,
        )
    }

    /**
     * 连接后首次合并：先做 snapshot 差量（远端有、本机没有 → 拉下来；本机有、远端没有 → 入队待推），
     * 再跑一次 [syncNow]。用户确认后才能调。返回预览供 UI 展示。
     */
    suspend fun prepareInitialMerge(context: Context) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = "正在准备首次合并…")
        try {
            val config = RuleSyncSettings.load(context)
            val token = RuleSyncSecrets.loadToken(context) ?: throw IOException("设备令牌不存在，请重新配对")
            if (!config.paired) throw IOException("请先连接家庭服务")
            val ledgerId = LedgerSyncSettings.ledgerId(context)
            LedgerSyncRepository.bindSpace(context, config.spaceId, ledgerId, config.epoch)
            val snapshot = LedgerSyncApi.snapshot(config.serverUrl, token, ledgerId, transport(context, config))
            // 远端已存在的 syncId 记下 revision；本机没有的远端条目先拉下来
            val remoteIds = snapshot.items.map { it.itemId }.toSet()
            for (item in snapshot.items) {
                if (item.itemKind == LedgerItemKind.BILL) {
                    LedgerSyncRepository.applyRemoteBill(context, item)
                    LedgerSyncRepository.noteRevision(item.itemId.removePrefix("bill:"), item.revision)
                }
            }
            // 本机有、远端没有的账单入队（首次上传）
            val localBills = LedgerSyncRepository.localBills(context)
            for (bill in localBills) {
                if (bill.syncId.isNotBlank() && "bill:${bill.syncId}" !in remoteIds) {
                    LedgerSyncRepository.enqueueBill(context, bill, deleted = bill.deletedAt != null)
                }
            }
            val st = LedgerSyncRepository.state(context)
            LedgerSyncRepository.saveState(context, st.copy(cursor = snapshot.serverRevision, lastPulledRevision = snapshot.serverRevision))
            LedgerSyncRepository.setInitialMergeDone(context, true)
            _state.value = _state.value.copy(busy = false, message = "首次合并已准备，$localBills 条本机账单（无则无需上传）", initialMergeDone = true)
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = e.message ?: "首次合并失败")
            throw e
        }
    }

    suspend fun syncNow(context: Context) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = "正在同步账本…")
        try {
            val config = RuleSyncSettings.load(context)
            val token = RuleSyncSecrets.loadToken(context) ?: throw IOException("设备令牌不存在，请重新配对")
            if (!config.paired) throw IOException("请先连接家庭服务")
            val ledgerId = LedgerSyncSettings.ledgerId(context)
            LedgerSyncRepository.bindSpace(context, config.spaceId, ledgerId, config.epoch)
            val transport = transport(context, config)

            // 1) 推送待同步：循环分批推完，直到没有 pending 或不再前进为止。
            //    （首次合并 2310 条要 47 批，不能只推一批就停。）
            var pushedTotal = 0
            var rounds = 0
            while (rounds < MAX_PUSH_ROUNDS) {
                val pending = LedgerSyncRepository.pendingOutbox(context)
                if (pending.isEmpty()) break
                rounds++
                val batchOutbox = pending.take(PUSH_BATCH)
                val batch = batchOutbox.mapNotNull { outbox ->
                    val map = MiniJson.decode(outbox.requestJson) as? Map<*, *> ?: return@mapNotNull null
                    val item = map.entries.associate { it.key.toString() to it.value }.toMutableMap()
                    item["epoch"] = config.epoch
                    item["ledgerId"] = ledgerId
                    item["operationId"] = outbox.operationId
                    item["baseRevision"] = outbox.baseRevision
                    // payload 已是 Map；确认是对象
                    item
                }
                if (batch.isEmpty()) {
                    // 本批全解不开，标记失败避免死循环
                    batchOutbox.forEach { LedgerSyncRepository.markFailed(context, it.operationId, "同步请求损坏") }
                    continue
                }
                _state.value = _state.value.copy(
                    busy = true,
                    message = "正在上传账本… ${pushedTotal}/${pending.size + pushedTotal}",
                )
                val results = LedgerSyncApi.push(config.serverUrl, token, config.spaceId, ledgerId, config.epoch, batch, transport)
                var progressed = 0
                results.forEach { result ->
                    when (result.status) {
                        "ok" -> {
                            LedgerSyncRepository.markSynced(context, result.operationId)
                            if (result.revision != null) LedgerSyncRepository.noteRevision(result.itemId.removePrefix("bill:"), result.revision)
                            progressed++
                        }
                        "conflict" -> {
                            LedgerSyncRepository.markFailed(context, result.operationId, "家庭端已有更新，待处理")
                            val local = batchOutbox.firstOrNull { it.operationId == result.operationId }?.requestJson
                            LedgerSyncRepository.saveConflict(
                                context,
                                LedgerConflictEntity(
                                    itemId = result.itemId,
                                    itemKind = LedgerItemKind.BILL,
                                    localPayload = local.orEmpty(),
                                    remotePayload = result.remote?.payloadJson.orEmpty(),
                                    remoteRevision = result.remoteRevision ?: 0,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                            progressed++
                        }
                        else -> LedgerSyncRepository.markFailed(context, result.operationId, "同步结果未知")
                    }
                }
                pushedTotal += progressed
                // 一批里一条都没推进（全 unknown）：标记失败后退出，避免死循环
                if (progressed == 0) {
                    batchOutbox.forEach { LedgerSyncRepository.markFailed(context, it.operationId, "同步未推进") }
                    break
                }
                runCatching { refreshStatus(context) }
            }

            // 2) 拉取远端变更
            var cursor = LedgerSyncRepository.state(context).cursor
            var page: LedgerPullPage
            do {
                page = LedgerSyncApi.pull(config.serverUrl, token, ledgerId, cursor, transport = transport)
                val conflictIds = LedgerSyncRepository.conflicts(context).map { it.itemId }.toSet()
                page.changes.forEach { change ->
                    val syncId = change.itemId.removePrefix("bill:")
                    if (LedgerSyncRepository.pendingForItem(context, change.itemId) || change.itemId in conflictIds) {
                        // 本机还有未推送或已冲突的同条：不动本机，标冲突
                        LedgerSyncRepository.saveConflict(
                            context,
                            LedgerConflictEntity(
                                itemId = change.itemId,
                                itemKind = LedgerItemKind.BILL,
                                localPayload = "",
                                remotePayload = change.snapshot.payloadJson,
                                remoteRevision = change.revision,
                                remoteDevice = change.deviceId,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        LedgerSyncRepository.applyRemoteBill(context, change.snapshot)
                        LedgerSyncRepository.noteRevision(syncId, change.revision)
                    }
                }
                cursor = page.nextCursor
                LedgerSyncRepository.setCursor(context, cursor, page.nextCursor, System.currentTimeMillis())
                runCatching { LedgerSyncApi.ack(config.serverUrl, token, ledgerId, page.nextCursor, config.epoch, transport) }
            } while (page.hasMore)

            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(busy = false, message = "账本同步完成", lastSyncAt = now)
            refreshStatus(context)
            if (pushedTotal > 0) {
                _state.value = _state.value.copy(message = "账本同步完成，上传 $pushedTotal 条")
            }
        } catch (e: Exception) {
            val message = when (e) {
                is RuleSyncApiException -> e.message ?: "账本同步失败"
                else -> e.message ?: "账本同步失败"
            }
            _state.value = _state.value.copy(busy = false, message = message)
            throw e
        }
    }
}
