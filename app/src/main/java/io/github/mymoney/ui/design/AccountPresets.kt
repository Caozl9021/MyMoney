package io.github.mymoney.ui.design

/**
 * 添加账户向导用的预设目录：覆盖全部 6 种账户类型，并支持每个分组下自定义创建。
 */
data class AccountPreset(
    val name: String,
    val kind: String,
    val aliases: String = "",
    val iconKey: String? = null,
    val colorHex: String? = null,
    val quickSelect: Boolean = true,
)

data class AccountPresetKind(
    val kind: String,
    val label: String,
    val subtitle: String,
    val defaultQuickSelect: Boolean = true,
    val defaultIconKey: String = "account_balance_wallet",
)

object AccountPresets {

    val kinds: List<AccountPresetKind> = listOf(
        AccountPresetKind("PAYMENT", "支付账户", "钱包、支付宝、微信、余额宝、云闪付", true, "account_balance_wallet"),
        AccountPresetKind("CASH", "现金", "纸钞、日常零钱与备用金", true, "payments"),
        AccountPresetKind("SAVINGS", "储蓄卡", "工商、农业、中国、建设、招商等主流银行", true, "landmark"),
        AccountPresetKind("CREDIT", "信用账户", "信用卡、花呗、白条、月付", true, "credit_card"),
        AccountPresetKind("OTHER_ASSET", "投资与卡券资产", "基金、股票、定期、公积金、医保、借出", false, "trending_up"),
        AccountPresetKind("OTHER_DEBT", "借款与负债", "借入款、房贷、车贷、分期账单", false, "receipt_long"),
    )

    val all: List<AccountPreset> = listOf(
        // 支付账户
        AccountPreset("钱包", "PAYMENT", ""),
        AccountPreset("支付宝", "PAYMENT", "支付宝余额"),
        AccountPreset("微信钱包", "PAYMENT", "微信,零钱"),
        AccountPreset("微信零钱通", "PAYMENT", "零钱通", "savings", "#10B981"),
        AccountPreset("余额宝", "PAYMENT", "", "savings", "#F59E0B"),
        AccountPreset("数字人民币", "PAYMENT", "数币"),
        AccountPreset("云闪付", "PAYMENT", "银联云闪付", "credit_card", "#DC2626"),
        AccountPreset("PayPal", "PAYMENT", "", "payments", "#2563EB"),
        // 现金
        AccountPreset("现金", "CASH", "", "payments", "#10B981"),
        AccountPreset("备用金", "CASH", "家庭备用金", "account_balance_wallet", "#D97706"),
        // 储蓄卡（主流银行）
        AccountPreset("工商银行", "SAVINGS", "工行"),
        AccountPreset("农业银行", "SAVINGS", "农行"),
        AccountPreset("中国银行", "SAVINGS", "中行"),
        AccountPreset("建设银行", "SAVINGS", "建行"),
        AccountPreset("招商银行", "SAVINGS", "招行"),
        AccountPreset("交通银行", "SAVINGS", "交行", "landmark", "#1D4ED8"),
        AccountPreset("邮储银行", "SAVINGS", "邮政,中国邮政储蓄银行", "landmark", "#047857"),
        AccountPreset("浦发银行", "SAVINGS", "浦发", "landmark", "#1E40AF"),
        AccountPreset("中信银行", "SAVINGS", "中信", "landmark", "#DC2626"),
        AccountPreset("兴业银行", "SAVINGS", "兴业", "landmark", "#2563EB"),
        AccountPreset("光大银行", "SAVINGS", "光大", "landmark", "#7C3AED"),
        AccountPreset("民生银行", "SAVINGS", "民生", "landmark", "#0D9488"),
        AccountPreset("平安银行", "SAVINGS", "平安", "landmark", "#EA580C"),
        AccountPreset("广发银行", "SAVINGS", "广发", "landmark", "#E11D48"),
        AccountPreset("华夏银行", "SAVINGS", "华夏", "landmark", "#B91C1C"),
        AccountPreset("北京银行", "SAVINGS", "北行", "landmark", "#E11D48"),
        AccountPreset("上海银行", "SAVINGS", "上行", "landmark", "#2563EB"),
        AccountPreset("江苏银行", "SAVINGS", "苏行", "landmark", "#0EA5E9"),
        AccountPreset("宁波银行", "SAVINGS", "宁行", "landmark", "#D97706"),
        // 信用账户
        AccountPreset("花呗", "CREDIT", ""),
        AccountPreset("白条", "CREDIT", "京东白条"),
        AccountPreset("信用卡", "CREDIT", "", "credit_card", "#3B82F6"),
        AccountPreset("抖音月付", "CREDIT", "", "credit_card", "#111827"),
        AccountPreset("美团月付", "CREDIT", "", "credit_card", "#D97706"),
        // 其他资产（投资 / 社保 / 卡券 / 债权）
        AccountPreset("基金账户", "OTHER_ASSET", "基金,天天基金", "trending_up", "#0D9488", quickSelect = false),
        AccountPreset("股票证券", "OTHER_ASSET", "股票,证券", "trending_up", "#DC2626", quickSelect = false),
        AccountPreset("定期存款", "OTHER_ASSET", "定存,大额存单", "savings", "#D97706", quickSelect = false),
        AccountPreset("黄金理财", "OTHER_ASSET", "黄金", "emoji_events", "#D97706", quickSelect = false),
        AccountPreset("公积金", "OTHER_ASSET", "住房公积金", "apartment", "#0EA5E9", quickSelect = false),
        AccountPreset("医保个人账户", "OTHER_ASSET", "医保卡", "shield", "#10B981", quickSelect = false),
        AccountPreset("交通充值卡", "OTHER_ASSET", "公交卡,校园卡,饭卡", "directions_bus", "#3B82F6", quickSelect = true),
        AccountPreset("借出款", "OTHER_ASSET", "应收账款,借出", "card_giftcard", "#6366F1", quickSelect = false),
        // 其他负债（借入 / 贷款）
        AccountPreset("借入款", "OTHER_DEBT", "应付账款,欠款", "receipt_long", "#E04F5F", quickSelect = false),
        AccountPreset("房贷", "OTHER_DEBT", "住房贷款,按揭", "home", "#64748B", quickSelect = false),
        AccountPreset("车贷", "OTHER_DEBT", "汽车贷款", "local_taxi", "#64748B", quickSelect = false),
        AccountPreset("分期借款", "OTHER_DEBT", "分期", "receipt_long", "#E04F5F", quickSelect = false),
    )

    fun forKind(kind: String): List<AccountPreset> = all.filter { it.kind == kind }

    fun kindInfo(kind: String): AccountPresetKind? = kinds.firstOrNull { it.kind == kind }

    /** 所有类型都展示具体预设列表及「自定义账户」入口，不再跳过具体选择步骤。 */
    fun skipsSpecificStep(kind: String): Boolean = false
}
