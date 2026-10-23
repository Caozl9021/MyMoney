# AutoBook 验证报告

更新：2026-10-23。当前候选 **0.6.8 / 27，待用户验收**。本页汇总已有证据；本次文档整理未重新运行构建或设备测试。

## 当前版本证据

来源 run：[20261023-092336-brand-jpg-wechat-release](runs/20261023-092336-brand-jpg-wechat-release/outputs/verification.md)。

| 检查 | 结果与证据 |
|---|---|
| JVM 单测 | 强制重跑 221 项、24 类；0 失败/错误/跳过，见 [计数 JSON](runs/20261023-092336-brand-jpg-wechat-release/outputs/unit-test-summary.json) 与 [测试结果目录](runs/20261023-092336-brand-jpg-wechat-release/test-results/) |
| 构建 | 单测后 debug 和签名 release 构建成功，见 [构建日志](runs/20261023-092336-brand-jpg-wechat-release/logs/build.log) |
| APK | 两包 0.6.8/27，签名通过，release 证书与 0.6.7 一致；每包 26 项图标的可见 RGBA 与 alpha 对应一致，见 [包核查](runs/20261023-092336-brand-jpg-wechat-release/outputs/apk-verification.json) |
| 品牌资源 | 使用 Auto.jpg；彩色保留四彩边框/白色内部，独立单色保留负空间；自适应 54dp/108dp、legacy 55% 留白，见 [资源审计](runs/20261023-092336-brand-jpg-wechat-release/outputs/resource-audit.json) 与 [负空间核查](runs/20261023-092336-brand-jpg-wechat-release/outputs/negative-space-verification.json) |
| API35 当前目视 | 覆盖安装后确认卡、QS 磁贴、已放置 widget 显示最新图标；取消编辑未保存账单。见 [确认卡](runs/20261023-092336-brand-jpg-wechat-release/outputs/confirm-new-brand.png)、[磁贴](runs/20261023-092336-brand-jpg-wechat-release/outputs/qs-new-brand.png)、[widget](runs/20261023-092336-brand-jpg-wechat-release/outputs/widget-new-brand.png) |
| 崩溃缓冲 | 本轮采集未见应用崩溃记录；这是有限测试窗口的观察，见 [日志](runs/20261023-092336-brand-jpg-wechat-release/logs/emulator-crash-buffer.log) |

单测覆盖分类/金额/状态、退款、编辑、导出/备份、模式选择、守护策略、本地规则及学习、系统入口、微信极简成功页与无障碍调度等逻辑。测试通过不代表第三方 App 所有页面或实际服务运行均已验证。

## 微信修复的证据强度

用户提供截图的可见文字为“支付成功、收款方、¥0.01、完成”，没有旧规则要求的支付方式。代码新增严格极简成功页分支：独立成功标题、完成按钮、唯一无符号货币金额；拒绝失败、取消、待支付、转账/收款/退款及多金额歧义，缺失字段不捏造。

调度修复保留窗口事件最早 120ms 期限，纯内容事件最大等待 1 秒；根节点不可用仅重试一次。单测核对这些策略，不能证明真实付款页面必定在此时间内出现确认卡。

**截图人工转录不是无障碍节点 dump**。本轮没有真实微信付款事件、节点快照与悬浮投递日志，不能宣称漏报已全部解决。复测时停留成功页后在“设置 → 诊断”保存样本，避免进入详情页覆盖最近样本。

## 旧版 UI 预览证据

[U 系列预览记录](runs/20261022-231606-u-series/outputs/verification.md) 对应 **0.6.6/25 调试预览**，当时未更新 release/dist。API35 已检查手动新增/编辑取消、删除撤销、草稿重建保留、磁贴启停、widget 添加点击、浅深主题、组合字号 2.6、宽屏及确认卡抓手分支。这些可说明已有实现的预览结果，不能当作 0.6.8 的完整设备回归。

0.6.4 用户曾确认当时桌面新图已生效；该历史确认不覆盖 0.6.8 的 Auto.jpg、所有主题裁切或启动器刷新。跨版本事实见 [进度记录](docs/PROGRESS.md)。

## 仍待验证与验收

- 真实微信付款瞬时成功页、实际节点采集、识别与悬浮确认完整链路；Root/OCR 在真实支付页面上的运行。
- 通知实际显示、不同启动器的桌面/主题图标与快捷方式观感；当前通知仅有代码/资源核查。
- LSPosed 注册及拦截真实调用、不同系统版本的 Hook 兼容；守护异常通知、厂商省电/自启动引导和长时间后台行为。相关 [模块文档](docs/LSP_MODULE.md) 中的代码能力不能当真机结论。
- API26–32 玻璃兜底、安全锁屏磁贴解锁、完整 TalkBack、长时间息屏/Doze 跨日 widget 刷新及最终动效观感。widget 使用非精确午夜闹钟，Doze 下可延迟。

本轮功能变更记录中 OpenSpec CLI 不可用，已按 proposal/design/spec/tasks 结构手工核查；不等同运行过 CLI 校验。当前源码、构建、单测与有限模拟器证据均不替代用户验收。[plan1.0](docs/PLAN_1.0.md) 是规划待审阅，其新增功能未实施。

复现构建命令见 [部署指南](DEPLOYMENT.md)；稳定交付与来源见 [0.6.8 交付索引](deliverables/0.6.8/README.md)。
