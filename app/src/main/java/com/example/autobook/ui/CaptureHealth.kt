package com.example.autobook.ui

import com.example.autobook.data.CaptureMode

/** Presentation-only policy: no permission requests or service starts during evaluation. */
internal data class CaptureHealthInput(
    val mode: CaptureMode?, val enabled: Boolean, val rootRunning: Boolean,
    val accessibilityAuthorized: Boolean, val accessibilityBound: Boolean,
    val overlayAllowed: Boolean, val autoConfirm: Boolean,
)
internal enum class CaptureAction(val label: String) {
    SELECT_MODE("选择识别方式"), RESUME("恢复识别"), ROOT_STATUS("查看 Root 状态"),
    ACCESSIBILITY("打开无障碍设置"), OVERLAY("允许悬浮窗"), PENDING("查看待确认"), SUPPORTED_APPS("查看支持的应用"),
}
internal enum class CaptureHealthKind { MODE_REQUIRED, PAUSED, ROOT_STOPPED, ACCESSIBILITY_DISABLED, ACCESSIBILITY_UNBOUND, OVERLAY_MISSING, PENDING_MODE, RUNNING }
internal data class CaptureHealth(
    val kind: CaptureHealthKind, val title: String, val detail: String,
    val actions: List<CaptureAction> = emptyList(),
) {
    val attention: Boolean get() = kind !in setOf(CaptureHealthKind.RUNNING, CaptureHealthKind.PENDING_MODE)
}
internal fun captureHealth(input: CaptureHealthInput): CaptureHealth = with(input) {
    when {
        mode == null -> CaptureHealth(CaptureHealthKind.MODE_REQUIRED, "选择自动识别方式", "选择 Root 或无障碍后开始识别。", listOf(CaptureAction.SELECT_MODE))
        !enabled -> CaptureHealth(CaptureHealthKind.PAUSED, "自动识别已暂停", "已保留识别方式，恢复后继续识别。", listOf(CaptureAction.RESUME))
        mode == CaptureMode.ROOT && !rootRunning -> CaptureHealth(CaptureHealthKind.ROOT_STOPPED, "自动识别暂不可用", "Root 识别未运行，请查看授权与启动状态。", listOf(CaptureAction.ROOT_STATUS))
        mode == CaptureMode.ACCESSIBILITY && !accessibilityAuthorized -> CaptureHealth(CaptureHealthKind.ACCESSIBILITY_DISABLED, "自动识别暂不可用", "无障碍服务未授权，请在系统设置中开启 AutoBook。", listOf(CaptureAction.ACCESSIBILITY))
        mode == CaptureMode.ACCESSIBILITY && !accessibilityBound -> CaptureHealth(CaptureHealthKind.ACCESSIBILITY_UNBOUND, "自动识别暂不可用", "无障碍已授权但服务未连接，请在系统设置中重新开启 AutoBook。", listOf(CaptureAction.ACCESSIBILITY))
        !autoConfirm -> CaptureHealth(CaptureHealthKind.PENDING_MODE, "自动识别已开启 · 待确认模式", "识别结果保存在待确认中，不自动弹卡。", listOf(CaptureAction.PENDING))
        !overlayAllowed -> CaptureHealth(CaptureHealthKind.OVERLAY_MISSING, "悬浮确认不可用", "仍会尝试打开确认页；无法打开时保留待确认，并尝试通知提醒。", listOf(CaptureAction.OVERLAY, CaptureAction.PENDING))
        else -> CaptureHealth(CaptureHealthKind.RUNNING, "自动识别已开启", "识别到账单后显示确认卡。")
    }
}

/** Navigation only; deliberately separate from persisted capture modes. */
enum class CaptureSettingsDestination { MODE, ROOT_STATUS, SUPPORTED_APPS }
