package io.github.mymoney

import io.github.mymoney.data.rulesync.DirectHttpTransport
import io.github.mymoney.data.rulesync.FamilyTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3.6 connection-loss behavior. A dropped connection must fail in a bounded time
 * (never an infinite wait), must never leave a half-written `.part` file behind, and a
 * caller retry must resend the identical request (same operationId) rather than a new one.
 */
class FamilyTransportDisconnectTest {

    private fun readLine(input: java.io.InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == 10) break
            if (b != 13) bytes.write(b)
        }
        return bytes.toString("UTF-8")
    }

    private fun headersOf(input: java.io.InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        return headers
    }

    @Test fun dropBeforeResponseFailsBoundedlyWithoutPartialFile() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val directory = kotlin.io.path.createTempDirectory("tsnet-drop").toFile()
        val error = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                repeat(2) {
                    server.accept().use { socket -> socket.getInputStream().read() } // no response at all
                }
            } catch (t: Throwable) { error.set(t) }
        }
        try {
            val transport = DirectHttpTransport()
            val url = "http://127.0.0.1:${server.localPort}/v1/backups/x"
            val started = System.currentTimeMillis()
            // A peer closing before any response may surface as an IOException or as an empty
            // response; either way it must return promptly and deliver no payload.
            val drop = runCatching {
                transport.execute("GET", url, emptyMap(), null, 2_000, 2_000).use { it.bodyStream().readBytes() }
            }
            assertTrue("must not hang on a dropped connection", System.currentTimeMillis() - started < 10_000)
            drop.getOrNull()?.let { assertEquals("empty drop carries no bytes", 0, it.size) }

            val part = File(directory, "a.json.part")
            val failed = runCatching {
                transport.downloadToFile(url, emptyMap(), part, -1, "b".repeat(64), 1024L * 1024, 2_000)
            }.exceptionOrNull()
            assertTrue("a dropped download must fail", failed != null)
            assertFalse("no half file may remain", part.exists())
            worker.join(5_000)
            error.get()?.let { throw it }
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test fun truncatedBodyFailsAndLeavesNoPartialFile() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val directory = kotlin.io.path.createTempDirectory("tsnet-truncated").toFile()
        val error = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().buffered()
                    readLine(input); headersOf(input)
                    socket.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                        out.write(ByteArray(100) { 7 })
                        out.flush()
                    }
                }
            } catch (t: Throwable) { error.set(t) }
        }
        try {
            val transport = DirectHttpTransport()
            val part = File(directory, "download.json.part")
            val failed = runCatching {
                transport.downloadToFile("http://127.0.0.1:${server.localPort}/v1/backups/x",
                    emptyMap(), part, 1000, "", 1024L * 1024, 2_000)
            }.exceptionOrNull()
            assertTrue("truncated body must fail", failed != null)
            assertFalse("no half file may remain", part.exists())
            assertFalse("no final file may appear", File(directory, "download.json").exists())
            worker.join(5_000)
            error.get()?.let { throw it }
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test fun retryAfterFailureResendsSameOperationIdVerbatim() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val seen = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val error = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                repeat(2) { attempt ->
                    server.accept().use { socket ->
                        val input = socket.getInputStream().buffered()
                        readLine(input)
                        val headers = headersOf(input)
                        val length = headers["content-length"]?.toInt() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) { val n = input.read(body, read, length - read); if (n <= 0) break; read += n }
                        val text = String(body, Charsets.UTF_8)
                        bodies += text
                        seen += Regex("\"operationId\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                        val output = socket.getOutputStream()
                        if (attempt == 0) {
                            output.write("HTTP/1.1 500 Oops\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        } else {
                            val payload = "{\"items\":[{\"status\":\"ok\"}]}".toByteArray()
                            output.write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n".toByteArray())
                            output.write(payload)
                        }
                        output.flush()
                    }
                }
            } catch (t: Throwable) { error.set(t) }
        }
        try {
            val transport = DirectHttpTransport()
            val url = "http://127.0.0.1:${server.localPort}/v1/sync/push"
            val body = """{"protocolVersion":1,"items":[{"operationId":"op-0000000000000abc","itemId":"m-1"}]}"""
            val headers = mapOf("Content-Type" to "application/json")
            val first = transport.execute("POST", url, headers, FamilyTransport.RequestBody.Bytes(body.toByteArray()), 2_000, 2_000).use {
                assertEquals(500, it.status); it.errorStream().readBytes()
            }
            val second = transport.execute("POST", url, headers, FamilyTransport.RequestBody.Bytes(body.toByteArray()), 2_000, 2_000).use {
                assertEquals(200, it.status)
                val payload = it.bodyStream().readBytes()
                assertEquals("{\"items\":[{\"status\":\"ok\"}]}", String(payload, Charsets.UTF_8))
                payload
            }
            assertTrue(first.isEmpty())
            assertTrue(second.isNotEmpty())
            worker.join(5_000)
            error.get()?.let { throw it }
            assertEquals(2, seen.size)
            assertEquals("resend must reuse the same operationId", listOf(seen[0], seen[0]), seen)
            assertEquals("resend must carry identical bytes", listOf(bodies[0], bodies[0]), bodies)
        } finally {
            server.close()
        }
    }
}
