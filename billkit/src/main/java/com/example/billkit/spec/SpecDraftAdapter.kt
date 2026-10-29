package com.example.billkit.spec

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import com.example.billkit.TransactionIdentity
import java.math.BigDecimal

/** 所有脚本结果进入宿主前的纯函数安全边界。 */
object SpecDraftAdapter {
    private const val MAX_CENTS = 100_000_000L
    private const val MAX_AGE = 3L * 365 * 24 * 60 * 60 * 1000

    fun toDraft(bill: SpecRuleObject, app: String, ruleId: String, now: Long): BillDraft? {
        if (bill.currency != "CNY" || !bill.money.isFinite() || bill.money <= 0.0) return null
        val cents = runCatching { BigDecimal.valueOf(bill.money).movePointRight(2).longValueExact() }
            .getOrNull()?.takeIf { it in 1..MAX_CENTS } ?: return null
        // 双端转账及手续费尚未完整支持，不能静默丢字段或降级支出。
        if (!bill.fee.isFinite() || bill.fee != 0.0 || bill.accountNameTo.isNotBlank()) return null
        val type = when (bill.type) {
            "Expend" -> BillType.EXPENSE
            "Income" -> BillType.INCOME
            "Refund" -> BillType.REFUND
            else -> return null
        }
        val validWindow = (now - MAX_AGE)..(now + 60_000)
        if (bill.hasExplicitTime && bill.time !in validWindow) return null
        if (!bill.hasExplicitTime && bill.time != 0L) return null
        val explicit = bill.hasExplicitTime
        val time = if (explicit) bill.time else bill.observedAt.takeIf { it in validWindow } ?: now
        val merchant = bill.shopName.trim().ifEmpty { null }
        val method = bill.accountNameFrom.trim().ifEmpty { null }
        val order = bill.orderId.trim().takeIf { it.length in 6..128 && it.all { c -> c.isLetterOrDigit() || c in "-_" } }
        if (bill.orderId.isNotBlank() && order == null) return null
        return BillDraft(
            priceCents = cents, type = type, timeMillis = time,
            merchant = merchant, method = method, account = method,
            orderTail = order?.takeLast(6), sourcePkg = app, strategy = "spec:$ruleId",
            note = bill.shopItem.trim().ifEmpty { null },
            transactionKey = order?.let { TransactionIdentity.key(app, type, it) },
            hasExplicitTime = explicit,
            productName = bill.shopItem.trim().ifEmpty { null },
            dedupKey = if (explicit) SpecDedup.key(app, type, cents, time) else null,
        )
    }
}
