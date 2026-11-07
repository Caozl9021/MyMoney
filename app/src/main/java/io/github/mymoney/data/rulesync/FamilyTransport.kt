package io.github.mymoney.data.rulesync

import java.io.File
import java.io.IOException

/**
 * Transport abstraction so the same sync/backup logic (RuleSyncApi, LedgerBackupApi)
 * runs over either:
 *   - [DirectHttpTransport]: the existing direct HttpURLConnection path (private LAN / HTTPS /
 *     the fixed tailnet HTTP exception). Kept byte-for-byte compatible with prior behavior.
 *   - an embedded tsnet transport (gomobile), added in U3.2, which routes the same request
 *     through a userspace tailnet node without a system VPN.
 *
 * This is deliberately a request/response (not socket) abstraction: every policy and
 * validation in the two APIs (size bounds, SHA checks, redirect refusal, .part commit)
 * stays identical regardless of transport. The transport is responsible only for
 * performing the HTTP exchange and enforcing its own hard limits.
 *
 * Security invariants every implementation MUST uphold (checked again here so a buggy
 * or hostile transport cannot widen the blast radius):
 *   - No redirects followed.
 *   - No userinfo/fragment in the target URL.
 *   - Request body size <= [MAX_REQUEST_BODY]; response body bounded by caller.
 */
interface FamilyTransport {
    /**
     * Executes a single HTTP exchange.
     *
     * @param method HTTP method, e.g. "GET"/"POST"/"PUT".
     * @param url absolute URL string (already validated by caller against isAllowedServiceUrl).
     * @param headers request headers (must include Authorization etc.); applied verbatim.
     * @param body if non-null, the exact request body bytes; Content-Length is set to its size.
     * @param readTimeoutMs socket read timeout.
     * @return [Response] with status, headers and a streaming body the caller must close.
     */
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: RequestBody?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Response

    /** A request body: either an in-memory byte array or a streamed file (for large uploads). */
    sealed class RequestBody {
        abstract val length: Long
        class Bytes(val bytes: ByteArray) : RequestBody() {
            override val length: Long get() = bytes.size.toLong()
        }

        /** Streams [file] as the request body; length is the file size (fixed-length streaming). */
        class FromFile(val file: File) : RequestBody() {
            override val length: Long get() = file.length()
        }
    }

    /** A response whose body stream the caller must close. */
    interface Response : AutoCloseable {
        val status: Int
        val headers: Map<String, List<String>>
        /** Content-Length header if present and non-negative, else -1. */
        val contentLength: Long

        /** Streams the response body. Only valid for 2xx; for non-2xx use [errorStream]. */
        fun bodyStream(): java.io.InputStream

        /** Streams the error body for non-2xx responses (may be empty). */
        fun errorStream(): java.io.InputStream

        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
    }

    /**
     * Streams a GET body directly to [destTemp] on disk, then the caller renames it.
     * Large transfers (ledger backups up to 200 MiB) MUST use this so the body is
     * never materialized as a byte array across the transport boundary. The default
     * implementation falls back to [execute] + streaming copy, which is correct for
     * the direct HTTP transport; the embedded transport overrides it to call the Go
     * streaming downloader directly.
     *
     * Verifies length against [expectedLength] (>=0) and SHA-256 against [expectedSha]
     * (if non-empty) while streaming. On any failure [destTemp] is removed and an
     * exception is thrown; [destTemp] is only left in place on success.
     *
     * @return the number of bytes written.
     */
    fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        destTemp: File,
        expectedLength: Long,
        expectedSha: String,
        maxBytes: Long,
        readTimeoutMs: Int,
    ): Long {
        val response = execute("GET", url, headers, null, 15_000, readTimeoutMs)
        try {
            if (response.status !in 200..299) throw FamilyTransportException("下载失败（${response.status}）")
            if (expectedLength >= 0 && response.contentLength >= 0 && response.contentLength != expectedLength) {
                throw FamilyTransportException("下载长度与索引不一致")
            }
            var size = 0L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            response.bodyStream().use { input -> destTemp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    size += n
                    if (maxBytes in 1..Long.MAX_VALUE && size > maxBytes) {
                        throw FamilyTransportException("下载超过大小限制")
                    }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
                output.flush()
            } }
            if (expectedLength >= 0 && size != expectedLength) {
                destTemp.delete(); throw FamilyTransportException("下载长度校验失败")
            }
            if (expectedSha.isNotEmpty()) {
                val sum = digest.digest().joinToString("") { "%02x".format(it) }
                if (!sum.equals(expectedSha, ignoreCase = true)) {
                    destTemp.delete(); throw FamilyTransportException("下载摘要校验失败")
                }
            }
            return size
        } catch (e: Exception) {
            destTemp.delete()
            throw e
        } finally {
            response.close()
        }
    }

    /**
     * Cancelable + progress-reporting variant of [downloadToFile] (AA3). The default
     * implementation streams via [execute] and honours [operation] cancellation by
     * closing the response; the embedded transport overrides it to call the Go
     * streaming downloader with a native cancel handle. Semantics (part file, length
     * and SHA verification, atomic rename) are identical to [downloadToFile].
     *
     * @return the number of bytes written.
     */
    fun downloadToFile(
        operation: DownloadOperation,
        url: String,
        headers: Map<String, String>,
        destTemp: File,
        expectedLength: Long,
        expectedSha: String,
        maxBytes: Long,
        readTimeoutMs: Int,
    ): Long {
        var cancelled = false
        val response = execute("GET", url, headers, null, 15_000, readTimeoutMs)
        try {
            if (isCancelled(operation)) throw DownloadCancelledException()
            if (response.status !in 200..299) throw FamilyTransportException("下载失败（${response.status}）")
            if (expectedLength >= 0 && response.contentLength >= 0 && response.contentLength != expectedLength) {
                throw FamilyTransportException("下载长度与索引不一致")
            }
            var size = 0L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            response.bodyStream().use { input -> destTemp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled(operation)) { cancelled = true; throw DownloadCancelledException() }
                    val n = input.read(buffer); if (n < 0) break
                    size += n
                    if (maxBytes in 1..Long.MAX_VALUE && size > maxBytes) {
                        throw FamilyTransportException("下载超过大小限制")
                    }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                    operation.reportProgress(size)
                }
                output.flush()
            } }
            if (expectedLength >= 0 && size != expectedLength) {
                destTemp.delete(); throw FamilyTransportException("下载长度校验失败")
            }
            if (expectedSha.isNotEmpty()) {
                val sum = digest.digest().joinToString("") { "%02x".format(it) }
                if (!sum.equals(expectedSha, ignoreCase = true)) {
                    destTemp.delete(); throw FamilyTransportException("下载摘要校验失败")
                }
            }
            return size
        } catch (e: Exception) {
            destTemp.delete()
            throw e
        } finally {
            if (cancelled) runCatching { response.close() }
            else response.close()
        }
    }

    private fun isCancelled(operation: DownloadOperation): Boolean =
        (operation as? CancellableOperation)?.isCancelled == true

    companion object {
        /** Hard cap on an in-memory/small request body (large uploads stream from a file). */
        const val MAX_REQUEST_BODY: Long = 1L * 1024 * 1024
    }
}
