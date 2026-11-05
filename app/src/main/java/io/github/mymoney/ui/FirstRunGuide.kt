package io.github.mymoney.ui

/** Configuration and first recognition are independent milestones. */
internal object FirstRunGuide {
    data class Step(val label: String, val complete: Boolean)

    fun configurationComplete(input: CaptureHealthInput): Boolean =
        input.recognitionReady

    fun steps(input: CaptureHealthInput, recognized: Boolean): List<Step> = listOf(
        Step("完成识别设置", input.enabled && input.recognitionReady),
        Step("设置确认卡（可选）", !input.autoConfirm || input.overlayAllowed),
        Step("识别第一笔账单", recognized),
    )

    fun hasRecognizedSource(source: String): Boolean =
        source.isNotBlank() && !source.startsWith("io.github.mymoney.manual.")
}
