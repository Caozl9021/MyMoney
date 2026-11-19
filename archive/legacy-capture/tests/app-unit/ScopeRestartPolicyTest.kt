package io.github.mymoney

import io.github.mymoney.lsp.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScopeRestartPolicyTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"

    @Test fun nonRootCannotProbeOrStopAnything() = runBlocking {
        val report = ScopeRestartPolicy.restart(false, { error("No package probing needed") },
            { error("No Root outside mode") }, { error("No stop") }, { error("No start") })
        assertTrue(report.rootDenied)
    }
    @Test fun rootDeniedNeverStopsApps() = runBlocking {
        val report = ScopeRestartPolicy.restart(true, { true }, { false },
            { error("No stop without Root") }, { error("No start") })
        assertTrue(report.rootDenied)
    }
    @Test fun bothMissingAvoidsRootPrompt() = runBlocking {
        val report = ScopeRestartPolicy.restart(true, { false }, { error("No Root prompt needed") },
            { error("No stop") }, { error("No start") })
        assertEquals(listOf(ScopeRestartOutcome.UNAVAILABLE, ScopeRestartOutcome.UNAVAILABLE), report.items.map { it.outcome })
    }
    @Test fun missingStartEntryIsNeverStopped() = runBlocking {
        val stopped = mutableListOf<String>()
        val report = ScopeRestartPolicy.restart(true, { it == wechat }, { true },
            { stopped += it; true }, { true })
        assertEquals(listOf(wechat), stopped)
        assertEquals(ScopeRestartOutcome.UNAVAILABLE, report.items.last().outcome)
    }
    @Test fun failedStopSkipsStartAndDoesNotBlockOtherTarget() = runBlocking {
        val started = mutableListOf<String>()
        val report = ScopeRestartPolicy.restart(true, { true }, { true }, { it != wechat },
            { started += it; true })
        assertEquals(listOf(alipay), started)
        assertEquals(listOf(ScopeRestartOutcome.STOP_FAILED, ScopeRestartOutcome.RESTARTED), report.items.map { it.outcome })
    }
    @Test fun failedStartIsReportedPerTarget() = runBlocking {
        val report = ScopeRestartPolicy.restart(true, { true }, { true }, { true }, { it != alipay })
        assertEquals(listOf(ScopeRestartOutcome.RESTARTED, ScopeRestartOutcome.START_FAILED), report.items.map { it.outcome })
    }
    @Test fun operationsAreOrderedAndLimitedToTwoTargets() = runBlocking {
        val operations = mutableListOf<String>()
        ScopeRestartPolicy.restart(true, { true }, { operations += "root"; true },
            { operations += "stop:$it"; true }, { operations += "start:$it"; true })
        assertEquals(listOf("root", "stop:$wechat", "start:$wechat", "stop:$alipay", "start:$alipay"), operations)
    }
    @Test fun shellCommandsKeepExplicitUserAndAllowedComponent() {
        assertEquals("am force-stop --user 10 'com.tencent.mm'", ScopeRestartCommands.stop(wechat, 10))
        assertTrue(ScopeRestartCommands.start(wechat, "$wechat/.ui.LauncherUI", 10)!!.contains("--user 10"))
        assertNotNull(ScopeRestartCommands.start(alipay, "$alipay/$alipay.AlipayLogin", 0))
    }
    @Test fun unrelatedPackageAndCrossPackageComponentAreRejected() {
        assertNull(ScopeRestartCommands.stop("android", 0))
        assertNull(ScopeRestartCommands.stop("io.github.mymoney", 0))
        assertNull(ScopeRestartCommands.start(wechat, "$alipay/.Launch", 0))
        assertNotNull(ScopeRestartCommands.start(wechat, "$wechat/com.other.Activity", 0))
        assertNull(ScopeRestartCommands.stop(wechat, -1))
    }
    @Test fun shellInjectionAndActivityTimeoutAreNeverAccepted() {
        assertNull(ScopeRestartCommands.stop("$wechat; reboot", 0))
        assertNull(ScopeRestartCommands.start(wechat, "$wechat/.Main'; reboot #", 0))
        assertNull(ScopeRestartCommands.start(wechat, "$wechat/.Main\nreboot", 0))
        assertFalse(ManagerLaunchPolicy.acceptedShellStart(0, "Starting: Intent {}\nStatus: timeout"))
    }
}
