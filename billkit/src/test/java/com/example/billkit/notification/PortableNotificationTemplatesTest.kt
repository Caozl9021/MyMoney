package com.example.billkit.notification

import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecJson
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class PortableNotificationTemplatesTest {
    private fun config()=NotificationTemplateConfig.decode(SpecAssets.classpath("spec/"+NotificationTemplateConfig.FILE))
    private fun observation(text: String="你有一笔17.53元的支出，点击领2元水燃费红包。")=NotificationObservation("e","com.eg.android.AlipayGphone","test","n",1,null,"0",1793506849867,1793506850100,"交易提醒",text,null,"mipush|com.eg.android.AlipayGphone|100060",null,null)
    private fun parse(o:NotificationObservation)=NotificationParser(config().templates).parse(o)
    private fun reject(block:()->Unit) { try { block();fail("must reject") } catch (_:IllegalArgumentException) {} catch (_:IllegalStateException) {} }
    private fun edit(key:String,value:Any?):String {
        val m=SpecJson.decodeObject(SpecAssets.classpath("spec/"+NotificationTemplateConfig.FILE)).toMutableMap()
        val rows=(m["templates"] as List<*>).map { (it as Map<*,*>).entries.associate { p->p.key.toString() to p.value }.toMutableMap() }
        rows[0][key]=value;m["templates"]=rows;return SpecJson.encode(m)
    }
    @Test fun capturedBodyUsesExpenseRatherThanRewardAndDoesNotGuessFields() {
        val x=parse(observation()) as NotificationParseResult.Candidate
        assertEquals(1753,x.draft.priceCents);assertNull(x.draft.merchant);assertNull(x.draft.method)
        assertEquals("alipay-trade-expense-v1",x.templateId)
    }
    @Test fun sameBodyPreviouslyFailsOnlyAtChannelGate() {
        val o=observation();val legacy=AlipayExpenseNotificationTemplate()
        assertTrue(legacy.matches(o));assertFalse(legacy.matchesMetadata(o))
        assertEquals(NotificationParseResult.Ignored("not-successful-expense"),NotificationParser(listOf(legacy)).parse(o))
    }
    @Test fun packageSelfTestsUseActualProductionParser() { assertTrue(config().validateSamples()>=16) }
    @Test fun exactCentsAndAmountBounds() {
        assertEquals(1999,(parse(observation("你有一笔19.99元的支出")) as NotificationParseResult.Candidate).draft.priceCents)
        for(amount in listOf("0","0.00","-1.00","01.00","1.999","1e2","1,000","1000000.01","NaN")) assertFalse(amount,parse(observation("你有一笔${amount}元的支出")) is NotificationParseResult.Candidate)
    }
    @Test fun unknownMetadataNeverUsesConfiguredTemplate() {
        for(o in listOf(observation().copy(channelId="unknown"),observation().copy(category="msg"),observation().copy(template="other"),observation().copy(title="未知标题"),observation().copy(packageName="com.tencent.mm"))) assertFalse(parse(o) is NotificationParseResult.Candidate)
    }
    @Test fun unsafeAndMixedFinancialDirectionsNeverPass() {
        for(tail in listOf("付款失败","待扣款","取消","退款成功","转账成功","收入1元","收款1元","你有一笔2元的支出","消费2元","USD")) assertFalse(tail,parse(observation("你有一笔17.53元的支出，$tail")) is NotificationParseResult.Candidate)
    }
    @Test fun envelopeGuardsStillApply() {
        for(o in listOf(observation().copy(isGroupSummary=true),observation().copy(isMessagingStyle=true),observation().copy(isOngoing=true),observation().copy(isReconnectBaseline=true),observation().copy(truncated=true),observation().copy(postTime=0))) assertFalse(parse(o) is NotificationParseResult.Candidate)
    }
    @Test fun onlyDeclaredWholeRewardSuffixPasses() {
        for(tail in listOf("，点击领2元水燃费红包。收入1元","，点击领2元水燃费红包。你有一笔3元的支出","，点击领2元水燃费红包。USD","，领取2元返现。","，点击领2.001元水燃费红包。")) assertFalse(parse(observation("你有一笔17.53元的支出"+tail)) is NotificationParseResult.Candidate)
    }
    @Test fun malformedOrExecutableTemplateFieldsReject() {
        for(v in listOf(".*","你有一笔{amount}{amount}元的支出","你有一笔{amount}元的收入","你有一笔{amount}元的支出\\d+")) reject {NotificationTemplateConfig.decode(edit("bodyPattern",v))}
        reject {NotificationTemplateConfig.decode(edit("channelHashes",listOf("*")))}
        reject {NotificationTemplateConfig.decode(edit("rewardPatterns",listOf("，点击领2元红包。")))}
        reject {NotificationTemplateConfig.decode(edit("rewardPatterns",listOf("，点击领{rewardAmount}元红包。收入")))}
        reject {NotificationTemplateConfig.decode(edit("ruleId","maimi_wechat_notice_expense_v1"))}
    }
    @Test fun missingOrWrongSelfTestRejects() {
        val m=SpecJson.decodeObject(SpecAssets.classpath("spec/"+NotificationTemplateConfig.FILE)).toMutableMap()
        m["samples"]=emptyList<Any>();reject {NotificationTemplateConfig.decode(SpecJson.encode(m)).validateSamples()}
        m["samples"]=listOf(mapOf("id" to "wrong","templateId" to "unknown","provenance" to "synthetic","evidence" to "test"));reject {NotificationTemplateConfig.decode(SpecJson.encode(m)).validateSamples()}
    }
    @Test fun literalPatternUpdateRequiresNoCodeAndRemainsWholeSentence() {
        val changed=NotificationTemplateConfig.decode(edit("rewardPatterns",listOf("，点击领{rewardAmount}元生活红包。")))
        val parser=NotificationParser(changed.templates)
        assertTrue(parser.parse(observation("你有一笔17.53元的支出，点击领3元生活红包。")) is NotificationParseResult.Candidate)
        assertFalse(parser.parse(observation()) is NotificationParseResult.Candidate)
    }
    @Test fun existingParserUsesPublishedGenerationAndDisableImmediately() {
        val previous=NotificationTemplateRegistry.snapshot();try {
            val parser=NotificationParser();NotificationTemplateRegistry.install(config().templates)
            assertTrue(parser.parse(observation()) is NotificationParseResult.Candidate)
            NotificationTemplateRegistry.install(emptyList());assertFalse(parser.parse(observation()) is NotificationParseResult.Candidate)
        }finally {NotificationTemplateRegistry.install(previous)}
    }
    @Test fun concurrentGenerationSwitchNeverCombinesTemplates() {
        val previous=NotificationTemplateRegistry.snapshot();val executor=Executors.newFixedThreadPool(2)
        try {
            val templates=config().templates;val parser=NotificationParser()
            val writes=executor.submit {repeat(100){NotificationTemplateRegistry.install(if(it%2==0)templates else emptyList())}}
            val reads=executor.submit {repeat(100){val result=parser.parse(observation());if(result is NotificationParseResult.Candidate)assertEquals(1753,result.draft.priceCents)}}
            writes.get();reads.get()
        }finally {executor.shutdown();NotificationTemplateRegistry.install(previous)}
    }
    @Test fun rootDumpAndListenerObservationUseSameConfiguredParser() {
        val dumped=com.example.billkit.root.NotificationDumpParser.parse("""
            NotificationRecord(0x1: pkg=com.eg.android.AlipayGphone key=0|com.eg.android.AlipayGphone|1|null|10000 Notification(channel=mipush|com.eg.android.AlipayGphone|100060 flags=0 category=null))
              posttime=1793506849867
              extras={
                android.template=null
                android.title=String (交易提醒)
                android.text=String (你有一笔17.53元的支出，点击领2元水燃费红包。)
              }
        """.trimIndent()).single().observation(1793506850100)!!
        assertEquals(1753,(parse(dumped) as NotificationParseResult.Candidate).draft.priceCents)
    }
    @Test fun duplicateTemplatesAndUnknownFieldsReject() {
        val m=SpecJson.decodeObject(SpecAssets.classpath("spec/"+NotificationTemplateConfig.FILE)).toMutableMap()
        m["templates"]=(m["templates"] as List<*>).let {it+it};reject {NotificationTemplateConfig.decode(SpecJson.encode(m))}
        reject {NotificationTemplateConfig.decode(edit("js","while(true){}"))}
    }
}
