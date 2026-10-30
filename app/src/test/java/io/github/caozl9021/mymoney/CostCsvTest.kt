package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.export.CostCsv
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按 iCost 官方模板（icost_template.xlsx，10 列）做的格式回归。
 * 任何列序/表头/日期格式的改动都会在这里挂掉。
 */
class CostCsvTest {

    private fun bill(
        price: Long = 350,
        type: BillType = BillType.EXPENSE,
        merchant: String? = "早餐店",
        method: String? = "工商银行储蓄卡(5821)",
        category: String? = null,
        account: String? = null,
        note: String? = null,
    ) = BillDraft(
        priceCents = price,
        type = type,
        timeMillis = 0L,
        merchant = merchant,
        method = method,
        orderTail = "445553",
        sourcePkg = "com.tencent.mm",
        strategy = "微信账单详情/bare-unique",
        category = category,
        account = account,
        note = note,
    )

    private fun rows(csv: String) = csv.trim().lines()
    private fun cells(csv: String) = rows(csv)[1].split(",")

    @Test
    fun `包含退款时明确拒绝整个导出而非伪装收入或丢行`() {
        val error = runCatching {
            CostCsv.build(listOf(bill(), bill(type = BillType.REFUND)))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("不支持退款"))
    }

    @Test
    fun `包含转账时拒绝导出避免丢失转入账户`() {
        val error = runCatching { CostCsv.build(listOf(bill(), bill(type = BillType.TRANSFER))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("双账户转账"))
    }

    @Test
    fun `表头与模板完全一致`() {
        assertEquals(
            "日期,类型,金额,一级分类,二级分类,账户1,账户2,备注,货币,标签",
            rows(CostCsv.build(listOf(bill())))[0],
        )
    }

    @Test
    fun `每行列数与表头一致`() {
        val csv = CostCsv.build(
            listOf(bill(), bill(type = BillType.INCOME))
        )
        rows(csv).drop(1).forEach { line ->
            assertEquals("列数不对: $line", 10, line.split(",").size)
        }
    }

    @Test
    fun `分类和账户用账单自带的值`() {
        val csv = CostCsv.build(listOf(bill(category = "餐饮", account = "花呗")))
        val c = cells(csv)
        assertEquals("餐饮", c[3])
        assertEquals("花呗", c[5])
    }

    @Test
    fun `没有分类时用兜底值`() {
        val csv = CostCsv.build(
            listOf(bill(category = null)),
            CostCsv.Profile(fallbackCategory = "其他"),
        )
        assertEquals("其他", cells(csv)[3])
    }

    @Test
    fun `账户缺失时从支付方式推断`() {
        val csv = CostCsv.build(listOf(bill(account = null)))
        assertEquals("储蓄卡", cells(csv)[5]) // 工商银行储蓄卡(5821) → 储蓄卡
    }

    @Test
    fun `日期是模板要求的文本格式`() {
        val date = cells(CostCsv.build(listOf(bill())))[0]
        assertTrue("日期格式不对: $date", Regex("""^\d{4}年\d{2}月\d{2}日 \d{2}:\d{2}:\d{2}$""").matches(date))
    }

    @Test
    fun `金额固定两位小数且不受区域设置影响`() {
        assertTrue(CostCsv.build(listOf(bill(price = 100000))).contains(",1000.00,"))
    }

    @Test
    fun `备注优先用 note`() {
        val csv = CostCsv.build(listOf(bill(note = "和同事", merchant = "早餐店")))
        assertEquals("和同事", cells(csv)[7])
    }

    @Test
    fun `含逗号的单元格被引号包裹`() {
        assertTrue(CostCsv.build(listOf(bill(note = "渠道A,渠道B"))).contains("\"渠道A,渠道B\""))
    }

    @Test
    fun `货币可配置`() {
        assertEquals("USD", cells(CostCsv.build(listOf(bill()), CostCsv.Profile(currency = "USD")))[8])
    }

    @Test
    fun `账户推断对未知渠道返回 null`() {
        assertNull(CostCsv.accountFromMethod("某个奇怪的渠道"))
        assertEquals("微信零钱通", CostCsv.accountFromMethod("微信零钱通"))
        assertEquals("微信钱包", CostCsv.accountFromMethod("零钱"))
    }
    @Test fun `explicit empty and missing notes never leak merchant or strategy into export`() {
        listOf(null, "", "  ").forEach { note ->
            val c = cells(CostCsv.build(listOf(bill(note = note, merchant = "来源商户"))))
            assertEquals(note.orEmpty(), c[7])
        }
    }
}
