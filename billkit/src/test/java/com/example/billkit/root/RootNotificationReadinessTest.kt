package com.example.billkit.root

import org.junit.Assert.*
import org.junit.Test

class RootNotificationReadinessTest {
    @Test fun validEmptyListCountsAsReadable() {
        val result = RootNotificationSource.fromShellResult(RootShell.Result(0, "Notification List:\n  Group summaries:"))
        assertTrue(result.successful)
        assertTrue(result.notifications.isEmpty())
        assertNull(result.error)
    }

    @Test fun permissionFailureIsNotAHealthyEmptyList() {
        val result = RootNotificationSource.fromShellResult(RootShell.Result(1, "su: Permission denied"))
        assertFalse(result.successful)
        assertTrue(result.error!!.contains("未授权"))
        assertFalse(result.error!!.contains("su:"))
    }

    @Test fun commandFailureAndUnknownFormatAreNotReadiness() {
        assertFalse(RootNotificationSource.fromShellResult(RootShell.Result(-1, "")).successful)
        assertFalse(RootNotificationSource.fromShellResult(RootShell.Result(0, "unexpected vendor output")).successful)
        assertFalse(RootNotificationSource.fromShellResult(RootShell.Result(1, "Notification List:")).successful)
    }
}
