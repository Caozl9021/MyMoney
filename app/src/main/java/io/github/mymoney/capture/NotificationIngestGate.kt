package io.github.mymoney.capture

import io.github.mymoney.data.CaptureModeState

/**
 * 通知观察进入识别流程前的闸门。
 *
 * 只判「开关 + 范围 + 配置令牌 + 授权」。**不看采集预设**，也不看「此刻谁在读」。
 *
 * 曾经这里有一条 `captureMode != ROOT`：那是「ROOT 模式下通知由 dumpsys 读、listener 的输入
 * 一律扔掉」的年代留下的。阶段 3 把实现改成按可用性判之后，它成了漏网的旧绑定——
 * 选了 ROOT 预设、通知使用权也在、实际正由 listener 读的机器，观察全被这一条默默丢掉，
 * 账上一笔不出，而健康页显示一切正常。
 *
 * 两套实现短暂重叠时不靠这里挡：跨来源合并按既有语义去重（见 `ChannelSelection` 的说明）。
 */
internal fun notificationIngestAllowed(
    state: CaptureModeState,
    pkg: String,
    whitelist: Set<String>,
    tokenMatches: Boolean,
    listenerAuthorized: Boolean,
): Boolean = state.enabled && state.notificationsEnabled && pkg in whitelist &&
    tokenMatches && listenerAuthorized
