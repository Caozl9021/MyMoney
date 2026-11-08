# 麦米 My Money

## 当前版本1.10.0/158

2026-11-07用户确认158现有交付验收通过。此版本包含云备份网页管理台、扫码连接及邀请管理、历史备份删除与按需下载恢复，Room11／JSON10。

设置中的云备份可连接家庭服务、创建本机快照、浏览历史并按需恢复。恢复支持进度、取消与缓存复用。管理台提供设备／备份管理及连接二维码。

淘宝闪购订单数据采样已整理交接，实时验证与产品接入继续后续执行。[验收记录](docs/ACCEPTANCE.md) · [当前进度](docs/PROGRESS.md) · [淘宝实时交接](docs/SHANGOU_LIVE_VALIDATION_HANDOFF.md)

## 两种基础识别方式

| 方式 | 怎么用 |
| --- | --- |
| 通知监听 | 收到支持的微信/支付宝交易通知后识别，不依赖无障碍 |
| 页面识别 | 开启后打开交易详情即可自动识别；付款成功页由通知监听负责，不从页面生成草稿 |
| 页面文字补全 | 可选OCR，默认关闭并需同意；为可靠的详情节点草稿补商户、支付方式或商品等空字段 |

两种方式独立开关/授权/状态，位于设置→自动记账→识别方式与增强。总开关统一暂停，关闭自动弹卡时结果进入待确认。没有合格通知时，付款成功页允许不弹；页面识别并非手动按钮，也不承诺所有付款都有通知。

Root读通知是通知的内部备用实现，listener优先；LSPosed交易凭证为高级可选增强。两者不恢复旧Root页面轮询，不改变仅微信/支付宝的应用范围。

识别一律先形成待确认，只有你点“记一笔”才入账。同笔两路共用去重和确认卡，已记账/忽略及人工字段受保护。再次打开已记账/忽略/曾呈现的同笔详情仍可弹卡；只有账本命中疑似对应账单时，在金额下方仅显示“（可能重复）”，保存或忽略由你决定；同一识别重复保存仍幂等。

## 账本和迁移

- 首页按账期汇总，分类/账户统计可下钻到统一账单详情；资产与转账按原子事务联动。
- 删除先确认，记录历史保存只读删除日志，不提供恢复或5秒撤销。
- 钱迹XLSX导入、设置内iCost批量导出、本地JSON备份和个人云备份。当前主UI不再提供家庭共享规则/逐条账本同步/互导；单笔iCost分享已移除。
- 首页加号直接手动记账。主动“从截图补记”已取消，源码入口/Activity仍有残留待清退；PAGE内部OCR辅助保留，公共确认卡不提供用截图补充。
- Room11，JSON10兼容1–9；原始截图、设备身份和凭证不进备份。历史rule-sync.db与ledger-sync.db保留，但不等于当前仍有同步主入口。

## 规则与OCR边界

规则包源码当前1.3.0、15条规则、生成标记maimi-rules-5；App和规则包独立版本/发布。官方包升级保留个人规则与状态。规则只处理宿主已经准入的输入，不能绕过详情场景或通知门禁。见[规则说明](docs/LOCAL_RULES.md)。

固定离线OCR引擎为V6 Tiny，需API30+与64位进程；主App仍支持minSdk26。Q1字段补充已实现，Q0真实样本/ARM64预算仍有缺口，Q2缺金额时生成第一份草稿未实现。整页空节点盲截不在范围。见[OCR说明](docs/OCR_ASSISTANCE.md)。

## 构建

JDK17、Android SDK35，Gradle wrapper8.14、AGP8.7、Kotlin2.0.21。

```bash
./gradlew :app:testDebugUnitTest :billkit:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
./build-all.sh
```

本机SDK在local.properties配置，release签名从仓库外读取；未签名包不作为正式交付。debug与release不同签名，覆盖升级使用同签名APK，不卸载规避。产物进入dist/app/<version>-<code>/<run-id>；规则另放dist/rules，发布快照和候选分开，详见[部署](DEPLOYMENT.md)与[打包](docs/PACKAGING.md)。

| 模块 | 作用 |
| --- | --- |
| :app | Compose宿主、采集协调、确认/账本/备份、内置LSP |
| :billkit | 原生页面/通知解析、交易规则与纯策略 |
| :ocr-v6 | 固定离线原生引擎 |
| :ocr-fixture | 可选隔离测试页面，不进生产包 |

