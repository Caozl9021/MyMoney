package io.github.mymoney

import io.github.mymoney.lsp.ManagerLaunchPolicy
import io.github.mymoney.lsp.ManagerOpenResult
import io.github.mymoney.ui.lspAvailable
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

    // ---------- pidof lspd：凭证入口的第二条证据 ----------

    @Test fun daemonNeedsExitZeroAndPurePidOutput() {
        assertTrue(ManagerLaunchPolicy.daemonEvidence(0, "1234"))
        assertTrue("多个 pid 也算在跑", ManagerLaunchPolicy.daemonEvidence(0, "1234 5678"))
        assertTrue("换行与尾巴空白不影响", ManagerLaunchPolicy.daemonEvidence(0, " 1234 \n"))
        assertFalse("没找到进程时 pidof 退出码非 0", ManagerLaunchPolicy.daemonEvidence(1, ""))
        assertFalse(ManagerLaunchPolicy.daemonEvidence(0, ""))
        assertFalse("没看懂的话不算证据", ManagerLaunchPolicy.daemonEvidence(0, "su: not allowed"))
        assertFalse(ManagerLaunchPolicy.daemonEvidence(0, "1234\nError"))
    }

    @Test fun lspEntryVisibleWhenEitherManagerOrDaemonProvesIt() {
        assertTrue("管理器可见就够了", lspAvailable(managerInstalled = true, daemonRunning = null))
        assertTrue("隐藏式管理器：靠守护进程补上", lspAvailable(managerInstalled = false, daemonRunning = true))
        assertTrue(lspAvailable(managerInstalled = true, daemonRunning = false))
        assertFalse("两条都没有就不画入口", lspAvailable(managerInstalled = false, daemonRunning = false))
        assertFalse("还没探出来时保持原样，不提前画", lspAvailable(managerInstalled = false, daemonRunning = null))
    }
}
