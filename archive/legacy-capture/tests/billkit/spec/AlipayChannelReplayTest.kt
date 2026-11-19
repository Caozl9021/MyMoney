package com.example.billkit.spec

import org.junit.Assert.*
import org.junit.Test

class AlipayChannelReplayTest {
    private val engine = originalEngine()
    @Test fun `结构化成功交易的方向金额与商户正确`() {
        val samples = SpecSamples.load().filter { it.packageName == "com.eg.android.AlipayGphone" && it.type == "DATA" }
        assertEquals(24, samples.size)
        for (s in samples) {
            val hit = engine.parse(s.packageName, s.type, s.data)
            if (s.expectMatch) {
                assertNotNull(s.label, hit)
                assertEquals(s.expectedType, hit!!.bill.type)
                assertEquals(s.expectedCents, hit.bill.priceCents)
                assertEquals(s.expectedMerchant, hit.bill.shopName)
                assertEquals(1793289600000L, hit.bill.observedAt)
                assertEquals(0L, hit.bill.time)
            } else assertNull(s.label, hit)
        }
    }
    @Test fun `多条推送必须先拆分而不是只吃第一条`() {
        val s = SpecSamples.load().first { it.packageName == "com.eg.android.AlipayGphone" && it.type == "DATA" && it.expectMatch }
        val item = SpecJson.decodeArray(s.data).single()
        val combined = SpecJson.encode(listOf(item, item))
        assertNull(engine.parse(s.packageName, s.type, combined))
        assertEquals(2, SpecHookPayload.alipaySplit(combined).size)
        SpecHookPayload.alipaySplit(combined).forEach { assertNotNull(engine.parse(s.packageName, s.type, it)) }
    }
    @Test fun `坏嵌套JSON不生成交易`() {
        assertNull(engine.parse("com.eg.android.AlipayGphone", "DATA", "[{\"pl\":\"bad json\",\"mct\":1793289600}]"))
    }
}
