package io.github.mymoney.ui

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.NotificationPath
import org.junit.Assert.*
import org.junit.Test

/** Regression cases for capability裁剪 and raw permission/binding propagation. */
class AutomationPageTest {
    private val normal = CaptureHealthInput(CaptureMode.ACCESSIBILITY, true, true, true, true, true,
        notificationsEnabled = true, notificationPlan = NotificationPath.LISTENER)

    @Test fun lspEntryHiddenUnderOrdinaryPresetButNeverWhileChannelOn() {
        // 「普通记账」预设下不画凭证入口：那个预设只讲通知 + 页面辅助。
        assertFalse(showsLspSettings(hookEnabled = false, lspInstalled = true, preset = CaptureMode.ACCESSIBILITY))
        // Root 增强与「还没选过预设」按能力显示。
        assertTrue(showsLspSettings(hookEnabled = false, lspInstalled = true, preset = CaptureMode.ROOT))
        assertTrue(showsLspSettings(hookEnabled = false, lspInstalled = true, preset = null))
        // 通道已经开着：任何预设下都必须看得到，否则用户没地方关掉它或重新检测。
        assertTrue(showsLspSettings(hookEnabled = true, lspInstalled = false, preset = CaptureMode.ACCESSIBILITY))
        assertTrue(showsLspSettings(hookEnabled = true, lspInstalled = true, preset = CaptureMode.ACCESSIBILITY))
        // 没装 LSPosed、通道也没开：不渲染一个点了没反应的入口。
        assertFalse(showsLspSettings(hookEnabled = false, lspInstalled = false, preset = CaptureMode.ROOT))
    }

    @Test fun notificationAuthorizationAndBindingAreNeverInferredFromHealthKind() {
        val ungranted = captureSettingsSnapshot(normal, true, null)
        assertFalse(ungranted.notificationAuthorized)
        assertFalse(ungranted.notificationBound)
        assertTrue(notificationRowStatus(ungranted).contains("使用权"))
        val unbound = captureSettingsSnapshot(normal.copy(notificationAuthorized = true), true, null)
        assertTrue(unbound.notificationAuthorized)
        assertFalse(unbound.notificationBound)
        assertTrue(notificationRowStatus(unbound).contains("未连接"))
        val connected = captureSettingsSnapshot(normal.copy(notificationAuthorized = true, notificationBound = true), true, null)
        assertTrue(notificationRowStatus(connected).contains("已就绪"))
    }

    @Test fun pausedRootDoesNotPretendToBeRunning() {
        val snapshot = captureSettingsSnapshot(normal.copy(mode = CaptureMode.ROOT, enabled = false,
            notificationPlan = NotificationPath.DUMPSYS, rootNotificationReady = true), true, true)
        assertTrue(notificationRowStatus(snapshot).contains("已暂停"))
        assertFalse(notificationRowStatus(snapshot).contains("已就绪"))
    }

    @Test fun unknownRootReadDoesNotPretendToBeRunning() {
        val snapshot = captureSettingsSnapshot(normal.copy(mode = CaptureMode.ROOT,
            notificationPlan = NotificationPath.DUMPSYS), true, null)
        assertFalse(snapshot.rootGranted)
        assertFalse(notificationRowStatus(snapshot).contains("已就绪"))
    }

    // ---- 通道列表按设备能力裁剪 ----

    @Test fun onlyCapabilityDecidesWhetherTheHookRowExists() {
        assertEquals(listOf(CaptureChannel.NOTIFICATION, CaptureChannel.PAGE), channelList(showsHook = false))
        assertEquals(listOf(CaptureChannel.NOTIFICATION, CaptureChannel.PAGE, CaptureChannel.HOOK),
            channelList(showsHook = true))
        // 短信通道在 self 分支已移除，任何能力下都不该冒出来。
        assertFalse(channelList(showsHook = true).contains(CaptureChannel.SMS))
    }

    // ---- 能力提示 ----

    @Test fun plainDeviceHasNothingToAnnounce() {
        assertNull(capabilityNotice(DeviceCapabilities(), hookEnabled = false,
            notificationFallbackUseful = true, dismissed = emptySet(), preset = null))
    }

    @Test fun lsposedNoticeOnlyWhileTheHookChannelIsOff() {
        val caps = DeviceCapabilities(lspInstalled = true)
        assertEquals(CapabilityNotice.LSP, capabilityNotice(caps, hookEnabled = false,
            notificationFallbackUseful = false, dismissed = emptySet(), preset = null))
        // 已经开着凭证采集的人不需要被告知「你有 LSPosed」。
        assertNull(capabilityNotice(caps, hookEnabled = true,
            notificationFallbackUseful = false, dismissed = emptySet(), preset = null))
        // 普通记账预设下连入口都不画，提示也就不该出现。
        assertNull(capabilityNotice(caps, hookEnabled = false, notificationFallbackUseful = false,
            dismissed = emptySet(), preset = CaptureMode.ACCESSIBILITY))
    }

    @Test fun rootNoticeOnlyWhenItWouldActuallyAddAFallback() {
        val caps = DeviceCapabilities(rootGranted = true)
        // 通知使用权没给：Root 读通知正好是兜底。
        assertEquals(CapabilityNotice.ROOT, capabilityNotice(caps, hookEnabled = false,
            notificationFallbackUseful = true, dismissed = emptySet(), preset = null))
        // 通知监听正常工作：root 没有增量，说了只会让人以为自己少配了什么。
        assertNull(capabilityNotice(caps, hookEnabled = false,
            notificationFallbackUseful = false, dismissed = emptySet(), preset = null))
    }

    @Test fun dismissedNoticesNeverComeBack() {
        val caps = DeviceCapabilities(rootGranted = true, lspInstalled = true)
        // 关掉 LSPosed 那条之后，下一次轮到 Root 那条——两条各自只提一次。
        assertEquals(CapabilityNotice.ROOT, capabilityNotice(caps, hookEnabled = true,
            notificationFallbackUseful = true, dismissed = setOf(NOTICE_LSP), preset = null))
        assertNull(capabilityNotice(caps, hookEnabled = true,
            notificationFallbackUseful = true, dismissed = setOf(NOTICE_LSP, NOTICE_ROOT), preset = null))
    }

    @Test fun noticeTextsStayActionableWithoutImplementationJargon() {
        CapabilityNotice.entries.forEach { notice ->
            assertTrue("${notice.name} 的说明太短", notice.detail().length > 8)
            listOf("dumpsys", "WCDB", "classloader", "provider", "Hook").forEach {
                assertFalse("${notice.name} 的说明混入实现细节「$it」：${notice.detail()}",
                    notice.detail().contains(it))
            }
        }
    }
}
