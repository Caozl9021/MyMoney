package com.example.autobook.ui

import com.example.autobook.data.CaptureMode

/**
 * 单条采集通道在界面上的门禁判断。
 *
 * 为什么单独抽出来：`CaptureHealthInput` 里的 `accessibilityAuthorized` / `accessibilityBound`
 * 只对无障碍模式有意义，`rootGranted` 只对 root 模式有意义。之前这些字段全塞进一个
 * `when (mode)` 大表里，结果每个分支都要顺手提一句"另一种模式"——
 * 用户选了无障碍，却在设置里反复看到 root 字样。见 [channelsFor]。
 */
internal val CaptureHealthInput.pageChannelEnabled: Boolean
    get() = mode == CaptureMode.ACCESSIBILITY && pageEnabled

internal val CaptureHealthInput.notificationChannelEnabled: Boolean
    get() = notificationsEnabled

/** Presentation-only policy: no permission requests or service starts during evaluation. */
internal data class CaptureHealthInput(
    val mode: CaptureMode?, val enabled: Boolean,
    val accessibilityAuthorized: Boolean, val accessibilityBound: Boolean,
    val overlayAllowed: Boolean, val autoConfirm: Boolean,
    val pageEnabled: Boolean = true,
    val notificationsEnabled: Boolean = false,
    val notificationAuthorized: Boolean = false,
    val notificationBound: Boolean = false,
    val smsEnabled: Boolean = false,
    val smsGranted: Boolean = false,
    val hookEnabled: Boolean = false,
    val hookReady: Boolean = false,
    val rootNotificationReady: Boolean = false,
    val rootNotificationError: String? = null,
    val hookPackages: Set<String> = emptySet(),
)
internal enum class CaptureAction(val label: String) {
    NOTIFICATION_ACCESS("授予通知使用权"), NOTIFICATION_RECONNECT("重新连接通知监听"),
    SELECT_MODE("选择识别方式"), RESUME("恢复识别"), ROOT_STATUS("查看 Root 状态"),
    ACCESSIBILITY("打开无障碍设置"), OVERLAY("允许悬浮窗"), PENDING("查看待确认"), SUPPORTED_APPS("查看应用与通道"),
    // 通道行专用的三个动作：渠道开关与它依赖的系统授权在同一个视线里，
    // 所以动作粒度是「补这一个授权」而不是「跳到某个设置页」。
    SMS_PERMISSION("授予短信权限"), NOTIFY("允许通知"), ROOT("授予 Root"),
    LSP("去启用 LSPosed 模块"),
}
internal enum class CaptureHealthKind { MODE_REQUIRED, PAUSED, ACCESSIBILITY_DISABLED, ACCESSIBILITY_UNBOUND, NOTIFICATION_DISABLED, NOTIFICATION_UNBOUND, ROOT_UNREADY, SMS_UNAUTHORIZED, HOOK_UNCONFIRMED, DEGRADED, SOURCES_DISABLED, NOTIFICATION_OBSERVING, OVERLAY_MISSING, PENDING_MODE, RUNNING }
internal data class CaptureHealth(
    val kind: CaptureHealthKind, val title: String, val detail: String,
    val actions: List<CaptureAction> = emptyList(),
) {
    val attention: Boolean get() = kind !in setOf(CaptureHealthKind.RUNNING, CaptureHealthKind.PENDING_MODE, CaptureHealthKind.NOTIFICATION_OBSERVING)
}

/** LSP has a dedicated Root-only page; the regular channel list contains notification, SMS and page. */
internal enum class CaptureChannel { NOTIFICATION, SMS, HOOK, PAGE }

internal fun channelsFor(mode: CaptureMode?): List<CaptureChannel> = when (mode) {
    CaptureMode.ROOT -> listOf(CaptureChannel.NOTIFICATION, CaptureChannel.SMS)
    CaptureMode.ACCESSIBILITY -> listOf(CaptureChannel.NOTIFICATION, CaptureChannel.SMS, CaptureChannel.PAGE)
    null -> listOf(CaptureChannel.NOTIFICATION, CaptureChannel.SMS)
}

internal val CaptureHealthInput.pageReady: Boolean
    get() = pageChannelEnabled && accessibilityAuthorized && accessibilityBound
internal val CaptureHealthInput.notificationReady: Boolean
    get() = notificationsEnabled && if (mode == CaptureMode.ROOT) rootNotificationReady
        else notificationAuthorized && notificationBound
internal val CaptureHealthInput.smsReady: Boolean
    get() = smsEnabled && smsGranted
internal val CaptureHealthInput.lspReady: Boolean
    get() = mode == CaptureMode.ROOT && hookEnabled && hookReady
/** Readiness is independent of presentation permissions and never inferred from the master switch. */
internal val CaptureHealthInput.recognitionReady: Boolean
    get() = enabled && (pageReady || notificationReady || smsReady || lspReady)

