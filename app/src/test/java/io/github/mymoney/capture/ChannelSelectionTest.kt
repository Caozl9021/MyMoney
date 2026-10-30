package io.github.mymoney.capture

import io.github.mymoney.data.NotificationPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阶段 3：通知通道按 listener/root 的可用性选实现。
 *
 * 这组用例钉住判定表本身。真机上「root 被撤销」「通知使用权被撤销」「两套实现同时可用」
 * 这些组合没法随手制造，所以判定收进纯函数，用穷举把每格都过一遍。
 */
class ChannelSelectionTest {

    private fun caps(
        enabled: Boolean = true,
        notificationsEnabled: Boolean = true,
        listenerAuthorized: Boolean = false,
        listenerBound: Boolean = false,
        rootAvailable: Boolean = false,
    ) = ChannelCapabilities(enabled, notificationsEnabled, listenerAuthorized, listenerBound, rootAvailable)

    @Test
    fun `开关关着时两套实现都不该跑`() {
        // 有 listener、有 root、全都授权：只要开关关着就什么都不起。
        assertNull(ChannelSelection.decide(
            caps(enabled = false, listenerAuthorized = true, listenerBound = true, rootAvailable = true), 0))
        assertNull(ChannelSelection.decide(
            caps(notificationsEnabled = false, listenerAuthorized = true, listenerBound = true, rootAvailable = true), 0))
    }

    @Test
    fun `listener 可用时永远优先它，哪怕 root 也在`() {
        // 这是本阶段最要紧的一条：root 可用不再意味着要去跑 dumpsys。
        // 否则授权了通知的 root 用户会一直挂着前台服务和 5 秒一次的 su。
        assertEquals(NotificationPath.LISTENER,
            ChannelSelection.decide(caps(listenerAuthorized = true, listenerBound = true, rootAvailable = true), 0))
        assertEquals(NotificationPath.LISTENER,
            ChannelSelection.decide(caps(listenerAuthorized = true, listenerBound = true), 0))
    }

    @Test
    fun `从没授权过不算抖动，有 root 就直接兜底`() {
        // 没授权这件事等到天亮也不会自己好，没必要迟滞。
        assertEquals(NotificationPath.DUMPSYS,
            ChannelSelection.decide(caps(listenerAuthorized = false, rootAvailable = true), 0))
        assertNull("没授权又没 root：两条路都没有",
            ChannelSelection.decide(caps(listenerAuthorized = false, rootAvailable = false), 0))
    }

    @Test
    fun `授权了但没连上要连续两次确认才降级`() {
        val authorized = caps(listenerAuthorized = true, listenerBound = false, rootAvailable = true)
        assertNull("第一次确认：可能是系统正在重绑，先等", ChannelSelection.decide(authorized, 1))
        assertEquals(NotificationPath.DUMPSYS, ChannelSelection.decide(authorized, 2))
        // 没有 root 时，确认够了也无路可走。
        assertNull(ChannelSelection.decide(
            caps(listenerAuthorized = true, rootAvailable = false), 2))
    }

    @Test
    fun `掉线计数只在确认点推进，别的状态变化不涨`() {
        val down = caps(listenerAuthorized = true)
        assertEquals(1, ChannelSelection.nextStreak(down, 0))
        assertEquals(2, ChannelSelection.nextStreak(down, 1))
        assertEquals("封顶就是确认阈值，不会无上限增长",
            ChannelSelection.LISTENER_DOWN_CONFIRMATIONS, ChannelSelection.nextStreak(down, 99))
        // 连上了、关掉了、没授权：都清零，下次掉线重新数。
        assertEquals(0, ChannelSelection.nextStreak(caps(listenerAuthorized = true, listenerBound = true), 2))
        assertEquals(0, ChannelSelection.nextStreak(caps(enabled = false), 2))
        assertEquals(0, ChannelSelection.nextStreak(caps(listenerAuthorized = false), 2))
    }

    @Test
    fun `只在授权未连通且还没确认够时才安排复查`() {
        assertEquals(ChannelSelection.LISTENER_RECHECK_MS,
            ChannelSelection.recheckDelayMs(caps(listenerAuthorized = true), 0))
        assertEquals(ChannelSelection.LISTENER_RECHECK_MS,
            ChannelSelection.recheckDelayMs(caps(listenerAuthorized = true), 1))
        assertNull("确认够了，直接降级，不用再等",
            ChannelSelection.recheckDelayMs(caps(listenerAuthorized = true), 2))
        assertNull("连通了没什么可复查",
            ChannelSelection.recheckDelayMs(caps(listenerAuthorized = true, listenerBound = true), 0))
        assertNull("没授权走的是另一条分支",
            ChannelSelection.recheckDelayMs(caps(listenerAuthorized = false), 0))
        assertNull("开关关着不复查", ChannelSelection.recheckDelayMs(caps(enabled = false, listenerAuthorized = true), 0))
    }

    @Test
    fun `恢复连通后立刻回到 listener`() {
        // 迟滞只用来「慢点降级」，不能推迟恢复：一收到 onListenerConnected 就该切回去，
        // 否则 dumpsys 会多跑一轮，同一个通知被两套实现各报一次。
        val recovered = caps(listenerAuthorized = true, listenerBound = true, rootAvailable = true)
        assertEquals(0, ChannelSelection.nextStreak(recovered, 2))
        assertEquals(NotificationPath.LISTENER, ChannelSelection.decide(recovered, 0))
    }

    @Test
    fun `授权了但没连上才去要重绑`() {
        // 真机现场：系统设置里授予通知使用权之后，应用仍按「无授权」跑 root 读通知十分钟。
        // 授权这一路变化必须能把通道拉回来——先主动要一次重绑，再重算。
        assertEquals(true, ChannelSelection.shouldRequestReconnect(caps(listenerAuthorized = true)))
        assertEquals("连上了不用重绑",
            false, ChannelSelection.shouldRequestReconnect(caps(listenerAuthorized = true, listenerBound = true)))
        assertEquals("没授权重绑没意义，系统也不会绑",
            false, ChannelSelection.shouldRequestReconnect(caps(listenerAuthorized = false)))
        assertEquals("总开关关着不折腾",
            false, ChannelSelection.shouldRequestReconnect(caps(enabled = false, listenerAuthorized = true)))
        assertEquals("通知开关关着不折腾",
            false, ChannelSelection.shouldRequestReconnect(caps(notificationsEnabled = false, listenerAuthorized = true)))
    }
}
