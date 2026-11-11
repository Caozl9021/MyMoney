# 文档索引

<!-- release-161-20261111 -->
> **当前发布线1.10.2/161**（2026-11-11 已发 GitHub Release `v1.10.2`；`latest-app.json` pending_acceptance）。**资产添加向导及相关交互**、**首页/账单详情（方案 A）/拖拽样式/统计与明细页全局样式统一（`refine-home-and-bill-row-density`）**均于 2026-11-11 获用户验收通过；整版含报销待用户确认。家庭服务 `cloud-backup-161`（format 8–12）。前一整版验收为1.10.0/158。淘宝被动抓取已判定不可行。详见[ACCEPTANCE](ACCEPTANCE.md)、[PROGRESS](PROGRESS.md)、[实时验证交接](SHANGOU_LIVE_VALIDATION_HANDOFF.md)。
<!-- /release-161-20261111 -->


> 历史用户结论（2026-11-07）：**1.9.0/157 AA历史备份与恢复改进验收通过**——用户明确“改过代码了，验收通过”，并自测小米14两千多笔通过。当前源码 versionCode=157/versionName=1.9.0，`dist/latest-app.json` status=accepted。范围仅本轮历史备份浏览/按需下载/进度/缓存复用/取消与列表解耦的 AA 改动；不扩为 Z 管理台/扫码全部、Y 历史未验收项或云备份全范围。以下 154/155 段落按历史快照阅读。
>
> 更早（2026-11-06）：“返回修复验收通过，更多按钮正常显示”。

历史快照（2026-11-06，1.9.0/154，pending_acceptance）：家庭服务已部署个人云备份协议。153/154 保数据安装小米14 均 Success，但安装不代验证。APK 未提交/推送/发布（截至该快照）。

## 按用途查找

| 想了解什么 | 文档 |
| --- | --- |
| 当前进度、安装事实、最新反馈 | [PROGRESS](PROGRESS.md) |
| 当前未关闭问题与待做项 | [PLAN_1.0](PLAN_1.0.md) |
| 用户已验收和仍待确认 | [ACCEPTANCE](ACCEPTANCE.md) |
| 个人云备份设计/身份/边界 | [PERSONAL_CLOUD_BACKUP_PLAN](PERSONAL_CLOUD_BACKUP_PLAN.md) |
| 本地JSON与云端历史恢复入口 | [FAMILY_BACKUPS](FAMILY_BACKUPS.md) |
| 管理令牌、服务地址与连接码 | [CONNECT_CODE_QUICKSTART](CONNECT_CODE_QUICKSTART.md) |
| 全部OpenSpec变更分类 | [OPENSPEC_STATUS](OPENSPEC_STATUS.md) |
| 项目范围 | [PROJECT_SCOPE](PROJECT_SCOPE.md) |
| 构建/签名/发布与测试环境 | [打包](PACKAGING.md)、[版本](VERSIONING.md)、[测试环境](TEST_ENVIRONMENT.md) |
| 架构、部署、使用速查 | [架构](../ARCHITECTURE.md)、[部署](../DEPLOYMENT.md)、[速查](../QUICKREF.md) |
| 识别/规则/OCR | [规则](LOCAL_RULES.md)、[OCR](OCR_ASSISTANCE.md)、[LSP](LSP_MODULE.md) |

用户已明确验收154返回修复并确认“更多”正常显示。仅关闭这两项，不把153/154云备份整体、本地JSON文件操作或双机链路自动验收；旧W/M3/M4资料按历史解释，当前采用X个人云备份。

<details>
<summary>本次同步前原文（历史快照，版本与结论按当时范围解释）</summary>

## 1.9.0/154 云备份返回修复候选（2026-11-06，待用户验证）

历史备份/连接管理改走统一设置导航栈，去掉云备份内部独立保存的子页状态，修复系统返回跳过主页、重进恢复旧子页的问题。JVM1175与三构建通过，strict35/35；154仅构建收集，**未安装或操作手机**，未改服务/备份数据。当前latest=154 pending_acceptance；153的备份/恢复实测只按153范围保留，不当作154返回实测。

证据：[154导航修复核查](../runs/20261106-063858-cloud-backup-navigation/outputs/verification.md)。

## 1.9.0/153 个人云备份（2026-11-06，已实现，待用户验收）

- 设置→迁移与备份只保留“云备份”：立即备份、本设备历史、预览/二次确认恢复；本地文件与连接管理放到更多。
- 设备安装UUID+凭证复用，登记幂等；重装经授权接替原来源、保留历史并使旧凭证失效。服务端强制只访问本设备备份；五分钟一次性业务连接码，管理页一键生成、手机显式粘贴。
- 用户明确授权清表重来，已保存可恢复数据库快照后重置家庭服务专用表；旧21来源清空，Mi10新登记后1条，2份完整快照各58笔。手机账单、其他schema、文件卷与网络节点未清，未复制密钥到服务配置。
- Mi10已安装最终153 Release，保数据、备份→预览→实际恢复→再备份及冷启复用通过；58笔业务内容/sid与账户快照前后一致。JVM1175、服务端pytest65、身份仪器4+内存恢复8、strict35/35。
- Xiaomi14尚未安装153/重连并创建新备份；真实双机/重装接替和复杂资产转账真机验证仍开放。自动备份、扫码、有新改动指示及不可达旧服务器主动解绑未实现。
- 家庭服务已部署；APK仍pending_acceptance，未提交/推送/发布。148已有用户验收保留，153不因工程验证自动验收。

