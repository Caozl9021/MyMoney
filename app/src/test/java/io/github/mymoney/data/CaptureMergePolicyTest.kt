package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Pure policy contracts; these are not Room/device validation claims. */
class CaptureMergePolicyTest {
    private fun draft() = BillDraft(priceCents = 1999, type = BillType.EXPENSE, timeMillis = 1000,
        merchant = null, method = null, orderTail = null, sourcePkg = "com.eg.android.AlipayGphone",
        strategy = "test-contract", historyId = "original-history", captureSessionId = "page-session")
    private fun row(draft: BillDraft = draft()) = RecognitionEntity.fromDraft(draft, "original-identity", 900)
    private fun filled() = draft().copy(merchant = "通知商户", method = "储蓄卡", category = "餐饮",
        subCategory = "午餐", account = "支付宝", note = "来源备注", transactionKey = "order-1",
        timeMillis = 950, hasExplicitTime = true)

    @Test fun emptyFieldsAreEnrichedWithoutChangingStableIdentityOrAmount() {
        val original = row()
        val merged = CaptureMergePolicy.enrich(original, filled(), editing = false)
        assertEquals("通知商户", merged.merchant)
        assertEquals("储蓄卡", merged.method)
        assertEquals("餐饮", merged.category)
        assertEquals("午餐", merged.subCategory)
        assertEquals("支付宝", merged.account)
        assertEquals("来源备注", merged.note)
        assertEquals("order-1", merged.transactionKey)
        assertEquals(original.id, merged.id)
        assertEquals(original.identityKey, merged.identityKey)
        assertEquals(original.recognizedAt, merged.recognizedAt)
        assertEquals(original.priceCents, merged.priceCents)
        assertEquals(original.type, merged.type)
        assertEquals(original.captureSessionId, merged.captureSessionId)
    }

    @Test fun existingHumanValuesAreNeverReplacedByLaterSource() {
        val original = row(draft().copy(merchant = "我的商户", method = "我的方式", category = "自定义分类",
            subCategory = "自定义二级", account = "我的账户", note = "我写的备注"))
            .copy(protectedFields = CaptureFields.ALL)
        val merged = CaptureMergePolicy.enrich(original, filled(), editing = false)
        assertEquals(original.merchant, merged.merchant)
        assertEquals(original.method, merged.method)
        assertEquals(original.category, merged.category)
        assertEquals(original.subCategory, merged.subCategory)
        assertEquals(original.account, merged.account)
        assertEquals(original.note, merged.note)
        assertEquals(original.timeMillis, merged.timeMillis)
        assertFalse(merged.hasExplicitTime)
        assertEquals(CaptureFields.ALL, merged.protectedFields)
    }

    @Test fun protectedEmptyFieldsRepresentIntentionalClearsNotMissingData() {
        val original = row(draft().copy(merchant = "", category = " ", note = ""))
            .copy(protectedFields = CaptureFields.MERCHANT or CaptureFields.CATEGORY or CaptureFields.NOTE or CaptureFields.ACCOUNT)
        val merged = CaptureMergePolicy.enrich(original, filled(), editing = false)
        assertEquals("", merged.merchant)
        assertEquals(" ", merged.category)
        assertEquals("", merged.note)
        assertNull(merged.account)
        assertEquals("储蓄卡", merged.method)
        assertNull(merged.subCategory)
    }

    @Test fun incomingSubcategoryCannotBeAttachedToAnUnrelatedExistingParent() {
        val original = row(draft().copy(category = "餐饮", subCategory = null))
        val incoming = filled().copy(category = "交通", subCategory = "公交")
        val merged = CaptureMergePolicy.enrich(original, incoming, false)
        assertEquals("餐饮", merged.category)
        assertNull(merged.subCategory)
        // Unrelated merchant information may still enrich this identified transaction.
        assertEquals("通知商户", merged.merchant)
    }

    @Test fun intentionallyClearedParentDoesNotAcquireAnIncomingChild() {
        val original = row().copy(protectedFields = CaptureFields.CATEGORY)
        val merged = CaptureMergePolicy.enrich(original, filled().copy(category = "交通", subCategory = "公交"), false)
        assertNull(merged.category)
        assertNull(merged.subCategory)
    }

    @Test fun configurationGenerationIsEphemeralAndNotPersistedOrIdentityBearing() {
        val token = "test-configuration-generation-not-durable"
        val original = filled()
        val captured = original.copy(captureConfigurationToken = token)
        assertEquals(token, captured.captureConfigurationToken)
        assertEquals(CaptureMergePolicy.strongAlias(original), CaptureMergePolicy.strongAlias(captured))
        assertEquals(original.fingerprint, captured.fingerprint)
        val persisted = row(captured)
        assertNull(persisted.toDraft().captureConfigurationToken)
        val json = MiniJson.encode(RecognitionCodec.encode(persisted))
        assertFalse(json.contains("captureConfigurationToken"))
        assertFalse(json.contains(token))
    }

    @Test fun blankUnprotectedFieldsCanBeCompleted() {
        val original = row(draft().copy(merchant = "  ", category = "", note = "\n"))
        val merged = CaptureMergePolicy.enrich(original, filled(), editing = false)
        assertEquals("通知商户", merged.merchant)
        assertEquals("餐饮", merged.category)
        assertEquals("来源备注", merged.note)
    }

    @Test fun openEditorBlocksEveryAutomaticMutationIncludingIdentityAndTime() {
        val original = row()
        assertSame(original, CaptureMergePolicy.enrich(original, filled(), editing = true))
    }

