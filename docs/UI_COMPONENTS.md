# UI 公共组件与当前约束

下表的尺寸与间距是源码中的约束，不是逐页设备测量结果。

| 组件/规则 | 当前职责与约束 |
| --- | --- |
| SubPageBar / SubPageScaffold | 子页无左上返回入口，标题按内容区域中线居中；长标题和右动作避让；系统返回负责层级 |
| SectionColumn / SectionLazyColumn | 分组 16dp、标题到卡 8dp、卡到脚注 4dp、同级卡 10dp、主操作 16dp；由单一容器负责外距，懒列表保留 key/状态 |
| AbCardDensity | 内容内距 12dp、列表行上下 8dp、账单行最少 64dp；字号/多行按内容增高 |
| ListRow / CapsuleChip / AbSwitch | 可点目标最少 48dp；列表卡外层不重复加内容卡 12dp；保留类型/选择/禁用语义 |
| PrimaryButton / SecondaryButton / DestructiveButton | 最少 50dp；忙碌/禁用不触发操作，成功由真实结果决定 |
| BackSurface / BackHandoff | 默认关闭预测返回；显式选择保留；就绪准入、按需独立像素、固定回调顺序；残影问题未解决，不能列为无缺陷 |
| 备注模板 / DiscardChangesDialog | 模板无预设；放弃弹窗为最大 400dp、24dp 留白/内距、居中标题/次级说明、横向等宽中性双操作，放弃用语义红；保留原关闭回调 |
| BillActionsHost / BillUndoStack | 原 ID 观察和编辑，真实成功后退出；逐笔 5 秒撤销；不能把部分加载日小计当全天 |
| Segmented / 搜索 / 图表 | 现有滑块、完整查询与周/月图表保留；图表、日期网格与气泡按专用结构，不机械套同一卡间距 |
| FloatingHeader / 玻璃标题操作 | 四 Tab 分体玻璃胶囊与轻柔外阴影；点击目标保留 48dp；正常采集用账本按钮内绿色虚线圈，点击进自动记账 |
| AccountDetailsCards / AccountPeriodHistory | 账户余额首卡、更多操作与账期折叠卡；本金转账隐藏仅影响列表，不改变余额 |
| AppDialog | 资产更多和操作确认使用应用 token 容器；其他默认 Material3 弹层尚未统一迁移 |

源码入口为 `ui/design` 下 `Chrome.kt`、`FloatingHeader.kt`、`AppDialog.kt`、`Components.kt`、`Tokens.kt`、`SectionColumn.kt`、`SectionLazyColumn.kt` 及 `ui/BackNavigation.kt`；规则最终以源码为准。精确 1dp 还原、低 API、完整 TalkBack、深色大字矩阵仍属未覆盖范围。
