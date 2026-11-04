package io.github.mymoney

import io.github.mymoney.data.*
import org.junit.Assert.*
import org.junit.Test

class BillDeletionCodecTest {
    private fun row() = BillEntity(id = 23, priceCents = 3200, type = "EXPENSE", timeMillis = 1700000000000,
        merchant = "店铺", method = "付款", orderTail = "001", sourcePkg = "com.tencent.mm", strategy = "test",
        fingerprint = "fingerprint", createdAt = 1700000001000, category = "餐饮", subCategory = "早餐",
        account = "旧账户名", note = "人工备注", recognitionId = "recognition", accountId = "account",
        assetLinked = true, assetKey = "asset-key", productName = "商品", productSource = "PRODUCT",
        noteOrigin = "USER", noteTemplateSnapshot = "模板")

    @Test fun immutableSnapshotRoundTripsEveryFieldWithoutAssetsOrLiveBill() {
        val original = row()
        val log = BillDeletionCodec.create(listOf(original), "detail", 1700000002000,
            "00000000-0000-4000-8000-000000000001")
        val encoded = BillDeletionCodec.encode(log)
        assertEquals(log, BillDeletionCodec.decode(MiniJson.decode(MiniJson.encode(encoded))))
        assertEquals(listOf(original), BillDeletionCodec.bills(log))
        assertEquals("旧账户名", BillDeletionCodec.bills(log).single().account)
    }

    @Test fun completeTransferFeeGroupIsRequired() {
        val principal = row().copy(type = "TRANSFER", transferAccountId = "destination", transferAccount = "转入",
            transferFeeCents = 50, recognitionId = null)
        val fee = row().copy(id = 24, priceCents = 50, recognitionId = null, assetKey = "asset-key-fee", feeParentKey = "asset-key")
        val log = BillDeletionCodec.create(listOf(principal, fee), "ledger", 1700000002000)
        assertEquals(listOf(principal, fee), BillDeletionCodec.bills(log))
        assertTrue(runCatching { BillDeletionCodec.create(listOf(principal), "ledger") }.isFailure)
        assertTrue(runCatching { BillDeletionCodec.create(listOf(principal, fee.copy(priceCents = 51)), "ledger") }.isFailure)
        assertTrue(runCatching { BillDeletionCodec.create(listOf(principal, fee.copy(accountId = "other")), "ledger") }.isFailure)
        assertTrue(runCatching { BillDeletionCodec.create(listOf(row(), fee), "ledger") }.isFailure)
    }

    @Test fun alteredMetadataVersionAndNonIntegralNumbersAreRejected() {
        val log = BillDeletionCodec.create(listOf(row()), "detail")
        assertTrue(runCatching { BillDeletionCodec.encode(log.copy(principalId = 99)) }.isFailure)
        assertTrue(runCatching { BillDeletionCodec.encode(log.copy(eventId = "not-a-uuid")) }.isFailure)
        assertTrue(runCatching { BillDeletionCodec.encode(log.copy(snapshotVersion = 2)) }.isFailure)
        val raw = BillDeletionCodec.encode(log).toMutableMap()
        raw["principalId"] = 23.5
        assertTrue(runCatching { BillDeletionCodec.decode(raw) }.isFailure)
    }
}
