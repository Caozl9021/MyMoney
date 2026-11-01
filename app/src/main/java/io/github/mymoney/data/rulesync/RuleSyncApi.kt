package io.github.mymoney.data.rulesync

import io.github.mymoney.data.MiniJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class RuleSyncApiException(message: String, val code: Int = 0) : IOException(message)

data class RuleSyncPairResponse(val spaceId: String, val epoch: String, val deviceId: String, val token: String)

data class RuleSyncChange(
    val revision: Long,
    val itemId: String,
    val operation: String,
    val snapshot: RuleSyncRemoteItem,
)

data class RuleSyncPullPage(
    val changes: List<RuleSyncChange>,
    val nextCursor: Long,
    val hasMore: Boolean,
    val serverRevision: Long,
)

data class RuleSyncSnapshotPage(
    val items: List<RuleSyncRemoteItem>,
    val serverRevision: Long,
)

object RuleSyncApi {
    private const val MAX_BODY = 512 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000

    fun pair(serverUrl: String, code: String, deviceName: String): RuleSyncPairResponse {
        val json = request(serverUrl, "POST", "/v1/pair", mapOf("code" to code, "deviceName" to deviceName), null)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("配对响应格式无效")
        return RuleSyncPairResponse(
            spaceId = text(map, "spaceId"),
            epoch = text(map, "epoch"),
            deviceId = text(map, "deviceId"),
            token = text(map, "token"),
        )
    }

    fun snapshot(serverUrl: String, token: String): RuleSyncSnapshotPage {
        val json = request(serverUrl, "GET", "/v1/sync/snapshot", null, token)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("规则快照格式无效")
        return RuleSyncSnapshotPage(
            items = items(map["items"]),
            serverRevision = number(map["serverRevision"]).toLong(),
        )
    }

    fun push(serverUrl: String, token: String, spaceId: String, epoch: String, items: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (items.isEmpty()) return emptyList()
        val json = request(serverUrl, "POST", "/v1/sync/push", mapOf(
            "protocolVersion" to 1,
            "spaceId" to spaceId,
            "epoch" to epoch,
            "items" to items,
        ), token)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("同步响应格式无效")
        val raw = map["items"] as? List<*> ?: throw RuleSyncApiException("同步结果缺少 items")
        return raw.map { row ->
            row as? Map<*, *> ?: throw RuleSyncApiException("同步结果格式无效")
            row.entries.associate { it.key.toString() to it.value }
        }
    }

    fun pull(serverUrl: String, token: String, after: Long, limit: Int = 100): RuleSyncPullPage {
        val json = request(serverUrl, "GET", "/v1/sync/pull?after=$after&limit=$limit", null, token)
        val map = json as? Map<*, *> ?: throw RuleSyncApiException("同步拉取格式无效")
        val raw = map["changes"] as? List<*> ?: emptyList<Any?>()
        return RuleSyncPullPage(
            changes = raw.map { value ->
                val row = value as? Map<*, *> ?: throw RuleSyncApiException("同步变更格式无效")
                RuleSyncChange(
                    revision = number(row["revision"]).toLong(),
                    itemId = text(row, "itemId"),
                    operation = text(row, "operation"),
                    snapshot = remoteItem(row["snapshot"] as? Map<*, *> ?: throw RuleSyncApiException("同步快照格式无效")),
                )
            },
            nextCursor = number(map["nextCursor"]).toLong(),
            hasMore = map["hasMore"] as? Boolean ?: false,
            serverRevision = number(map["serverRevision"]).toLong(),
        )
    }

    fun ack(serverUrl: String, token: String, throughRevision: Long, epoch: String) {
        request(serverUrl, "POST", "/v1/sync/ack", mapOf("throughRevision" to throughRevision, "epoch" to epoch), token)
    }

    fun revoke(serverUrl: String, token: String) {
        request(serverUrl, "POST", "/v1/device/revoke", emptyMap<String, Any?>(), token)
    }

    /** 家庭局域网明文 HTTP 只允许回环或 RFC1918 私网地址；公网地址仍必须 HTTPS。 */
    internal fun isPrivateHttpHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1") return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
        return nums[0] == 10 ||
            (nums[0] == 172 && nums[1] in 16..31) ||
            (nums[0] == 192 && nums[1] == 168) ||
            nums[0] == 127
    }

    private fun request(serverUrl: String, method: String, path: String, body: Any?, token: String?): Any? {
        val base = serverUrl.trim().trimEnd('/')
        val url = URL(base + path)
        if (url.protocol != "https" && !(url.protocol == "http" && isPrivateHttpHost(url.host))) {
            throw RuleSyncApiException("家庭同步地址必须使用 HTTPS，或使用家庭局域网私网地址（如 http://192.168.1.10:8080）")
        }
        val payload = body?.let { MiniJson.encode(it) }?.toByteArray(Charsets.UTF_8)
        if (payload != null && payload.size > MAX_BODY) throw RuleSyncApiException("同步请求超过大小限制")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            if (payload != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    val body = MiniJson.decode(text) as? Map<*, *>
                    (body?.get("detail") as? String) ?: (body?.get("error") as? String)
                }.getOrNull() ?: when (code) {
                    401, 403 -> "设备连接已失效，请重新配对"
                    409 -> "家庭规则已被重置，请重新对账"
                    413 -> "同步内容超过大小限制"
                    else -> "家庭服务暂不可用（$code）"
                }
                throw RuleSyncApiException(message, code)
            }
            return if (text.isBlank()) emptyMap<String, Any?>() else MiniJson.decode(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun items(value: Any?): List<RuleSyncRemoteItem> =
        (value as? List<*> ?: emptyList<Any?>()).map { row ->
            remoteItem(row as? Map<*, *> ?: throw RuleSyncApiException("规则快照格式无效"))
        }

    private fun remoteItem(row: Map<*, *>): RuleSyncRemoteItem {
        val payload = row["payload"]
        val payloadText = when (payload) {
            null -> "{}"
            is String -> payload
            else -> MiniJson.encode(payload)
        }
        val deleted = (row["deletedAt"] as? String)?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        return RuleSyncRemoteItem(
            itemId = text(row, "itemId"),
            kind = text(row, "kind"),
            schemaVersion = number(row["schemaVersion"]).toInt(),
            payload = payloadText,
            naturalKey = row["naturalKey"] as? String,
            sortKey = when (val rawSort = row["sortKey"]) {
                is Number -> rawSort.toLong()
                is String -> rawSort.toLongOrNull() ?: 0L
                else -> 0L
            },
            revision = number(row["revision"]).toLong(),
            contentUpdatedAt = (row["contentUpdatedAt"] as? Number)?.toLong(),
            deletedAt = deleted,
        )
    }

    private fun text(map: Map<*, *>, key: String): String =
        map[key] as? String ?: throw RuleSyncApiException("响应缺少 $key")

    private fun number(value: Any?): Number =
        value as? Number ?: throw RuleSyncApiException("响应数字无效")
}
