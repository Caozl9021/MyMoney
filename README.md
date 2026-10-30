<div align="center">

<img src="assets/brand/icon.png" width="120" alt="麦米 My Money" />

# 麦米 My Money

**在微信、支付宝付完款的那一刻，账单已经替你记好了。**

本地优先的 Android 自动记账 · 规则识别 · 你点确认才入账

<sub>My Money — a local-first auto bookkeeping app for WeChat Pay &amp; Alipay</sub>

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#构建)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](#项目结构)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)](#项目结构)
![Version](https://img.shields.io/badge/版本-1.0.0%20%2F%20105-00847D)

**[⬇ 下载最新版本](../../releases/latest)**

</div>

![麦米 My Money 界面总览](assets/screenshots/hero.png)

<div align="center"><sub>账本 · 确认卡 · 统计 · 资产：App 实际运行截图，示例为测试账本数据</sub></div>

---

## 一次支付，只要三步

```mermaid
flowchart LR
    A["微信 / 支付宝 完成支付"] --> B["通知 · 页面辅助 · Root · LSPosed<br/>四条输入通道"]
    B --> C["本地规则引擎<br/>14 条规则 · Rhino 执行 · 不联网"]
    C --> D["确认卡<br/>金额 / 类型 / 分类 / 账户 / 备注"]
    D -->|你点「记一笔」| E["Room 本地账本<br/>账单 · 历史 · 资产流水"]
    D -->|下滑 / 返回 / 忽略| F["待处理 · 识别历史<br/>随时再确认"]
```

**不猜、不替你做主。** 识别结果一律先成草稿，金额、类型、分类、账户都可以当场改；只有你点了「记一笔」，数据才会写进账本。

---

## 界面

<table>
  <tr>
    <td width="50%" align="center"><img src="assets/screenshots/01-home.png" width="330" alt="账本首页" /><br/><sub><b>账本</b> · 账期摘要 + 按自然日倒序的日卡</sub></td>
    <td width="50%" align="center"><img src="assets/screenshots/02-confirm.png" width="330" alt="确认卡" /><br/><sub><b>确认卡</b> · 悬浮在支付页上方，改完即记</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/screenshots/03-stats.png" width="330" alt="统计" /><br/><sub><b>统计</b> · 周柱状图与跨月热力图</sub></td>
    <td align="center"><img src="assets/screenshots/04-assets.png" width="330" alt="资产" /><br/><sub><b>资产</b> · 账户余额、总资产与负债</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/screenshots/05-appearance.png" width="330" alt="外观" /><br/><sub><b>外观</b> · 三款主题图标、深浅色、字号、降低动效</sub></td>
    <td align="center">
      <img src="assets/brand/icon.png" width="96" alt="青绿图标" />&nbsp;
      <img src="assets/brand/icon-blue.png" width="96" alt="蓝白图标" />&nbsp;
      <img src="assets/brand/icon-red.png" width="96" alt="红金图标" /><br/>
      <sub><b>应用图标</b> · 青绿 / 蓝白 / 红金，与主题同步</sub>
    </td>
  </tr>
</table>

---

## 能力一览

| | 能力 | 说明 |
|---|---|---|
| 🎯 | **微信 / 支付宝识别** | 只做这两家。通知识别、页面辅助、Root 通知、LSPosed 交易凭证四条通道，全部收敛到同一套本地规则 |
| 🧾 | **确认卡** | 悬浮窗优先、Activity 兜底；金额、收支类型、分类、账户、备注、时间一次改完；下滑或返回会保留草稿到「待处理」，不会丢 |
| 📒 | **账本** | 账期起始日可设 1–31 号，首页摘要与日卡同步翻期；全库搜索 + 日期 / 分类 / 类型组合筛选；逐笔编辑、删除与 5 秒撤销 |
| 📊 | **统计** | 周 / 月切换，每日支出柱状图与跨月热力图；分类占比与账户汇总；转账不计收支，结余 = 收入 − 支出 + 退款 |
| 💰 | **资产** | 总资产 / 总负债、单个账户余额与按账期的非本金转账流水；账户可归档，历史导入不重复扣减 |
| 🧠 | **本地规则与学习** | 分类、账户、备注各有独立来源；人工修改与接受推荐会在本机学习，可逐条查看与删除，关掉开关即停止 |
| 🔄 | **导入导出** | 钱迹 XLSX 导入（支出 / 收入 / 完整转账）、iCost CSV 导出（含退款时阻止整次导出）、JSON 完整备份与恢复 |
| 🎨 | **外观** | 青绿 / 蓝白 / 红金三款主题与图标、浅色 / 深色 / 跟随系统、三档字号、降低动效、可关的预测性返回、随主题调整的液体玻璃顶栏与底栏 |

---

## 识别范围

只启用 **14 条原创规则**（`maimi-rules-4`，全部启用）：

| 渠道 | 页面 / 凭证规则 | 通知规则 | 转账 |
|---|---|---|---|
| 微信 | 红包支出、红包收入、支出、收入、退款 | 支出、收入 | 支出、收入 |
| 支付宝 | 支出、收入、退款 | 支出、收入 | — |

- 输入通道：**通知识别**、**页面辅助（无障碍）**、**Root 通知**、**LSPosed 交易凭证**。Root 与无障碍互斥，首次选择即保存，暂停不清模式。
- 红包金额按同一红包单号关联经过核验的回执，缺金额、串单或冲突一律拒绝，不用群总额凑数。
- 银行短信与其他 App 渠道不在范围内：权限、receiver 与入口均已移除，旧配置原值只做兼容，不会越界生效。

---

## 项目结构

```mermaid
flowchart TB
    subgraph APP[":app"]
        UI["ui/ · 首页 / 统计 / 资产 / 设置<br/>Compose + 设计 token + 液体玻璃"]
        CAP["capture/ · 通知 / 无障碍 / Root<br/>调度与投递"]
        LSP["xposed/ · 微信支付宝主进程采集<br/>并独立加载回执"]
        STORE["data/ · Room v9<br/>账单 / 识别历史 / 资产"]
    end
    subgraph KIT[":billkit"]
        PIPE["PageClassifier → AmountParser<br/>→ FieldExtractor → BillPipeline"]
        RULES["SPEC 规则 · Rhino 执行<br/>不联网"]
    end
    CAP --> PIPE
    LSP --> RULES
    PIPE --> RULES
    UI --> STORE
    RULES --> STORE
```

| 模块 | 职责 |
|---|---|
| `:billkit` | 识别核心：`BillKit` 接收 `UiSnapshot`，节流去重、分类、金额解析、字段抽取、规则执行与诊断 |
| `:app` | Compose 界面与宿主、采集通道、确认卡投递、Room 账本、备份与导入导出、LSP 内核（内置主 APK） |
| 构建 | JDK 17 · Gradle 8.14 · AGP 8.7 · Kotlin 2.0.21 · `minSdk 26` / `targetSdk 35` |

更细的设计说明见 [ARCHITECTURE.md](ARCHITECTURE.md)，各功能专题见 [docs/](docs/)。

---

## 构建

需要 JDK 17 与 Android SDK（compileSdk 35）。项目自带 Gradle wrapper，不需要单独装 Gradle。

```bash
# 1. 指向本机 SDK（local.properties 不进版本库）
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2. 跑单元测试
./gradlew :billkit:testDebugUnitTest :app:testDebugUnitTest

# 3. 构建
./gradlew :app:assembleDebug        # 可直接安装的调试包
./gradlew :app:assembleRelease      # 未签名时产出 -unsigned.apk
```

- release 签名从仓库外的 `~/.mymoney-keys/keystore.properties` 读取；缺失时产物为 unsigned，不影响本地调试，但**不能当正式包分发**。
- debug 与 release 签名不同，不能互相覆盖。覆盖升级请勿卸载，避免丢数据。
- 构建需要访问 `https://api.xposed.info/` 拉取 `de.robv.android.xposed:api:82`（`compileOnly`，不进 APK）。该仓库不可用时，可先注释掉这一行，只构建非 LSP 部分。
- 依赖缓存完整时才加 `--offline`。

`./build-all.sh` 会读取 `app/build.gradle.kts` 里的版本号，把 debug / release 两个包输出到 `dist/MyMoney-<版本>-<分支>-<类型>.apk`。

> **自行构建的包使用你自己的签名，与官方 Release 的签名不同，无法覆盖安装官方包。** 想跟官方版本共存或升级，请从 Releases 下载。

---

## 数据与隐私

- **不声明 `INTERNET` 权限。** 规则、账单、学习与备份全部在本机处理，没有云端账号、没有云端学习。可以自己验证：

  ```bash
  aapt dump permissions MyMoney-1.0.0-release.apk
  ```

- 不读写第三方 App 的私有数据库，不 Hook 微信 / 支付宝的 Flutter / Dart 层，不做截图 OCR，不恢复任何「两路同时采集」的旧方案。
- 金额一律以 **分（Long）** 存储；账单、识别历史、资产流水保留各自身份与关联，重复提交幂等。
- 备份是 **JSON v8**，含账单、历史、账户余额与流水、采集配置、导入身份、账期与备注，兼容 v1–v7；系统 `allowBackup` 关闭，数据只走你自己导出与恢复的文件。
- 诊断样本与截图先脱敏再导出；分享给第三方 App（如 iCost）是否入账由对方与你自己确认。

---

<details>
<summary><b>已知问题与边界</b>（点开）</summary>

- **BUG-ALIPAY-DELAYED-CONFIRM-001**：支付宝停留超过 10 秒或切到桌面 / 其他 App 后才显示确认卡，具体原因待原始采集时间线确认。
- **BUG-BACK-GHOST-001**：预测性返回的残影问题，默认关闭该特性保留。
- 权限状态偶发不同步（`BUG-PERM-STALE-STATE-001`），以系统实际授权与运行状态为准。
- 各厂商的省电策略、后台清理与通知折叠可能影响采集时延；本项目不承诺固定弹框时延，也不承诺外部 App 的导入结果。
- 完整 TalkBack、低 API 设备、锁屏与长时间功耗等矩阵仍属未覆盖范围。

</details>

<details>
<summary><b>第三方组件与许可</b>（点开）</summary>

本项目以 [Apache-2.0](LICENSE) 发布。完整声明见 [NOTICE](NOTICE)。

| 组件 | 许可 | 用途 |
| --- | --- | --- |
| [Mozilla Rhino](https://github.com/mozilla/rhino) | MPL-2.0 | 在设备上执行本地识别规则，无原生 `.so` |
| [AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) | MIT | 顶栏与底栏的液体玻璃折射效果；本地 AAR 的来源与改动见 [说明](app/libs/LIQUIDGLASS-SOURCE.md) |
| [Lucide](https://github.com/lucide-icons/lucide) | ISC | 底部 dock 的动态图标路径，已适配 Compose |
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 | UI 工具链、生命周期、Room |
| Material Symbols | Apache-2.0 | 应用内的分类图标 |

**商标**：微信、支付宝分别是腾讯控股有限公司与蚂蚁集团股份有限公司的商标；iCost（钱迹）为其各自所有者的商标。本项目是独立的非官方工具，与上述公司无关联、未获其背书或赞助。

</details>

---

## 参与贡献

欢迎 Issue 与 PR。提交前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)——里面写了代码约定、如何新增一条识别规则，以及**报告问题前如何脱敏诊断样本**。

发现安全问题请走 [SECURITY.md](SECURITY.md) 里的私密渠道，不要开公开 Issue。

---

<div align="center"><sub>规则、账单与学习都留在你自己的手机里 · Made with ♥ for 只想记好账的人</sub></div>
