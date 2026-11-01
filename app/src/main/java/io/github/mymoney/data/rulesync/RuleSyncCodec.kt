package io.github.mymoney.data.rulesync

import com.example.billkit.spec.PersonalRule
import com.example.billkit.spec.PersonalRuleCodec
import io.github.mymoney.data.MerchantNoteRule
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.NoteFillConfig
import io.github.mymoney.data.NoteFillMode
import java.security.MessageDigest

/** Wire payloads are deliberately small and validated before they reach the repository. */
object RuleSyncCodec {
    const val SCHEMA_VERSION = 1

    fun personalPayload(rule: PersonalRule): String = MiniJson.encode(rule.json())

    fun personalFromPayload(payload: String): PersonalRule =
        PersonalRuleCodec.decodeRows(listOf(MiniJson.decode(payload))).single()

    fun merchantPayload(rule: MerchantNoteRule): String = MiniJson.encode(mapOf(
        "id" to rule.id,
        "app" to rule.app,
        "merchant" to rule.merchant,
        "type" to rule.type,
        "mode" to rule.mode.name,
        "text" to rule.text,
    ))

    fun merchantFromPayload(payload: String): MerchantNoteRule {
        val map = MiniJson.decode(payload) as? Map<*, *> ?: error("商家备注规则格式无效")
        require(map.keys == setOf("id", "app", "merchant", "type", "mode", "text")) { "商家备注规则字段不完整" }
        fun text(key: String): String = map[key] as? String ?: error("商家备注规则字段无效：$key")
        val rule = MerchantNoteRule(
            app = text("app"),
            merchant = text("merchant"),
            type = text("type"),
            mode = runCatching { NoteFillMode.valueOf(text("mode")) }.getOrElse { error("商家备注模式无效") },
            text = text("text"),
            id = text("id"),
        )
        rule.validate()
        return rule
    }

    fun officialPayload(enabled: Boolean): String = MiniJson.encode(mapOf("enabled" to enabled))

    fun officialFromPayload(payload: String): Boolean {
        val map = MiniJson.decode(payload) as? Map<*, *> ?: error("官方规则开关格式无效")
        require(map.keys == setOf("enabled")) { "官方规则开关字段不完整" }
        return map["enabled"] as? Boolean ?: error("官方规则开关值无效")
    }

    fun noteDefaultsPayload(noteTemplate: String, productFirst: Boolean): String =
        MiniJson.encode(mapOf("noteTemplate" to noteTemplate, "productFirst" to productFirst))

    fun noteDefaultsFromPayload(payload: String): Pair<String, Boolean> {
        val map = MiniJson.decode(payload) as? Map<*, *> ?: error("备注默认设置格式无效")
        require(map.keys == setOf("noteTemplate", "productFirst")) { "备注默认设置字段不完整" }
        val template = map["noteTemplate"] as? String ?: error("备注模板无效")
        require(template.length <= 200) { "备注模板过长" }
        val productFirst = map["productFirst"] as? Boolean ?: error("商品优先设置无效")
        return template to productFirst
    }

    /** JSON object key order is not part of rule identity. */
    fun samePayload(left: String, right: String): Boolean = runCatching {
        MiniJson.decode(left) == MiniJson.decode(right)
    }.getOrElse { left == right }

    /** Stable fallback for legacy merchant rules that only had a natural key. */
    fun stableMerchantId(naturalKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(naturalKey.toByteArray(Charsets.UTF_8))
        return "merchant_" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun stableOfficialId(ruleId: String): String =
        "official_" + MessageDigest.getInstance("SHA-256").digest(ruleId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
}

data class RuleSyncSnapshot(
    val personalRules: List<PersonalRule> = emptyList(),
    val officialEnabled: Map<String, Boolean> = emptyMap(),
    val merchantRules: List<MerchantNoteRule> = emptyList(),
    val noteTemplate: String = "{商户}",
    val productFirst: Boolean = true,
    val generation: Long = 0L,
    val activeGeneration: Long = 0L,
) {
    fun noteConfig(): NoteFillConfig = NoteFillConfig(productFirst, merchantRules)
}

data class RuleSyncRemoteItem(
    val itemId: String,
    val kind: String,
    val schemaVersion: Int,
    val payload: String,
    val naturalKey: String?,
    val sortKey: Long,
    val revision: Long,
    val contentUpdatedAt: Long?,
    val deletedAt: Long?,
)
