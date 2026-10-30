package io.github.mymoney

import io.github.mymoney.data.*
import org.junit.Assert.*
import org.junit.Test

class BillAssetIdentityTest {
    private val bill = BillEntity(id = 3, priceCents = 1999, type = "EXPENSE", timeMillis = 1234,
        merchant = "商户", method = null, orderTail = null, sourcePkg = "test", strategy = "test",
        fingerprint = "old", createdAt = 1235, account = "原卡", accountId = "account-a",
        assetLinked = true, assetKey = "stable-asset-key")

    @Test fun changingAccountClearsOldIdentityButKeepsStableBusinessKey() {
        val changed = applyBillEdit(bill, BillWriter.toDraft(bill).copy(account = "新卡", priceCents = 2999))
        assertNull(changed.accountId)
        assertEquals("stable-asset-key", changed.assetKey)
        assertEquals(bill.id, changed.id)
        assertTrue(changed.assetLinked)
    }

    @Test fun ordinaryEditPreservesAccountIdentityAndLinkage() {
        val changed = applyBillEdit(bill, BillWriter.toDraft(bill).copy(note = "备注"))
        assertEquals("account-a", changed.accountId)
        assertTrue(changed.assetLinked)
        assertEquals(bill.assetKey, changed.assetKey)
    }

    @Test fun legacyBillDefaultsNeverClaimBalanceLinkage() {
        val legacy = bill.copy(accountId = null, assetLinked = false, assetKey = "legacy-3")
        val changed = applyBillEdit(legacy, BillWriter.toDraft(legacy).copy(priceCents = 2999))
        assertFalse(changed.assetLinked)
        assertEquals("legacy-3", changed.assetKey)
    }
}
