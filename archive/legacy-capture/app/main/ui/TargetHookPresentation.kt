package io.github.mymoney.ui

import io.github.mymoney.lsp.HookLoadState
import io.github.mymoney.lsp.HookTargetStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Load proof and the last received transaction are independent facts. */
internal fun targetHookSubtitle(status: HookTargetStatus, enabled: Boolean, hookEnabled: Boolean): String {
    val receipt = status.lastReceiptAt.takeIf { it > 0 }?.let {
        "最近凭证 " + DateTimeFormatter.ofPattern("MM/dd HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    val load = when (status.state) {
        HookLoadState.NOT_CHECKED -> "尚未检测模块加载"
        HookLoadState.CHECKING -> "正在确认模块加载…"
        HookLoadState.NO_REPLY -> "本次尚未确认加载 · 请重新打开应用后检测"
        HookLoadState.OLD_VERSION -> if (status.version == "unknown") "模块版本待确认 · 请重新打开应用"
            else "已加载旧版模块 · 请重新打开应用"
        HookLoadState.UNSUPPORTED -> "模块已加载 · 采集方法尚未就绪"
        HookLoadState.READY -> when {
            !hookEnabled -> "模块已加载 · 采集已关闭"
            !enabled -> "模块已加载 · 自动记账已暂停"
            receipt == null -> "模块已加载 · 等待交易凭证"
            else -> "模块已加载"
        }
    }
    return if (receipt == null) load else "$load\n$receipt"
}
