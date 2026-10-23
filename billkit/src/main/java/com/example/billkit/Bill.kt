package com.example.billkit

/** 支出 / 收入 / 转账 / 退款（金额均为正） */
enum class BillType { EXPENSE, INCOME, TRANSFER, REFUND }

/**
 * 一条待确认的账单草稿。库只负责产出它，落库/导出/通知全部由宿主实现
 * （通过 [BillSink]），所以 :billkit 不依赖 Room、不依赖任何 UI。
 */
data class BillDraft(
    val priceCents: Long,
    val type: BillType,
    val timeMillis: Long,
    val merchant: String?,
    val method: String?,
    val orderTail: String?,
    val sourcePkg: String,
    val strategy: String,
    /** 一级分类（来自用户自己的清单，可自动推断后由用户修改） */
    val category: String? = null,
    /** 二级分类 */
    val subCategory: String? = null,
    /** 账户（同上） */
    val account: String? = null,
    val note: String? = null,
) {
    /** 去重指纹：包名 + 金额 + 分钟 + 单号尾号（容忍几秒内的重复回调） */
    val fingerprint: String
        get() = buildString {
            append(sourcePkg).append('|')
            append(priceCents).append('|')
            append(timeMillis / 60_000)
            orderTail?.let { append('|').append(it) }
            if (type == BillType.REFUND) append("|REFUND")
        }

    val amountYuan: Double get() = priceCents / 100.0
}
