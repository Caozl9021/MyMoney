package io.github.mymoney.ui.design

/**
 * 添加账户向导用的预设目录：先选类型，再选具体项（单项类型可跳过）。
 * 不含「其他资产 / 其他负债」与泛称「储蓄卡」。
 */
data class AccountPreset(
    val name: String,
    val kind: String,
    val aliases: String = "",
)

data class AccountPresetKind(
    val kind: String,
    val label: String,
    val subtitle: String,
)

object AccountPresets {

    val kinds: List<AccountPresetKind> = listOf(
        AccountPresetKind("PAYMENT", "支付账户", "钱包、支付宝、微信、数字人民币"),
        AccountPresetKind("CASH", "现金", "纸钞与日常现金"),
        AccountPresetKind("SAVINGS", "储蓄卡", "工商、农业、中国、建设、招商"),
        AccountPresetKind("CREDIT", "信用账户", "花呗、白条、信用卡"),
    )

    val all: List<AccountPreset> = listOf(
        AccountPreset("钱包", "PAYMENT", ""),
        AccountPreset("支付宝", "PAYMENT", "支付宝余额"),
        AccountPreset("微信钱包", "PAYMENT", "微信,零钱"),
        AccountPreset("数字人民币", "PAYMENT", "数币"),
        AccountPreset("现金", "CASH", ""),
        AccountPreset("工商银行", "SAVINGS", "工行"),
        AccountPreset("农业银行", "SAVINGS", "农行"),
        AccountPreset("中国银行", "SAVINGS", "中行"),
        AccountPreset("建设银行", "SAVINGS", "建行"),
        AccountPreset("招商银行", "SAVINGS", "招行"),
        AccountPreset("花呗", "CREDIT", ""),
        AccountPreset("白条", "CREDIT", "京东白条"),
        AccountPreset("信用卡", "CREDIT", ""),
    )

    fun forKind(kind: String): List<AccountPreset> = all.filter { it.kind == kind }

    /** 该类型只有一项时，选类型后直接进设置页。 */
    fun skipsSpecificStep(kind: String): Boolean = forKind(kind).size <= 1
}
