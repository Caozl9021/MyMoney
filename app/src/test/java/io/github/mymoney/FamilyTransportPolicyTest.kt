package io.github.mymoney

import io.github.mymoney.data.rulesync.FamilyTransport
import io.github.mymoney.data.rulesync.FamilyTransportException
import io.github.mymoney.data.rulesync.DirectHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * U3.3: destination/size restriction invariants at the transport boundary.
 *
 * These run on the JVM with the DirectHttpTransport (no device, no network): every
 * implementation must refuse userinfo/fragment in the URL and oversize request
 * bodies *before* any socket is opened, so a caller bug can't widen the blast radius.
 */
class FamilyTransportPolicyTest {

    private val transport = DirectHttpTransport()

    @Test
    fun directRejectsUserinfo() {
        val e = assertThrows(FamilyTransportException::class.java) {
            transport.execute("GET", "http://user:pass@10.0.0.1/x", emptyMap(), null, 1000, 1000)
        }
        assertEquals(true, e.message!!.contains("账号密码"))
    }

    @Test
    fun directRejectsFragment() {
        assertThrows(FamilyTransportException::class.java) {
            transport.execute("GET", "http://10.0.0.1/x#frag", emptyMap(), null, 1000, 1000)
        }
    }

    @Test
    fun directRejectsOversizeBody() {
        val big = ByteArray((FamilyTransport.MAX_REQUEST_BODY + 1).toInt())
        assertThrows(FamilyTransportException::class.java) {
            transport.execute("POST", "http://10.0.0.1/x", emptyMap(), FamilyTransport.RequestBody.Bytes(big), 1000, 1000)
        }
    }

    @Test
    fun directRejectsDisallowedPublicHttpHost() {
        // Public HTTP (not private/RFC1918, not the fixed tailnet exception) must fail
        // the shared endpoint policy even before dialing.
        assertThrows(io.github.mymoney.data.rulesync.RuleSyncApiException::class.java) {
            transport.execute("GET", "http://8.8.8.8/x", emptyMap(), null, 1000, 1000)
        }
    }

    @Test
    fun directRejectsTooLargeFileBody() {
        val f = File.createTempFile("huge", ".bin")
        try {
            // Sparse-extend beyond the request cap; the fixed-length guard fires on
            // body.length before any bytes are streamed.
            java.io.RandomAccessFile(f, "rw").use { it.setLength(FamilyTransport.MAX_REQUEST_BODY + 1) }
            assertThrows(FamilyTransportException::class.java) {
                transport.execute("PUT", "http://10.0.0.1/x", emptyMap(), FamilyTransport.RequestBody.FromFile(f), 1000, 1000)
            }
        } finally { f.delete() }
    }
}
