package io.github.mymoney.ui

import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.NotificationPath

/**
 * 单条采集通道在界面上的门禁判断。
 *
 * 为什么单独抽出来：`CaptureHealthInput` 里的 `accessibilityAuthorized` / `accessibilityBound`
 * 只对页面通道有意义，`rootNotificationReady` 只对 dumpsys 那套实现有意义。这些字段曾经全塞进
 * 一个 `when (mode)` 大表里，结果每个分支都要顺手提一句"另一种模式"——用户选了无障碍，
 * 却在设置里反复看到 root 字样。现在按通道自己判，见 [channelList]。
 */
/**
 * 页面通道开没开。**不看模式**——它只有无障碍一种实现，要不要它是开关的事。
 * 曾经写成 `mode == ACCESSIBILITY && pageEnabled`，结果是已 root 的用户即使开着无障碍也用不上它。
 */
internal val CaptureHealthInput.pageChannelEnabled: Boolean
    get() = pageEnabled

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
    /**
     * 此刻实际在跑的通知实现，由 `NotificationPlanState` 提供。
     *
     * 不是从 [mode] 推的：模式是用户当初的选择，实现是运行时按 listener/root 可用性判出来的。
     * 为 null 表示「这一刻没有可用的实现」——所以纯展示的调用方也必须显式给值，
     * 不能靠默认值蒙混过去。
     */
    val notificationPlan: NotificationPath? = null,
    val smsEnabled: Boolean = false,
    val smsGranted: Boolean = false,
    val hookEnabled: Boolean = false,
    val hookReady: Boolean = false,
    val rootNotificationReady: Boolean = false,
    val rootNotificationError: String? = null,
    val hookPackages: Set<String> = emptySet(),
    /** null 仅用于不提供规则状态的纯展示调用方；实际界面始终传入真实覆盖集。 */
    val ruleCapabilities: Set<String>? = null,
    val ruleEngineReady: Boolean = true,
    val selectedPackages: Set<String>? = null,
)
internal enum class CaptureAction(val label: String) {
    NOTIFICATION_ACCESS("授予通知使用权"), NOTIFICATION_RECONNECT("重新连接通知监听"),
    OPEN_AUTOMATION("开启自动记账"), RESUME("恢复识别"), ROOT_STATUS("查看 Root 状态"),
    ACCESSIBILITY("打开无障碍设置"), OVERLAY("允许悬浮窗"), PENDING("查看待确认"), SUPPORTED_APPS("查看应用与通道"),
    // 通道行专用的三个动作：渠道开关与它依赖的系统授权在同一个视线里，
    // 所以动作粒度是「补这一个授权」而不是「跳到某个设置页」。
    SMS_PERMISSION("查看应用范围"), NOTIFY("允许通知"), ROOT("授予 Root"),
    LSP("去启用 LSPosed 模块"),
}
internal enum class CaptureHealthKind { MODE_REQUIRED, PAUSED, ACCESSIBILITY_DISABLED, ACCESSIBILITY_UNBOUND, NOTIFICATION_DISABLED, NOTIFICATION_UNBOUND, ROOT_UNREADY, SMS_UNAUTHORIZED, HOOK_UNCONFIRMED, RULES_UNAVAILABLE, DEGRADED, SOURCES_DISABLED, NOTIFICATION_OBSERVING, OVERLAY_MISSING, PENDING_MODE, RUNNING }
internal data class CaptureHealth(
    val kind: CaptureHealthKind, val title: String, val detail: String,
    val actions: List<CaptureAction> = emptyList(),
) {
    val attention: Boolean get() = kind !in setOf(CaptureHealthKind.RUNNING, CaptureHealthKind.PENDING_MODE, CaptureHealthKind.NOTIFICATION_OBSERVING)
}

/** 通道列表。凭证只在设备真的具备 LSPosed 能力时出现，其余两条始终在。 */
internal enum class CaptureChannel { NOTIFICATION, SMS, HOOK, PAGE }

internal fun channelList(showsHook: Boolean): List<CaptureChannel> = buildList {
    add(CaptureChannel.NOTIFICATION)
    add(CaptureChannel.PAGE)
    if (showsHook) add(CaptureChannel.HOOK)
}

internal val CaptureHealthInput.pageReady: Boolean
    get() = pageChannelEnabled && accessibilityAuthorized && accessibilityBound
internal val CaptureHealthInput.notificationReady: Boolean
    get() = notificationsEnabled && notificationRulesAvailable && when (notificationPlan) {
        NotificationPath.LISTENER -> notificationAuthorized && notificationBound
        NotificationPath.DUMPSYS -> rootNotificationReady
        null -> false
    }
