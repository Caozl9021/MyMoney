package com.example.autobook.ui

import com.example.autobook.data.CaptureMode
import com.example.billkit.notification.NotificationParser

/**
 * 单个应用在每条采集通道上的实际生效结论（纯展示模型，不持有 Compose 或系统状态）。
 *
 * 判定只读 [CaptureHealthInput]，与 [captureHealth] 共用同一组布尔量。
 * 界面里另查系统权限必然产生第二套条件，两处结论迟早不一致——
 * 而"两处结论不一致"正是统一管理台要消除的问题。
 */
internal enum class ChannelReason {
    ACTIVE,
    NOT_WHITELISTED,
    GLOBAL_PAUSED,
    NOTIFICATION_DISABLED,
    NOTIFICATION_UNAUTHORIZED,
    NOTIFICATION_UNBOUND,
    ROOT_UNREADY,
    HOOK_UNCONFIRMED,
    PAGE_DISABLED,
    PAGE_UNAUTHORIZED,
    PAGE_UNBOUND,
}

internal data class ChannelVerdict(val active: Boolean, val reason: ChannelReason, val detail: String)

internal data class AppChannelState(
    val packageName: String,
    val whitelisted: Boolean,
    val hasTemplate: Boolean,
    val notification: ChannelVerdict,
    /**
     * 页面通道结论。当前模式下没有页面通道（root）时为 null，
     * 界面据此**不渲染页面行**——不显示比显示一个"已停用"更清楚。
     */
    val page: ChannelVerdict?,
    val hook: ChannelVerdict? = null,
) {
    /** 任一通道可用即视为该应用能从某条路径识别到账单。 */
    val anyActive: Boolean get() = notification.active || page?.active == true || hook?.active == true

    /** 需要用户处理的原因（白名单外不算缺陷，是用户自己没选）。 */
    val needsAttention: Boolean get() = whitelisted && !anyActive

    /** 该应用在当前模式下实际有意义的通道数，用于列表按行渲染。 */
    val channelCount: Int get() = 1 + (if (page == null) 0 else 1) + (if (hook == null) 0 else 1)
}

/**
 * 判定优先级固定为：未在白名单 → 全局暂停 → 通知（未启用/未授权/未绑定）→ 页面（未启用/未授权/未绑定）。
 *
 * 顺序本身就是语义：全局暂停时不该显示成"权限丢了"，否则用户会去重开一个本来没关的开关。
 */
internal fun appChannelState(
    input: CaptureHealthInput,
    packageName: String,
    whitelist: Set<String>,
    /** 保留参数以便调用方逐步迁移；163 条 JS 规则已不依赖白名单模板。 */
    @Suppress("UNUSED_PARAMETER") templatePackages: Set<String> = NotificationParser.TEMPLATE_PACKAGES,
): AppChannelState {
    val whitelisted = packageName in whitelist
    return AppChannelState(
        packageName = packageName,
        whitelisted = whitelisted,
        hasTemplate = packageName in NotificationParser.TEMPLATE_PACKAGES,
        notification = notificationVerdict(input, whitelisted),
        page = pageVerdict(input, whitelisted),
        hook = hookVerdict(input, packageName, whitelisted),
    )
}

internal fun appChannelStates(
    input: CaptureHealthInput,
    packages: Collection<String>,
    whitelist: Set<String>,
    templatePackages: Set<String> = NotificationParser.TEMPLATE_PACKAGES,
): List<AppChannelState> = packages.distinct()
    .map { appChannelState(input, it, whitelist, templatePackages) }
    // 有问题的排前面；同组保持调用方给的顺序（预设清单顺序），
    // 不能再按包名排序——那会把 com.dianping.v1、cn.gov.pbc.dcep 顶到最前面。
    .sortedBy { !it.needsAttention }

