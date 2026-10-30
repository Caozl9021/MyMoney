package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.lsp.ManagerLaunchPolicy
import io.github.caozl9021.mymoney.lsp.ManagerOpenResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManagerLaunchPolicyTest {
    @Test fun standaloneFailureContinuesAndSuccessAvoidsRoot() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ManagerLaunchPolicy.open(listOf({ calls += "denied"; throw SecurityException() },
            { calls += "installed"; true }), true,
            { error("Root should not run") }, { error("No parasitic launch") }, { error("No Root launch") })
        assertEquals(ManagerOpenResult.STANDALONE, result)
        assertEquals(listOf("denied", "installed"), calls)
    }
    @Test fun nonRootNeverProbesOrStartsParasiticHost() = runBlocking {
        assertEquals(ManagerOpenResult.NO_ENTRY, ManagerLaunchPolicy.open(emptyList(), false,
            { error("No su outside Root") }, { error("No bugreport") }, { error("No Root launch") }))
    }
    @Test fun deniedOrAbsentDaemonDoesNotOpenBugreport() = runBlocking {
        assertEquals(ManagerOpenResult.UNCONFIRMED, ManagerLaunchPolicy.open(emptyList(), true,
            { false }, { error("No bugreport") }, { error("No Root launch") }))
    }
    @Test fun normalParasiticEntryAvoidsRootLaunch() = runBlocking {
        assertEquals(ManagerOpenResult.PARASITIC, ManagerLaunchPolicy.open(emptyList(), true,
            { true }, { true }, { error("No privileged start needed") }))
    }
    @Test fun deniedNormalLaunchUsesRootFallbackOnce() = runBlocking {
        var roots = 0
        assertEquals(ManagerOpenResult.ROOT_PARASITIC, ManagerLaunchPolicy.open(emptyList(), true,
            { true }, { throw SecurityException() }, { roots++; true }))
        assertEquals(1, roots)
    }
    @Test fun rootFailureStaysFailure() = runBlocking {
        assertEquals(ManagerOpenResult.FAILED, ManagerLaunchPolicy.open(emptyList(), true,
            { true }, { false }, { false }))
    }
    @Test fun commandExitZeroWithAndroidErrorIsNotSuccess() {
        assertFalse(ManagerLaunchPolicy.acceptedShellStart(0, "Starting: Intent {}\nError: Activity not started"))
        assertFalse(ManagerLaunchPolicy.acceptedShellStart(0, "Starting: Intent {}\nPermission Denial"))
        assertFalse(ManagerLaunchPolicy.acceptedShellStart(0, ""))
    }
    @Test fun timeoutAndDeniedExitNeverClaimSuccess() {
        assertFalse(ManagerLaunchPolicy.acceptedShellStart(-1, "Starting: Intent {}"))
        assertFalse(ManagerLaunchPolicy.acceptedShellStart(1, "Starting: Intent {}"))
        assertTrue(ManagerLaunchPolicy.acceptedShellStart(0, "Starting: Intent { act=android.intent.action.MAIN }"))
    }
}
