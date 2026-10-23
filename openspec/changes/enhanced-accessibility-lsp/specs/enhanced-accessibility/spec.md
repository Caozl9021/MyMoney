# Enhanced accessibility & built-in LSP

## ADDED Requirements

### Requirement: 按事件类型分级去抖
无障碍服务 MUST 对 `TYPE_WINDOW_STATE_CHANGED` 使用较短去抖（120ms），对
`TYPE_WINDOW_CONTENT_CHANGED` 使用较长去抖（600ms）；去抖 MUST 不影响截断标记与投递诊断的传递。
#### Scenario: 付款成功页
- WHEN 页面发生窗口切换（`TYPE_WINDOW_STATE_CHANGED`）
- THEN 服务在 120ms 去抖窗口后处理该次事件，不再等满 600ms。

#### Scenario: 列表滚动
- WHEN 同一屏持续产生 `TYPE_WINDOW_CONTENT_CHANGED`
- THEN 服务按 600ms 合并，不重复 dump。

### Requirement: LSP 增强内置在主 App
LSP 模块 MUST 打包在主 App 内（`assets/xposed_init` + `xposed/` 源码），MUST NOT 依赖独立的
`:xposed-module` Gradle 模块或第二个 APK。
#### Scenario: 安装
- WHEN 用户安装 `dist/AutoBook-0.6.2-release.apk`
- THEN 包内存在 `assets/xposed_init`，在 LSPosed 中启用 AutoBook 即可。

### Requirement: 作用域只需系统框架
模块 MUST 只在 `lpparam.packageName == "android"` 时注册 Hook；文档 MUST 说明只勾 `android`。
#### Scenario: 只勾 android
- WHEN 用户只在 LSPosed 中勾选 `系统框架 (android)`
- THEN Hook 正常注册；勾选 `com.example.autobook` 不产生任何额外效果。

### Requirement: 文档与实际 Hook 一致
文档 MUST 只列代码中真实存在的 Hook 点。当前为 4 处：`AccessibilityManagerService.unbindService`、
`AccessibilityManagerService.removeServiceLocked`、`ActivityManagerService.killApplication`
的两个重载。MUST NOT 宣称 `PowerManagerService`、`Context.getSharedPreferences`、
`BillAccessibilityService.onUnbind/onDestroy` 为已实现。
#### Scenario: 电池优化
- WHEN 用户查看文档中的能力列表
- THEN 明确告知没有 `PowerManagerService` Hook，仍需手动关闭电池优化。

### Requirement: 未接线的能力必须显式标注
`AccessibilityGuardian` 与 `AccessibilityEnhancer` MUST 在文档中标注为"未接线"，直至其被注册为
`<service>` 并在运行时被调用。（**2026-10-22 0.6.3 已满足该前提**：两者已注册并接入运行时，
本条要求的"标注未接线"随之失效，改由 `guardian-keepalive-wiring` 变更接管。）
#### Scenario: 守护服务
- WHEN 用户阅读 LSP 文档
- THEN 不会认为存在 30s 自动恢复能力。

### Requirement: 验证与交付
单测 MUST 通过，debug/release MUST 构建成功，APK 元数据 MUST 为 0.6.2/21 并输出 `dist`。
真机 Hook 验证 MUST 单独标注，未验证时 MUST 保持"待验收"。
#### Scenario: 交付
- WHEN 本版交付
- THEN 说明 149 单测通过、产物已核，同时说明 LSP 与保活无真机证据。