[架构](ARCHITECTURE.md) · [贡献约定](CONTRIBUTING.md) · [测试环境](docs/TEST_ENVIRONMENT.md)

## 数据与验证

识别和学习在本机执行。App声明INTERNET，用于主动GitHub更新和用户配置的个人云备份；不会向GitHub上传支付内容。个人快照只发送到所配置的自有服务，原图不上传；不扫描第三方私有数据库或自动点击支付。详见[安全边界](SECURITY.md)。

154已有1175 JVM、三构建与strict35/35工程结果；小米14已按用户要求安装153后安装154，均Success，未代操作或验收。153 Mi10完整备份恢复证据不算154返回实测；用户已确认154返回修复通过与“更多”显示正常，云备份整体未验收。支付宝延时弹卡已按用户确认关闭，权限状态/旧预测返回残影等未决仍见[剩余计划](docs/PLAN_1.0.md)。

<details>
<summary><b>第三方组件与许可</b>（点开）</summary>

本项目以 [GPL-3.0](LICENSE) 发布。完整声明见 [NOTICE](NOTICE)。

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

## 致谢

本项目的产品思路受 [AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting) 启发：它证明了「支付后自动记账」可以做成真正能日常使用的工具，本项目在形态与交互上从中受益。

---

## 参与贡献

欢迎 Issue 与 PR。提交前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)——里面写了代码约定、如何新增一条识别规则，以及**报告问题前如何脱敏诊断样本**。

发现安全问题请走 [SECURITY.md](SECURITY.md) 里的私密渠道，不要开公开 Issue。

---

<div align="center"><sub>识别在本机执行 · 备份由你选择保存到自己的服务器 · Made with ♥ for 只想记好账的人</sub></div>

打包产物按版本、构建号和独立运行目录存放，位置见 `dist/latest-app.json`；详细规则见 [打包文件存放规则](docs/PACKAGING.md)。

<details>
<summary>旧版当前状态前言（历史，现状以上文为准）</summary>

## 1.9.0/154 云备份返回修复候选（2026-11-06，待用户验证）

历史备份/连接管理改走统一设置导航栈，去掉云备份内部独立保存的子页状态，修复系统返回跳过主页、重进恢复旧子页的问题。JVM1175与三构建通过，strict35/35；154仅构建收集，**未安装或操作手机**，未改服务/备份数据。当前latest=154 pending_acceptance；153的备份/恢复实测只按153范围保留，不当作154返回实测。

证据：[154导航修复核查](runs/20261106-063858-cloud-backup-navigation/outputs/verification.md)。

## 1.9.0/153 个人云备份（2026-11-06，已实现，待用户验收）

- 设置→迁移与备份只保留“云备份”：立即备份、本设备历史、预览/二次确认恢复；本地文件与连接管理放到更多。
- 设备安装UUID+凭证复用，登记幂等；重装经授权接替原来源、保留历史并使旧凭证失效。服务端强制只访问本设备备份；五分钟一次性业务连接码，管理页一键生成、手机显式粘贴。
- 用户明确授权清表重来，已保存可恢复数据库快照后重置家庭服务专用表；旧21来源清空，Mi10新登记后1条，2份完整快照各58笔。手机账单、其他schema、文件卷与网络节点未清，未复制密钥到服务配置。
- Mi10已安装最终153 Release，保数据、备份→预览→实际恢复→再备份及冷启复用通过；58笔业务内容/sid与账户快照前后一致。JVM1175、服务端pytest65、身份仪器4+内存恢复8、strict35/35。
- Xiaomi14尚未安装153/重连并创建新备份；真实双机/重装接替和复杂资产转账真机验证仍开放。自动备份、扫码、有新改动指示及不可达旧服务器主动解绑未实现。
- 家庭服务已部署；APK仍pending_acceptance，未提交/推送/发布。148已有用户验收保留，153不因工程验证自动验收。

证据：[153交付核查](runs/20261106-055644-personal-cloud-backup-implementation/outputs/verification.md)。当前方案见[个人云备份](docs/PERSONAL_CLOUD_BACKUP_PLAN.md)。

以下为历史状态，当前执行范围以上文X阶段为准，W剩余任务不再整体作为当前需求必做。

