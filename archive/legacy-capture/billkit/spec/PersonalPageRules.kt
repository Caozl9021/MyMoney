package com.example.billkit.spec

import com.example.billkit.*

/** Published only after durable personal state commits; all normal page delivery gates remain downstream. */
object PersonalPageRules {
    @Volatile private var rules: List<PersonalRule> = emptyList()
    fun install(values: List<PersonalRule>) { rules=values.filter { it.inputType=="PAGE" && it.enabled }.toList() }
    fun match(snapshot: UiSnapshot, now: Long): BillPipeline.Outcome.Draft? {
        if(snapshot.truncated || snapshot.packageName !in setOf(Packages.WECHAT,Packages.ALIPAY)) return null
        if(!PageClassifier().permitsCustomRule(snapshot.items)) return null
        if(listOf("LauncherUI", "Chatting", "ChatRoom", "Conversation").any { snapshot.activityClass.orEmpty().contains(it,true) }) return null
        val current=rules
        if(current.none { it.app==snapshot.packageName }) return null
        val hit=PersonalRuleEngine.match(current,snapshot.packageName,"PAGE",PersonalRuleEngine.pageRaw(snapshot,now)) ?: return null
        val draft=SpecDraftAdapter.toDraft(hit.bill,snapshot.packageName,hit.ruleId,now) ?: return null
        val time=if(draft.type==BillType.REFUND) AmountParser.findRefundTime(snapshot.items) else AmountParser.findTime(snapshot.items)
        val product=ProductExtractor.extract(snapshot.items,snapshot.truncated)
        return BillPipeline.Outcome.Draft(draft.copy(timeMillis=time ?: now,hasExplicitTime=time!=null,productName=product,productSource=product?.let { ProductExtractor.RULE + ":" + snapshot.source.name }),
            PageMatch(if(snapshot.packageName==Packages.WECHAT) PageKind.WECHAT_PAY else PageKind.ALIPAY_PAY,"个人规则:${hit.ruleId}"))
    }
}
