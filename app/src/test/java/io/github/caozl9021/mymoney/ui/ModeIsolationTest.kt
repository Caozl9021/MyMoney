package io.github.caozl9021.mymoney.ui

import io.github.caozl9021.mymoney.data.CaptureMode
import org.junit.Assert.*
import org.junit.Test

/**
 * 模式隔离的守门测试。
 *
 * 用户要求（2026-10-27）：选无障碍就看不到任何 root 字样，选 root 就看不到无障碍那套。
 * 这类回归最容易从"某处文案顺手提一句另一种模式"溜回来，所以这里直接对文案做词表断言，
 * 而不是逐条比对字符串——文案可以改，越界不可以。
 */
class ModeIsolationTest {

    private val forbiddenForAccessibility = listOf("root", "Root", "ROOT", "Root 模式")
    private val forbiddenForRoot = listOf("无障碍", "无障碍服务", "通知使用权", "辅助服务")

    private val accessibilityInput = CaptureHealthInput(
        CaptureMode.ACCESSIBILITY, true, true, true, true, true)
    private val rootInput = CaptureHealthInput(
        CaptureMode.ROOT, true, false, false, true, true, notificationsEnabled = true, rootNotificationReady = true)

    private fun healthText(mode: CaptureMode): String {
        val input = if (mode == CaptureMode.ROOT) rootInput else accessibilityInput
        val h = captureHealth(input)
        return h.title + " " + h.detail
    }

    @Test fun accessibilityHealthNeverMentionsRoot() {
        val text = healthText(CaptureMode.ACCESSIBILITY)
        forbiddenForAccessibility.forEach {
            assertFalse("无障碍健康卡不该出现「$it」：$text", text.contains(it))
        }
    }

    @Test fun rootHealthUsesReadWordingAndNoAccessibilityNoise() {
        val text = healthText(CaptureMode.ROOT)
        // root 模式读的是通知栏，不是页面，也不涉及辅助服务。
        assertTrue("root 健康卡应说明在读通知：$text", text.contains("通知"))
        assertFalse("root 健康卡不该出现「页面」：$text", text.contains("页面"))
        forbiddenForRoot.forEach {
            assertFalse("root 健康卡不该出现「$it」：$text", text.contains(it))
        }
    }

    @Test fun accessibilityChannelsIncludePage() {
        assertEquals(
            listOf(CaptureChannel.NOTIFICATION, CaptureChannel.PAGE),
            channelsFor(CaptureMode.ACCESSIBILITY))
    }

    @Test fun legacySmsIsNeverAVisibleChannelInSelf() {
        listOf(CaptureMode.ACCESSIBILITY, CaptureMode.ROOT, null).forEach { mode ->
            assertFalse(channelsFor(mode).contains(CaptureChannel.SMS))
        }
    }

    @Test fun hookChannelIsNeverPartOfEitherMode() {
        // LSPosed 采集的前提是在 LSPosed 里启用模块并勾选微信/支付宝，
        // 那是与 Root、无障碍并列的第三条特权路径，不是任何一种模式的下级选项。
        // 早前它挂在「识别通道」里，选了无障碍的人会问"我选的是无障碍，怎么还要弄 LSP"。
        listOf(CaptureMode.ACCESSIBILITY, CaptureMode.ROOT, null).forEach { mode ->
            assertFalse("$mode 不该在模式内列出 LSPosed：${channelsFor(mode)}",
                channelsFor(mode).contains(CaptureChannel.HOOK))
        }
    }

    @Test fun rootChannelsNeverIncludePage() {
        assertFalse(channelsFor(CaptureMode.ROOT).contains(CaptureChannel.PAGE))
        assertFalse(channelsFor(null).contains(CaptureChannel.PAGE))
    }

    @Test fun accessibilityFirstRunStepDoesNotMentionRoot() {
        val steps = FirstRunGuide.steps(accessibilityInput, true)
        steps.forEach { step ->
            forbiddenForAccessibility.forEach {
                assertFalse("无障碍首启步骤出现「$it」：${step.label}", step.label.contains(it))
            }
        }
    }

    @Test fun rootFirstRunStepDoesNotMentionAccessibility() {
        val steps = FirstRunGuide.steps(rootInput, true)
        steps.forEach { step ->
            forbiddenForRoot.forEach {
                assertFalse("root 首启步骤出现「$it」：${step.label}", step.label.contains(it))
            }
        }
    }

    @Test fun pageVerdictIsAbsentOutsideAccessibilityMode() {
        // root / 未选模式下连"页面"这个通道对象都不该存在，UI 才不会画出置灰的假入口。
        val rootState = appChannelStateFor(rootInput, "com.tencent.mm")
        assertNull(rootState.page)
        assertEquals(1, rootState.channelCount)
        val unset = appChannelStateFor(rootInput.copy(mode = null), "com.tencent.mm")
        assertNull(unset.page)
    }

    private fun appChannelStateFor(input: CaptureHealthInput, pkg: String): AppChannelState =
        if (input.mode == CaptureMode.ACCESSIBILITY) {
            AppChannelState(
                packageName = pkg, whitelisted = true, hasTemplate = true,
                notification = ChannelVerdict(true, ChannelReason.ACTIVE, "读取通知栏"),
                page = ChannelVerdict(true, ChannelReason.ACTIVE, "读取页面文字"),
            )
        } else {
            AppChannelState(
                packageName = pkg, whitelisted = true, hasTemplate = true,
                notification = ChannelVerdict(true, ChannelReason.ACTIVE, "读取通知栏"),
                page = null,
            )
        }
}
