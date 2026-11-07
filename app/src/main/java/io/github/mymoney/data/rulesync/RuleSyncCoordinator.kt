package io.github.mymoney.data.rulesync

import android.content.Context
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.ledgersync.LedgerSyncRepository
import io.github.mymoney.data.ledgersync.LedgerSyncSettings
import io.github.mymoney.data.NoteFillStore
import io.github.mymoney.data.SpecRuleStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InitialSyncPreview(
    val localCount: Int,
    val remoteCount: Int,
    val localOnly: Int,
    val remoteOnly: Int,
    val different: Int,
)

data class RuleSyncUiState(
    val busy: Boolean = false,
    val message: String = "尚未连接家庭服务",
    val pending: Int = 0,
    val conflicts: Int = 0,
    val lastSyncAt: Long? = null,
    val initialPreview: InitialSyncPreview? = null,
)

object RuleSyncCoordinator {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RuleSyncUiState())
    val state: StateFlow<RuleSyncUiState> = _state.asStateFlow()

    /** 这次调用走哪条传输：默认直连，配置开了内嵌就走内嵌（不可用时报错，不静默降级）。 */
    private fun transport(context: Context): FamilyTransport =
        FamilyTransportProvider.select(context, RuleSyncSettings.load(context))

    suspend fun refreshStatus() {
        val pending = runCatching { RuleSyncRepository.outboxCount() }.getOrDefault(0)
        val conflicts = runCatching { RuleSyncRepository.conflicts().size }.getOrDefault(0)
        val lastSyncAt = runCatching { RuleSyncRepository.currentState().lastSyncAt }.getOrNull()
        _state.value = _state.value.copy(pending = pending, conflicts = conflicts, lastSyncAt = lastSyncAt)
    }

    /** “打开麦米就能连上”：一次真实探测，走与同步相同的传输与地址策略。 */
    suspend fun testConnection(context: Context) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = "正在测试连接…")
        try {
            val config = RuleSyncSettings.load(context)
            if (config.serverUrl.isBlank()) throw IOException("请先填写家庭服务地址")
            val transport = transport(context)
            val via = if (config.useEmbedded) "内嵌节点" else "系统直连"
            // 内嵌模式第一次拉起节点时，Start 返回只代表本地节点起来了，真正上线还要等控制面
            // 授权和打洞完成；这期间发出的第一个请求必然失败。留一段重试窗口，别让用户第一次
            // 点「测试连接」就吃到失败。真连不上时窗口结束仍会报错，不是无限等。
            val attempts = if (config.useEmbedded) 6 else 1
            var failure: Exception? = null
            for (attempt in 0 until attempts) {
                if (attempt == 1) {
                    _state.value = _state.value.copy(message = "正在等待内嵌节点上线…")
                }
                try {
                    RuleSyncApi.health(config.serverUrl, transport)
                    failure = null
                    break
                } catch (e: Exception) {
                    failure = e
                    if (attempt < attempts - 1) delay(2_500)
                }
            }
            failure?.let { throw it }
            _state.value = _state.value.copy(busy = false, message = "连接正常（$via）")
        } catch (e: Exception) {
            val via = if (RuleSyncSettings.load(context).useEmbedded) "内嵌节点" else "系统直连"
            _state.value = _state.value.copy(busy = false, message = "连接失败（$via）：${e.message ?: "未知错误"}")
        }
    }

    suspend fun pair(context: Context, serverUrl: String, code: String, deviceName: String) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = "正在配对…")
        try {
            // Pair against the address the user is looking at: the field may differ from
            // the stored config, and the peer allowlist must be derived from the same
            // address the request is sent to.
            val base = RuleSyncSettings.load(context)
            val effective = base.copy(serverUrl = serverUrl.trim().trimEnd('/'))
            val transport = FamilyTransportProvider.select(context, effective)
            val response = RuleSyncApi.pair(serverUrl, code, deviceName, transport)
            // Keep the transport choice the user picked: rebuilding the config from
            // scratch used to drop useEmbedded/ctrlUrl, so a successful embedded pairing
            // silently fell back to direct afterwards.
            val previous = base
            RuleSyncSettings.save(
                context,
                RuleSyncConfig(
                    serverUrl = serverUrl,
                    spaceId = response.spaceId,
                    epoch = response.epoch,
                    deviceId = response.deviceId,
                    deviceName = deviceName,
                    initialMergePending = true,
                    useEmbedded = previous.useEmbedded,
                    ctrlUrl = previous.ctrlUrl,
                ),
                token = response.token,
            )
            RuleSyncRepository.bindSpace(response.spaceId, response.epoch, enqueue = false)
            // 账本副本同步也在此刻绑定 space/ledger：让账本 state 在配对成功时就建立，
            // 之后记账→同步不会触发 bindSpace 首次绑定，避免首次同步清空配对前先记的账（首笔账单丢失）。
            runCatching {
                LedgerSyncRepository.bindSpace(
                    context,
                    response.spaceId,
                    LedgerSyncSettings.ledgerId(context),
                    response.epoch,
                )
            }
            val local = RuleSyncRepository.activeItems()
            val remote = RuleSyncApi.snapshot(serverUrl, response.token, transport(context)).items.filter { it.deletedAt == null }
            val localById = local.associateBy { it.id }
            val remoteById = remote.associateBy { it.itemId }
            val localIds = localById.keys
            val remoteIds = remoteById.keys
            val different = localIds.intersect(remoteIds).count { id ->
                val localPayload = localById[id]?.payload
                val remotePayload = remoteById[id]?.payload
                localPayload != null && remotePayload != null &&
                    !RuleSyncCodec.samePayload(localPayload, remotePayload)
            }
            val preview = InitialSyncPreview(
                localCount = local.size,
                remoteCount = remote.size,
                localOnly = localIds.count { it !in remoteIds },
                remoteOnly = remoteIds.count { it !in localIds },
                different = different,
            )
            _state.value = _state.value.copy(
                busy = false,
                message = "配对成功，请确认首次合并",
                initialPreview = preview,
            )
            refreshStatus()
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = e.message ?: "配对失败")
            throw e
        }
    }

    suspend fun clearInitialPreview() {
        _state.value = _state.value.copy(initialPreview = null)
    }

    /** Confirms first merge without re-uploading local items that already match the family snapshot. */
    suspend fun confirmInitialSync(context: Context) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = "正在准备首次合并…")
        try {
            val config = RuleSyncSettings.load(context)
            val token = RuleSyncSecrets.loadToken(context) ?: throw IOException("设备令牌不存在，请重新配对")
            val remote = RuleSyncApi.snapshot(config.serverUrl, token, transport(context)).items.filter { it.deletedAt == null }
            val skip = RuleSyncRepository.prepareInitialMerge(remote)
            val queued = RuleSyncRepository.enqueueAllForInitialSync(skip)
            clearInitialPreview()
            RuleSyncSettings.setInitialMergePending(context, false)
            _state.value = _state.value.copy(busy = false, message = "首次合并已准备，${queued} 条本地规则待上传")
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = e.message ?: "首次合并失败")
            throw e
        }
    }

    suspend fun cancelInitialSync(context: Context) = mutex.withLock {
        try {
            disconnectWithoutLock(context)
            RuleSyncRepository.unbindSpace()
            _state.value = RuleSyncUiState(message = "已取消首次同步，本地规则未修改")
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = "取消连接失败：${e.message}")
        }
    }

    private suspend fun disconnectWithoutLock(context: Context) {
        val config = RuleSyncSettings.load(context)
        val token = RuleSyncSecrets.loadToken(context)
        if (config.paired && token != null) {
            runCatching { RuleSyncApi.revoke(config.serverUrl, token, transport(context)) }
        }
        TsnetSession.shutdown()
        PendingInvite.clear()
        RuleSyncSettings.clear(context)
    }

    suspend fun syncNow(context: Context) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = "正在同步…")
        try {
            val config = RuleSyncSettings.load(context)
            val token = RuleSyncSecrets.loadToken(context) ?: throw IOException("设备令牌不存在，请重新配对")
            if (!config.paired) throw IOException("请先连接家庭服务")
            runCatching { RuleSyncRepository.repairIdenticalConflicts() }
            val state = RuleSyncRepository.currentState()
            if (state.spaceId != config.spaceId || state.epoch != config.epoch) {
                RuleSyncRepository.bindSpace(config.spaceId, config.epoch)
            }

            val pending = RuleSyncRepository.pendingOutbox()
            if (pending.isNotEmpty()) {
                val batch = pending.take(50).mapNotNull { outbox ->
                    val map = MiniJson.decode(outbox.requestJson) as? Map<*, *> ?: return@mapNotNull null
                    val item = map.entries.associate { it.key.toString() to it.value }.toMutableMap()
                    item["epoch"] = config.epoch
                    if (item["payload"] is String) {
                        item["payload"] = runCatching { MiniJson.decode(item["payload"] as String) }.getOrNull()
                    }
                    item["sortKey"] = item["sortKey"]?.toString()
                    item
                }
                val results = RuleSyncApi.push(config.serverUrl, token, config.spaceId, config.epoch, batch, transport(context))
                results.forEach { result ->
                    val operationId = result["operationId"] as? String ?: return@forEach
                    when (result["status"] as? String) {
                        "ok" -> {
                            RuleSyncRepository.markOutboxSynced(operationId)
                            val itemId = result["itemId"] as? String
                            val revision = (result["revision"] as? Number)?.toLong()
                            val snapshot = result["snapshot"] as? Map<*, *>
                            val contentUpdatedAt = (snapshot?.get("contentUpdatedAt") as? Number)?.toLong()
                            if (itemId != null && revision != null) {
                                RuleSyncRepository.updateItemServerRevision(itemId, revision, contentUpdatedAt)
                            }
                        }
                        "conflict" -> {
                            RuleSyncRepository.markOutboxFailed(operationId, "家庭端已有更新，待处理冲突")
                            val itemId = result["itemId"] as? String ?: return@forEach
                            val local = pending.firstOrNull { it.operationId == operationId }
                                ?.requestJson
                                ?.let { runCatching { MiniJson.decode(it) as? Map<*, *> }.getOrNull() }
                                ?.get("payload")
                            RuleSyncRepository.saveConflict(
                                SyncConflictEntity(
                                    itemId = itemId,
                                    localPayload = local?.let { MiniJson.encode(it) }.orEmpty(),
                                    remotePayload = result["remote"]?.let { MiniJson.encode(it) }.orEmpty(),
                                    remoteRevision = (result["remoteRevision"] as? Number)?.toLong() ?: 0,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        else -> RuleSyncRepository.markOutboxFailed(operationId, "同步结果未知")
                    }
                }
            }

            var cursor = RuleSyncRepository.currentState().cursor
            var page: RuleSyncPullPage
            do {
                page = RuleSyncApi.pull(config.serverUrl, token, cursor, transport = transport(context))
                val conflictIds = RuleSyncRepository.conflicts().map { it.itemId }.toSet()
                page.changes.forEach { change ->
                    val local = RuleSyncRepository.localItem(change.itemId)
                    val identical = local != null && local.deletedAt == null && change.snapshot.deletedAt == null &&
                        RuleSyncCodec.samePayload(local.payload, change.snapshot.payload)
                    if (identical) {
                        RuleSyncRepository.clearOutboxForItem(change.itemId)
                        RuleSyncRepository.clearConflict(change.itemId)
                        RuleSyncRepository.updateItemServerRevision(
                            change.itemId,
                            change.snapshot.revision,
                            change.snapshot.contentUpdatedAt,
                        )
                    } else if (RuleSyncRepository.pendingForItem(change.itemId) || change.itemId in conflictIds) {
                        RuleSyncRepository.saveConflict(
                            SyncConflictEntity(
                                itemId = change.itemId,
                                localPayload = "",
                                remotePayload = MiniJson.encode(mapOf(
                                    "itemId" to change.snapshot.itemId,
                                    "kind" to change.snapshot.kind,
                                    "payload" to change.snapshot.payload,
                                )),
                                remoteRevision = change.snapshot.revision,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        RuleSyncRepository.applyRemote(change.snapshot)
                    }
                }
                cursor = page.nextCursor
                RuleSyncRepository.setCursor(cursor, page.nextCursor, System.currentTimeMillis())
                RuleSyncApi.ack(config.serverUrl, token, page.nextCursor, config.epoch, transport(context))
            } while (page.hasMore)

            RuleSyncRepository.reload()
            runCatching { SpecRuleStore.refreshFromRepository(context) }
            NoteFillStore.refreshFromRepository()
            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(busy = false, message = "同步完成", lastSyncAt = now)
            refreshStatus()
        } catch (e: Exception) {
            val message = when (e) {
                is RuleSyncApiException -> e.message ?: "同步失败"
                else -> e.message ?: "同步失败"
            }
            _state.value = _state.value.copy(busy = false, message = message)
            throw e
        }
    }

    suspend fun disconnect(context: Context) = mutex.withLock {
        try {
            disconnectWithoutLock(context)
            RuleSyncRepository.unbindSpace()
            _state.value = RuleSyncUiState(message = "已断开家庭服务，本地规则保留")
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = "断开连接失败：${e.message}")
        }
    }
}