    @Test fun ignoredAndRecordedStatesRemainUntouched() {
        val ignored = row().copy(status = RecognitionStatus.IGNORED)
        val recorded = row().copy(status = RecognitionStatus.RECORDED, billId = 22L)
        assertSame(ignored, CaptureMergePolicy.enrich(ignored, filled(), editing = false))
        assertSame(recorded, CaptureMergePolicy.enrich(recorded, filled(), editing = false))
        assertEquals(22L, recorded.billId ?: -1L)
    }

    @Test fun explicitTimeOnlyEnrichesPreviouslyInferredUnprotectedTime() {
        val original = row()
        val merged = CaptureMergePolicy.enrich(original, filled(), editing = false)
        assertEquals(950L, merged.timeMillis)
        assertTrue(merged.hasExplicitTime)
        val explicit = row(draft().copy(timeMillis = 800, hasExplicitTime = true))
        assertEquals(800L, CaptureMergePolicy.enrich(explicit, filled(), false).timeMillis)
        val protected = original.copy(protectedFields = CaptureFields.TIME)
        val protectedMerge = CaptureMergePolicy.enrich(protected, filled(), false)
        assertEquals(original.timeMillis, protectedMerge.timeMillis)
        assertFalse(protectedMerge.hasExplicitTime)
        val onlyInferred = CaptureMergePolicy.enrich(original, filled().copy(hasExplicitTime = false), false)
        assertEquals(original.timeMillis, onlyInferred.timeMillis)
        assertFalse(onlyInferred.hasExplicitTime)
    }

    @Test fun strongAliasesNamespaceProviderTypeAndFullTransaction() {
        val original = filled()
        val alias = CaptureMergePolicy.strongAlias(original)
        assertNotNull(alias)
        assertEquals(alias, CaptureMergePolicy.strongAlias(original.copy(strategy = "page-rule", captureSessionId = "new-page", historyId = "other")))
        assertNotEquals(alias, CaptureMergePolicy.strongAlias(original.copy(sourcePkg = "com.tencent.mm")))
        assertNotEquals(alias, CaptureMergePolicy.strongAlias(original.copy(type = BillType.REFUND)))
        assertNotEquals(alias, CaptureMergePolicy.strongAlias(original.copy(transactionKey = "order-2")))
        assertNull(CaptureMergePolicy.strongAlias(original.copy(transactionKey = null)))
        assertNull(CaptureMergePolicy.strongAlias(original.copy(transactionKey = " \n")))
        assertFalse(alias!!.contains("order-1"))
    }

    @Test fun conflictsCheckFactsWithoutTreatingMetadataAsConflict() {
        val original = filled()
        assertFalse(CaptureMergePolicy.conflicts(original, original.copy(merchant = "另一来源商户", note = "另一备注")))
        assertTrue(CaptureMergePolicy.conflicts(original, original.copy(priceCents = 2000)))
        assertTrue(CaptureMergePolicy.conflicts(original, original.copy(type = BillType.REFUND)))
        assertTrue(CaptureMergePolicy.conflicts(original, original.copy(transactionKey = "order-2")))
        assertTrue(CaptureMergePolicy.conflicts(original, original.copy(timeMillis = 951)))
        assertFalse(CaptureMergePolicy.conflicts(original, original.copy(timeMillis = 2000, hasExplicitTime = false)))
        assertFalse(CaptureMergePolicy.conflicts(original, original.copy(transactionKey = null)))
    }

    @Test fun changedMaskTracksEachEditableFieldIncludingIntentionalClears() {
        val original = filled()
        val cases = listOf(CaptureFields.AMOUNT to original.copy(priceCents = 2000),
            CaptureFields.TYPE to original.copy(type = BillType.INCOME),
            CaptureFields.TIME to original.copy(timeMillis = 999),
            CaptureFields.MERCHANT to original.copy(merchant = null),
            CaptureFields.METHOD to original.copy(method = ""),
            CaptureFields.CATEGORY to original.copy(category = null),
            CaptureFields.SUBCATEGORY to original.copy(subCategory = null),
            CaptureFields.ACCOUNT to original.copy(account = null),
            CaptureFields.NOTE to original.copy(note = ""))
        var all = 0L
        for ((expected, modified) in cases) {
            assertEquals("field flag $expected", expected, CaptureFields.changed(original, modified))
            all = all or expected
        }
        assertEquals(CaptureFields.ALL, all)
        assertEquals(0L, CaptureFields.changed(original, original.copy(historyId = "other", strategy = "other-source")))
        assertEquals(CaptureFields.ALL, CaptureFields.changed(original, original.copy(priceCents = 2000,
            type = BillType.INCOME, timeMillis = 999, merchant = null, method = null, category = null,
            subCategory = null, account = null, note = null)))
    }

    @Test fun editorLocksAreReferenceCountedAndReleaseIsSafe() {
        val id = "lock-test-${UUID.randomUUID()}"
        val other = "lock-test-${UUID.randomUUID()}"
        try {
            assertFalse(CaptureEditLocks.contains(id))
            CaptureEditLocks.acquire(id)
            CaptureEditLocks.acquire(id)
            CaptureEditLocks.acquire(other)
            CaptureEditLocks.release(id)
            assertTrue(CaptureEditLocks.contains(id))
            CaptureEditLocks.release(id)
            assertFalse(CaptureEditLocks.contains(id))
            assertTrue(CaptureEditLocks.contains(other))
            CaptureEditLocks.release(id)
            assertFalse(CaptureEditLocks.contains(id))
        } finally {
            CaptureEditLocks.release(id)
            CaptureEditLocks.release(id)
            CaptureEditLocks.release(other)
        }
    }
}
