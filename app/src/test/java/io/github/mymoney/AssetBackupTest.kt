package io.github.mymoney

import io.github.mymoney.data.*
import org.junit.Assert.*
import org.junit.Test

class AssetBackupTest {
    private fun defaults() = AssetSnapshot(LedgerConfig.DEFAULT_ACCOUNTS.mapIndexed { index, name ->
        AssetAccount(java.util.UUID.nameUUIDFromBytes(("legacy-account:" + name).toByteArray(Charsets.UTF_8)).toString(),
            name, sortOrder = index, createdAt = 100, updatedAt = 100)
    }, emptyList())

    @Test fun onlyTheExactUnusedDefaultSnapshotCanInitializeAssets() {
        val original = defaults()
        assertTrue(AssetBackupCodec.isUntouchedDefaultSnapshot(original, LedgerConfig.DEFAULT_ACCOUNTS))
        assertTrue(AssetBackupCodec.isUntouchedDefaultSnapshot(original.copy(accounts = original.accounts.reversed()), LedgerConfig.DEFAULT_ACCOUNTS))
        assertFalse(AssetBackupCodec.isUntouchedDefaultSnapshot(original.copy(accounts = original.accounts.dropLast(1)), LedgerConfig.DEFAULT_ACCOUNTS))
        assertFalse(AssetBackupCodec.isUntouchedDefaultSnapshot(AssetSnapshot(emptyList(), emptyList()), emptyList()))
    }

    @Test fun everyAccountCustomizationDisqualifiesDefaultInitialization() {
        val original = defaults()
        val a = original.accounts.first()
        val changed = listOf(a.copy(id = "custom"), a.copy(name = "自定义"), a.copy(kind = "CASH"),
            a.copy(aliases = "[\"别名\"]"), a.copy(sortOrder = 1), a.copy(archived = true),
            a.copy(included = false), a.copy(initialized = true), a.copy(createdAt = 99), a.copy(updatedAt = 101))
        changed.forEach { row ->
            assertFalse("edited account must be protected: $row", AssetBackupCodec.isUntouchedDefaultSnapshot(
                original.copy(accounts = listOf(row) + original.accounts.drop(1)), LedgerConfig.DEFAULT_ACCOUNTS))
        }
    }

    @Test fun duplicateAccountsAndAnyPostingDisqualifyDefaultInitialization() {
        val original = defaults()
        assertFalse(AssetBackupCodec.isUntouchedDefaultSnapshot(original.copy(accounts =
            listOf(original.accounts.first()) + original.accounts.dropLast(1)), LedgerConfig.DEFAULT_ACCOUNTS))
        assertFalse(AssetBackupCodec.isUntouchedDefaultSnapshot(original.copy(entries =
            listOf(entry(accountId = original.accounts.first().id))), LedgerConfig.DEFAULT_ACCOUNTS))
    }

    @Test fun defaultEligibilityDoesNotRelaxStrictUuidMerge() {
        val original = defaults()
        val initialized = original.copy(accounts = original.accounts.map { it.copy(initialized = true, updatedAt = 200) })
        rejects { AssetBackupCodec.merge(original, initialized) }
        rejects { AssetBackupCodec.merge(initialized, original) }
    }

