# Capture mode

## ADDED Requirements

### Requirement: 首次选择与持久记忆
未选择模式时 MUST 显示Root/无障碍选择，用户选择后 MUST 持久保存，重启/升级/Activity重建 MUST 不再次强制选择。权限不可用 MUST 不清除或替换选择。
#### Scenario: 首次选Root并重开
- WHEN 用户选择Root后关闭并重新打开App
- THEN 仍为Root，不要求再次选择，不要求开启无障碍。

### Requirement: 可随时在设置切换
设置 MUST 提供当前模式与切换入口；识别暂停 MUST 保留模式。切换 MUST 起停唯一对应采集路径。
#### Scenario: Root切为无障碍
- WHEN 用户在设置选择无障碍
- THEN Root服务停止，无障碍路径按系统授权运行，选择持久保存。

### Requirement: 模式对应提示与互斥运行
首页/设置状态/权限页 MUST 按当前模式显示所需授权；Root模式 MUST 不把无障碍未开启视作故障；无障碍模式 MUST 不自动请求Root。
#### Scenario: Root已选而无障碍关闭
- WHEN 进入首页或权限页
- THEN 只指引Root授权/悬浮窗/通知，不引导必须启用无障碍。

### Requirement: 升级与失败行为
旧Root选择 MUST 迁移保留；Root拒绝/失效 MUST 可返回或切换无障碍，不能反复弹授权或陷入不能使用App。备份恢复 MUST 不静默覆盖现有明确模式。
#### Scenario: Root未授权
- WHEN Root不可用
- THEN 显示模式已选及授权未就绪，允许切换，重开仍记住原选择。

### Requirement: 验证与交付
单测、debug/release构建 MUST 通过，APK元数据 MUST 为0.6.1/20并输出dist，待用户验收。
