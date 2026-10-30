# 参与贡献

感谢你愿意花时间。这个项目的目标很窄：**只把微信和支付宝的支付记下来，其余什么都不做。** 提交前请先确认改动没有偏离这条线。

## 开发环境

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.14（用仓库自带的 wrapper） |
| Android SDK | compileSdk / targetSdk 35，minSdk 26 |
| AGP / Kotlin | 8.7 / 2.0.21 |

```bash
# 1. 指向本机 SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2. 跑单元测试
./gradlew :billkit:testDebugUnitTest :app:testDebugUnitTest

# 3. 构建
./gradlew :app:assembleDebug
```

release 签名从仓库外的 `~/.mymoney-keys/keystore.properties` 读取。文件不存在时 `assembleRelease` 会产出 unsigned 包，**这不影响你本地调试**，但请不要把它当作可分发产物。fork 自行构建的包签名与官方包不同，无法覆盖安装。

构建时需要访问 `https://api.xposed.info/` 拉取 `de.robv.android.xposed:api:82`（`compileOnly`，不进 APK）。如果该仓库不可用，可以先把这一行注释掉，只构建不涉及 LSP 的部分。

## 代码约定

1. **金额一律以分（Long）存储。** 两位小数输入用 `Locale.US` 解析，`19.99` 必须是 `1999` 分，不能变成 `1998`。
2. **失败、取消、进行中的状态不得入账。** 只有用户点「记一笔」并且真实写库成功，才算记录。
3. **不引入网络。** 项目不声明 `INTERNET` 权限。任何需要联网的功能都不会被接受。
4. **不读写第三方 App 的私有数据。** 不 Hook 微信/支付宝的 Flutter/Dart 层，不做截图 OCR，不自动点击对方的确认按钮。
5. **不猜。** 缺字段就留空并保留原始诊断，不要补成看起来合理的事实。
6. 面向用户的文案用中文；代码注释沿用现有风格，解释「为什么」而不是「做了什么」。

## 加一条识别规则

规则是本地 JS，由 Rhino 在设备上执行，不联网。

1. 在 `billkit/rules/src/` 下找到对应渠道的文件（`wechat-data.js`、`alipay-data.js`、`wechat-transfer.js`、`wechat-redpackage.js`、`notice.js`）。
2. 按现有结构新增规则，写明 `require` / `anyOf` / `exclude` 条件与 `strategy`。
3. 在 `billkit/src/test/resources/spec/samples.json` 增加正例与反例样本。**样本必须是合成的**，不要提交真实账单。
4. 跑 `./gradlew :billkit:testDebugUnitTest`，确认 `coverage.json` 与实际规则一致。

## 提交 Pull Request

- 一个 PR 只做一件事，标题写清楚改了什么。
- 涉及 UI 的改动请附截图；涉及识别的改动请附新增的测试样本说明。
- 提交信息用中文或英文都可以，但要能看懂。
- 新增第三方依赖请在 `NOTICE` 里补一条，并确认许可证与 GPL-3.0 兼容。Apache-2.0、MIT、ISC、BSD、MPL-2.0 都可以；专有许可证和 GPL 不兼容的许可证不行。

## 报告问题

用 Issue 模板，**必须**包含：

- App 版本（设置 → 关于）与 `versionCode`
- 机型、Android 版本、采集模式（通知 / 无障碍 / Root / LSPosed）
- 复现步骤
- 诊断导出（设置 → 自动记账 → 识别统计 → 导出样本）

**导出前请先脱敏。** 诊断样本包含页面原文，可能带有商户名、金额和订单号。请把金额、订单号、商户名替换成示例值再贴出来，不要提交真实账单截图。
