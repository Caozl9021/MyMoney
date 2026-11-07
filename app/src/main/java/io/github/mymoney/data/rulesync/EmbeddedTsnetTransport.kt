package io.github.mymoney.data.rulesync

import android.os.Build
import famisync.Famisync
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Embedded tsnet transport: routes the same [FamilyTransport] requests through a
 * userspace tailscale node (gomobile `famisync`), without a system VPN or the
 * tailscale app. The node identity is persisted in [stateDir] (app-private noBackup
 * on device) so restarts reuse the node without re-auth.
 *
 * Security: every call passes an explicit allowlist ([peer] + [pathPrefix]) to Go,
 * which re-checks the exact URL it dials. Redirects are refused, bodies are bounded,
 * and large transfers stream (file->Go / Go->.part) so nothing is buffered whole on
 * the JNI bridge. Node lifecycle is a single shared instance guarded by [lock].
 *
 * Only 64-bit ABIs are supported (the AAR ships arm64-v8a + x86_64). On unsupported
 * ABIs [isAvailable] is false and callers must fall back to [DirectHttpTransport].
 */
class EmbeddedTsnetTransport(
    private val stateDir: File,
    private val hostname: String,
    private val peer: Peer,
) : FamilyTransport {

    /** An approved family-service peer: exact scheme/host/port and an allowed path prefix. */
    data class Peer(val scheme: String, val host: String, val port: Int, val pathPrefix: String)

    @Volatile private var started = false
    @Volatile private var nodeStatus = "down"

    /** Whether the embedded node is currently up (used for restart decisions). */
    val isRunning: Boolean get() = started

    /**
     * Brings up the node if needed. authKey may be null when state already has a node.
     *
     * The native start blocks while it reaches the control plane, so it is bounded here:
     * an unreachable network must surface as a readable failure in a couple of seconds,
     * not freeze the settings page (the caller holds the sync mutex).
     */
    @Synchronized
    fun ensureStarted(authKey: String?, ctrlUrl: String = "") {
        if (started) return
        try {
            nodeStatus = "连接中"
            // The Go call owns its deadline and closes a failed native server before
            // returning. Java Future.cancel cannot cancel a JNI call.
            val ip = Famisync.startWithTimeout(stateDir.absolutePath, hostname, authKey ?: "", ctrlUrl, 20_000L)
            nodeStatus = "up $ip"
        } catch (e: Exception) {
            nodeStatus = "down"
            throw FamilyTransportException("内嵌节点启动失败：${e.message ?: "未知错误"}", e)
        }
        started = true
    }

    fun status(): String = nodeStatus

    @Synchronized
    fun shutdown() {
        if (!started) return
        Famisync.stop()
        started = false
        nodeStatus = "down"
    }

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: FamilyTransport.RequestBody?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): FamilyTransport.Response {
        if (!started) throw FamilyTransportException("内嵌连接未启动")
        // Parse and re-derive the path+query we hand to Go. Go rebuilds the URL from
        // peer+path and enforces the allowlist; we never pass a full URL that could
        // carry a different host.
        val parsed = java.net.URL(url)
        if (parsed.userInfo != null || parsed.ref != null) throw FamilyTransportException("地址不能包含账号密码或片段")
        val pathAndQuery = parsed.file // path + ?query
        if (pathAndQuery.isNullOrEmpty()) throw FamilyTransportException("地址缺少路径")
        val headersJson = JSONObject(headers as Map<*, *>).toString()

        return when {
            method.equals("GET", true) && body == null -> get(pathAndQuery, headersJson, readTimeoutMs)
            method.equals("GET", true) && body != null -> throw FamilyTransportException("GET 不支持请求体")
            method.equals("PUT", true) || method.equals("POST", true) -> when (body) {
                is FamilyTransport.RequestBody.FromFile -> putFile(method, parsed.file, headersJson, body.file, null)
                is FamilyTransport.RequestBody.Bytes -> {
                    // Small rule-sync body: stream via a temp file (never buffer a big
                    // backup here — FromFile path above streams directly).
                    if (body.bytes.size > FamilyTransport.MAX_REQUEST_BODY) throw FamilyTransportException("请求体超过大小限制")
                    val tmp = File.createTempFile("famisync-put", ".bin", stateDir)
                    try {
                        tmp.writeBytes(body.bytes)
                        putFile(method, parsed.file, headersJson, tmp, null)
                    } finally { tmp.delete() }
                }
                null -> throw FamilyTransportException("缺少请求体")
            }
            else -> throw FamilyTransportException("内嵌传输不支持方法 $method")
        }
    }

    private fun get(pathAndQuery: String, headersJson: String, readBudgetMs: Int): FamilyTransport.Response {
        val json = try {
            Famisync.get(peer.scheme, peer.host, peer.port.toString(), peer.pathPrefix, pathAndQuery, headersJson, readBudgetMs.toLong())
        } catch (e: Exception) {
            throw FamilyTransportException("内嵌请求失败: ${e.message}", e)
        }
        val obj = JSONObject(json)
        return JsonResponse(obj.getInt("status"), obj.getString("body"))
    }

    /**
     * Streams a backup download straight to [destTemp] via the Go streaming downloader
     * (length + SHA verified inside Go while copying; nothing is buffered on the JNI
     * bridge). Overrides the interface default so 200 MiB backups never cross as a
     * byte array.
     */
    override fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        destTemp: File,
        expectedLength: Long,
        expectedSha: String,
        maxBytes: Long,
        readTimeoutMs: Int,
    ): Long {
        if (!started) throw FamilyTransportException("内嵌连接未启动")
        val parsed = java.net.URL(url)
        if (parsed.userInfo != null || parsed.ref != null) throw FamilyTransportException("地址不能包含账号密码或片段")
        val pathAndQuery = parsed.file
        if (pathAndQuery.isNullOrEmpty()) throw FamilyTransportException("地址缺少路径")
        val headersJson = JSONObject(headers as Map<*, *>).toString()
        val json = try {
            Famisync.getToFile(
                peer.scheme, peer.host, peer.port.toString(), peer.pathPrefix, pathAndQuery, headersJson,
                destTemp.absolutePath, expectedSha, expectedLength, maxBytes, readTimeoutMs.toLong(),
            )
        } catch (e: Exception) {
            throw FamilyTransportException("内嵌下载失败: ${e.message}", e)
        }
        val obj = JSONObject(json)
        val status = obj.getInt("status")
        if (status !in 200..299) throw FamilyTransportException("下载失败（$status）")
        return obj.getLong("bytes")
    }

    /**
     * Operation-aware streaming download: registers a per-operation cancel handle in Go
     * and forwards byte progress to the Kotlin listener. Cancel exits the in-flight copy
     * inside Go (Famisync.cancelOp); the JNI call itself is not interruptible from Java.
     */
    override fun downloadToFile(
        operation: DownloadOperation,
        url: String,
        headers: Map<String, String>,
        destTemp: File,
        expectedLength: Long,
        expectedSha: String,
        maxBytes: Long,
        readTimeoutMs: Int,
    ): Long {
        if (!started) throw FamilyTransportException("内嵌连接未启动")
        val parsed = java.net.URL(url)
        if (parsed.userInfo != null || parsed.ref != null) throw FamilyTransportException("地址不能包含账号密码或片段")
        val pathAndQuery = parsed.file
        if (pathAndQuery.isNullOrEmpty()) throw FamilyTransportException("地址缺少路径")
        val headersJson = JSONObject(headers as Map<*, *>).toString()
        val embedded = operation as EmbeddedDownloadOperation
        embedded.register()
        if (embedded.isCancelled) throw DownloadCancelledException()
        val callback = embedded.progressCallback { received -> operation.reportProgress(received) }
        val json = try {
            Famisync.getToFileOp(
                embedded.id,
                peer.scheme, peer.host, peer.port.toString(), peer.pathPrefix, pathAndQuery, headersJson,
                destTemp.absolutePath, expectedSha, expectedLength, maxBytes, readTimeoutMs.toLong(), callback,
            )
        } catch (e: Exception) {
            destTemp.delete()
            throwIfCancelled(operation, e)
            throw FamilyTransportException("内嵌下载失败: ${e.message}", e)
        }
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            destTemp.delete()
            if (embedded.isCancelled) throw DownloadCancelledException()
            throw FamilyTransportException("内嵌下载响应无效", e)
        }
        val status = obj.optInt("status", 0)
        if (obj.optBoolean("cancelled", false) || embedded.isCancelled) {
            destTemp.delete()
            throw DownloadCancelledException()
        }
        if (status !in 200..299) {
            destTemp.delete()
            throw FamilyTransportException("下载失败（$status）")
        }
        return obj.optLong("bytes", 0L)
    }

    private fun putFile(method: String, pathAndQuery: String?, headersJson: String, file: File, expectSha: String?): FamilyTransport.Response {
        if (pathAndQuery == null || pathAndQuery.isEmpty()) throw FamilyTransportException("地址缺少路径")
        val verb = method.uppercase()
        if (verb != "PUT" && verb != "POST") throw FamilyTransportException("内嵌传输不支持方法 $method")
        val json = try {
            // Go streams the file and re-checks the allowlist; the verb is explicit
            // because /v1/pair and /v1/sync/push are POST while backups are PUT.
            Famisync.putFile(verb, peer.scheme, peer.host, peer.port.toString(), peer.pathPrefix, pathAndQuery, headersJson, file.absolutePath, expectSha ?: "")
        } catch (e: Exception) {
            throw FamilyTransportException("内嵌上传失败: ${e.message}", e)
        }
        val obj = JSONObject(json)
        return JsonResponse(obj.getInt("status"), obj.getString("body"))
    }

    /** A response backed by an in-memory body string (rule-sync JSON is small). */
    private class JsonResponse(override val status: Int, private val body: String) : FamilyTransport.Response {
        private val bytes = body.toByteArray(Charsets.UTF_8)
        override val headers: Map<String, List<String>> = emptyMap()
        override val contentLength: Long get() = bytes.size.toLong()
        override fun bodyStream(): InputStream = bytes.inputStream()
        override fun errorStream(): InputStream = if (status in 200..299) "".byteInputStream() else bytes.inputStream()
        override fun close() {}
    }

    companion object {
        /** The AAR ships only arm64-v8a and x86_64; embedded transport requires one of them. */
        fun isAvailable(): Boolean {
            val abis = Build.SUPPORTED_ABIS
            return abis.any { it == "arm64-v8a" || it == "x86_64" }
        }
    }
}
