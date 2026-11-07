package io.github.mymoney.data.rulesync

import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct transport: the existing HttpURLConnection path (private LAN HTTP / HTTPS /
 * the fixed tailnet HTTP exception). Behavior is byte-for-byte the same as the pre-U3
 * direct code in RuleSyncApi/LedgerBackupApi: no redirects, bounded bodies, fixed
 * timeouts supplied by the caller. This is the default transport and remains in use
 * for every user who has not enabled embedded tsnet.
 */
class DirectHttpTransport : FamilyTransport {

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: FamilyTransport.RequestBody?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): FamilyTransport.Response {
        if (url.contains('@')) throw FamilyTransportException("地址不能包含账号密码")
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw FamilyTransportException("地址无效", e)
        }
        if (parsed.userInfo != null || parsed.ref != null) throw FamilyTransportException("地址不能包含账号密码或片段")
        if (body != null && body.length > FamilyTransport.MAX_REQUEST_BODY) {
            throw FamilyTransportException("请求体超过大小限制")
        }
        // Re-check the shared endpoint policy so a caller bug can never bypass it.
        if (!RuleSyncApi.isAllowedServiceUrl(parsed)) {
            throw RuleSyncApiException("家庭服务地址须使用HTTPS、私网HTTP或http://<家庭服务地址>（不能含账号密码或片段）")
        }

        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.length)
                conn.outputStream.use { output ->
                    when (body) {
                        is FamilyTransport.RequestBody.Bytes -> output.write(body.bytes)
                        is FamilyTransport.RequestBody.FromFile -> FileInputStream(body.file).use { it.copyTo(output) }
                    }
                }
            }
            // Force the request and capture the status before returning the body stream.
            val status = conn.responseCode
            val headerMap = LinkedHashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) {
                if (k != null) headerMap[k] = v ?: emptyList()
            }
            val length = conn.contentLengthLong
            return DirectResponse(conn, status, headerMap, length)
        } catch (e: FamilyTransportException) {
            conn.disconnect(); throw e
        } catch (e: IOException) {
            conn.disconnect(); throw e
        } catch (e: Exception) {
            conn.disconnect(); throw FamilyTransportException("网络请求失败", e)
        }
    }

    override fun downloadToFile(
        operation: DownloadOperation,
        url: String,
        headers: Map<String, String>,
        destTemp: java.io.File,
        expectedLength: Long,
        expectedSha: String,
        maxBytes: Long,
        readTimeoutMs: Int,
    ): Long {
        if (operation is CancellableOperation && operation.isCancelled) {
            throw DownloadCancelledException()
        }
        if (url.contains('@')) throw FamilyTransportException("地址不能包含账号密码")
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw FamilyTransportException("地址无效", e)
        }
        if (parsed.userInfo != null || parsed.ref != null) throw FamilyTransportException("地址不能包含账号密码或片段")
        if (!RuleSyncApi.isAllowedServiceUrl(parsed)) {
            throw RuleSyncApiException("家庭服务地址须使用HTTPS、私网HTTP或http://<家庭服务地址>（不能含账号密码或片段）")
        }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        val direct = operation as? DirectDownloadOperation
        direct?.attach(conn)
        if (direct != null && direct.isCancelled) {
            conn.disconnect()
            throw DownloadCancelledException()
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) throw FamilyTransportException("下载失败：HTTP $status")
            val declared = conn.contentLengthLong
            if (expectedLength > 0 && declared > 0 && declared != expectedLength) {
                throw FamilyTransportException("响应长度与预期不符")
            }
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                destTemp.outputStream().use { output ->
                    while (true) {
                        if (direct != null && direct.isCancelled) throw DownloadCancelledException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > maxBytes) throw FamilyTransportException("文件超过大小限制")
                        output.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        operation.reportProgress(written)
                    }
                }
            }
            if (expectedLength > 0 && written != expectedLength) {
                throw FamilyTransportException("下载长度与预期不符")
            }
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha.isNotEmpty() && !expectedSha.equals(actual, ignoreCase = true)) {
                throw FamilyTransportException("下载文件校验失败")
            }
            return written
        } catch (e: DownloadCancelledException) {
            destTemp.delete(); conn.disconnect(); throw e
        } catch (e: FamilyTransportException) {
            destTemp.delete(); conn.disconnect(); throw e
        } catch (e: IOException) {
            if (direct != null && direct.isCancelled) {
                destTemp.delete(); conn.disconnect()
                throw DownloadCancelledException()
            }
            destTemp.delete(); conn.disconnect(); throw FamilyTransportException("网络请求失败", e)
        } catch (e: Exception) {
            destTemp.delete(); conn.disconnect(); throw FamilyTransportException("网络请求失败", e)
        } finally {
            direct?.detach(conn)
        }
    }

    private class DirectResponse(
        private val conn: HttpURLConnection,
        override val status: Int,
        override val headers: Map<String, List<String>>,
        override val contentLength: Long,
    ) : FamilyTransport.Response {
        private var closed = false

        override fun bodyStream(): InputStream =
            if (status in 200..299) conn.inputStream else errorStream()

        override fun errorStream(): InputStream = conn.errorStream ?: "".byteInputStream()

        override fun close() {
            if (closed) return
            closed = true
            // Drain/closing the streams happens at the call site via use{}; disconnect frees the socket.
            conn.disconnect()
        }
    }
}
