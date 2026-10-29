package com.example.billkit.notification

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.math.BigDecimal

/** Only templates backed by reviewed real notification evidence may be registered in production.
 * There is intentionally no user-facing "trust keywords" switch.
 */
interface VerifiedExpenseNotificationTemplate {
    val id: String
    val evidenceReference: String
    val packageName: String
    val channelId: String
    val platformTemplate: String
    val structuredExpense: Boolean get() = false
    fun matchesMetadata(input: NotificationObservation): Boolean = packageName == input.packageName &&
        channelId.isNotBlank() && channelId == input.channelId &&
        platformTemplate.isNotBlank() && platformTemplate == input.template
    fun matches(observation: NotificationObservation): Boolean
    fun extract(observation: NotificationObservation): NotificationExpenseFields?
}

data class NotificationExpenseFields(
    val amount: String,
    val currency: String = "CNY",
    val merchant: String? = null,
    /** Full ID extracted from the verified payload; canonicalized with the page identity codec. */
    val orderId: String? = null,
    val transactionTimeMillis: Long? = null,
    val method: String? = null,
)

sealed interface NotificationParseResult {
    data class Candidate(val draft: BillDraft, val templateId: String) : NotificationParseResult
    data class Ignored(val reason: String) : NotificationParseResult
    data class NeedsEvidence(val reason: String) : NotificationParseResult
}

/** Pure parser: envelope guards apply to all templates; structured expense semantics are sample-bound. */
class NotificationParser(private val templates: List<VerifiedExpenseNotificationTemplate> = PRODUCTION_TEMPLATES) {
    fun parse(input: NotificationObservation): NotificationParseResult {
        NotificationEnvelopeGuard.rejection(input)?.let { reason ->
            return if (reason in setOf("truncated-content", "missing-observation-time", "empty-or-hidden-content")) {
                NotificationParseResult.NeedsEvidence(reason)
            } else NotificationParseResult.Ignored(reason)
        }
        val body = input.bigText?.takeIf { it.isNotBlank() } ?: input.text.orEmpty()
        val content = listOfNotNull(input.title, body).joinToString("\n")
        if (content.isBlank()) return NotificationParseResult.NeedsEvidence("empty-or-hidden-content")
        val eligible = templates.filter { it.evidenceReference.isNotBlank() && it.id.isNotBlank() &&
            it.packageName == input.packageName && it.matchesMetadata(input) && it.matches(input) }
        val structured = eligible.filter { it.structuredExpense }
        // A reviewed structured sentence identifies the transaction amount before any reward suffix.
        // All other templates retain the conservative explicit-success and single-amount gates.
        val template: VerifiedExpenseNotificationTemplate
        val amounts: List<BigDecimal>
        if (structured.isNotEmpty()) {
            if (structured.size != 1) return NotificationParseResult.NeedsEvidence("ambiguous-template")
            template = structured.single()
            amounts = currencyAmount.find(body)?.let {
                listOfNotNull(it.groupValues[1].ifEmpty { it.groupValues[2] }.toBigDecimalOrNull())
            }.orEmpty()
        } else {
            if (hardNegative.containsMatchIn(content)) return NotificationParseResult.Ignored("not-successful-expense")
            if (otherDirection.containsMatchIn(content)) {
                val visible = currencyAmount.findAll(content).map { it.value }.toList()
                if (currencyAmount.findAll(body).count() > 1 || visible.distinct().size > 1) {
                    return NotificationParseResult.NeedsEvidence("ambiguous-multiple-amounts")
                }
                if (visible.isEmpty()) return NotificationParseResult.NeedsEvidence("missing-visible-amount")
                return NotificationParseResult.NeedsEvidence("non-expense-transaction")
            }
            if (negative.containsMatchIn(content)) return NotificationParseResult.Ignored("not-successful-expense")
            if (!success.containsMatchIn(content)) return NotificationParseResult.NeedsEvidence("missing-explicit-success")
            val bodyAmounts = currencyAmount.findAll(body).toList()
            if (bodyAmounts.size > 1) return NotificationParseResult.NeedsEvidence("ambiguous-multiple-amounts")
            amounts = currencyAmount.findAll(content).map {
                it.groupValues[1].ifEmpty { it.groupValues[2] }.toBigDecimalOrNull()
            }.filterNotNull().map { it.stripTrailingZeros() }.distinct().toList()
            if (amounts.size > 1) return NotificationParseResult.NeedsEvidence("ambiguous-multiple-amounts")
            if (eligible.isEmpty()) return NotificationParseResult.NeedsEvidence("unverified-template")
            if (eligible.size != 1) return NotificationParseResult.NeedsEvidence("ambiguous-template")
            template = eligible.single()
        }
        val fields = template.extract(input) ?: return NotificationParseResult.NeedsEvidence("missing-required-fields")
        if (fields.currency != "CNY") return NotificationParseResult.Ignored("unsupported-currency")
        if (!plainAmount.matches(fields.amount)) return NotificationParseResult.NeedsEvidence("invalid-amount")
        val cents = runCatching { BigDecimal(fields.amount).movePointRight(2).longValueExact() }.getOrNull()
            ?.takeIf { it > 0 } ?: return NotificationParseResult.NeedsEvidence("invalid-amount")
        if (amounts.isEmpty()) return NotificationParseResult.NeedsEvidence("missing-visible-amount")
        if (amounts.single().compareTo(BigDecimal(fields.amount)) != 0) {
            return NotificationParseResult.NeedsEvidence("conflicting-amount")
        }
        if (fields.transactionTimeMillis != null && fields.transactionTimeMillis <= 0) {
            return NotificationParseResult.NeedsEvidence("invalid-transaction-time")
        }
        return NotificationParseResult.Candidate(
            BillDraft(priceCents = cents, type = BillType.EXPENSE,
                timeMillis = fields.transactionTimeMillis ?: input.receivedAt,
                merchant = fields.merchant?.takeIf { it.isNotBlank() }, method = fields.method,
                orderTail = null, sourcePkg = input.packageName, strategy = "notification:${template.id}",
                transactionKey = fields.orderId?.takeIf { it.isNotBlank() }?.let {
                    com.example.billkit.TransactionIdentity.key(input.packageName, BillType.EXPENSE, it)
                },
                hasExplicitTime = fields.transactionTimeMillis != null,
                captureSessionId = input.observationKey),
            template.id,
        )
    }

