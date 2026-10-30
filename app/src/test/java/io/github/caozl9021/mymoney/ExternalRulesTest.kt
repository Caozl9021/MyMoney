package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.*
import com.example.billkit.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExternalRulesTest {
    private val json = """{"version":1,"rules":[{"packages":["test.pay"],"kind":"ALIPAY_BILL","require":["特别详情","支付成功|交易成功"],"strategy":"测试"}],"categories":[{"type":"EXPENSE","keywords":["小面馆"],"category":"餐饮","subCategory":"三餐"}],"accounts":[{"type":"EXPENSE","keywords":["银行卡"],"account":"储蓄卡"}]}"""
    @After fun cleanup() { ExternalPageRules.install(emptyList()); DebugBus.publicationListener = null }
    @Test fun jsonOverridesBuiltinAndReportsSource() {
        val doc = ExternalRuleCodec.decode(json)
        ExternalPageRules.install(doc.pages)
        val snapshot = UiSnapshot("test.pay", null, listOf("特别详情", "交易成功", "¥12.00"))
        val match = PageClassifier().classify(snapshot)!!
        assertEquals(PageKind.ALIPAY_BILL, match.kind)
        assertTrue(match.strategy.contains("JSON_OVERRIDE"))
        assertEquals(2, doc.candidates.size)
        assertEquals(RuleSource.JSON_OVERRIDE, doc.candidates.first().source)
        assertTrue(doc.candidates.last().matchMethod)
    }
    @Test fun listsAndInputPagesCannotBeEnabledByJson() {
        ExternalPageRules.install(ExternalRuleCodec.decode(json).pages)
        for (exclude in listOf("筛选", "全部订单", "立即支付")) {
            assertNull(PageClassifier().classify(UiSnapshot("test.pay", null,
                listOf("特别详情", "交易成功", "¥12.00", exclude))))
        }
    }
    @Test fun badFilesRejectWithoutChangingInstalledPages() {
        ExternalPageRules.install(ExternalRuleCodec.decode(json).pages)
        val invalid = listOf("{", " ".repeat(65_537), "[".repeat(20) + "]".repeat(20),
            json.replace("\"strategy\":\"测试\"", "\"strategy\":\"测试\",\"regex\":\"(a+)+\""),
            json.replace("特别详情", "x".repeat(121)), json.replace("\"version\":1", "\"version\":2"))
        invalid.forEach { assertTrue(runCatching { ExternalRuleCodec.decode(it) }.isFailure) }
        assertNotNull(PageClassifier().classify(UiSnapshot("test.pay", null, listOf("特别详情", "交易成功", "¥12"))))
    }
    @Test fun atomicImportFailureAndResetPreserveExpectedRules() {
        val dir = Files.createTempDirectory("rules-override").toFile()
        try {
            val defaults = """{"version":1,"rules":[],"categories":[],"accounts":[]}"""
            ExternalRuleStore.initialize(dir, defaults)
            assertTrue(ExternalRuleStore.importJson(json).isSuccess)
            assertEquals(json, ExternalRuleStore.exportJson())
            assertTrue(ExternalRuleStore.importJson("{bad").isFailure)
            assertEquals(json, ExternalRuleStore.exportJson())
            assertEquals(json, File(dir, "rules_override.json").readText())
            assertEquals(2, ExternalRuleStore.candidates().size)
            ExternalRuleStore.reset()
            assertEquals(defaults, ExternalRuleStore.exportJson())
            assertFalse(File(dir, "rules_override.json").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun bundledSourceAndAllAnyExcludeLiteralGrammar() {
        val rule = ExternalPageRule(setOf("test.pay"), PageKind.GENERIC_BILL,
            listOf("详情|凭证", "成功"), listOf(listOf("单号", "时间")), listOf("取消"), "test")
        assertTrue(rule.matches(UiSnapshot("test.pay", null, listOf("凭证", "成功", "时间"))))
        assertFalse(rule.matches(UiSnapshot("test.pay", null, listOf("凭证", "成功", "时间", "取消"))))
        assertEquals(RuleSource.JSON_BUNDLED, ExternalRuleCodec.decode(json, RuleSource.JSON_BUNDLED).candidates.first().source)
    }
    @Test fun coverageDoesNotCountAnnotationsAndDoesNotLoseBurstPublications() {
        val counters = CoverageAccumulator()
        val time = System.currentTimeMillis()
        DebugBus.publicationListener = { counters.record(it.packageName, it.outcome, it.atMillis) }
        repeat(100) { DebugBus.publish(UiSnapshot("test.pay", null, listOf("sensitive")), "skip:no-amount:secret", time) }
        DebugBus.annotateOutcome(" → overlay")
        assertEquals(100L, counters.rows(time).single().count)
        assertFalse(counters.encode(time).contains("secret"))
        assertFalse(counters.encode(time).contains("sensitive"))
    }
    @Test fun coverageExpiresAndPersistsOnlySevenCalendarDays() {
        val counters = CoverageAccumulator()
        val now = System.currentTimeMillis(); val day = 86_400_000L
        counters.record("test.pay", "draft:123.45", now - day * 7, now)
        counters.record("test.pay", "draft:99", now - day * 6, now)
        counters.record("test.pay", "skip:duplicate", now, now)
        assertEquals(2, counters.rows(now).size)
        val restored = CoverageAccumulator(); restored.restore(counters.encode(now), now)
        assertEquals(counters.rows(now), restored.rows(now))
        assertTrue(restored.rows(now + day * 7).isEmpty())
    }
    @Test fun coverageBoundedAndFailedRestorePreservesCounters() {
        val counters = CoverageAccumulator(); val now = System.currentTimeMillis()
        repeat(600) { counters.record("test.p$it", "skip:failed:private", now) }
        assertEquals(512, counters.rows(now).size)
        assertTrue(runCatching { counters.restore("[1]", now) }.isFailure)
        assertEquals(512, counters.rows(now).size)
    }
    @Test fun sampleIndexAndDuplicateNamesKeepBothOriginals() {
        val dir = Files.createTempDirectory("sample-index").toFile()
        try {
            val state = DebugBus.State("test.pay", "Activity", "ROOT_DUMP", "skip:no-amount", "原始\n文本", 1_700_000_000_000)
            val first = SampleIndex.save(dir, state); val second = SampleIndex.save(dir, state)
            assertNotEquals(first, second)
            assertTrue(first.readText().endsWith(state.text))
            val index = File(dir, "index.txt").readText()
            assertTrue(index.contains(first.name)); assertTrue(index.contains(second.name))
            assertEquals(3, index.lineSequence().filter { it.isNotBlank() }.count())
        } finally { dir.deleteRecursively() }
    }
}
