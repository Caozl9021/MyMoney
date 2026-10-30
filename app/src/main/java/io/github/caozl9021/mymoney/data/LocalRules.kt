package io.github.caozl9021.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.time.Instant
import java.time.ZoneId

enum class RuleSource(val weight: Int, val label: String) {
    MANUAL(600, "手动学习"), JSON_OVERRIDE(500, "本地规则"), JSON_BUNDLED(400, "随包规则"),
    LEARNED(300, "确认学习"), BUILTIN(200, "内置规则"), MEAL(100, "账单餐段")
}
data class RuleCandidate(val type: BillType, val pattern: String, val category: String? = null,
    val subCategory: String? = null, val account: String? = null, val source: RuleSource = RuleSource.JSON_OVERRIDE,
    val regex: Boolean = false, val matchMethod: Boolean = false)
data class RuleHit(val category: String? = null, val subCategory: String? = null, val account: String? = null,
    val score: Int, val source: RuleSource, val explanation: String)
data class MealWindow(val subCategory: String, val from: String, val to: String)
data class LearnedRule(val id: String, val type: BillType, val merchant: String, val method: String? = null,
    val category: String? = null, val subCategory: String? = null, val account: String? = null,
    val source: RuleSource = RuleSource.MANUAL, val hits: Int = 1, val updatedAt: Long = 0)
data class PendingLearning(val key: String, val value: String, val count: Int)
data class LocalRuleSnapshot(val mealWindows: List<MealWindow> = MealRules.defaults,
    val learned: List<LearnedRule> = emptyList(), val pending: List<PendingLearning> = emptyList(),
    val learningEnabled: Boolean = true)

object MealRules {
    val names = listOf("早餐", "午餐", "晚餐", "夜宵")
    val defaults = listOf(MealWindow("早餐", "05:00", "10:30"), MealWindow("午餐", "10:30", "14:30"),
        MealWindow("晚餐", "16:30", "21:00"), MealWindow("夜宵", "21:00", "05:00"))
    fun minute(text: String): Int {
        require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(text)) { "时间须为 HH:mm" }
        return text.substring(0, 2).toInt() * 60 + text.substring(3).toInt()
    }
    private fun contains(window: MealWindow, minute: Int): Boolean {
        val a = minute(window.from); val b = minute(window.to)
        return if (a < b) minute in a until b else minute >= a || minute < b
    }
    fun validate(windows: List<MealWindow>) {
        require(windows.size <= 16) { "餐段数量不能超过16" }
        windows.forEach { require(it.subCategory.isNotBlank() && it.subCategory.length <= 40) { "餐段分类不能为空或过长" }; require(minute(it.from) != minute(it.to)) { "餐段起止时间不能相同" } }
        require((0 until 1440).all { m -> windows.count { contains(it, m) } <= 1 }) { "餐段不能重叠" }
    }
    fun match(time: Long, windows: List<MealWindow>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val local = Instant.ofEpochMilli(time).atZone(zone)
        return windows.firstOrNull { contains(it, local.hour * 60 + local.minute) }?.subCategory
    }
}

