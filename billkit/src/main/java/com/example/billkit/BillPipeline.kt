package com.example.billkit

import com.example.billkit.BillDraft
import com.example.billkit.BillType

/**
 * 编排层：分类 → 状态校验 → 金额 → 方向 → 时间 → 字段 → 生成草稿。
 *
 * 无 Android 依赖，输入是 UiSnapshot，所以无障碍 / root dump / OCR
 * 三条来源共用同一套规则与同一批单测。
 */
class BillPipeline(
    private val classifier: PageClassifier = PageClassifier(),
) {

    sealed interface Outcome {
        data class Draft(val draft: BillDraft, val match: PageMatch) : Outcome
        data class Skipped(val reason: String) : Outcome

        /** 值得再走一次 OCR 的失败原因（分类没命中 / 分类命中但没金额） */
        val needsOcr: Boolean
            get() = this is Skipped && (
                reason.startsWith("not-a-bill-page") || reason.startsWith("no-amount")
                )
    }

    fun process(snapshot: UiSnapshot, now: Long = System.currentTimeMillis()): Outcome {
        val match = classifier.classify(snapshot)
            ?: return Outcome.Skipped("not-a-bill-page")

        when (AmountParser.status(snapshot.items)) {
            AmountParser.Status.FAILED -> return Outcome.Skipped("failed:${match.strategy}")
            AmountParser.Status.CANCELLED -> return Outcome.Skipped("cancelled:${match.strategy}")
            AmountParser.Status.PENDING -> return Outcome.Skipped("pending:${match.strategy}")
            AmountParser.Status.UNKNOWN -> return Outcome.Skipped("no-status:${match.strategy}")
            AmountParser.Status.SUCCESS -> Unit
        }

        val refund = AmountParser.isCompletedRefund(snapshot.items)
        val hit = (if (refund) AmountParser.findRefundAmount(snapshot.items) else AmountParser.findAmount(snapshot.items))
            ?: return Outcome.Skipped("no-amount:${match.strategy}")
        if (hit.cents <= 0) return Outcome.Skipped("zero-amount")

        val fields = FieldExtractor.extract(snapshot.items)
        val draft = BillDraft(
            priceCents = hit.cents,
            type = if (refund) BillType.REFUND else detectType(snapshot.items, match.kind, hit.sign),
            timeMillis = (if (refund) AmountParser.findRefundTime(snapshot.items)
                else AmountParser.findTime(snapshot.items)) ?: now,
            merchant = fields.merchant
                ?: FieldExtractor.merchantFromRemark(snapshot.items)
                ?: fields.counterParty
                ?: FieldExtractor.merchantAboveAmount(snapshot.items),
            method = fields.method,
            orderTail = FieldExtractor.orderIdTail(fields.orderId),
            sourcePkg = snapshot.packageName,
            strategy = "${match.strategy}/${hit.source}",
        )
        return Outcome.Draft(draft, match)
    }

    /**
     * 方向判定，顺序：
     *   1) 金额自带符号最可靠（微信交易详情就是 `-3.50`）
     *   2) 明确的收入文案
     *   3) 转账类页面
     *   4) 其余按支出
     */
    private fun detectType(items: List<String>, kind: PageKind, sign: Int): BillType {
        if (sign < 0) return BillType.EXPENSE
        if (sign > 0) return BillType.INCOME

        val joined = items.joinToString(" ")
        // 注意：故意不含泛化的"收款"（会误命中"收款方"）
        val incomeStrong = listOf(
            "收入", "已收钱", "你已收款", "收款成功", "收款到账", "已到账",
            "已存入零钱", "入账", "收益",
        )
        if (incomeStrong.any { joined.contains(it) }) return BillType.INCOME

        if (kind == PageKind.WECHAT_RECEIVE) return BillType.INCOME

        if (joined.contains("转账") ||
            kind == PageKind.WECHAT_TRANSFER ||
            kind == PageKind.ALIPAY_TRANSFER ||
            kind == PageKind.WECHAT_LQT
        ) {
            return BillType.TRANSFER
        }

        return when (kind) {
            PageKind.WECHAT_RED_PACKET -> BillType.INCOME
            else -> BillType.EXPENSE
        }
    }
}
