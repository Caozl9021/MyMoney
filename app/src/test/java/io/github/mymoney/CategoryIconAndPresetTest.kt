package io.github.mymoney

import io.github.mymoney.data.CategoryGroup
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.ui.design.AbAccentSwatches
import io.github.mymoney.ui.design.AccountLogos
import io.github.mymoney.ui.design.CategoryIcons
import io.github.mymoney.ui.design.CategoryPresets
import io.github.mymoney.ui.design.parseColorHex
import io.github.mymoney.ui.design.toHexRgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryIconAndPresetTest {

    @Test
    fun allGroupedIconPathsCompileWithoutError() {
        assertTrue(CategoryIcons.keys.size >= 85)
        val fallbackVector = CategoryIcons.vectorFor(CategoryIcons.FALLBACK)
        CategoryIcons.keys.forEach { key ->
            val vector = CategoryIcons.vectorFor(key)
            assertNotNull("Vector for $key must exist", vector)
            if (key != CategoryIcons.FALLBACK) {
                assertTrue("Icon key '$key' must not silently fall back to FALLBACK", vector.name != fallbackVector.name)
            }
        }
        val groupedKeys = CategoryIcons.groups.flatMap { it.keys }.toSet()
        assertEquals(CategoryIcons.keys.toSet(), groupedKeys)

        val accountKeys = AccountLogos.iconGroups.flatMap { it.keys }
        assertTrue(accountKeys.size >= 35)
        accountKeys.forEach { key ->
            assertTrue("Account icon $key must be in CategoryIcons.keys", key in CategoryIcons.keys)
        }
    }

    @Test
    fun colorHexHelpersRoundTripAllSwatches() {
        assertEquals(16, AbAccentSwatches.size)
        AbAccentSwatches.forEach { color ->
            val hex = color.toHexRgb()
            val parsed = parseColorHex(hex)
            assertNotNull(parsed)
            assertEquals(hex, parsed!!.toHexRgb())
        }
    }

    @Test
    fun defaultCategoriesAndPresetPacksCoverMainstreamNeeds() {
        assertTrue(LedgerConfig.DEFAULT_EXPENSE.size >= 18)
        assertTrue(LedgerConfig.DEFAULT_INCOME.size >= 8)
        assertTrue(CategoryPresets.packs.isNotEmpty())
        CategoryPresets.packs.forEach { pack ->
            assertTrue(pack.presets.isNotEmpty())
            pack.presets.forEach { preset ->
                assertTrue(preset.iconKey in CategoryIcons.keys)
            }
        }
    }
}
