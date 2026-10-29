# 本地文档索引

同步日期：2026-10-29。当前交付 **0.32.0/99（麦米），待用户验收**。Room9/JSON8（兼容JSON1–7）。首页期摘要去掉重复今日行，三款图标联动品牌主题与柔和切换动画。0.31.0三款图标功能既有用户验收保持，0.28.5/0.28.4等历史独立范围不扩大，BUG-BACK-GHOST-001仍OPEN。

进度、剩余范围、执行证据与用户验收分别维护；本轮普通模式配置已实施并出包，历史验收不扩展；不清理资料。

| 用途 | 入口 |
| --- | --- |
| 产品和当前使用 | [项目README](../README.md) |
| 当前计划与真正未实施范围 | [PLAN_1.0](PLAN_1.0.md) |
| 最新实现/验收与历史证据 | [PROGRESS](PROGRESS.md)、[CHANGELOG](../CHANGELOG.md) |
| 最新测试与未测边界 | [TEST_REPORT](../TEST_REPORT.md) |
| 构建/签名/迁移 | [DEPLOYMENT](../DEPLOYMENT.md)、[QUICKREF](../QUICKREF.md) |
| 开发交接 | [AGENTS](../AGENTS.md) |
| 每月起始日与首页范围 | [LEDGER_MONTH_PERIOD](LEDGER_MONTH_PERIOD.md) |
| 钱迹导入 | [QIANJI_IMPORT](QIANJI_IMPORT.md) |
| iCost导出 | [ICOST_IMPORT](ICOST_IMPORT.md) |
| 资产账户与余额 | [ASSET_MANAGEMENT](ASSET_MANAGEMENT.md) |
| 规则与学习 | [LOCAL_RULES](LOCAL_RULES.md)、[规则管理台方案](RULE_CONSOLE_PLAN_2026-10-26.md) |
| 通知方案与剩余范围 | [NOTIFICATION_CAPTURE_PLAN](NOTIFICATION_CAPTURE_PLAN_2026-10-26.md) |
| SPEC 规则通道与 root 可行性 | [SPEC_INTEGRATION](SPEC_INTEGRATION.md) |
| Root及LSP | [ROOT_PLAN](ROOT_PLAN.md)、[LSP_MODULE](LSP_MODULE.md)、[LSP_QUICKSTART](LSP_QUICKSTART.md)、[LSP_SCOPE_CONFIG](LSP_SCOPE_CONFIG.md) |
| 历史文件留存/可清理候选（未获清理授权） | [清理审核清单](FILE_CLEANUP_REVIEW_2026-10-27.md) |
| 公共UI组件 | [UI_COMPONENTS](UI_COMPONENTS.md) |
| 历史需求 | [PLAN_0.5](PLAN_0.5.md)、[UX审计](UX_AUDIT_2026-10-24.md)、[早期UI](IOS27_UI_PLAN.md)、[视觉方案](VISUAL_REFRESH_PLAN_2026-10-25.md)、[后续视觉提案](UI_REFINEMENT_PROPOSAL_2026-10-26.md) |
| 确认卡精修与修订关系 | [确认卡方案](CONFIRM_SHEET_REFINEMENT_PLAN_2026-10-28.md) |
| 设置文案删除结果 / 弹层审计 | [S001–S064](SETTINGS_COPY_REVIEW_2026-10-28.md)、[弹层清单](POPUP_STYLE_AUDIT_2026-10-28.md) |

[本轮文档核查](../runs/20261028-234127-all-progress-docs-sync/outputs/verification.md) · [28项OpenSpec状态](../runs/20261028-234127-all-progress-docs-sync/outputs/openspec-status.md)


<details>
<summary>本次同步前的文档原文（历史快照，当前状态以上方为准）</summary>

> 2026-10-28 0.28.4/92交付及验收：LSPosed独立/寄生管理器入口兼容，新增用户点击的“重启微信与支付宝”、Root白名单/有界后台执行/按目标结果与自动加载检测。739项唯一JVM、三构建、strict28与连续签名/真机版本/APK摘要通过；管理器0.28.3中间包App入口两次真机打开LSPosed2.2.1(7912)。最终0.28.4已覆盖安装，代理未执行双目标重启；用户明确“验证通过”，**本轮功能用户验收通过**，不外推其他矩阵或历史未验收范围。Room9/JSON8不变，残影OPEN。证据`/home/caozl/Projects/AutoBook/runs/20261028-232511-restart-wechat-alipay/outputs/verification.md`；验收`deliverables/0.28.4/acceptance.md`；包`dist/AutoBook-0.28.4-release.apk`。

