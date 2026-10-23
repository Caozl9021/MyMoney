# 接通无障碍保活：清理未接线死代码

> 历史变更记录：描述该轮目标与当时状态，不作为当前操作指南；最新实现/验收边界见 `docs/PROGRESS.md`，后续任务见 `docs/PLAN_1.0.md`。保留原始需求与验证过程，不代表用户已验收。

状态：实施与主代理核查完成，待用户验收；**真机证据缺失**（当轮 `adb devices` 为空）。

0.6.2 的核查发现 `AccessibilityGuardian` 与 `AccessibilityEnhancer` 是零调用点的死代码：
Manifest 没有注册 `<service>`、全工程没有 `start()` 调用，而上层文档却把"30s 守护""厂商优化"
当作已交付能力。本变更把这条链接通，而不是删除——用户诉求是"确定保活无障碍模块的正常"。

同时清掉另外两处"看起来在工作、其实在说谎"的实现：

- `GuardianPolicy`（新增）替掉旧守护判定里两个真缺陷：`lastCheckEnabled` 初值 `false` 导致
  "服务启动时无障碍已关"永不报警；只查 `ENABLED_ACCESSIBILITY_SERVICES` 分不出
  "设置里打着勾但服务已被厂商解绑"的假开启。
- `XposedModuleDetector` 三路检测里，SharedPreferences 标记没有写入方、`getModuleVersion()`
  查的包 `com.example.autobook.xposed` 不存在、`getEnhancedFeatures()` 有 3 条不存在的宣称。
  改为三档 `HookState`，不再给假绿勾。

版本 0.6.3（22），产物 `dist/AutoBook-0.6.3-{debug|release}.apk`。

范围外（本变更刻意不做）：给 `PowerManagerService` 加 Hook 去改电源策略（替用户改系统策略风险高，
且 LSPosed 里真正生效的入口在各 ROM 上不一致）；自动重启被杀的无障碍服务（`onDestroy` 不重启，
需要 `AlarmManager` 或 JobScheduler 轮询，收益不确定而耗电确定）；提高进程优先级。

证据：`runs/20261022-155747-guardian-hardening/`（167 单测 + 双构建 + APK 元数据；`outputs/` 为空）。
