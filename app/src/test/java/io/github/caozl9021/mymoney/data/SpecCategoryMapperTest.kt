package io.github.caozl9021.mymoney.data

import com.example.billkit.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 规格分类名 → 用户清单的映射契约。
 *
 * 这些断言保护的是「绝不硬塞清单外的名字」：规格能产出 64 个分类名，项目默认只有 10 组，
 * 映射不到就必须返回 null，让用户在确认卡上点一下，而不是写一个界面上不存在的分类。
 */
class SpecCategoryMapperTest {

    private val expense = LedgerConfig.DEFAULT_EXPENSE
    private val income = LedgerConfig.DEFAULT_INCOME

    private fun e(name: String) = SpecCategoryMapper.resolve(name, BillType.EXPENSE, expense)
    private fun i(name: String) = SpecCategoryMapper.resolve(name, BillType.INCOME, income)

    @Test
    fun `规格三餐一族落到项目餐饮`() {
        assertEquals("餐饮" to "三餐", e("三餐"))
        assertEquals("餐饮" to "早餐", e("早餐"))
        assertEquals("餐饮" to "午餐", e("午餐"))
        assertEquals("餐饮" to "晚餐", e("晚餐"))
        assertEquals("餐饮" to "夜宵", e("夜宵"))
        // 项目清单里没有早茶/下午茶这两个二级，归到咖啡茶饮而不是丢掉
        assertEquals("餐饮" to "咖啡茶饮", e("早茶"))
        assertEquals("餐饮" to "咖啡茶饮", e("下午茶"))
    }

    @Test
    fun `规格一级落到项目一级并保留合法二级`() {
        assertEquals("交通" to "打车", e("打车"))
        assertEquals("交通" to "公交地铁", e("地铁"))
        assertEquals("居住" to "房租", e("房租"))
        assertEquals("居住" to "水电燃气", e("水电煤"))
        assertEquals("娱乐" to "电影", e("电影"))
        assertEquals("人情" to "红包", e("发红包"))
        assertEquals("通讯" to "话费", e("话费网费"))
        assertEquals("购物" to "数码", e("电器数码"))
    }

    @Test
    fun `映射到的一级不存在时不写清单外分类`() {
        // 用户把「通讯」整组删了：话费网费只能放弃，不能落一个界面里没有的一级。
        val withoutComms = expense.filterNot { it.name == "通讯" }
        assertNull(SpecCategoryMapper.resolve("话费网费", BillType.EXPENSE, withoutComms))
        // 但如果「话费」作为二级存在于别的组里，就挂到那个组上。
        val moved = expense.map { if (it.name == "通讯") it.copy(name = "生活缴费") else it }
        assertEquals("生活缴费" to "话费", SpecCategoryMapper.resolve("话费网费", BillType.EXPENSE, moved))
    }

    @Test
    fun `用户照 iCost 建了同名一级时直接采用`() {
        val custom = listOf(CategoryGroup("三餐"), CategoryGroup("电器数码"), CategoryGroup("其他"))
        assertEquals("三餐" to null, SpecCategoryMapper.resolve("三餐", BillType.EXPENSE, custom))
        assertEquals("电器数码" to null, SpecCategoryMapper.resolve("电器数码", BillType.EXPENSE, custom))
    }

    @Test
    fun `收入分支走收入表`() {
        assertEquals("工资" to null, i("工资"))
        assertEquals("退款" to null, i("退款"))
        assertEquals("红包" to null, i("收红包"))
        // 规格的「公积金」「外快」在项目清单里没有对应组，归到其他/奖金而不是丢弃
        assertEquals("其他" to null, i("公积金"))
        assertEquals("奖金" to null, i("外快"))
        // 理财一族统一归到理财收益
        assertEquals("理财收益" to null, i("基金"))
        assertEquals("理财收益" to null, i("快赎理财"))
    }

    @Test
    fun `未知名字返回空`() {
        assertNull(e("规格里没有的分类"))
        assertNull(i("规格里没有的分类"))
    }
}
