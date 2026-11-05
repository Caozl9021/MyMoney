package com.example.billkit.visual

/** Safety evidence must exist in nodes before pixels are requested. Activity/package alone is insufficient. */
object VisualCaptureGate {
    private val forbidden = Regex("密码|验证码|身份验证|指纹验证|人脸验证|面容验证|支付失败|付款失败|待支付|待付款|未支付|未付款|支付中|付款中|交易关闭|确认付款|立即付款")
    fun allows(packageName: String, items: List<String>, truncated: Boolean): Boolean {
        if (truncated || packageName !in setOf("com.tencent.mm", "com.eg.android.AlipayGphone") ||
            items.size > 400 || items.sumOf { it.length.toLong() } > 32768) return false
        val normalized = items.map { it.filterNot(Char::isWhitespace) }
        if (forbidden.containsMatchIn(normalized.joinToString("\n"))) return false
        return normalized.any { it in setOf("支付成功", "付款成功", "交易成功", "转账成功", "已支付", "已付款") }
    }
}
