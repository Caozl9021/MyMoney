# UI公共组件与当前约束

当前1.9.0/143；以下为源码约束，实测范围单列，不能理解为完整矩阵通过。

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
| SectionColumn/ListRow | 组间距/触控遵循design tokens；内容长/大字可增高，不机械套叠多层padding |

143仅在Waydroid400×890/density160的ConfirmActivity同一演示卡实看间距。其他宿主复用实现，但本轮没有逐个实跑；大字/窄屏/深色完整矩阵、Release UI和完整TalkBack未补齐。用户继续操作的演示卡不自动保存、忽略或清理。详见[143证据](../runs/20261105-151939-duplicate-label-spacing/outputs/verification.md)与[验收清单](ACCEPTANCE.md)。
