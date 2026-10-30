package io.github.caozl9021.mymoney

import com.example.billkit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RecognitionCaptureIdentityTest {
    private val minimal = listOf("支付成功", "测试商户（*某）", "¥0.01", "完成")
    private val t0 = 1_792_713_660_000L
    private fun page(items: List<String>) = UiSnapshot(Packages.WECHAT, null, items)
    @After fun cleanup() { BillKit.reset(); DebugBus.clear() }

    @Test fun `full order IDs distinguish equal tails without changing legacy fingerprint`() {
        val pipeline = BillPipeline()
        val first = (pipeline.process(page(Samples.wechatBill), t0) as BillPipeline.Outcome.Draft).draft
        val other = Samples.wechatBill.map { if (it == "4200001234202410211234567890") "9900001234202410211234567890" else it }
        val second = (pipeline.process(page(other), t0) as BillPipeline.Outcome.Draft).draft
        assertEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(first.transactionKey, second.transactionKey)
        assertEquals(64, first.transactionKey!!.length)
        assertTrue(first.hasExplicitTime)
        assertFalse((pipeline.process(page(minimal), t0) as BillPipeline.Outcome.Draft).draft.hasExplicitTime)
    }

    @Test fun `observed intermediate page allows same amount within same minute`() {
        val drafts = mutableListOf<BillDraft>()
        var token = "first"
        val observed = mutableListOf<List<String>>()
        BillKit.install(sink = { drafts += it }, pageSessionProvider = { observed += it.items; token })
        BillKit.offer(page(minimal), t0)
        token = "between"
        BillKit.offer(page(listOf("首页")), t0 + 2_000)
        token = "second"
        BillKit.offer(page(minimal), t0 + 4_000)
        assertEquals(2, drafts.size)
        assertEquals(listOf("first", "second"), drafts.map { it.captureSessionId })
        assertEquals(3, observed.size)
    }

    @Test fun `failed reservation can retry while unrelated reservations and throttle remain`() {
        val drafts = mutableListOf<BillDraft>()
        BillKit.install(sink = { drafts += it }, pageSessionProvider = { "session" })
        BillKit.offer(page(minimal), t0)
        BillKit.retryFailedDraft(drafts.single())
        BillKit.offer(page(minimal), t0 + 100)
        assertEquals("skip:throttled", DebugBus.state.value?.outcome)
        BillKit.offer(page(minimal), t0 + 2_000)
        assertEquals(2, drafts.size)
        BillKit.offer(page(minimal), t0 + 120_000)
        assertEquals(2, drafts.size)
    }

    @Test fun `strong order identity suppresses different page layout in one process`() {
        val drafts = mutableListOf<BillDraft>()
        BillKit.install(sink = { drafts += it }, pageSessionProvider = { it.items.hashCode().toString() })
        BillKit.offer(page(Samples.wechatBill), t0)
        BillKit.offer(page(Samples.wechatBill + "其他服务"), t0 + 2_000)
        assertEquals(1, drafts.size)
        assertEquals("skip:duplicate", DebugBus.state.value?.outcome)
    }
}
