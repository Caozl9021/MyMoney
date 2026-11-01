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
}
