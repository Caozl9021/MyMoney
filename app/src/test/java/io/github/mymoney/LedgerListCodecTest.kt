package io.github.mymoney

import io.github.mymoney.data.Backup
import io.github.mymoney.data.CategoryGroup
import io.github.mymoney.data.LedgerListCodec
import org.junit.Assert.*
import org.junit.Test

class LedgerListCodecTest {
    private val groups = listOf(CategoryGroup("默认", listOf("子类")))
    private val accounts = listOf("默认账户")

    @Test fun explicitEmptyListsSurviveEncodingAndRepeatedReading() {
        val rawGroups = LedgerListCodec.encodeGroups(emptyList())
        val rawAccounts = LedgerListCodec.encodeStrings(emptyList())
        repeat(3) {
            assertEquals(emptyList<CategoryGroup>(), LedgerListCodec.readGroups(rawGroups, groups))
            assertEquals(emptyList<String>(), LedgerListCodec.readStrings(rawAccounts, accounts))
        }
    }

    @Test fun missingMalformedAndWrongTypedListsFallBack() {
        listOf(null, "bad", "{}", "[42]", "[{\"n\":\"\"}]").forEach {
            assertEquals(groups, LedgerListCodec.readGroups(it, groups))
        }
        listOf(null, "bad", "{}", "[42]", "[\"\"]").forEach {
            assertEquals(accounts, LedgerListCodec.readStrings(it, accounts))
        }
    }

    @Test fun namesAreTrimmedAndDuplicatesRemovedWithoutDefaultInsertion() {
        assertEquals(listOf(CategoryGroup("自定", listOf("子类"))), LedgerListCodec.readGroups(
            LedgerListCodec.encodeGroups(listOf(CategoryGroup(" 自定 ", listOf(" 子类 ", "子类")), CategoryGroup("自定"))), groups))
        assertEquals(emptyList<String>(), LedgerListCodec.readStrings(LedgerListCodec.encodeStrings(listOf(" ")), accounts))
    }

    @Test fun backupExplicitEmptyAndMissingFieldsRemainDistinctAcrossFormatUpgrade() {
        val empty = Backup.decode("""{"format":2,"bills":[],"ledger":{"expense":[],"income":[],"accounts":[],"noteTemplate":""}}""")
        val config = empty.ledger!!
        assertTrue(config.hasExpense && config.hasIncome && config.hasAccounts && config.hasNoteTemplate)
        assertTrue(config.expense.isEmpty() && config.income.isEmpty() && config.accounts.isEmpty())
        assertEquals(empty, Backup.decode(Backup.encode(empty)))
        val partial = Backup.decode("""{"format":1,"bills":[],"ledger":{"income":[]}}""")
        assertFalse(partial.ledger!!.hasExpense)
        assertFalse(partial.ledger!!.hasAccounts)
        assertFalse(partial.ledger!!.hasNoteTemplate)
        assertTrue(partial.ledger!!.hasIncome)
        assertEquals(partial, Backup.decode(Backup.encode(partial)))
        assertNull(Backup.decode("""{"format":1,"bills":[]}""").ledger)
        assertEquals(8, Backup.FORMAT)
        assertNull(empty.assets)
        assertNull(partial.assets)
        assertTrue(Backup.encode(empty).contains("\"format\":${Backup.FORMAT}"))
    }

    @Test fun malformedBackupArrayCannotMasqueradeAsExplicitEmpty() {
        listOf("null", "{}", "42").forEach { raw ->
            try {
                Backup.decode("""{"bills":[],"ledger":{"accounts":$raw}}""")
                fail("Invalid array must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun invalidCategoryAndWrongTypedNoteDoNotBecomeReplacementDefaults() {
        listOf(
            """"expense":[{}]""", """"income":[{"n":""}]""", """"expense":[42]""",
            """"noteTemplate":null""", """"noteTemplate":42""",
        ).forEach { field ->
            try {
                Backup.decode("""{"bills":[],"ledger":{$field}}""")
                fail("Malformed present configuration must fail")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
