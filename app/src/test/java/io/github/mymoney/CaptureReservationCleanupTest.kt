package io.github.mymoney

import com.example.billkit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** J-stage: reservation ownership and compatibility; no Android state or wall-clock waits. */
class CaptureReservationCleanupTest {
    private val now = 1_792_713_660_000L
    private val drafts = mutableListOf<BillDraft>()
    private val timeOnly = listOf("交易详情") + Samples.wechatBill.filterNot {
        it == "交易单号" || it == "4200001234202410211234567890"
    }

    private fun page(observation: String, extra: String? = null, items: List<String> = Samples.wechatBill) =
        UiSnapshot(Packages.WECHAT, null, items + listOfNotNull(extra),
            configurationToken = "configuration", pageObservationId = observation)

    private fun installNew(provider: ((UiSnapshot) -> String)? = null) =
        BillKit.install(BillKitConfig(confirmOnRevisit = true), { drafts += it }, provider)

    @After fun cleanup() { BillKit.reset(); DebugBus.clear() }

    @Test fun `revisit mode never calls legacy session provider for accepted or skipped pages`() {
        installNew { error("Legacy disk-backed session provider must not run") }
        BillKit.offer(page("first"), now)
        BillKit.offer(page("redraw", "其他服务"), now + 2_000)
        BillKit.offer(page("list", items = Samples.wechatListPage), now + 4_000)
        assertEquals(1, drafts.size)
        assertNotNull(drafts.single().captureSessionId)
    }

    @Test fun `revisit mode without provider deduplicates the same strong order across layout changes`() {
        installNew()
        BillKit.offer(page("first"), now)
        BillKit.offer(page("redraw", "其他服务"), now + 2_000)
        assertEquals(1, drafts.size)
        assertNotNull(drafts.single().transactionKey)
        assertEquals("skip:duplicate", DebugBus.state.value?.outcome)
    }

    @Test fun `revisit mode without provider deduplicates explicit time identity across layout changes`() {
        installNew()
        BillKit.offer(page("first", items = timeOnly), now)
        BillKit.offer(page("redraw", "其他服务", timeOnly), now + 2_000)
        assertEquals(1, drafts.size)
        assertNull(drafts.single().transactionKey)
        assertTrue(drafts.single().hasExplicitTime)
        assertEquals("skip:duplicate", DebugBus.state.value?.outcome)
    }

    @Test fun `revisit mode without provider keeps payment method in explicit time identity`() {
        installNew()
        BillKit.offer(page("first", items = timeOnly), now)
        val otherMethod = timeOnly.map { if (it == "零钱") "工商银行储蓄卡(5821)" else it }
        BillKit.offer(page("other-method", items = otherMethod), now + 2_000)
        assertEquals(2, drafts.size)
        assertNotEquals(drafts[0].method, drafts[1].method)
        assertEquals(drafts[0].fingerprint, drafts[1].fingerprint)
    }

    @Test fun `releasing an expired observation allows the next same-page observation without bypassing throttle`() {
        installNew()
        BillKit.offer(page("expired"), now)
        val expired = drafts.single()
        BillKit.retryFailedDraft(expired)
        BillKit.offer(page("too-early"), now + 100)
        assertEquals(1, drafts.size)
        assertEquals("skip:throttled", DebugBus.state.value?.outcome)
        BillKit.offer(page("current"), now + 2_000)
        assertEquals(2, drafts.size)
        assertEquals("current", drafts.last().pageObservationId)
        assertEquals(expired.captureSessionId, drafts.last().captureSessionId)
    }

    @Test fun `an old visit cannot release a newer visit reservation for the same order`() {
        installNew()
        BillKit.offer(page("old"), now)
        val old = drafts.single()
        BillKit.endPageVisit()
        BillKit.offer(page("new"), now + 2_000)
        assertEquals(2, drafts.size)
        assertNotEquals(old.captureSessionId, drafts.last().captureSessionId)
        BillKit.retryFailedDraft(old)
        BillKit.offer(page("new-redraw", "其他服务"), now + 4_000)
        assertEquals(2, drafts.size)
        assertEquals("skip:duplicate", DebugBus.state.value?.outcome)
    }

    @Test fun `a late second release from an old observation cannot clear its same-visit successor`() {
        installNew()
        BillKit.offer(page("old"), now)
        val old = drafts.single()
        BillKit.retryFailedDraft(old)
        BillKit.offer(page("new"), now + 2_000)
        assertEquals(2, drafts.size)
        assertEquals(old.captureSessionId, drafts.last().captureSessionId)
        BillKit.retryFailedDraft(old)
        BillKit.offer(page("redraw", "其他服务"), now + 4_000)
        assertEquals(2, drafts.size)
        assertEquals("skip:duplicate", DebugBus.state.value?.outcome)
    }

    @Test fun `legacy mode still calls provider and uses its continuity token`() {
        var calls = 0
        BillKit.install(sink = { drafts += it }, pageSessionProvider = { calls++; "legacy-session" })
        BillKit.offer(page("first"), now)
        BillKit.offer(page("redraw", "其他服务"), now + 2_000)
        assertEquals(2, calls)
        assertEquals(1, drafts.size)
        assertEquals("legacy-session", drafts.single().captureSessionId)
    }

    @Test fun `legacy mode without provider retains fingerprint deduplication`() {
        BillKit.install(sink = { drafts += it })
        BillKit.offer(page("first", items = timeOnly), now)
        val otherMethod = timeOnly.map { if (it == "零钱") "工商银行储蓄卡(5821)" else it }
        BillKit.offer(page("other-method", items = otherMethod), now + 2_000)
        assertEquals(1, drafts.size)
        assertEquals("skip:duplicate", DebugBus.state.value?.outcome)
    }
}
