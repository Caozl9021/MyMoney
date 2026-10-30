package io.github.mymoney

import android.content.SharedPreferences
import io.github.mymoney.data.CategoryGroup
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.LedgerListCodec
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Category-only restore must not depend on the asset store or mutate its legacy input. */
class LedgerResetScopeTest {
    @Test fun restoringCategoriesPreservesAccountInputNoteAndUnrelatedPreferences() {
        withPreferences(linkedMapOf(
            "expense_groups" to LedgerListCodec.encodeGroups(listOf(CategoryGroup("自定义支出"))),
            "income_groups" to LedgerListCodec.encodeGroups(listOf(CategoryGroup("自定义收入"))),
            "expense_categories" to "[\"旧支出\"]",
            "income_categories" to "[\"旧收入\"]",
            "accounts" to "[\"自定义账户\"]",
            "note_template" to "{商户}·保留",
            "future_setting" to "保留",
        )) { values ->
            LedgerConfig.resetCategoriesToDefault()
            assertEquals(LedgerConfig.DEFAULT_EXPENSE, LedgerConfig.expenseGroups)
            assertEquals(LedgerConfig.DEFAULT_INCOME, LedgerConfig.incomeGroups)
            assertEquals("{商户}·保留", LedgerConfig.noteTemplate)
            assertEquals(mapOf(
                "accounts" to "[\"自定义账户\"]",
                "note_template" to "{商户}·保留",
                "future_setting" to "保留",
            ), values)
        }
    }

    @Test fun restoringCategoriesIsRepeatableForExplicitEmptyLists() {
        withPreferences(linkedMapOf(
            "expense_groups" to "[]", "income_groups" to "[]", "accounts" to "[]",
        )) { values ->
            repeat(2) { LedgerConfig.resetCategoriesToDefault() }
            assertEquals(LedgerConfig.DEFAULT_EXPENSE, LedgerConfig.expenseGroups)
            assertEquals(LedgerConfig.DEFAULT_INCOME, LedgerConfig.incomeGroups)
            assertEquals(mapOf("accounts" to "[]"), values)
        }
    }

    private fun withPreferences(initial: MutableMap<String, String>, block: (Map<String, String>) -> Unit) {
        val field = LedgerConfig::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previous = field.get(null)
        val removals = mutableSetOf<String>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "remove" -> { removals += args!![0] as String; proxy }
                "apply", "commit" -> { removals.forEach(initial::remove); removals.clear(); if (method.name == "commit") true else null }
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> initial[args!![0]] ?: args[1]
                else -> error("Unexpected preferences operation: ${method.name}")
            }
        } as SharedPreferences
        try { field.set(null, prefs); block(initial) } finally { field.set(null, previous) }
    }
}
