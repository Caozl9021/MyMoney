package com.example.billkit.spec

import com.example.billkit.BillType
import com.example.billkit.TransactionIdentity
import org.junit.Assert.*
import org.junit.Test

/** 独立业务边界测试，不依赖随包规则的原有数量与名称。 */
class SpecRuntimeSafetyTest {
    private val now = 1_792_000_000_000L
    private fun bill() = SpecRuleObject(type = "Expend", money = 19.99, currency = "CNY", observedAt = now)
    private fun draft(value: SpecRuleObject) = SpecDraftAdapter.toDraft(value, "test.app", "original", now)
    private fun rule(id: String, expression: String, enabled: Boolean = true) = SpecRuleModel(
        "test.app", "DATA", id, id, creator = "maimi", enabled = enabled,
        js = "var $id={get:function(raw){$expression}};",
    )

    @Test fun `decimal amount and missing business time remain precise and nonexplicit`() {
        val result = draft(bill())!!
        assertEquals(1999L, result.priceCents)
        assertEquals(now, result.timeMillis)
        assertFalse(result.hasExplicitTime)
        assertNull(result.dedupKey)
        assertEquals(1L, draft(bill().copy(money = 0.01))!!.priceCents)
    }

    @Test fun `bad type currency amount fee and incomplete transfer cannot become expense`() {
        listOf(
            bill().copy(type = "unknown"), bill().copy(type = "Transfer"),
            bill().copy(currency = "USD"), bill().copy(currency = ""),
            bill().copy(money = -19.99), bill().copy(money = Double.NaN),
            bill().copy(money = Double.POSITIVE_INFINITY), bill().copy(money = 0.0),
            bill().copy(money = 1.001), bill().copy(money = 1_000_001.0),
            bill().copy(fee = 0.01), bill().copy(accountNameTo = "unknown destination"),
        ).forEach { assertNull(it.toString(), draft(it)) }
    }

    @Test fun `refund is independent and valid full order has strong identity`() {
        val result = draft(bill().copy(type = "Refund", orderId = "ORDER-20261029-001"))!!
        assertEquals(BillType.REFUND, result.type)
        assertEquals(TransactionIdentity.key("test.app", BillType.REFUND, "ORDER-20261029-001"), result.transactionKey)
        assertNull(draft(bill().copy(orderId = "not an order")))
    }

    @Test fun `explicit bad time rejected and observation time never becomes weak merge identity`() {
        assertNull(draft(bill().copy(time = now + 60_001, hasExplicitTime = true)))
        assertNull(draft(bill().copy(time = now - 4L * 365 * 86_400_000, hasExplicitTime = true)))
        assertNull(draft(bill().copy(time = now, hasExplicitTime = false)))
        assertNull(draft(bill().copy(time = 0, hasExplicitTime = true)))
        val result = draft(bill().copy(time = now - 1000, hasExplicitTime = true))!!
        assertTrue(result.hasExplicitTime)
        assertNotNull(result.dedupKey)
    }

    @Test fun `string amount is whole matched rather than stripped out of arbitrary text`() {
        assertTrue(SpecRuleObject.fromJson(mapOf("money" to "paid 19.99 yuan")).money.isNaN())
        assertTrue(SpecRuleObject.fromJson(mapOf("money" to "19.99junk")).money.isNaN())
        assertEquals(19.99, SpecRuleObject.fromJson(mapOf("money" to "19.99")).money, 0.0)
    }

    @Test fun `invalid truthy output and throwing rule do not hide later valid rule`() {
        val engine = SpecRuleEngine()
        engine.rebuild("var common={};", listOf(
            rule("empty", "return {};"), rule("broken", "throw Error('fixture');"),
            rule("valid", "return {type:'Expend',money:19.99,currency:'CNY',time:0,observedAt:$now,hasExplicitTime:false};"),
        ))
        assertEquals(3, engine.registeredRules())
        val result = engine.parse("test.app", "DATA", "{}")!!
        assertEquals("valid", result.ruleId)
        assertEquals(0L, result.bill.time)
        assertFalse(result.bill.hasExplicitTime)
        assertEquals(SpecRuleEngine.Outcome.MATCHED, engine.lastOutcome)
    }

