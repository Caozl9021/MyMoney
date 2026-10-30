package io.github.mymoney.data

import android.content.Context
import android.content.SharedPreferences
import com.example.billkit.BillDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LocalRuleStore {
    private var prefs: SharedPreferences? = null
    private val mutable = MutableStateFlow(LocalRuleSnapshot())
    val state: StateFlow<LocalRuleSnapshot> = mutable.asStateFlow()
    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("ledger_config", Context.MODE_PRIVATE)
        mutable.value = prefs?.getString("local_rules_v1", null)?.let { runCatching { LocalRuleCodec.decode(it) }.getOrNull() } ?: LocalRuleSnapshot()
    }
    fun snapshot(): LocalRuleSnapshot = mutable.value
    @Synchronized fun restore(snapshot: LocalRuleSnapshot) { MealRules.validate(snapshot.mealWindows); save(snapshot) }
    @Synchronized fun saveMealWindows(windows: List<MealWindow>) { MealRules.validate(windows); save(snapshot().copy(mealWindows = windows.toList())) }
    @Synchronized fun setLearningEnabled(enabled: Boolean) { save(snapshot().copy(learningEnabled = enabled)) }
    @Synchronized fun recordConfirmation(original: BillDraft, edited: BillDraft, allowFrequency: Boolean = true) {
        val groups = LedgerConfig.groupsFor(edited.type)
        val group = groups.firstOrNull { it.name == edited.category }
        val valid = edited.copy(category = group?.name, subCategory = edited.subCategory?.takeIf { it in group?.subs.orEmpty() }, account = edited.account?.takeIf { it in LedgerConfig.accounts })
        save(RuleLearning.record(snapshot(), original, valid, allowFrequency))
    }
    @Synchronized fun deleteLearned(id: String) { save(snapshot().copy(learned = snapshot().learned.filterNot { it.id == id }, pending = snapshot().pending.filterNot { it.key == id })) }
    /**
     * 只清"待累计"，不动已经生效的记忆：用户想撤掉的是"还没生效的那条候选"，
     * 顺手删掉已生效的规则会静默改变现有推荐。
     */
    @Synchronized fun clearPending() { save(LearningRules.clearPending(snapshot())) }
    @Synchronized fun deletePending(key: String) { save(LearningRules.deletePending(snapshot(), key)) }
    @Synchronized fun clearLearned() { save(snapshot().copy(learned = emptyList(), pending = emptyList())) }
    private fun save(snapshot: LocalRuleSnapshot) { prefs?.edit()?.putString("local_rules_v1", LocalRuleCodec.encode(snapshot))?.apply(); mutable.value = snapshot }
}
