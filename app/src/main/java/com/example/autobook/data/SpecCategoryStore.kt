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

/** 原创规则分类兼容入口，只在用户推荐为空时补充，不创建分类或覆盖配置。 */
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
        // 转账无分类，交给项目双端账户逻辑。
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
        BillType.REFUND -> "Refund"
        BillType.EXPENSE -> "Expend"
    }
}