> 2026-10-28 0.28.2/90交付：按真机反馈精修共享放弃修改弹窗，中性宽卡片/居中文字/横向等宽按钮，放弃用语义红。三构建、strict28、连续签名与真机版本/APK摘要通过；小米23127PN0CC正式包浅色截图、临时草稿继续编辑/系统返回/点外部/明确放弃已实测，未保存账单，已回首页。未清数据或读私有库，深色/大字/窄屏和其他宿主未测。Room9/JSON8不变，既有验收保持、残影OPEN，**待用户验收**。证据`/home/caozl/Projects/AutoBook/runs/20261028-202738-discard-dialog-style/outputs/verification.md`；包`dist/AutoBook-0.28.2-release.apk`。

> 2026-10-28 0.28.1/89交付：按用户选择仅保留S030，其他63项处理（60处本轮移除、3处前轮已不存在），清理空说明占位及死入口。三构建、strict28、与真机现有0.27.0连续签名通过；已按要求覆盖安装小米23127PN0CC，回读版本/APK摘要与dist一致，授权标记一致。未清数据、未由代理启动/操作App，未读私有账本，本轮无JVM重跑或UI/交易验证。Room9/JSON8不变，0.27验收保持、残影OPEN，**待用户验收**。证据`runs/20261028-195614-remove-selected-copy/outputs/verification.md`；包`dist/AutoBook-0.28.1-release.apk`。

> 2026-10-28 0.28.0/88交付：资产Tab精简、管理移设置；账户余额首卡/更多及账期折叠明细，按用户澄清隐藏本金转账且保留真实余额。确认卡转账去分类、双端账户/备注/时间接原子保存，待确认转入ID持久化；Room9/JSON8兼容旧链及JSON1–7。721项JVM、Android专项6+资产14+备份8、三构建、strict28、连续签名/debug实装SHA通过；只读API35手动/待确认真实保存、浅深1.3/IME与原7文件恢复通过并退出，未操作真机，release及全悬浮宿主未实装验证。64条说明/状态与20处弹层审计已展示，其余文案和弹层未批量修改。0.27.0验收保持，残影OPEN，**待用户验收**。证据`runs/20261028-170253-asset-period-transfer-refinement/outputs/verification.md`；包`dist/AutoBook-0.28.0-release.apk`。

> 2026-10-28 验收更新：用户明确回复“验收通过”，**0.27.0/87首页与统计精简交付已获用户验收通过**。仅同步文档与验收元数据，未改源码/版本/APK、未构建或操作设备；既有验证及未测边界保留，BUG-BACK-GHOST-001继续OPEN。记录：`deliverables/0.27.0/acceptance.md`。

> 2026-10-28 0.27.0/87交付：首页正常采集说明改账本标题绿色内圈虚线，点击进自动记账；摘要翻期与日卡共用账期，去重复账单标题及全部账单入口，历史日期点击回本期；统计周月扁胶囊、日期固定布局及点击回当前。714项JVM（billkit100缓存）、三构建、strict28、Android内存Room7项及连续签名/debug实装SHA通过；API35只读模拟器翻期/空期/25日起算/运行暂停与深色1.3核查完成，13表不变，原7文件逐字节恢复并退出。未操作真机，release未实装，采集验证边界见报告。Room8/JSON7不变，残影OPEN，**用户验收通过**。证据`runs/20261028-161433-simplify-home-stats/outputs/verification.md`；包`dist/AutoBook-0.27.0-release.apk`。

> 2026-10-28 0.26.2/86交付：四页标题与加号/明细胶囊增加统一轻柔外阴影，保持尺寸、48dp触控和正文玻璃采样；三构建、strict28、连续签名/版本通过，API35只读模拟器四页浅深色截图完成。模拟器原11文件恢复SHA一致并退出，未操作真机；本轮无JVM重跑、无release实装验证，其他边界见报告。Room8/JSON7不变，残影OPEN，待用户验收。用户“可以”已记录0.26.1当前分类气泡交付接受。证据`runs/20261028-154259-header-shadow/outputs/verification.md`；包`dist/AutoBook-0.26.2-release.apk`。

