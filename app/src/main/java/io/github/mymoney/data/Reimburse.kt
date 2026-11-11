package io.github.mymoney.data

/** 报销只挂在原支出上：待报销不改余额，已报销把退回记到选中的资产账户。 */
object Reimburse {
    const val NONE = "NONE"
    const val PENDING = "PENDING"
    const val REIMBURSED = "REIMBURSED"
    val statuses = setOf(NONE, PENDING, REIMBURSED)

    /** 转账手续费不是一笔可单独报销的支出。 */
    fun eligible(bill: BillEntity): Boolean = bill.type == "EXPENSE" && bill.feeParentKey == null

    /** 已报销从支出合计里扣除，收入和退款口径不变。 */
    fun countsInExpense(bill: BillEntity): Boolean = bill.type == "EXPENSE" && bill.reimburseStatus != REIMBURSED

    fun normalize(status: String?): String = status?.takeIf { it in statuses } ?: NONE
}
