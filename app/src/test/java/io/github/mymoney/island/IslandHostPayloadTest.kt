package io.github.mymoney.island

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.capture.island.IslandHost
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 超级岛确认宿主（方案A）文案逻辑的纯 JVM 回归。
 *
 * 说明：payload 的 JSON 结构（protocol=3 / 顶层 scene / param_island 内嵌岛区 / textButton 双按钮
 * 等）已在 K2 探针 r9/r11 **真机实测上岛**验证，不在 JVM 单测里重复断言——`org.json` 在 JVM 单测里
 * 是空桩（见 MiniJsonTest 注释），断言其输出无意义。这里只测不依赖 JSON 序列化的纯字符串逻辑：
 * 摘要带方向+金额、副标题商户优先/缺失退回来源、可能重复标注。
 */
class IslandHostPayloadTest {

    private fun draft(
        type: BillType = BillType.EXPENSE,
        cents: Long = 1234,
        merchant: String? = "美团",
        account: String? = null,
        sourcePkg: String = "com.tencent.mm",
        historyId: String = "h1",
    ) = BillDraft(
        priceCents = cents,
        type = type,
        timeMillis = 1_700_000_000_000,
        merchant = merchant,
        method = null,
        orderTail = null,
        sourcePkg = sourcePkg,
        strategy = "S",
        account = account,
        historyId = historyId,
        hasExplicitTime = true,
    )

    @Test
    fun summaryLineHasDirectionAndAmount() {
        assertEquals("支出 ¥12.34", IslandHost.summaryLineFor(draft(type = BillType.EXPENSE, cents = 1234)))
        assertEquals("收入 ¥3500.00", IslandHost.summaryLineFor(draft(type = BillType.INCOME, cents = 350000)))
        assertEquals("退款 ¥8.00", IslandHost.summaryLineFor(draft(type = BillType.REFUND, cents = 800)))
        assertEquals("转账 ¥500.00", IslandHost.summaryLineFor(draft(type = BillType.TRANSFER, cents = 50000)))
    }

    @Test
    fun detailLinePrefersMerchantThenAccountThenSource() {
        assertEquals("美团", IslandHost.detailLineFor(draft(merchant = "美团"), false))
        assertEquals("零钱", IslandHost.detailLineFor(draft(merchant = null, account = "零钱"), false))
        assertEquals("com.tencent.mm", IslandHost.detailLineFor(draft(merchant = null, account = null, sourcePkg = "com.tencent.mm"), false))
    }

    @Test
    fun detailLineMarksDuplicateSuspected() {
        assertEquals("美团 ·（可能重复）", IslandHost.detailLineFor(draft(merchant = "美团"), true))
        assertEquals("美团", IslandHost.detailLineFor(draft(merchant = "美团"), false))
    }

    @Test
    fun blankMerchantFallsBack() {
        assertEquals("零钱", IslandHost.detailLineFor(draft(merchant = "   ", account = "零钱"), false))
    }
}
