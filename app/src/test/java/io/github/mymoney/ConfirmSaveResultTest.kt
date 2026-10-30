package io.github.mymoney

import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.ui.ConfirmFeedback
import io.github.mymoney.ui.ConfirmSaveOperation
import org.junit.Assert.*
import org.junit.Test

class ConfirmSaveResultTest {
    private val stored = BillEntity(id = 42, priceCents = 1999, type = "EXPENSE", timeMillis = 1700000000000,
        merchant = "真实原商户", method = "零钱", orderTail = "1234", sourcePkg = "com.tencent.mm",
        strategy = "test", fingerprint = "stored", createdAt = 1700000001000,
        category = "餐饮", account = "现金", note = "原账单备注", recognitionId = "history-42")

    @Test fun duplicateResultUsesStoredBillFieldsInsteadOfResubmittedEdits() {
        val operation = ConfirmSaveOperation()
        operation.edited = BillWriter.toDraft(stored).copy(priceCents = 9999, merchant = "未保存修改", note = "未保存备注")
        operation.restoreResult(BillWriter.SaveResult(inserted = false, bill = stored))
        val shown = BillWriter.toDraft(operation.result!!.bill!!)
        assertEquals(1999L, shown.priceCents)
        assertEquals("真实原商户", shown.merchant)
        assertEquals("原账单备注", shown.note)
        assertEquals(42L, operation.result!!.bill!!.id)
        assertFalse(operation.result!!.inserted)
    }

    @Test fun activityRecreationDoesNotDowngradeAlreadyReceivedInsertResult() {
        val operation = ConfirmSaveOperation()
        val inserted = BillWriter.SaveResult(true, stored)
        operation.restoreResult(inserted)
        operation.restoreResult(BillWriter.SaveResult(false, stored.copy(note = "later read")))
        assertSame(inserted, operation.result)
    }

    @Test fun missingAssociationDoesNotInventBillAndExplicitlyExplainsNoReinsert() {
        val operation = ConfirmSaveOperation()
        operation.restoreResult(BillWriter.SaveResult(false, null))
        assertNull(operation.result!!.bill)
        val detail = ConfirmFeedback.resultDetail(operation.result!!).orEmpty()
        assertTrue(detail.contains("已删除或不存在"))
        assertTrue(detail.contains("未重新记账"))
        assertFalse(ConfirmFeedback.saved(false).contains("已记一笔"))
    }

    @Test fun refundResultExplainsUnsupportedExternalShare() {
        val result = BillWriter.SaveResult(true, stored.copy(type = "REFUND"))
        val detail = ConfirmFeedback.resultDetail(result).orEmpty()
        assertTrue(detail.contains("已保存到本地"))
        assertTrue(detail.contains("不支持退款"))
        assertTrue(detail.contains("无法分享"))
    }
}
