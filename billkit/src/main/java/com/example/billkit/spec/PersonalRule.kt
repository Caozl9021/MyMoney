package com.example.billkit.spec

/** User-authored literal conditions, never executable code or user supplied regular expressions. */
data class PersonalRule(
    val id: String, val name: String, val app: String = "com.tencent.mm", val inputType: String = "PAGE",
    val billType: String = "Expend", val keyword: String, val amountSource: String = "AUTO",
    val merchant: String = "", val method: String = "", val enabled: Boolean = true, val updatedAt: Long? = null,
) {
    fun validate() {
        require(updatedAt == null || updatedAt > 0) { "规则修改时间无效" }
        require(Regex("personal_[A-Za-z0-9_-]{6,80}").matches(id)) { "个人规则标识无效" }
        require(name.isNotBlank() && name.length <= 40) { "请填写40字以内的规则名称" }
        require(app in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) { "请选择微信或支付宝" }
        require(inputType in setOf("PAGE", "DATA", "NOTICE")) { "请选择支付页面、支付凭证或交易通知" }
        require(billType in setOf("Expend", "Income", "Refund")) { "请选择支出、收入或退款" }
        require(keyword.trim().length in 2..80 && keyword.any { it.isLetter() }) { "匹配文字需为2至80字，不能只有数字" }
        require(amountSource in setOf("AUTO", "TITLE", "DESCRIPTION")) { "金额来源无效" }
        require(merchant.length <= 80 && method.length <= 80) { "商户或支付方式不能超过80字" }
    }
    fun json(): Map<String, Any?> = mapOf("id" to id, "name" to name, "app" to app, "inputType" to inputType,
        "billType" to billType, "keyword" to keyword, "amountSource" to amountSource,
        "merchant" to merchant, "method" to method, "enabled" to enabled, "updatedAt" to updatedAt)
}

object PersonalRuleCodec {
    const val MAX_BYTES = 256 * 1024
    fun decodeRows(value: Any?): List<PersonalRule> {
        val rows = value as? List<*> ?: error("个人规则列表无效")
        require(rows.size <= 100) { "个人规则最多100条" }
        val allowed = setOf("id", "name", "app", "inputType", "billType", "keyword", "amountSource", "merchant", "method", "enabled")
        val parsed = rows.map { raw ->
            val m = raw as? Map<*, *> ?: error("个人规则格式无效")
            require(m.keys.containsAll(allowed) && (m.keys - allowed).all { it == "updatedAt" }) { "个人规则字段缺失或包含不支持的内容" }
            fun text(key: String) = m[key] as? String ?: error("个人规则字段无效：$key")
            PersonalRule(text("id"), text("name"), text("app"), text("inputType"), text("billType"), text("keyword"),
                text("amountSource"), text("merchant"), text("method"), m["enabled"] as? Boolean ?: error("规则开关无效"), m["updatedAt"]?.let { v ->
                    require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble()) { "规则修改时间无效" }; v.toLong()
                }).also { it.validate() }
        }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "个人规则标识重复" }
        return parsed
    }
    fun export(rules: List<PersonalRule>): String {
        rules.forEach { it.validate() }
        return SpecJson.encode(mapOf("kind" to "MyMoneyPersonalRules", "schemaVersion" to 1, "rules" to rules.map { it.json() }))
    }
    fun decode(text: String): List<PersonalRule> {
        require(text.toByteArray().size <= MAX_BYTES) { "个人规则文件超过256KB" }
        val m = SpecJson.decodeObject(text)
        require(m.keys == setOf("kind", "schemaVersion", "rules") && m["kind"] == "MyMoneyPersonalRules" &&
            (m["schemaVersion"] as? Number)?.toDouble() == 1.0) { "请选择本应用导出的个人规则文件" }
        return decodeRows(m["rules"])
    }
}
