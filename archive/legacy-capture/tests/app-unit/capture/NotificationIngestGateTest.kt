package io.github.mymoney.capture

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.CaptureModeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知观察的入口闸门：只看开关、范围、配置令牌与授权。
 *
 * 这里的核心断言是「三种预设给出的结论完全一样」——接入通知的判定与用户当初选了哪张表无关。
 * 曾经 `NotificationIngress.allowed` 里有一条 `captureMode != ROOT`，与阶段 3 的按可用性选实现
 * 直接冲突：选了 ROOT 预设、通知使用权也在、实际正由 listener 读的机器，观察全被丢掉。
 */
class NotificationIngestGateTest {
    private val wechat = "com.tencent.mm"
    private val scope = setOf(wechat, "com.eg.android.AlipayGphone")

    private fun state(mode: CaptureMode?, enabled: Boolean = true, notifications: Boolean = true,
        page: Boolean = true, hook: Boolean = false) =
        CaptureModeState(mode, enabled, page, notifications, smsEnabled = false, hookEnabled = hook)

    private fun allows(state: CaptureModeState, pkg: String = wechat, whitelist: Set<String> = scope,
        token: Boolean = true, authorized: Boolean = true) =
        notificationIngestAllowed(state, pkg, whitelist, token, authorized)

    @Test fun everyPresetGivesTheSameAnswer() {
        for (mode in listOf(null, CaptureMode.ROOT, CaptureMode.ACCESSIBILITY)) {
            assertTrue("mode=$mode", allows(state(mode)))
            assertFalse("mode=$mode 关了总开关", allows(state(mode, enabled = false)))
            assertFalse("mode=$mode 关了通知开关", allows(state(mode, notifications = false)))
            assertFalse("mode=$mode 不在范围", allows(state(mode), pkg = "com.jingdong.app.mall"))
            assertFalse("mode=$mode 令牌过期", allows(state(mode), token = false))
            assertFalse("mode=$mode 没有通知使用权", allows(state(mode), authorized = false))
        }
    }

    @Test fun pageAndCredentialSwitchesNeverDecideNotificationIngest() {
        // 三条通道各管各的：开页面或凭证通道，不会让通知观察多进来一条，也不会挡住它。
        assertTrue(allows(state(CaptureMode.ROOT, page = false)))
        assertTrue(allows(state(CaptureMode.ROOT, page = true, hook = true)))
        assertTrue(allows(state(null, page = false, hook = true)))
        // 反过来，通知开关关掉就是关掉，页面开着也不补。
        assertFalse(allows(state(CaptureMode.ACCESSIBILITY, notifications = false)))
    }

    @Test fun gateTrustsTheScopeItIsGiven() {
        // 范围过滤在 Settings.whitelist（CaptureScope）那层做，闸门只按传进来的集合判——
        // 所以这里的输入必须是过滤后的值，不能是旧配置原值。
        val jd = "com.jingdong.app.mall"
        assertFalse(allows(state(null), pkg = jd, whitelist = scope))
        assertTrue(allows(state(null), pkg = jd, whitelist = scope + jd))
    }
}
