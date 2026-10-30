package io.github.mymoney

import io.github.mymoney.data.*
import io.github.mymoney.ui.*
import com.example.billkit.BillType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ConfirmRefinementTest {
    private val row = BillEntity(id = 7, priceCents = 1999, type = "EXPENSE", timeMillis = 1234567,
        merchant = "来源商户", method = "零钱", orderTail = "123", sourcePkg = "com.tencent.mm", strategy = "capture",
        fingerprint = "captured-fingerprint", createdAt = 2345, recognitionId = "original-history",
        accountId = "wallet", assetLinked = true, assetKey = "stable", note = "原备注")

    @Test fun dateAndNoteEditPreservesCapturedAndImportedIdentity() {
        listOf(row, row.copy(fingerprint = "qianji:original-import", recognitionId = null)).forEach { original ->
            val updated = applyBillEdit(original, BillWriter.toDraft(original).copy(timeMillis = 9999999, note = null, merchant = null))
            assertEquals(9999999L, updated.timeMillis)
            assertNull(updated.note)
            assertEquals(original.copy(timeMillis = 9999999, note = null), updated)
        }
    }

    @Test fun manualDateUpdatesFingerprintWithinSameIndependentIdentity() {
        val original = row.copy(sourcePkg = "io.github.mymoney.manual.unique", recognitionId = null)
        val updated = applyBillEdit(original, BillWriter.toDraft(original).copy(timeMillis = 9999999))
        assertEquals(original.id, updated.id)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(original.sourcePkg, updated.sourcePkg)
        assertEquals(BillWriter.toDraft(updated).fingerprint, updated.fingerprint)
    }

    @Test fun localDateAndMinuteSelectionRespectsZoneAndDirtyProtection() {
        val date = LocalDate.of(2026, 12, 31)
        val zone = ZoneId.of("Asia/Shanghai")
        val selected = ledgerTimeMillis(date, 23, 59, zone)
        assertEquals(date.atTime(23, 59).atZone(zone).toInstant().toEpochMilli(), selected)
        assertEquals(0L, selected % 60000)
        val d = BillWriter.toDraft(row)
        assertTrue(editorFieldsChanged(d, d.copy(timeMillis = selected), "19.99"))
        assertTrue(runCatching { ledgerTimeMillis(date, 24, 0, zone) }.isFailure)
    }

    @Test fun horizontalSlideOutCancelsAndReentryCommitsOnlyFinalCandidate() {
        val regions = listOf(CategoryHitRegion("早餐", 10f, 20f, 58f, 68f), CategoryHitRegion("午餐", 60f, 20f, 108f, 68f))
        val canceled = CategorySlideGesture(setOf("早餐", "午餐"))
        assertEquals("早餐", canceled.move(30f, 30f, regions))
        assertNull(canceled.move(30f, 80f, regions))
        assertNull(canceled.release())
        val reenter = CategorySlideGesture(setOf("早餐", "午餐"))
        reenter.move(30f, 30f, regions); reenter.move(30f, 80f, regions)
        assertEquals("午餐", reenter.move(80f, 30f, regions))
        assertEquals("午餐", reenter.release()); assertNull(reenter.release())
    }

    @Test fun onlyNewRealSaveClosesWhileDuplicateAndMissingRemainActionable() {
        assertTrue(ConfirmFeedback.closeAfterSave(BillWriter.SaveResult(true, row)))
        assertTrue(ConfirmFeedback.closeAfterSave(BillWriter.SaveResult(true, row.copy(type = BillType.REFUND.name))))
        assertFalse(ConfirmFeedback.closeAfterSave(BillWriter.SaveResult(false, row)))
        assertFalse(ConfirmFeedback.closeAfterSave(BillWriter.SaveResult(false, null)))
        assertFalse(ConfirmFeedback.closeAfterSave(BillWriter.SaveResult(true, null)))
    }
}