> 2026-10-28 0.26.1/85交付：按反馈精修分类气泡，内容自适应宽度，外框28dp/滑块24dp圆角、间距4dp，共用弹簧滑块；滑入新候选轻触感一次，保留松手选择/移出取消/边缘滚动，修复长按不动松手串发一级点击。88类713项JVM、三构建、strict28、连续签名及实装SHA通过；API35只读模拟器宽度/动效/取消/18项边缘/深色1.3/IME与正式包升级通过。13表与全部偏好已恢复，测试包移除、模拟器退出，未操作真机；真实震感与视觉待用户验收。Room8/JSON7不变，残影OPEN。0.26.0气泡样式被用户驳回，其他范围不补记验收。证据`runs/20261028-143207-category-bubble-style/outputs/verification.md`；包`dist/AutoBook-0.26.1-release.apk`。

> 2026-10-28 0.26.0/84交付：确认卡去重复标题/长说明/商户输入，备注无图标紧凑自适应；二级同排展开、连续滑选/移出松手取消与边缘滚动保留；手动/编辑可选日期时分，转账手续费同步改期，真实成功直接收起无成功弹窗/Toast。备注全链路优先，来源商户/身份保留、空备注导出不偷填。87类711项JVM、三构建、strict28、连续签名/实装SHA通过；API35只读模拟器4项真实内存Room、7组核心UI、深色1.3/IME与18分类边缘检查、正式包升级/保存/详情入口通过。debug13表与全部偏好恢复一致，测试包移除、模拟器退出。未操作真机，未测矩阵见报告；Room8/JSON7不变，残影OPEN；**待用户验收**。证据`runs/20261028-132914-confirm-refinement/outputs/verification.md`；包`dist/AutoBook-0.26.0-release.apk`。

> 2026-10-28 0.25.0/83交付：按用户确认的分体玻璃落地四页统一顶栏，账本/资产仅加号、统计仅明细图标；按实施反馈缩为标题42dp/20sp、操作可见38dp/图标20dp，保留48dp触控，首页首卡前移20dp。独立正文采样/模糊/边缘折射，日期与摘要衔接、翻期方向修正。86类705项JVM、三构建、strict28、连续签名/实装SHA通过；API35只读模拟器最终紧凑正式包6组UI/深色1.3通过，调试包13表与偏好一致，已退出。未操作真机，低API/完整TalkBack/真机性能未外推；Room8/JSON7不变，残影仍OPEN；**待用户验收**。证据`runs/20261028-104221-split-glass-header/outputs/verification.md`；包`dist/AutoBook-0.25.0-release.apk`。

# 本地文档索引

历史已验收 **0.24.0/82，用户于2026-10-28验收通过**：移除系统框架保活增强，LSP仅保留微信/支付宝采集与目标加载检测。普通无障碍守护仍在。86类705项JVM、三构建、strict28、连续签名及API35只读模拟器升级/12项核对通过，未操作真机。

[验证证据](../runs/20261028-093149-remove-lsp-system-enhancement/outputs/verification.md) · [使用与验收](../runs/20261028-093149-remove-lsp-system-enhancement/outputs/DEVICE_TEST_GUIDE.md)。Room8/JSON7不变，作用域仅两目标，旧system_server注入待下次正常重启退出。

<details><summary>0.22.2/79历史索引快照（由后续版本替代）</summary>

当时交付 **0.22.2/79**（自动记账页重做成模式驱动：顶部一枚横向总开关，左「无障碍模式」右「Root 模式」，互斥，滑到哪边下面只显示那边的设置；识别通道与权限合并成一页，一条通道一行，开关与缺哪个授权同屏；LSPosed 采集已从识别通道移出、只留在「更多设置」且无 Root 时整行不出现；高级入口收进「更多设置」；Root 模式的能力判定只看 su（LSPosed 是装在 Root 之上的框架，且宿主看不见自己模块的启用状态，不能拿它顶替 Root），没 Root 时明说并给「授予 Root」跳转；真机核对通过，待用户验收），Room8/JSON7（兼容1–6）。四条采集通道：通知（无障碍走 `NotificationListenerService`，root 走 `dumpsys`，免通知使用权）、短信、LSPosed 采集（微信 WCDB / 支付宝 SyncMessage，**不属于任何模式**，前提是在 LSPosed 里启用模块并勾选微信、支付宝）、页面辅助（仅无障碍实现，root 模式下该通道在界面与模型里都不存在）。0.22.1 把「哪些通道存在」与「Root 能不能选」各自收成一个判定点（`channelsFor` / `modeGate`），页面里不再散落 `if (mode == …)`；新增 `AutomationPageTest` 11 项与 `ModeIsolationTest` 8 项与 `lsposedIsNotARootSubstitute`锁住门禁四态、已在 Root 不拦、以及「HOOK 不属于任何模式」。JVM 83 类 674 项 0 失败，正样本 23/23、负样本 45/45 不变，签名连续，设备回读 79/0.22.2。**仍未验证**：「确实没有 Root 也没有 LSPosed」的真机环境、深色/大字/减少动效下的新页面、androidTest、「更多设置」各入口的逐条跳转。`BUG-BACK-GHOST-001`（预测性返回残影）保持 OPEN/未解决。


