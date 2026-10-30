package io.github.caozl9021.mymoney.ui

import io.github.caozl9021.mymoney.data.CaptureMode
import org.junit.Assert.*
import org.junit.Test

class AppChannelStateTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val meituan = "com.sankuai.meituan"

    /** 全部通道就绪：无障碍已授权且已绑定，通知已授权且已绑定。 */
    private val ready = CaptureHealthInput(
        mode = CaptureMode.ACCESSIBILITY, enabled = true,
        accessibilityAuthorized = true, accessibilityBound = true,
        overlayAllowed = true, autoConfirm = true,
        pageEnabled = true, notificationsEnabled = true,
        notificationAuthorized = true, notificationBound = true,
    )

    private fun state(input: CaptureHealthInput, pkg: String, whitelist: Set<String> = setOf(wechat, alipay, meituan)) =
        appChannelState(input, pkg, whitelist)

    // ---------------------------------------------------------------- 12 种原因逐一进入

    @Test fun activeWhenBothChannelsReady() {
        val s = state(ready, alipay)
        assertEquals(ChannelReason.ACTIVE, s.notification.reason)
        assertEquals(ChannelReason.ACTIVE, s.page?.reason)
        assertTrue(s.notification.active)
        assertTrue(s.page?.active == true)
        assertTrue(s.hasTemplate)
        assertFalse(s.needsAttention)
    }

    @Test fun notWhitelistedIsNotADefect() {
        val s = state(ready, "com.example.other", whitelist = setOf(alipay))
        assertEquals(ChannelReason.NOT_WHITELISTED, s.notification.reason)
        assertEquals(ChannelReason.NOT_WHITELISTED, s.page?.reason)
        assertFalse(s.whitelisted)
        // 用户自己没选它，不该进"有问题"。
        assertFalse(s.needsAttention)
        assertFalse(s.anyActive)
    }

    @Test fun pausedWinsOverEveryPermissionSignal() {
        val s = state(ready.copy(enabled = false, notificationBound = false, accessibilityBound = false,
            notificationAuthorized = false, accessibilityAuthorized = false), alipay)
        assertEquals(ChannelReason.GLOBAL_PAUSED, s.notification.reason)
        assertEquals(ChannelReason.GLOBAL_PAUSED, s.page?.reason)
    }

    @Test fun notificationDisabledDiffersFromUnauthorized() {
        val s = state(ready.copy(notificationsEnabled = false), alipay)
        assertEquals(ChannelReason.NOTIFICATION_DISABLED, s.notification.reason)
        assertFalse(s.notification.active)
        // 页面通道不受影响
        assertEquals(ChannelReason.ACTIVE, s.page?.reason)
    }

    @Test fun notificationUnauthorizedAndUnboundAreDistinct() {
        assertEquals(ChannelReason.NOTIFICATION_UNAUTHORIZED,
            state(ready.copy(notificationAuthorized = false), alipay).notification.reason)
        assertEquals(ChannelReason.NOTIFICATION_UNBOUND,
            state(ready.copy(notificationBound = false), alipay).notification.reason)
        // 未授权时不再报"未连接"：先授予权限，重连才有意义。
        val unauthorized = state(ready.copy(notificationAuthorized = false, notificationBound = false), alipay)
        assertEquals(ChannelReason.NOTIFICATION_UNAUTHORIZED, unauthorized.notification.reason)
    }

    @Test fun appWithoutTemplateStillCountsOnRules() {
        // 163 条 JS 规则接管后，"没有白名单模板"不等于识别不到微信：
        // 通知通道照常可用，模板只是可选的一层。
        val s = state(ready, wechat)
        assertFalse(s.hasTemplate)
        assertEquals(ChannelReason.ACTIVE, s.notification.reason)
        assertTrue(s.notification.active)
        assertEquals(ChannelReason.ACTIVE, s.page?.reason)
        assertTrue(s.anyActive)
        assertFalse(s.needsAttention)
    }

    @Test fun notificationOnlyAppStillActive() {
        // 规则引擎覆盖所有应用，通知是唯一来源时微信同样可用。
        // 未选模式时没有页面通道（page 为 null），列表里也不该出现页面徽标。
        val notificationOnly = ready.copy(mode = null, pageEnabled = false)
        val s = state(notificationOnly, wechat)
        assertEquals(ChannelReason.ACTIVE, s.notification.reason)
        assertNull(s.page)
        assertEquals(1, s.channelCount)
        assertTrue(s.anyActive)
        assertFalse(s.needsAttention)
    }

    @Test fun pageDisabledUnauthorizedUnboundAreDistinct() {
        assertEquals(ChannelReason.PAGE_DISABLED,
            state(ready.copy(pageEnabled = false), alipay).page?.reason)
        assertEquals(ChannelReason.PAGE_UNAUTHORIZED,
            state(ready.copy(accessibilityAuthorized = false), alipay).page?.reason)
        assertEquals(ChannelReason.PAGE_UNBOUND,
            state(ready.copy(accessibilityBound = false), alipay).page?.reason)
    }

    @Test fun rootModeHasNoPageChannelAtAll() {
        // root 模式没有页面采集：结论是 null 而不是"已停用"。
        // 界面据此不渲染页面行——显示一个永远灰着的通道，用户会以为功能坏了。
        assertNull(state(ready.copy(mode = CaptureMode.ROOT), alipay).page)
        assertNull(state(ready.copy(mode = null), alipay).page)
        assertEquals(1, state(ready.copy(mode = CaptureMode.ROOT), alipay).channelCount)
        assertEquals(2, state(ready, alipay).channelCount)
    }

    @Test fun rootModeNotificationIgnoresAccessibilityAndListenerSignals() {
        // root 模式下通知走 dumpsys：既不需要无障碍，也不需要通知使用权与 Listener 绑定。
        val s = state(ready.copy(mode = CaptureMode.ROOT,
            accessibilityAuthorized = false, accessibilityBound = false,
            notificationAuthorized = false, notificationBound = false, rootNotificationReady = true), alipay)
        assertEquals(ChannelReason.ACTIVE, s.notification.reason)
        // 页面通道在 root 下根本不存在。
        assertNull(s.page)
        assertTrue(s.anyActive)
    }

    @Test fun accessibilityUnauthorizedPrecedesUnbound() {
        val s = state(ready.copy(accessibilityAuthorized = false, accessibilityBound = false), alipay)
        assertEquals(ChannelReason.PAGE_UNAUTHORIZED, s.page?.reason)
    }

    @Test fun everyReasonHasReadableChineseText() {
        val inputs = listOf(
            ready, ready.copy(notificationsEnabled = false), ready.copy(notificationAuthorized = false),
            ready.copy(notificationBound = false), ready.copy(enabled = false),
            ready.copy(pageEnabled = false), ready.copy(mode = null),
            ready.copy(mode = CaptureMode.ROOT),
            ready.copy(mode = CaptureMode.ROOT, hookEnabled = true),
            ready.copy(accessibilityAuthorized = false), ready.copy(accessibilityBound = false),
            ready.copy(ruleCapabilities = emptySet()),
        )
        val reasons = inputs.flatMap { input ->
            listOf(wechat, alipay, "com.example.other").flatMap { pkg ->
                val s = state(input, pkg)
                // root 模式下 page 为 null（该模式没有页面通道），只收通知结论。
                (listOf(s.notification) + listOfNotNull(s.page, s.hook)).map { it.reason to it.detail }
            }
        }
        val covered = reasons.map { it.first }.toSet()
        // 没有白名单模板不再是不可用原因：163 条 JS 规则随时可用。
        // 除 ACTIVE（代表可用，不是问题）外，每种原因都必须在这些输入里出现过，
        // 否则下面的文案断言是空转。
        val expected = ChannelReason.entries.toSet() - ChannelReason.ACTIVE
        assertEquals(expected, covered - ChannelReason.ACTIVE)
        reasons.forEach { (reason, detail) ->
            assertTrue("原因 $reason 缺少说明文案", detail.isNotBlank())
            assertFalse("说明文案不应泄漏原始字段", detail.contains("%s"))
        }
    }

    // ---------------------------------------------------------------- 与 captureHealth 的一致性

    @Test fun neverContradictsGlobalHealth() {
        val inputs = listOf(
            ready, ready.copy(enabled = false), ready.copy(mode = null),
            ready.copy(notificationsEnabled = false, pageEnabled = false),
            ready.copy(autoConfirm = false),
        )
        inputs.forEach { input ->
            val health = captureHealth(input)
            val states = appChannelStates(input, listOf(wechat, alipay, meituan), setOf(wechat, alipay, meituan))
            states.forEach { s ->
                if (health.kind == CaptureHealthKind.MODE_REQUIRED && input.mode == null && !input.notificationsEnabled) {
                    assertFalse("全局要求选择来源时，${s.packageName} 不应显示任一通道可用", s.anyActive)
                }
                if (health.kind == CaptureHealthKind.PAUSED) {
                    assertEquals("全局已暂停时 ${s.packageName} 必须报同一原因",
                        ChannelReason.GLOBAL_PAUSED, s.notification.reason)
                    assertEquals(ChannelReason.GLOBAL_PAUSED, s.page?.reason)
                    assertFalse(s.anyActive)
                }
                if (health.kind == CaptureHealthKind.RUNNING || health.kind == CaptureHealthKind.NOTIFICATION_OBSERVING) {
                    // 页面通道只在无障碍模式下存在；root 下为 null 是正确结果。
                    val pageActive = s.page?.active ?: false
                    assertTrue("全局正常时 ${s.packageName} 的页面通道应可用", pageActive || s.page == null)
                }
                if (health.kind == CaptureHealthKind.SOURCES_DISABLED) {
                    assertFalse("全局无来源时 ${s.packageName} 不应有可用通道", s.anyActive)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 列表排序

    @Test fun attentionFirstThenStablePackageOrder() {
        // 两条通道都关掉：已选应用全部"有问题"，未选应用不算问题，因此排在后面。
        val broken = ready.copy(notificationsEnabled = false, pageEnabled = false)
        val states = appChannelStates(broken, listOf(wechat, alipay, meituan),
            whitelist = setOf(alipay, meituan))
        assertTrue(states.first { it.packageName == alipay }.needsAttention)
        assertTrue(states.first { it.packageName == meituan }.needsAttention)
        assertFalse(states.first { it.packageName == wechat }.needsAttention)
        // 有问题的排在前面；同组保持传入顺序（清单顺序），不按包名字典序重排。
        assertEquals(setOf(wechat, alipay, meituan), states.map { it.packageName }.toSet())
        assertEquals(listOf(alipay, meituan), states.take(2).map { it.packageName })
        assertEquals(wechat, states.last().packageName)
    }

    @Test fun sameGroupKeepsCallerOrder() {
        // 预设清单的语义顺序不能被字典序打乱：主包应排在衍生包之前。
        val order = listOf(meituan, alipay, wechat)
        val states = appChannelStates(ready.copy(notificationsEnabled = false, pageEnabled = false),
            order, order.toSet())
        assertEquals(order, states.map { it.packageName })
    }

    @Test fun duplicatePackagesCollapseToOneRow() {
        val states = appChannelStates(ready, listOf(alipay, alipay, wechat), setOf(alipay, wechat))
        assertEquals(2, states.size)
    }

    // ---------------------------------------------------------------- 模板结论来源

    @Test fun templateCoverageComesFromRegisteredTemplates() {
        // 支付宝已注册模板；微信在 SUPPORTED_PACKAGES 里但没有模板，两者不能混为一谈。
        assertTrue(state(ready, alipay).hasTemplate)
        assertFalse(state(ready, wechat).hasTemplate)
        // 没有模板也不再降级为 NO_TEMPLATE_FOR_APP：163 条规则随时可用。
        val none = appChannelStates(ready, listOf(alipay), setOf(alipay), templatePackages = emptySet())
        assertEquals(ChannelReason.ACTIVE, none.single().notification.reason)
    }

    @Test fun templatePackagesAreDerivedFromAdmittedTemplates() {
        val packages = com.example.billkit.notification.NotificationParser.TEMPLATE_PACKAGES
        // 当前只有支付宝那一个模板通过真实样本准入。
        assertEquals(setOf(alipay), packages)
        // 模板包必须同时是解析器认可的信封来源，否则模板永远不会被调用。
        assertTrue(packages.all { it in com.example.billkit.notification.NotificationParser.SUPPORTED_PACKAGES })
        // 反证：微信在信封白名单里，但没有模板 → 界面上不能显示为可自动记账。
        assertTrue(wechat in com.example.billkit.notification.NotificationParser.SUPPORTED_PACKAGES)
        assertFalse(wechat in packages)
    }
    @Test fun rootReadsNeedMeasuredReadiness() {
        val input = ready.copy(mode = CaptureMode.ROOT)
        assertEquals(ChannelReason.ROOT_UNREADY, state(input, wechat).notification.reason)
        assertFalse(state(input, wechat).anyActive)
        assertTrue(state(input.copy(rootNotificationReady = true), wechat).notification.active)
    }

    @Test fun hookOnlyIsScopedToTheActuallyObservedTarget() {
        val input = ready.copy(mode = CaptureMode.ROOT, notificationsEnabled = false,
            hookEnabled = true, hookReady = true, hookPackages = setOf(wechat))
        assertTrue(state(input, wechat).hook?.active == true)
        assertTrue(state(input, wechat).anyActive)
        assertFalse(state(input, wechat).needsAttention)
        assertFalse(state(input, alipay).hook?.active == true)
        assertEquals(ChannelReason.HOOK_UNCONFIRMED, state(input, alipay).hook?.reason)
        assertNull(state(input, meituan).hook)
        assertNull(state(input.copy(mode = CaptureMode.ACCESSIBILITY), wechat).hook)
        assertEquals(ChannelReason.GLOBAL_PAUSED, state(input.copy(enabled = false), wechat).hook?.reason)
        assertEquals(ChannelReason.NOT_WHITELISTED, state(input, wechat, emptySet()).hook?.reason)
    }

}
