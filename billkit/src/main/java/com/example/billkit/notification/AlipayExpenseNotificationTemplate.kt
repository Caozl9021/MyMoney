package com.example.billkit.notification

import java.math.BigDecimal
import java.security.MessageDigest

/** Alipay's structured expense reminder, admitted only for the observed channel metadata.
 * Evidence contains exported format/metadata; the complete reward suffix is a user screenshot
 * transcription, not a raw notification callback dump. No merchant, order or payment time was shown.
 *
 * matches/extract validate content only. The parser MUST also require matchesMetadata before
 * accepting a Candidate; a successful content fixture is not an end-to-end metadata verification.
 */
class AlipayExpenseNotificationTemplate : VerifiedExpenseNotificationTemplate {
    override val id = "alipay-trade-expense-v1"
    override val evidenceReference = "截图/MyMoney-notification-1793002244077.json"
    override val packageName = ALIPAY_PACKAGE
    // The raw channel ID was not exported. Empty legacy fields are not wildcard matches.
    override val channelId = ""
    override val platformTemplate = ""
    override val structuredExpense = true

    override fun matchesMetadata(input: NotificationObservation): Boolean {
        val actualChannel = input.channelId?.takeIf { it.isNotBlank() } ?: return false
        return matchesMetadataSignature(input.packageName, sha256(actualChannel), input.category, input.template)
    }

    /** Also accepts exported hashes for evidence verification; production hashes the raw callback channel. */
    fun matchesMetadataSignature(packageName: String, channelIdHash: String?, category: String?, platformTemplate: String?): Boolean =
        packageName == ALIPAY_PACKAGE && channelIdHash == OBSERVED_CHANNEL_SHA256 &&
            category == null && platformTemplate == null

    override fun matches(observation: NotificationObservation): Boolean = extract(observation) != null

    override fun extract(observation: NotificationObservation): NotificationExpenseFields? {
        if (observation.title?.trim() != "交易提醒") return null
        val body = (observation.bigText?.takeIf { it.isNotBlank() } ?: observation.text)?.trim() ?: return null
        if (hardNegative.containsMatchIn(body)) return null
        val matched = expense.matchEntire(body) ?: return null
        val amount = matched.groupValues[1]
        val tail = matched.groupValues[2]
        // Reward amounts are permitted after the expense sentence, e.g. "领2元小荷包支付红包".
        // A second transaction or another financial direction is never treated as an advertisement.
        if (conflictingTail.containsMatchIn(tail)) return null
        val cents = runCatching { BigDecimal(amount).movePointRight(2).longValueExact() }.getOrNull()
            ?.takeIf { it > 0L } ?: return null
        // cents is validated here as well as in the parser; amount keeps its exact visible decimal.
        if (BigDecimal.valueOf(cents, 2).compareTo(BigDecimal(amount)) != 0) return null
        return NotificationExpenseFields(amount = amount, currency = "CNY", merchant = null,
            orderId = null, transactionTimeMillis = null, method = null)
    }

    companion object {
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        const val OBSERVED_CHANNEL_SHA256 = "be22bef6e8da604af0e7e083445124f53a894c5480b078fce74861a69f2c7907"
        private val expense = Regex(
            "^你有一笔\\s*((?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?)\\s*元的支出(?=$|[\\s，,。.!！；;：:、])([\\s\\S]*)$",
        )
        private val hardNegative = Regex(
            "失败|取消|撤销|待支付|待付款|待扣款|未支付|未付款|未扣款|未完成|待完成|待确认|尚未|支付中|付款中|扣款中|处理中|进行中|冻结|验证码",
        )
        private val conflictingTail = Regex("支出|消费|扣款|付款|收入|退款|转账|收款|你有一笔|支付\\s*[¥￥]?\\s*[0-9]+(?:\\.[0-9]+)?\\s*元")
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