</details>

本索引和专题是本机补充资料，运行证据在runs/，稳定交付在deliverables/。旧版本状态在下方折叠留档；当前剩余任务只维护[PLAN_1.0](PLAN_1.0.md)。

| 用途 | 入口 |
| --- | --- |
| 产品和当前使用 | [项目README](../README.md) |
| 当前计划与真正未实施范围 | [PLAN_1.0](PLAN_1.0.md) |
| 最新实现/验收与历史证据 | [PROGRESS](PROGRESS.md)、[CHANGELOG](../CHANGELOG.md) |
| 最新测试与未测边界 | [TEST_REPORT](../TEST_REPORT.md) |
| 构建/签名/迁移 | [DEPLOYMENT](../DEPLOYMENT.md)、[QUICKREF](../QUICKREF.md) |
| 开发交接 | [AGENTS](../AGENTS.md) |
| 每月起始日与首页范围 | [LEDGER_MONTH_PERIOD](LEDGER_MONTH_PERIOD.md) |
| 钱迹导入 | [QIANJI_IMPORT](QIANJI_IMPORT.md) |
| iCost导出 | [ICOST_IMPORT](ICOST_IMPORT.md) |
| 资产账户与余额 | [ASSET_MANAGEMENT](ASSET_MANAGEMENT.md) |
| 规则与学习 | [LOCAL_RULES](LOCAL_RULES.md)、[规则管理台方案](RULE_CONSOLE_PLAN_2026-10-26.md) |
| 通知方案与剩余范围 | [NOTIFICATION_CAPTURE_PLAN](NOTIFICATION_CAPTURE_PLAN_2026-10-26.md) |
| SPEC 规则通道与 root 可行性 | [SPEC_INTEGRATION](SPEC_INTEGRATION.md) |
| Root及LSP | [ROOT_PLAN](ROOT_PLAN.md)、[LSP_MODULE](LSP_MODULE.md)、[LSP_QUICKSTART](LSP_QUICKSTART.md)、[LSP_SCOPE_CONFIG](LSP_SCOPE_CONFIG.md) |
| 文件留存/可清理候选（本轮未删除） | [清理审核清单](FILE_CLEANUP_REVIEW_2026-10-27.md) |
| 公共UI组件 | [UI_COMPONENTS](UI_COMPONENTS.md) |
| 历史需求 | [PLAN_0.5](PLAN_0.5.md)、[UX审计](UX_AUDIT_2026-10-24.md)、[早期UI](IOS27_UI_PLAN.md)、[视觉方案](VISUAL_REFRESH_PLAN_2026-10-25.md)、[后续视觉提案](UI_REFINEMENT_PROPOSAL_2026-10-26.md) |

[本次全量文档审计](../runs/20261027-161708-all-docs-and-cleanup-audit/outputs/document-audit.md)、[不变性与校验](../runs/20261027-161708-all-docs-and-cleanup-audit/outputs/verification.md)。根/docs活动入口与28个OpenSpec已核对；按用户要求忽略_spec，本轮不改代码或包，不删除任何文件。

<details>
<summary>历史说明与交付记录（原文留档，当前状态以上方为准）</summary>

# 本地补充文档索引

