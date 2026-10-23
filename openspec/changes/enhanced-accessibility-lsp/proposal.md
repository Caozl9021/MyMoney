# 无障碍分级去抖与内置 LSP 增强

> 历史变更记录：描述该轮目标与当时状态，不作为当前操作指南；最新实现/验收边界见 `docs/PROGRESS.md`，后续任务见 `docs/PLAN_1.0.md`。保留原始需求与验证过程，不代表用户已验收。

状态：实施与主代理核查完成（文档核查轮已按代码订正口径），待用户验收；**真机证据缺失**。

无障碍服务按事件类型分级去抖（窗口切换 120ms、内容变化 600ms），并把 LSP 增强功能内置进主 App（不再有独立模块 APK）。版本 0.6.2（21），debug 与 release 输出 `dist/AutoBook-0.6.2-{debug|release}.apk`。

本轮（2026-10-22 文档核查）补充的核查结论，与早期文档口径不同的地方：

- 真实 Hook 只有 4 处：`AccessibilityManagerService.unbindService`、`AccessibilityManagerService.removeServiceLocked`、`ActivityManagerService.killApplication` 两个重载。文档曾宣称的 `PowerManagerService` 强制豁免电池优化、`Context.getSharedPreferences` 写激活标记、`BillAccessibilityService.onUnbind/onDestroy` 监控 —— **均不存在**。
- `AccessibilityGuardian`（30s 检查）与 `AccessibilityEnhancer` 在 0.6.2 时**未接线**：`AndroidManifest.xml` 无该 `<service>`，全工程无 `start()` 调用。**已在 0.6.3 接通**，见 `openspec/changes/guardian-keepalive-wiring/`。
- `settings.gradle.kts` 没有 `:xposed-module`，`build-xposed-module.sh` 与两份 LSP 文档里的 `:xposed-module:assembleRelease` 必然失败。
- 单测：149 通过（16 类，2026-10-22 14:51 复跑）；产物元数据已核对。**功能层面零真机验证**。

逐项证据与后续动作见 `docs/PROGRESS.md`。
