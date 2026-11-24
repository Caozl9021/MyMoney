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

## 2026-11-12 资产 Tab 布局精简（用户验收通过；未出包）

- **用户结论**：用户于 2026-11-12 明确确认**「验收通过」**（OpenSpec `refine-asset-tab-layout`）。本次不升版本、不出包。
- **总览卡（`AssetNetWorthCard`，仅 `isTab`）**：一张卡。有金额时净资产用 `AbType.amountHero`；净资产为负且未隐藏金额时用 `p.expense`。总负债大于 0 时，卡内 `1.dp` 分隔线下并排「总资产 │ 总负债」，总负债未隐藏时用 `p.expense`。总负债为 0 时不画这一行。隐藏金额时净资产和总负债都用 `p.label`。已删除 `AssetMetricTile`。
- **账户列表（`AssetsScreen`，Tab 与设置 → 资产共用）**：`ASSET_GROUP_THRESHOLD = 5`。达到阈值时按支付账户、储蓄卡、信用账户、现金、其他资产、其他负债分组，空组不显示；组标题（`AssetGroupTitle`）右侧是该组可见账户 `AssetMath.balance` 之和，隐藏金额或未分组时不显示。不足 5 个时一张「资金账户（N）」，标题右侧无金额。不显示「长按拖动排序」。
- **排序**：每组一个 `DragReorderColumn`，`onReorder` 只把本组 id 交给 `AssetStore.reorderActive`。信用账户行不增加「欠款/溢缴」。
- **未在实机覆盖**：深色、大字、隐藏金额时的负债色、组内长按拖动。证据 `runs/20261112-1423-asset-tab-layout/outputs/verification.md`。

## 2026-11-18 ~ 2026-11-19 全局设计系统收束：Lucide 图标体系、纯色圆形白标志徽章与跨页一致性（用户验收通过）

> 基础样式重构已随 2026-11-18 提交 `1774fd3` 合入 `master`；2026-11-19 按用户反馈将全站全局圆形图标徽章统一由浅底深标志调整为**纯色实底 + 白色标志**，并经 Release `1.11.0/163` 安装小米 14 后由用户确认**「验收通过」**（工作区暂未提交与发布 `dist/`）。

| 规范项 | 当前约束与实现 |
| --- | --- |
| 底部悬浮 Dock 留白 | `PageScaffold` 与 `SubPageScaffold(reserveDock = true)` 统一叠加 `dockClearance = 24.dp`，滚动到底部时卡片/列表末项完整避开悬浮胶囊与阴影 |
| 图标规范（Lucide） | `AbIcons`（34 项）与 `CategoryIcons`（全量收支分类与设置图标）全量迁移为官方 **Lucide Icons**（`24×24`、`2.0f` 等宽圆头圆角线性矢量），与底部 `AnimatedTabIcons` 完全同源 |
| 纯色圆形白标志容器 | `CategoryBadge`、`SettingsIconBadge`、`IconBadge`、`AccountAvatar` 文字头像、`ConfirmResultSheet` 完成钩圆圈与分类预设添加按钮统一采用 `CircleShape` + `solidBadgeBackground(tint)` 纯色实底与 `Color.White` 白色前景（过亮浅色由 `solidBadgeBackground` 自动压深以确保白标志清晰）；详情页 `RoundedSquareIconButton` 为 `CircleShape`（标为可报销激活态用纯蓝实底 + 白色发票图标），`RoundedSquareStatusButton` 使用 `AbRadius.capsule`；设置页周期/历史/备注/外观图标已去重 |
| 语义色板（`Tokens`） | `AbAccentSwatches` 与 `swatchFor(name)` 按语义固定映射（`餐饮` `#E67E22`、`购物/服饰/人情` `#E04F5F`、`交通/数码/旅行` `#3B82F6`、`居住/通讯/缴费` `#0D9488`、`娱乐/学习` `#8B5CF6`、`医疗/运动/育儿/宠物` `#10B981`、`工资/奖金/理财` `#D97706`、`退款` `#0E7490`、`其他/未分类/转账` `#64748B`），保证 `BillRow`、`AccountDetailsCards`、`BillActionsHost`、`StatisticsRing`、`StatisticsBreakdownRow` 颜色一致 |
| 默认值与去工程化 | `BillEditorDialog` / `ConfirmSheet` 新建手动账单默认选中首个分类（「餐饮」/切换收入为「工资」）与首个可用资产账户（如「钱包」）；`BillActionsHost` 隐藏空账户「未提供」、未初始化账户隐藏余额联动状态与开关、纯手动无扩展字段账单隐藏「来源与识别信息」；`HomeScreen` 暂停提示改为单行浅底圆角条 |
| 跨页细节与对比度 | `AssetNetWorthCard` 圆角统一为 `AbRadius.group (20.dp)`；`BillRow` 账户标签、`AssetGroupTitle` 组小计、`StatsScreen` / `StatisticsCharts` 次级标签统一提升至 `p.secondaryLabel`；`LedgerConfigCard` 头部采用 `Column` 竖排标题与「长按拖动排序」避免文本重叠 |

