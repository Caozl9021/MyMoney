package io.github.caozl9021.mymoney.ui

import io.github.caozl9021.mymoney.data.CaptureMode
import org.junit.Assert.*
import org.junit.Test

/** Regression cases for mode visibility and raw permission/binding propagation. */
class AutomationPageTest {
    private val normal = CaptureHealthInput(CaptureMode.ACCESSIBILITY, true, true, true, true, true,
        notificationsEnabled = true)

    @Test fun lspConfigurationFollowsRootChoiceEvenWhenSuProbeFails() {
        assertFalse(showsLspSettings(CaptureMode.ACCESSIBILITY))
        assertFalse(showsLspSettings(null))
        assertTrue(showsLspSettings(CaptureMode.ROOT))
        val snapshot = captureSettingsSnapshot(normal.copy(mode = CaptureMode.ROOT), true, false)
        assertFalse(snapshot.rootGranted)
        assertTrue(showsLspSettings(CaptureMode.ROOT))
    }

    @Test fun notificationAuthorizationAndBindingAreNeverInferredFromHealthKind() {
        val ungranted = captureSettingsSnapshot(normal, true, null)
        assertFalse(ungranted.notificationAuthorized)
        assertFalse(ungranted.notificationBound)
        assertTrue(notificationRowStatus(CaptureMode.ACCESSIBILITY, ungranted).contains("使用权"))
        val unbound = captureSettingsSnapshot(normal.copy(notificationAuthorized = true), true, null)
        assertTrue(unbound.notificationAuthorized)
        assertFalse(unbound.notificationBound)
        assertTrue(notificationRowStatus(CaptureMode.ACCESSIBILITY, unbound).contains("未连接"))
        val connected = captureSettingsSnapshot(normal.copy(notificationAuthorized = true, notificationBound = true), true, null)
        assertTrue(notificationRowStatus(CaptureMode.ACCESSIBILITY, connected).contains("已就绪"))
    }

    @Test fun pausedRootDoesNotPretendToBeRunning() {
        val snapshot = captureSettingsSnapshot(normal.copy(mode = CaptureMode.ROOT, enabled = false,
            rootNotificationReady = true), true, true)
        assertTrue(notificationRowStatus(CaptureMode.ROOT, snapshot).contains("已暂停"))
        assertFalse(notificationRowStatus(CaptureMode.ROOT, snapshot).contains("已就绪"))
    }

    @Test fun unknownRootReadDoesNotPretendToBeRunning() {
        val snapshot = captureSettingsSnapshot(normal.copy(mode = CaptureMode.ROOT), true, null)
        assertFalse(snapshot.rootGranted)
        assertFalse(notificationRowStatus(CaptureMode.ROOT, snapshot).contains("已就绪"))
    }

    // ---- 能力门禁 ----

    @Test fun rootAvailableMeansReady() {
        assertEquals(ModeGate.READY, modeGate(hasRoot = true, mode = null))
        assertEquals(ModeGate.READY, modeGate(hasRoot = true, mode = CaptureMode.ACCESSIBILITY))
    }

    @Test fun noRootIsBlockedWithRootAction() {
        val gate = modeGate(hasRoot = false, mode = CaptureMode.ACCESSIBILITY)
        assertEquals(ModeGate.NO_ROOT, gate)
        val denial = gate.denial()!!
        assertTrue("应说清缺的是 Root：${denial.detail}", denial.detail.contains("Root"))
        assertEquals(CaptureAction.ROOT, denial.action)
    }

    @Test fun lsposedIsNotARootSubstitute() {
        // LSPosed 是装在 Magisk/KernelSU 之上的 Xposed 框架，没有 Root 装不上也跑不起来。
        // 更关键的是：宿主进程看不见自己模块的启用状态（LSPosed 不会把自己塞进模块自己的
        // classloader），所以「微信/支付宝那边握手通了」根本不代表这台手机有 Root ——
        // MyMoney 自己要读通知栏，走的是 su / dumpsys。能力判定只看 su。
        assertEquals(ModeGate.NO_ROOT, modeGate(hasRoot = false, mode = null))
        assertFalse(
            "门禁不该有 LSPosed 分支：${ModeGate.entries}",
            ModeGate.entries.any { it.name.contains("LSP") },
        )
    }

    @Test fun alreadyInRootModeIsNeverBlocked() {
        // 真机上 ROM 更新后 su 偶尔短暂读不到；此时拦下来会把正在正常工作的人挡在外面。
        assertEquals(ModeGate.READY, modeGate(hasRoot = false, mode = CaptureMode.ROOT))
    }

    @Test fun readyGateHasNoDenial() {
        assertNull(ModeGate.READY.denial())
    }

    @Test fun everyDenialOffersAnActionableNextStep() {
        // 被拦下却不给去处，等于把门关上又不给钥匙。
        ModeGate.entries.filter { it != ModeGate.READY }.forEach { gate ->
            val denial = gate.denial()
            assertNotNull("$gate 缺说明", denial)
            assertNotNull("$gate 缺少可点的授权动作", denial!!.action)
            assertTrue("$gate 的说明太短：${denial.detail}", denial.detail.length > 8)
        }
    }

    @Test fun denialTextNeverMentionsTheOtherModesInternals() {
        // 说明文案里不该冒出实现细节名词，用户只需要知道"缺什么"和"去哪给"。
        ModeGate.entries.filter { it != ModeGate.READY }.forEach { gate ->
            val detail = gate.denial()!!.detail
            listOf("dumpsys", "WCDB", "审计日志", "classloader").forEach {
                assertFalse("$gate 的说明混入实现细节「$it」：$detail", detail.contains(it))
            }
        }
    }
}
