# 工作项

- [x] T1 核查 0.6.2 的死代码结论：`AccessibilityGuardian` 无 `<service>` 注册、无 `start()` 调用；
      `AccessibilityEnhancer` 全工程无外部调用点（唯一引用者是 `AccessibilityGuardian` 的 `getStatus`）。
- [x] T2 新增 `capture/GuardianPolicy.kt`（纯逻辑、可 JVM 单测），三档 `Health{OK,DISABLED,UNBOUND}`，
      `DISABLED` 阈值 2 次、`UNBOUND` 阈值 4 次、重复提醒间隔 20 次、计数封顶 10000。
- [x] T3 重写 `capture/AccessibilityGuardian.kt` 为真前台服务（`specialUse`、通知 id 1002、
      异常通知 id 9001、`CHECK_INTERVAL_MS=30_000L`），`onCreate` 里 `startForeground` 失败即
      `stopSelf()`，成功才置 `running` 并立刻巡检一次。
- [x] T4 重写 `capture/AccessibilityEnhancer.kt`：删掉 `autoStartEnabled=null`、
      `notificationEnabled=true // TODO`、`attemptAutoRepair()`、`logStatus()`、`guideUserToOptimize()`；
      只保留实测项（通知权限、电池优化豁免、守护是否在跑、厂商识别与跳转、优化清单）。
- [x] T5 `AndroidManifest.xml` 注册 `.capture.AccessibilityGuardian`（`exported=false`、
      `foregroundServiceType=specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`），
      新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- [x] T6 接线：`CaptureModeManager.reconcile()` 按 `next.accessibilityActive` 起停；
      `BillAccessibilityService.onServiceConnected()` 幂等兜底；`App.ensureGuardianChannel()`
      委托 `AccessibilityGuardian.ensureChannel()`。
- [x] T7 重写 `capture/XposedModuleDetector.kt` 为三档 `HookState`，删掉永假的 SharedPreferences 路、
      查不存在包 `com.example.autobook.xposed` 的 `getModuleVersion()`、含 3 条谎言的 `getEnhancedFeatures()`。
- [x] T8 权限页新增「无障碍保活」组（守护服务状态 / 电池优化豁免 / 厂商自启动引导），
      无障碍行改三分支，LSP 组改三档文案并写清局限。
- [x] T9 新增 `GuardianPolicyTest`（12 例）与 `AccessibilityEnhancerTest`（6 例）；
      全量 `--rerun-tasks` 重跑 **167 / 0 / 0 / 0，18 类**。
- [x] T10 修三个脚本：`build-xposed-module.sh`（不再调不存在的 `:xposed-module`）、
      `build-all.sh`（版本号从 gradle 读 + `aapt2` 核对 + `~/.gradle` 只读时自动 copydown）、
      `check-lsp-status.sh`（`adb pull` 取代 `adb shell unzip`）。
- [x] T11 版本升 0.6.3 / versionCode 22；debug + release 构建；`aapt2 dump badging` 两包均为 22/0.6.3；
      release 证书与前版一致；产物进 `dist/`。
- [x] T12 证据 run `runs/20261022-155747-guardian-hardening/`（manifest + verification + 单测日志 +
      18 个 XML；`outputs/` 故意为空，写明原因）。
- [x] T13 文档回写：AGENTS / README / CHANGELOG / HANDOVER / QUICKREF / TEST_REPORT / DEPLOYMENT /
      agent.md / docs 下 LSP 三件套 + ENHANCEMENT_SUMMARY + PROGRESS + PLAN_0.5。
- [ ] T14 真机验证（**当前最大缺口**）：守护服务能否常驻、30s 巡检是否在跑、
      `DISABLED` 约 60s / `UNBOUND` 约 2 分钟是否会各弹一条通知、权限页三行读数是否与实际一致、
      电池优化与厂商自启动跳转是否落到正确页面。
- [ ] T15 观察 `XposedModuleDetector` 在真机上的实际档位。若 `MODULE_CONFIRMED` 长期不出现，
      再决定是否让模块 Hook 到 App 进程自身写标记（见 `design.md` 的"刻意不做"一节）。
- [ ] T16 补**功能验收** run（如 `runs/<timestamp>-guardian-acceptance/`，需带 `outputs/` 截图与 UI XML）。

验收：`AccessibilityGuardian` 在 Manifest 中注册且被 `CaptureModeManager.reconcile()` 与
`BillAccessibilityService.onServiceConnected()` 两处调用；`GuardianPolicy` 有覆盖三档判定与两种阈值差异的
单测；`XposedModuleDetector` 不再有永不成立的检测路径；文档不再出现"30s 自动恢复""强制豁免电池优化"
这类不存在的宣称；APK 为 0.6.3/22 且 release 证书与前版一致。

未完成项 T14-T16 全部依赖真实设备（当轮 `adb devices` 为空），不阻塞本版交付，
但 MUST 与"待验收"状态一起说明。OpenSpec CLI 不可用，手工校验结构。

主代理核查证据：`runs/20261022-155747-guardian-hardening/verification.md`、
`runs/20261022-155747-guardian-hardening/manifest.json`、`docs/PROGRESS.md`（第八节）。