> 2026-10-25 当前交付：**统计页第二轮 0.9.1/43 已实施并同签名安装真机，待用户验收**。分段控件改为「胶囊底槽 + 单枚滑动滑块」（150ms 平移过渡、胶囊圆角，减少动效时 0ms 且不画投影），统计页、设置页与确认卡同时生效；统计默认进入周视图，周柱图按可用宽度自适应，10/19–10/25 七天柱与日期标签完整呈现、无需横滚；月视图改为日历热力图（周一起始、5 档深浅、图例「少→多」、选中格描边、格内数字按混色底计算对比度）；周期标签缩短为「10月第4周」与「2026年10月」，不再被省略号截断；统计左右滑动可切周期。367项/47类JVM、四构建任务、OpenSpec strict19通过；真机浅色/深色/1.3大字下统计周视图、月热力图、收入退款分页、账户汇总、上一周期与回跳、统计说明均可达，滑块动画经录屏抽帧测得 2–3 帧平移；原 5 笔账单/12 条历史逐字段一致，采集/外观/学习设置与 120Hz 保留，设备安装包 SHA 与 dist 一致。真实支付/外部导入/低API/完整TalkBack 等未测边界保留。证据：`runs/20261025-180345-stats-slider-heatmap/`。

> 2026-10-25 历史交付：**视觉精修 0.9.0/42 已实施并同签名安装真机，待用户验收**。统计页压缩为首屏可见完整趋势图（安全区下缘到绘图区上缘 721dp→361dp，可见绘图区 176dp），明细搜索改为胶囊输入、账单行以商户为标题并显示真实账户、日期组改为「日期+笔数左 / 支出金额右」单行；首页以本月支出渐变主卡为最大数字，月收入/结余与退款（非零时）为次级，今日摘要压成单行；图表统一支出/收入/退款语义色，环图中心显示类型与完整总额。362项/46类JVM、双构建/androidTest、OpenSpec strict19通过；真机浅色/深色/1.3大字与 650 笔合成数据验证，原 5 笔账单/12 条历史逐字段一致，设置与 120Hz 保留。真实支付/外部导入/低API/完整TalkBack 等未测边界保留。证据：`runs/20261025-135702-visual-refresh/`。

> 历史交付：**UX3第三批0.8.2/41用户验收通过**。统计按所选支出/收入/退款突出金额、趋势与占比并提供统计说明；设置分为识别/数据/外观/导出/帮助，规则技术内容收入高级折叠，批量恢复/清空操作先确认；分类账户主动删空后保持空，详情识别信息默认折叠。按用户要求直接真机验证并跳过模拟器。353项/44类JVM单测通过，debug/release及androidTest已构建，包内版本与0.8.1连续签名已核对；真机配置8主体+finally共9项、统计8主体+finally共9项通过。正式包31项界面证据核查完成；48笔QA已精确清理，原4笔账单/11条历史逐字段一致，原配置按类型恢复，采集/外观/权限及120Hz保留。正式包设备SHA与dist一致，测试包和恢复标记已移除，普通无障碍已绑定。证据目录：`runs/20261025-123433-ux-batch3/`。

> 历史交付（2026-10-25）：**UX2第二批0.8.1/40用户验收通过**；**UX1第一批0.8.0/39已获用户明确验收**。本批实现今日摘要与紧凑月概览、完整账本商户/备注搜索及日期/分类/类型组合筛选、完整筛选日汇总和连续日期卡面。347项/43类JVM、debug/release及androidTest构建通过；真机Room9主体+finally共10项通过，605笔合成账单覆盖窗口外搜索、组合筛选、独立汇总、类型/日期与编辑删除撤销。最新正式包的硬件Enter误触识别历史问题已修复并复测通过。日期自选、月概览、深色1.3字号/减少动效回放完成；605笔QA已精确清理，原4笔账单/11条历史逐字段一致，原采集/外观/学习偏好、权限与120Hz保留，正式混淆包已恢复安装且测试包移除。真实支付/外部iCost导入等既有证据缺口保留。证据目录：`runs/20261025-012706-ux-batch2/`。

> 历史交付：UX1 0.8.0/39于2026-10-25获用户明确验收。确认卡渐进展开、原位真实保存结果与共享可行动状态；327项/40类JVM及当轮真机证据保持原范围，不外推真实支付或外部导入。证据：`runs/20261025-002441-ux-batch1/outputs/verification.md`。

