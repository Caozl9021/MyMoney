# UI公共组件与当前约束

<!-- acceptance-158-20261107 -->
> **当前版本1.10.0/158，用户验收通过（2026-11-07）**。用户确认现有交付通过，淘宝闪购订单抓取／实时验证继续作为后续工作。管理台、扫码连接、备份删除及历史恢复改进已纳入本次版本验收。工程覆盖按已有证据登记。验收详见[ACCEPTANCE](ACCEPTANCE.md)，淘宝后续见[实时验证交接](SHANGOU_LIVE_VALIDATION_HANDOFF.md)。以下历轮版本、待验收和测试描述按原日期理解。
<!-- /acceptance-158-20261107 -->


155：主页历史卡片下一行右对齐TextButton“更多”；管理模式选择/删除上限100，确认框目前只显示份数与大小。CONNECTION/BACKUP/HISTORY是共享busy的状态类型，并非并发任务队列；更多及本地入口不受网络busy禁用。

## 历史155进度复核（2026-11-06，仅文档）

> 历史版本快照（原日期口径） 当前候选 **1.9.0/155，Room11/JSON10，pending_acceptance**。删除历史、更多下移右对齐、context请求精简和Go HTTP复用主体已实现；155尚无用户验收。154返回修复/原“更多正常显示”分项通过保留。

本轮复核候选SHA五项通过，现存JVM XML为1180/0/0/0；服务端76 pytest、部署镜像155和Mi10显式直连实测引用原记录，未重跑/操作在线环境。已有实机证据是单选删除，批删能力不等于真实多选已测；内嵌通道和分段耗时未闭环，HTTP请求减少不等于连接慢已解决。

静态待收尾：R1删除重试未保留operationId；R2删后分页仍用旧offset；R3文件已不在时回收状态不能收尾；R4新备份未失效context缓存。详见[实现复核与后续](../runs/20261106-181513-155-progress-review/outputs/implementation-review.md)。本轮只同步文档，不改源码/APK/旧证据，不操作手机或服务。

> 历史版本快照（原日期口径） 当前1.9.0/155；以下为源码约束（143 的间距/重复提示项保持不变），实测范围单列，不能理解为完整矩阵通过。

| 组件 | 约束 |
| --- | --- |
| ConfirmSheet | 共用悬浮窗/Activity/编辑器本体；金额下方疑似重复仅一行“（可能重复）”，无摘要/解释 |
| 金额与重复标签 | 同一紧凑Column，不叠加标签上padding与外层间距；标签至类型滑块12dp，无标签保留原布局/错误行间距 |
| 金额输入/忽略 | 保留字号自适应与bounds交接，忽略最小48dp目标；不靠负偏移盖住控件 |
| Segmented | 收支类型及记录历史五项滑块；不将两种识别方式拆成两套确认卡 |
| ConfirmDelivery | 自动弹卡仍受autoConfirm和锁屏/呈现条件控制，不是自动保存；未知回执不盲目重投 |
| BillActionsHost | 各入口统一账单查看/编辑，删除先确认，持久只读日志；没有5秒撤销或日志恢复 |
| FloatingHeader | 四Tab普通玻璃材质/中性阴影；账本绿色光晕/虚线状态圈已退出，标题点击仍进自动记账 |
| SubPageScaffold/BackSurface | 按原父级返回、设置重选回设置首页、保留未保存离开保护；预测返回残影仍按历史未决处理 |
| 云备份（CloudBackupPage） | 主操作「立即备份」，历史自动加载；主页左上内容区「更多」收纳连接管理/本地备份文件；未连接为连接码+显式粘贴/连接，不暴露旧规则/ledger/互导入口。用户已明确确认「更多」正常显示；本地JSON实际导入/导出不因此验收 |
| 云备份子路由 | 154主页/历史/连接管理使用SettingsScreen统一栈；系统返回与页面返回共用，去掉内部rememberSaveable page。小米14已安装，用户明确验收返回修复并确认“更多”正常；未逐项报告的手势工程覆盖另列 |
| SectionColumn/ListRow | 组间距/触控遵循design tokens；内容长/大字可增高，不机械套叠多层padding |

