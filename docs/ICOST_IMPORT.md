# 把账单导入 iCost

按 **AutoBook 0.6.8 / 27**（2026-10-23）的 `CostCsv` / `CostShare` 核对。本文描述项目已实现的模板与投递方式，iCost 当前版本的最终导入行为仍需在目标设备确认。

## 操作

单笔在确认卡点击「导入 iCost」：先通过本地保存回调，再发起 CSV 分享；实际导入确认由 iCost 完成。本地保存与第三方导入不是同一事务，分享成功也不等于 iCost 已入账。

批量在设置 → 导出中选择本月、上月、最近3个月或全部。日期范围为左闭右开。代码还提供 `CostShare.shareRecent(context, limit = 50)` 用于最近条数导出。

所选范围只要包含 **退款**，整次 iCost CSV 导出就会明确阻止，并提示使用 JSON 备份保存完整账目；不会静默忽略退款或将它转成收入。账本中的退款独立列示，结余为收入−原支出+退款。

## 十列模板

UTF-8 CSV 固定表头如下，默认不加 BOM：

| 列 | 字段 | 当前取值 |
|---|---|---|
| A | 日期 | 本地时区文本 `yyyy年MM月dd日 HH:mm:ss` |
| B | 类型 | 支出、收入、转账 |
| C | 金额 | 正金额、两位小数，`Locale.US` |
| D | 一级分类 | 优先账单分类，缺失用 `Profile.fallbackCategory`（默认空） |
| E | 二级分类 | 账单二级分类，缺失为空 |
| F | 账户1 | 优先账单账户；否则支付方式同义词映射；再否则 `fallbackAccount`（默认空） |
| G | 账户2 | 空，转账的另一账户需在 iCost 补选 |
| H | 备注 | 依次取非 null 的账单备注、商户、策略名，换行转空格 |
| I | 货币 | 默认 `CNY` |
| J | 标签 | 默认空 |

逗号、双引号或换行按 CSV 转义。分类和账户需要与目标 iCost 账本中的名称对应；建议先在 AutoBook 分类与账户设置中配置，确认卡可修改具体账单。未知名称的处理由目标版本决定。

默认支付方式映射为：零钱通→微信零钱通、零钱→微信钱包、余额宝→余额宝、花呗→花呗、白条→白条、云闪付→云闪付、数字人民币→数字人民币、信用卡→信用卡、储蓄卡→储蓄卡。按关键词长度优先匹配；未命中时用兜底值，**不会原样输出支付方式**。

开发者可用 `CostCsv.Profile` 调整 `datePattern`、`currency`、三类类型文案、`fallbackCategory`、`fallbackAccount`、`tags` 与 `withBom`。旧的 `expenseCategory` / `incomeCategory` / `accountAliases` 参数已不属于当前接口。

## 投递与边界

`CostShare` 将 CSV 写入应用缓存 `share/`，通过 FileProvider 提供只读 `content://` URI，依次尝试：定向 `ACTION_VIEW` → 定向 `ACTION_SEND` → 系统 `VIEW` 选择器 → 系统 `SEND` 选择器。目标包为 `com.gostraight.smallAccountBook.fltandroid`。返回的 `ShareMode` 或导出条数只说明成功启动投递流程。

项目不写 iCost 私有数据库、不 Hook 它的内部方法、不自动点击最终确认，也不绕过其导入限制。iCost 未安装或不接受定向请求时可退回系统选择器；所有投递失败时接口返回失败；单笔 `ConfirmActivity` 回调当前会吞掉分享异常并关闭页面，不能保证每种单笔失败都有可见提示。

源码：`app/src/main/java/com/example/autobook/export/CostCsv.kt`、`CostShare.kt`、`ExportRange.kt`；回归覆盖在 `app/src/test/java/com/example/autobook/CostCsvTest.kt` 等测试中。已有构建与测试证据见 [PROGRESS.md](PROGRESS.md)，不据此宣称当前 iCost 真机导入已验证。
