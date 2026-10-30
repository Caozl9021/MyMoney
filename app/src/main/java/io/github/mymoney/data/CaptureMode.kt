package io.github.mymoney.data

/** The persisted collection choice is independent of the recognition pause switch. */
enum class CaptureMode { ROOT, ACCESSIBILITY }

/**
 * 采集配置。
 *
 * 通道只有四条，各有独立开关：[notificationsEnabled]、[smsEnabled]、[hookEnabled]、[pageEnabled]。
 *
 * [mode] 曾经决定通知通道**怎么实现**（`ACCESSIBILITY` 走 `NotificationListenerService`、
 * `ROOT` 走 `dumpsys notification`）。那个绑定已拆掉：实现改由 `capture.ChannelSelection`
 * 按当时的可用性判——listener 能连上就用 listener，连不上再看有没有 root。理由是模式是用户
 * 当初的选择，而授权、ROM 解绑、root 撤销都是后来发生的事实，用一次性的表单去回答「此刻谁在跑」
 * 必然答错。现在 [mode] 只剩两件事：
 *
 * - 页面通道要不要跑。它只有 `ACCESSIBILITY` 一种实现——root 模式下的 uiautomator dump + 截图
 *   OCR 已随 `RootWatcher` 一起删除（它补的「页面有付款但既没通知也没 hook」场景在微信/支付宝上
 *   不存在，代价却是每 1.2–3 秒一次 `su` + dump）。所以 root 模式下页面辅助恒不可用。
 * - 界面按哪套预设展示。
 *
 * 凭证通道（[hookEnabled]）**不受 [mode] 约束**：模块跑在微信/支付宝自己的进程里，经 provider
 * 把凭证交回宿主，与宿主这边用哪种采集方式无关。它的前提只有两个——LSPosed 里启用模块并勾选
 * 作用域，以及用户打开这个开关。曾经把它绑在 `ROOT` 上只是历史遗留：早期它和 root 页面轮询
 * 一起做，root 没了就顺手一起判掉了；实际结果是「用了无障碍的人永远开不了最准的那条通道」。
 *
 * 历史：过去 [notificationsEnabled] 与 root 读通知是两个并行开关，同一条微信付款
 * 可能被两条路各读一遍，靠跨来源去重兜住——纯属冗余，已合并。
 */
data class CaptureModeState(
    val mode: CaptureMode? = null,
    val enabled: Boolean = false,
    val pageEnabled: Boolean = true,
    val notificationsEnabled: Boolean = false,
    /**
     * 短信通道独立于页面辅助与通知通道：它不需要选采集方式，也不占无障碍/Root 互斥。
     * 默认关闭，因为 `RECEIVE_SMS` 会看到全部短信，必须由用户显式打开。
     */
    val smsEnabled: Boolean = false,
    /**
     * LSPosed 采集通道（SPEC §8 阶段 3/4）。需要用户在 LSPosed 里启用模块并勾选微信/支付宝，
     * 是比通知/短信更重的一次授权，所以同样默认关闭、单独开关。
     */
    val hookEnabled: Boolean = false,
) {
    /**
     * 页面通道是否应该在工作。只看总开关与它自己的开关。
     *
     * 曾经还要求 `mode == ACCESSIBILITY`——因为 root 模式下的页面实现（uiautomator 轮询）被删了，
     * 于是「root 模式下页面恒不可用」。这个绑定在阶段 4 拆掉：页面通道本来就只有无障碍一种实现，
     * 用不用它是用户的开关 + 有没有授权，不该由「当初填的那张表单」决定。留着它的直接后果是
     * 已 root 的用户即使开着无障碍也用不上页面辅助，而界面上连这一行都不给。
     */
    val pageActive: Boolean get() = enabled && pageEnabled

    /**
     * 通知通道开没开。**这一刻能不能真读到通知不归这里管**：那取决于 listener 授权、绑定状态
     * 与 root 可用性，由 `capture.ChannelSelection` 判定。之前这里叫 `notificationPath`，
     * 用模式推实现，等于把「开关」和「此刻走哪条路」混成一个值。
     */
    val notificationActive: Boolean get() = enabled && notificationsEnabled
    val smsActive: Boolean get() = enabled && smsEnabled

    /** 凭证通道是否应该在工作。只看总开关与它自己的开关——模式与它无关。 */
    val hookActive: Boolean get() = enabled && hookEnabled
    val configured: Boolean get() = mode != null || notificationsEnabled || smsEnabled || hookEnabled
}

/**
 * 通知通道的两种实现。用户看不到这个枚举，它只是实现细节。
 *
 * 由 `ChannelSelection.decide` 在运行时挑，**不由 [CaptureMode] 推**；当前值挂在
 * `NotificationPlanState` 上供启停与诊断读取。
 */
enum class NotificationPath { LISTENER, DUMPSYS }

/** Pure migration and restore decisions; uncertainty must keep the choice unset. */
object CaptureModePolicy {
    /** Only a genuinely new configuration receives defaults; existing choices are immutable here. */
    fun prepareOrdinary(current: CaptureModeState): CaptureModeState =
        if (current.configured) current else current.copy(mode = CaptureMode.ACCESSIBILITY,
            enabled = false, notificationsEnabled = true, pageEnabled = true)

    fun migrate(stored: String?, legacyRoot: Boolean, accessibilityConfigured: Boolean): CaptureMode? =
        CaptureMode.entries.firstOrNull { it.name == stored }
            ?: when {
                legacyRoot -> CaptureMode.ROOT
                accessibilityConfigured -> CaptureMode.ACCESSIBILITY
                else -> null
            }

    fun restore(current: CaptureMode?, legacyRoot: Boolean): CaptureMode? =
        current ?: if (legacyRoot) CaptureMode.ROOT else null

    /**
     * `page_fallback_enabled` 键缺失时，页面通道的默认值。
     *
     * 阶段 4 之前它是 `captureMode != null || rootMode`——「配置过就默认开」。这两个条件都长在
     * **另一个键**上，于是备份恢复只要写进一个 `capture_mode`，就能把页面通道从关掰成开：
     * 全新安装时键是缺的（默认 false），恢复一份旧备份（`rootMode = true`）之后就变 true 了。
     * 恢复不该激活任何用户没开过的通道，所以判据收回到「用户当初是不是选了无障碍」：
     * ROOT 预设拿到的仍是 false（根模式下页面实现从来就不存在），恢复了无障碍配置的仍是 true。
     */
    fun defaultPageEnabled(mode: CaptureMode?): Boolean = mode == CaptureMode.ACCESSIBILITY

    /**
     * 旧的「root 读通知」独立开关要不要搬进统一的通知开关。
     *
     * 只在旧开关开着、用户当初确实选了 ROOT、且新开关还没被碰过时搬，且只搬一次。
     * 搬完**不碰凭证开关**：升级不该替用户打开一条他没给过授权的通道。
     */
    fun legacyRootNotificationMoves(
        legacyRootNotification: Boolean,
        mode: CaptureMode?,
        notificationsEnabled: Boolean,
        alreadyMigrated: Boolean,
    ): Boolean = !alreadyMigrated && legacyRootNotification && mode == CaptureMode.ROOT && !notificationsEnabled

    fun select(current: CaptureModeState, next: CaptureMode): CaptureModeState =
        current.copy(mode = next, pageEnabled = if (!current.configured) true else current.pageEnabled,
            notificationsEnabled = if (!current.configured) true else current.notificationsEnabled,
            enabled = if (current.mode == null && !current.notificationsEnabled && !current.smsEnabled &&
                !current.hookEnabled) true else current.enabled)
}
