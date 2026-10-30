package io.github.mymoney

import io.github.mymoney.data.CategoryGroup
import io.github.mymoney.data.CategorySuggester
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动分类/账户规则。约定：**只返回清单里真实存在的名字**（含二级），
 * 匹配不上返回 null，绝不硬塞。
 */
class CategorySuggesterTest {

    private val expense = listOf(
        CategoryGroup("餐饮", listOf("三餐", "咖啡茶饮", "零食")),
        CategoryGroup("交通", listOf("公交地铁", "打车")),
        CategoryGroup("购物", listOf("日用品")),
        CategoryGroup("其他"),
    )
    private val income = listOf(
        CategoryGroup("工资"),
        CategoryGroup("红包"),
        CategoryGroup("其他"),
    )
    private val accounts = listOf("微信钱包", "微信零钱通", "支付宝", "花呗", "储蓄卡")

    private fun bill(
        type: BillType = BillType.EXPENSE,
        merchant: String? = "早餐店",
        method: String? = "工商银行储蓄卡(5821)",
        strategy: String = "",
    ) = BillDraft(
        priceCents = 350,
        type = type,
        timeMillis = 0L,
        merchant = merchant,
        method = method,
        orderTail = null,
        sourcePkg = "com.tencent.mm",
        strategy = strategy,
    )

    private fun suggest(draft: BillDraft) =
        CategorySuggester.suggest(draft, expense, income, accounts)

    @Test
    fun `商户关键词映射到一级和二级分类`() {
        val s = suggest(bill(merchant = "早餐店"))
        assertEquals("餐饮", s.category)
        assertEquals("三餐", s.subCategory)

        val coffee = suggest(bill(merchant = "瑞幸咖啡"))
        assertEquals("餐饮", coffee.category)
        assertEquals("咖啡茶饮", coffee.subCategory)
    }

    @Test
    fun `交通类映射`() {
        val s = suggest(bill(merchant = "地铁乘车码"))
        assertEquals("交通", s.category)
        assertEquals("公交地铁", s.subCategory)
    }

    @Test
    fun `支付方式映射到账户`() {
        assertEquals("储蓄卡", suggest(bill()).account)
        assertEquals("微信钱包", suggest(bill(method = "零钱")).account)
        assertEquals("微信零钱通", suggest(bill(method = "微信零钱通")).account)
        assertEquals("花呗", suggest(bill(method = "花呗")).account)
    }

    @Test
    fun `收入用收入分类`() {
        assertEquals("工资", suggest(bill(type = BillType.INCOME, merchant = "某公司代发")).category)
        assertEquals("红包", suggest(bill(type = BillType.INCOME, merchant = "收到红包")).category)
    }

    @Test
    fun `转账默认归到其他`() {
        assertEquals("其他", suggest(bill(type = BillType.TRANSFER, merchant = "张三")).category)
    }

    @Test
    fun `清单里没有的分类不会硬塞`() {
        val narrow = listOf(CategoryGroup("购物"))
        val s = CategorySuggester.suggest(bill(merchant = "早餐店"), narrow, income, accounts)
        assertNull(s.category)
        assertNull(s.subCategory)
    }

    @Test
    fun `二级分类不在清单里时只返回一级`() {
        val noSubs = listOf(CategoryGroup("餐饮"), CategoryGroup("其他"))
        val s = CategorySuggester.suggest(bill(merchant = "早餐店"), noSubs, income, accounts)
        assertEquals("餐饮", s.category)
        assertNull(s.subCategory)
    }

    @Test
    fun `账户清单里没有时返回 null`() {
        val narrowAccounts = listOf("现金")
        assertNull(CategorySuggester.suggest(bill(method = "零钱"), expense, income, narrowAccounts).account)
    }

    @Test
    fun `匹配不上时全为 null`() {
        val s = suggest(bill(merchant = "某个没有关键词的商户", method = "未知渠道"))
        assertNull(s.category)
        assertNull(s.account)
    }
}
