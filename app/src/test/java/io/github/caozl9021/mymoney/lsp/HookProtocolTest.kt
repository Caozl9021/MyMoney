package io.github.caozl9021.mymoney.lsp

import org.junit.Assert.*
import org.junit.Test

class HookProtocolTest {
    @Test fun bothTargetMainProcessesRemainEligible() {
        assertTrue(HookProtocol.shouldCapture("com.tencent.mm", "com.tencent.mm"))
        assertTrue(HookProtocol.shouldCapture("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone"))
    }

    @Test fun targetSubprocessesCannotInstallDuplicateCollectors() {
        assertFalse(HookProtocol.shouldCapture("com.tencent.mm", "com.tencent.mm:push"))
        assertFalse(HookProtocol.shouldCapture("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone:push"))
        assertFalse(HookProtocol.shouldCapture("com.tencent.mm", "com.eg.android.AlipayGphone"))
    }

    @Test fun systemFrameworkAndUnrelatedPackagesNeverInstallHooks() {
        listOf("android", "system_server", "io.github.caozl9021.mymoney", "com.tencent.mm.other", "").forEach {
            assertFalse("unexpected scope: $it", HookProtocol.shouldCapture(it, it))
        }
        assertFalse(HookProtocol.shouldCapture(null, null))
        assertFalse(HookProtocol.shouldCapture("com.tencent.mm", null))
    }

    @Test fun existingTargetWireIdentifiersStayCompatible() {
        assertEquals("io.github.caozl9021.mymoney.lsp.status", HookProtocol.AUTHORITY)
        assertEquals("io.github.caozl9021.mymoney.permission.LSP_PROBE", HookProtocol.PERMISSION)
        assertEquals("hook", HookProtocol.METHOD)
        assertEquals("hook_status", HookProtocol.STATUS_METHOD)
        assertEquals("io.github.caozl9021.mymoney.lsp.TARGET_PROBE", HookProtocol.PROBE_ACTION)
    }
}
