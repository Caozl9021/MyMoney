package io.github.caozl9021.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LearningRulesTest {
    private fun rule(merchant: String, type: BillType = BillType.EXPENSE, category: String? = "餐饮",
        sub: String? = "午餐", account: String? = "支付宝", method: String? = "支付宝",
        source: RuleSource = RuleSource.MANUAL, hits: Int = 1, at: Long = 1_000L) =
        LearnedRule(MiniJson.encode(listOf(type.name, merchant, "category", method ?: "")), type, merchant, method,
            category, sub, account, source, hits, at)

    private val rules = listOf(
        rule("沙县小吃", hits = 3, at = 300L),
        rule("瑞幸咖啡", category = "餐饮", sub = "咖啡茶饮", hits = 1, at = 500L),
        rule("京东商城", category = "购物", sub = "日用品", account = "储蓄卡", method = "银行卡",
            source = RuleSource.LEARNED, hits = 3, at = 400L),
        rule("工资入账", type = BillType.INCOME, category = "工资", sub = null, account = "储蓄卡",
            method = "银行卡", hits = 2, at = 200L),
    )

    @Test fun emptyQueryKeepsEverything() {
        assertEquals(4, LearningRules.filter(rules, LearningQuery()).size)
        assertEquals(4, LearningRules.filter(rules, LearningQuery(text = "   ")).size)
    }

    @Test fun textMatchesMerchantAndMethodAcrossFields() {
        assertEquals(listOf("沙县小吃"), LearningRules.filter(rules, LearningQuery(text = "沙县")).map { it.merchant })
        // 商户名与支付方式都在 ALL 范围内
        assertTrue(LearningRules.filter(rules, LearningQuery(text = "银行卡")).isNotEmpty())
        assertTrue(LearningRules.filter(rules, LearningQuery(text = "不存在")).isEmpty())
    }

    @Test fun fieldScopeNarrowsTheMatch() {
        // "日用品" 只在分类里；按账户字段搜不到，按分类字段能搜到。
        assertTrue(LearningRules.filter(rules, LearningQuery(text = "日用品", field = LearningField.ACCOUNT)).isEmpty())
        assertEquals(listOf("京东商城"),
            LearningRules.filter(rules, LearningQuery(text = "日用品", field = LearningField.CATEGORY)).map { it.merchant })
        assertEquals(listOf("京东商城", "工资入账"),
            LearningRules.filter(rules, LearningQuery(text = "储蓄卡", field = LearningField.ACCOUNT)).map { it.merchant })
    }

    @Test fun whitespaceAndCaseAreIgnored() {
        val english = listOf(rule("Luckin Coffee", method = "Alipay"))
        assertEquals(1, LearningRules.filter(english, LearningQuery(text = "luckincoffee")).size)
        // 中文输入法下常带全角空格，检索不应因此失灵
        val spaced = listOf(rule("瑞幸 咖啡"))
        assertEquals(1, LearningRules.filter(spaced, LearningQuery(text = "瑞幸 咖啡")).size)
        assertEquals(1, LearningRules.filter(spaced, LearningQuery(text = "瑞幸咖啡")).size)
    }

    @Test fun typeAndSourceFiltersAreIndependent() {
        assertEquals(listOf("工资入账"),
            LearningRules.filter(rules, LearningQuery(type = BillType.INCOME)).map { it.merchant })
        assertEquals(listOf("京东商城"),
            LearningRules.filter(rules, LearningQuery(source = RuleSource.LEARNED)).map { it.merchant })
        // 两个筛选同时生效时取交集
        assertTrue(LearningRules.filter(rules, LearningQuery(type = BillType.INCOME, source = RuleSource.LEARNED)).isEmpty())
    }

    @Test fun orderIsHitsThenRecencyThenMerchant() {
        val sorted = LearningRules.filter(rules, LearningQuery())
        // 命中次数优先：3 次的「京东商城」排在 3 次但更早更新的「沙县小吃」之前
        assertEquals(listOf("京东商城", "沙县小吃", "工资入账", "瑞幸咖啡"), sorted.map { it.merchant })
        assertEquals(3, sorted[0].hits)
        assertEquals(3, sorted[1].hits)
        assertTrue("同次数时最近更新的在前", sorted[0].updatedAt > sorted[1].updatedAt)
    }

    @Test fun progressExplainsWhyARuleHasNotPromoted() {
        assertEquals("已累计 1/3 次，还需 2 次", LearningRules.progress(PendingLearning("k", "v", 1)))
        assertEquals("已累计 2/3 次，还需 1 次", LearningRules.progress(PendingLearning("k", "v", 2)))
        assertEquals("已累计 3/3 次，还需 0 次", LearningRules.progress(PendingLearning("k", "v", 3)))
    }

    @Test fun promotionThresholdMatchesActualLearningBehaviour() {
        val draft = BillDraft(1900, BillType.EXPENSE,
            LocalDateTime.of(2026, 10, 22, 12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            "沙县小吃", "支付宝", null, "sample", "")
            .copy(category = "购物", subCategory = "日用品", account = "支付宝")
        // 用真实 reducer 验证阈值常量没有和实现漂移。
        var state = LocalRuleSnapshot()
        repeat(LearningRules.PROMOTION_THRESHOLD - 1) { state = RuleLearning.record(state, draft, draft) }
        assertTrue("阈值前不应生效", state.learned.isEmpty())
        assertEquals(LearningRules.PROMOTION_THRESHOLD - 1, state.pending.first().count)
        state = RuleLearning.record(state, draft, draft)
        assertFalse("达到阈值应生效", state.learned.isEmpty())
        // reducer 会把 3/3 计数写回 pending 以便下次续算，因此"待生效"展示必须过滤已提升项，
        // 否则界面会同时说"已生效"和"还需 0 次"。
        assertTrue(state.pending.any { it.count >= LearningRules.PROMOTION_THRESHOLD })
        assertTrue("已生效项不再出现在待生效列表",
            LearningRules.effectivePending(state).none { it.count >= LearningRules.PROMOTION_THRESHOLD })
    }

    @Test fun effectivePendingKeepsCountsBelowThreshold() {
        val state = LocalRuleSnapshot(
            learned = listOf(rule("沙县小吃")),
            pending = listOf(PendingLearning("k1", "v", 2)),
        )
        assertEquals(listOf("k1"), LearningRules.effectivePending(state).map { it.key })
    }

    @Test fun effectivePendingHidesOnlyPromotedKeys() {
        val learnedRule = rule("沙县小吃")
        val state = LocalRuleSnapshot(
            learned = listOf(learnedRule),
            // 同一 key 但计数未达阈值：说明是升级前的残留，仍应提示还需次数。
            pending = listOf(PendingLearning(learnedRule.id, "v", 1), PendingLearning("other", "v", 3)),
        )
        val shown = LearningRules.effectivePending(state)
        assertEquals(listOf(learnedRule.id, "other"), shown.map { it.key })
    }

    @Test fun pendingLabelsDecodeCategoryPairsWithoutLeakingJson() {
        val category = PendingLearning("k", MiniJson.encode(listOf("餐饮", "午餐")), 2)
        assertEquals(listOf("餐饮", "午餐"), LearningRules.pendingLabels(category))
        val account = PendingLearning("k", "支付宝", 2)
        assertEquals(listOf("支付宝"), LearningRules.pendingLabels(account))
        // 坏数据不猜、不抛异常，原样返回
        assertEquals(listOf("[坏"), LearningRules.pendingLabels(PendingLearning("k", "[坏", 1)))
    }

    // ---------------------------------------------------------------- pending 清除语义

    @Test fun clearPendingKeepsLearnedRules() {
        val state = LocalRuleSnapshot(
            learned = listOf(rule("沙县小吃"), rule("瑞幸咖啡")),
            pending = listOf(PendingLearning("k1", "v", 2)),
        )
        val cleared = LearningRules.clearPending(state)
        assertEquals(2, cleared.learned.size)
        assertTrue(cleared.pending.isEmpty())
    }

    @Test fun deletePendingRemovesOnlyThatKey() {
        val state = LocalRuleSnapshot(
            learned = listOf(rule("沙县小吃")),
            pending = listOf(PendingLearning("k1", "v", 1), PendingLearning("k2", "v", 2)),
        )
        val after = LearningRules.deletePending(state, "k1")
        assertEquals(listOf("k2"), after.pending.map { it.key })
        assertEquals(state.learned, after.learned)
        // 不存在的 key 是空操作，不清空其他项
        assertEquals(2, LearningRules.deletePending(state, "missing").pending.size)
    }

    @Test fun clearPendingDoesNotChangeLearningSwitchOrMeals() {
        val meals = MealRules.defaults
        val state = LocalRuleSnapshot(mealWindows = meals, learningEnabled = false,
            pending = listOf(PendingLearning("k", "v", 1)))
        val cleared = LearningRules.clearPending(state)
        assertFalse(cleared.learningEnabled)
        assertEquals(meals, cleared.mealWindows)
    }
}
