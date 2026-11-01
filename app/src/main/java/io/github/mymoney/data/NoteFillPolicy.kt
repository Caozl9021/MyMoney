package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.util.Locale

enum class NoteFillMode(val label:String) { PRODUCT("使用本次商品说明"), FIXED("固定文字"), NONE("不自动填充") }

data class MerchantNoteRule(val app:String,val merchant:String,val type:String="EXPENSE",val mode:NoteFillMode=NoteFillMode.PRODUCT,val text:String="",val id:String="") {
    val key:String get()=app+"|"+type+"|"+NoteFillPolicy.merchantKey(merchant)
    val itemKey:String get()=id.ifBlank { key }
    fun validate() {
        require(id.isBlank() || Regex("merchant_[A-Za-z0-9_-]{6,80}").matches(id)) {"备注规则标识无效"}
        require(app in setOf("com.tencent.mm","com.eg.android.AlipayGphone")) {"请选择微信或支付宝"}
        require(merchant.isNotBlank() && merchant.length<=80 && !merchant.contains('\n')) {"请填写80字以内的完整商家名称"}
        require(type in setOf("EXPENSE","INCOME","REFUND")) {"请选择支出、收入或退款"}
        require(if(mode==NoteFillMode.FIXED) text.isNotBlank() && text.codePointCount(0,text.length)<=60 else text.isEmpty()) {"固定备注需为1至60字，其他方式无需固定文字"}
    }
}

data class NoteFillConfig(val productFirst:Boolean=true,val rules:List<MerchantNoteRule> = emptyList()) {
    fun validate() {require(rules.size<=100){"商家备注规则最多100条"};rules.forEach{it.validate()};require(rules.map{it.key}.distinct().size==rules.size){"同一商家和交易类型只能有一条备注规则"}}
}

object NoteFillCodec {
    fun encode(config:NoteFillConfig):String {
        config.validate()
        return MiniJson.encode(mapOf("schemaVersion" to 1,"productFirst" to config.productFirst,"rules" to config.rules.map { r ->mapOf("app" to r.app,"merchant" to r.merchant,"type" to r.type,"mode" to r.mode.name,"text" to r.text) }))
    }
    fun decode(text:String):NoteFillConfig {
        require(text.toByteArray().size<=128*1024){"备注规则配置过大"}
        val m=MiniJson.decode(text) as? Map<*,*> ?: error("备注规则格式无效")
        require(m.keys==setOf("schemaVersion","productFirst","rules") && (m["schemaVersion"] as? Number)?.toDouble()==1.0){"备注规则版本或字段无效"}
        val rows=m["rules"] as? List<*> ?: error("备注规则列表缺失")
        require(rows.size<=100){"商家备注规则最多100条"}
        val config=NoteFillConfig(m["productFirst"] as? Boolean ?: error("商品优先开关无效"),rows.map { value ->
            val r=value as? Map<*,*> ?: error("商家备注规则格式无效")
            require(r.keys==setOf("app","merchant","type","mode","text")){"商家备注字段无效"}
            fun str(k:String)=r[k] as? String ?: error("商家备注字段缺失：$k")
            MerchantNoteRule(str("app"),str("merchant"),str("type"),runCatching{NoteFillMode.valueOf(str("mode"))}.getOrElse{error("备注填充方式无效")},str("text"))
        })
        config.validate();return config
    }
}

/** Does not learn yesterday's goods, change a merchant or book a transaction. */
object NoteFillPolicy {
    const val PRODUCT="PRODUCT"
    const val FIXED="MERCHANT_FIXED"
    const val DISABLED="AUTO_DISABLED"
    fun merchantKey(s:String?)=s.orEmpty().trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
    fun ruleFor(config:NoteFillConfig,draft:BillDraft):MerchantNoteRule? = config.rules.firstOrNull {
        it.app==draft.sourcePkg && it.type==draft.type.name && merchantKey(it.merchant)==merchantKey(draft.merchant)
    }
    fun manual(draft:BillDraft):Boolean = draft.noteOrigin in setOf(NoteTemplate.USER,NoteTemplate.SUGGESTION) ||
        (draft.noteOrigin==null && draft.note!=null)
    fun apply(draft:BillDraft,config:NoteFillConfig,template:String):BillDraft {
        if(manual(draft)) return draft
        val product=draft.productName?.trim()?.takeIf{it.isNotBlank() && it.length<=120}?.let(NoteTemplate::limit)
        val rule=ruleFor(config,draft)
        val generated=when {
            draft.type==BillType.TRANSFER -> null
            rule?.mode==NoteFillMode.PRODUCT -> Pair(product,PRODUCT)
            rule?.mode==NoteFillMode.FIXED -> Pair(rule.text.trim(),FIXED)
            rule?.mode==NoteFillMode.NONE -> Pair(null,DISABLED)
            config.productFirst && product!=null -> Pair(product,PRODUCT)
            else -> null
        }
        return draft.copy(note=generated?.first ?: if(generated==null) NoteTemplate.render(draft,template).ifBlank{null} else null,
            noteOrigin=generated?.second ?: NoteTemplate.TEMPLATE,noteTemplateSnapshot=template)
    }
    /** Only an untouched pending automatic note may be upgraded by current product evidence. */
    fun mayUpgrade(row:RecognitionEntity,incoming:BillDraft):Boolean =
        row.noteOrigin in setOf(NoteTemplate.TEMPLATE,PRODUCT) && incoming.noteOrigin==PRODUCT &&
            !incoming.productName.isNullOrBlank() && !incoming.note.isNullOrBlank() &&
            (row.productName.isNullOrBlank() || row.productName==incoming.productName) && row.protectedFields and CaptureFields.NOTE==0L
}