    private fun account(id: String = "cash", name: String = "现金", initialized: Boolean = true) =
        AssetAccount(id, name, kind = "CASH", initialized = initialized, createdAt = 1, updatedAt = 1)
    private fun entry(id: String = "opening", accountId: String = "cash", cents: Long = 100_000) =
        AssetEntry(id, "op-$id", accountId, cents, "OPENING", "当前余额", 1)
    private fun snapshot() = AssetSnapshot(listOf(account()), listOf(entry()))
    private fun bill() = Backup.Bill(1999, "EXPENSE", 2, "早餐", null, null, "manual", "手动", "fp", 2,
        "餐饮", null, "现金", null, accountId = "cash", assetLinked = true, assetKey = "bill-1")
    private fun backup() = Backup.Snapshot(3, listOf(bill()), null, null, assets = AssetSnapshot(
        listOf(account()), listOf(entry(), AssetEntry("spend", "op-spend", "cash", -1999, "BILL", "早餐", 2, "bill-1"))))
    private fun rejects(block: () -> Unit) {
        assertTrue("坏数据必须拒绝", runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun currentFormatRoundTripPreservesEveryAssetFieldAndStableBillIdentity() {
        val original = backup()
        assertEquals(original, Backup.decode(Backup.encode(original)))
        assertEquals(98_001L, AssetMath.balance("cash", original.assets!!.entries))
        assertTrue(Backup.encode(original).contains("\"format\":${Backup.FORMAT}"))
    }

    @Test fun missingAssetSectionDiffersFromExplicitEmptySection() {
        val old = Backup.decode("""{"format":2,"bills":[]}""")
        val empty = Backup.decode("""{"format":3,"bills":[],"assets":{"accounts":[],"entries":[]}}""")
        assertNull(old.assets)
        assertEquals(AssetSnapshot(emptyList(), emptyList()), empty.assets)
    }

    @Test fun repeatedMergeDoesNotDoubleOpeningOrBillPostings() {
        val a = backup().assets!!
        val once = AssetBackupCodec.merge(AssetSnapshot(emptyList(), emptyList()), a)
        val twice = AssetBackupCodec.merge(once, a)
        assertEquals(once, twice)
        assertEquals(98_001L, AssetMath.balance("cash", twice.entries))
    }

    @Test fun sameUuidWithDifferentContentsRejectsInsteadOfOverwriting() {
        val a = snapshot()
        rejects { AssetBackupCodec.merge(a, a.copy(accounts = listOf(account().copy(name = "钱包")))) }
        rejects { AssetBackupCodec.merge(a, a.copy(entries = listOf(entry(cents = 90_000)))) }
    }

    @Test fun sameNameDifferentUuidRequiresExplicitResolution() {
        rejects { AssetBackupCodec.merge(snapshot(), AssetSnapshot(listOf(account("new-id")), listOf(entry("new-opening", "new-id")))) }
    }

    @Test fun danglingAccountAndReversalReferencesCannotBeRestored() {
        rejects { AssetBackupCodec.validate(snapshot().copy(entries = listOf(entry(accountId = "missing")))) }
        rejects { AssetBackupCodec.validate(snapshot().copy(entries = listOf(entry().copy(kind = "REVERSAL", reversalOf = "missing")))) }
    }

    @Test fun malformedReversalCannotHideAnIncorrectBalance() {
        val original = entry()
        val valid = original.copy(id = "reverse", operationId = "op-reverse", kind = "REVERSAL", deltaCents = -100_000, reversalOf = original.id, createdAt = 2)
        AssetBackupCodec.validate(snapshot().copy(entries = listOf(original, valid)))
        rejects { AssetBackupCodec.validate(snapshot().copy(entries = listOf(original, valid.copy(deltaCents = -99_999)))) }
        rejects { AssetBackupCodec.validate(snapshot().copy(entries = listOf(original, valid, valid.copy(id = "second-reverse")))) }
    }

    @Test fun unknownBalanceCannotCarryPostings() {
        rejects { AssetBackupCodec.validate(snapshot().copy(accounts = listOf(account(initialized = false)))) }
    }

    @Test fun aliasesAcrossAccountsCannotBeSilentlyRebound() {
        rejects { AssetBackupCodec.validate(AssetSnapshot(listOf(account(), account("bank", "银行").copy(aliases = "[\"现金\"]")), listOf(entry()))) }
    }

    @Test fun badAssetIntegerAndBooleanAreRejectedBeforeRestore() {
        val raw = Backup.encode(backup())
        rejects { Backup.decode(raw.replace("\"deltaCents\":100000", "\"deltaCents\":100000.5")) }
        rejects { Backup.decode(raw.replace("\"initialized\":true", "\"initialized\":\"true\"")) }
        rejects { Backup.decode(raw.replace("\"deltaCents\":100000", "\"deltaCents\":9223372036854775808")) }
        rejects { Backup.decode(raw.replace("\"aid\":\"cash\"", "\"aid\":123")) }
    }

    @Test fun balanceOverflowAndDuplicateEntryIdsAreRejected() {
        rejects { AssetBackupCodec.validate(snapshot().copy(entries = listOf(entry(cents = Long.MAX_VALUE), entry("extra", cents = 1)))) }
        rejects { AssetBackupCodec.validate(snapshot().copy(entries = listOf(entry(), entry()))) }
    }

    @Test fun linkedBillMustMatchAllItsEffectivePostings() {
        val source = backup()
        rejects { Backup.encode(source.copy(bills = listOf(bill().copy(priceCents = 2000)))) }
        rejects { Backup.encode(source.copy(bills = listOf(bill().copy(accountId = "missing")))) }
        rejects { Backup.encode(source.copy(assets = null)) }
    }

    @Test fun deletedBillsMayRetainAuditableReversedPostingsWithoutDanglingAccount() {
        val source = backup().assets!!
        val expense = source.entries.last()
        val reversed = expense.copy(id = "reverse-spend", operationId = "delete-op", kind = "REVERSAL", deltaCents = 1999, createdAt = 3, reversalOf = expense.id)
        val oldLedger = source.copy(entries = source.entries + reversed)
        val encoded = Backup.encode(Backup.Snapshot(4, emptyList(), null, null, assets = oldLedger))
        val restored = Backup.decode(encoded).assets!!
        assertEquals(100_000L, AssetMath.balance("cash", restored.entries))
    }

    @Test fun transfersRequireTwoDifferentExistingInitializedAccountsAndBalancedPostings() {
        val cash = account()
        val credit = account("credit", "信用卡").copy(kind = "CREDIT")
        val principal = bill().copy(type = "TRANSFER", priceCents = 10_000, transferAccountId = "credit")
        val entries = listOf(entry(), entry("credit-opening", "credit", -30_000),
            AssetEntry("out", "transfer-op", "cash", -10_000, "TRANSFER", "还款", 2, "bill-1"),
            AssetEntry("in", "transfer-op", "credit", 10_000, "TRANSFER", "还款", 2, "bill-1"))
        val src = Backup.Snapshot(4, listOf(principal), null, null, assets = AssetSnapshot(listOf(cash, credit), entries))
        assertEquals(src, Backup.decode(Backup.encode(src)))
        assertEquals(70_000L, AssetMath.summary(src.assets!!.accounts, entries).net)
        rejects { Backup.encode(src.copy(bills = listOf(principal.copy(transferAccountId = "cash")))) }
        rejects { Backup.encode(src.copy(assets = src.assets!!.copy(entries = entries.dropLast(1)))) }
    }
    @Test fun transferFeeMustBeExactlyOneMatchingExpenseAndSurvivesRoundTrip() {
        val source = backup()
        val principal = bill().copy(type = "TRANSFER", priceCents = 10_000, transferAccountId = "credit", transferFeeCents = 200)
        val fee = bill().copy(priceCents = 200, assetKey = "bill-1-fee", feeParentKey = "bill-1", fingerprint = "fee-fp")
        val credit = account("credit", "信用卡").copy(kind = "CREDIT")
        val entries = listOf(entry(), entry("credit-opening", "credit", -30_000),
            AssetEntry("out", "transfer-op", "cash", -10_000, "TRANSFER", "还款", 2, "bill-1"),
            AssetEntry("in", "transfer-op", "credit", 10_000, "TRANSFER", "还款", 2, "bill-1"),
            AssetEntry("fee", "fee-op", "cash", -200, "BILL", "手续费", 2, "bill-1-fee"))
        val src = source.copy(bills = listOf(principal, fee), assets = AssetSnapshot(listOf(account(), credit), entries))
        assertEquals(src, Backup.decode(Backup.encode(src)))
        assertEquals(69_800L, AssetMath.summary(src.assets!!.accounts, entries).net)
        rejects { Backup.encode(src.copy(bills = listOf(principal))) }
        rejects { Backup.encode(src.copy(bills = listOf(principal.copy(transferFeeCents = 0), fee))) }
        rejects { Backup.encode(src.copy(bills = listOf(principal, fee.copy(priceCents = 201)))) }
    }

    @Test fun unlinkedFlagCannotConcealActivePostingsButReversedHistoryIsAllowed() {
        val source = backup()
        val unlinked = source.copy(bills = listOf(bill().copy(assetLinked = false)))
        rejects { Backup.encode(unlinked) }
        val expense = source.assets!!.entries.last()
        val reversal = expense.copy(id = "reversed-unlinked", operationId = "unlink-op", kind = "REVERSAL",
            deltaCents = 1999, createdAt = 3, reversalOf = expense.id)
        val historical = unlinked.copy(assets = source.assets!!.copy(entries = source.assets!!.entries + reversal))
        assertEquals(historical, Backup.decode(Backup.encode(historical)))
        // Old-format replacement may retain independent postings for removed bills.
        val orphanHistory = source.copy(bills = emptyList())
        assertEquals(orphanHistory, Backup.decode(Backup.encode(orphanHistory)))
    }

}
