package io.github.mymoney.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.lsp.LsposedManagerLauncher

/**
 * 这台设备**具备**什么能力。
 *
 * 阶段 4 起，界面按它裁剪：没有的能力连入口都不画，而不是画一个灰的点不动。
 * 灰入口的问题是它仍然占着版面、仍然写着「Root」，而绝大多数用户既没有也不需要 root，
 * 每次进设置都要先绕过一堆自己用不上的东西。
 */
internal data class DeviceCapabilities(
    val rootGranted: Boolean = false,
    val lspInstalled: Boolean = false,
)

/**
 * 读一次设备能力。
 *
 * root 探测走 [CaptureModeManager] 的缓存（`su` 是外部进程调用，不能在主线程做，也不该每次
 * 冷启动都问一遍）；包可见性只在进入时查一次，不放在重组路径上。
 *
 * 结论没出来之前按「没有」处理——宁可晚一帧出现，也不要先画出来再抽掉。
 */
@Composable
internal fun rememberDeviceCapabilities(): DeviceCapabilities {
    val context = LocalContext.current.applicationContext
    val root by CaptureModeManager.rootCapability.collectAsStateWithLifecycle()
    val lspDaemon by io.github.mymoney.lsp.LsposedPresence.detected.collectAsStateWithLifecycle()
    val managerInstalled = remember(context) { LsposedManagerLauncher.managerInstalled(context) }
    LaunchedEffect(context) { CaptureModeManager.ensureRootProbed(context) }
    // root 结论出来之后再问守护进程：没 root 问不出来，也只是白弹一次授权框。
    LaunchedEffect(root) { io.github.mymoney.lsp.LsposedPresence.probeIfRooted() }
    return DeviceCapabilities(rootGranted = root == true, lspInstalled = lspAvailable(managerInstalled, lspDaemon))
}

/**
 * 本机是否具备 LSPosed 能力。两条证据任一成立即可：
 *
 * - 管理器可见（[LsposedManagerLauncher.managerInstalled]，四个已知包名）；
 * - 守护进程在跑（[io.github.mymoney.lsp.LsposedPresence]，`pidof lspd`）。
 *
 * 第二条是为隐藏式管理器准备的：真机上模块在微信里跑着，管理器却因为包名对不上而看不见，
 * 凭证采集整行被藏掉，用户没有入口能打开它。
 */
internal fun lspAvailable(managerInstalled: Boolean, daemonRunning: Boolean?): Boolean =
    managerInstalled || daemonRunning == true

/** 能力从无到有时的提示。用户关掉之后不再提。 */
internal enum class CapabilityNotice { ROOT, LSP }

internal const val NOTICE_ROOT = "root"
internal const val NOTICE_LSP = "lsp"

/**
 * 要不要提示。**只提示一次**：`dismissed` 是用户点过「知道了」之后落盘的集合。
 *
 * 提示只在这个能力**真能多做一件事**时出现，否则就是噪音：
 * - LSPosed：凭证通道还关着。已经开着的人不需要被告知「你有 LSPosed」。
 * - Root：通知使用权没给，Root 读通知正好是那条兜底。通知监听正常工作时
 *   root 没有增量，说「你可以用 Root 读通知」只会让人以为自己少配了什么。
 *
 * LSPosed 排在 root 前面：它更具体，也更是「现在就能多做一件事」；两个都成立时先说它，
 * 用户关掉后下一次自然会看到 root 那条。
 */
internal fun capabilityNotice(capabilities: DeviceCapabilities, hookEnabled: Boolean,
    notificationFallbackUseful: Boolean, dismissed: Set<String>,
    preset: io.github.mymoney.data.CaptureMode?): CapabilityNotice? = when {
    // 「普通记账」下连入口都不画，就别再弹一条「检测到 LSPosed」去提示用户少了什么。
    capabilities.lspInstalled && !hookEnabled &&
        preset != io.github.mymoney.data.CaptureMode.ACCESSIBILITY && NOTICE_LSP !in dismissed -> CapabilityNotice.LSP
    capabilities.rootGranted && notificationFallbackUseful && NOTICE_ROOT !in dismissed -> CapabilityNotice.ROOT
    else -> null
}

internal fun CapabilityNotice.title(): String = when (this) {
    CapabilityNotice.LSP -> "检测到 LSPosed"
    CapabilityNotice.ROOT -> "检测到 Root 权限"
}

internal fun CapabilityNotice.detail(): String = when (this) {
    CapabilityNotice.LSP -> "可以去启用凭证采集：它直接读微信、支付宝的交易凭证，比通知更准。"
    CapabilityNotice.ROOT -> "还没给通知使用权。授予之前，麦米先用 Root 读取通知栏兜底。"
}

internal fun CapabilityNotice.noticeKey(): String = when (this) {
    CapabilityNotice.LSP -> NOTICE_LSP
    CapabilityNotice.ROOT -> NOTICE_ROOT
}
