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

    @Test fun availableCreditAndRepaymentCountdown() {
        val credit = account("cc", kind = "CREDIT").copy(creditLimitCents = 2_000_000L, billingDay = 5, repaymentDay = 25)
        assertEquals(1_650_000L, AssetMath.availableCreditCents(credit, -350_000L))
        assertNull(AssetMath.availableCreditCents(account("debit", kind = "SAVINGS"), 100_000L))

        val today = java.time.LocalDate.of(2026, 2, 26)
        assertEquals(2, AssetMath.daysUntilDayOfMonth(31, today)) // Feb 28 in 2026
        assertEquals(0, AssetMath.daysUntilDayOfMonth(26, today))

        assertTrue(runCatching {
            AssetMath.validate(AssetSnapshot(listOf(credit.copy(billingDay = 32)), emptyList()))
        }.isFailure)
        assertTrue(runCatching {
            AssetMath.validate(AssetSnapshot(listOf(credit.copy(creditLimitCents = -1L)), emptyList()))
        }.isFailure)
        assertTrue(runCatching {
            AssetMath.validate(AssetSnapshot(listOf(credit.copy(colorHex = "not-a-color")), emptyList()))
        }.isFailure)
    }
    @Test fun arithmeticOverflowCannotWrapIntoValidBalance() {
        assertTrue(runCatching { AssetMath.balance("a", listOf(entry("1", "a", Long.MAX_VALUE), entry("2", "a", 1))) }.isFailure)
        assertTrue(runCatching { AssetMath.summary(listOf(account("a")), listOf(entry("1", "a", Long.MIN_VALUE))) }.isFailure)
    }

    @Test fun accountDeletionUnlinksBillsPreservesOtherBalancesAndPassesBackupValidation() {
        val accA = account("acc-a").copy(name = "招商信用卡", kind = "CREDIT")
        val accB = account("acc-b").copy(name = "支付宝", kind = "PAYMENT")
        val entries = listOf(
            entry("e-open-a", "acc-a", 0L, "OPENING"),
            entry("e-open-b", "acc-b", 100_000L, "OPENING"),
            // A pays expense 2500 (bill-exp)
            AssetEntry("e-exp-a", "op1", "acc-a", -2_500L, "BILL", "午餐", 10L, billKey = "bill-exp"),
            // B transfers 10000 to A (bill-xfer) with fee 100 on B (bill-fee)
            AssetEntry("e-xfer-b-old", "op2a", "acc-b", -12_000L, "TRANSFER", "还款", 11L, billKey = "bill-xfer"),
            AssetEntry("e-xfer-b-rev", "op2b", "acc-b", 12_000L, "REVERSAL", "修改账单", 12L, billKey = "bill-xfer", reversalOf = "e-xfer-b-old"),
            AssetEntry("e-xfer-b", "op2c", "acc-b", -10_000L, "TRANSFER", "还款", 13L, billKey = "bill-xfer"),
            AssetEntry("e-xfer-a", "op2c", "acc-a", 10_000L, "TRANSFER", "还款", 13L, billKey = "bill-xfer"),
            AssetEntry("e-fee-b", "op3", "acc-b", -100L, "BILL", "手续费", 14L, billKey = "bill-fee"),
            // B pays expense 3000 reimbursed into A (bill-reimb)
            AssetEntry("e-reimb-pay-b", "op4", "acc-b", -3_000L, "BILL", "差旅", 15L, billKey = "bill-reimb"),
            AssetEntry("e-reimb-ret-a", "op5", "acc-a", 3_000L, "REIMBURSE", "报销退回", 16L, billKey = "bill-reimb"),
        )
        val bills = listOf(
            BillEntity(1L, 2_500L, "EXPENSE", 10L, "餐饮店", null, null, "manual", "手动", "fp1", 10L,
                "餐饮", null, "招商信用卡", "午餐", accountId = "acc-a", assetLinked = true, assetKey = "bill-exp"),
            BillEntity(2L, 10_000L, "TRANSFER", 13L, null, null, null, "manual", "手动", "fp2", 13L,
                "转账", null, "支付宝", "还款", accountId = "acc-b", transferAccountId = "acc-a",
                transferAccount = "招商信用卡", transferFeeCents = 100L, assetLinked = true, assetKey = "bill-xfer"),
            BillEntity(3L, 100L, "EXPENSE", 14L, null, null, null, "manual", "手动", "fp3", 14L,
                "转账", "手续费", "支付宝", "转账手续费", accountId = "acc-b", feeParentKey = "bill-xfer",
                assetLinked = true, assetKey = "bill-fee"),
            BillEntity(4L, 3_000L, "EXPENSE", 15L, "酒店", null, null, "manual", "手动", "fp4", 15L,
                "旅行", null, "支付宝", "差旅", accountId = "acc-b", assetLinked = true, assetKey = "bill-reimb",
                reimburseStatus = Reimburse.REIMBURSED, reimbursedAt = 16L, reimburseAccountId = "acc-a"),
        )

        val balanceBBefore = AssetMath.balance("acc-b", entries)
        assertEquals(86_900L, balanceBBefore)

        val plan = buildAccountDeletionPlan("acc-a", listOf(accA, accB), entries, bills, now = 999L)

        // All entries on acc-a plus the canceled reversal pair on acc-b for bill-xfer are deleted.
        assertTrue(setOf("e-open-a", "e-exp-a", "e-xfer-a", "e-reimb-ret-a", "e-xfer-b-old", "e-xfer-b-rev")
            .all { it in plan.deleteEntryIds })

        // Remaining account is only acc-b and its balance is completely preserved.
        assertEquals(listOf("acc-b"), plan.remainingSnapshot.accounts.map { it.id })
        assertEquals(balanceBBefore, AssetMath.balance("acc-b", plan.remainingSnapshot.entries))

        // Bills referencing acc-a keep their ledger records and readable names, with FKs & assetLinked cleared;
        // independent fee bill on acc-b remains linked.
        val afterBills = bills.map { orig -> plan.updateBills.firstOrNull { it.id == orig.id } ?: orig }
        val exp = afterBills.first { it.id == 1L }
        assertNull(exp.accountId)
        assertFalse(exp.assetLinked)
        assertEquals("招商信用卡", exp.account)

        val xfer = afterBills.first { it.id == 2L }
        assertEquals("acc-b", xfer.accountId)
        assertNull(xfer.transferAccountId)
        assertFalse(xfer.assetLinked)

        val fee = afterBills.first { it.id == 3L }
        assertEquals("acc-b", fee.accountId)
        assertTrue(fee.assetLinked)

        val reimb = afterBills.first { it.id == 4L }
        assertEquals("acc-b", reimb.accountId)
        assertNull(reimb.reimburseAccountId)
        assertFalse(reimb.assetLinked)

        // Full Backup round-trip & integrity validation must pass without throwing.
        val backupBills = afterBills.map { b ->
            Backup.Bill(
                priceCents = b.priceCents, type = b.type, timeMillis = b.timeMillis,
                merchant = b.merchant, method = b.method, orderTail = b.orderTail,
                sourcePkg = b.sourcePkg, strategy = b.strategy, fingerprint = b.fingerprint,
                createdAt = b.createdAt, category = b.category, subCategory = b.subCategory,
                account = b.account, note = b.note, accountId = b.accountId,
                transferAccountId = b.transferAccountId, assetLinked = b.assetLinked,
                assetKey = b.assetKey, transferFeeCents = b.transferFeeCents,
                feeParentKey = b.feeParentKey, transferAccount = b.transferAccount,
                reimburseStatus = b.reimburseStatus, reimbursedAt = b.reimbursedAt,
                reimburseAccountId = b.reimburseAccountId,
            )
        }
        val snapshot = Backup.Snapshot(999L, backupBills, null, null, assets = plan.remainingSnapshot)
        val roundTripped = Backup.decode(Backup.encode(snapshot))
        assertEquals(1, roundTripped.assets!!.accounts.size)
    }
}
