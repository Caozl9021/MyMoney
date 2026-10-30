package io.github.caozl9021.mymoney.data

/** The persisted collection choice is independent of the recognition pause switch. */
enum class CaptureMode { ROOT, ACCESSIBILITY }

/**
 * 采集配置。
 *
 * 通道只有四条，各有独立开关：[notificationsEnabled]、[smsEnabled]、[hookEnabled]、[pageEnabled]。
 * [mode] 不再是一条独立通道，它只决定两条通道**怎么实现**：
 * - 通知通道：`ACCESSIBILITY` 走 `NotificationListenerService`；`ROOT` 走 `dumpsys notification`
 *   （免「通知使用权」授权，但需要常驻 root 前台服务）。
 * - 页面通道：只有 `ACCESSIBILITY` 一种实现。root 模式下的 uiautomator dump + 截图 OCR
 *   已随 `RootWatcher` 一起删除——它补的「页面有付款但既没通知也没 hook」场景在微信/支付宝上
 *   不存在，代价却是每 1.2–3 秒一次 `su` + dump。所以 root 模式下页面辅助恒不可用。
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
    /** 通知通道实际走哪条实现；开关关着或没选模式时返回 null。 */
    val notificationPath: NotificationPath?
        get() = when {
            !enabled || !notificationsEnabled -> null
            mode == CaptureMode.ROOT -> NotificationPath.DUMPSYS
            mode == CaptureMode.ACCESSIBILITY -> NotificationPath.LISTENER
            else -> null
        }

    val pageActive: Boolean get() = enabled && pageEnabled && mode == CaptureMode.ACCESSIBILITY
    val notificationActive: Boolean get() = notificationPath != null
    val smsActive: Boolean get() = enabled && smsEnabled
    val hookActive: Boolean get() = enabled && hookEnabled && mode == CaptureMode.ROOT
    val configured: Boolean get() = mode != null || notificationsEnabled || smsEnabled || hookEnabled
}

/** 通知通道的两种实现。用户看不到这个枚举，它只是实现细节。 */
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

    fun select(current: CaptureModeState, next: CaptureMode): CaptureModeState =
        current.copy(mode = next, pageEnabled = if (!current.configured) true else current.pageEnabled,
            notificationsEnabled = if (!current.configured) true else current.notificationsEnabled,
            enabled = if (current.mode == null && !current.notificationsEnabled && !current.smsEnabled &&
                !current.hookEnabled) true else current.enabled)
}