## 1.9.0/152 工作区（2026-11-06，家庭数据 W1–W5，待用户验收）

- 「家庭数据」落地：每台设备一个独立账本，只同步到家庭服务里的**本机副本**；一条 `MMJOIN2` 连接码完成地址/网络授权/业务配对；需要家人数据时主动「从其他设备导入」；共享规则独立同步；历史备份沿用快照恢复。
- 数据面：Room10→11（`syncId/updatedAt/deletedAt`）、JSON9→10（账单带 `sid`）、独立 `ledger-sync.db` outbox；服务端新增 `/v1/ledger/{snapshot,push,pull,ack}` 与 `/v1/ledger/import/{sources,source}`，`ledger_items/ledger_changes/ledger_operations/ledger_device_cursors/ledger_import_snapshots` 五表。
- 真机定位并修掉 4 个真 bug：立即同步接错协调器、`BillWriter` ThreadLocal 跨线程丢 outbox、`bindSpace` 首绑清空首笔账单、同步详情页配置快照不刷新。
- 修复体验问题：首次合并从"点 41 次同步"改为单次触发循环推送（每批50条、最多200轮）（`PUSH_BATCH=50`/`MAX_PUSH_ROUNDS=200`）。
- 候选 1.9.0/152：JVM 1169 全绿、服务端 pytest 49、strict valid，真机 Mi10 保数据与同步链路通过；双真机 A/B 链路、账户/流水条目同步、导入日期范围仍开放。
- 旧版覆盖升级残留的假已同步、断开崩按用户决定转为 Release Notes「升级须知」；详情页旧配置导致的未连接误报已在152针对修复，未补测所有升级路径。未提交/推送/发布。

本次仅复核文档；上述测试与真机结果引用既有run，未重跑。148既有用户验收保留，149–152未验收。复核记录见[文档核查](runs/20261106-043950-docs-progress-sync/outputs/verification.md)。

## 用户验收结论（2026-11-06，优先于下方历史状态）

- **通过**：144再次确认；142重复提示精简、143标签间距；132统计下钻/记录历史/账单操作。
- **141分项**：交易详情自动识别通过；通知监听未继续深入优化，暂不通过。不得再把141整组写成待验收或整组通过。
- **138/139范围收敛后通过**：主动“从截图补记”功能取消，仅保留页面识别内部OCR辅助补字段；不新增并列采集通道。当前源码仍有SettingsScreen入口及OcrImportActivity声明，属于取消范围的残留，尚未清退；本次只同步文档，未改代码。
- **超级岛**：实验性功能，相关项目用户全部暂时验收通过；原“不出岛只出原卡”由用户确认是未授予通知权限，授权后正常，不再列为待定位/待用户验收。工程打包/装机摘要、源码提交与测试证据整理不因用户验收自动完成。
- **支付宝延时弹卡**：用户确认已修复，从历史遗留问题移除；本轮未定位修复提交或新增测试，不编造根因/验证数据。

未确认关闭的历史项：权限状态偶发过期、预测返回残影、整App流畅度全面结论；家庭同步failed按用户要求继续暂缓。OCR研究语料/预算、其他ROM/API/可访问性属于工程覆盖缺口，不重复要求用户验收已通过的功能。当前源码/本地Release输出146，候选指针仍145；不据此声明已发布。证据：`runs/20261106-110618-user-acceptance-sync/outputs/verification.md`。

<div align="center">
<img src="assets/brand/icon.png" width="120" alt="麦米 My Money" />

</details>

<details>
<summary>同步前README首屏文本（历史）</summary>

```markdown
# 麦米 My Money

本地识别微信、支付宝交易，核对后由你确认记账。

**1.9.0 / 152 候选 · 待用户验收**

本版新增「家庭数据」：每台设备一个独立账本，只同步到自建家庭服务的本机副本；一条 `MMJOIN2` 连接码完成配对；需要家人数据时主动「从其他设备导入」。

> 从旧版**覆盖升级**请先导出本机数据 → 卸载 → 装新版 → 导入，直接覆盖不保证家庭同步状态正确（见 Release Notes「升级须知」）。

超级岛为实验功能（源码146，尚未完成候选交付）：已实现纯展示、点击进确认页；**用户真机开启后不出岛、只出原确认卡，验收未通过，待定位**。

[下载发布附件](https://github.com/Caozl9021/MyMoney/releases) · [文档](docs/README.md) · [当前范围](docs/PROJECT_SCOPE.md) · [待验收清单](docs/ACCEPTANCE.md)
</div>

![界面示例](assets/screenshots/hero.png)

图片为历史测试账本界面示例，不作为143布局或真实支付覆盖的证明。当前包从[候选导航](dist/latest-app.json)定位，本地候选不表示已发布到GitHub。
```

