package com.example.autobook.ui

import com.example.autobook.data.CaptureMode

/** Presentation-only policy: no permission requests or service starts during evaluation. */
internal data class CaptureHealthInput(
    val mode: CaptureMode?, val enabled: Boolean, val rootRunning: Boolean,
    val accessibilityAuthorized: Boolean, val accessibilityBound: Boolean,
    val overlayAllowed: Boolean, val autoConfirm: Boolean,
    val pageFallbackEnabled: Boolean = true,
    val notificationsEnabled: Boolean = false,
    val notificationAuthorized: Boolean = false,
    val notificationBound: Boolean = false,
)
internal enum class CaptureAction(val label: String) {
    NOTIFICATION_ACCESS("授予通知使用权"), NOTIFICATION_RECONNECT("重新连接通知监听"),
    SELECT_MODE("选择识别方式"), RESUME("恢复识别"), ROOT_STATUS("查看 Root 状态"),
    ACCESSIBILITY("打开无障碍设置"), OVERLAY("允许悬浮窗"), PENDING("查看待确认"), SUPPORTED_APPS("查看识别与规则"),
}
internal enum class CaptureHealthKind { MODE_REQUIRED, PAUSED, ROOT_STOPPED, ACCESSIBILITY_DISABLED, ACCESSIBILITY_UNBOUND, NOTIFICATION_DISABLED, NOTIFICATION_UNBOUND, DEGRADED, SOURCES_DISABLED, NOTIFICATION_OBSERVING, OVERLAY_MISSING, PENDING_MODE, RUNNING }
internal data class CaptureHealth(
    val kind: CaptureHealthKind, val title: String, val detail: String,
    val actions: List<CaptureAction> = emptyList(),
) {
    val attention: Boolean get() = kind !in setOf(CaptureHealthKind.RUNNING, CaptureHealthKind.PENDING_MODE, CaptureHealthKind.NOTIFICATION_OBSERVING)
}
internal fun captureHealth(input: CaptureHealthInput): CaptureHealth = with(input) {
    if (mode == null && !notificationsEnabled) return CaptureHealth(CaptureHealthKind.MODE_REQUIRED,
        "选择识别来源", "可启用通知监听，或选择 Root / 无障碍页面辅助。", listOf(CaptureAction.SELECT_MODE))
    if (!enabled) return CaptureHealth(CaptureHealthKind.PAUSED, "自动识别已暂停",
        "已保留来源与模式，恢复后继续识别。", listOf(CaptureAction.RESUME))
    val pageReady = pageFallbackEnabled && when (mode) {
        CaptureMode.ROOT -> rootRunning
        CaptureMode.ACCESSIBILITY -> accessibilityAuthorized && accessibilityBound
        null -> false
    }
    val notificationReady = notificationsEnabled && notificationAuthorized && notificationBound
    val pageIssue = if (!pageFallbackEnabled) null else when {
        mode == null -> CaptureHealth(CaptureHealthKind.MODE_REQUIRED, "页面辅助尚未选择方式", "选择 Root 或无障碍作为页面辅助。", listOf(CaptureAction.SELECT_MODE))
        mode == CaptureMode.ROOT && !rootRunning -> CaptureHealth(CaptureHealthKind.ROOT_STOPPED, "自动识别暂不可用", "Root 识别未运行，请查看授权与启动状态。", listOf(CaptureAction.ROOT_STATUS))
        mode == CaptureMode.ACCESSIBILITY && !accessibilityAuthorized -> CaptureHealth(CaptureHealthKind.ACCESSIBILITY_DISABLED, "自动识别暂不可用", "无障碍服务未授权，请在系统设置中开启 AutoBook。", listOf(CaptureAction.ACCESSIBILITY))
        mode == CaptureMode.ACCESSIBILITY && !accessibilityBound -> CaptureHealth(CaptureHealthKind.ACCESSIBILITY_UNBOUND, "自动识别暂不可用", "无障碍已授权但服务未连接，请在系统设置中重新开启 AutoBook。", listOf(CaptureAction.ACCESSIBILITY))
        else -> null
    }
    val notificationIssue = when {
        !notificationsEnabled -> null
        !notificationAuthorized -> CaptureHealth(CaptureHealthKind.NOTIFICATION_DISABLED, "通知监听未授权", "通知使用权与 App 自身发送通知权限不同；页面辅助可独立工作。", listOf(CaptureAction.NOTIFICATION_ACCESS))
        !notificationBound -> CaptureHealth(CaptureHealthKind.NOTIFICATION_UNBOUND, "通知监听未连接", "系统已授权，但监听服务尚未绑定，可尝试重新连接。", listOf(CaptureAction.NOTIFICATION_RECONNECT, CaptureAction.NOTIFICATION_ACCESS))
        else -> null
    }
    if (!pageReady && !notificationReady) return notificationIssue ?: pageIssue ?: CaptureHealth(
        CaptureHealthKind.SOURCES_DISABLED, "识别来源已关闭", "总开关仍开启，请在设置中启用通知监听或页面辅助。", listOf(CaptureAction.SELECT_MODE))
    if (pageReady && notificationIssue != null) return CaptureHealth(CaptureHealthKind.DEGRADED,
        "页面辅助可用 · 通知需处理", notificationIssue.detail, notificationIssue.actions)
    if (notificationReady && pageIssue != null) return CaptureHealth(CaptureHealthKind.DEGRADED,
        "通知监听已连接 · 页面辅助需处理", "交易通知模板仍待真实样本验证；" + pageIssue.detail, pageIssue.actions)
    if (!autoConfirm) return CaptureHealth(CaptureHealthKind.PENDING_MODE, "自动识别已开启 · 待确认模式",
        "识别结果保存在待确认中，不自动弹卡。", listOf(CaptureAction.PENDING))
    if (!overlayAllowed) return CaptureHealth(CaptureHealthKind.OVERLAY_MISSING, "悬浮确认不可用",
        "仍会尝试打开确认页；无法打开时保留待确认，并尝试通知提醒。", listOf(CaptureAction.OVERLAY, CaptureAction.PENDING))
    if (notificationReady) return CaptureHealth(CaptureHealthKind.NOTIFICATION_OBSERVING,
        "通知监听已连接", "已适配支付宝指定渠道的支出交易提醒；微信及其他通知模板仍待样本验证，页面辅助可独立识别。")
    CaptureHealth(CaptureHealthKind.RUNNING, "自动识别已开启", "识别到账单后显示确认卡。")
}

/** Navigation only; deliberately separate from persisted capture modes. */
enum class CaptureSettingsDestination { MODE, ROOT_STATUS, SUPPORTED_APPS }
