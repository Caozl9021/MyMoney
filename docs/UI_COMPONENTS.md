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

## 2026-11-09 页边与视觉对齐统一（用户验收通过）

- **用户结论**：Release 包覆盖安装至 Xiaomi 14 后，用户于 2026-11-09 明确确认**“验收通过”**。
- **页边与对齐约束**：
  - `FloatingHeaderAction`：保持 `48.dp` 触控热区，视觉 `38.dp` 玻璃圆胶囊向右补偿 `+5.dp`，使右上角胶囊右边缘与左上角标题胶囊左边缘、下方卡片左右边缘统一对齐 `AbSpacing.page (16.dp)`。
  - `StatsScreen`：通过 `contentPadding(top = padding.calculateTopPadding())` 让滚动内容自然穿过顶部悬浮玻璃胶囊并触发折射/模糊，消除半空视口硬裁切。
  - `GroupHeader` / `GroupFooter` / 首页日期 / `AssetGroupTitle` / `LedgerDayHeader` / `LedgerFilters` 摘要：水平内缩统一为 `AbSpacing.m (12.dp)`，避开卡片 `AbRadius.group (20.dp)` 圆角弧度并与卡片内边距对齐。
  - `SubPageBar`：补齐底部 `0.5.dp` `p.separator` 分隔细线；`BillsScreen` 统计下钻范围说明补齐 `padding(horizontal = AbSpacing.page, vertical = AbSpacing.xs)` 并移除列表底部冗余的 `+ 64.dp` 留白。
- **验证**：`scripts/check-ui-components.sh` 通过，`:app:testDebugUnitTest` + `:billkit:testDebugUnitTest` 全绿，`:app:assembleRelease` 构建并安装真机验收通过。

## 2026-11-11 首页、账单详情（方案 A）、拖拽排序与统计/明细全局样式统一（用户验收通过）

- **用户结论**：用户于 2026-11-11 明确确认**「验收通过，首页和统计页先这样」**（OpenSpec `refine-home-and-bill-row-density`）。
- **核心组件与视觉约束**：
  - **首页与总览卡（`HomeScreen` / `SummaryCard`）**：移除「账本」悬浮胶囊下方的独立今日日期行，总览卡直接作为首卡呈现；移除非 1 号记账周期下的起止日期范围文本（1 号起算显示「本月支出」，非 1 号显示「本期支出」）。
  - **账单列表行（`BillRow` / `Typography`）**：右侧金额样式 `AbType.amountRow` 调整为 `15.sp Normal`；移除右侧「待报销 / 已报销」文字行，改用金额颜色编码（普通支出 `p.expense` 红色、待报销年轻明黄色 `0xFFF5A623` / `0xFFFFD60A`、已报销灰色 `p.secondaryLabel`）；左侧副标题去掉一级分类名，按 `时间 · 备注` 顺序拼接。
  - **账单详情页方案 A（`BillActionsHost` / `DeleteBillDialog`）**：合并原两级页面为单页「账单详情」；主卡采用紧凑收据头（`CategoryBadge` + `一级分类 · 二级分类` + 类型副标题 + 右侧金额）与左右单行键值对 `BillDetailRow`；隐藏默认态的「未标记」报销与「已计入账户余额」，识别元数据收纳于默认收起的「来源与识别信息」折叠卡；底部操作栏采用 `48.dp` 圆角方形（`14.dp` 圆角）图标按钮（灰色/蓝色发票 `AbIcons.Receipt` + 旁边展开报销状态按钮、铅笔修改 `AbIcons.Pencil`、红色垃圾桶删除 `AbIcons.Trash`）；删除确认框精简文案并采用人性化「删除后金额将返回『XX』」表述。
  - **全局点击态、分隔线、拖拽与子页过渡（`Theme` / `Components` / `DragReorder` / `Chrome`）**：
    - `MyMoneyTheme` 提供 `LocalIndication provides NoIndication` 且 `SwitchCanvas` 显式 `indication = null`，消除开关与按钮上的灰色矩形按压/悬停底色；
    - `InsetDivider` 增加右侧内边距 `endInset`，使卡片内分隔线左右留白对称不顶边；
    - `DragReorderColumn` 使用静态 `.drawWithContent` 修饰符绘制托起态（`14.dp` 圆角矩形实心 `p.cardBg` 底 + 约 `8%` 极浅细描边与 `2.2%` 外缘柔光），不重建手势节点链且无黑重阴影；
    - `SlideSubPage` / `SlideSubPageValue` 及 `AnimatedContent` 统一子页 220ms 左右横滑推入/推出动画，详情打开编辑面板保留完整底部弹簧上滑动画。
  - **统计页（`StatsScreen` / `StatisticsCharts`）**：
    - 顶部 `[周 | 月]` 与 `< 周期 >` 换行阈值收紧为 `260.dp` 保持同行，`[支出 | 收入 | 退款]` 切换条采用 `Segmented(..., compact = true)`（`32.dp` 高度）；总览卡去掉「转账 X 笔不计收支」尾注；
    - 每日趋势卡去掉底部重复合计与图例，选中日详情（`M月d日 周X · ¥XX.XX · N 笔`）显示在卡片右上角；周柱状图与月热力图选中某天时其余项柔和变淡，月热力图选中格采用 `1.dp` 语义色浅描边与浅色底，去除原 `2.dp` 黑框；
    - 「构成占比」一体卡合并标题、紧凑 `[分类 | 账户]` 切换器、圆环图（中心动态显示选中项名称/金额/占比/笔数）与排行列表（`StatisticsBreakdownRow`：左侧 `CategoryBadge`/`AccountAvatar`、中间 `名称 + 百分比` + `4.dp` 进度条、右侧 `金额 + 笔数` + `ChevronRight`）。
  - **明细页（`BillsScreen` / `LedgerFilters` / `MainActivity`）**：
    - 顶栏右侧精简为「记录历史」与「记一笔」图标按钮；搜索框压缩为 `38.dp` 无边框胶囊，筛选胶囊（`LedgerFilterPill`）统一为 `32.dp` 紧凑高度，底部单行展示汇总说明与 `清除筛选`，筛选弹窗统一使用 `AppChoiceDialog`；
    - 统计下钻进入时顶部展示 `InsetGroup` 汇总头卡（左侧 `周期 · 类型` + `共 N 笔`，右侧总金额），且点击排行行直接下钻、不挂起等待全屏截图、不闪烁首帧加载占位卡；
    - 每日账单卡片统一采用与首页一致的卡片内日汇总头 `LedgerCardDayHeader`（左侧 `MM/dd`，右侧当日收支小计 + 缩进分隔线），移除卡片外逐日 SQL 汇总查询及列表底部冗余历史卡与脚注。
