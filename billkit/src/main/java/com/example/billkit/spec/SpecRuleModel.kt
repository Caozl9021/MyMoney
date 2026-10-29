package com.example.billkit.spec

/**
 * 一条规则在 `rule_model` 里的形态（对应 SPEC §7 与 `tools/import_rules.py` 的列）。
 *
 * @param app 规则适用的包名，例如 `com.tencent.mm`
 * @param type 数据形态：`DATA`（hook 到的结构化数据）或 `NOTICE`（通知栏）
 * @param systemRuleName 系统规则标识，例如 `rule_68`；规则 JS 的 `var rule_68=...` 靠它剥离
 * @param name 展示名，例如「美团月付支出」；命中后写进账单的 `rule_name`
 * @param autoRecord 是否自动记账。当前 163 条系统规则全部为 false（仅提示、等用户确认）
 * @param js 规则源码，形如 `var rule_68=function(t,r){...}({},common);`
 */
data class SpecRuleModel(
    val app: String,
    val type: String,
    val systemRuleName: String,
    val name: String,
    val creator: String = "system",
    val autoRecord: Boolean = false,
    val enabled: Boolean = true,
    val js: String,
) {
    companion object {
        /** 从 `rules_all.json` 的一条记录构造。字段缺失按「不启用」处理，避免脏数据带崩整包。 */
        fun fromJson(raw: Any?): SpecRuleModel? {
            val map = raw as? Map<*, *> ?: return null
            val app = map["app"] as? String ?: return null
            val type = map["type"] as? String ?: return null
            val systemRuleName = map["systemRuleName"] as? String ?: return null
            val js = map["js"] as? String ?: return null
            if (js.isBlank()) return null
            return SpecRuleModel(
                app = app,
                type = type,
                systemRuleName = systemRuleName,
                name = map["name"] as? String ?: systemRuleName,
                creator = map["creator"] as? String ?: "system",
                autoRecord = truthy(map["autoRecord"]),
                enabled = truthy(map["enabled"]),
                js = js,
            )
        }

        /** JSON 里 0/1、true/false 都出现过；缺失视为 false。 */
        private fun truthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toLong() != 0L
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }
    }
}

/**
 * 规则产出（对应 SPEC §6.4 的 10 参 RuleObject）。
 *
 * 字段全部可空：规则作者经常只写需要的几个字段，缺失就是「没提供」，
 * 不能补成猜测值（AGENTS.md §6.8）。`money` 是**元**，换算成分要走 [toCents]。
 */
data class SpecRuleObject(
    val type: String = "",
    val money: Double = 0.0,
    val shopName: String = "",
    val shopItem: String = "",
    val accountNameFrom: String = "",
    val accountNameTo: String = "",
    val fee: Double = 0.0,
    val currency: String = "",
    val time: Long = 0L,
    val channel: String = "",
) {
    val priceCents: Long get() = toCents(money)
    val feeCents: Long get() = toCents(fee)

    companion object {
        /** 从规则返回的 JS 对象（已转成 Map）构造。`undefined`/`null` 一律清成空串。 */
        fun fromJson(map: Map<String, Any?>): SpecRuleObject = SpecRuleObject(
            type = str(map["type"]),
            money = num(map["money"]),
            shopName = str(map["shopName"]),
            shopItem = str(map["shopItem"]),
            accountNameFrom = str(map["accountNameFrom"]),
            accountNameTo = str(map["accountNameTo"]),
            fee = num(map["fee"]),
            currency = str(map["currency"]),
            time = num(map["time"]).toLong(),
            channel = str(map["channel"]),
        )

        private fun str(value: Any?): String {
            val s = value?.toString() ?: return ""
            return if (s == "undefined" || s == "null") "" else s
        }

        private fun num(value: Any?): Double = when (value) {
            null -> 0.0
            is Number -> value.toDouble()
            is String -> {
                val s = value.trim()
                if (s.isEmpty() || s == "undefined" || s == "null") 0.0 else
                    s.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull() ?: 0.0
            }
            else -> 0.0
        }
    }
}

/** 一次规则命中的完整结果。 */
data class SpecRuleResult(
    val bill: SpecRuleObject,
    val ruleName: String,
    val ruleId: String,
    val autoRecord: Boolean,
)
