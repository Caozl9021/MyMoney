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
    NO_TEMPLATE_FOR_APP,
    PAGE_DISABLED,
    PAGE_MODE_UNSET,
    PAGE_ROOT_STOPPED,
    PAGE_UNAUTHORIZED,
    PAGE_UNBOUND,
}

internal data class ChannelVerdict(val active: Boolean, val reason: ChannelReason, val detail: String)

internal data class AppChannelState(
    val packageName: String,
    val whitelisted: Boolean,
    val hasTemplate: Boolean,
    val notification: ChannelVerdict,
    val page: ChannelVerdict,
) {
    /** 任一通道可用即视为该应用能从某条路径识别到账单。 */
    val anyActive: Boolean get() = notification.active || page.active

    /** 需要用户处理的原因（白名单外不算缺陷，是用户自己没选）。 */
    val needsAttention: Boolean get() = whitelisted && !anyActive
}

/**
 * 判定优先级固定为：未在白名单 → 全局暂停 → 通知（未启用/未授权/未绑定/无模板）→ 页面（未启用/未选模式/Root 未运行/未授权/未绑定）。
 *
 * 顺序本身就是语义：全局暂停时不该显示成"权限丢了"，否则用户会去重开一个本来没关的开关。
 */
internal fun appChannelState(
    input: CaptureHealthInput,
    packageName: String,
    whitelist: Set<String>,
    templatePackages: Set<String> = NotificationParser.TEMPLATE_PACKAGES,
): AppChannelState {
    val whitelisted = packageName in whitelist
    val hasTemplate = packageName in templatePackages
    return AppChannelState(
        packageName = packageName,
        whitelisted = whitelisted,
        hasTemplate = hasTemplate,
        notification = notificationVerdict(input, whitelisted, hasTemplate),
        page = pageVerdict(input, whitelisted),
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

private fun notificationVerdict(input: CaptureHealthInput, whitelisted: Boolean, hasTemplate: Boolean): ChannelVerdict = when {
    !whitelisted -> ChannelVerdict(false, ChannelReason.NOT_WHITELISTED, "未选择该应用，不会读取它的通知")
    !input.enabled -> ChannelVerdict(false, ChannelReason.GLOBAL_PAUSED, "自动识别已暂停，恢复后继续")
    !input.notificationsEnabled -> ChannelVerdict(false, ChannelReason.NOTIFICATION_DISABLED, "通知监听未启用")
    !input.notificationAuthorized -> ChannelVerdict(false, ChannelReason.NOTIFICATION_UNAUTHORIZED,
        "通知使用权未授予。它与 App 自身发送通知的权限不同")
    !input.notificationBound -> ChannelVerdict(false, ChannelReason.NOTIFICATION_UNBOUND,
        "系统已授权，但监听服务尚未连接，可尝试重新连接")
    !hasTemplate -> ChannelVerdict(false, ChannelReason.NO_TEMPLATE_FOR_APP,
        "该应用尚无已验证的交易通知模板，通知到了也不会自动记账")
    else -> ChannelVerdict(true, ChannelReason.ACTIVE, "通知监听已连接，命中已验证模板时识别")
}

private fun pageVerdict(input: CaptureHealthInput, whitelisted: Boolean): ChannelVerdict = when {
    !whitelisted -> ChannelVerdict(false, ChannelReason.NOT_WHITELISTED, "未选择该应用，不会读取它的页面")
    !input.enabled -> ChannelVerdict(false, ChannelReason.GLOBAL_PAUSED, "自动识别已暂停，恢复后继续")
    !input.pageFallbackEnabled -> ChannelVerdict(false, ChannelReason.PAGE_DISABLED, "页面辅助未启用")
    input.mode == null -> ChannelVerdict(false, ChannelReason.PAGE_MODE_UNSET, "页面辅助尚未选择 Root 或无障碍")
    input.mode == CaptureMode.ROOT && !input.rootRunning ->
        ChannelVerdict(false, ChannelReason.PAGE_ROOT_STOPPED, "Root 识别未运行，请查看授权与启动状态")
    input.mode == CaptureMode.ACCESSIBILITY && !input.accessibilityAuthorized ->
        ChannelVerdict(false, ChannelReason.PAGE_UNAUTHORIZED, "无障碍服务未授权，请在系统设置中开启 AutoBook")
    input.mode == CaptureMode.ACCESSIBILITY && !input.accessibilityBound ->
        ChannelVerdict(false, ChannelReason.PAGE_UNBOUND, "无障碍已授权但服务未连接，请在系统设置中重新开启 AutoBook")
    else -> ChannelVerdict(true, ChannelReason.ACTIVE,
        if (input.mode == CaptureMode.ROOT) "Root 识别运行中" else "无障碍页面识别可用")
}
