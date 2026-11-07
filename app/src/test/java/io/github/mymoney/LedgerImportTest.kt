package io.github.mymoney

import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.ImportIdentity
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.ledgersync.LedgerImport
import io.github.mymoney.data.ledgersync.LedgerItemKind
import io.github.mymoney.data.ledgersync.LedgerPayloadCodec
import io.github.mymoney.data.ledgersync.LedgerRemoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W3 跨设备导入预览：READY/DUPLICATE/CONFLICT/DELETED 判定 + 转账手续费成组。 */
class LedgerImportTest {

    private val sourceLedger = "1234abcd-0000-0000-0000-000000000000"

    private fun bill(
        syncId: String, cents: Long = 1000, type: String = "EXPENSE", account: String? = "工行5821",
        transferAccount: String? = null, feeParentKey: String? = null, fee: Long = 0,
    ) = BillEntity(
        id = 0, priceCents = cents, type = type, timeMillis = 1_730_000_000_000, merchant = "商户",
        method = null, orderTail = null, sourcePkg = "pkg", strategy = "s", fingerprint = "fp-$syncId",
        createdAt = 1_730_000_000_000, category = null, subCategory = null, account = account, note = null,
        recognitionId = null, accountId = null, transferAccountId = null, assetLinked = false,
        assetKey = "key-$syncId", transferFeeCents = fee, feeParentKey = feeParentKey,
        transferAccount = transferAccount, productName = null, productSource = null,
        noteOrigin = null, noteTemplateSnapshot = null, syncId = syncId, updatedAt = 1_730_000_000_000,
    )

    private fun remote(b: BillEntity, deleted: Boolean = false) = LedgerRemoteItem(
        itemId = "bill:${b.syncId}",
        itemKind = LedgerItemKind.BILL,
        schemaVersion = 1,
        payloadJson = MiniJson.encode(LedgerPayloadCodec.encodeBill(b)),
        revision = 1,
        contentUpdatedAt = b.updatedAt,
        deletedAt = if (deleted) "2026-11-06T00:00:00Z" else null,
    )

    private fun previewOf(items: List<LedgerRemoteItem>, existing: List<ImportIdentity> = emptyList()) =
        LedgerImport.preview("A 手机", sourceLedger, 5, items, existing)

    @Test fun freshImportIsReady() {
        val p = previewOf(listOf(remote(bill("s1")), remote(bill("s2", cents = 2000))))
        assertEquals(2, p.count(LedgerImport.State.READY))
        assertEquals(0, p.count(LedgerImport.State.DUPLICATE))
    }

    @Test fun reimportSameContentIsDuplicate() {
        val b = bill("s1")
        // 先导一次得到导入身份
        val first = previewOf(listOf(remote(b)))
        val key = first.items[0].identity
        // 再导同内容 → DUPLICATE
        val second = previewOf(listOf(remote(b)), existing = listOf(key))
        assertEquals(1, second.count(LedgerImport.State.DUPLICATE))
        assertEquals(0, second.count(LedgerImport.State.READY))
    }

    @Test fun changedContentIsConflict() {
        val b = bill("s1", cents = 1000)
        val key = previewOf(listOf(remote(b))).items[0].identity
        // 对方改了金额
        val changed = previewOf(listOf(remote(bill("s1", cents = 3000))), existing = listOf(key))
        assertEquals(1, changed.count(LedgerImport.State.CONFLICT))
        assertEquals(0, changed.count(LedgerImport.State.READY))
    }

    @Test fun deletedSourceIsSkippedNotImported() {
        val p = previewOf(listOf(remote(bill("s1"), deleted = true)))
        assertEquals(1, p.count(LedgerImport.State.DELETED))
        assertEquals(0, p.count(LedgerImport.State.READY))
        // 源删除不影响本机已导入记录：DELETED 不算 READY/CONFLICT
        assertTrue(p.readyBills().isEmpty())
    }

    @Test fun transferWithFeeGroupsIntoSingleItem() {
        val transfer = bill("t1", cents = 50000, type = "TRANSFER", account = "工行", transferAccount = "余额", fee = 200)
        val fee = bill("f1", cents = 200, type = "EXPENSE", account = "工行", feeParentKey = "key-t1")
        // 主转账的 assetKey = "key-t1"，手续费 feeParentKey 指向它
        val p = previewOf(listOf(remote(transfer), remote(fee)))
        // 只出一条（转账），手续费并入不再单列
        assertEquals(1, p.items.size)
        val item = p.items[0]
        assertEquals("t1", item.sourceSyncId)
        assertNotNull(item.fee)
        assertEquals(200L, item.fee!!.priceCents)
    }

    @Test fun feeParentMissingLeavesNoFee() {
        val transfer = bill("t1", cents = 50000, type = "TRANSFER", account = "工行", transferAccount = "余额")
        val p = previewOf(listOf(remote(transfer)))
        assertEquals(1, p.items.size)
        assertNull(p.items[0].fee)
    }
}
