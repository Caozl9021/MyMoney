package io.github.mymoney

import com.example.billkit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RepeatPageVisitTest {
    private val now = 1_792_713_660_000L
    private fun page(extra: String? = null) = UiSnapshot(Packages.WECHAT, null,
        Samples.wechatBill + listOfNotNull(extra))
    @After fun cleanup() { BillKit.reset(); DebugBus.clear() }

    @Test fun `revisit inside old ninety second window delivers again with a new observation session`() {
        val drafts = mutableListOf<BillDraft>()
        BillKit.install(BillKitConfig(confirmOnRevisit = true), { drafts += it }, { "persistent-page" })
        BillKit.offer(page(), now)
        BillKit.offer(page(), now + 2_000)
        BillKit.offer(page("其他服务"), now + 4_000)
        assertEquals(1, drafts.size)
        BillKit.endPageVisit()
        BillKit.offer(page(), now + 5_000)
        assertEquals(2, drafts.size)
        assertNotEquals(drafts[0].captureSessionId, drafts[1].captureSessionId)
        assertEquals(drafts[0].transactionKey, drafts[1].transactionKey)
    }

    @Test fun `closing card on unchanged page does not make another confirmation even after timeout`() {
        val drafts = mutableListOf<BillDraft>()
        BillKit.install(BillKitConfig(confirmOnRevisit = true), { drafts += it })
        BillKit.offer(page(), now)
        BillKit.offer(page(), now + 120_000)
        assertEquals(1, drafts.size)
        assertEquals("skip:same-page", DebugBus.state.value?.outcome)
    }

    @Test fun `switching to a different detail and back allows the previous bill immediately`() {
        val drafts = mutableListOf<BillDraft>()
        BillKit.install(BillKitConfig(confirmOnRevisit = true), { drafts += it }, { "persistent-page" })
        BillKit.offer(page(), now)
        val other = Samples.wechatBill.map { if (it == "4200001234202410211234567890") "9900001234202410211234567890" else it }
        BillKit.offer(UiSnapshot(Packages.WECHAT, null, other), now + 2_000)
        BillKit.offer(page(), now + 3_000)
        assertEquals(3, drafts.size)
        assertEquals(3, drafts.map { it.captureSessionId }.distinct().size)
    }

    @Test fun `legacy library clients retain existing deduplication`() {
        val drafts = mutableListOf<BillDraft>()
        BillKit.install(sink = { drafts += it }, pageSessionProvider = { "persistent-page" })
        BillKit.offer(page(), now)
        BillKit.endPageVisit()
        BillKit.offer(page(), now + 2_000)
        assertEquals(1, drafts.size)
    }
}
