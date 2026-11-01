package io.github.mymoney

import io.github.mymoney.data.rulesync.RuleSyncApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