- **验证与验收（2026-11-19 纯色底 + 白标志改版后）**：`scripts/check-ui-components.sh` 输出 `UI component check passed.`；`:app:testDebugUnitTest`（124 文件 **966 项**）+ `:billkit:testDebugUnitTest`（30 文件 **239 项**）共 **1,205 项** 全部通过（0 失败/错误/跳过）；`emulator-5554`（AVD `mymoney_api35`，Android 15）在浅色与深色模式下分别完成首页、账单详情、统计、设置与支出分类页截图检查；签名 Release APK `1.11.0/163`（SHA-256 `f7f1d858…0b7e`，证书 `05212beb…66c0` 连续）保数据安装小米 14（`100.107.134.40:38253`）后用户确认「验收通过」。

## 2026-11-20 悬浮底栏液态玻璃滑块（1.11.1/164，用户验收通过）

| 组件 / 文件 | 当前约束与实现 |
| --- | --- |
| `LiquidGlassTabBar` / `dockStage` | `CapsuleShadowView`、`LiquidGlassView` 与全 bleed `ComposeView` 统一收进居中 `dockStage`（`roundToInt()` 高度 + `Gravity.CENTER`）；`LiquidGlassView` 模糊半径 `(4.5f * density).coerceAtMost(18f)`、玻璃 `tintAlpha` 浅色 `0.42f` / 深色 `0.22f`；API 33+ 同级挂载 `LiquidPillLensView`；`pointerInput` 仅挂在内层 `Row`，`18.dp` bleed 透传页面触摸 |
| `LiquidPillLensView`（AGSL） | 仅在 `lensMix > 0.005f` 时录制 `contentSource` + `dockStage`（静止零开销）；各向异性折射保持左右 `15dp / 19dp` 折射、`0.145f` 放大与 `0.24f` 色散，垂直方向收窄带宽（`0.36`）与弱化 `verticalRefraction = 0.08f`、`verticalMagnify = 0.55f` 消除上下图标倒影拉伸；`rimWidth = 1.55dp`，浅色内部微提亮 `0.06`、外影 `0.026`、外缘暗廓 `0.22`、倒角高光 `0.25`（深色外影 `0.045`、暗廓 `0.20`、高光 `0.28`） |
| `LiquidTabCells`（`Chrome.kt` / `Tokens.kt`） | 静止灰囊 `inset = 5.dp`、`extraPad = 0.dp` 且去白描边，浅色 `tabIndicator = 0x1C767680`（`pageBg = #F7F7F7`）；起飞/滑行按 `scaleX = 1f + 0.34f * l + 0.14f * flow`、`scaleY = 1f + 0.36f * l - 0.04f * flow` 漫出底栏；点击切换采用「快位移 → 飞行保持清澈 → 到位后缓收并 0%→100% 回灰」两段节奏；支持 1:1 跟手横滑、边缘橡皮筋回弹与速度接力 |
| 空间实时着色与无障碍 | 使用 `ClipOp.Difference`（外层未选中态 `inactiveColor = p.label.copy(alpha = if (isDark) 0.72f else 0.78f)`）与 `ClipOp.Intersect`（水滴内层 `p.accent`）双层裁剪；叠加层 `clearAndSetSemantics {}` 保持单节点与静默复点语义；`TabCell` 采用 `11.sp / 12.sp lineHeight`（`includeFontPadding = false`）与 `23.dp` 图标盒垂直光学居中 |

- **当前状态**：底栏效果已在 `1.11.0/163` 候选包获用户验收通过，随后升版至 `1.11.1/164` 并打 tag `v1.11.1` 发布。

## 2026-11-24 悬浮底栏 Dock 物理动效与光学细节定稿（1.11.1/164，用户验收通过）