证据：[153交付核查](../runs/20261106-055644-personal-cloud-backup-implementation/outputs/verification.md)。当前方案见[个人云备份](PERSONAL_CLOUD_BACKUP_PLAN.md)。

以下为历史状态，当前执行范围以上文X阶段为准，W剩余任务不再整体作为当前需求必做。

## 当前交付（2026-11-06复核）

当前候选 **1.9.0/152，Room11/JSON10，pending_acceptance**；148内嵌家庭同步已按原范围验收，149–152家庭数据仍待验收。所列JVM1169、服务端pytest49及Mi10验证沿用原证据，本轮只更新文档，未重跑业务测试/构建/设备验证。

进度见[PROGRESS](PROGRESS.md)，剩余事项见[PLAN_1.0](PLAN_1.0.md)，验收见[ACCEPTANCE](ACCEPTANCE.md)。

## 用户验收结论（2026-11-06，优先于下方历史状态）

- **通过**：144再次确认；142重复提示精简、143标签间距；132统计下钻/记录历史/账单操作。
- **141分项**：交易详情自动识别通过；通知监听未继续深入优化，暂不通过。不得再把141整组写成待验收或整组通过。
- **138/139范围收敛后通过**：主动“从截图补记”功能取消，仅保留页面识别内部OCR辅助补字段；不新增并列采集通道。当前源码仍有SettingsScreen入口及OcrImportActivity声明，属于取消范围的残留，尚未清退；本次只同步文档，未改代码。
- **超级岛**：实验性功能，相关项目用户全部暂时验收通过；原“不出岛只出原卡”由用户确认是未授予通知权限，授权后正常，不再列为待定位/待用户验收。工程打包/装机摘要、源码提交与测试证据整理不因用户验收自动完成。
- **支付宝延时弹卡**：用户确认已修复，从历史遗留问题移除；本轮未定位修复提交或新增测试，不编造根因/验证数据。

未确认关闭的历史项：权限状态偶发过期、预测返回残影、整App流畅度全面结论；家庭同步failed按用户要求继续暂缓。OCR研究语料/预算、其他ROM/API/可访问性属于工程覆盖缺口，不重复要求用户验收已通过的功能。当前源码/本地Release输出146，候选指针仍145；不据此声明已发布。证据：`runs/20261106-110618-user-acceptance-sync/outputs/verification.md`。

# 文档索引



145 J阶段：移除未消费账本查询和旧会话写盘；过期观察释放自有预留、保留节流，等待参数不变。1105JVM/82隔离Room/3实际卡回归通过；当前交付和未测边界见[验证](../runs/20261105-193908-capture-cleanup/outputs/verification.md)，待用户验收。

当前候选1.9.0/145，待用户验收；更新日期2026-11-05。I阶段144已实施并同签名覆盖真机，真实应用行为待验收。

| 当前入口 | 内容 |
| --- | --- |
| [PROJECT_SCOPE](PROJECT_SCOPE.md) | 当前版本、两种识别分工、数据与未测边界 |
| [ACCEPTANCE](ACCEPTANCE.md) | 144再次确认/账本标签、143外观与G阶段真实使用待验收项目 |
| [PROGRESS](PROGRESS.md) | 各构建实施/验证/验收状态与历史证据 |
| [PLAN_1.0](PLAN_1.0.md) | 当前剩余项及原1.0发布合同 |
| [OPENSPEC_STATUS](OPENSPEC_STATUS.md) | 35个变更的当前关联与历史范围 |
| [架构](../ARCHITECTURE.md) | 准入/协调/投递/存储和OCR生命周期 |
| [验证](../TEST_REPORT.md) | JVM、合成设备、真实支付边界 |

| 功能与工程 | 文档 |
| --- | --- |
| 页面OCR、选图及Q阶段 | [OCR_ASSISTANCE](OCR_ASSISTANCE.md) |
| 识别规则、备注与学习 | [LOCAL_RULES](LOCAL_RULES.md) |
| UI/重复提示间距 | [UI_COMPONENTS](UI_COMPONENTS.md) |
| 资产/账期 | [ASSET_MANAGEMENT](ASSET_MANAGEMENT.md)、[LEDGER_MONTH_PERIOD](LEDGER_MONTH_PERIOD.md) |
| 导入导出 | [QIANJI_IMPORT](QIANJI_IMPORT.md)、[ICOST_IMPORT](ICOST_IMPORT.md) |
| 家庭服务/更新 | [FAMILY_BACKUPS](FAMILY_BACKUPS.md)、[M1–M5计划](HOME_SYNC_AND_APP_UPDATE_PLAN.md) |
| Root/凭证 | [ROOT_PLAN](ROOT_PLAN.md)、[LSP_MODULE](LSP_MODULE.md)、[LSP_SCOPE_CONFIG](LSP_SCOPE_CONFIG.md) |
| 构建/包与版本 | [部署](../DEPLOYMENT.md)、[PACKAGING](PACKAGING.md)、[VERSIONING](VERSIONING.md) |
| 测试环境 | [TEST_ENVIRONMENT](TEST_ENVIRONMENT.md)、[旧环境清理记录](LEGACY_EMULATOR_CLEANUP_REVIEW.md) |

M3/M4交接、BUG记录和旧self入口保留为历史；历史图和旧run不表示143已在真实支付/各ROM/API上通过。完整矩阵与用户最终验收继续开放。

</details>
