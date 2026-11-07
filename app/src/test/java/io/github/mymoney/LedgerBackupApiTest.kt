package io.github.mymoney

import io.github.mymoney.data.Backup
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.rulesync.LedgerBackupApi
import io.github.mymoney.data.rulesync.RuleSyncConfig
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LedgerBackupApiTest {
    private fun metadata(id: String, file: File) = mapOf(
        "id" to id, "deviceName" to "test", "appVersion" to "1.7.0/121", "backupSchema" to 8,
        "sha256" to LedgerBackupApi.sha256(file), "sizeBytes" to file.length(), "billCount" to 0,
        "snapshotAt" to "2026-11-01T00:00:00Z", "uploadedAt" to "2026-11-01T00:00:00Z",
    )
    @Test fun rejectsMalformedOrOversizedIndexes() {
        val file = File.createTempFile("ledger-index", ".json")
        try {
            file.writeText("{}")
            val valid = metadata(UUID.randomUUID().toString(), file)
            assertEquals(0L, LedgerBackupApi.entry(valid).billCount)
            listOf(valid + ("sizeBytes" to (LedgerBackupApi.MAX_BYTES + 1)), valid + ("sha256" to "bad"),
                valid + ("id" to "../outside"), valid + ("backupSchema" to 99), valid + ("billCount" to -1), valid + ("backupSchema" to 8.5), valid + ("sizeBytes" to 1.5)).forEach {
                assertTrue(runCatching { LedgerBackupApi.entry(it) }.isFailure)
            }
        } finally { file.delete() }
    }
    @Test fun uploadDownloadAndRedirectBoundary() {
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val directory = kotlin.io.path.createTempDirectory("ledger-network").toFile()
        val source = File(directory, "source.json").apply {
            writeText(Backup.encode(Backup.Snapshot(1, emptyList(), null, null)))
        }
        val id = UUID.randomUUID().toString()
        val row = metadata(id, source)
        var received = ByteArray(0)
        var redirects = 0
        val serverError = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                repeat(4) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) bytes.write(b) }
                            return bytes.toString("UTF-8")
                        }
                        val request = line()
                        val headers = mutableMapOf<String, String>()
                        while (true) { val h = line(); if (h.isEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
                        assertEquals("Bearer test-secret", headers["authorization"])
                        val isPut = request.startsWith("PUT ")
                        val isList = request.contains("?offset=")
                        val response = if (isPut) {
                            received = ByteArray(headers["content-length"]!!.toInt())
                            var n = 0
                            while (n < received.size) { val read = input.read(received, n, received.size - n); check(read > 0); n += read }
                            assertEquals(LedgerBackupApi.sha256(source), headers["x-content-sha256"])
                            MiniJson.encode(row).toByteArray()
                        } else if (isList) ByteArray(0) else source.readBytes()
                        if (request.contains("/stolen")) redirects++
                        val status = if (isList) "302 Found" else "200 OK"
                        val extra = if (isList) "Location: /stolen\r\n" else "X-Content-SHA256: ${LedgerBackupApi.sha256(source)}\r\n"
                        socket.getOutputStream().use { out ->
                            out.write(("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: ${response.size}\r\n" + extra + "\r\n").toByteArray())
                            out.write(response)
                        }
                    }
                }
            } catch (t: Throwable) { serverError.set(t) }
        }
        try {
            val config = RuleSyncConfig(serverUrl = "http://127.0.0.1:${server.localPort}", epoch = UUID.randomUUID().toString())
            val entry = LedgerBackupApi.upload(config, "test-secret", id, source, "1.7.0/121")
            assertArrayEquals(source.readBytes(), received)
            val downloaded = File(directory, "download.json")
            assertEquals(0, LedgerBackupApi.download(config, "test-secret", entry, downloaded).bills.size)
            assertEquals(LedgerBackupApi.sha256(source), LedgerBackupApi.sha256(downloaded))
            assertTrue(runCatching { LedgerBackupApi.list(config, "test-secret") }.isFailure)
            assertEquals(0, redirects)
            val corruptIndex = entry.copy(sha256 = "a".repeat(64))
            assertTrue(runCatching { LedgerBackupApi.download(config, "test-secret", corruptIndex, File(directory, "bad.json")) }.isFailure)
            assertFalse(File(directory, "bad.json").exists())
            assertFalse(File(directory, "bad.json.part").exists())
                    worker.join(5000)
            serverError.get()?.let { throw it }
        } finally { server.close(); directory.deleteRecursively() }
    }

    private class OneShot(val config: RuleSyncConfig, val worker: Thread, val failure: AtomicReference<Throwable?>)

    /** Serves exactly one request, then hands back its line, headers and body for assertions. */
    private fun singleRequest(
        status: String, payload: Map<String, Any?>,
        inspect: (request: String, headers: Map<String, String>, body: String) -> Unit,
    ): OneShot {
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) bytes.write(b) }
                        return bytes.toString("UTF-8")
                    }
                    val request = line()
                    val headers = mutableMapOf<String, String>()
                    while (true) { val h = line(); if (h.isEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length).also { var n = 0; while (n < it.size) { val read = input.read(it, n, it.size - n); check(read > 0); n += read } }
                    inspect(request, headers, String(body, Charsets.UTF_8))
                    val bytes = MiniJson.encode(payload).toByteArray()
                    socket.getOutputStream().use { out ->
                        out.write(("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray())
                        out.write(bytes)
                    }
                }
            } catch (t: Throwable) { failure.set(t) } finally { server.close() }
        }
        return OneShot(RuleSyncConfig(serverUrl = "http://127.0.0.1:${server.localPort}", epoch = UUID.randomUUID().toString()), worker, failure)
    }

    private fun OneShot.finish() { worker.join(5000); failure.get()?.let { throw it } }

    @Test fun deleteSendsOneBatchAndReportsRecycleState() {
        val ids = List(2) { UUID.randomUUID().toString() }
        val operationId = UUID.randomUUID().toString()
        val server = singleRequest("200 OK", mapOf(
            "operationId" to operationId, "deletedCount" to 2, "deletedBytes" to 4096,
            "recycledBytes" to 2048, "pendingBytes" to 2048, "recyclePending" to true,
        )) { line, headers, body ->
            assertEquals("POST /v1/backups/delete", line.substringBefore(" HTTP/"))
            assertEquals("Bearer test-secret", headers["authorization"])
            val json = MiniJson.decode(body) as Map<*, *>
            assertEquals(operationId, json["operationId"])
            assertEquals(ids, json["backupIds"])
        }
        val result = LedgerBackupApi.delete(server.config, "test-secret", operationId, ids)
        server.finish()
        assertEquals(2, result.deletedCount)
        assertEquals(4096L, result.deletedBytes)
        assertEquals(2048L, result.recycledBytes)
        assertEquals(2048L, result.pendingBytes)
        assertTrue(result.recyclePending)
    }

    @Test fun deletedBackupStatusIsReportedAsDeleted() {
        val id = UUID.randomUUID().toString()
        val server = singleRequest("404 Not Found", mapOf("error" to "备份不存在或已删除")) { line, _, _ ->
            assertEquals("GET /v1/backups/$id/status", line.substringBefore(" HTTP/"))
        }
        val error = runCatching { LedgerBackupApi.status(server.config, "test-secret", id) }.exceptionOrNull()
        server.finish()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("已删除"))
    }
}
