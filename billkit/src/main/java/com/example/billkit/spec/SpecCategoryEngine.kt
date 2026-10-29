package com.example.billkit.spec

import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * `js_category.js` 的求值器：把商户/商品/类型/时段喂给规格里的分类函数。
 *
 * <h3>为什么单独一个引擎，不塞进 [SpecRuleEngine]</h3>
 * 两者生命周期不同。规则引擎在 163 条规则变更时才重建；分类引擎只依赖 `js_common.js`
 * 和 `js_category.js` 两个随包文件，永远不会变。混在一个作用域里会让分类的
 * `var category` 和规则的 `var rule_N` 共享全局，规则重载时分类也跟着抖一次。
 *
 * <h3>调用约定（实测，与直觉不同）</h3>
 * `category.get(shopName, billType, a, r, hhmm, t)` 的匹配串是
 * `"a_r_t"`——**第 1 参 shopName 在函数体里被内层闭包的形参遮蔽，根本没参与匹配**。
 * 所以必须把待匹配文本重复放进第 3 参：`get(shop, type, shop, item, "12:00", "")`。
 * 只传第 1 参会得到永远"其他"。
 */
class SpecCategoryEngine {

    private val lock = Any()
    private var scope: Scriptable? = null
    private var getter: Scriptable? = null

    @Volatile var lastBuildMillis: Long = 0L
        private set

    fun isReady(): Boolean = getter != null

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
                if (fn !is Scriptable) throw IllegalStateException("category.get 不存在")
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

/** 分类结果。`book` 恒为「默认账本」（规格里写死），当前项目只有一个账本，暂不使用。 */
data class SpecCategoryResult(val category: String, val book: String)
