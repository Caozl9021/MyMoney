package io.github.mymoney

import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.ui.ConfirmFeedback
import org.junit.Assert.*
import org.junit.Test

class ConfirmFeedbackTest {
    @Test fun duplicateDoesNotReportANewEntry() {
        val message = ConfirmFeedback.saved(inserted = false)
        assertTrue(message.contains("已经记过"))
        assertFalse(message.contains("已记一笔"))
    }

    @Test fun savedFeedbackOnlyDescribesLocalLedgerForEveryTransactionType() {
        for (type in listOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")) {
            val bill = BillEntity(id = 1, priceCents = 1200, type = type, timeMillis = 1,
                merchant = null, method = null, orderTail = null, sourcePkg = "test", strategy = "test",
                fingerprint = "test", createdAt = 1)
            val message = ConfirmFeedback.resultDetail(BillWriter.SaveResult(true, bill)).orEmpty()
            assertTrue(message.contains("已保存到本地"))
            assertTrue(message.contains("查看账单"))
            assertFalse(message.contains("iCost"))
            assertFalse(message.contains("分享"))
        }
    }
}
