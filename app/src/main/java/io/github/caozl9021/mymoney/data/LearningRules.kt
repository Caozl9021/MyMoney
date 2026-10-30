package io.github.caozl9021.mymoney.data

import com.example.billkit.BillType

/**
 * 学习记忆的检索与筛选（纯函数，无持久化状态、无 Compose 依赖）。
 *
 * 查询串和筛选只在内存里对 `LocalRuleSnapshot.learned` 做投影，不落盘、不建索引：
 * 上限是 [LocalRules] 已有的 500 条，线性扫描足够，也避免为"能看到记忆"这件事
 * 再引入一份可能和 `local_rules_v1` 漂移的副本。
 */
enum class LearningField { ALL, CATEGORY, ACCOUNT }

data class LearningQuery(
    val text: String = "",
    val type: BillType? = null,
    val field: LearningField = LearningField.ALL,
    val source: RuleSource? = null,
)

object LearningRules {
    /** 同一结果累计到该次数才会从 pending 升级为 learned（与 [RuleLearning] 的门槛同源）。 */
    const val PROMOTION_THRESHOLD = 3

    fun matches(rule: LearnedRule, query: LearningQuery): Boolean {
        val text = normalize(query.text)
        if (text.isNotEmpty()) {
            val member = when (query.field) {
                LearningField.ALL -> listOf(rule.merchant, rule.method, rule.category, rule.subCategory, rule.account)
                LearningField.CATEGORY -> listOf(rule.category, rule.subCategory)
                LearningField.ACCOUNT -> listOf(rule.account, rule.method)
            }
            if (member.none { normalize(it.orEmpty()).contains(text) }) return false
        }
        if (query.type != null && rule.type != query.type) return false
        if (query.source != null && rule.source != query.source) return false
        return true
    }

    /** 命中次数高的排前面；同次数按最近更新时间倒序，再按商户名稳定排序。 */
    fun filter(rules: List<LearnedRule>, query: LearningQuery): List<LearnedRule> = rules
        .filter { matches(it, query) }
        .sortedWith(compareByDescending<LearnedRule> { it.hits }
            .thenByDescending { it.updatedAt }
            .thenBy { it.merchant })

    /** 全角空格与大小写都要能被忽略，否则中文输入法下的空格会让检索"看起来失灵"。 */
    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), "").lowercase()

    // 快照级纯操作：Store 只负责落盘，语义放在这里才能被 JVM 测试覆盖。

    /** 只清"待累计"，已生效的记忆保持不变。 */
    fun clearPending(state: LocalRuleSnapshot): LocalRuleSnapshot = state.copy(pending = emptyList())

    fun deletePending(state: LocalRuleSnapshot, key: String): LocalRuleSnapshot =
        state.copy(pending = state.pending.filterNot { it.key == key })

    /**
     * 「待生效」组真正该显示的条目。
     *
     * [RuleLearning] 在弱学习生效时仍会把 3/3 的计数写回 `pending`（因为下一次确认需要它续算），
     * 所以原始 `pending` 里可能同时存在"已生效"和"还差 0 次"两种状态。
     * 直接铺出来会自相矛盾；这里按 `key == learned.id` 过滤，**只影响展示，不改存储**。
     */
    fun effectivePending(state: LocalRuleSnapshot): List<PendingLearning> {
        val promoted = state.learned.mapTo(HashSet()) { it.id }
        return state.pending.filterNot { it.key in promoted && it.count >= PROMOTION_THRESHOLD }
    }

    /**
     * 待生效项的可读进度；[PendingLearning.count] 已在解码时收敛到 1..3。
     * 只解释计数，不解释具体字段——那要靠 [PendingLearning.key] 反解，属于诊断范围。
     */
    fun progress(pending: PendingLearning): String =
        "已累计 ${pending.count}/$PROMOTION_THRESHOLD 次，还需 ${(PROMOTION_THRESHOLD - pending.count).coerceAtLeast(0)} 次"

    /**
     * 待生效项的分类与账户取值。
     *
     * `PendingLearning.value` 是写入时编码进去的：分类项是 `MiniJson.encode(listOf(分类, 二级))`，
     * 账户项是账户名字面量。直接展示原文会让用户看到 `["餐饮","午餐"]` 这种内部格式，
     * 所以这里只做格式判定，判不出来就原样返回——**不猜**。
     */
    fun pendingLabels(pending: PendingLearning): List<String> {
        val raw = pending.value
        if (!raw.startsWith("[")) return listOf(raw)
        return runCatching {
            (MiniJson.decode(raw) as List<*>).mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
        }.getOrNull().orEmpty().ifEmpty { listOf(raw) }
    }
}