    companion object {
        val SUPPORTED_PACKAGES = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")

        /** Templates admitted from reviewed real evidence; a template without evidence never joins this list. */
        val PRODUCTION_TEMPLATES: List<VerifiedExpenseNotificationTemplate> = listOf(AlipayExpenseNotificationTemplate())

        /**
         * Packages that currently have at least one admitted production template.
         *
         * Derived from [PRODUCTION_TEMPLATES] rather than a second hand-written list: a settings screen
         * that claims "this app has no verified template" must not be able to drift from what the parser
         * actually accepts. Being in [SUPPORTED_PACKAGES] only means the envelope is recognised.
         */
        val TEMPLATE_PACKAGES: Set<String> = PRODUCTION_TEMPLATES
            .filter { it.id.isNotBlank() && it.evidenceReference.isNotBlank() }
            .map { it.packageName }.toSet()
        private val success = Regex("支付成功|付款成功|扣款成功|消费成功")
        private val negative = Regex("失败|取消|待支付|待付款|未支付|未付款|支付中|付款中|处理中|进行中|退款|收款|转账|收入|优惠|红包|营销|验证码")
        private val hardNegative = Regex("失败|取消|撤销|待支付|待付款|待扣款|未支付|未付款|未扣款|未完成|待完成|待确认|尚未|支付中|付款中|扣款中|处理中|进行中|冻结|验证码|营销|优惠")
        private val otherDirection = Regex("退款|收款|转账|收入")
        private val plainAmount = Regex("(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?")
        private val currencyAmount = Regex("[¥￥]\\s*(\\d+(?:\\.\\d+)?)|(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*元")
    }
}