internal val CaptureHealthInput.notificationRulesAvailable: Boolean
    get() = ruleCapabilities?.let { capabilities ->
        val supported = capabilities.filter { it.endsWith("|NOTICE") }.map { it.substringBefore('|') }.toSet() +
            com.example.billkit.notification.NotificationParser.TEMPLATE_PACKAGES
        supported.any { selectedPackages == null || it in selectedPackages }
    } ?: true
internal val CaptureHealthInput.smsReady: Boolean
    get() = false // Legacy fields remain readable, but self has no SMS source.
/**
 * 凭证通道是否就绪。**不看模式**：模块跑在微信/支付宝进程里，宿主选哪种采集方式跟它无关。
 * 判据只有三条——通道开着、模块加载已确认、且至少一个已加载的目标有匹配规则。
 */
internal val CaptureHealthInput.lspReady: Boolean
    get() = hookEnabled && hookReady &&
        (ruleCapabilities == null || hookPackages.any { "$it|DATA" in ruleCapabilities })
/** Readiness is independent of presentation permissions and never inferred from the master switch. */
internal val CaptureHealthInput.recognitionReady: Boolean
    get() = enabled && (pageReady || notificationReady || smsReady || lspReady)

internal fun captureHealth(input: CaptureHealthInput): CaptureHealth = with(input) {
    // 凭证通道不需要选模式，所以只开了它也算「设置过」——不能一边采着一边要求用户去选模式。
    // 一条通道都没配过。以前这里要求用户先选「普通模式 / Root 模式」，现在没有这个选择了：
    // 需要的是把通知识别打开，所以直接指向自动记账页。
    if (mode == null && !notificationsEnabled && !hookEnabled) return CaptureHealth(CaptureHealthKind.MODE_REQUIRED,
        "尚未开启自动记账", "打开通知识别后，麦米会在收到微信、支付宝交易通知时生成草稿。",
        listOf(CaptureAction.OPEN_AUTOMATION))
    if (!enabled) return CaptureHealth(CaptureHealthKind.PAUSED, "自动记账已暂停",
        "已保留通道设置与推荐配置。", listOf(CaptureAction.RESUME))

    val issues = buildList {
        if (notificationsEnabled) {
            // 按实际实现报缺，不按模式：root 模式 + 已授权通知时，现在跑的是 listener，
            // 再拿「Root 未就绪」去催用户就是误报。
            if (notificationPlan == NotificationPath.DUMPSYS) {
                if (!rootNotificationReady) add(CaptureHealth(CaptureHealthKind.ROOT_UNREADY,
                    "尚未就绪 · Root 通知需处理", rootNotificationError ?: "正在检测通知读取状态，请稍候。",
                    listOf(CaptureAction.ROOT_STATUS)))
            } else when {
                // 还剩两种情况：跑的是 listener，或者这一刻压根没有实现（没授权也没 root）。
                // 两者要给的动作一样——先把通知使用权补上，那是唯一不需要 root 的路。
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
        if (hookEnabled && !hookReady) add(CaptureHealth(CaptureHealthKind.HOOK_UNCONFIRMED,
            "尚未确认应用模块加载", "请在 LSPosed 设置中检测微信、支付宝的模块加载状态。", listOf(CaptureAction.LSP)))
        if (ruleCapabilities != null && ((notificationsEnabled && !notificationRulesAvailable) ||
                (hookEnabled && hookReady && !lspReady))) {
            add(CaptureHealth(CaptureHealthKind.RULES_UNAVAILABLE,
                if (!ruleEngineReady) "本地规则尚未就绪" else "部分来源暂无匹配规则",
                if (!ruleEngineReady) "规则正在准备或加载失败，请查看规则文件状态。" else "已保留来源设置；可在应用与通道查看当前支持范围。",
                listOf(CaptureAction.SUPPORTED_APPS)))
        }
    }
    if (!recognitionReady) return issues.firstOrNull() ?: CaptureHealth(CaptureHealthKind.SOURCES_DISABLED,
        "尚未就绪 · 识别来源已关闭", "请在识别设置或更多设置中开启至少一种识别方式。", listOf(CaptureAction.OPEN_AUTOMATION))

    val sources = buildList {
        if (notificationReady) add(if (notificationPlan == NotificationPath.DUMPSYS) "Root 通知识别" else "通知识别")
        if (pageReady) add("页面辅助")
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
enum class CaptureSettingsDestination { AUTOMATION, SETUP, ROOT_STATUS, SUPPORTED_APPS, SMS }
