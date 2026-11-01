package com.example.billkit.spec

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class PersonalRuleTest {
    private val rule=PersonalRule("personal_fixture01","美团还款",keyword="美团金融还款",inputType="DATA")
    private fun raw(title: String="已支付¥26.77",desc: String="使用零钱支付¥26.77",shop: String="美团金融还款",extra: Map<String,Any?> = emptyMap()) =
        SpecJson.encode(mapOf("mMap" to (mapOf("source" to "微信支付","title" to title,"description" to desc,"display_name" to shop,"t" to 1793419110639L)+extra)))
    private fun match(value: String, r: PersonalRule=rule)=PersonalRuleEngine.match(listOf(r),r.app,r.inputType,value)
    private fun reject(block: ()->Unit) { try {block();fail("must reject")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){} }
    @Test fun timestampIsOptionalForOldFilesAndRoundTripsWhenPresent() {
        val old=rule.json().toMutableMap().apply {remove("updatedAt")}
        assertNull(PersonalRuleCodec.decodeRows(listOf(old)).single().updatedAt)
        val dated=rule.copy(updatedAt=1793506849000)
        assertEquals(dated,PersonalRuleCodec.decode(PersonalRuleCodec.export(listOf(dated))).single())
        reject {PersonalRuleCodec.decodeRows(listOf(old+mapOf("updatedAt" to 1.5)))}
        reject {PersonalRuleCodec.decodeRows(listOf(old+mapOf("updatedAt" to -1)))}
    }
    @Test fun shortSuccessfulReceiptMatchesLiteralMerchantAndKeepsCents() {
        val hit=match(raw())!!
        assertEquals(2677,hit.bill.priceCents);assertEquals("美团金融还款",hit.bill.shopName);assertEquals("零钱",hit.bill.accountNameFrom)
        assertEquals(rule.id,hit.ruleId);assertFalse(hit.autoRecord)
        assertEquals(1999,match(raw("已支付¥19.99","使用零钱支付¥19.99"))!!.bill.priceCents)
    }
    @Test fun statusCannotBeBypassedByPersonalConditions() {
        for(status in listOf("支付失败","支付中","待付款","未支付","取消支付","交易成功")) assertNull(status,match(raw(status)))
        assertNull(match(raw(desc="交易状态：支付失败\n使用零钱支付¥26.77")))
        assertNull(match(raw("已收款¥26.77")))
        assertNull(match(raw(extra=mapOf("source" to "普通聊天"))))
        assertNull(match(raw(desc="转账给好友，支付成功，使用零钱支付¥26.77")))
    }
    @Test fun ambiguousZeroForeignAndMalformedAmountsNeverMatch() {
        for((title,desc) in listOf("已支付¥26.77" to "使用零钱支付¥20.00","已支付¥0.00" to "支付成功",
            "已支付¥19.999" to "支付成功","已支付¥-19.99" to "支付成功","已支付19.99美元" to "支付成功",
            "已支付¥1,23.00" to "支付成功")) assertNull(title,match(raw(title,desc)))
        assertNull(match(raw(extra=mapOf("currency" to "USD"))))
    }
    @Test fun onlySelectedAmountLocationCanMatchButConflictsStillReject() {
        assertNull(match(raw("支付成功","金额：¥26.77"),rule.copy(amountSource="TITLE")))
        assertNotNull(match(raw("支付成功","金额：¥26.77"),rule.copy(amountSource="DESCRIPTION")))
        assertNull(match(raw("已支付¥26.77","金额：¥12.34"),rule.copy(amountSource="TITLE")))
    }
    @Test fun disabledWrongScopeOrKeywordDoesNotMatch() {
        assertNull(match(raw(),rule.copy(enabled=false)))
        assertNull(match(raw(),rule.copy(keyword="其他商户")))
        assertNull(PersonalRuleEngine.match(listOf(rule),"com.eg.android.AlipayGphone","DATA",raw()))
    }
    @Test fun userSelectedDirectionAndFixedFieldsAreExplicit() {
        val r=rule.copy(billType="Income",merchant="自定商户",method="自定方式")
        val hit=match(raw("已收款¥26.77","收款已到账"),r)!!
        assertEquals("Income",hit.bill.type);assertEquals("自定商户",hit.bill.shopName);assertEquals("自定方式",hit.bill.accountNameFrom)
        assertNull(match(raw("已收款¥26.77","退款成功"),r))
        assertNotNull(match(raw("退款到账¥26.77","原支付退款已到账"),rule.copy(billType="Refund")))
    }
    @Test fun firstMatchingPersonalRuleHasPriority() {
        val latest=rule.copy(id="personal_latest001",merchant="最新规则")
        assertEquals(latest.id,PersonalRuleEngine.match(listOf(latest,rule),rule.app,"DATA",raw())!!.ruleId)
    }
    @Test fun notificationNeedsTrustedEnvelopeAndCorrectTitle() {
        val r=rule.copy(inputType="NOTICE",keyword="支付")
        fun notice(verified: Boolean,title: String)=SpecJson.encode(mapOf("title" to title,"text" to "已支付¥26.77","envelopeVerified" to verified,"t" to 1793419110639L))
        assertNotNull(match(notice(true,"微信支付"),r));assertNull(match(notice(false,"微信支付"),r));assertNull(match(notice(true,"好友"),r))
    }
    @Test fun alipaySingleStructuredReceiptAndNativeAmounts() {
        val r=rule.copy(app="com.eg.android.AlipayGphone")
        fun ali(amount: String, nested: String=amount)=SpecJson.encode(listOf(mapOf("mct" to 1793419110L,"pl" to mapOf("title" to "付款成功","money" to amount,
            "content" to mapOf("money" to nested,"content" to listOf(mapOf("title" to "收款方：","content" to "美团金融还款"),mapOf("title" to "付款方式：","content" to "余额")))))))
        assertEquals(2677,match(ali("-26.77"),r)!!.bill.priceCents)
        assertNull(match(ali("26.77","20.00"),r));assertNull(match(ali("0.00"),r))
    }
    @Test fun editorExamplesWorkForBothApplicationsAndNotice() {
        val r=rule.copy(keyword="示例商户")
        for(app in listOf("com.tencent.mm","com.eg.android.AlipayGphone")) for(type in listOf("DATA","NOTICE")) {
            val value=r.copy(app=app,inputType=type)
            assertNotNull("$app $type",match(PersonalRuleEngine.example(value,"已支付¥12.34","使用零钱支付¥12.34","示例商户"),value))
        }
    }
    @Test fun personalFileRoundTripStrictFieldsDuplicateIdAndLimits() {
        assertEquals(listOf(rule),PersonalRuleCodec.decode(PersonalRuleCodec.export(listOf(rule))))
        reject {PersonalRuleCodec.decode(PersonalRuleCodec.export(listOf(rule,rule)))}
        reject {PersonalRuleCodec.decode(SpecJson.encode(mapOf("kind" to "MyMoneyPersonalRules","schemaVersion" to 1,"rules" to listOf(rule.json()+("js" to "while(true){}")))))}
        reject {PersonalRuleCodec.decode("x".repeat(PersonalRuleCodec.MAX_BYTES+1))}
        reject {rule.copy(keyword="26.77").validate()};reject {rule.copy(billType="Transfer").validate()}
    }
    @Test fun pageRuleUsesRealPipelineButKeepsNegativeGates() {
        val r=rule.copy(inputType="PAGE")
        com.example.billkit.spec.PersonalPageRules.install(listOf(r))
        try {
            val page=com.example.billkit.UiSnapshot("com.tencent.mm","com.tencent.mm.wallet.NewReceiptUI",listOf("支付成功","¥26.77","美团金融还款"))
            val pipeline=com.example.billkit.BillPipeline()
            val result=pipeline.process(page,1793419110639L)
            assertTrue(result is com.example.billkit.BillPipeline.Outcome.Draft)
            assertEquals(2677,(result as com.example.billkit.BillPipeline.Outcome.Draft).draft.priceCents)
            assertTrue(result.draft.strategy.contains(r.id))
            assertNull(PersonalPageRules.match(page.copy(truncated=true),1793419110639L))
            assertNull(PersonalPageRules.match(page.copy(activityClass="com.tencent.mm.ui.LauncherUI"),1793419110639L))
            assertNull(PersonalPageRules.match(page.copy(items=page.items+"立即支付"),1793419110639L))
            assertNull(PersonalPageRules.match(page.copy(items=listOf("支付中","¥26.77","美团金融还款")),1793419110639L))
            assertNull(PersonalPageRules.match(page.copy(items=page.items+"¥12.34"),1793419110639L))
        } finally { PersonalPageRules.install(emptyList()) }
    }
    @Test fun pagePreviewDoesNotMutateBillKitDeliveryOrDedup() {
        val r=rule.copy(inputType="PAGE",keyword="示例商户")
        val raw=PersonalRuleEngine.example(r,"支付成功¥12.34","使用零钱支付¥12.34","示例商户")
        repeat(3) { assertNotNull(PersonalRuleEngine.match(listOf(r),r.app,"PAGE",raw)) }
    }
    @Test fun explicitTransactionTimeAndOrderSurvivePersonalOverride() {
        val value=raw(extra=mapOf("transactionTime" to "1793419000","orderId" to "ORDER-123456"))
        val hit=match(value)!!
        assertTrue(hit.bill.hasExplicitTime);assertEquals(1793419000000L,hit.bill.time)
        assertEquals("ORDER-123456",hit.bill.orderId)
        assertNull(match(raw(extra=mapOf("transactionTime" to "bad"))))
        assertNull(match(raw(desc="使用零钱支付¥26.77，手续费1.00元")))
    }
    @Test fun packageChangesAndRestartRetainPersonalDefinitions() {
        val dir=Files.createTempDirectory("personal-rule-state").toFile()
        try {
            val store=RulePackageFiles(dir)
            val first=RulePackageFiles.State(personalRules=listOf(rule.copy(enabled=false)))
            store.commit(first)
            val second=store.state().copy(active="a".repeat(64),previous=null,canRollback=true)
            store.commit(second)
            assertEquals(first.personalRules,RulePackageFiles(dir).state().personalRules)
            store.commit(second.copy(active=null,previous=second.active))
            assertEquals(first.personalRules,store.state().personalRules)
        }finally{dir.deleteRecursively()}
    }
}
