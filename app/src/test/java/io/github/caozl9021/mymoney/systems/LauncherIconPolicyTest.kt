package io.github.caozl9021.mymoney.systems

import org.junit.Assert.*
import org.junit.Test

class LauncherIconPolicyTest {
    private val green = LauncherIconChoice.GREEN
    private val blue = LauncherIconChoice.BLUE
    private val red = LauncherIconChoice.RED

    private class Fake(var current: Set<LauncherIconChoice>) : LauncherIconBackend {
        val calls = mutableListOf<LauncherIconChoice>()
        var action: ((LauncherIconChoice) -> Unit)? = null
        override fun enabled() = current
        override fun apply(choice: LauncherIconChoice) {
            calls += choice
            val callback = action
            if (callback == null) current = setOf(choice) else callback(choice)
        }
    }

    @Test fun freshDefaultRequiresNoComponentMutation() {
        val fake = Fake(setOf(green))
        assertEquals(green, LauncherIconController(fake).reconcile(null))
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun persistedSystemChoiceWinsOverStalePreference() {
        val fake = Fake(setOf(red))
        assertEquals(red, LauncherIconController(fake).reconcile(blue.id))
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun invalidPreferenceDoesNotEraseSystemSelection() {
        assertEquals(blue, LauncherIconController(Fake(setOf(blue))).reconcile("unknown"))
    }
    @Test fun noEnabledAliasRestoresSavedChoice() {
        val fake = Fake(emptySet())
        assertEquals(blue, LauncherIconController(fake).reconcile(blue.id))
        assertEquals(setOf(blue), fake.current)
    }
    @Test fun noEnabledAliasAndBadPreferenceRestoresDefault() {
        assertEquals(green, LauncherIconController(Fake(emptySet())).reconcile("invalid"))
    }
    @Test fun interruptedSequentialChangeConvergesUsingSavedChoice() {
        val fake = Fake(setOf(green, blue))
        assertEquals(green, LauncherIconController(fake).reconcile(green.id))
        assertEquals(setOf(green), fake.current)
    }
    @Test fun multipleAliasesWithoutPreferenceRestoreDefault() {
        assertEquals(green, LauncherIconController(Fake(setOf(red, blue))).reconcile(null))
    }
    @Test fun allThreeChoicesRoundTripAndPersistedStateIsReadableByNewController() {
        val fake = Fake(setOf(green))
        for (choice in listOf(blue, red, green)) {
            val result = LauncherIconController(fake).select(choice, null)
            assertTrue(result.success)
            assertTrue(result.changed)
            assertEquals(choice, result.selection)
            assertEquals(choice, LauncherIconController(fake).reconcile("stale"))
        }
    }
    @Test fun repeatTapIsNoOp() {
        val fake = Fake(setOf(blue))
        val result = LauncherIconController(fake).select(blue, blue.id)
        assertTrue(result.success)
        assertFalse(result.changed)
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun failureBeforeEnableKeepsOldUsableChoice() {
        val fake = Fake(setOf(green))
        fake.action = { if (it == blue) error("enable rejected") else fake.current = setOf(it) }
        val result = LauncherIconController(fake).select(blue, green.id)
        assertFalse(result.success)
        assertEquals(green, result.selection)
        assertEquals(setOf(green), fake.current)
    }
    @Test fun failureAfterEnableRollsBackDoubleEntry() {
        val fake = Fake(setOf(green))
        fake.action = {
            if (it == blue) { fake.current = setOf(green, blue); error("old disable rejected") }
            fake.current = setOf(it)
        }
        val result = LauncherIconController(fake).select(blue, green.id)
        assertFalse(result.success)
        assertEquals(green, result.selection)
        assertEquals(listOf(blue, green), fake.calls)
    }
    @Test fun silentSystemRefusalIsDetectedAndNotReportedAsSuccess() {
        val fake = Fake(setOf(green)); fake.action = { }
        val result = LauncherIconController(fake).select(red, green.id)
        assertFalse(result.success)
        assertEquals(green, result.selection)
    }
    @Test fun rollbackFailureDoesNotInventASingleSelectedChoice() {
        val fake = Fake(setOf(green))
        fake.action = { fake.current = setOf(green, blue); error("persistent failure") }
        val result = LauncherIconController(fake).select(blue, green.id)
        assertFalse(result.success)
        assertNull(result.selection)
        assertFalse(fake.current.isEmpty())
        assertEquals(1, result.error!!.suppressed.size)
    }
    @Test fun failedRecoveryDoesNotStartAnotherSwitch() {
        val fake = Fake(setOf(green, blue)); fake.action = { error("component service unavailable") }
        val result = LauncherIconController(fake).select(red, green.id)
        assertFalse(result.success)
        assertNull(result.selection)
        assertEquals(listOf(green), fake.calls)
    }
    @Test fun stableIdsAreNotFilenameOrOrdinalDependent() {
        assertEquals(green, LauncherIconChoice.fromId("logo1"))
        assertEquals(red, LauncherIconChoice.fromId("logo3"))
        assertNull(LauncherIconChoice.fromId("loso3.png"))
    }
}