/** Pure successful-human-confirmation reducer. No persistence, UI or automatic save side effects. */
object RuleLearning {
    fun normalizeMerchant(raw: String?): String = raw.orEmpty().trim().replace(Regex("\\s+"), "")
        .replace(Regex("[（(][^()（）]{1,24}(?:分店|门店|店)[）)]$"), "")
    fun methodKey(raw: String?): String = raw.orEmpty().trim().replace(Regex("\\s+"), "").lowercase()
    fun record(state: LocalRuleSnapshot, original: BillDraft, edited: BillDraft, allowFrequency: Boolean = true,
        now: Long = System.currentTimeMillis()): LocalRuleSnapshot {
        if (!state.learningEnabled) return state
        val merchant = normalizeMerchant(edited.merchant)
        if (merchant.isBlank() || merchant.length > 120) return state
        var learned = state.learned
        var pending = state.pending
        fun field(kind: String, changed: Boolean, value: String?, category: String? = null, sub: String? = null, account: String? = null) {
            val method = if (kind == "account") methodKey(edited.method) else null
            if (method != null && method.length > 160) return
            if (listOfNotNull(category, sub, account).any { it.length > 160 }) return
            val id = MiniJson.encode(listOf(edited.type.name, merchant, kind, method))
            if (value.isNullOrBlank()) {
                pending = pending.filterNot { it.key == id }
                if (changed) learned = learned.filterNot { it.id == id }
                return
            }
            val old = learned.firstOrNull { it.id == id }
            val prior = pending.firstOrNull { it.key == id }
            val count = if (prior?.value == value) (prior.count + 1).coerceAtMost(LearningRules.PROMOTION_THRESHOLD) else 1
            if (changed || allowFrequency) {
                pending = pending.filterNot { it.key == id }
                if (!changed) pending = pending + PendingLearning(id, value, count)
            }
            if (changed || (allowFrequency && count >= LearningRules.PROMOTION_THRESHOLD && old?.source != RuleSource.MANUAL)) {
                learned = learned.filterNot { it.id == id } + LearnedRule(id, edited.type, merchant, method,
                    category, sub, account, if (changed) RuleSource.MANUAL else RuleSource.LEARNED,
                    (old?.hits ?: 0) + 1, now)
            }
        }
        val categoryChanged = original.type != edited.type || original.category != edited.category || original.subCategory != edited.subCategory
        // Weak learning stores the food group, never the time-derived meal. Manual meal changes remain explicit.
        val sub = if (!categoryChanged && edited.subCategory in MealRules.names) null else edited.subCategory
        field("category", categoryChanged, edited.category?.let { MiniJson.encode(listOf(it, sub)) }, edited.category, sub)
        field("account", original.type != edited.type || original.account != edited.account, edited.account, account = edited.account)
        return state.copy(learned = learned.takeLast(500), pending = pending.takeLast(1000))
    }
}

object LocalRuleCodec {
    fun encode(state: LocalRuleSnapshot): String = MiniJson.encode(linkedMapOf(
        "enabled" to state.learningEnabled,
        "meals" to state.mealWindows.map { listOf(it.subCategory, it.from, it.to) },
        "learned" to state.learned.map { linkedMapOf("id" to it.id, "type" to it.type.name, "merchant" to it.merchant,
            "method" to it.method, "category" to it.category, "sub" to it.subCategory, "account" to it.account,
            "source" to it.source.name, "hits" to it.hits, "at" to it.updatedAt) },
        "pending" to state.pending.map { listOf(it.key, it.value, it.count) }))
    fun decode(raw: String): LocalRuleSnapshot {
        val root = MiniJson.decode(raw) as? Map<*, *> ?: error("规则配置格式错误")
        val meals = (root["meals"] as? List<*>)?.map { val a = it as List<*>; MealWindow(a[0] as String, a[1] as String, a[2] as String) } ?: MealRules.defaults
        MealRules.validate(meals)
        val learned = (root["learned"] as? List<*>).orEmpty().takeLast(500).mapNotNull { item -> runCatching {
            val m = item as Map<*, *>; val source = RuleSource.valueOf(m["source"] as String)
            require(source == RuleSource.MANUAL || source == RuleSource.LEARNED)
            require(((m["id"] as? String)?.length ?: 0) in 1..512)
            require(((m["merchant"] as? String)?.length ?: 0) in 1..120)
            listOf("method", "category", "sub", "account").forEach { key -> require(m[key] == null || ((m[key] as? String)?.length ?: -1) in 0..160) }
            LearnedRule(m["id"] as String, BillType.valueOf(m["type"] as String), m["merchant"] as String,
                m["method"] as? String, m["category"] as? String, m["sub"] as? String, m["account"] as? String,
                source, ((m["hits"] as? Number)?.toInt() ?: 1).coerceIn(1,100000), (m["at"] as? Number)?.toLong() ?: 0)
        }.getOrNull() }
        val pending = (root["pending"] as? List<*>).orEmpty().takeLast(1000).mapNotNull { runCatching {
            val a = it as List<*>; require((a[0] as String).length <= 512 && (a[1] as String).length <= 512); PendingLearning(a[0] as String, a[1] as String, (a[2] as Number).toInt().coerceIn(1,3))
        }.getOrNull() }
        return LocalRuleSnapshot(meals, learned, pending, root["enabled"] as? Boolean ?: true)
    }
}