| 组件 / 机制 | 当前定稿约束与实现 |
| --- | --- |
| 大胶囊呼吸与矩阵同步（`LiquidGlassTabBar` / `LiquidPillLensView`） | `LiquidPillLensBridge.onDockTransform` 驱动 `dockStage` 切页/松手微膨（`+3.6%` 宽 / `+5.2%` 高，`dampingRatio = 0.48f, stiffness = 230f`）与横滑限幅随动（`±3.5.dp`）；`LiquidPillLensView.updateLensNode()` 同步对录制画布与水滴坐标做缩放平移映射，关闭 `LocalLiquidGlassEnabled` 或 `LocalReduceTransparency` 时立即复位 |
| 速率挤窄与果冻回弹（`Chrome.kt`） | 按住静止保持饱满胶囊（`scaleX = 1f + 0.26f * lx`、`scaleY = 1f + 0.28f * ly`）；横滑由实时速率 `activeSpeed / (activeSpeed + 3.2f)` 驱动 `dragSqueeze`（`0.46f, 340f`）对称挤窄至最低 `0.76f` 并纵向微鼓，停顿 `45ms` 或松手以 `0.42f–0.44f` 低阻尼舒张回弹；`position` 拖动追踪采用 `spring(0.68f, 900f)` |
| X/Y 错相与起滑防跳（`Chrome.kt`） | 拆出横向 `lift`（`0.60f, 220f`）与纵向 `liftY`（`0.74f, 270f`）错相弹性收束；选中 Tab 边缘按下起滑使用 `dragAnchorX` + `dragStartPos` 相对增量推进消除瞬跳；各手势退出分支收口于 `settleVisualsToRest()` |
| 内壁跟随柔光（`Chrome.kt`） | 开启液态玻璃且 `frame.lensMix > 0.005f` 时，在严格裁剪于母胶囊轮廓的 `dockGlowClipPath` 内叠加随水滴中心移动的 `BlendMode.Plus` 径向柔光（浅色中心 `0.22f` / 深色 `0.14f`） |

- **验证与验收**：`scripts/check-ui-components.sh` 通过，`:app:testDebugUnitTest --offline`（106 文件 **800 项**，含 `LiquidTabIndicatorTest` **12/12**）全绿，Release `1.11.1/164`（SHA-256 `d6db9d25…1d4e`）安装小米 14 后用户确认「dock完美了」，并已将 Dock 方案与模板同步整理至个人 AI 知识库。

## 2026-11-24 局内组件、物理动效与分区域液态玻璃规范定稿（1.11.1/164，用户验收通过）

| 能力域 | 核心实现 / 规范 | 位置 |
| --- | --- | --- |
| 统一弹簧动效 | `AbSpring`（`navigation` / `sheet` / `modal` / `morph` / `bouncy`）与 `-22%` 视差 `liquidNavigationTransition` | `LiquidMotion.kt` / `Chrome.kt` / `SettingsScreen.kt` |
| 轻透表面与高光 | 修复 `glassSurfaceAlpha` 放大公式、新增 `glassBlurRadiusDp` 与斜向高光 `liquidSpecularRimBrush`；移除确认卡与芯片深色/红黑边框；`badgeLiquidGlaze` 收敛为极细斜向边光 | `Tokens.kt` / `SettingsIcons.kt` / `Components.kt` |
| 分区域通透度控制 | `Appearance.LIQUID_GLASS_PER_ZONE_CLARITY` + 四通道 `dock` / `header` / `sheet` / `dialog`（`LocalLiquidGlassClaritySpec` / `resolveClaritySpec`），入口收口至「外观 → 液态玻璃」极简三级页 | `Appearance.kt` / `Theme.kt` / `SettingsScreen.kt` |
| 悬浮胶囊与二级分类 | `HeaderGlassApi33Holder` 升级为 `11.5dp` 环带、`5.5dp` 边缘位移、`0.12` 色散与 `4.5dp` 浅层模糊（含 `PageScaffold` 大标题兜底）；`CategoryPicker` 升级为 `20.dp` 底层模糊与无框主题色选中胶囊 + 锚点缩放 | `FloatingHeader.kt` / `Chrome.kt` / `CategoryPicker.kt` |
| 滚轮时间与窗内浮卡 | `LiquidWheelTimePicker` 双列惯性滚轮 + `AppDialog` 动态高度适配 + `LocalDialogDismiss` 对称退场；`AssetBalanceSheet` 迁入窗内 `GlassBottomSheetBox`；`ConfirmSheet` ↔ `ConfirmResultSheet` 原生变形 | `LedgerDateTimeDialog.kt` / `AppDialog.kt` / `AssetsScreen.kt` / `ConfirmSheet.kt` |

- **验证与验收**：`scripts/check-ui-components.sh` 通过；`:app:testDebugUnitTest :billkit:testDebugUnitTest --offline` 共 **836 项**全绿（`:app` 107 文件 808 项 + `:billkit` 4 文件 28 项）；签名 Release `1.11.1/164`（SHA-256 `7d5204d9…49b9`）安装小米 14 后获用户验收通过。
