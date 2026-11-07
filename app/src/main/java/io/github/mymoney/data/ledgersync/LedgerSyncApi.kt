package io.github.mymoney.data.ledgersync

import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.rulesync.DirectHttpTransport
import io.github.mymoney.data.rulesync.FamilyTransport
import io.github.mymoney.data.rulesync.RuleSyncApiException
import java.io.IOException
import java.net.URL

/**
 * W2 账本副本同步的客户端协议（v1/ledger 系列端点）。走与规则同步同一条 FamilyTransport
 * 与同一套地址策略（RuleSyncApi.isAllowedServiceUrl）。与本机自增 id 无关：
 * itemId 一律使用稳定 syncId。
 */
data class LedgerRemoteItem(
    val itemId: String,
    val itemKind: String,
    val schemaVersion: Int,
    val payloadJson: String,
    val revision: Long,
    val contentUpdatedAt: Long?,
    val deletedAt: String?,
)

data class LedgerSnapshotPage(val items: List<LedgerRemoteItem>, val serverRevision: Long)

data class LedgerPushResult(
    val status: String,
    val operationId: String,
    val itemId: String,
    val revision: Long?,
    val remoteRevision: Long?,
    val remote: LedgerRemoteItem?,
)

data class LedgerChange(
    val revision: Long,
    val itemId: String,
    val operation: String,
    val snapshot: LedgerRemoteItem,
    val deviceId: String,
)

data class LedgerPullPage(
    val changes: List<LedgerChange>,
    val nextCursor: Long,
    val hasMore: Boolean,
    val serverRevision: Long,
)

data class LedgerImportSnapshot(
    val importSnapshotId: String,
    val sourceLedgerId: String,
    val sourceRevision: Long,
    val items: List<LedgerRemoteItem>,
)

data class LedgerImportSource(
    val deviceId: String,
    val deviceName: String,
    val ledgerId: String,
    val serverRevision: Long,
    val itemCount: Int,
)

object LedgerSyncApi {
    private const val MAX_BODY = 512 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    fun snapshot(serverUrl: String, token: String, ledgerId: String, transport: FamilyTransport = DirectHttpTransport()): LedgerSnapshotPage {
        val json = request(serverUrl, "GET", "/v1/ledger/snapshot?ledgerId=$ledgerId", null, token, transport)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("账本快照格式无效")
        return LedgerSnapshotPage(
            items = items(map["items"]),
            serverRevision = number(map["serverRevision"]).toLong(),
        )
    }

    fun push(
        serverUrl: String, token: String, spaceId: String, ledgerId: String, epoch: String,
        items: List<Map<String, Any?>>, transport: FamilyTransport = DirectHttpTransport(),
    ): List<LedgerPushResult> {
        if (items.isEmpty()) return emptyList()
        val json = request(serverUrl, "POST", "/v1/ledger/push", mapOf(
            "protocolVersion" to 1,
            "spaceId" to spaceId,
            "ledgerId" to ledgerId,
            "epoch" to epoch,
            "items" to items,
        ), token, transport)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("账本同步响应格式无效")
        val raw = map["items"] as? List<*> ?: throw RuleSyncApiException("账本同步结果缺少 items")
        return raw.map { row ->
            val m = row as? Map<*, *> ?: throw RuleSyncApiException("账本同步结果格式无效")
            LedgerPushResult(
                status = text(m, "status"),
                operationId = text(m, "operationId"),
                itemId = text(m, "itemId"),
                revision = (m["revision"] as? Number)?.toLong(),
                remoteRevision = (m["remoteRevision"] as? Number)?.toLong(),
                remote = (m["remote"] as? Map<*, *>)?.let { remoteItem(it) },
            )
        }
    }

    fun pull(serverUrl: String, token: String, ledgerId: String, after: Long, limit: Int = 100, transport: FamilyTransport = DirectHttpTransport()): LedgerPullPage {
        val json = request(serverUrl, "GET", "/v1/ledger/pull?ledgerId=$ledgerId&after=$after&limit=$limit", null, token, transport)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("账本拉取格式无效")
        val raw = map["changes"] as? List<*> ?: emptyList<Any?>()
        return LedgerPullPage(
            changes = raw.map { value ->
                val row = value as? Map<*, *> ?: throw RuleSyncApiException("账本变更格式无效")
                LedgerChange(
                    revision = number(row["revision"]).toLong(),
                    itemId = text(row, "itemId"),
                    operation = text(row, "operation"),
                    snapshot = remoteItem(row["snapshot"] as? Map<*, *> ?: throw RuleSyncApiException("账本快照格式无效")),
                    deviceId = text(row, "deviceId"),
                )
            },
            nextCursor = number(map["nextCursor"]).toLong(),
            hasMore = map["hasMore"] as? Boolean ?: false,
            serverRevision = number(map["serverRevision"]).toLong(),
        )
    }

    fun ack(serverUrl: String, token: String, ledgerId: String, throughRevision: Long, epoch: String, transport: FamilyTransport = DirectHttpTransport()) {
        request(serverUrl, "POST", "/v1/ledger/ack?ledgerId=$ledgerId", mapOf("throughRevision" to throughRevision, "epoch" to epoch), token, transport)
    }

