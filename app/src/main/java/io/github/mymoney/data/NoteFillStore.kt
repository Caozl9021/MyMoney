package io.github.mymoney.data

import com.example.billkit.BillDraft
import io.github.mymoney.data.rulesync.RuleSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/** A single preference value commits atomically before the immutable runtime snapshot is published. */
object NoteFillStore {
    private val mutable=MutableStateFlow(NoteFillConfig())
    val state=mutable.asStateFlow()
    private val mutableError=MutableStateFlow<String?>(null)
    val error=mutableError.asStateFlow()

    @Synchronized fun init() {
        try {
            mutable.value = if (RuleSyncRepository.isReady()) {
                LedgerConfig.noteTemplate = RuleSyncRepository.noteTemplate()
                RuleSyncRepository.noteConfig()
            } else LedgerConfig.noteFillPolicy?.let(NoteFillCodec::decode) ?: NoteFillConfig()
            mutableError.value = null
        } catch(_:Exception) {
            mutable.value=NoteFillConfig(productFirst=false)
            mutableError.value="备注规则暂不可用，原配置已保留；请检查备份或重新保存设置"
        }
    }

    /** Reload the in-memory snapshot after a remote sync pull. */
    @Synchronized fun refreshFromRepository() {
        if (RuleSyncRepository.isReady()) {
            runCatching { publishFromRepository() }
                .onFailure { mutableError.value = "备注规则刷新失败，当前显示可能不是最新" }
        }
    }

    @Synchronized fun save(config:NoteFillConfig) {
        if (RuleSyncRepository.isReady()) {
            repository { RuleSyncRepository.replaceNoteConfig(config) }
            publishFromRepository()
        } else {
            val text=NoteFillCodec.encode(config)
            LedgerConfig.saveNoteFillPolicy(text)
            mutable.value=NoteFillCodec.decode(text)
            mutableError.value=null
        }
    }

    @Synchronized fun setProductFirst(enabled:Boolean) {
        if (RuleSyncRepository.isReady()) {
            repository { RuleSyncRepository.setProductFirst(enabled) }
            publishFromRepository()
        } else save(mutable.value.copy(productFirst=enabled))
    }

    @Synchronized fun saveRule(rule:MerchantNoteRule,oldKey:String?=null) {
        rule.validate()
        if (RuleSyncRepository.isReady()) {
            repository { RuleSyncRepository.saveMerchantRule(rule, oldKey) }
            publishFromRepository()
        } else {
            save(mutable.value.copy(rules=listOf(rule)+mutable.value.rules.filterNot{it.key==rule.key || it.key==oldKey}))
        }
    }

    @Synchronized fun deleteRule(key:String) {
        if (RuleSyncRepository.isReady()) {
            repository { RuleSyncRepository.deleteMerchantRule(key) }
            publishFromRepository()
        } else save(mutable.value.copy(rules=mutable.value.rules.filterNot{it.key==key}))
    }

    @Synchronized fun saveNoteTemplate(template:String) {
        require(template.length<=200){"备注模板过长"}
        if (RuleSyncRepository.isReady()) {
            repository { RuleSyncRepository.saveNoteTemplate(template) }
            publishFromRepository(template)
        } else {
            LedgerConfig.noteTemplate = template
        }
    }

    @Synchronized fun restore(text:String) {
        val config=NoteFillCodec.decode(text)
        if (RuleSyncRepository.isReady()) {
            repository { RuleSyncRepository.replaceNoteConfig(config) }
            publishFromRepository()
        } else save(config)
    }

    fun prepare(draft:BillDraft,template:String=LedgerConfig.noteTemplate)=NoteFillPolicy.apply(draft,mutable.value,template)

    private fun publishFromRepository(template:String?=null) {
        val config=RuleSyncRepository.noteConfig()
        val text=NoteFillCodec.encode(config)
        LedgerConfig.saveNoteFillPolicy(text)
        LedgerConfig.noteTemplate = template ?: RuleSyncRepository.noteTemplate()
        mutable.value=config
        mutableError.value=null
    }

    private fun <T> repository(block:suspend ()->T):T = runBlocking(Dispatchers.IO) { block() }
}
