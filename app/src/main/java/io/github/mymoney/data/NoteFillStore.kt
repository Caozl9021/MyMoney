package io.github.mymoney.data

import com.example.billkit.BillDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single preference value commits atomically before the immutable runtime snapshot is published. */
object NoteFillStore {
    private val mutable=MutableStateFlow(NoteFillConfig())
    val state=mutable.asStateFlow()
    private val mutableError=MutableStateFlow<String?>(null)
    val error=mutableError.asStateFlow()
    @Synchronized fun init() {
        try {mutable.value=LedgerConfig.noteFillPolicy?.let(NoteFillCodec::decode) ?: NoteFillConfig();mutableError.value=null}
        catch(_:Exception){mutable.value=NoteFillConfig(productFirst=false);mutableError.value="备注规则暂不可用，原配置已保留；请检查备份或重新保存设置"}
    }
    @Synchronized fun save(config:NoteFillConfig) {
        val text=NoteFillCodec.encode(config)
        LedgerConfig.saveNoteFillPolicy(text)
        mutable.value=NoteFillCodec.decode(text);mutableError.value=null
    }
    @Synchronized fun setProductFirst(enabled:Boolean)=save(mutable.value.copy(productFirst=enabled))
    @Synchronized fun saveRule(rule:MerchantNoteRule,oldKey:String?=null) {
        rule.validate();save(mutable.value.copy(rules=listOf(rule)+mutable.value.rules.filterNot{it.key==rule.key || it.key==oldKey}))
    }
    @Synchronized fun deleteRule(key:String)=save(mutable.value.copy(rules=mutable.value.rules.filterNot{it.key==key}))
    @Synchronized fun restore(text:String)=save(NoteFillCodec.decode(text))
    fun prepare(draft:BillDraft,template:String=LedgerConfig.noteTemplate)=NoteFillPolicy.apply(draft,mutable.value,template)
}