internal fun captureHealth(input: CaptureHealthInput): CaptureHealth = with(input) {
    if (mode == null && !notificationsEnabled && !smsEnabled) return CaptureHealth(CaptureHealthKind.MODE_REQUIRED,
        "选择使用方式", "选择普通模式或 Root 模式后，按提示完成设置。", listOf(CaptureAction.SELECT_MODE))
    if (!enabled) return CaptureHealth(CaptureHealthKind.PAUSED, "自动记账已暂停",
        "已保留使用方式与通道设置。", listOf(CaptureAction.RESUME))

    val issues = buildList {
        if (notificationsEnabled) {
            if (mode == CaptureMode.ROOT) {
                if (!rootNotificationReady) add(CaptureHealth(CaptureHealthKind.ROOT_UNREADY,
                    "尚未就绪 · Root 通知需处理", rootNotificationError ?: "正在检测通知读取状态，请稍候。",
                    listOf(CaptureAction.ROOT_STATUS)))
            } else when {
                !notificationAuthorized -> add(CaptureHealth(CaptureHealthKind.NOTIFICATION_DISABLED,
                    "尚未就绪 · 还差通知使用权", "通知使用权尚未授予，开启后可读取交易提醒。", listOf(CaptureAction.NOTIFICATION_ACCESS)))
                !notificationBound -> add(CaptureHealth(CaptureHealthKind.NOTIFICATION_UNBOUND,
                    "尚未就绪 · 通知尚未连接", "系统已授权，但监听服务尚未连接。", listOf(CaptureAction.NOTIFICATION_RECONNECT)))
            }
        }
        if (pageChannelEnabled) when {
            !accessibilityAuthorized -> add(CaptureHealth(CaptureHealthKind.ACCESSIBILITY_DISABLED,
                "尚未就绪 · 还差无障碍授权", "无障碍服务未授权，请在系统设置中开启 麦米。", listOf(CaptureAction.ACCESSIBILITY)))
            !accessibilityBound -> add(CaptureHealth(CaptureHealthKind.ACCESSIBILITY_UNBOUND,
                "尚未就绪 · 页面辅助尚未连接", "无障碍已授权但服务未连接，请在系统设置中重新开启 麦米。", listOf(CaptureAction.ACCESSIBILITY)))
        }
        if (smsEnabled && !smsGranted) add(CaptureHealth(CaptureHealthKind.SMS_UNAUTHORIZED,
            "尚未就绪 · 还差短信权限", "银行短信识别已开启，还需授予短信权限。", listOf(CaptureAction.SMS_PERMISSION)))
        if (mode == CaptureMode.ROOT && hookEnabled && !hookReady) add(CaptureHealth(CaptureHealthKind.HOOK_UNCONFIRMED,
            "尚未确认应用模块加载", "请在 LSPosed 设置中检测微信、支付宝的模块加载状态。", listOf(CaptureAction.LSP)))
    }
    if (!recognitionReady) return issues.firstOrNull() ?: CaptureHealth(CaptureHealthKind.SOURCES_DISABLED,
        "尚未就绪 · 识别来源已关闭", "请在识别设置或更多设置中开启至少一种识别方式。", listOf(CaptureAction.SELECT_MODE))

    val sources = buildList {
        if (notificationReady) add(if (mode == CaptureMode.ROOT) "Root 通知识别" else "通知识别")
        if (pageReady) add("页面辅助")
        if (smsReady) add("银行短信")
        if (lspReady) add("LSPosed 采集")
    }.joinToString("、")
    if (!autoConfirm) return CaptureHealth(CaptureHealthKind.PENDING_MODE, "已开启 · 待确认模式",
        "$sources 可用；识别结果保存在待确认中，不自动弹卡。", listOf(CaptureAction.PENDING))
    if (!overlayAllowed) return CaptureHealth(CaptureHealthKind.OVERLAY_MISSING, "已开启 · 确认卡需授权",
        "$sources 可用；仍会尝试打开确认页，无法打开时保留待确认并尝试通知提醒。", listOf(CaptureAction.OVERLAY, CaptureAction.PENDING))
    if (issues.isNotEmpty()) return CaptureHealth(CaptureHealthKind.DEGRADED,
        "已开启 · $sources 可用", issues.first().detail, issues.first().actions)
    return CaptureHealth(if (notificationReady) CaptureHealthKind.NOTIFICATION_OBSERVING else CaptureHealthKind.RUNNING,
        "已开启 · $sources 可用", "识别到交易提醒或账单后生成草稿，确认保存后记账。")
}

/** Navigation only; deliberately separate from persisted capture modes. */
enum class CaptureSettingsDestination { MODE, SETUP, ROOT_STATUS, SUPPORTED_APPS, SMS }
