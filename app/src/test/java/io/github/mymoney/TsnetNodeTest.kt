package io.github.mymoney

import io.github.mymoney.data.rulesync.TsnetNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3 single-instance / reference-count lifecycle: concurrent tasks must share one native
 * node, the node must stay up while any task holds it, and it must be stopped and re-sealed
 * exactly once when the last holder releases. Reuse must unseal the same store, never
 * create a second node.
 */
class TsnetNodeTest {

    private class FakeDriver : TsnetNode.Driver {
        var starts = 0
        var stops = 0
        private var running = false
        override fun start(authKey: String?, ctrlUrl: String) { starts++; running = true }
        override fun stop() { stops++; running = false }
        override fun isRunning(): Boolean = running
        override fun status(): String = if (running) "Running" else "down"
    }

    private class FakeSealer : TsnetNode.Sealer {
        var unsealed = 0
        var sealed = 0
        var hasStore = true
        override fun unseal(): Boolean { unsealed++; return hasStore }
        override fun seal(): Boolean { sealed++; return true }
    }

    @Test fun sharedNodeStaysUpUntilLastHolderReleases() {
        val driver = FakeDriver()
        val sealer = FakeSealer()
        val node = TsnetNode(driver, sealer)

        val first = node.acquire("KEY")
        val second = node.acquire(null)
        assertEquals("only one native start for two holders", 1, driver.starts)
        assertEquals(2, node.holders())
        assertEquals("Running", node.status())

        first.close()
        assertEquals("must not stop while a holder remains", 0, driver.stops)
        assertEquals(0, sealer.sealed)
        assertEquals(1, node.holders())

        second.close()
        assertEquals(1, driver.stops)
        assertEquals("state must be sealed when idle", 1, sealer.sealed)
        assertEquals(0, node.holders())
        assertEquals("down", node.status())
    }

    @Test fun doubleCloseDoesNotCorruptTheCount() {
        val driver = FakeDriver()
        val sealer = FakeSealer()
        val node = TsnetNode(driver, sealer)
        val a = node.acquire(null)
        val b = node.acquire(null)
        a.close(); a.close(); a.close()
        assertEquals(1, node.holders())
        assertEquals(0, driver.stops)
        b.close()
        assertEquals(0, node.holders())
        assertEquals(1, driver.stops)
    }

    @Test fun laterAcquireReusesTheSameStore() {
        val driver = FakeDriver()
        val sealer = FakeSealer()
        val node = TsnetNode(driver, sealer)
        node.acquire("KEY").close()
        node.acquire(null).close()
        assertEquals("second session unseals, never re-creates", 2, sealer.unsealed)
        assertEquals("no re-auth key needed on reuse", 2, driver.starts)
        assertEquals(2, sealer.sealed)
    }

    @Test fun deadNodeIsRestartedInsteadOfHandedOut() {
        val driver = FakeDriver()
        val sealer = FakeSealer()
        val node = TsnetNode(driver, sealer)
        node.acquire(null)
        driver.stop() // e.g. the OS killed the socket underneath us
        assertFalse(driver.isRunning())
        node.acquire(null)
        assertEquals("must restart rather than return a dead node", 2, driver.starts)
        assertEquals(2, node.holders())
    }

    @Test fun processWideFactoryReturnsOneInstance() {
        TsnetNode.resetForTest()
        val driver = FakeDriver()
        val sealer = FakeSealer()
        val a = TsnetNode.of { TsnetNode(driver, sealer) }
        val b = TsnetNode.of { TsnetNode(FakeDriver(), FakeSealer()) }
        assertSame("second caller must get the first instance", a, b)
        assertNotNull(a)
        assertTrue(a.holders() == 0)
        TsnetNode.resetForTest()
    }
    @Test fun failedSharedStartDoesNotPoisonRetryWithOldDriver() {
        TsnetNode.resetForTest()
        val failed = object : TsnetNode.Driver {
            override fun start(authKey: String?, ctrlUrl: String) { error("offline") }
            override fun stop() {}
            override fun isRunning() = false
            override fun status() = "down"
        }
        try {
            assertTrue(runCatching {
                TsnetNode.acquireShared(null) { TsnetNode(failed, FakeSealer()) }
            }.isFailure)
            val replacement = FakeDriver()
            val (node, handle) = TsnetNode.acquireShared(null) { TsnetNode(replacement, FakeSealer()) }
            assertEquals(1, replacement.starts)
            assertEquals("Running", node.status())
            handle.close()
        } finally { TsnetNode.resetForTest() }
    }

    @Test fun failedSealCanBeRetriedWithoutLosingTheHandle() {
        val driver = FakeDriver()
        var allowSeal = false
        val sealer = object : TsnetNode.Sealer {
            override fun unseal() = true
            override fun seal() = allowSeal
        }
        val node = TsnetNode(driver, sealer)
        val handle = node.acquire(null)
        assertTrue(runCatching { handle.close() }.isFailure)
        assertEquals(1, node.holders())
        allowSeal = true
        handle.close()
        assertEquals(0, node.holders())
    }

    @Test fun unsealFailureNeverStartsANewIdentity() {
        val driver = FakeDriver()
        val sealer = object : TsnetNode.Sealer {
            override fun unseal(): Boolean = throw java.io.IOException("Keystore temporarily unavailable")
            override fun seal() = true
        }
        val node = TsnetNode(driver, sealer)
        assertTrue(runCatching { node.acquire("unused-key") }.isFailure)
        assertEquals(0, driver.starts)
        assertEquals(0, node.holders())
    }

}
