package io.github.mymoney.ui

import io.github.mymoney.data.CaptureMode

/**
 * LSPosed 入口的显隐判据。
 *
 * 采集本身仍然不看模式（见 `CaptureModeState.hookActive`）——通不通道只看它自己的开关。
 * 但**入口**是另一回事：2026-10-31 用户明确要求「普通记账时隐藏」，因为那个预设就是给
 * 只想用通知 + 页面辅助的人准备的一键配置，多一行需要 LSPosed 的高级选项反而像没配好。
 *
 * 两条例外必须保住：
 * - 通道已经打开时永远显示——否则用户没有地方关掉它，也没有地方重新检测模块；
 * - 还没选过预设（`null`）时按能力显示，否则首次配置的用户永远发现不了这条通道。
 *
 * 拆成纯函数是刻意的：`lspInstalled` 的取值需要 `Context`，而这条判断本身值得单测。
 */
internal fun showsLspSettings(hookEnabled: Boolean, lspInstalled: Boolean, preset: CaptureMode?): Boolean =
    hookEnabled || (lspInstalled && preset != CaptureMode.ACCESSIBILITY)
