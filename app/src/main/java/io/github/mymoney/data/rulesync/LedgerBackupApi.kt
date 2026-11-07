package io.github.mymoney.data.rulesync

import io.github.mymoney.data.Backup
import io.github.mymoney.data.MiniJson
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Raw private files; tokens never follow redirects and all bodies have an actual byte limit. */
object LedgerBackupApi {
    const val MAX_BYTES = 200L * 1024 * 1024

    /** Mirrors the server batch cap so the App never sends a request that is rejected wholesale. */
    const val DELETE_BATCH = 100
    data class Entry(
        val id: String, val deviceName: String, val appVersion: String,
        val sha256: String, val sizeBytes: Long, val billCount: Long,
        val snapshotAt: String, val uploadedAt: String, val backupSchema: Int = Backup.FORMAT,
    )
    data class Page(val items: List<Entry>, val nextOffset: Int, val hasMore: Boolean)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun upload(config: RuleSyncConfig, token: String, id: String, file: File, version: String,
               transport: FamilyTransport = DirectHttpTransport()): Entry {
        UUID.fromString(id)
        require(file.length() in 1..MAX_BYTES) { "快照大小超过限制" }
        val sha = sha256(file)
        val response = openResponse(config, token, "/v1/backups/$id", "PUT", transport, mapOf(
            "Content-Type" to "application/json",
            "X-Content-SHA256" to sha,
            "X-App-Version" to version,
            "X-Space-Epoch" to config.epoch,
        ), FamilyTransport.RequestBody.FromFile(file))
        try {
            checkStatus(response)
            val entry = entry(readObject(response))
            require(entry.id == id && entry.sha256 == sha && entry.sizeBytes == file.length()) { "上传回执与本地快照不一致" }
            return entry
        } finally { response.close() }
    }

    fun list(config: RuleSyncConfig, token: String, offset: Int = 0,
             transport: FamilyTransport = DirectHttpTransport()): Page {
        val response = openResponse(config, token, "/v1/backups?offset=$offset&limit=30", "GET", transport)
        try {
            checkStatus(response)
            val root = readObject(response)
            require(root["protocolVersion"] == 1L || root["protocolVersion"] == 1) { "家庭备份协议不兼容" }
            require(root["spaceId"] == config.spaceId && root["epoch"] == config.epoch) { "家庭空间已变化，请重新连接对账" }
            val rows = root["items"] as? List<*> ?: error("备份列表无效")
            val next = (root["nextOffset"] as? Number)?.toInt() ?: error("备份分页无效")
            val more = root["hasMore"] as? Boolean ?: error("备份分页无效")
            require(!more || next > offset) { "备份分页未推进" }
            return Page(rows.map { entry(it as? Map<*, *> ?: error("备份索引无效")) }, next, more)
        } finally { response.close() }
    }

    /** Server-side deletion receipt; the server caps one batch at 100 ids. */
    data class DeleteResult(
        val deletedCount: Int, val deletedBytes: Long, val recycledBytes: Long,
        val pendingBytes: Long, val recyclePending: Boolean,
    )

    fun delete(config: RuleSyncConfig, token: String, operationId: String, ids: List<String>,
               transport: FamilyTransport = DirectHttpTransport()): DeleteResult {
        require(ids.isNotEmpty()) { "请选择要删除的备份" }
        val payload = MiniJson.encode(mapOf("operationId" to operationId, "backupIds" to ids))
        val response = openResponse(config, token, "/v1/backups/delete", "POST", transport,
            extraHeaders = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = FamilyTransport.RequestBody.Bytes(payload.toByteArray(Charsets.UTF_8)))
        try {
            checkStatus(response)
            val root = readObject(response)
            return DeleteResult(
                deletedCount = (root["deletedCount"] as? Number)?.toInt() ?: error("删除回执缺少数量"),
                deletedBytes = (root["deletedBytes"] as? Number)?.toLong() ?: 0L,
                recycledBytes = (root["recycledBytes"] as? Number)?.toLong() ?: 0L,
                pendingBytes = (root["pendingBytes"] as? Number)?.toLong() ?: 0L,
                recyclePending = root["recyclePending"] == true,
            )
        } finally { response.close() }
    }

    /** Re-checks one backup right before the restore confirmation actually writes local data. */
    fun status(config: RuleSyncConfig, token: String, id: String,
               transport: FamilyTransport = DirectHttpTransport()): Entry {
        val response = openResponse(config, token, "/v1/backups/$id/status", "GET", transport)
        try {
            checkStatus(response)
            val entry = entry(readObject(response))
            require(entry.id == id) { "备份状态与请求不一致" }
            return entry
        } finally { response.close() }
    }

