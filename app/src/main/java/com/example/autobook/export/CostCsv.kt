package com.example.autobook.export

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按 iCost 官方导入模板导出 CSV。
 *
 * 模板（`icost_template.xlsx`，sheet `icost_template`）实测 10 列：
 * `日期,类型,金额,一级分类,二级分类,账户1,账户2,备注,货币,标签`
 *
 * 关键点：
 * - 日期是**文本** `yyyy年MM月dd日 HH:mm:ss`（不是 Excel 日期序列）
 * - 金额是正数，方向由「类型」列表达
 * - **一级分类 / 账户1 必须是 iCost 里已存在的名字**，所以这里优先用
 *   账单自带的值（用户在确认页选/自动识别出来的），兜底才用同义词映射
 */
object CostCsv {

    const val REFUND_UNSUPPORTED = "当前 iCost CSV 模板不支持退款，所选账单包含退款，无法导出。请使用 JSON 备份保留完整账目。"

    const val TRANSFER_UNSUPPORTED = "当前 iCost CSV 无法完整保留双账户转账，所选账单包含转账，无法导出。请使用 JSON 备份。"

    const val TARGET_PACKAGE = "com.gostraight.smallAccountBook.fltandroid"

    val HEADER = listOf(
        "日期", "类型", "金额", "一级分类", "二级分类",
        "账户1", "账户2", "备注", "货币", "标签",
    )

    data class Profile(
        val datePattern: String = "yyyy年MM月dd日 HH:mm:ss",
        val currency: String = "CNY",
        val expenseWord: String = "支出",
        val incomeWord: String = "收入",
        val transferWord: String = "转账",
        /** 账单没分类/账户时的兜底（留空则交给 iCost，通常会落到"未分类"） */
        val fallbackCategory: String = "",
        val fallbackAccount: String = "",
        val tags: String = "",
        val withBom: Boolean = false,
    )

    /** 支付方式原始文本 → 常见账户名（只在账单没带 account 时兜底） */
    val DEFAULT_ACCOUNT_ALIASES: Map<String, String> = linkedMapOf(
        "零钱通" to "微信零钱通",
        "零钱" to "微信钱包",
        "余额宝" to "余额宝",
        "花呗" to "花呗",
        "白条" to "白条",
        "云闪付" to "云闪付",
        "数字人民币" to "数字人民币",
        "信用卡" to "信用卡",
        "储蓄卡" to "储蓄卡",
    )

    fun build(bills: List<BillDraft>, profile: Profile = Profile()): String {
        require(bills.none { it.type == BillType.REFUND }) { REFUND_UNSUPPORTED }
        require(bills.none { it.type == BillType.TRANSFER }) { TRANSFER_UNSUPPORTED }
        val format = SimpleDateFormat(profile.datePattern, Locale.US)
        val builder = StringBuilder()

        builder.append(HEADER.joinToString(",")).append('\n')
        bills.forEach { bill ->
            val row = listOf(
                format.format(Date(bill.timeMillis)),
                typeWord(bill.type, profile),
                money(bill.amountYuan),
                bill.category?.takeIf { it.isNotBlank() } ?: profile.fallbackCategory,
                bill.subCategory.orEmpty(),
                bill.account?.takeIf { it.isNotBlank() }
                    ?: accountFromMethod(bill.method)
                    ?: profile.fallbackAccount,
                // 账户2 仅转账有意义；我们只解析出一个账户，留空让用户在 iCost 里补
                "",
                bill.note.orEmpty().replace("\n", " "),
                profile.currency,
                profile.tags,
            )
            check(row.size == HEADER.size) { "列数必须与表头一致: ${row.size} != ${HEADER.size}" }
            builder.append(escape(row)).append('\n')
        }

        val text = builder.toString()
        return if (profile.withBom) "\uFEFF$text" else text
    }

    private fun typeWord(type: BillType, profile: Profile): String = when (type) {
        BillType.EXPENSE -> profile.expenseWord
        BillType.INCOME -> profile.incomeWord
        BillType.TRANSFER -> profile.transferWord
        BillType.REFUND -> throw IllegalArgumentException(REFUND_UNSUPPORTED)
    }

    /** 账单没带账户时，从"支付方式"文本猜一个常见账户名 */
    fun accountFromMethod(method: String?): String? {
        val raw = method?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return DEFAULT_ACCOUNT_ALIASES.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { raw.contains(it.key) }
            ?.value
    }

    private fun money(yuan: Double): String = String.format(Locale.US, "%.2f", yuan)

    private fun escape(row: List<String>): String = row.joinToString(",") { cell ->
        if (cell.contains(',') || cell.contains('"') || cell.contains('\n')) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }
    }
}
