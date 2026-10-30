package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocalRulesTest {
    private val groups = listOf(CategoryGroup("餐饮", listOf("三餐", "早餐", "午餐", "晚餐", "夜宵", "咖啡茶饮", "零食")), CategoryGroup("购物", listOf("日用品")), CategoryGroup("交通"))
    private val accounts = listOf("现金", "支付宝", "储蓄卡")
    private fun draft(merchant: String = "沙县小吃", hour: Int = 12, minute: Int = 30) = BillDraft(1900, BillType.EXPENSE,
        LocalDateTime.of(2026, 10, 22, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), merchant, "支付宝", null, "sample", "")
    private fun suggest(d: BillDraft, s: LocalRuleSnapshot = LocalRuleSnapshot(), c: List<RuleCandidate> = emptyList()) = CategorySuggester.suggest(d, groups, listOf(CategoryGroup("工资")), accounts, s, c)
    @Test fun `bill time defines lunch and night boundaries`() {
        assertEquals("午餐", suggest(draft()).subCategory)
        assertEquals("早餐", suggest(draft(hour=10, minute=29)).subCategory)
        assertEquals("午餐", suggest(draft(hour=10, minute=30)).subCategory)
        assertEquals("夜宵", suggest(draft(hour=21, minute=0)).subCategory)
        assertEquals("夜宵", suggest(draft(hour=4, minute=59)).subCategory)
        assertEquals("三餐", suggest(draft(hour=15)).subCategory)
    }
    @Test fun `explicit meal precedes time and coffee stays coffee`() {
        assertEquals("早餐", suggest(draft("早餐店", 19)).subCategory)
        assertEquals("咖啡茶饮", suggest(draft("瑞幸咖啡", 12)).subCategory)
        assertNull(suggest(draft("未知商户")).category)
    }
    @Test fun `manual one time category changes take effect independently of account`() {
        val original = draft().copy(category="餐饮",subCategory="午餐",account="支付宝")
        val s = RuleLearning.record(LocalRuleSnapshot(), original, original.copy(category="购物",subCategory="日用品"))
        assertEquals(1, s.learned.size)
        assertEquals("购物", suggest(draft(), s).category)
        assertEquals("支付宝", suggest(draft(), s).account)
    }
    @Test fun `three accepts promote but two do not`() {
        val d = draft().copy(category="购物",subCategory="日用品",account="支付宝")
        val one = RuleLearning.record(LocalRuleSnapshot(),d,d)
        val two = RuleLearning.record(one,d,d)
        assertTrue(two.learned.isEmpty())
        val three = RuleLearning.record(two,d,d)
        assertEquals(2,three.learned.size)
        assertTrue(three.learned.all { it.source == RuleSource.LEARNED })
        assertEquals("购物", suggest(draft(),three).category)
    }
    @Test fun `changed accepted result resets consecutive count`() {
        val d = draft().copy(category="购物",subCategory="日用品")
        var s = RuleLearning.record(LocalRuleSnapshot(),d,d)
        s = RuleLearning.record(s,d,d)
        val different = d.copy(category="交通",subCategory=null)
        s = RuleLearning.record(s,different,different)
        s = RuleLearning.record(s,d,d)
        assertTrue(s.learned.isEmpty())
    }
    @Test fun `weak meals do not become pinned lunch`() {
        val d = draft().copy(category="餐饮",subCategory="午餐")
        var s = LocalRuleSnapshot()
        repeat(3) { s=RuleLearning.record(s,d,d) }
        assertNull(s.learned.single().subCategory)
        assertEquals("晚餐",suggest(draft(hour=19),s).subCategory)
    }
    @Test fun `manual meal is pinned over time and stays strong`() {
        val d = draft().copy(category="餐饮",subCategory="午餐")
        var s = RuleLearning.record(LocalRuleSnapshot(),d,d.copy(subCategory="早餐"))
        repeat(3) { s=RuleLearning.record(s,d,d) }
        assertEquals("早餐",suggest(draft(hour=19),s).subCategory)
        assertEquals(RuleSource.MANUAL,s.learned.single().source)
    }
    @Test fun `account learning needs same method and never crosses type`() {
        val d = draft().copy(account="支付宝")
        val s = RuleLearning.record(LocalRuleSnapshot(),d,d.copy(account="现金"))
        assertEquals("现金",suggest(draft(),s).account)
        assertEquals("储蓄卡",suggest(draft().copy(method="工商银行储蓄卡"),s).account)
        assertEquals("支付宝",suggest(draft().copy(type=BillType.INCOME),s).account)
        assertEquals("支付宝",suggest(draft().copy(type=BillType.REFUND),s).account)
    }
    @Test fun `missing merchant and disabled learning have no effect`() {
        val d = draft().copy(merchant=null,category="购物")
        assertEquals(LocalRuleSnapshot(), RuleLearning.record(LocalRuleSnapshot(),d,d))
        val disabled = LocalRuleSnapshot(learningEnabled=false)
        assertEquals(disabled,RuleLearning.record(disabled,draft(),draft().copy(category="购物")))
    }
    @Test fun `historical edits do not add frequency accepts`() {
        val d=draft().copy(category="购物")
        assertTrue(RuleLearning.record(LocalRuleSnapshot(),d,d,allowFrequency=false).pending.isEmpty())
    }
    @Test fun `normalization removes only explicit parenthesized branch suffix`() {
        assertEquals("星巴克",RuleLearning.normalizeMerchant(" 星 巴克（东富路店） "))
        assertEquals("星巴克东富路店",RuleLearning.normalizeMerchant("星巴克东富路店"))
        assertEquals("星巴克(中国)",RuleLearning.normalizeMerchant("星巴克(中国)"))
    }
    @Test fun `manual override bundled learned builtin resolve independently`() {
        val d=draft().copy(category="餐饮",account="支付宝")
        val s=RuleLearning.record(LocalRuleSnapshot(),d,d.copy(account="现金"))
        val c=listOf(RuleCandidate(BillType.EXPENSE,"沙县",category="购物",source=RuleSource.JSON_OVERRIDE),RuleCandidate(BillType.EXPENSE,"沙县小吃",category="交通",source=RuleSource.JSON_BUNDLED))
        val hit=suggest(d,s,c)
        assertEquals("购物",hit.category)
        assertEquals("现金",hit.account)
        assertEquals(RuleSource.JSON_OVERRIDE,hit.categoryHit?.source)
        assertEquals(RuleSource.MANUAL,hit.accountHit?.source)
    }
    @Test fun `specific actual matched text wins stable ties`() {
        val candidates=listOf(RuleCandidate(BillType.EXPENSE,"沙县",category="交通"),RuleCandidate(BillType.EXPENSE,"沙县小吃",category="购物"))
        assertEquals("购物",suggest(draft(),c=candidates).category)
        assertEquals("交通",suggest(draft(),c=listOf(candidates[0],candidates[0].copy(category="购物"))).category)
    }
    @Test fun `deleted taxonomy is ignored and disabled learned ignored`() {
        val rule=LearnedRule("x",BillType.EXPENSE,"沙县小吃",category="餐饮",subCategory="删除分类")
        assertEquals("午餐",suggest(draft(),LocalRuleSnapshot(learned=listOf(rule))).subCategory)
        val valid=rule.copy(category="购物",subCategory="日用品")
        assertEquals("午餐",suggest(draft(),LocalRuleSnapshot(learned=listOf(valid),learningEnabled=false)).subCategory)
    }
    @Test fun `explicit manual three meals and JSON takeout survive time`() {
        val d=draft().copy(category="餐饮",subCategory="午餐")
        val manual=RuleLearning.record(LocalRuleSnapshot(),d,d.copy(subCategory="三餐"))
        assertEquals("三餐",suggest(draft(),manual).subCategory)
        val expanded=groups.map { if(it.name=="餐饮") it.copy(subs=it.subs+"外卖") else it }
        val candidate=RuleCandidate(BillType.EXPENSE,"沙县",category="餐饮",subCategory="外卖")
        assertEquals("外卖",CategorySuggester.suggest(draft(),expanded,emptyList(),accounts,LocalRuleSnapshot(),listOf(candidate)).subCategory)
    }
    @Test fun `meal validation rejects invalid overlapping equal windows`() {
        listOf(listOf(MealWindow("早餐","5:00","10:00")),listOf(MealWindow("早餐","05:00","05:00")),
            listOf(MealWindow("夜宵","21:00","05:00"),MealWindow("早餐","04:00","10:00"))).forEach {
            assertTrue(runCatching { MealRules.validate(it) }.isFailure)
        }
        MealRules.validate(MealRules.defaults)
    }
    @Test fun `codec roundtrip preserves all rule configuration`() {
        val d=draft().copy(category="餐饮",subCategory="午餐",account="支付宝")
        val s=RuleLearning.record(LocalRuleSnapshot(),d,d.copy(account="现金"),now=42)
        assertEquals(s,LocalRuleCodec.decode(LocalRuleCodec.encode(s)))
        val snapshot=Backup.Snapshot(42, emptyList(),Backup.Ledger(groups,emptyList(),accounts,"{商户}",s),null)
        assertEquals(s,Backup.decode(Backup.encode(snapshot)).ledger?.localRules)
    }
    @Test fun `old backup leaves optional rule configuration absent`() {
        val snapshot=Backup.Snapshot(42,emptyList(),Backup.Ledger(groups,emptyList(),accounts,"{商户}"),null)
        assertNull(Backup.decode(Backup.encode(snapshot)).ledger?.localRules)
    }
}
