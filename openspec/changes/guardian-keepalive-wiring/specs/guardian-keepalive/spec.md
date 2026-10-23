# Guardian keep-alive wiring

## ADDED Requirements

### Requirement: 守护服务必须真正注册并被调用
`AccessibilityGuardian` MUST 在 `AndroidManifest.xml` 注册为 `foregroundServiceType="specialUse"`
的服务，且 MUST 由 `CaptureModeManager.reconcile()` 按 `CaptureModeState.accessibilityActive` 起停。
MUST NOT 存在"类在仓库里但无处调用"的状态。
#### Scenario: 切到无障碍模式
- WHEN 用户选择无障碍模式并打开识别总开关
- THEN `reconcile()` 调用 `AccessibilityGuardian.ensureRunning(context)`，服务进入前台并开始 30s 巡检。

#### Scenario: 切到 root 模式或暂停识别
- WHEN `accessibilityActive` 变为 false
- THEN `reconcile()` 调用 `AccessibilityGuardian.stop(context)`，服务退出前台。

### Requirement: 守护判定必须区分两种异常
判定 MUST 至少区分"系统设置里已关闭"（`DISABLED`）与"设置里勾着但服务未绑定"（`UNBOUND`），
且 MUST NOT 依赖"上一次观测值"的边沿检测（否则启动时已关闭将永不报警）。
#### Scenario: 启动时无障碍已关
- WHEN 守护服务启动时无障碍开关就是关闭的
- THEN 连续 2 次巡检后发出提醒（约 60s），不需要先观测到一次"开启"。

#### Scenario: 假开启
- WHEN 系统设置里 AutoBook 的无障碍仍处于勾选状态，但 `BillAccessibilityService.instance` 为 null
- THEN 判定为 `UNBOUND`，连续 4 次巡检后提醒（约 2 分钟），避开冷启动假阳性。

#### Scenario: 一切正常
- WHEN 设置里勾着且服务已绑定
- THEN 判定为 `OK`，异常计数清零，永不提醒。

### Requirement: 提醒必须有节制且可关闭
提醒 MUST 在连续异常达到阈值后才发出，MUST 按固定间隔重复而不是每次巡检都发；
用户关闭通知权限时 MUST 只记日志、不崩溃、不静默失败重试。
#### Scenario: 长期异常
- WHEN 无障碍持续异常超过 20 个提醒周期
- THEN 不会每个 30s 巡检都弹通知。

#### Scenario: 没有通知权限
- WHEN `areNotificationsEnabled()` 为 false
- THEN 只写日志，不发通知，服务继续巡检。

### Requirement: 保活能力不得过度承诺
文档与 UI MUST 明确说明守护服务只能"发现掉线并提醒"，MUST NOT 宣称自动重启服务、
低内存豁免、进程优先级提升，或通过 Hook `PowerManagerService` 强制豁免电池优化。
#### Scenario: 用户读权限页
- WHEN 用户打开设置→权限→「无障碍保活」
- THEN 看到的是实测的守护状态、电池优化豁免状态、厂商自启动入口，
       以及"系统不提供检查自启动是否已开"的说明。

### Requirement: 模块检测不得给出无依据的肯定
`XposedModuleDetector` MUST 只在有真实证据时报告模块已确认生效，否则 MUST 报告为
"检测到框架但未确认"或"未检测到"，MUST NOT 使用没有写入方的标记作为判据。
#### Scenario: 只装了框架没启用模块
- WHEN LSPosed 已装但未启用 AutoBook 模块
- THEN 状态为 `FRAMEWORK_PRESENT`，UI 不显示"已激活"。

### Requirement: 交付必须有可复核证据
单测 MUST 通过、debug/release MUST 构建成功、APK 元数据 MUST 与 `dist` 文件名一致；
真机未验证时 MUST 保持"待验收"，且证据 run 的 `outputs/` 为空时 MUST 写明原因。
#### Scenario: 无设备交付
- WHEN 交付时没有连接设备
- THEN 说明"只有单测与产物元数据证据"，并在 run 目录里写明 `outputs/` 为什么为空。
