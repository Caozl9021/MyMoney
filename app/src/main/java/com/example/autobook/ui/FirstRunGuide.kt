package com.example.autobook.ui

import com.example.autobook.data.CaptureMode

/** Completion derives only from measured permission/service and actual recognition evidence. */
internal object FirstRunGuide {
    data class Step(val label: String, val complete: Boolean)

    fun steps(mode: CaptureMode?, enabled: Boolean, rootRunning: Boolean,
              accessibilityBound: Boolean, overlayAllowed: Boolean, recognized: Boolean): List<Step> = listOf(
        Step(when (mode) {
            CaptureMode.ROOT -> "开启识别并授予 Root"
            CaptureMode.ACCESSIBILITY -> "开启识别并连接无障碍服务"
            null -> "选择 Root 或无障碍模式"
        }, enabled && when (mode) {
            CaptureMode.ROOT -> rootRunning
            CaptureMode.ACCESSIBILITY -> accessibilityBound
            null -> false
        }),
        Step("允许悬浮窗以显示确认卡", overlayAllowed),
        Step("在「识别与规则」里确认应用，识别一笔成功账单", recognized),
    )

    fun hasRecognizedSource(source: String): Boolean =
        source.isNotBlank() && !source.startsWith("com.example.autobook.manual.")
}
