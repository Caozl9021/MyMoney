package io.github.mymoney

import com.example.billkit.spec.PersonalRule
import com.example.billkit.spec.RulePackageFiles
import io.github.mymoney.data.MerchantNoteRule
import io.github.mymoney.data.NoteFillConfig
import io.github.mymoney.data.NoteFillMode
import io.github.mymoney.data.rulesync.RuleSyncKind
import io.github.mymoney.data.rulesync.RuleSyncMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSyncMigrationTest {
    private fun personal(id: String, name: String, order: Long = 0) = PersonalRule(
        id = id,
        name = name,
        keyword = "关键词$name",
        updatedAt = order,
    )

    @Test fun migrationKeepsStableIdsAndPreservesSortOrder() {
        val state = RulePackageFiles.State(
            enabled = mapOf("rule-b" to false, "rule-a" to true),
            personalRules = listOf(personal("personal_aaa111", "甲", 10), personal("personal_bbb222", "乙", 20)),
        )
        val policy = NoteFillConfig(
            productFirst = true,
            rules = listOf(
                MerchantNoteRule("com.tencent.mm", "店A", "EXPENSE", NoteFillMode.FIXED, "固定A"),
                MerchantNoteRule("com.eg.android.AlipayGphone", "店B", "INCOME", NoteFillMode.NONE, ""),
            ),
        )
        val items = RuleSyncMigration.plan(RuleSyncMigration.Input(state, policy, "{商户}-{商品}", mapOf("rule-c" to true)))
        assertEquals(listOf("personal_aaa111", "personal_bbb222"), items.filter { it.kind == RuleSyncKind.RECOGNITION_RULE }.map { it.id })
        assertEquals(listOf(0L, 1000L), items.filter { it.kind == RuleSyncKind.RECOGNITION_RULE }.map { it.sortKey })
        assertEquals(3, items.count { it.kind == RuleSyncKind.OFFICIAL_RULE_SWITCH })
        assertEquals(2, items.count { it.kind == RuleSyncKind.MERCHANT_NOTE_RULE })
        assertEquals("note_defaults", items.single { it.kind == RuleSyncKind.NOTE_DEFAULTS }.id)
        assertTrue(items.filter { it.kind == RuleSyncKind.MERCHANT_NOTE_RULE }.all { it.naturalKey != null })
    }

    @Test fun migrationRejectsDuplicatePersonalIdsAndMerchantNaturalKeys() {
        val duplicatePersonal = RulePackageFiles.State(personalRules = listOf(personal("personal_aaa111", "甲"), personal("personal_aaa111", "乙")))
        assertTrue(runCatching {
            RuleSyncMigration.plan(RuleSyncMigration.Input(duplicatePersonal, NoteFillConfig(), "{商户}"))
        }.isFailure)

        val duplicateMerchant = NoteFillConfig(
            rules = listOf(
                MerchantNoteRule("com.tencent.mm", "店A", "EXPENSE", NoteFillMode.PRODUCT, ""),
                MerchantNoteRule("com.tencent.mm", " 店A ", "EXPENSE", NoteFillMode.NONE, ""),
            ),
        )
        assertTrue(runCatching {
            RuleSyncMigration.plan(RuleSyncMigration.Input(RulePackageFiles.State(), duplicateMerchant, "{商户}"))
        }.isFailure)
    }
}
