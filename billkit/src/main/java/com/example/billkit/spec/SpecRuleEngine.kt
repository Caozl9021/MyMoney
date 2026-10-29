package com.example.billkit.spec

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 规则引擎：把 163 条规则 JS 拼成一个 bundle，一次性求值，之后常驻复用。
 *
 * <h3>为什么这样设计</h3>
 * - 规则不能硬编码：163 条、327KB 正则，改一条就要发版。
 * - 不能每条规则一个 JS 作用域：163 个 context 内存爆炸。
 * - 不能每次请求重新求值：300KB 源码求值 200ms 量级，用户付完款要等。
 * 所以：启动时拼一次 + 单作用域 + 注册表分发。
 *
 * <h3>bundle 结构</h3>
 * ```
 * var common = function(e){...}({});          // js_common.js 原样
 * var __REG = [];
 * __REG.push({app:"...", type:"DATA", name:"...", id:"rule_142", autoRecord:0,
 *             rule:(function(e,t){...})({},common)});
 * function __dispatch(app, type, raw) { ... }
 * ```
 *
 * <h3>引擎选型（对 SPEC §6.7 的修正）</h3>
 * SPEC 推荐 QuickJS，理由是 Rhino「ES5 only，规则里用了 for...of/let 需转译」。实测 163 条
 * 规则里 arrow/let/const/template/spread/for...of/destructuring/class/async 出现次数全为 0，
 * 只有 `Array.includes` 7 处——这批规则是 Babel 降级产物，纯 ES5。Rhino 1.7.15 解释模式
 * 直接跑通全部样本，因此不需要 QuickJS 的原生 .so（也就不用处理 32/64 位 ABI 与打包问题）。
 *
 * <h3>线程安全</h3>
 * Rhino 的 Context 不是线程安全的，但作用域对象本身可以在多个 Context 之间传递。
 * 本类持锁串行化所有调用；每次调用进出一个 Context（开销很小），作用域常驻。
 */
class SpecRuleEngine {

    /** 秒级时间戳上界（1e11 ≈ 公元 5138 年），小于此值判定为秒，需 ×1000。 */
    private companion object {
        const val SECONDS_THRESHOLD = 100_000_000_000L
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        /** 逐条 try/catch：单条规则抛异常不能影响后续规则。 */
        val DISPATCHER = """
            function __dispatch(app, type, raw) {
              for (var i = 0; i < __REG.length; i++) {
                var r = __REG[i];
                if (r.app !== app || r.type !== type) continue;
                try {
                  var out = r.rule.get(raw);
                  if (out) {
                    out.__rule = r.name;
                    out.__id = r.id;
                    out.__autoRecord = r.autoRecord;
                    return out;
                  }
                } catch (e) {
                  if (typeof console !== 'undefined') console.log('rule fail ' + r.id + ': ' + e);
                }
              }
              return null;
            }
        """.trimIndent()
    }

    private val lock = Any()
    private var scope: ScriptableObject? = null
    private var dispatch: Function? = null
    private var registeredCount = 0

    /** 上次 [rebuild] 的耗时（毫秒），用于自检与日志。 */
    @Volatile
    var lastBuildMillis: Long = 0L
        private set

    /** 全量重建。规则变更（热更 / 用户编辑）后调用。 */
    fun rebuild(commonJs: String, rules: List<SpecRuleModel>) {
        synchronized(lock) {
            val started = System.nanoTime()
            val bundle = buildBundle(commonJs, rules)
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                val newScope = cx.initStandardObjects(null, false)
                cx.evaluateString(newScope, bundle, "autobook-rules", 1, null)
                scope = newScope
                dispatch = ScriptableObject.getProperty(newScope, "__dispatch") as? Function
                registeredCount = rules.count { it.enabled }
            } finally {
                Context.exit()
            }
            lastBuildMillis = (System.nanoTime() - started) / 1_000_000
        }
    }

    /**
     * 解析一条原始 payload。
     *
     * @param app 包名，例如 `com.tencent.mm`
     * @param type `DATA` 或 `NOTICE`
     * @param raw 原始数据（微信 mMap 的 JSON、通知的 `{title,text,t}` JSON、短信 JSON）
     * @return 命中的规则产出；未命中返回 null
     */
    fun parse(app: String, type: String, raw: String?): SpecRuleResult? {
        if (raw == null) return null
        synchronized(lock) {
            val target = scope ?: return null
            val fn = dispatch ?: return null
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                val out = fn.call(cx, target, target, arrayOf<Any?>(app, type, raw)) ?: return null
                if (out !is Scriptable) return null
                val json = NativeJSON.stringify(cx, target, out, null, null) as? String ?: return null
                val map = SpecJson.decode(json) as? Map<*, *> ?: return null
                @Suppress("UNCHECKED_CAST")
                val fields = map as Map<String, Any?>
                if (!fields.containsKey("money")) return null

                val bill = SpecRuleObject.fromJson(fields)
                // 时间归一化：支付宝 mct 是秒，其余是毫秒
                var time = bill.time
                if (app == PKG_ALIPAY && time > 0 && time < SECONDS_THRESHOLD) time *= 1000L
                if (time <= 0) time = System.currentTimeMillis()
                return SpecRuleResult(
                    bill = bill.copy(time = time),
                    ruleName = fields["__rule"]?.toString().orEmpty(),
                    ruleId = fields["__id"]?.toString().orEmpty(),
                    autoRecord = fields["__autoRecord"] == true ||
                        (fields["__autoRecord"] as? Number)?.toLong() == 1L,
                )
            } catch (e: Throwable) {
                return null
            } finally {
                Context.exit()
            }
        }
    }

    fun isReady(): Boolean = synchronized(lock) { scope != null && dispatch != null }

    fun registeredRules(): Int = synchronized(lock) { registeredCount }

    fun close() {
        synchronized(lock) {
            scope = null
            dispatch = null
            registeredCount = 0
        }
    }

    // ==================== bundle 拼装 ====================

    private fun buildBundle(commonJs: String, rules: List<SpecRuleModel>): String {
        val sb = StringBuilder(commonJs.length + rules.size * 1600 + 2048)
        sb.append(commonJs).append('\n')
        sb.append("var __REG = [];\n")
        for (rule in rules) {
            if (!rule.enabled) continue
            val body = normalizeRuleJs(rule) ?: continue
            sb.append("__REG.push({app:").append(quote(rule.app))
                .append(",type:").append(quote(rule.type))
                .append(",name:").append(quote(rule.name))
                .append(",id:").append(quote(rule.systemRuleName))
                .append(",autoRecord:").append(if (rule.autoRecord) 1 else 0)
                .append(",rule:").append(body)
                .append("});\n")
        }
        sb.append(DISPATCHER).append('\n')
        return sb.toString()
    }

    /**
     * 把规则 JS 包成可求值的表达式。
     *
     * 原始形如 `var rule_142=function(e,t){...}({},common);`，剥掉左侧 `var rule_142=` 与
     * 末尾分号，只保留右侧表达式。剥不掉时原样丢进注册表，靠 try/catch 兜底。
     */
    private fun normalizeRuleJs(rule: SpecRuleModel): String? {
        var js = rule.js.trim()
        if (js.isEmpty()) return null
        val tight = "var " + rule.systemRuleName + "="
        val spaced = "var " + rule.systemRuleName + " = "
        when {
            js.startsWith(tight) -> js = js.substring(tight.length)
            js.startsWith(spaced) -> js = js.substring(spaced.length)
        }
        if (js.endsWith(";")) js = js.substring(0, js.length - 1)
        return js.trim().ifEmpty { null }
    }

    /** JS 字符串字面量转义。 */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
