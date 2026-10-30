package com.example.mymoney.data

import com.example.billkit.*

data class ExternalRuleDocument(val version: Int, val pages: List<ExternalPageRule>, val candidates: List<RuleCandidate>)

/** Bounded literal grammar: contains, `|` alternatives for page clauses. No regex execution. */
object ExternalRuleCodec {
    const val MAX_BYTES = 65_536
    const val MAX_RULES = 200
    fun decode(text: String, source: RuleSource = RuleSource.JSON_OVERRIDE): ExternalRuleDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "规则文件不能超过64KiB" }
        // Check nesting before recursive MiniJson parser, including malicious nested arrays.
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (escaped) escaped = false
            else if (quoted && c == '\\') escaped = true
            else if (c == '"') quoted = !quoted
            else if (!quoted && (c == '[' || c == '{')) { depth++; require(depth <= 12) { "规则嵌套过深" } }
            else if (!quoted && (c == ']' || c == '}')) depth--
        }
        val root = MiniJson.decode(text) as? Map<*, *> ?: error("规则须为JSON对象")
        require(root.keys.all { it in setOf("version", "rules", "categories", "accounts") }) { "存在不支持的规则字段" }
        val version = if (root["version"] == null) 1 else {
            val number = root["version"] as? Long ?: error("version须为整数")
            require(number == 1L) { "不支持此规则版本" }; number.toInt()
        }
        require(version == 1) { "不支持此规则版本" }
        fun list(key: String): List<*> = root[key]?.let { it as? List<*> ?: error("$key 须为数组") } ?: emptyList<Any>()
        val pages = list("rules"); val categories = list("categories"); val accounts = list("accounts")
        require(pages.size + categories.size + accounts.size <= MAX_RULES) { "规则总数不能超过200" }
        fun strings(value: Any?, required: Boolean = false): List<String> {
            val values = value as? List<*> ?: if (value == null && !required) return emptyList() else error("条件须为数组")
            require(values.size <= 24) { "单组条件过多" }
            return values.map { expression(it) }.also { if (required) require(it.isNotEmpty()) { "条件不能为空" } }
        }
        val pageRules = pages.map { value ->
            val m = obj(value, setOf("packages", "kind", "require", "anyOf", "exclude", "strategy", "match"))
            require(m["match"] == null || m["match"] == "contains") { "只支持contains；正则不允许" }
            val packages = strings(m["packages"], true)
            require(packages.all { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }) { "包名不合法" }
            val any = m["anyOf"]?.let { it as? List<*> ?: error("anyOf须为数组") } ?: emptyList<Any>()
            require(any.size <= 24)
            ExternalPageRule(packages.toSet(), PageKind.valueOf(expression(m["kind"])),
                strings(m["require"], true), any.map { strings(it, true) }, strings(m["exclude"]),
                "JSON:${source.name}:${expression(m["strategy"])}")
        }
        fun keywords(values: List<*>, account: Boolean): List<RuleCandidate> = values.flatMap { value ->
            val m = obj(value, setOf("type", "keywords", "category", "subCategory", "account", "matchMethod", "match"))
            require(m["match"] == null || m["match"] == "contains") { "只支持contains；正则不允许" }
            val type = BillType.valueOf(expression(m["type"]))
            val category = if (!account) expression(m["category"]) else null
            val sub = m["subCategory"]?.let(::expression)
            val target = if (account) expression(m["account"]) else null
            val method = m["matchMethod"]?.let { it as? Boolean ?: error("matchMethod须为布尔值") } ?: account
            strings(m["keywords"], true).map { RuleCandidate(type, it, category, sub, target, source, false, method) }
        }
        val candidates = keywords(categories, false) + keywords(accounts, true)
        require(candidates.size <= 500) { "关键词总数不能超过500" }
        return ExternalRuleDocument(version, pageRules, candidates)
    }
    private fun expression(value: Any?): String = (value as? String ?: error("条件须为文本")).also {
        require(it.isNotBlank() && it.length <= 120 && it.none { c -> c < ' ' }) { "条件须为1至120字且不含控制字符" }
        require(it.split('|').none(String::isBlank)) { "不能使用空匹配词" }
    }
    private fun obj(value: Any?, keys: Set<String>): Map<*, *> = (value as? Map<*, *> ?: error("规则须为对象")).also {
        require(it.keys.all { key -> key in keys }) { "存在不支持的规则字段（正则不允许）" }
    }
}
