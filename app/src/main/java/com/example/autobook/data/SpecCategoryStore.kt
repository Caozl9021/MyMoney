package com.example.autobook.data

import android.content.Context
import android.util.Log
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecCategoryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规格分类引擎（SPEC §8 阶段 5）的宿主侧装配。
 *
 * <h3>它在链路里的位置</h3>
 * [CategorySuggester] 是主力：它懂用户自己的清单、学习记忆、随包规则和外部 JSON 规则，
 * 命中就命中。本对象只在**它给不出分类时**补一刀——即规格的 64 条分类正则
 * （`js_category.js`，来自 iCost 的分类体系）能认出、但项目内置关键词没覆盖的商户。
 * 反过来说：规格永远不会覆盖用户清单和学习结果，那才是用户真正在用的东西。
 *
 * <h3>为什么叫 fill 而不是 suggest</h3>
 * 它接受一个已有的 [CategorySuggester.Suggestion] 并只在 category 为空时补齐，
 * 从类型上就不可能盖掉别人的结论。调用点因此不需要写 if/else 顺序判断。
 */
object SpecCategoryStore {

    private const val TAG = "SpecCategoryStore"

    private val engine = SpecCategoryEngine()
    private val lock = Mutex()

    @Volatile private var initialized = false

    fun isReady(): Boolean = initialized && engine.isReady()

    suspend fun init(context: Context) {
        runCatching { ensureReady(context) }.onFailure {
            Log.w(TAG, "分类引擎初始化失败：${it.message}")
        }
    }

    suspend fun ensureReady(context: Context) {
        if (initialized && engine.isReady()) return
        lock.withLock {
            if (initialized && engine.isReady()) return
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                val common = app.assets.open(SpecAssets.COMMON_JS).use { it.readBytes().toString(Charsets.UTF_8) }
                val category = app.assets.open(SpecAssets.CATEGORY_JS).use { it.readBytes().toString(Charsets.UTF_8) }
                engine.rebuild(common, category)
                initialized = engine.isReady()
            }
        }
    }

    /**
     * 规格分类 → 用户清单里的名字。
     *
     * @return 一级/二级；认不出或用户清单里没有对应分类时返回 null（草稿保持未分类，
     *         让用户在确认卡上点一下，不硬塞清单外的名字）
     */
    suspend fun classify(context: Context, draft: BillDraft): Pair<String, String?>? {
        runCatching { ensureReady(context) }.onFailure { return null }
        if (!isReady()) return null
        // 转账在规格的分类表里没有分支（js_category 只区分 Expend / 其它），交给项目默认逻辑。
        if (draft.type == BillType.TRANSFER) return null
        val groups = LedgerConfig.groupsFor(draft.type)
        if (groups.isEmpty()) return null
        val hhmm = runCatching {
            SimpleDateFormat("HH:mm", Locale.US).format(Date(draft.timeMillis))
        }.getOrNull() ?: "12:00"
        val result = engine.classify(
            shopName = draft.merchant.orEmpty(),
            type = jsType(draft.type),
            item = draft.productName ?: draft.note.orEmpty(),
            timeHhMm = hhmm,
        ) ?: return null
        return SpecCategoryMapper.resolve(result.category, draft.type, groups)
    }

    /** 只在 [suggestion] 没有一级分类时补；已有分类一律不动。 */
    suspend fun fill(
        context: Context,
        draft: BillDraft,
        suggestion: CategorySuggester.Suggestion,
    ): CategorySuggester.Suggestion {
        if (suggestion.category != null) return suggestion
        val (category, sub) = classify(context, draft) ?: return suggestion
        return suggestion.copy(category = category, subCategory = sub)
    }

    private fun jsType(type: BillType): String = when (type) {
        BillType.INCOME -> "Income"
        BillType.TRANSFER -> "Transfer"
        // 退款在规格里是 Income 分支的「退款」，但项目把 REFUND 当独立类型单独记账；
        // 分类按支出侧匹配更贴近用户预期（退款多发生在购物/餐饮）。
        BillType.REFUND -> "Expend"
        BillType.EXPENSE -> "Expend"
    }
}
