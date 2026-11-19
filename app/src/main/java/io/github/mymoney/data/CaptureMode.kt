package io.github.mymoney.data

/** The persisted collection choice is independent of the recognition pause switch. */
enum class CaptureMode { ROOT, ACCESSIBILITY }

/**
 * 采集配置（仅保留无障碍页面识别；其余历史字段保留兼容备份与单测）。
 */
data class CaptureModeState(
    val mode: CaptureMode? = null,
    val enabled: Boolean = false,
    val pageEnabled: Boolean = true,
    val notificationsEnabled: Boolean = false,
    val smsEnabled: Boolean = false,
    val hookEnabled: Boolean = false,
) {
    val pageActive: Boolean get() = enabled && pageEnabled
    val notificationActive: Boolean get() = enabled && notificationsEnabled
    val smsActive: Boolean get() = enabled && smsEnabled
    val hookActive: Boolean get() = enabled && hookEnabled
    val configured: Boolean get() = mode != null
}

/**
 * 历史通知通道枚举（仅保留以兼容既有备份与测试引用）。
 */
enum class NotificationPath { LISTENER, DUMPSYS }

/** Pure migration and restore decisions; uncertainty must keep the choice unset. */
object CaptureModePolicy {
    /** Only a genuinely new configuration receives defaults; existing choices are immutable here. */
    fun prepareOrdinary(current: CaptureModeState): CaptureModeState =
        if (current.configured) current else current.copy(
            mode = CaptureMode.ACCESSIBILITY,
            enabled = false,
            notificationsEnabled = false,
            pageEnabled = true,
        )

    fun migrate(stored: String?, legacyRoot: Boolean, accessibilityConfigured: Boolean): CaptureMode? =
        CaptureMode.entries.firstOrNull { it.name == stored }
            ?: when {
                legacyRoot -> CaptureMode.ROOT
                accessibilityConfigured -> CaptureMode.ACCESSIBILITY
                else -> null
            }

    fun restore(current: CaptureMode?, legacyRoot: Boolean): CaptureMode? =
        current ?: if (legacyRoot) CaptureMode.ROOT else null

    fun defaultPageEnabled(mode: CaptureMode?): Boolean = mode == CaptureMode.ACCESSIBILITY

    fun legacyRootNotificationMoves(
        legacyRootNotification: Boolean,
        mode: CaptureMode?,
        notificationsEnabled: Boolean,
        alreadyMigrated: Boolean,
    ): Boolean = !alreadyMigrated && legacyRootNotification && mode == CaptureMode.ROOT && !notificationsEnabled

    fun select(current: CaptureModeState, next: CaptureMode): CaptureModeState =
        current.copy(
            mode = next,
            pageEnabled = if (!current.configured) true else current.pageEnabled,
            notificationsEnabled = if (!current.configured) true else current.notificationsEnabled,
            enabled = if (current.mode == null && !current.notificationsEnabled && !current.smsEnabled &&
                !current.hookEnabled
            ) true else current.enabled,
        )
}
