package com.example.billkit.spec

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** 原创规则的本地 Rhino 执行器；串行调用、候选构建成功后才替换作用域。 */
class SpecRuleEngine {

    /** 秒级时间戳上界（1e11 ≈ 公元 5138 年），小于此值判定为秒，需 ×1000。 */
    private companion object {
        const val SECONDS_THRESHOLD = 100_000_000_000L
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        /** 逐条 try/catch：单条规则抛异常不能影响后续规则。 */
        val DISPATCHER = """
            var __lastErrors = 0;
            var __lastRejected = 0;
            function __valid(out) {
              if (!out || typeof out !== 'object') return false;
              if (out.type !== 'Expend' && out.type !== 'Income' && out.type !== 'Refund') return false;
              if (out.currency !== 'CNY') return false;
              var amount = out.money;
              if (typeof amount !== 'number' &&
                  (typeof amount !== 'string' || !/^(0|[1-9][0-9]*)(\.[0-9]{1,2})?$/.test(amount))) return false;
              var value = Number(amount);
              if (!isFinite(value) || value <= 0 || value > 1000000) return false;
              if (!/^(0|[1-9][0-9]*)(\.[0-9]{1,2})?$/.test(String(value))) return false;
              if (out.fee !== undefined && Number(out.fee) !== 0) return false;
              if (out.accountNameTo) return false;
              return true;
            }
            function __dispatch(app, type, raw) {
              __lastErrors = 0;
              __lastRejected = 0;
              for (var i = 0; i < __REG.length; i++) {
                var r = __REG[i];
                if (r.app !== app || r.type !== type) continue;
                try {
                  var out = r.rule.get(raw);
                  if (out !== null && out !== undefined) {
                    if (!__valid(out)) { __lastRejected++; continue; }
                    out.__rule = r.name;
                    out.__id = r.id;
                    out.__autoRecord = r.autoRecord;
                    return out;
                  }
                } catch (e) { __lastErrors++; }
              }
              return null;
            }
        """.trimIndent()
    }

    private val lock = Any()
    private var scope: ScriptableObject? = null
    private var dispatch: Function? = null
    private var registeredCount = 0
    enum class Outcome { MATCHED, NO_MATCH, REJECTED, ERROR }
    @Volatile var lastOutcome = Outcome.NO_MATCH
        private set

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
                val newDispatch = ScriptableObject.getProperty(newScope, "__dispatch") as? Function
                    ?: error("规则分发器缺失")
                val registry = ScriptableObject.getProperty(newScope, "__REG") as? Scriptable
                    ?: error("规则注册表缺失")
                val count = (ScriptableObject.getProperty(registry, "length") as Number).toInt()
                require(count == rules.count { it.enabled }) { "实际注册数不一致" }
                for (index in 0 until count) {
                    val entry = ScriptableObject.getProperty(registry, index) as? Scriptable
                        ?: error("规则注册项损坏")
                    val body = ScriptableObject.getProperty(entry, "rule") as? Scriptable
                        ?: error("规则未返回对象")
                    require(ScriptableObject.getProperty(body, "get") is Function) { "规则缺少get" }
                }
                scope = newScope
                dispatch = newDispatch
                registeredCount = count
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
        if (raw == null) { lastOutcome = Outcome.NO_MATCH; return null }
        synchronized(lock) {
            val target = scope ?: return null
            val fn = dispatch ?: return null
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                lastOutcome = Outcome.NO_MATCH
                val out = fn.call(cx, target, target, arrayOf<Any?>(app, type, raw))
                if (out == null) {
                    val errors = (ScriptableObject.getProperty(target, "__lastErrors") as? Number)?.toInt() ?: 0
                    val rejected = (ScriptableObject.getProperty(target, "__lastRejected") as? Number)?.toInt() ?: 0
                    lastOutcome = when { errors > 0 -> Outcome.ERROR; rejected > 0 -> Outcome.REJECTED; else -> Outcome.NO_MATCH }
                    return null
                }
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
                // 缺失时间保留0，由宿主以observedAt展示，不得伪造显式业务时间。
                lastOutcome = Outcome.MATCHED
                return SpecRuleResult(
                    bill = bill.copy(time = time),
                    ruleName = fields["__rule"]?.toString().orEmpty(),
                    ruleId = fields["__id"]?.toString().orEmpty(),
                    autoRecord = fields["__autoRecord"] == true ||
                        (fields["__autoRecord"] as? Number)?.toLong() == 1L,
                )
            } catch (e: Throwable) {
                lastOutcome = Outcome.ERROR
                return null
            } finally {
                Context.exit()
            }
        }
    }

    data class ParseResult(val result: SpecRuleResult?, val outcome: Outcome)

    /** 结果和状态在同一锁内取出，避免多来源线程串诊断。 */
    fun parseDetailed(app: String, type: String, raw: String?): ParseResult = synchronized(lock) {
        if (scope == null || dispatch == null) return@synchronized ParseResult(null, Outcome.ERROR)
        val result = parse(app, type, raw)
        ParseResult(result, lastOutcome)
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
            val body = requireNotNull(normalizeRuleJs(rule)) { "规则脚本为空：${rule.systemRuleName}" }
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
     * 输入为 `var <id>=<expression>;`，剥掉与目录ID一致的左侧声明与
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
