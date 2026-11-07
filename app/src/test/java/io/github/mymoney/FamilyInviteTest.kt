package io.github.mymoney

import io.github.mymoney.data.rulesync.FamilyInvite
import io.github.mymoney.data.rulesync.FamilyInviteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4 invite codec: one paste string, strictly validated. These cases are the whole
 * contract the settings page relies on — a bad invite must fail loudly before any
 * network call, and a good one must round-trip byte for byte.
 */
class FamilyInviteTest {

    private val invite = FamilyInvite(
        ctrlUrl = "https://controlplane.tailscale.com",
        authKey = "tskey-auth-kTEST1234CNTRL-abcdefghijklmnop",
        serverUrl = "http://100.65.248.62:18080",
        deviceName = "小米14",
    )

    @Test fun pasteFormRoundTrips() {
        val text = FamilyInvite.encode(invite)
        assertTrue(text.startsWith(FamilyInvite.PREFIX_V1))
        assertEquals(invite, FamilyInvite.parse(text))
    }

    @Test fun deepLinkFormIsAccepted() {
        val text = FamilyInvite.encode(invite)
        val link = "mymoney://join?invite=" + java.net.URLEncoder.encode(text, "UTF-8")
        assertEquals(invite, FamilyInvite.parse(link))
    }

    @Test fun rawJsonIsAccepted() {
        val json = """{"v":1,"ctrl":"https://controlplane.tailscale.com","key":"tskey-auth-kTEST1234CNTRL-abcdefghijklmnop","server":"http://100.65.248.62:18080","name":"小米14"}"""
        assertEquals(invite, FamilyInvite.parse(json))
    }

    @Test fun controlPlaneMustBeHttps() {
        val bad = invite.copy(ctrlUrl = "http://controlplane.tailscale.com")
        assertThrows { FamilyInvite.parse(FamilyInvite.encodeQuiet(bad)) }
    }

    @Test fun authKeyMustLookLikeATailscaleKey() {
        assertThrows { FamilyInvite.parse(FamilyInvite.encodeQuiet(invite.copy(authKey = "abc123"))) }
    }

    @Test fun authKeyWithWhitespaceIsRejected() {
        assertThrows { FamilyInvite.parse(FamilyInvite.encodeQuiet(invite.copy(authKey = "tskey-auth-k1 CNTRL-x"))) }
    }

    @Test fun serverMustBePresentAndClean() {
        assertThrows { FamilyInvite.parse(FamilyInvite.encodeQuiet(invite.copy(serverUrl = ""))) }
        assertThrows { FamilyInvite.parse(FamilyInvite.encodeQuiet(invite.copy(serverUrl = "http://user:pw@10.0.0.2:18080"))) }
        assertThrows { FamilyInvite.parse(FamilyInvite.encodeQuiet(invite.copy(serverUrl = "ftp://10.0.0.2/x"))) }
    }

    @Test fun garbageIsRejectedWithoutGuessing() {
        assertThrows { FamilyInvite.parse("") }
        assertThrows { FamilyInvite.parse("http://100.65.248.62:18080") }
        assertThrows { FamilyInvite.parse("MMJOIN1.not-base64!!") }
        // 未知版本（v3）仍拒绝；v2 现在是合法的完整连接码，见 connectCodeRoundTrips。
        assertThrows { FamilyInvite.parse("MMJOIN2." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"v":3,"key":"tskey-auth-k","server":"http://1.2.3.4"}""".toByteArray())) }
    }

    // ── v2 完整连接码（MMJOIN2）──

    private val connectCode = invite.copy(pairCode = "ABC123XY")

    @Test fun connectCodeRoundTrips() {
        val text = FamilyInvite.encode(connectCode)
        assertTrue(text.startsWith(FamilyInvite.PREFIX))
        assertEquals(connectCode, FamilyInvite.parse(text))
        assertTrue(FamilyInvite.isConnectCode(FamilyInvite.parse(text)))
        assertFalse(FamilyInvite.parse(text).direct)
    }

    @Test fun directConnectCodeRoundTrips() {
        // 显式直连码：v2 + direct:true，解析后仍取回 direct，供 connectWithCode 关内嵌走直连。
        val directCode = connectCode.copy(direct = true)
        val text = FamilyInvite.encode(directCode)
        assertTrue(text.startsWith(FamilyInvite.PREFIX))
        val parsed = FamilyInvite.parse(text)
        assertEquals(directCode, parsed)
        assertTrue(parsed.direct)
        assertTrue(FamilyInvite.isConnectCode(parsed))
    }

    @Test fun v1InviteStillParsesAsNonConnectCode() {
        // 无配对码 → 出 MMJOIN1，仍能被解析，且不被当作完整连接码。
        val text = FamilyInvite.encode(invite)
        assertTrue(text.startsWith(FamilyInvite.PREFIX_V1))
        val parsed = FamilyInvite.parse(text)
        assertEquals(invite, parsed)
        assertTrue(parsed.pairCode.isEmpty())
        assertFalse(FamilyInvite.isConnectCode(parsed))
    }

    @Test fun rawJsonV2WithPairCodeIsAccepted() {
        val json = """{"v":2,"ctrl":"https://controlplane.tailscale.com","key":"tskey-auth-kTEST1234CNTRL-abcdefghijklmnop","server":"http://100.65.248.62:18080","name":"小米14","code":"ABC123XY"}"""
        val parsed = FamilyInvite.parse(json)
        assertEquals("ABC123XY", parsed.pairCode)
        assertTrue(FamilyInvite.isConnectCode(parsed))
    }

    @Test fun connectCodeDeepLinkWorks() {
        val text = FamilyInvite.encode(connectCode)
        val link = "mymoney://join?invite=" + java.net.URLEncoder.encode(text, "UTF-8")
        assertEquals(connectCode, FamilyInvite.parse(link))
    }

    @Test fun pairCodeWithWhitespaceRejected() {
        assertThrows { FamilyInvite.parse(FamilyInvite.encodeQuiet(connectCode.copy(pairCode = "ABC 123"))) }
    }

    @Test fun detectionAcceptsMjoin2() {
        assertTrue(FamilyInvite.looksLikeInvite(FamilyInvite.encode(connectCode)))
    }

    @Test fun detectionSeparatesInviteFromPlainAddress() {
        assertTrue(FamilyInvite.looksLikeInvite(FamilyInvite.encode(invite)))
        assertTrue(FamilyInvite.looksLikeInvite("mymoney://join?invite=x"))
        assertFalse(FamilyInvite.looksLikeInvite("http://100.65.248.62:18080"))
    }

    private fun assertThrows(block: () -> Unit) {
        val failed = runCatching(block).exceptionOrNull()
        assertTrue("expected FamilyInviteException, got $failed", failed is FamilyInviteException)
    }
}
