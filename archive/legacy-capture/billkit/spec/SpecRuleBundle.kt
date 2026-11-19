package com.example.billkit.spec

import java.security.MessageDigest

/** 完整规则包清单校验；失败前不允许写入数据库。 */
data class SpecRuleBundle(val version: String, val digest: String, val rules: List<SpecRuleModel>) {
    companion object {
        const val MANIFEST = "spec/manifest.json"
        val FILES = listOf("rules_all.json", "js_common.js", "js_category.js", "categories.json", "assets.json")

        fun load(read: (String) -> String): SpecRuleBundle {
            val rawManifest = read(MANIFEST)
            val manifest = SpecJson.decodeObject(rawManifest) ?: error("规则清单无效")
            require((manifest["schemaVersion"] as? Number)?.toDouble() == 1.0) { "不支持的规则清单版本" }
            val version = (manifest["version"] as? String)?.takeIf { it.isNotBlank() } ?: error("规则版本缺失")
            val hashes = manifest["files"] as? Map<*, *> ?: error("资产摘要缺失")
            require(hashes.keys == FILES.toSet()) { "规则清单必须覆盖五份资产" }
            val contents = FILES.associateWith { name ->
                val content = read("spec/$name")
                require(hashes[name] == sha256(content)) { "规则资产摘要不一致：$name" }
                content
            }
            val rawRules = SpecJson.decodeArray(contents.getValue("rules_all.json"))
            val rules = SpecAssets.parseRules(contents.getValue("rules_all.json"))
            require(rules.isNotEmpty() && rules.size == rawRules.size) { "规则目录含无效或空规则" }
            require(rules.map { it.systemRuleName }.toSet().size == rules.size) { "规则标识重复" }
            require(rules.all { it.app.isNotBlank() && it.type in setOf("DATA", "NOTICE") &&
                it.creator == "maimi" && it.systemRuleName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !it.autoRecord }) { "规则元数据无效" }
            val entries = manifest["rules"] as? List<*> ?: error("规则证据清单缺失")
            require(entries.size == rules.size) { "规则清单条数不一致" }
            val indexed = entries.associate { value ->
                val row = value as? Map<*, *> ?: error("规则证据行无效")
                (row["id"] as? String ?: error("规则证据ID缺失")) to row
            }
            require(indexed.size == rules.size) { "规则证据ID重复" }
            for (rule in rules) {
                val row = indexed[rule.systemRuleName] ?: error("规则证据缺失")
                require(row["app"] == rule.app && row["type"] == rule.type && row["enabled"] == rule.enabled) { "规则证据元数据不一致" }
                require((row["evidence"] as? List<*>)?.all { it is String && it.isNotBlank() } == true) { "规则来源引用格式无效" }
            }
            val categoryJson = contents.getValue("categories.json")
            val categories = SpecCategory.parseAll(categoryJson)
            require(categories.isNotEmpty() && categories.size == SpecJson.decodeArray(categoryJson).size &&
                categories.map { it.id }.toSet().size == categories.size && categories.all { it.name.isNotBlank() }) {
                "分类建议表含无效行、重复ID或为空"
            }
            val assetJson = contents.getValue("assets.json")
            val assets = SpecAsset.parseAll(assetJson)
            require(assets.isNotEmpty() && assets.size == SpecJson.decodeArray(assetJson).size &&
                assets.map { it.id }.toSet().size == assets.size && assets.all { it.name.isNotBlank() && it.currency == "CNY" }) {
                "账户建议表含无效行、重复ID或为空"
            }
            return SpecRuleBundle(version, sha256(rawManifest + FILES.joinToString { contents.getValue(it) }), rules)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
