package io.github.mymoney

import io.github.mymoney.data.rulesync.RuleSyncApi
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4 "打开麦米就能连上" 的最小可验证单元：一次 `/health` 探测，走与同步完全相同的传输。
 * 用本地 ServerSocket 起真 HTTP，不 mock。
 */
class FamilyHealthTest {

    private fun serve(response: String, seen: AtomicReference<String> = AtomicReference()): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val requestLine = input.readLine().orEmpty()
                    seen.set(requestLine)
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                    val body = response
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        flush()
                    }
                }
            }.onFailure { }
        }
        return server to worker
    }

    @Test fun healthProbeUsesTheSameWire() {
        val seen = AtomicReference("")
        val (server, worker) = serve("""{"ok":true,"protocolVersion":1}""", seen)
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            assertTrue(RuleSyncApi.health(base))
            assertEquals("GET /health HTTP/1.1", seen.get().trim())
        } finally {
            server.close(); worker.join(2_000)
        }
    }

    @Test fun brokenServiceIsReportedNotSwallowed() {
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val error = runCatching { RuleSyncApi.health(base) }.exceptionOrNull()
            // 连接被拒/无响应都要变成可读的 IO 失败，不能返回 false 假装“服务健康但没连上”。
            assertTrue("must surface as an IO failure, got $error", error is java.io.IOException)
        } finally {
            server.close()
        }
    }
}
