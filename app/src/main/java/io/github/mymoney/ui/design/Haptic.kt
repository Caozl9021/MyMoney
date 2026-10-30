package io.github.mymoney.ui.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 触感分级。
 *
 * 之前全项目只有两档（`TextHandleMove` 和 `LongPress`），而且 `TextHandleMove` 是给
 * "拖动文本光标"用的 —— 拿它当"点了一下"的反馈，力度和语义都不对。
 *
 * 这里按语义分四档，用 `android.view.HapticFeedbackConstants` 而不是 Compose 的
 * `HapticFeedbackType`：后者在 compose-ui 1.7 才补了 CONFIRM/REJECT 这些常量，
 * 而且各家 ROM 对 `HapticFeedbackType` 的映射差异比系统常量更大。
 *
 * 所有调用都做了版本判断 —— `minSdk 26` 上 `CONFIRM`/`REJECT` 不存在，
 * 降级到 `LONG_PRESS`/`CLOCK_TICK`，不会因为 OnePlus/小米的旧机器崩掉。
 */
class AbHaptic(private val view: View) {

    /** 轻点、切换 tab、选中 chip：几乎察觉不到，只是"摸到实体" */
    fun tick() = play(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.KEYBOARD_TAP)

    /** 打开/关闭 sheet、进入子页：比 tick 稍重 */
    fun select() = play(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.CLOCK_TICK)

    /** 记一笔、保存成功、覆盖恢复确认：明确的"成了" */
    fun confirm() = play(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.LONG_PRESS)

    /** 忽略/丢弃/校验失败：短促的"没成"，比 confirm 更硬 */
    fun warn() = play(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.LONG_PRESS)

    private fun play(primary: Int, fallback: Int) {
        // performHapticFeedback 自己会处理"系统设置里关掉了触感"的情况
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) primary else fallback)
    }
}

/** 在 composable 里取一个可复用的触感入口（同一个 View 只建一次） */
@Composable
fun rememberAbHaptic(): AbHaptic {
    val view = LocalView.current
    return remember(view) { AbHaptic(view) }
}