    @Test fun `zero enabled rules is a valid explicitly empty capability engine`() {
        val engine = SpecRuleEngine()
        engine.rebuild("var common={};", listOf(rule("disabled", "return {};", false)))
        assertTrue(engine.isReady())
        assertEquals(0, engine.registeredRules())
        assertNull(engine.parse("test.app", "DATA", "{}"))
    }

    @Test fun `failed candidate rebuild preserves last valid scope`() {
        val engine = SpecRuleEngine()
        engine.rebuild("var common={};", listOf(rule("valid", "return {type:'Income',money:1,currency:'CNY'};")))
        runCatching { engine.rebuild("var common={};", listOf(rule("bad", "return {};" ).copy(js = "invalid syntax (("))) }
            .onSuccess { fail("bad build accepted") }
        assertEquals("valid", engine.parse("test.app", "DATA", "{}")!!.ruleId)
        assertEquals(1, engine.registeredRules())
    }

    @Test fun `execution errors and rejected output are observable separately from no match`() {
        val engine = SpecRuleEngine()
        engine.rebuild("var common={};", listOf(rule("broken", "throw Error('fixture');")))
        assertNull(engine.parse("test.app", "DATA", "{}"))
        assertEquals(SpecRuleEngine.Outcome.ERROR, engine.lastOutcome)
        engine.rebuild("var common={};", listOf(rule("empty", "return {};")))
        assertNull(engine.parse("test.app", "DATA", "{}"))
        assertEquals(SpecRuleEngine.Outcome.REJECTED, engine.lastOutcome)
    }
    @Test fun `checksum valid but malformed suggestion tables are rejected`() {
        val source = (SpecRuleBundle.FILES.map { "spec/$it" } + SpecRuleBundle.MANIFEST)
            .associateWith { SpecAssets.classpath(it) }
        assertTrue(SpecRuleBundle.load { source.getValue(it) }.rules.isNotEmpty())
        fun damaged(name: String, json: String): Map<String, String> {
            val files = source.toMutableMap()
            files["spec/$name"] = json
            val manifest = SpecJson.decodeObject(files.getValue(SpecRuleBundle.MANIFEST))!!.toMutableMap()
            val hashes = (manifest["files"] as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
            hashes[name] = java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            manifest["files"] = hashes
            files[SpecRuleBundle.MANIFEST] = SpecJson.encode(manifest)
            return files
        }
        listOf(
            damaged("categories.json", "[{\"id\":1,\"name\":\"valid\"},{}]"),
            damaged("assets.json", "[{\"id\":1,\"name\":\"x\",\"currency\":\"USD\"}]"),
            damaged("assets.json", "[]"),
            damaged("categories.json", "[{\"id\":1,\"name\":\"a\"},{\"id\":1,\"name\":\"b\"}]"),
        ).forEach { files -> assertTrue(runCatching { SpecRuleBundle.load { files.getValue(it) } }.isFailure) }
    }

    @Test fun `concurrent detailed parse keeps each outcome beside its own result`() {
        val engine = SpecRuleEngine().apply { rebuild("var common={};", listOf(rule("mixed",
            "if(raw==='bad')throw Error('fixture');return {type:'Expend',money:1,currency:'CNY'};"))) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 100).map { i -> pool.submit<Boolean> {
                val isBad = i % 2 == 0
                val parsed = engine.parseDetailed("test.app", "DATA", if (isBad) "bad" else "good")
                if (isBad) parsed.result == null && parsed.outcome == SpecRuleEngine.Outcome.ERROR
                else parsed.result != null && parsed.outcome == SpecRuleEngine.Outcome.MATCHED
            } }
            futures.forEach { assertTrue(it.get()) }
        } finally { pool.shutdownNow() }
    }

}
