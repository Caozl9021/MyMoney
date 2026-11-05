package io.github.mymoney

import com.example.billkit.*
import com.example.billkit.spec.SpecRuleObject
import com.example.billkit.spec.SpecDraftAdapter
import io.github.mymoney.data.*
import org.junit.Assert.*
import org.junit.Test

class NoteFillPolicyTest {
    private val shop="淘宝闪购"
    private val product="示例·胡辣汤·水煎包(园区店)外卖订单"
    private fun draft(goods:String?=product)=BillDraft(1753,BillType.EXPENSE,1793506849000,shop,"储蓄卡",null,"com.eg.android.AlipayGphone","test",transactionKey="order-test",hasExplicitTime=true,productName=goods,productSource=goods?.let{"explicit-product-v1:ACCESSIBILITY"})
    private fun apply(d:BillDraft=draft(),c:NoteFillConfig=NoteFillConfig())=NoteFillPolicy.apply(d,c,"{商户}")
    private fun rule(mode:NoteFillMode=NoteFillMode.PRODUCT,text:String="")=MerchantNoteRule("com.eg.android.AlipayGphone",shop,"EXPENSE",mode,text)
    private fun config(r:MerchantNoteRule=rule())=NoteFillConfig(false,listOf(r))
    private fun invalid(block:()->Unit){try{block();fail("must reject")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun newProductBecomesNoteWithoutChangingMerchantMoneyOrIdentity() {
        val raw=draft();val hit=apply(raw)
        assertEquals(product,hit.note);assertEquals(NoteFillPolicy.PRODUCT,hit.noteOrigin)
        assertEquals(raw,hit.copy(note=null,noteOrigin=null,noteTemplateSnapshot=null))
    }
    @Test fun eachPaymentUsesItsOwnProductAndMissingExplicitProductRuleStaysEmpty() {
        val c=config();assertEquals(product,apply(draft(),c).note)
        assertEquals("另一家店本次商品",apply(draft("另一家店本次商品"),c).note)
        assertNull(apply(draft(null),c).note)
    }
    @Test fun globalDefaultWithoutGoodsKeepsExistingTemplateAndCanBeDisabled() {
        assertEquals(shop,apply(draft(null)).note)
        assertEquals(shop,apply(draft(),NoteFillConfig(false)).note)
        assertNull(NoteFillPolicy.apply(draft(null),NoteFillConfig(),"{商品}").note)
    }
    @Test fun fixedAndDisabledRulesOverrideGlobalProductPreference() {
        assertEquals("我的常用备注",apply(draft(),NoteFillConfig(true,listOf(rule(NoteFillMode.FIXED,"我的常用备注")))).note)
        val off=apply(draft(),NoteFillConfig(true,listOf(rule(NoteFillMode.NONE))))
        assertNull(off.note);assertEquals(NoteFillPolicy.DISABLED,off.noteOrigin)
    }
    @Test fun exactAppTypeAndMerchantDoNotBleedAcrossPayments() {
        val c=config(rule(NoteFillMode.FIXED,"固定"))
        for(d in listOf(draft().copy(sourcePkg="com.tencent.mm"),draft().copy(type=BillType.REFUND),draft().copy(merchant="淘宝闪购其他商家")))assertNotEquals("固定",apply(d,c).note)
        assertEquals("固定",apply(draft().copy(merchant=" 淘宝闪购 "),c).note)
    }
    @Test fun manualNotesAcceptedSuggestionsAndExplicitClearingAlwaysWin() {
        for(origin in listOf(NoteTemplate.USER,NoteTemplate.SUGGESTION))for(note in listOf(null,"用户备注")) {
            val d=draft().copy(note=note,noteOrigin=origin);assertEquals(d,apply(d,config(rule(NoteFillMode.NONE))))
        }
        val legacy=draft().copy(note="未知来源已有备注");assertEquals(legacy,apply(legacy))
        val unknown=draft().copy(note=product);assertEquals(unknown,apply(unknown,config(rule(NoteFillMode.NONE))))
    }
    @Test fun trustedSourceGoodsAreNotMistakenForManualNote() {
        val d=SpecDraftAdapter.toDraft(SpecRuleObject(type="Expend",money=17.53,shopName=shop,shopItem=product,currency="CNY"),"com.eg.android.AlipayGphone","spec-product",1793506849000)!!
        assertEquals(NoteFillPolicy.PRODUCT,apply(d).noteOrigin)
        assertNull(apply(d,config(rule(NoteFillMode.NONE))).note)
    }
    @Test fun automaticProductDoesNotMutateTemplateAndLimitsUnicodeSafely() {
        assertEquals(shop,NoteTemplate.render(draft(),"{商户}·{商品}"))
        val d=apply(draft("😀".repeat(60)))
        assertEquals(60,d.note!!.codePointCount(0,d.note!!.length));assertFalse(d.note!!.endsWith("\uD83D"))
        assertEquals(shop,apply(draft("x".repeat(121))).note)
    }
    @Test fun transferDoesNotUseMerchantProductRules() {
        assertEquals(shop,apply(draft().copy(type=BillType.TRANSFER)).note)
    }
    @Test fun unchangedPendingAutomaticTemplateCanUpgradeFromCurrentGoods() {
        val initial=apply(draft(null));val row=RecognitionEntity.fromDraft(initial,"identity",1793506849000)
        val incoming=apply(draft());val enriched=CaptureMergePolicy.enrich(row,incoming,false)
        assertEquals(product,enriched.note);assertEquals(product,enriched.productName);assertEquals(NoteFillPolicy.PRODUCT,enriched.noteOrigin)
        assertEquals(row.id,enriched.id);assertEquals(row.priceCents,enriched.priceCents);assertEquals(row.identityKey,enriched.identityKey)
    }
    @Test fun manualProtectedFixedOffEditingAndRecordedRowsNeverUpgradeNotes() {
        val incoming=apply(draft());val base=RecognitionEntity.fromDraft(apply(draft(null)),"identity",1793506849000)
        for(row in listOf(base.copy(note="人工",noteOrigin=NoteTemplate.USER),base.copy(note=null,noteOrigin=NoteTemplate.USER),base.copy(note="固定",noteOrigin=NoteFillPolicy.FIXED),base.copy(note=null,noteOrigin=NoteFillPolicy.DISABLED),base.copy(protectedFields=CaptureFields.NOTE),base.copy(status=RecognitionStatus.RECORDED)))assertEquals(row.note,CaptureMergePolicy.enrich(row,incoming,false).note)
        CaptureEditLocks.acquire(base.id)
        try {
            CaptureEditLocks.touch(base.id, CaptureFields.NOTE)
            assertEquals(base.note,CaptureMergePolicy.enrich(base,incoming,true).note)
        } finally { CaptureEditLocks.release(base.id) }
    }
    @Test fun conflictingProductEvidenceDoesNotChangeAlreadyObservedProductNote() {
        val row=RecognitionEntity.fromDraft(apply(draft()),"identity",1793506849000)
        val changed=CaptureMergePolicy.enrich(row,apply(draft("不同商品")),false)
        assertEquals(product,changed.note);assertEquals(product,changed.productName)
    }
    @Test fun codecRoundTripRejectsBadModesUnknownFieldsDuplicatesAndOversize() {
        val c=NoteFillConfig(true,listOf(rule(),rule(NoteFillMode.FIXED,"😀 常用").copy(merchant="其他店"),rule(NoteFillMode.NONE).copy(merchant="第三店")))
        assertEquals(c,NoteFillCodec.decode(NoteFillCodec.encode(c)))
        invalid{NoteFillCodec.encode(c.copy(rules=listOf(rule(),rule())))}
        invalid{NoteFillCodec.encode(config(rule(NoteFillMode.FIXED,"")))}
        invalid{NoteFillCodec.encode(config(rule(NoteFillMode.FIXED,"x".repeat(61))))}
        invalid{NoteFillCodec.decode(NoteFillCodec.encode(c).replace("PRODUCT","EXECUTE"))}
        invalid{NoteFillCodec.decode(NoteFillCodec.encode(c).replace("\"schemaVersion\":1","\"schemaVersion\":2"))}
        invalid{NoteFillCodec.decode("x".repeat(128*1024+1))}
    }
    @Test fun actualVisibleNodeShapeProvidesCurrentDescriptionWithoutChangingMoney() {
        val snapshot=UiSnapshot("com.eg.android.AlipayGphone","com.alipay.mobile.bill.detail.BillDetailActivity",listOf("账单详情",shop,"支出17.53元","交易成功","网上消费红包","-0.47","支付时间","2026-11-01 12:20:49","付款方式","工商银行储蓄卡(0000)","商品说明",product,product,"支付奖励","立即领取2积分","更多"))
        val result=BillPipeline().process(snapshot,1793506849000)
        assertTrue(result.toString(),result is BillPipeline.Outcome.Draft)
        val hit=result as BillPipeline.Outcome.Draft
        assertEquals(product,hit.draft.productName);assertEquals(1753,hit.draft.priceCents)
        assertEquals(product,apply(hit.draft).note)
    }
    @Test fun malformedOrAmbiguousGoodsCannotBecomeAnAutomaticNote() {
        assertNull(ProductExtractor.extract(listOf("商品说明","A","商品名称","B")))
        assertNull(ProductExtractor.extract(listOf("商品说明",product),true))
        assertEquals(shop,apply(draft(ProductExtractor.extract(listOf("商品总额","17.53元")))).note)
    }
    @Test fun json8PolicyRoundTripAndOldMissingFieldSemantics() {
        val c=config(rule(NoteFillMode.FIXED,"常用备注"))
        val snapshot=Backup.Snapshot(1793506849000,emptyList(),settings=null,ledger=Backup.Ledger(emptyList(),emptyList(),emptyList(),"{商户}",noteFillPolicy=NoteFillCodec.encode(c)))
        val restored=Backup.decode(Backup.encode(snapshot));assertEquals(c,NoteFillCodec.decode(restored.ledger!!.noteFillPolicy!!))
        val old=snapshot.copy(ledger=snapshot.ledger!!.copy(noteFillPolicy=null));assertNull(Backup.decode(Backup.encode(old)).ledger!!.noteFillPolicy)
        val invalidRoot=(MiniJson.decode(Backup.encode(snapshot)) as Map<*,*>).toMutableMap();val ledger=(invalidRoot["ledger"] as Map<*,*>).toMutableMap();ledger["noteFillPolicy"]=mapOf("schemaVersion" to 99);invalidRoot["ledger"]=ledger
        invalid{Backup.decode(MiniJson.encode(invalidRoot))}
    }
}
