package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.data.BillWriter
import io.github.caozl9021.mymoney.data.applyBillEdit
import io.github.caozl9021.mymoney.data.newManualEntity
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test

class BillEditingTest {
    private val original = BillEntity(
        id = 27, priceCents = 1999, type = "EXPENSE", timeMillis = 1_700_000_000_000,
        merchant = "旧商户", method = "银行卡", orderTail = "123456", sourcePkg = "third.party",
        strategy = "ALIPAY_BILL", fingerprint = "old", createdAt = 1_700_000_000_500,
        category = "餐饮", subCategory = "午餐", account = "工资卡", note = "原备注",
    )

    @Test fun editPreservesIdentityAndCaptureMetadata() {
        val edited = BillWriter.toDraft(original).copy(
            priceCents = 2999, type = BillType.INCOME, merchant = "新商户",
            category = "工资", subCategory = null, account = "现金", note = "新备注",
            timeMillis = 9, sourcePkg = "should.not.replace", method = "不同支付方式", orderTail = null,
        )
        val result = applyBillEdit(original, edited)
        assertEquals(27L, result.id)
        assertEquals(original.createdAt, result.createdAt)
        assertEquals(9L, result.timeMillis)
        assertEquals(original.sourcePkg, result.sourcePkg)
        assertEquals(original.strategy, result.strategy)
        assertEquals(original.method, result.method)
        assertEquals(original.orderTail, result.orderTail)
        assertEquals(2999L, result.priceCents)
        assertEquals("INCOME", result.type)
        assertEquals(original.merchant, result.merchant)
        assertEquals("现金", result.account)
        assertEquals("新备注", result.note)
        assertNull(result.subCategory)
        assertEquals(original.fingerprint, result.fingerprint)
    }

    @Test fun capturedRefundEditPreservesSourceFingerprint() {
        val result = applyBillEdit(original, BillWriter.toDraft(original).copy(type = BillType.REFUND))
        assertEquals("REFUND", result.type)
        assertEquals(original.fingerprint, result.fingerprint)
        assertEquals(original.id, result.id)
    }

    @Test fun manualEntriesAtSameTimeAndAmountHaveIndependentIdentities() {
        val draft = BillWriter.toDraft(original).copy(sourcePkg = "manual")
        val first = newManualEntity(draft, 111)
        val second = newManualEntity(draft, 112)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(first.sourcePkg, second.sourcePkg)
        assertEquals(1999L, first.priceCents)
        assertNull(first.method)
        assertNull(first.orderTail)
        assertEquals(0L, first.id)
    }

    @Test fun manualDraftIdentitySurvivesRecreationAndEditing() {
        val draft = BillWriter.toDraft(original).copy(sourcePkg = "io.github.caozl9021.mymoney.manual.stable-id", orderTail = null)
        val inserted = newManualEntity(draft, 111).copy(id = 42)
        assertEquals(draft.fingerprint, inserted.fingerprint)
        val updated = applyBillEdit(inserted, BillWriter.toDraft(inserted).copy(priceCents = 2999))
        assertEquals(inserted.sourcePkg, updated.sourcePkg)
        assertEquals(42L, updated.id)
        assertEquals(111L, updated.createdAt)
        assertNotEquals(inserted.fingerprint, updated.fingerprint)
    }
}
