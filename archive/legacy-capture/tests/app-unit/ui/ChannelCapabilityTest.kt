package io.github.mymoney.ui

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.NotificationPath
import org.junit.Assert.*
import org.junit.Test

/**
 * 通道可见性的守门测试。
 *
 * 原来的要求是「按采集模式隔离」：选无障碍就看不到 root 字样，选 root 就看不到无障碍那套。
 * 阶段 4 把那个互斥拆了，要求换成**按设备能力裁剪 + 按实际实现说话**：
 *
 * - 普通设备（没有 root）不该在任何文案里看到 root / dumpsys；
 * - 已 root 的用户可以同时用通知、凭证、页面三条通道，文案不该再假装哪一条不存在；
 * - 页面通道是否出现只取决于 `pageEnabled`，与当初选的那套预设无关。
 *
 * 这类回归最容易从"某处文案顺手提一句另一种模式"溜回来，所以直接对文案做词表断言，
 * 而不是逐条比对字符串——文案可以改，越界不可以。
 */
class ChannelCapabilityTest {

    private val rootWords = listOf("root", "Root", "ROOT", "dumpsys")

    /** 最常见的装机：没 root，通知走 listener，页面辅助开着。 */
    private val listenerInput = CaptureHealthInput(
        CaptureMode.ACCESSIBILITY, true, true, true, true, true,
        notificationsEnabled = true, notificationPlan = NotificationPath.LISTENER)

    /** 已 root 且监听不可用，走 dumpsys 兜底；页面辅助按预设关闭。 */
    private val dumpsysInput = CaptureHealthInput(
        CaptureMode.ROOT, true, false, false, true, true, notificationsEnabled = true,
        pageEnabled = false, notificationPlan = NotificationPath.DUMPSYS, rootNotificationReady = true)

    private fun healthText(input: CaptureHealthInput): String {
        val h = captureHealth(input)
        return h.title + " " + h.detail
    }

    @Test fun ordinaryDeviceHealthNeverMentionsRoot() {
        val text = healthText(listenerInput)
        rootWords.forEach {
            assertFalse("普通设备的健康卡不该出现「$it」：$text", text.contains(it))
        }
    }

    @Test fun dumpsysHealthSaysItReadsNotifications() {
        val text = healthText(dumpsysInput)
        assertTrue("dumpsys 健康卡应说明在读通知：$text", text.contains("通知"))
        // 页面辅助是按预设关掉的，关掉的东西不该出现在「可用」清单里。
        assertFalse("页面辅助已关闭，不该被算作可用来源：$text", text.contains("页面"))
    }

    @Test fun rootUserWithPageEnabledGetsBothSources() {
        // 拆掉互斥的直接后果：已 root 也能用页面辅助，健康卡必须如实说两条都在跑。
        val text = healthText(dumpsysInput.copy(pageEnabled = true, accessibilityAuthorized = true,
            accessibilityBound = true))
        assertTrue("应列出 Root 通知识别：$text", text.contains("通知"))
        assertTrue("页面辅助开着就该被算作可用来源：$text", text.contains("页面"))
    }

    @Test fun channelListNeverIncludesLegacySms() {
        listOf(false, true).forEach { showsHook ->
            assertFalse(channelList(showsHook).contains(CaptureChannel.SMS))
        }
    }

    @Test fun hookRowAppearsOnlyWithTheCapability() {
        assertFalse(channelList(showsHook = false).contains(CaptureChannel.HOOK))
        assertTrue(channelList(showsHook = true).contains(CaptureChannel.HOOK))
    }

    @Test fun firstRunStepsStayFreeOfBothModesJargon() {
        listOf(listenerInput, dumpsysInput).forEach { input ->
            FirstRunGuide.steps(input, true).forEach { step ->
                rootWords.forEach {
                    assertFalse("首启步骤不该出现「$it」：${step.label}", step.label.contains(it))
                }
            }
        }
    }

    @Test fun pageChannelIsJudgedByItsOwnSwitchNotThePreset() {
        // 两条通道在「预设」上分属不同模式，但页面结论只该看 pageEnabled 与授权。
        val verdict = appChannelState(listenerInput, "com.tencent.mm", setOf("com.tencent.mm")).page
        assertNotNull("页面通道始终存在，不该因为预设不同而消失", verdict)
        assertTrue(verdict!!.active)
        val off = appChannelState(listenerInput.copy(pageEnabled = false), "com.tencent.mm", setOf("com.tencent.mm"))
        assertEquals(ChannelReason.PAGE_DISABLED, off.page!!.reason)
    }

    @Test fun notificationVerdictFollowsTheImplementationInUse() {
        // 走 dumpsys 时不该拿「通知使用权未授予」去解释；走 listener 时才该。
        val root = appChannelState(dumpsysInput.copy(notificationAuthorized = false),
            "com.tencent.mm", setOf("com.tencent.mm")).notification
        assertTrue("走得通就不该报缺授权：${root.detail}", root.active)
        val listener = appChannelState(listenerInput.copy(notificationAuthorized = false),
            "com.tencent.mm", setOf("com.tencent.mm")).notification
        assertEquals(ChannelReason.NOTIFICATION_UNAUTHORIZED, listener.reason)
    }
}
