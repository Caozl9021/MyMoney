package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.Backup
import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.data.CategoryGroup
import io.github.caozl9021.mymoney.data.MealRules
import io.github.caozl9021.mymoney.data.NoteSuggester
import io.github.caozl9021.mymoney.data.Reorder
import io.github.caozl9021.mymoney.ui.initialAmountText
import io.github.caozl9021.mymoney.ui.parseAmountCents
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** plan1.0 批次1：金额初值、排序、备注建议的纯逻辑。 */
class CompactSheetLogicTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private var nextId = 1L
    private fun bill(merchant: String?, note: String?, time: Long, type: String = "EXPENSE",
        category: String? = "餐饮", sub: String? = "午餐", account: String? = "微信钱包") = BillEntity(
        id = nextId++, priceCents = 1800, type = type, timeMillis = time, merchant = merchant, method = null,
        orderTail = null, sourcePkg = "pkg", strategy = "t", fingerprint = "f$nextId", createdAt = time,
        category = category, subCategory = sub, account = account, note = note)

    private fun draft(merchant: String?, time: Long, type: BillType = BillType.EXPENSE) =
        BillDraft(0, type, time, merchant, null, null, "pkg", "t")

    // ---- N1.1 ----
    @Test fun `new bill starts empty so typing replaces nothing and empty never saves as zero`() {
        assertEquals("", initialAmountText(0))
        assertEquals("19.99", initialAmountText(1999))
        assertNull(parseAmountCents(initialAmountText(0)))
        assertEquals(1250L, parseAmountCents("12.5"))
    }

    // ---- N1.3 ----
    @Test fun `reorder moves one item and ignores out of range indexes`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), Reorder.move(list, 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), Reorder.move(list, 3, 1))
        assertEquals(list, Reorder.move(list, 0, 9))
        assertEquals(list, Reorder.move(list, -1, 0))
    }

    @Test fun `backup round trip keeps custom group sub and account order`() {
        val expense = listOf(CategoryGroup("交通", listOf("打车", "公交地铁")), CategoryGroup("餐饮", listOf("晚餐", "早餐")))
        val ledger = Backup.Ledger(expense = expense, income = listOf(CategoryGroup("工资")),
            accounts = listOf("现金", "支付宝", "微信钱包"), noteTemplate = "{商户}")
        val restored = Backup.decode(Backup.encode(Backup.Snapshot(0L, emptyList(), ledger, null))).ledger!!
        assertEquals(expense, restored.expense)
        assertEquals(listOf("现金", "支付宝", "微信钱包"), restored.accounts)
    }

    // ---- N1.4 ----
    @Test fun `same merchant notes ranked by count then recency and merchant echo excluded`() {
        val history = listOf(
            bill("老王面馆", "牛肉面", at(1, 12)), bill("老王面馆", "牛肉面", at(2, 12)),
            bill("老王面馆", "炸酱面", at(3, 12)), bill("老王面馆", "老王面馆", at(4, 12)),
            bill("别家", "盖饭", at(4, 12)),
        )
        val got = NoteSuggester.suggest(draft("老王面馆", at(5, 12)), history, zone = zone).map { it.note }
        assertEquals(listOf("牛肉面", "炸酱面"), got)
    }

    @Test fun `manual entry at mealtime suggests habitual shop and dish from the same meal only`() {
        val history = listOf(
            bill("老王面馆", "牛肉面", at(20, 12)), bill("老王面馆", "牛肉面", at(21, 12, 30)),
            bill("楼下咖啡", "美式", at(21, 8), sub = "早餐"),     // breakfast: not lunch
            bill("黄焖鸡", "黄焖鸡米饭", at(22, 13), account = "支付宝"),
            bill("远古饭店", "旧菜", at(1, 12) - 200L * 86_400_000), // > 90 days ago
        )
        val got = NoteSuggester.suggest(draft(null, at(23, 12, 10)), history, zone = zone)
        assertEquals(listOf("老王面馆 · 牛肉面", "黄焖鸡 · 黄焖鸡米饭"), got.map { it.label })
        assertEquals("支付宝", got[1].account)
        assertEquals("午餐", got[0].subCategory)
    }

    @Test fun `applying a habit changes only note and keeps all user choices`() {
        val s = NoteSuggester.Suggestion("牛肉面", "老王面馆", "餐饮", "午餐", "微信钱包")
        val blank = NoteSuggester.Fields("", "", null, null, null)
        assertEquals(blank.copy(note = "牛肉面"), NoteSuggester.apply(blank, s))
        val chosen = NoteSuggester.Fields("旧", "自填店", "交通", "打车", "现金")
        assertEquals(NoteSuggester.Fields("牛肉面", "自填店", "交通", "打车", "现金"), NoteSuggester.apply(chosen, s))
    }

    @Test fun `no history means no invented suggestion and learning off keeps only template`() {
        assertTrue(NoteSuggester.suggest(draft("新店", at(5, 12)), emptyList(), zone = zone).isEmpty())
        val history = listOf(bill("老王面馆", "牛肉面", at(1, 12)))
        val off = NoteSuggester.suggest(draft("老王面馆", at(5, 12)), history, learningEnabled = false,
            templateNote = "老王面馆", zone = zone)
        assertEquals(listOf("老王面馆"), off.map { it.note })
    }

    @Test fun `current note template duplicates and income are filtered`() {
        val history = listOf(bill("公司", "十月工资", at(1, 10), type = "INCOME", category = "工资", sub = null))
        val income = NoteSuggester.suggest(draft("公司", at(5, 10), BillType.INCOME), history,
            templateNote = "十月工资", currentNote = "公司", zone = zone)
        assertEquals(listOf("十月工资"), income.map { it.note })
        // Manual income at a meal time has no habit suggestions.
        assertTrue(NoteSuggester.suggest(draft(null, at(5, 12), BillType.INCOME), history, zone = zone).isEmpty())
        assertEquals(4, MealRules.defaults.size)
    }
}