    /**
     * W3：列出同空间可导入的来源设备（排除自己/已撤销）。
     */
    fun importSources(serverUrl: String, token: String, transport: FamilyTransport = DirectHttpTransport()): List<LedgerImportSource> {
        val json = request(serverUrl, "GET", "/v1/ledger/import/sources", null, token, transport)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("导入来源列表格式无效")
        val raw = map["sources"] as? List<*> ?: emptyList<Any?>()
        return raw.map { value ->
            val m = value as? Map<*, *> ?: throw RuleSyncApiException("导入来源条目格式无效")
            LedgerImportSource(
                deviceId = text(m, "deviceId"),
                deviceName = text(m, "deviceName"),
                ledgerId = text(m, "ledgerId"),
                serverRevision = number(m["serverRevision"]).toLong(),
                itemCount = (m["itemCount"] as? Number)?.toInt() ?: 0,
            )
        }
    }

    /**
     * W3：读取同空间另一设备（来源设备/来源账本）的冻结来源快照。只读，服务端登记导入快照元数据。
     * 使用与账本同步相同的令牌；服务端按 space + 来源设备校验，防越权读其他空间。
     */
    fun importSource(
        serverUrl: String, token: String, sourceDeviceId: String, sourceLedgerId: String,
        transport: FamilyTransport = DirectHttpTransport(),
    ): LedgerImportSnapshot {
        val json = request(
            serverUrl, "GET",
            "/v1/ledger/import/source?sourceDeviceId=$sourceDeviceId&sourceLedgerId=$sourceLedgerId",
            null, token, transport,
        )
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("导入来源快照格式无效")
        return LedgerImportSnapshot(
            importSnapshotId = text(map, "importSnapshotId"),
            sourceLedgerId = text(map, "sourceLedgerId"),
            sourceRevision = number(map["sourceRevision"]).toLong(),
            items = items(map["items"]),
        )
    }

    private fun remoteItem(row: Map<*, *>): LedgerRemoteItem = LedgerRemoteItem(
        itemId = text(row, "itemId"),
        itemKind = text(row, "itemKind"),
        schemaVersion = (row["schemaVersion"] as? Number)?.toInt() ?: 1,
        payloadJson = MiniJson.encode(row["payload"] ?: emptyMap<String, Any?>()),
        revision = number(row["revision"]).toLong(),
        contentUpdatedAt = (row["contentUpdatedAt"] as? Number)?.toLong(),
        deletedAt = row["deletedAt"] as? String,
    )

    private fun items(value: Any?): List<LedgerRemoteItem> =
        (value as? List<*> ?: emptyList<Any?>()).map { row ->
            remoteItem(row as? Map<*, *> ?: throw RuleSyncApiException("账本快照条目格式无效"))
        }

    private fun request(
        serverUrl: String, method: String, path: String, body: Any?, token: String,
        transport: FamilyTransport,
    ): Any? {
        val base = serverUrl.trim().trimEnd('/')
        val url = URL(base + path)
        if (!io.github.mymoney.data.rulesync.RuleSyncApi.isAllowedServiceUrl(url)) {
            throw RuleSyncApiException("家庭同步地址须使用 HTTPS、家庭私网 HTTP 或 http://<家庭服务地址>（不能含账号密码或片段）")
        }
        val payload = body?.let { MiniJson.encode(it) }?.toByteArray(Charsets.UTF_8)
        if (payload != null && payload.size > MAX_BODY) throw RuleSyncApiException("账本同步请求超过大小限制")
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (payload != null) headers["Content-Type"] = "application/json; charset=utf-8"
        headers["Authorization"] = "Bearer $token"
        val bodySpec = payload?.let { FamilyTransport.RequestBody.Bytes(it) }
        val response = try {
            transport.execute(method, url.toString(), headers, bodySpec, CONNECT_TIMEOUT, READ_TIMEOUT)
        } catch (e: RuleSyncApiException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw RuleSyncApiException("账本同步网络请求失败")
        }
        try {
            val code = response.status
            val text = (if (code in 200..299) response.bodyStream() else response.errorStream())
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching {
                    val bodyMap = MiniJson.decode(text) as? Map<*, *>
                    (bodyMap?.get("detail") as? String) ?: (bodyMap?.get("error") as? String)
                }.getOrNull() ?: when (code) {
                    in 300..399 -> "家庭服务发生重定向，请检查连接地址"
                    401, 403 -> "设备连接已失效，请重新配对"
                    409 -> "家庭账本已被重置，请重新对账"
                    413 -> "账本内容超过大小限制"
                    else -> "家庭服务暂不可用（$code）"
                }
                throw RuleSyncApiException(message, code)
            }
            return if (text.isBlank()) emptyMap<String, Any?>() else MiniJson.decode(text)
        } finally {
            response.close()
        }
    }

    private fun text(map: Map<*, *>, key: String): String = map[key] as? String ?: ""
    private fun number(value: Any?): Number = value as? Number ?: 0
}
