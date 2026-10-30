package io.github.mymoney

import io.github.mymoney.data.*
import org.junit.Assert.*
import org.junit.Test

class AssetMathTest {
    private fun account(id: String, kind: String = "PAYMENT", initialized: Boolean = true, included: Boolean = true, archived: Boolean = false) =
        AssetAccount(id, id, kind, initialized = initialized, included = included, archived = archived, createdAt = 0, updatedAt = 0)
    private fun entry(id: String, account: String, cents: Long, kind: String = "OPENING", reversal: String? = null) =
        AssetEntry(id, id, account, cents, kind, "test", 0, reversalOf = reversal)

    @Test fun decimalParsingIsExactAndRoundsHalfUp() {
        assertEquals(1999L, AssetMath.parseCents("19.99"))
        assertEquals(2000L, AssetMath.parseCents("19.995"))
        assertEquals(-2000L, AssetMath.parseCents("-19.995"))
        assertEquals(0L, AssetMath.parseCents("0"))
        listOf("NaN", "Infinity", "1e9", "", "1,000", "92233720368547758.08").forEach {
            assertTrue(runCatching { AssetMath.parseCents(it) }.isFailure)
        }
    }
    @Test fun assetsDebtsAndNetIncludeArchivedAndDistinguishUnknownZero() {
        val accounts = listOf(account("s"), account("w"), account("c", "CREDIT", archived = true),
            account("u", initialized = false), account("zero"), account("excluded", included = false))
        val entries = listOf(entry("1", "s", 100000), entry("2", "w", 20000), entry("3", "c", -30000), entry("4", "excluded", 999999))
        assertEquals(AssetSummary(120000, 30000, 90000, 1), AssetMath.summary(accounts, entries))
    }
    @Test fun overdraftAndCreditSurplusUseSignedPositions() {
        assertEquals(AssetSummary(2000, 5000, -3000, 0), AssetMath.summary(listOf(account("s"), account("c", "CREDIT")),
            listOf(entry("1", "s", -5000), entry("2", "c", 2000))))
    }
    @Test fun spendingAndRefundHaveSameSignsForAssetsAndCredit() {
        assertEquals(-1999L, AssetMath.delta("EXPENSE", 1999))
        assertEquals(1999L, AssetMath.delta("INCOME", 1999))
        assertEquals(1999L, AssetMath.delta("REFUND", 1999))
        assertEquals(0L, AssetMath.delta("TRANSFER", 1999))
        assertTrue(runCatching { AssetMath.delta("EXPENSE", 0) }.isFailure)
    }
    @Test fun transferAndRepaymentConserveNetFeeReducesIt() {
        val a = listOf(account("s"), account("w"), account("c", "CREDIT"))
        val opening = listOf(entry("1", "s", 100000), entry("2", "w", 20000), entry("3", "c", -30000))
        val transfer = listOf(entry("4", "s", -10000), entry("5", "w", 10000))
        val repay = listOf(entry("6", "s", -10000), entry("7", "c", 10000))
        assertEquals(90000L, AssetMath.summary(a, opening + transfer + repay).net)
        assertEquals(89800L, AssetMath.summary(a, opening + transfer + repay + entry("fee", "s", -200)).net)
    }
    @Test fun adjustmentReversalKeepsHistoryAndRestoresBalance() {
        val entries = listOf(entry("opening", "a", 10000), entry("adjust", "a", -2000, "ADJUSTMENT"), entry("reverse", "a", 2000, "REVERSAL", "adjust"))
        AssetMath.validate(AssetSnapshot(listOf(account("a")), entries))
        assertEquals(10000L, AssetMath.balance("a", entries))
        assertTrue(runCatching { AssetMath.validate(AssetSnapshot(listOf(account("a")), entries + entry("duplicate", "a", 2000, "REVERSAL", "adjust"))) }.isFailure)
    }
    @Test fun malformedIdentitiesAndReferencesAreRejected() {
        assertTrue(runCatching { AssetMath.validate(AssetSnapshot(listOf(account("a"), account("b").copy(aliases = "[\"a\"]")), emptyList())) }.isFailure)
        assertTrue(runCatching { AssetMath.validate(AssetSnapshot(listOf(account("a")), listOf(entry("bad", "missing", 1)))) }.isFailure)
        assertTrue(runCatching { AssetMath.validate(AssetSnapshot(listOf(account("a", initialized = false)), listOf(entry("bad", "a", 0)))) }.isFailure)
    }
    @Test fun explicitAliasesResolveOnlyToActiveCandidateIdentity() {
        val a = account("a").copy(name = "日用零钱", aliases = "[\"微信钱包\"]")
        assertEquals("日用零钱", AssetMath.canonicalName("微信钱包", listOf("日用零钱"), listOf(a)))
        assertNull(AssetMath.canonicalName("微信钱包", listOf("日用零钱"), listOf(a.copy(archived = true))))
        assertNull(AssetMath.canonicalName("钱包", listOf("日用零钱"), listOf(a)))
    }
    @Test fun arithmeticOverflowCannotWrapIntoValidBalance() {
        assertTrue(runCatching { AssetMath.balance("a", listOf(entry("1", "a", Long.MAX_VALUE), entry("2", "a", 1))) }.isFailure)
        assertTrue(runCatching { AssetMath.summary(listOf(account("a")), listOf(entry("1", "a", Long.MIN_VALUE))) }.isFailure)
    }
}
