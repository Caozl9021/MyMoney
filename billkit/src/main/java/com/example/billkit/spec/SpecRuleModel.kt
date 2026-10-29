package com.example.billkit.spec

/** 本地规则目录行；启用只代表证据准入，不代表自动入账。 */
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
    /** 输入到达时间与业务时间分开，不能冒充交易身份。 */
    val observedAt: Long = 0L,
    val hasExplicitTime: Boolean = false,
    val orderId: String = "",
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
            observedAt = num(map["observedAt"]).toLong(),
            hasExplicitTime = map["hasExplicitTime"] == true,
            orderId = str(map["orderId"]),
        )

        private fun str(value: Any?): String {
            val s = value?.toString() ?: return ""
            return if (s == "undefined" || s == "null") "" else s
        }

        private val DECIMAL = Regex("(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?")

        /** 完整匹配数值，不从自由文本中拼出金额。非法值保留 NaN 交给安全边界拒绝。 */
        private fun num(value: Any?): Double = when (value) {
            null -> 0.0
            is Number -> value.toDouble()
            is String -> value.trim().takeIf { DECIMAL.matches(it) }?.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
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
