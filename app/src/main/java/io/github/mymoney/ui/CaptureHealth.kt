package io.github.mymoney.ui

import io.github.mymoney.data.CaptureMode

/** 页面识别状态输入；仅关注无障碍页面识别与确认卡呈现权限。 */
internal data class CaptureHealthInput(
    val mode: CaptureMode?,
    val enabled: Boolean,
    val accessibilityAuthorized: Boolean,
    val accessibilityBound: Boolean,
    val overlayAllowed: Boolean,
    val autoConfirm: Boolean,
)

internal enum class CaptureAction(val label: String) {
    OPEN_AUTOMATION("前往设置"),
    RESUME("开启"),
    ACCESSIBILITY("去开启"),
    OVERLAY("去允许"),
    PENDING("查看待处理"),
}

internal enum class CaptureHealthKind {
    MODE_REQUIRED,
    PAUSED,
    ACCESSIBILITY_DISABLED,
    ACCESSIBILITY_UNBOUND,
    OVERLAY_MISSING,
    PENDING_MODE,
    RUNNING,
}

internal data class CaptureHealth(
    val kind: CaptureHealthKind,
    val title: String,
    val detail: String,
    val shortStatus: String,
    val actions: List<CaptureAction> = emptyList(),
) {
    val attention: Boolean get() = kind !in setOf(CaptureHealthKind.RUNNING, CaptureHealthKind.PENDING_MODE)
}

internal val CaptureHealthInput.recognitionReady: Boolean
    get() = mode != null && enabled && accessibilityAuthorized && accessibilityBound

internal fun captureHealth(input: CaptureHealthInput): CaptureHealth = with(input) {
    if (mode == null) return CaptureHealth(
        kind = CaptureHealthKind.MODE_REQUIRED,
        title = "自动记账未开启",
        detail = "开启无障碍服务后自动识别交易详情",
        shortStatus = "未开启",
        actions = listOf(CaptureAction.OPEN_AUTOMATION),
    )
    if (!enabled) return CaptureHealth(
        kind = CaptureHealthKind.PAUSED,
        title = "自动记账已暂停",
        detail = "点击恢复页面识别",
        shortStatus = "已暂停",
        actions = listOf(CaptureAction.RESUME),
    )
    if (!accessibilityAuthorized) return CaptureHealth(
        kind = CaptureHealthKind.ACCESSIBILITY_DISABLED,
        title = "需开启无障碍服务",
        detail = "在系统设置中开启麦米",
        shortStatus = "需授权",
        actions = listOf(CaptureAction.ACCESSIBILITY),
    )
    if (!accessibilityBound) return CaptureHealth(
        kind = CaptureHealthKind.ACCESSIBILITY_UNBOUND,
        title = "无障碍服务未连接",
        detail = "请在系统设置中重新开关一次",
        shortStatus = "未连接",
        actions = listOf(CaptureAction.ACCESSIBILITY),
    )
    if (!autoConfirm) return CaptureHealth(
        kind = CaptureHealthKind.PENDING_MODE,
        title = "自动记账已开启",
        detail = "识别后保存在记录历史",
        shortStatus = "待确认",
        actions = listOf(CaptureAction.PENDING),
    )
    if (!overlayAllowed) return CaptureHealth(
        kind = CaptureHealthKind.OVERLAY_MISSING,
        title = "需开启悬浮窗",
        detail = "授权后可直接弹出确认卡",
        shortStatus = "需悬浮窗",
        actions = listOf(CaptureAction.OVERLAY, CaptureAction.PENDING),
    )
    return CaptureHealth(
        kind = CaptureHealthKind.RUNNING,
        title = "自动记账已开启",
        detail = "",
        shortStatus = "已开启",
    )
}

/** Navigation only; deliberately separate from persisted capture modes. */
enum class CaptureSettingsDestination { AUTOMATION }
