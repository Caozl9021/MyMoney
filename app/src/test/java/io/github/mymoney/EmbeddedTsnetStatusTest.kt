package io.github.mymoney

import io.github.mymoney.data.rulesync.EmbeddedTsnetTransport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedTsnetStatusTest {
    @Test fun statusDoesNotWaitForTheStartupMonitor() {
        val transport = EmbeddedTsnetTransport(File("unused"), "test",
            EmbeddedTsnetTransport.Peer("https", "home.example", 443, "/"))
        val read = CountDownLatch(1)
        lateinit var worker: Thread
        synchronized(transport) {
            worker = thread(isDaemon = true) { transport.status(); read.countDown() }
            assertTrue("UI status must not wait behind native start/stop", read.await(1, TimeUnit.SECONDS))
        }
        worker.join(1000)
    }
}
