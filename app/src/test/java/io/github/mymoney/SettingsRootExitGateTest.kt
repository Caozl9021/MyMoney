package io.github.mymoney

import io.github.mymoney.ui.SettingsRootExitGate
import org.junit.Assert.*
import org.junit.Test

class SettingsRootExitGateTest {
    @Test fun unguardedRequestCompletesImmediately() {
        val gate = SettingsRootExitGate()
        var roots = 0
        gate.request { roots++ }
        assertEquals(1, roots)
    }

    @Test fun dirtyPageMayDeclineWithoutAffectingTheNextRequest() {
        val gate = SettingsRootExitGate()
        val key = Any()
        var roots = 0
        gate.register(key) { /* User continues editing; no approval. */ }
        gate.request { roots++ }
        assertEquals(0, roots)
        gate.unregister(key)
        gate.request { roots++ }
        assertEquals(1, roots)
    }

    @Test fun approvalCanCompleteOnlyOnce() {
        val gate = SettingsRootExitGate()
        var approve: (() -> Unit)? = null
        var roots = 0
        gate.register(Any()) { approve = it }
        gate.request { roots++ }
        assertEquals(0, roots)
        approve!!(); approve!!()
        assertEquals(1, roots)
    }

    @Test fun navigationInvalidatesAnOldDialogApproval() {
        val gate = SettingsRootExitGate()
        var approve: (() -> Unit)? = null
        var roots = 0
        gate.register(Any()) { approve = it }
        gate.request { roots++ }
        gate.invalidate() // A fresh deep link or navigation replaced the page.
        approve!!()
        assertEquals(0, roots)
    }

    @Test fun rapidReselectOnlyHonorsTheLatestApproval() {
        val gate = SettingsRootExitGate()
        val approvals = mutableListOf<() -> Unit>()
        var roots = 0
        gate.register(Any()) { approvals += it }
        gate.request { roots++ }; gate.request { roots++ }
        approvals[0]()
        assertEquals(0, roots)
        approvals[1]()
        assertEquals(1, roots)
    }

    @Test fun innermostBusyGuardBlocksAndUnregistersCleanly() {
        val gate = SettingsRootExitGate()
        val parent = Any(); val child = Any()
        var parentRequests = 0
        var roots = 0
        gate.register(parent) { parentRequests++; it() }
        gate.register(child) { /* Same policy as a busy back handler. */ }
        gate.request { roots++ }
        assertEquals(0, roots); assertEquals(0, parentRequests)
        gate.unregister(child)
        gate.request { roots++ }
        assertEquals(1, roots); assertEquals(1, parentRequests)
    }
}
