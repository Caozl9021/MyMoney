package io.github.mymoney.capture

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.CaptureModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 2：凭证通道与采集模式解绑。
 *
 * 这组用例守的是「模式不参与凭证通道的任何判定」这一条。之前 `hookActive` 与凭证入口门禁
 * 都带着 `captureMode == ROOT`，后果是选了无障碍的用户永远开不了最准的那条通道，
 * 已经 root 的人又没法同时用页面辅助。任何一处把模式重新写回判定里，这里的断言都会挂。
 */
class HookIngestGateTest {

    @Test
    fun `两模式乘两种开关，凭证通道只看开关`() {
        for (mode in listOf(CaptureMode.ROOT, CaptureMode.ACCESSIBILITY, null)) {
            for (enabled in listOf(true, false)) {
                for (hookEnabled in listOf(true, false)) {
                    val state = CaptureModeState(mode, enabled, hookEnabled = hookEnabled)
                    assertEquals("mode=$mode enabled=$enabled hook=$hookEnabled",
                        enabled && hookEnabled, state.hookActive)
                }
            }
        }
    }

    @Test
    fun `凭证入口四个条件缺一不可`() {
        assertTrue(hookIngestAllowed(enabled = true, hookEnabled = true, whitelisted = true, hooked = true))
        assertFalse("总开关关着", hookIngestAllowed(enabled = false, hookEnabled = true, whitelisted = true, hooked = true))
        assertFalse("通道开关关着", hookIngestAllowed(enabled = true, hookEnabled = false, whitelisted = true, hooked = true))
        assertFalse("应用不在白名单", hookIngestAllowed(enabled = true, hookEnabled = true, whitelisted = false, hooked = true))
        assertFalse("不是被 hook 的包", hookIngestAllowed(enabled = true, hookEnabled = true, whitelisted = true, hooked = false))
    }
}
