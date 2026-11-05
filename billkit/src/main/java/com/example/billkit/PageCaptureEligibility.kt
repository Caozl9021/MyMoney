package com.example.billkit

/** Automatic PAGE admission, deliberately separate from read-only rule parsing. */
object PageCaptureEligibility {
    enum class Scene { DETAIL, PAYMENT_RESULT, OTHER, UNKNOWN }
    data class Decision(val scene: Scene, val reason: String) {
        val allowed: Boolean get() = scene == Scene.DETAIL
    }
    private val titles = setOf("交易详情", "账单详情", "收支详情", "余额明细详情", "转账详情", "收款详情", "红包详情")
    private val timeLabels = listOf("支付时间", "付款时间", "交易时间", "转账时间", "收款时间", "退款时间", "创建时间", "扣款时间")
    private val orderLabels = listOf("交易单号", "转账单号", "商户单号", "订单号", "交易号", "商户订单号", "退款单号")
    private val methodLabels = listOf("支付方式", "付款方式", "交易方式", "扣款方式", "转入方式", "转出方式")
    private fun List<String>.hasLabel(labels: List<String>) = any { row -> labels.any { row == it || row.startsWith("$it：") || row.startsWith("$it:") } }

    fun evaluate(snapshot: UiSnapshot): Decision {
        if (snapshot.packageName !in setOf(Packages.WECHAT, Packages.ALIPAY)) return Decision(Scene.OTHER, "unsupported-package")
        if (snapshot.truncated) return Decision(Scene.UNKNOWN, "incomplete-page")
        val rows = PaymentResultParser.normalize(snapshot.items).map(String::trim).filter(String::isNotEmpty)
        if (rows.size < 3) return Decision(Scene.UNKNOWN, "insufficient-page")
        if (!PageClassifier().permitsCustomRule(rows)) return Decision(Scene.OTHER, "not-a-detail")
        val title = rows.any { it in titles }
        val time = rows.hasLabel(timeLabels)
        val order = rows.hasLabel(orderLabels)
        val method = rows.hasLabel(methodLabels)
        val status = rows.any { it == "当前状态" || it.startsWith("当前状态：") }
        // A detail title alone can also be a navigation link on a payment result screen.
        val titledDetail = title && ((time && (method || status || order)) || (order && status))
        val structuredDetail = order && time && (status || method) &&
            rows.any { it == "账单服务" || it == "交易服务" || it.contains("本服务由财付通提供") || it == "账单管理" }
        val resultContainer = snapshot.activityClass?.contains("MspContainerActivity") == true
        if (resultContainer && (titledDetail || structuredDetail)) {
            return Decision(Scene.UNKNOWN, "mixed-result-detail")
        }
        if (titledDetail || structuredDetail) return Decision(Scene.DETAIL, "transaction-detail")
        val result = rows.any { it in setOf("支付成功", "付款成功", "转账成功", "支付完成", "付款完成") } ||
            resultContainer
        return if (result) Decision(Scene.PAYMENT_RESULT, "payment-result-not-page-capture")
            else Decision(Scene.UNKNOWN, "detail-not-established")
    }
}
