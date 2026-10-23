package com.example.autobook

import com.example.autobook.root.RootPollPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class RootPollPolicyTest {
    @Test fun `active idle and asleep have independent delays`() {
        assertEquals(1200L, RootPollPolicy.delayMs(RootPollPolicy.Tick.ACTIVE))
        assertEquals(3000L, RootPollPolicy.delayMs(RootPollPolicy.Tick.IDLE))
        assertEquals(30000L, RootPollPolicy.delayMs(RootPollPolicy.Tick.ASLEEP))
    }

    @Test fun `idle backs off without shortening longer user intervals`() {
        assertEquals(3000L, RootPollPolicy.delayMs(RootPollPolicy.Tick.IDLE, 2000))
        assertEquals(5000L, RootPollPolicy.delayMs(RootPollPolicy.Tick.IDLE, 5000))
        assertEquals(5000L, RootPollPolicy.delayMs(RootPollPolicy.Tick.ACTIVE, 5000))
        assertEquals(1200L, RootPollPolicy.delayMs(RootPollPolicy.Tick.ACTIVE, -1))
    }
}