</details>

<details>
<summary>158验收前的README交付状态</summary>

## 155进度复核（2026-11-06，仅文档）

当前候选 **1.9.0/155，Room11/JSON10，pending_acceptance**。删除历史、更多下移右对齐、context请求精简和Go HTTP复用主体已实现；155尚无用户验收。154返回修复/原“更多正常显示”分项通过保留。

本轮复核候选SHA五项通过，现存JVM XML为1180/0/0/0；服务端76 pytest、部署镜像155和Mi10显式直连实测引用原记录，未重跑/操作在线环境。已有实机证据是单选删除，批删能力不等于真实多选已测；内嵌通道和分段耗时未闭环，HTTP请求减少不等于连接慢已解决。

静态待收尾：R1删除重试未保留operationId；R2删后分页仍用旧offset；R3文件已不在时回收状态不能收尾；R4新备份未失效context缓存。详见[实现复核与后续](runs/20261106-181513-155-progress-review/outputs/implementation-review.md)。本轮只同步文档，不改源码/APK/旧证据，不操作手机或服务。

> 最新用户结论（2026-11-06）：“返回修复验收通过，更多按钮正常显示”。154返回专项与菜单显示已确认；不扩为个人云备份整体、本地JSON实际导入/导出或双机验收，不编造逐步骤日志。候选元数据仍为pending_acceptance。


本地识别微信、支付宝交易，核对后确认记账；按需把自己的完整账本备份到自己的服务器。

**1.9.0 / 154候选 · 已安装小米14 · 返回专项通过 / 云备份整体待验收**

当前是个人云备份：每台设备只访问自己的历史快照，提供手动备份和显式恢复。历史/连接管理已改统一导航栈；旧家庭共享规则、逐条账本同步和设备互导退出主UI。用户已明确验收154返回修复并确认“更多”正常显示；不扩大为云备份整体验收。

同签名覆盖安装保留本机数据；既有服务清表是用户一次明确授权，并有清前快照，不是常规升级要求。无须为本轮文档更新卸载、清表或重新操作设备。

[当前154本地候选](dist/app/1.9.0-154/20261106-064211-hkmfj9cp/MyMoney-1.9.0-154-release.apk) · [文档](docs/README.md) · [范围](docs/PROJECT_SCOPE.md) · [验收与反馈](docs/ACCEPTANCE.md)

![界面示例](assets/screenshots/hero.png)

图片为历史测试账本示例，不证明154布局或真实支付覆盖。源码/本地候选不表示已发布GitHub。超级岛按用户确认相关实验项目暂时通过，通知权限原因已澄清；工程缺口独立保留。

## 当前交付

当前源码和唯一候选为 **1.9.0/154，Room11/JSON10，pending_acceptance**。家庭服务已部署个人云备份协议，镜像仍为 `mymoney-home-sync:cloud-backup-153`；154只改App导航。工作区未提交/推送，APK未发布。

小米14（`[设备序列号]`，`[型号]`）已按用户要求先后保数据安装153、154，两个 `adb install -r` 均返回 `Success`；安装后未打开App或做其他操作，没有逐字段保数据核对，不据此声明连接、备份或返回验证成功。Mi10本轮最近工程验证版本为153，不冒充已装154。

[当前154安装包](dist/app/1.9.0-154/20261106-064211-hkmfj9cp/MyMoney-1.9.0-154-release.apk) · [进度](docs/PROGRESS.md) · [验收与反馈](docs/ACCEPTANCE.md) · [剩余工作](docs/PLAN_1.0.md)

本地JSON入口目前收在云备份主页“更多→本地备份文件”，用户已明确确认“更多”正常显示，JSON实际导入/导出仍未确认；详细说明见[备份与恢复](docs/FAMILY_BACKUPS.md)。


</details>
