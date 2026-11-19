package com.example.billkit.spec

import org.junit.Assert.*
import org.junit.Test

class NoticeChannelReplayTest {
    private val engine = originalEngine()
    @Test fun `四通知场景逐条回放且失败汇总不得复活`() {
        val samples = SpecSamples.load().filter { it.type == "NOTICE" }
        assertEquals(32, samples.size)
        assertEquals(12, samples.count { it.expectMatch })
        for (s in samples) {
            val hit = engine.parse(s.packageName, s.type, s.data)
            if (s.expectMatch) {
                assertNotNull(s.label, hit)
                assertEquals(s.expectedCents, hit!!.bill.priceCents)
                assertEquals(s.expectedType, hit.bill.type)
                assertFalse(hit.bill.hasExplicitTime)
            } else assertNull(s.label, hit)
        }
    }
    @Test fun `只有文字没有宿主信封验证不准入`() {
        val raw = SpecJson.encode(mapOf("title" to "微信支付", "text" to "已支付¥19.99", "t" to 1793289600000L))
        assertNull(engine.parse("com.tencent.mm", "NOTICE", raw))
    }
    @Test fun `未知商户不以通道名代填`() {
        val sample = SpecSamples.load().first { it.type == "NOTICE" && it.expectMatch }
        assertEquals("", engine.parse(sample.packageName, sample.type, sample.data)!!.bill.shopName)
    }
}
