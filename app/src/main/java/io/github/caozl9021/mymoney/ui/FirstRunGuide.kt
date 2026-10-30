package io.github.caozl9021.mymoney.ui

/** Configuration and first recognition are independent milestones. */
internal object FirstRunGuide {
    data class Step(val label: String, val complete: Boolean)

    fun configurationComplete(input: CaptureHealthInput): Boolean =
        input.recognitionReady && (!input.autoConfirm || input.overlayAllowed)

    fun steps(input: CaptureHealthInput, recognized: Boolean): List<Step> = listOf(
        Step("完成识别设置", input.enabled && input.recognitionReady),
        Step("允许悬浮确认卡", !input.autoConfirm || input.overlayAllowed),
        Step("识别第一笔账单", recognized),
    )

    fun hasRecognizedSource(source: String): Boolean =
        source.isNotBlank() && !source.startsWith("io.github.caozl9021.mymoney.manual.")
}
