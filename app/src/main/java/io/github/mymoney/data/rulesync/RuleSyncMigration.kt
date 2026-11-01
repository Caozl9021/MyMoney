package io.github.mymoney.data.rulesync

import com.example.billkit.spec.RulePackageFiles
import io.github.mymoney.data.NoteFillConfig

/** Pure migration plan for the four legacy rule sources. Kept separate so it can be tested on the JVM. */
object RuleSyncMigration {
    data class Input(
        val packageState: RulePackageFiles.State,
        val legacyPolicy: NoteFillConfig,
        val noteTemplate: String,
        val oldRoomDisabled: Map<String, Boolean> = emptyMap(),
    )

    fun plan(input: Input): List<LocalRuleItemEntity> {
        val personalIds = mutableSetOf<String>()
        val merchantKeys = mutableSetOf<String>()
        val items = mutableListOf<LocalRuleItemEntity>()

        input.packageState.personalRules.forEachIndexed { index, rule ->
            rule.validate()
            require(personalIds.add(rule.id)) { "个人规则ID重复：${rule.id}" }
            items += LocalRuleItemEntity(
                id = rule.id,
                kind = RuleSyncKind.RECOGNITION_RULE,
                payload = RuleSyncCodec.personalPayload(rule),
                naturalKey = rule.id,
                sortKey = index.toLong() * 1000L,
                contentUpdatedAt = rule.updatedAt,
            )
        }

        val enabled = input.packageState.enabled + input.oldRoomDisabled
        enabled.entries.forEach { (ruleId, value) ->
            require(ruleId.isNotBlank()) { "官方规则ID为空" }
            items += LocalRuleItemEntity(
                id = RuleSyncCodec.stableOfficialId(ruleId),
                kind = RuleSyncKind.OFFICIAL_RULE_SWITCH,
                payload = RuleSyncCodec.officialPayload(value),
                naturalKey = ruleId,
            )
        }

        input.legacyPolicy.rules.forEachIndexed { index, rule ->
            val saved = rule.copy(id = rule.id.ifBlank { RuleSyncCodec.stableMerchantId(rule.key) })
            saved.validate()
            require(merchantKeys.add(saved.key)) { "备注规则自然键重复：${saved.key}" }
            items += LocalRuleItemEntity(
                id = saved.id,
                kind = RuleSyncKind.MERCHANT_NOTE_RULE,
                payload = RuleSyncCodec.merchantPayload(saved),
                naturalKey = saved.key,
                sortKey = index.toLong() * 1000L,
            )
        }

        require(input.noteTemplate.length <= 200) { "备注模板过长" }
        items += LocalRuleItemEntity(
            id = "note_defaults",
            kind = RuleSyncKind.NOTE_DEFAULTS,
            payload = RuleSyncCodec.noteDefaultsPayload(input.noteTemplate, input.legacyPolicy.productFirst),
            naturalKey = null,
        )
        return items
    }
}
