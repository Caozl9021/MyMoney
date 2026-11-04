package io.github.mymoney.data.rulesync

import io.github.mymoney.data.Backup
import io.github.mymoney.data.MiniJson
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Raw private files; tokens never follow redirects and all bodies have an actual byte limit. */
object LedgerBackupApi {
    const val MAX_BYTES = 200L * 1024 * 1024
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

    fun upload(config: RuleSyncConfig, token: String, id: String, file: File, version: String): Entry {
        UUID.fromString(id)
        require(file.length() in 1..MAX_BYTES) { "快照大小超过限制" }
        val conn = open(config, token, "/v1/backups/$id", "PUT")
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Content-SHA256", sha256(file))
            conn.setRequestProperty("X-App-Version", version)
            conn.setRequestProperty("X-Space-Epoch", config.epoch)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(file.length())
            conn.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            checkStatus(conn)
            val entry = entry(readObject(conn))
            require(entry.id == id && entry.sha256 == sha256(file) && entry.sizeBytes == file.length()) { "上传回执与本地快照不一致" }
            return entry
        } finally { conn.disconnect() }
    }

    fun list(config: RuleSyncConfig, token: String, offset: Int = 0): Page {
        val conn = open(config, token, "/v1/backups?offset=$offset&limit=30", "GET")
        try {
            checkStatus(conn)
            val root = readObject(conn)
            require(root["protocolVersion"] == 1L || root["protocolVersion"] == 1) { "家庭备份协议不兼容" }
            require(root["spaceId"] == config.spaceId && root["epoch"] == config.epoch) { "家庭空间已变化，请重新连接对账" }
            val rows = root["items"] as? List<*> ?: error("备份列表无效")
            val next = (root["nextOffset"] as? Number)?.toInt() ?: error("备份分页无效")
            val more = root["hasMore"] as? Boolean ?: error("备份分页无效")
            require(!more || next > offset) { "备份分页未推进" }
            return Page(rows.map { entry(it as? Map<*, *> ?: error("备份索引无效")) }, next, more)
        } finally { conn.disconnect() }
    }

    fun download(config: RuleSyncConfig, token: String, entry: Entry, target: File): Backup.Snapshot {
        val conn = open(config, token, "/v1/backups/${entry.id}", "GET")
        val part = File(target.parentFile, target.name + ".part")
        try {
            checkStatus(conn)
            require(conn.contentLengthLong == entry.sizeBytes) { "下载长度与索引不一致" }
            require(conn.getHeaderField("X-Content-SHA256") == entry.sha256) { "下载摘要与索引不一致" }
            var size = 0L
            conn.inputStream.use { input -> FileOutputStream(part).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    size += n
                    require(size <= entry.sizeBytes && size <= MAX_BYTES) { "下载超过大小限制" }
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            } }
            require(size == entry.sizeBytes && sha256(part) == entry.sha256) { "下载文件校验失败" }
            val snapshot = Backup.decode(part.readText(Charsets.UTF_8))
            require(snapshot.bills.size.toLong() == entry.billCount) { "备份账单数与索引不一致" }
            require(part.renameTo(target)) { "无法保存下载快照" }
            return snapshot
        } finally { conn.disconnect(); part.delete() }
    }

    private fun open(config: RuleSyncConfig, token: String, path: String, method: String): HttpURLConnection {
        val url = URL(config.serverUrl.trimEnd('/') + path)
        require(RuleSyncApi.isAllowedServiceUrl(url)) { "家庭服务地址须使用HTTPS、私网HTTP或http://100.65.248.62:18080（不能含账号密码或片段）" }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method; instanceFollowRedirects = false
            connectTimeout = 15_000; readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
    }
    private fun checkStatus(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) throw RuleSyncApiException(when (code) {
            401, 403 -> "家庭连接已失效，请重新配对"
            409 -> "备份或家庭空间已变化，请重新检查"
            413 -> "备份超过家庭服务大小限制"
            422 -> "家庭服务拒绝了快照格式或摘要；请检查文件，使用新格式时需升级家庭服务"
            507 -> "家庭服务存储空间不足，已有备份已保留"
            in 300..399 -> "家庭服务发生重定向，请检查连接地址"
            else -> "家庭备份操作失败（$code），可重试"
        }, code)
    }
    private fun readObject(conn: HttpURLConnection): Map<*, *> {
        val bytes = conn.inputStream.use { it.readBytesBounded(1024 * 1024) }
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