private fun notificationVerdict(input: CaptureHealthInput, whitelisted: Boolean): ChannelVerdict = when {
    !whitelisted -> ChannelVerdict(false, ChannelReason.NOT_WHITELISTED, "未选择该应用，不会读取它的通知")
    !input.enabled -> ChannelVerdict(false, ChannelReason.GLOBAL_PAUSED, "自动识别已暂停，恢复后继续")
    !input.notificationsEnabled -> ChannelVerdict(false, ChannelReason.NOTIFICATION_DISABLED, "通知通道未启用")
    // root 模式用 dumpsys 读通知栏，不需要通知使用权，也不依赖 Listener 绑定。
    input.mode == CaptureMode.ROOT && !input.rootNotificationReady -> ChannelVerdict(false, ChannelReason.ROOT_UNREADY,
        input.rootNotificationError ?: "Root 通知读取尚未就绪，请查看 Root 状态")
    input.mode == CaptureMode.ROOT -> ChannelVerdict(true, ChannelReason.ACTIVE, "通知读取可用，命中本地规则时识别")
    !input.notificationAuthorized -> ChannelVerdict(false, ChannelReason.NOTIFICATION_UNAUTHORIZED,
        "通知使用权未授予。它与 App 自身发送通知的权限不同")
    !input.notificationBound -> ChannelVerdict(false, ChannelReason.NOTIFICATION_UNBOUND,
        "系统已授权，但监听服务尚未连接，可尝试重新连接")
    else -> ChannelVerdict(true, ChannelReason.ACTIVE, "通知监听已连接，命中本地规则时识别")
}

/**
 * 页面通道结论。
 *
 * root 模式返回 null：页面通道已删除，界面不该出现这一行。
 * 之前这里返回一个 `active=false` 的"已停用"结论，界面就会给每个应用多画一行
 * 永远灰着的页面通道——用户看到的是"功能坏了"，实际是这个模式根本没有这功能。
 */
private fun pageVerdict(input: CaptureHealthInput, whitelisted: Boolean): ChannelVerdict? {
    if (input.mode != CaptureMode.ACCESSIBILITY) return null
    return when {
        !whitelisted -> ChannelVerdict(false, ChannelReason.NOT_WHITELISTED, "未选择该应用，不会读取它的页面")
        !input.enabled -> ChannelVerdict(false, ChannelReason.GLOBAL_PAUSED, "自动识别已暂停，恢复后继续")
        !input.pageEnabled -> ChannelVerdict(false, ChannelReason.PAGE_DISABLED, "页面辅助未启用")
        !input.accessibilityAuthorized ->
            ChannelVerdict(false, ChannelReason.PAGE_UNAUTHORIZED, "无障碍服务未授权，请在系统设置中开启 AutoBook")
        !input.accessibilityBound ->
            ChannelVerdict(false, ChannelReason.PAGE_UNBOUND, "无障碍已授权但服务未连接，请在系统设置中重新开启 AutoBook")
        else -> ChannelVerdict(true, ChannelReason.ACTIVE, "无障碍页面识别可用")
    }
}

/** Only the target app's authenticated load report establishes this path; system reports are independent. */
private fun hookVerdict(input: CaptureHealthInput, packageName: String, whitelisted: Boolean): ChannelVerdict? {
    if (input.mode != CaptureMode.ROOT || !input.hookEnabled ||
        packageName !in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) return null
    return when {
        !whitelisted -> ChannelVerdict(false, ChannelReason.NOT_WHITELISTED, "未选择该应用，不接收它的交易凭证")
        !input.enabled -> ChannelVerdict(false, ChannelReason.GLOBAL_PAUSED, "自动记账已暂停，恢复后继续")
        packageName !in input.hookPackages -> ChannelVerdict(false, ChannelReason.HOOK_UNCONFIRMED, "尚未确认该应用模块加载，请进入 LSPosed 设置检测")
        else -> ChannelVerdict(true, ChannelReason.ACTIVE, "已确认该应用模块加载，交易命中规则后生成草稿")
    }
}
