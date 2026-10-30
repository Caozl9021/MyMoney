package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.capture.AccessibilityCaptureSchedule
import io.github.caozl9021.mymoney.capture.AccessibilityCaptureSchedule.Event
import org.junit.Assert.*
import org.junit.Test

class AccessibilityCaptureScheduleTest {
    @Test fun contentCannotPostponeWindowDeadline() {
        val policy = AccessibilityCaptureSchedule()
        assertEquals(120L, policy.offer("wechat", Event.WINDOW, 0L))
        assertEquals(120L, policy.offer("wechat", Event.CONTENT, 50L))
        assertEquals(120L, policy.offer("wechat", Event.WINDOW, 100L))
    }

    @Test fun contentStormHasOneSecondUpperBound() {
        val policy = AccessibilityCaptureSchedule()
        assertEquals(600L, policy.offer("wechat", Event.CONTENT, 0L))
        assertEquals(900L, policy.offer("wechat", Event.CONTENT, 300L))
        for (now in 500L..999L step 25L) {
            assertEquals(1000L, policy.offer("wechat", Event.CONTENT, now))
        }
        // An event coinciding with the due callback still cannot move it later.
        assertEquals(1000L, policy.offer("wechat", Event.CONTENT, 1000L))
    }

    @Test fun windowAcceleratesContentBatch() {
        val policy = AccessibilityCaptureSchedule()
        policy.offer("wechat", Event.CONTENT, 0L)
        assertEquals(170L, policy.offer("wechat", Event.WINDOW, 50L))
    }

    @Test fun completionAllowsLaterUpdatesAsNewBatch() {
        val policy = AccessibilityCaptureSchedule()
        policy.offer("wechat", Event.WINDOW, 0L)
        policy.clear() // capture finished at t=120
        assertEquals(750L, policy.offer("wechat", Event.CONTENT, 150L))
        assertFalse(policy.retrying)
    }

    @Test fun packageChangeDropsOldDeadlineAndRetry() {
        val policy = AccessibilityCaptureSchedule()
        policy.offer("wechat", Event.WINDOW, 0L)
        assertEquals(520L, policy.retry(120L))
        assertEquals(800L, policy.offer("alipay", Event.CONTENT, 200L))
        assertEquals("alipay", policy.packageName)
        assertFalse(policy.retrying)
    }

    @Test fun disableOrStopClearsEverything() {
        val policy = AccessibilityCaptureSchedule()
        policy.offer("wechat", Event.CONTENT, 0L)
        policy.retry(600L)
        policy.clear()
        assertNull(policy.packageName)
        assertNull(policy.deadline)
        assertNull(policy.retry(1000L))
        assertEquals(2120L, policy.offer("wechat", Event.WINDOW, 2000L))
    }

    @Test fun rootRetryIsOnceAndEventsCannotPostponeOrReplenishIt() {
        val policy = AccessibilityCaptureSchedule()
        policy.offer("wechat", Event.WINDOW, 0L)
        assertEquals(520L, policy.retry(120L))
        assertEquals(520L, policy.offer("wechat", Event.CONTENT, 200L))
        assertEquals(520L, policy.offer("wechat", Event.WINDOW, 300L))
        assertNull(policy.retry(520L))
        policy.clear()
        policy.offer("wechat", Event.CONTENT, 600L)
        assertEquals(1600L, policy.retry(1200L))
    }
}
