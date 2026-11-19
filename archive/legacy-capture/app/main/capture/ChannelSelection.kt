package io.github.mymoney.capture

import io.github.mymoney.data.NotificationPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 判定「通知通道这一刻该由谁来读」所需的能力快照。
 *
 * 单列成数据类而不是在 `reconcile` 里随手读 Settings，是为了让判定能被穷举测试：
 * 「root 被撤销」「通知使用权被撤销」「两套实现同时可用」这些组合在真机上没法随手制造。
 */
data class ChannelCapabilities(
    val enabled: Boolean,
    val notificationsEnabled: Boolean,
    val listenerAuthorized: Boolean,
    val listenerBound: Boolean,
    val rootAvailable: Boolean,
)

/**
 * 通知通道两套实现的选择规则。
 *
 * 以前这件事由 `captureMode` 决定：选了 Root 就走 dumpsys，选了无障碍就走 listener。问题在于
 * 模式是**用户当初填的一次表单**，而可用性是**此刻的事实**——用户后来撤销了通知使用权、
 * 厂商 ROM 把 listener 解绑了、或者本来就有 root 只是没选 Root 模式，模式都答不上来。
 * 于是要么「明明有可用的路却在空转」，要么「listener 好着还开着一个 root 前台服务每 5 秒 su 一次」。
 *
 * 现在只看可用性，优先级只有一条：**listener 能用就用 listener**。它更便宜也更准——不用常驻
 * root 前台服务、不用反复跑 `su dumpsys`，extras 里的字段还比 dumpsys 渲染后的文本完整。
 * dumpsys 存在的意义是覆盖「用户不给通知使用权」，而不是「用户选了 Root」。
 *
 * 迟滞只加在一处，见 [nextStreak]。
 */
object ChannelSelection {

    /**
     * 「授权了但没连上」要连续确认几次才判掉线。
     *
     * 只对这一条分支做迟滞。授权名单里还有我们，说明用户给过权限，此时没连上更可能是
     * 系统正在重绑、ROM 省电刚解绑、进程刚起来还没走到 `onListenerConnected`——**都会自己好**。
     * 立刻切 dumpsys 的代价是起一个前台服务再停掉，白折腾一遍还留个通知闪一下。
     *
     * 「从来没授权过」不走迟滞：那不是抖动，等多久系统也不会自己把授权补上。
     */
    const val LISTENER_DOWN_CONFIRMATIONS = 2

    /** 两次确认之间的间隔。真机上一次重绑通常 1–2 秒内完成。 */
    const val LISTENER_RECHECK_MS = 10_000L

    fun decide(caps: ChannelCapabilities, listenerDownStreak: Int): NotificationPath? = when {
        !caps.enabled || !caps.notificationsEnabled -> null
        caps.listenerAuthorized && caps.listenerBound -> NotificationPath.LISTENER
        !caps.listenerAuthorized -> if (caps.rootAvailable) NotificationPath.DUMPSYS else null
        caps.rootAvailable && listenerDownStreak >= LISTENER_DOWN_CONFIRMATIONS -> NotificationPath.DUMPSYS
        else -> null
    }

    /**
     * 掉线计数的步进。**只在确认点调用**——收到解绑回调，或者宽限期到点复查。
     *
     * 不能挂在每次 `reconcile` 上：那样改个无关开关也会让计数涨一格，两次无关的重组就能凑出
     * 一个「掉线确认」，迟滞等于没有。
     */
    fun nextStreak(caps: ChannelCapabilities, current: Int): Int = when {
        !caps.enabled || !caps.notificationsEnabled -> 0
        caps.listenerBound -> 0
        !caps.listenerAuthorized -> 0
        else -> (current + 1).coerceAtMost(LISTENER_DOWN_CONFIRMATIONS)
    }

    /**
     * 授权了、但这一刻还没连上，要不要主动要一次重绑。
     *
     * 系统在授予通知使用权后会自己绑定，正常几百毫秒内就到位。但真机上见过两种没到位的：
     * 授权写入与绑定回调抢先后（回调先到，那一刻还判成「无授权」），以及 MIUI 把后台进程
     * 的监听延后绑定。两种情况都表现为「已授权、未绑定」，此时让 dumpsys 顶上是最差的选择——
     * 它要常驻前台服务并每 5 秒跑一次 `su`，而 listener 一旦绑上就完全不需要这些。
     *
     * 所以这里只在「确实授权了」的前提下要重绑：没授权时重绑没有意义，系统也不会绑。
     */
    fun shouldRequestReconnect(caps: ChannelCapabilities): Boolean =
        caps.enabled && caps.notificationsEnabled && caps.listenerAuthorized && !caps.listenerBound

    /** 还要不要再复查一次；null 表示不需要，可以撤掉待办。 */
    fun recheckDelayMs(caps: ChannelCapabilities, listenerDownStreak: Int): Long? = when {
        !caps.enabled || !caps.notificationsEnabled -> null
        !caps.listenerAuthorized || caps.listenerBound -> null
        listenerDownStreak >= LISTENER_DOWN_CONFIRMATIONS -> null
        else -> LISTENER_RECHECK_MS
    }
}

/**
 * 当前生效的通知实现。由 [CaptureModeManager.reconcile] 写，其它地方只读。
 *
 * 这是**运行时事实**，不是配置：同一份配置在不同设备上会落到不同实现，同一设备上它还会随
 * 授权变化而变。要读「用户配了什么」看 `CaptureModeState`，要读「现在谁在跑」看这里。
 */
object NotificationPlanState {
    private val mutable = MutableStateFlow<NotificationPath?>(null)
    val flow: StateFlow<NotificationPath?> = mutable.asStateFlow()
    val value: NotificationPath? get() = mutable.value
    internal fun set(plan: NotificationPath?) {
        mutable.value = plan
    }
}
