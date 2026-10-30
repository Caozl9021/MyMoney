package io.github.mymoney

import io.github.mymoney.data.NoteTemplate
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「导入时的备注」由模板控制，这里锁住占位符与清理规则 */
class NoteTemplateTest {

    private fun bill(
        merchant: String? = "早餐店",
        method: String? = "零钱",
        category: String? = "餐饮",
        account: String? = "微信钱包",
    ) = BillDraft(
        priceCents = 350,
        type = BillType.EXPENSE,
        timeMillis = 0L,
        merchant = merchant,
        method = method,
        orderTail = null,
        sourcePkg = "com.tencent.mm",
        strategy = "",
        category = category,
        account = account,
    )

    @Test
    fun `默认只写商户`() {
        assertEquals("早餐店", NoteTemplate.render(bill(), "{商户}"))
    }

    @Test
    fun `多个占位符`() {
        assertEquals(
            "早餐店·零钱",
            NoteTemplate.render(bill(), "{商户}·{支付方式}"),
        )
    }

    @Test
    fun `占位符为空时清掉悬空分隔符`() {
        assertEquals(
            "早餐店",
            NoteTemplate.render(bill(merchant = "早餐店", method = null), "{商户}·{支付方式}"),
        )
    }

    @Test
    fun `分类账户占位符`() {
        assertEquals("餐饮/微信钱包", NoteTemplate.render(bill(), "{分类}/{账户}"))
    }

    @Test
    fun `固定文案也行`() {
        assertEquals("自动记账", NoteTemplate.render(bill(), "自动记账"))
    }

    @Test
    fun `空模板返回空串`() {
        assertEquals("", NoteTemplate.render(bill(), ""))
    }
}
