package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.time.ZoneId

/**
 * 确认卡备注气泡：只用本机已经存在的信息，缺数据就不给，不编造。
 *
 * 三个来源，按可信度排：
 * 1. 同商户以前写过的备注（常常就是商品名：「黄焖鸡米饭」「美式」）；
 * 2. 商户未知时（手动新建）：同一餐段里常去的「商户·备注」组合，仅将建议文字填入备注；
 * 3. 备注模板的渲染结果（原先就默认填进去的那一句）。
 *
 * 纯函数，不读数据库、不碰 Android；调用方把最近账单传进来。
 */
object NoteSuggester {

    data class Suggestion(
        val note: String,
        /** 下面四项只在「时段习惯」建议里有值；仅用于标识建议来源，不作为隐式编辑 */
        val merchant: String? = null,
        val category: String? = null,
        val subCategory: String? = null,
        val account: String? = null,
        val source: String? = null,
    ) {
        val label: String get() = if (source == "商品") "商品 · $note" else if (merchant != null && merchant != note) "$merchant · $note" else note
    }

    const val MAX = 6
    private const val HABIT_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

    fun suggest(
        draft: BillDraft,
        history: List<BillEntity>,
        mealWindows: List<MealWindow> = MealRules.defaults,
        learningEnabled: Boolean = true,
        templateNote: String? = null,
        currentNote: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Suggestion> {
        val out = LinkedHashMap<String, Suggestion>()
        val current = currentNote?.trim().orEmpty()
        fun add(s: Suggestion) {
            val note = s.note.trim()
            if (note.isEmpty() || note == current || note.codePointCount(0, note.length) > 60 || out.size >= MAX) return
            out.putIfAbsent(note, s.copy(note = note))
        }

        NoteTemplate.productSuggestion(draft, draft.noteTemplateSnapshot ?: "{商户}")?.let {
            add(Suggestion(it, source = "商品"))
        }
        if (learningEnabled) {
            val merchant = RuleLearning.normalizeMerchant(draft.merchant)
            val sameType = history.filter { it.type == draft.type.name && !it.note.isNullOrBlank() }
            if (merchant.isNotEmpty()) {
                sameType.filter { RuleLearning.normalizeMerchant(it.merchant) == merchant }
                    .filter { RuleLearning.normalizeMerchant(it.note) != merchant }
                    .groupBy { it.note!!.trim() }
                    .entries.sortedWith(compareByDescending<Map.Entry<String, List<BillEntity>>> { it.value.size }
                        .thenByDescending { e -> e.value.maxOf { it.timeMillis } })
                    .forEach { add(Suggestion(it.key)) }
            } else if (draft.type == BillType.EXPENSE) {
                val meal = MealRules.match(draft.timeMillis, mealWindows, zone)
                if (meal != null) {
                    sameType.asSequence()
                        .filter { draft.timeMillis - it.timeMillis in 0..HABIT_WINDOW_MS }
                        .filter { !it.merchant.isNullOrBlank() }
                        .filter { MealRules.match(it.timeMillis, mealWindows, zone) == meal }
                        .groupBy { it.merchant!!.trim() to it.note!!.trim() }
                        .entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, List<BillEntity>>> { it.value.size }
                            .thenByDescending { e -> e.value.maxOf { it.timeMillis } })
                        .forEach { (key, bills) ->
                            val latest = bills.maxBy { it.timeMillis }
                            add(Suggestion(key.second, merchant = key.first, category = latest.category,
                                subCategory = latest.subCategory, account = latest.account))
                        }
                }
            }
        }
        templateNote?.let { add(Suggestion(it)) }
        return out.values.toList()
    }

    /** 点气泡后的结果：仅替换备注，其余字段不变；返回值连同原值一起交给 UI 做撤销。 */
    data class Fields(val note: String, val merchant: String, val category: String?,
        val subCategory: String?, val account: String?)

    /** Suggestions replace only the optional note; metadata is context, never an implicit edit. */
    fun apply(fields: Fields, s: Suggestion): Fields = fields.copy(note = s.note)
}
