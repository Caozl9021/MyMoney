package io.github.mymoney

import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.ledgersync.LedgerPayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** W2 账本同步 payload 编解码：全字段往返 + syncId 强校验。 */
class LedgerPayloadCodecTest {

    private fun sampleBill() = BillEntity(
        id = 42,
        priceCents = 2629,
        type = "EXPENSE",
        timeMillis = 1_730_000_000_000,
        merchant = "华莱士·全鸡汉堡顺达店",
        method = "工行5821",
        orderTail = "3456",
        sourcePkg = "com.eg.android.AlipayGphone",
        strategy = "支付宝-页面",
        fingerprint = "fp-abc",
        createdAt = 1_730_000_100_000,
        category = "餐饮",
        subCategory = "外卖",
        account = "工行5821",
        note = "淘宝闪购",
        recognitionId = "rid-1",
        accountId = "acc-1",
        transferAccountId = null,
        assetLinked = true,
        assetKey = "key-1",
        transferFeeCents = 0,
        feeParentKey = null,
        transferAccount = null,
        productName = "全鸡汉堡",
        productSource = "PRODUCT",
        noteOrigin = "AUTO",
        noteTemplateSnapshot = "snap",
        syncId = "sync-uuid-1",
        updatedAt = 1_730_000_200_000,
        deletedAt = null,
    )

    @Test
    fun billRoundTripsThroughJson() {
        val original = sampleBill()
        val encoded = LedgerPayloadCodec.encodeBill(original)
        val json = MiniJson.encode(encoded)
        val decoded = LedgerPayloadCodec.decodeBill(LedgerPayloadCodec.decodeBillPayload(json), 0L)
        // id 是本地自增，不走同步；其余业务字段应逐一对齐。
        assertEquals(original.syncId, decoded.syncId)
        assertEquals(original.priceCents, decoded.priceCents)
        assertEquals(original.type, decoded.type)
        assertEquals(original.timeMillis, decoded.timeMillis)
        assertEquals(original.merchant, decoded.merchant)
        assertEquals(original.category, decoded.category)
        assertEquals(original.accountId, decoded.accountId)
        assertEquals(original.assetKey, decoded.assetKey)
        assertEquals(original.productName, decoded.productName)
        assertEquals(original.noteOrigin, decoded.noteOrigin)
        assertEquals(original.recognitionId, decoded.recognitionId)
        assertEquals(original.updatedAt, decoded.updatedAt)
        assertEquals("NONE", decoded.reimburseStatus)
        assertNull(decoded.reimbursedAt)
        assertNull(decoded.deletedAt)
    }

    @Test
    fun deletedBillCarriesTombstoneTimestamp() {
        val tomb = sampleBill().copy(deletedAt = 1_730_000_300_000)
        val decoded = LedgerPayloadCodec.decodeBill(
            LedgerPayloadCodec.decodeBillPayload(MiniJson.encode(LedgerPayloadCodec.encodeBill(tomb))), 0L,
        )
        assertEquals(1_730_000_300_000, decoded.deletedAt)
    }

    @Test
    fun missingSyncIdIsRejected() {
        val noSync = sampleBill().copy(syncId = "")
        try {
            LedgerPayloadCodec.encodeBill(noSync)
            fail("缺少 syncId 的账单应被拒绝编码")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun decodeWithoutSyncIdThrows() {
        val payload = LedgerPayloadCodec.encodeBill(sampleBill()).toMutableMap().apply { remove("syncId") }
        try {
            LedgerPayloadCodec.decodeBill(payload, 0L)
            fail("缺少 syncId 的 payload 应被拒绝解码")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun metaConfigCarriesMonthStartAndCategoryTrees() {
        val payload = LedgerPayloadCodec.encodeMeta(
            monthStartDay = 12,
            expenseGroups = listOf(
                io.github.mymoney.data.CategoryGroup("餐饮", listOf("午餐", "咖啡茶饮")),
                io.github.mymoney.data.CategoryGroup("交通", emptyList()),
            ),
            incomeGroups = listOf(io.github.mymoney.data.CategoryGroup("工资", emptyList())),
            updatedAt = 1_730_000_500_000,
        )
        val decoded = MiniJson.decode(MiniJson.encode(payload)) as Map<*, *>
        assertEquals("config", decoded["syncId"])
        assertEquals(12L, (decoded["monthStartDay"] as Number).toLong())
        assertEquals(1_730_000_500_000L, (decoded["updatedAt"] as Number).toLong())
        val expense = decoded["expenseCategories"] as List<*>
        assertEquals(2, expense.size)
        val first = expense[0] as Map<*, *>
        assertEquals("餐饮", first["name"])
        assertEquals(listOf("午餐", "咖啡茶饮"), first["subs"])
        val income = decoded["incomeCategories"] as List<*>
        assertEquals("工资", (income[0] as Map<*, *>)["name"])
    }

    @Test
    fun accountRoundTripsWithExtendedCreditAndStyleFields() {
        val original = io.github.mymoney.data.AssetAccount(
            id = "acc-credit",
            name = "中信信用卡",
            kind = "CREDIT",
            aliases = "[\"中信\"]",
            sortOrder = 2,
            archived = false,
            included = true,
            initialized = true,
            createdAt = 1000L,
            updatedAt = 2000L,
            quickSelect = false,
            cardTail = "4321",
            creditLimitCents = 5_000_000L,
            billingDay = 10,
            repaymentDay = 28,
            iconKey = "credit_card",
            colorHex = "#DC2626",
        )
        val decoded = LedgerPayloadCodec.decodeAccount(
            MiniJson.decode(MiniJson.encode(LedgerPayloadCodec.encodeAccount(original))) as Map<*, *>,
        )
        assertEquals(original, decoded)
    }
}
