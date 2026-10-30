package com.example.billkit.spec

import org.junit.Assert.*
import org.junit.Test

class FullSampleReplayTest {
    private val samples = SpecSamples.load()
    private val engine = originalEngine()

    @Test fun `每个场景都有三个正例五个负例且来源标明`() {
        val rules = SpecAssets.parseRules(SpecAssets.classpath(SpecAssets.RULES_ALL))
        assertEquals(14, rules.size)
        assertEquals(14, engine.registeredRules())
        assertEquals(130, samples.size)
        assertEquals(48, samples.count { it.expectMatch })
        assertEquals(6, samples.count { it.provenance == "captured-redacted" })
        assertTrue(samples.all { it.provenance in setOf("synthetic", "captured-redacted") && it.evidence.isNotBlank() })
        for (rule in rules) {
            assertTrue(rule.enabled)
            assertTrue(samples.count { it.expectMatch && it.expectedRuleId == rule.systemRuleName } >= 3)
            assertEquals(5, samples.count { !it.expectMatch && it.id.startsWith(rule.systemRuleName) })
        }
    }

    @Test fun `全部原创正反例按完整预期回放`() {
        for (s in samples) {
            val result = engine.parse(s.packageName, s.type, s.data)
            if (!s.expectMatch) assertNull(s.label, result) else {
                assertNotNull(s.label, result)
                result!!
                assertEquals(s.label, s.expectedRuleId, result.ruleId)
                assertEquals(s.label, s.expectedType, result.bill.type)
                assertEquals(s.label, s.expectedCents, result.bill.priceCents)
                assertEquals(s.label, s.expectedMerchant, result.bill.shopName)
                assertEquals(s.label, "CNY", result.bill.currency)
                assertEquals(s.label, s.expectedExplicitTime, result.bill.hasExplicitTime)
                assertEquals(s.label, s.expectedTimeMillis, result.bill.time)
                assertTrue(s.label, result.bill.observedAt > 0)
            }
        }
    }

    @Test fun `坏输入与未知来源不命中`() {
        assertNull(engine.parse("com.tencent.mm", "DATA", "{"))
        assertNull(engine.parse("other.app", "DATA", samples.first().data))
        assertNull(engine.parse("com.tencent.mm", "UNKNOWN", samples.first().data))
    }
}