> 2026-10-24 专项记录：**0.7.9/38已同签名安装真机，待用户验收**。311项/37类单测及双构建通过；高刷A/B确认MIUI限60，放开后实测120Hz/约8.3ms，测试还原后用户自行设120并要求保留；dock加强模糊。普通无障碍当前已启用且绑定。LSP兼容与可信探测修正版需重启加载，用户暂缓重启，未宣称解决掉权限。安装后原3笔/4条曾一致；收尾为4笔/9条，两笔原账单商户字段变化，原ID均保留；证据见 `runs/20261024-160616-high-refresh-glass-lsp/outputs/verification.md`。既有验收与未测边界保留。

历史记录（2026-10-24）：当时交付 **0.7.8/37，VF1公共组件与渲染开销优化已实施，待用户验收**。296项/35类JVM及双构建通过；API35完成605笔导航/操作回归、历史筛选与小屏深色字号1.3/减少动效可达检查，原数据与设置已恢复。605笔滚动对照未显示稳定整体帧率改善，用户真机掉帧仍待复现验证；本轮无真机连接、未安装或操作真机。0.7.7支付专项仍待真实支付验证，0.7.6及此前验收保留。证据`runs/20261024-151521-vf1-performance/`；其余VF2–VF5及既有缺口继续追踪。

本索引及所列文档为本机补充资料。Git 中的说明文档按当前约定仅保留各目录 `README.md`，项目根 README 为公共入口；下列本地文件不作为公共仓库的必备链接或运行依赖。第三批使用方式与基本构建命令已写入根 README。

本轮单测命令为 `./gradlew --offline --no-build-cache -Pkotlin.incremental=false :app:testDebugUnitTest`，禁用缓存与增量编译以避免旧测试字节码；依赖缓存完整时使用离线模式。当前安装入口为 `dist/AutoBook-0.9.1-release.apk`，最终清理/配置恢复与正式包恢复已完成，详见本轮本地验证报告。

| 用途 | 入口 |
|---|---|
| UI公共组件使用与本轮边界 | [UI_COMPONENTS](UI_COMPONENTS.md) |
| 产品能力与限制 | [项目README](../README.md) |
| 开发约定与交接 | [AGENTS](../AGENTS.md) |
| 快速命令 | [QUICKREF](../QUICKREF.md) |
| 构建、签名、安装及迁移 | [DEPLOYMENT](../DEPLOYMENT.md) |
| 当前验证范围 | [TEST_REPORT](../TEST_REPORT.md) |
| 版本实现与历史证据 | [PROGRESS](PROGRESS.md)、[CHANGELOG](../CHANGELOG.md) |
| 后续任务唯一总表 | [plan1.0](PLAN_1.0.md) |
| 本地规则、餐段与学习 | [LOCAL_RULES](LOCAL_RULES.md) |
| iCost公开导入链路 | [ICOST_IMPORT](ICOST_IMPORT.md) |
| Root模式 | [ROOT_PLAN](ROOT_PLAN.md) |
| LSP快速配置 | [LSP_QUICKSTART](LSP_QUICKSTART.md) |
| LSP作用域与排查 | [LSP_SCOPE_CONFIG](LSP_SCOPE_CONFIG.md) |
| LSP实现和能力边界 | [LSP_MODULE](LSP_MODULE.md) |
| 历史需求与设计 | [plan0.5](PLAN_0.5.md)、[早期UI草案](IOS27_UI_PLAN.md)，只作留档 |

具体实施方案和任务保存在`openspec/changes/`；已发生运行和交付证据保存在`runs/`及`deliverables/`，不随活动文档更新改写。

重复HANDOVER及ENHANCEMENT_SUMMARY已合并清理，旧文本保存在2026-10-23文档整理run的intermediate/before，清理说明见该run的outputs。测试数字和版本元数据优先引用TEST_REPORT/PROGRESS，不在每份专题重复维护。

2026-10-24：0.7.4/33批次3已实施，已完成所列验证，用户验收通过；0.7.3/32当前交付（批次2a含F19修复）已于2026-10-23获用户明确验收。既有验收及历史证据缺口保留；该句所述“批次5及后续未实施”为当时状态，当前批次与验收以文首为准。


2026-10-24 用户明确批次五0.7.6/35验收成功；此前已按要求adb覆盖安装release到真机，仅安装未操作验证。两张支付截图归批次六识别专项，非批次五图表变更。批次六本次实施及证据另记，不扩大批次五既有测试结论。


</details>

</details>
