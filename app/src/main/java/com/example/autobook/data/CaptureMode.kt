package com.example.autobook.data

/** The persisted collection choice is independent of the recognition pause switch. */
enum class CaptureMode { ROOT, ACCESSIBILITY }

data class CaptureModeState(
    val mode: CaptureMode? = null,
    val enabled: Boolean = false,
    val pageFallbackEnabled: Boolean = true,
    val notificationsEnabled: Boolean = false,
) {
    val rootActive: Boolean get() = enabled && pageFallbackEnabled && mode == CaptureMode.ROOT
    val accessibilityActive: Boolean get() = enabled && pageFallbackEnabled && mode == CaptureMode.ACCESSIBILITY
    val notificationActive: Boolean get() = enabled && notificationsEnabled
    val configured: Boolean get() = mode != null || notificationsEnabled
}

/** Pure migration and restore decisions; uncertainty must keep the choice unset. */
object CaptureModePolicy {
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
        current.copy(mode = next, pageFallbackEnabled = true,
            enabled = if (current.mode == null && !current.notificationsEnabled) true else current.enabled)
}
