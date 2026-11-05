package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import com.example.billkit.visual.VisualBillResult
import com.example.billkit.visual.VisualFields
import org.junit.Assert.*
import org.junit.Test

class OcrEvidencePolicyTest {
    private fun draft() = BillDraft(350, BillType.EXPENSE, 1000, null, null, null,
        "com.tencent.mm", "page", historyId="ocr-target", transactionKey=null)
    private fun row() = RecognitionEntity.fromDraft(draft(), "stable-page", 1000)
    private fun result() = VisualBillResult(amountCents=350, type=BillType.EXPENSE, explicitTime=true,
        fields=VisualFields(merchant="示例商户", method="零钱", productName="商品说明", timeMillis=900,
            orderCandidate="12345678901234567890123456789012"))
    private fun account(id: String, name: String, aliases: String="[]", archived: Boolean=false) =
        AssetAccount(id,name,aliases=aliases,archived=archived,createdAt=1,updatedAt=1)

    @Test fun fillsMissingFieldsWithoutAcquiringAnOcrOrderIdentityOrChangingNote() {
        val original=row().copy(note="既有备注",noteOrigin=NoteTemplate.TEMPLATE)
        val decision=OcrEvidencePolicy.apply(original,draft(),result(),resolvedAccount="微信")
        assertEquals("added",decision.outcome)
        assertEquals("示例商户",decision.row.merchant)
        assertEquals("零钱",decision.row.method)
        assertEquals("微信",decision.row.account)
        assertEquals("商品说明",decision.row.productName)
        assertEquals(900L,decision.row.timeMillis)
        assertTrue(decision.row.hasExplicitTime)
        assertEquals(original.identityKey,decision.row.identityKey)
        assertEquals(original.priceCents,decision.row.priceCents)
        assertEquals(original.type,decision.row.type)
        assertEquals("既有备注",decision.row.note)
        assertEquals(original.noteOrigin,decision.row.noteOrigin)
        assertNull(decision.row.transactionKey)
        assertNull(decision.row.orderTail)
    }

    @Test fun persistentAndLiveTouchedFieldsIncludingEmptyValuesStayProtected() {
        val original=row().copy(merchant="",method="",account=null,protectedFields=CaptureFields.MERCHANT)
        val decision=OcrEvidencePolicy.apply(original,draft(),result(),
            CaptureFields.METHOD or CaptureFields.ACCOUNT or CaptureFields.TIME or CaptureFields.NOTE,"微信")
        assertEquals(original,decision.row)
        assertEquals("no-change",decision.outcome)
    }

    @Test fun expectedEditorTouchMaskAlsoProtectsExplicitClears() {
        val decision=OcrEvidencePolicy.apply(row(),draft().copy(captureTouchedFields=CaptureFields.METHOD),result())
        assertNull(decision.row.method)
        assertEquals("示例商户",decision.row.merchant)
    }

    @Test fun nonemptyUnknownValuesAndExplicitTimeAreNeverOverwritten() {
        val original=row().copy(merchant="原商户",method="原支付方式",account="人工账户",productName="原商品",
            timeMillis=1234,hasExplicitTime=true)
        assertEquals(original,OcrEvidencePolicy.apply(original,draft(),result(),resolvedAccount="微信").row)
    }

    @Test fun conflictingAmountsDirectionsPackagesAndStrongIdentitiesCannotSupplement() {
        for(expected in listOf(draft().copy(priceCents=351),draft().copy(type=BillType.INCOME),
            draft().copy(sourcePkg="com.eg.android.AlipayGphone"),draft().copy(historyId="another-target"))) {
            val decision=OcrEvidencePolicy.apply(row(),expected,result())
            assertEquals("conflict",decision.outcome);assertEquals(row(),decision.row)
        }
        assertEquals("conflict",OcrEvidencePolicy.apply(row().copy(transactionKey="trusted-order"),
            draft().copy(transactionKey="different-order"),result()).outcome)
        assertEquals("conflict",OcrEvidencePolicy.apply(row(),draft(),result().copy(amountCents=351)).outcome)
        assertEquals("conflict",OcrEvidencePolicy.apply(row(),draft(),result().copy(type=BillType.INCOME)).outcome)
    }

    @Test fun unusableOrMissingAmountDoesNotWriteEvenOtherwiseValidFields() {
        assertEquals("unusable",OcrEvidencePolicy.apply(row(),draft(),result().copy(issue="ambiguous-amount")).outcome)
        assertEquals("conflict",OcrEvidencePolicy.apply(row(),draft(),result().copy(amountCents=null)).outcome)
    }

    @Test fun terminalAndReviewRowsRemainCompletelyUntouched() {
        for(original in listOf(row().copy(status=RecognitionStatus.RECORDED),row().copy(status=RecognitionStatus.IGNORED),
            row().copy(reviewOf="needs-review"))) {
            val decision=OcrEvidencePolicy.apply(original,draft(),result())
            assertEquals("terminal",decision.outcome);assertSame(original,decision.row)
        }
    }

    @Test fun invalidOrOversizedCandidatesAreRejectedIndividually() {
        val decision=OcrEvidencePolicy.apply(row(),draft(),result().copy(fields=VisualFields(
            merchant="商".repeat(81),method="银行卡" + 0.toChar(),productName="物".repeat(121))),resolvedAccount="微信")
        assertEquals("no-change",decision.outcome)
        assertEquals(row(),decision.row)
        assertTrue(decision.changedFields.isEmpty())
    }

    @Test fun differingOcrMethodDoesNotBindItsAccountToExistingPaymentMethod() {
        val original=row().copy(method="工商银行")
        val decision=OcrEvidencePolicy.apply(original,draft(),result(),resolvedAccount="微信")
        assertEquals("工商银行",decision.row.method);assertNull(decision.row.account)
    }

    @Test fun walletSynonymsResolveOnlyOneExistingActiveAccount() {
        val wallet=account("wallet","微信")
        assertEquals("微信",OcrEvidencePolicy.resolveAccount("零钱",listOf(wallet)))
        assertNull(OcrEvidencePolicy.resolveAccount("零钱",listOf(wallet,account("other","微信钱包"))))
        assertNull(OcrEvidencePolicy.resolveAccount("零钱",listOf(wallet.copy(archived=true))))
        assertNull(OcrEvidencePolicy.resolveAccount("余额",listOf(account("alipay","支付宝"))))
    }

    @Test fun fullBankAliasCanResolveButArbitraryBankSubstringCannotGuess() {
        val a=account("a","工资卡","[\"工商银行储蓄卡(1234)\"]")
        val b=account("b","备用卡","[\"工商银行储蓄卡(5678)\"]")
        assertEquals("工资卡",OcrEvidencePolicy.resolveAccount("工商银行储蓄卡(1234)",listOf(a,b)))
        assertNull(OcrEvidencePolicy.resolveAccount("工商银行",listOf(a,b)))
    }
}
