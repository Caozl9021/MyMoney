# 工作项

- [x] T1 主代理核查无障碍服务的去抖实现，确认按事件类型分级。
- [x] T2 实现并内置 LSP 模块（`assets/xposed_init` + `xposed/` 源码），只需装主 App。
- [x] T3 App 端接入 `XposedModuleDetector` 并在设置页权限分组显示状态。
- [x] T4 单测与 debug/release 构建；核 APK 版本 0.6.2/21、证书与前版一致、`assets/xposed_init` 存在。
- [x] T5 更新 CHANGELOG/README/AGENTS/HANDOVER 与 LSP 文档。
- [x] T6 文档核查轮（2026-10-22）：订正 Hook 表为实际 4 处、标出 `PowerManagerService`/
      `getSharedPreferences`/`onUnbind·onDestroy` 三项不存在、标出 `AccessibilityGuardian` 与
      `AccessibilityEnhancer` 未接线、订正 `:xposed-module` 构建命令、`xposedminversion` 改 93。
- [x] T7 ~~移除或接线~~ **已接线**（2026-10-22 0.6.3）：`AccessibilityGuardian` 注册为 `specialUse`
      前台服务并由 `CaptureModeManager.reconcile()` 起停、`BillAccessibilityService.onServiceConnected()`
      幂等兜底；`AccessibilityEnhancer` 重写为只保留实测项。详见
      `openspec/changes/guardian-keepalive-wiring/`。
- [x] T8 ~~修 `XposedModuleDetector` 的激活判定~~ **已改三档**（0.6.3）：`HookState{NOT_DETECTED,
      FRAMEWORK_PRESENT, MODULE_CONFIRMED}`，删掉永假的 SharedPreferences 路、查不存在包的
      `getModuleVersion()`、含 3 条谎言的 `getEnhancedFeatures()`。**残留**：`MODULE_CONFIRMED`
      依赖模块侧 `SystemProperties.set`，普通应用权限下大概率永远不出现——这是权限现实，
      不是 bug，UI 已按"未确认"措辞处理。
- [ ] T9 真机验证：手动撤无障碍是否被拦、`am force-stop` 后是否被拦、120ms 手感、图标是否刷新。
- [x] T10a 补元数据证据目录 `runs/20261022-145102-doc-audit/`（`manifest.json` + `verification.md`
      + `logs/unit-tests.log` + `test-results/` 16 个 XML，与 0.6.0/0.6.1 的格式对齐）。
      **注意它的 `outputs/` 是空的**——只证明单测与产物元数据，不构成功能验收。
- [ ] T10b 补**功能验收** run（如 `runs/<timestamp>-enhanced-lsp/`，需带 `outputs/` 截图与 UI XML）。

验收：分级去抖代码存在且有单测覆盖（`CaptureLimitsTest` 覆盖截断/诊断链路）；APK 只有一个且内含
`assets/xposed_init`；LSPosed 作用域只需勾 `android`；文档不再宣称不存在的 Hook 与构建目标。

未完成项 T9 / T10b 依赖真实设备（2026-10-22 当轮 `adb devices` 为空），不阻塞本版交付，
但必须与"待验收"状态一起说明。T7/T8 已在 0.6.3 处理完，本变更里保留原文以便对照。
OpenSpec CLI 不可用，手工校验结构。

主代理核查证据：`runs/20261022-145102-doc-audit/verification.md`（本轮新增，含 149 单测强制重跑的
完整记录、APK 元数据与 sha256、4 处 Hook 与 3 项不存在 Hook 的核对）、`docs/PROGRESS.md`（进度总账）、
`TEST_REPORT.md`（APK 与单测实测数据）。
