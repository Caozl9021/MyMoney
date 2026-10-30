package com.example.mymoney.ui

import com.example.mymoney.data.BillEntity

/** 原支出保持不变，退款独立汇总，避免把退款增加到收入。 */
data class LedgerTotals(val expense: Long, val income: Long, val refund: Long) {
    val balance: Long get() = income - expense + refund
}

fun ledgerTotals(bills: List<BillEntity>): LedgerTotals = LedgerTotals(
    expense = bills.filter { it.type == "EXPENSE" }.sumOf { it.priceCents },
    income = bills.filter { it.type == "INCOME" }.sumOf { it.priceCents },
    refund = bills.filter { it.type == "REFUND" }.sumOf { it.priceCents },
)
