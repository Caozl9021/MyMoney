package io.github.mymoney

import io.github.mymoney.data.rulesync.RuleSyncApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class RuleSyncApiTest {
    @Test
    fun privateLanHttpHostsAreAllowed() {
        assertTrue(RuleSyncApi.isPrivateHttpHost("127.0.0.1"))
        assertTrue(RuleSyncApi.isPrivateHttpHost("localhost"))
        assertTrue(RuleSyncApi.isPrivateHttpHost("192.168.31.90"))
        assertTrue(RuleSyncApi.isPrivateHttpHost("10.0.0.2"))
        assertTrue(RuleSyncApi.isPrivateHttpHost("172.16.0.1"))
        assertTrue(RuleSyncApi.isPrivateHttpHost("172.31.255.254"))
    }

    @Test
    fun publicOrMalformedHostsAreRejected() {
        assertFalse(RuleSyncApi.isPrivateHttpHost("example.com"))
        assertFalse(RuleSyncApi.isPrivateHttpHost("8.8.8.8"))
        assertFalse(RuleSyncApi.isPrivateHttpHost("172.32.0.1"))
        assertFalse(RuleSyncApi.isPrivateHttpHost("192.169.1.1"))
        assertFalse(RuleSyncApi.isPrivateHttpHost("999.1.1.1"))
    }

    @Test
    fun fixedTailscaleEndpointAndExistingPoliciesAreAllowed() {
        listOf(
            "http://100.65.248.62:18080", "http://100.65.248.62:18080/v1/pair",
            "http://192.168.31.90:18080", "http://10.0.0.2", "http://172.16.0.1:8080",
            "http://localhost:18080", "http://127.0.0.1:18080", "https://example.com",
        ).forEach { assertTrue(it, RuleSyncApi.isAllowedServiceUrl(URL(it))) }
    }

    @Test
    fun tailscaleExceptionDoesNotAllowOtherEndpointsOrUrlCredentials() {
        listOf(
            "http://100.65.248.61:18080", "http://100.65.248.63:18080",
            "http://100.64.0.1:18080", "http://100.65.248.62:8080",
            "http://100.65.248.62", "http://100.65.248.62:80",
            "http://100.65.248.62.example.com:18080", "http://1000.65.248.62:18080",
            "http://100.65.248.62@evil.example:18080", "http://user@100.65.248.62:18080",
            "http://100.65.248.62:18080/#fragment", "https://user@example.com",
            "https://example.com/#fragment", "ftp://100.65.248.62:18080", "http://8.8.8.8",
        ).forEach { assertFalse(it, RuleSyncApi.isAllowedServiceUrl(URL(it))) }
    }
}