153 Mi10已实看云备份/历史及恢复预览；154未代操作小米14。143仅在Waydroid400×890/density160的ConfirmActivity同一演示卡实看间距。其他宿主复用实现，但本轮没有逐个实跑；大字/窄屏/深色完整矩阵、Release UI和完整TalkBack未补齐。用户继续操作的演示卡不自动保存、忽略或清理。详见[143证据](../runs/20261105-151939-duplicate-label-spacing/outputs/verification.md)与[验收清单](ACCEPTANCE.md)。

## 2026-11-08 P2 UI 弹窗统一（工作区实现，待验收）

> 基线 1.10.0/158；本轮 P2 只处理 UI 收尾，不改变数据库、备份协议、识别规则或云备份接口。工作区未提交、未发布；以下为源码与 Debug 构建约束。

### AppDialog 2.0

| API | 用途 | 约束 |
| --- | --- | --- |
| `AppDialog(title, onDismiss, content)` | 自定义内容底座 | `usePlatformDefaultWidth=false`；宽度上限 400dp；圆角 `AbRadius.card`；内容区 `verticalScroll`、最高 560dp、`imePadding` |
| `AppDialogActions(...)` | 统一按钮行 | 取消左、确认右、等宽；单按钮通栏；`destructive` 用 `DestructiveButton`；`busy` 时取消禁用并显示 `busyLabel` |
| `AppAlertDialog(...)` | 纯确认/说明 | 标题 + 正文 + 统一按钮行；危险操作传 `destructive=true` |
| `AppChoiceDialog(...)` | 单选列表 | `InsetGroup` + `ListRow` + 尾勾；选项多时优先于 Segmented |
| `AppInputDialog(...)` | 单字段输入 | `InsetGroup` + `InputRow`，不引入 M3 `OutlinedTextField`；业务校验由调用方持有 |
| `AppActionSheet(...)` | 底部动作面板 | 替代 `DropdownMenu`，ListRow 动作 + 取消；按 `WindowInsets.navigationBars` 留出底部安全区，避免“取消”被系统导航栏裁切 |

### 迁移状态

- 34 处 M3 `AlertDialog` 实际调用已全部迁移；页面代码中 `androidx.compose.material3.AlertDialog`、`Checkbox`、`DropdownMenu` 实际调用为 0。
- `DiscardChangesDialog` 统一使用 `AppDialog` + `AppDialogActions`。
- `scripts/check-ui-components.sh` 作为防回归闸门，白名单只允许 `ui/design/AppDialog.kt`；`build-all.sh` 在 clean 前执行该检查。
- 本批保留 `DatePickerDialog`/`DateRangePicker` 与页面内 `TextButton`、`Snackbar`，不把它们纳入本批硬闸门。

### 验证状态

- `./gradlew :app:testDebugUnitTest :billkit:testDebugUnitTest`：app 950 + billkit 239 项，0 失败、0 错误、0 跳过。
- 真机（Xiaomi 14 / Android 17）：云备份“更多”动作面板修复底部安全区后，`取消` 完整可见、底角圆角正常；截图 `runs/20261108-p2-ui-polish/shots/real-device-more-sheet-fixed.png`。
- `./gradlew :app:assembleDebug`：BUILD SUCCESSFUL；`./gradlew :app:assembleRelease`：BUILD SUCCESSFUL；`scripts/check-ui-components.sh` 输出 `UI component check passed.`。
- Waydroid 实看：云备份页、更多动作面板、恢复默认分类弹窗（浅色/深色/特大字号/360dp 窄屏）、分类预设、添加账户、备注模板、识别规则、记录历史、外观深色、相机权限拒绝弹窗；家庭备份页因无家庭服务连接且当前导航不可达，未做页面级实看。
- 证据：`runs/20261108-p2-ui-polish/`（截图与 verification.md）。
