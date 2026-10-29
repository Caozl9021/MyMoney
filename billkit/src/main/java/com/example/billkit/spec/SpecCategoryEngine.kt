package com.example.billkit.spec

import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** 原创分类兜底的独立求值器，不覆盖用户分类和餐段配置。 */
class SpecCategoryEngine {

    private val lock = Any()
    private var scope: Scriptable? = null
    private var getter: Scriptable? = null

    @Volatile var lastBuildMillis: Long = 0L
        private set

    fun isReady(): Boolean = synchronized(lock) { getter != null }

    /**
     * 装载 `js_common.js` + `js_category.js`。
     *
     * 与规则引擎同样用解释模式（optimizationLevel = -1）：正则密集的脚本在 Android 上
     * 编译成字节码的收益远小于首次编译的停顿，而这里只在启动时跑一次。
     */
    fun rebuild(commonJs: String, categoryJs: String) {
        synchronized(lock) {
            val started = System.currentTimeMillis()
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                val s = cx.initStandardObjects(null, false)
                cx.evaluateString(s, commonJs, "js_common.js", 1, null)
                cx.evaluateString(s, categoryJs, "js_category.js", 1, null)
                val obj = ScriptableObject.getProperty(s, "category")
                if (obj !is Scriptable) throw IllegalStateException("js_category.js 未导出 category")
                val fn = ScriptableObject.getProperty(obj, "get")
                if (fn !is org.mozilla.javascript.Function) throw IllegalStateException("category.get 不是函数")
                scope = s
                getter = fn
            } finally {
                Context.exit()
            }
            lastBuildMillis = System.currentTimeMillis() - started
        }
    }

    /**
     * @param shopName 商户名（同时作为第 1 参与第 3 参，见类注释）
     * @param type `Expend` / `Income` / `Transfer`，其它值按 Expend 处理
     * @param item 商品说明（第 4 参，拼进匹配串）
     * @param timeHhMm 当前时间 "HH:mm"，只有三餐分支用到
     * @param extra 附加文本（第 6 参，拼进匹配串）
     */
    fun classify(
        shopName: String,
        type: String,
        item: String = "",
        timeHhMm: String = "12:00",
        extra: String = "",
    ): SpecCategoryResult? {
        val s = scope ?: return null
        val fn = getter ?: return null
        val billType = when (type) {
            "Income" -> "Income"
            "Transfer" -> "Transfer"
            "Refund" -> "Refund"
            else -> "Expend"
        }
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val out = (fn as org.mozilla.javascript.Function)
                .call(cx, s, s, arrayOf(shopName, billType, shopName, item, timeHhMm, extra))
            if (out == null || out == Context.getUndefinedValue() || out == Scriptable.NOT_FOUND) return null
            val json = NativeJSON.stringify(cx, s, out, null, null)
            val text = if (json == null) return null else Context.toString(json)
            val map = SpecJson.decodeObject(text) ?: return null
            val category = (map["category"] as? String).orEmpty()
            if (category.isEmpty()) return null
            return SpecCategoryResult(category = category, book = (map["book"] as? String).orEmpty())
        } catch (t: Throwable) {
            // 分类失败不该影响记账：草稿照常落库，只是没有分类。
            return null
        } finally {
            Context.exit()
        }
    }

    fun close() {
        synchronized(lock) {
            scope = null
            getter = null
        }
    }
}

/** 分类结果；book为保留兼容字段，用户账本不由脚本创建。 */
data class SpecCategoryResult(val category: String, val book: String)
