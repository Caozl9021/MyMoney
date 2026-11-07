package io.github.mymoney

import io.github.mymoney.data.rulesync.FamilyTransportException
import io.github.mymoney.data.rulesync.FamilyTransportKind
import io.github.mymoney.data.rulesync.FamilyTransportProvider
import io.github.mymoney.data.rulesync.RuleSyncConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4 wire selection. The rule that matters: turning embedded on must never quietly
 * fall back to the public network — on a 32-bit device it fails with a message the
 * settings page can show, and old configs stay direct.
 */
class FamilyTransportProviderTest {

    @Test fun oldConfigsStayDirect() {
        assertEquals(FamilyTransportKind.DIRECT, FamilyTransportProvider.kindFor(RuleSyncConfig(), true))
        assertEquals(FamilyTransportKind.DIRECT, FamilyTransportProvider.kindFor(RuleSyncConfig(useEmbedded = false), false))
        assertFalse("默认必须是直连，才不改变 1.9.0/146 的既有行为", RuleSyncConfig().useEmbedded)
        assertEquals("", RuleSyncConfig().ctrlUrl)
    }

    @Test fun embeddedOnSupportedAbi() {
        assertEquals(FamilyTransportKind.EMBEDDED, FamilyTransportProvider.kindFor(RuleSyncConfig(useEmbedded = true), true))
    }

    @Test fun embeddedOnUnsupportedAbiFailsLoudly() {
        val error = runCatching { FamilyTransportProvider.kindFor(RuleSyncConfig(useEmbedded = true), false) }.exceptionOrNull()
        assertTrue("must be a transport error, got $error", error is FamilyTransportException)
        assertTrue("message must name the requirement: ${error?.message}", error?.message?.contains("64") == true)
    }

    @Test fun peerKeepsExactHostAndPort() {
        val peer = FamilyTransportProvider.derivePeer("http://100.65.248.62:18080/")
        assertEquals("http", peer.scheme)
        assertEquals("100.65.248.62", peer.host)
        assertEquals(18080, peer.port)
        assertEquals("/", peer.pathPrefix)
    }

    @Test fun peerDefaultsPortByScheme() {
        assertEquals(443, FamilyTransportProvider.derivePeer("https://home.example.test").port)
        assertEquals(80, FamilyTransportProvider.derivePeer("http://10.0.0.2").port)
    }

    @Test fun peerRejectsDirtyOrEmptyAddresses() {
        listOf("", "   ", "http://user:pw@10.0.0.2:18080", "http://10.0.0.2:18080/#frag", "not a url").forEach { bad ->
            val error = runCatching { FamilyTransportProvider.derivePeer(bad) }.exceptionOrNull()
            assertTrue("[$bad] must be rejected, got $error", error is FamilyTransportException)
        }
    }
}
