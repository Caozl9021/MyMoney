package io.github.mymoney

import io.github.mymoney.data.StatisticsDrilldown
import org.junit.Assert.*
import org.junit.Test

class StatisticsDrilldownTest {
    @Test fun freezesTimeTypeAndGroupWithoutTreatingLabelsAsAnOrdinaryCategoryFilter() {
        val scope = StatisticsDrilldown(100, 200, "REFUND", "未分类", false, "历史月份")
        val query = scope.query("%_")
        assertEquals(100, query.fromMillis)
        assertEquals(200, query.toMillis)
        assertEquals("REFUND", query.type)
        assertEquals("未分类", query.statisticsCategory)
        assertNull(query.category)
        assertNull(query.statisticsAccount)
        assertEquals("%\\%\\_%", query.likePattern)
        val account = scope.copy(byAccount = true, groupName = "归档账户").query()
        assertNull(account.statisticsCategory)
        assertEquals("归档账户", account.statisticsAccount)
    }
}
