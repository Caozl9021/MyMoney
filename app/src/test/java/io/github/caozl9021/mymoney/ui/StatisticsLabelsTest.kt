package io.github.caozl9021.mymoney.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsLabelsTest {
    @Test fun shortTypeLabelMapsEveryTypeIncludingUnknown() {
        assertEquals("支出", statisticsShortTypeLabel("EXPENSE"))
        assertEquals("收入", statisticsShortTypeLabel("INCOME"))
        assertEquals("退款", statisticsShortTypeLabel("REFUND"))
        assertEquals("支出", statisticsShortTypeLabel(""))
    }

    @Test fun typeLabelMatchesSegmentedCopyForExpense() {
        // 分段按钮写「支出」，主金额标题也必须一致，不能出现「原支出」这类旧措辞。
        assertEquals("支出", statisticsTypeLabel("EXPENSE"))
        assertEquals("收入", statisticsTypeLabel("INCOME"))
        assertEquals("退款", statisticsTypeLabel("REFUND"))
    }
}