    fun download(config: RuleSyncConfig, token: String, entry: Entry, target: File,
                 transport: FamilyTransport = DirectHttpTransport(),
                 operation: DownloadOperation? = null): Backup.Snapshot {
        val url = java.net.URL(config.serverUrl.trimEnd('/') + "/v1/backups/${entry.id}")
        require(RuleSyncApi.isAllowedServiceUrl(url)) { "家庭服务地址须使用HTTPS、私网HTTP或http://<家庭服务地址>（不能含账号密码或片段）" }
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val part = File(target.parentFile, target.name + ".part")
        try {
            // Stream straight to the .part file; verifies length + SHA while copying and
            // never buffers the 200 MiB body in memory (embedded transport does it in Go).
            if (operation != null) {
                transport.downloadToFile(operation, url.toString(), headers, part, entry.sizeBytes, entry.sha256, MAX_BYTES, 120_000)
            } else {
                transport.downloadToFile(url.toString(), headers, part, entry.sizeBytes, entry.sha256, MAX_BYTES, 120_000)
            }
            require(part.length() == entry.sizeBytes) { "下载长度与索引不一致" }
            val snapshot = Backup.decode(part.readText(Charsets.UTF_8))
            require(snapshot.bills.size.toLong() == entry.billCount) { "备份账单数与索引不一致" }
            require(part.renameTo(target)) { "无法保存下载快照" }
            return snapshot
        } finally { part.delete() }
    }

    private fun openResponse(
        config: RuleSyncConfig, token: String, path: String, method: String,
        transport: FamilyTransport, extraHeaders: Map<String, String> = emptyMap(),
        body: FamilyTransport.RequestBody? = null,
    ): FamilyTransport.Response {
        val url = java.net.URL(config.serverUrl.trimEnd('/') + path)
        require(RuleSyncApi.isAllowedServiceUrl(url)) { "家庭服务地址须使用HTTPS、私网HTTP或http://<家庭服务地址>（不能含账号密码或片段）" }
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer $token"
        headers["Accept"] = "application/json"
        headers.putAll(extraHeaders)
        return try {
            transport.execute(method, url.toString(), headers, body, 15_000, 120_000)
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            throw RuleSyncApiException("家庭备份网络请求失败")
        }
    }

    private fun checkStatus(response: FamilyTransport.Response) {
        val code = response.status
        if (code !in 200..299) throw RuleSyncApiException(when (code) {
            401, 403 -> "家庭连接已失效，请重新配对"
            404 -> "备份不存在或已删除"
            409 -> "备份或家庭空间已变化，请重新检查"
            410 -> "此备份已删除，不能重新上传"
            413 -> "备份超过家庭服务大小限制"
            422 -> "家庭服务拒绝了快照格式或摘要；请检查文件，使用新格式时需升级家庭服务"
            507 -> "家庭服务存储空间不足，已有备份已保留"
            in 300..399 -> "家庭服务发生重定向，请检查连接地址"
            else -> "家庭备份操作失败（$code），可重试"
        }, code)
    }
    private fun readObject(response: FamilyTransport.Response): Map<*, *> {
        val bytes = response.bodyStream().use { it.readBytesBounded(1024 * 1024) }
        return MiniJson.decode(bytes.toString(Charsets.UTF_8)) as? Map<*, *> ?: error("家庭备份响应无效")
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "家庭备份响应过大" }; output.write(buffer, 0, n) }
        return output.toByteArray()
    }

    internal fun entry(row: Map<*, *>): Entry {
        fun text(key: String) = row[key] as? String ?: error("备份索引缺少$key")
        val id = text("id").also { UUID.fromString(it) }
        val hash = text("sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) { "备份摘要无效" } }
        fun integer(key: String): Long {
            val raw = row[key] as? Number ?: error("备份索引缺少$key")
            val value = raw.toLong()
            require(raw.toDouble().isFinite() && raw.toDouble() == value.toDouble()) { "备份索引${key}必须为整数" }
            return value
        }
        val size = integer("sizeBytes")
        val count = integer("billCount")
        require(size in 1..MAX_BYTES && count >= 0) { "备份索引超出限制" }
        val schema = integer("backupSchema")
        require(schema in 8L..Backup.FORMAT.toLong()) { "备份格式不受支持，请升级App" }
        return Entry(id, text("deviceName"), text("appVersion"), hash, size, count, text("snapshotAt"), text("uploadedAt"), schema.toInt())
    }
}
