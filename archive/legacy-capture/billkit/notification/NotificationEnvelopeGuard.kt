package com.example.billkit.notification

/** 通知信封的共同准入；模板和规则必须在这层之后运行。 */
object NotificationEnvelopeGuard {
    fun rejection(input: NotificationObservation): String? = when {
        input.packageName !in NotificationParser.SUPPORTED_PACKAGES -> "unsupported-package"
        input.isReconnectBaseline -> "reconnect-baseline"
        input.isGroupSummary -> "group-summary"
        input.isMessagingStyle || input.category == "msg" -> "chat-notification"
        input.isOngoing -> "ongoing-notification"
        input.truncated -> "truncated-content"
        input.postTime <= 0 || input.receivedAt <= 0 -> "missing-observation-time"
        (input.bigText?.takeIf(String::isNotBlank) ?: input.text).isNullOrBlank() -> "empty-or-hidden-content"
        else -> null
    }

    /** 只允许规则处理尚未适配的内容；明确拒绝、模糊金额等不能由兜底重新放行。 */
    fun mayTryRule(input: NotificationObservation, result: NotificationParseResult): Boolean =
        rejection(input) == null && result is NotificationParseResult.NeedsEvidence &&
            result.reason in setOf("unverified-template", "missing-explicit-success", "non-expense-transaction")
}
