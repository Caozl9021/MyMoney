# AutoBook 0.6.2（21）文档核查（不改代码）

状态：代码与产物的**元数据**已核查，文档已按代码订正，待用户验收；**功能层面无证据**（本 run 没有连接真机/模拟器，未做任何功能验证）。

## 实现

本 run 不改任何 `.kt` / `.xml` / Gradle 源码，只做三件事：逐条核对 0.6.2 的文档宣称与代码是否一致、重跑单元测试、复核 dist 产物元数据。

0.6.2 的真实改动只有三项：

1. **无障碍事件分级去抖**：`app/src/main/java/com/example/autobook/capture/BillAccessibilityService.kt:29-31` 定义 `DEBOUNCE_MS = 600L`、`WINDOW_DEBOUNCE_MS = 120L`；`:95-96` 按 `TYPE_WINDOW_STATE_CHANGED -> WINDOW_DEBOUNCE_MS`、`TYPE_WINDOW_CONTENT_CHANGED -> DEBOUNCE_MS` 选档。采集链路（`TextCollector` → `BillKit.offer`）与截断/诊断传递未改。
2. **LSP 增强内置进主 App**：入口 `app/src/main/assets/xposed_init`（48 字节，内容 `com.example.autobook.xposed.AutoBookXposedModule`），实现 `app/src/main/java/com/example/autobook/xposed/AutoBookXposedModule.kt`（208 行）。`handleLoadPackage` 只在 `lpparam.packageName == "android"` 时调用 `hookSystemFramework`。
3. **新图标**：`app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` 与 `ic_launcher_round.png` 等。

## 核查（文档宣称 vs 代码）

### 属实的部分

- 分级去抖：代码如上，`WINDOW_DEBOUNCE_MS` 确有 120ms。
- `xposed_init` 确实打进 APK，debug/release 两个包都有。
- release dex 里 `AutoBookXposedModule` 字符串命中 6 次，R8 后入口类未丢。
- 4 处真实 Hook（`XposedHelpers.findAndHookMethod` 共 4 次）：
  - `AccessibilityManagerService.unbindService(AccessibilityService)`
  - `AccessibilityManagerService.removeServiceLocked(AccessibilityServiceConnection, int)`
  - `ActivityManagerService.killApplication(String, int, String)`
  - `ActivityManagerService.killApplication(String, int, int, String)`
  
  命中 `com.example.autobook` 时 `param.result = null`。
- `AndroidManifest.xml:49-50` 的 `xposedminversion` 是 **93**。

### 不属实、已订正的部分

1. **Hook `PowerManagerService` 强制豁免电池优化 —— 不存在。** 全工程 grep `PowerManager` 只命中 `capture/AccessibilityEnhancer.kt:105` 与 `root/RootWatcher.kt:128`，都是普通 API 调用（`isIgnoringBatteryOptimizations` 之类），没有任何 `findAndHookMethod`。
2. **Hook `Context.getSharedPreferences` 写激活标记 —— 不存在。** 模块只处理 `android` 进程，不碰主 App 的 SharedPreferences。
3. **Hook `BillAccessibilityService.onUnbind/onDestroy` 做自动恢复 —— 不存在。** `BillAccessibilityService.onDestroy()`（`:147`）只做清理，不重启服务。
4. **`AccessibilityGuardian`（30s 检查）守护服务 —— 类在，但完全没接线。** `AndroidManifest.xml` 没有该 `<service>`；全工程 grep 不到任何 `AccessibilityGuardian.start(...)` 调用点（唯一引用是 `AccessibilityEnhancer.getStatus` 里的 `AccessibilityGuardian.isAccessibilityEnabled(context)`）。`App.kt` 只建了 `autobook_guardian` 通知渠道，没有启动服务。
5. **`AccessibilityEnhancer` 厂商优化 —— 类在，无任何外部调用点。** `grep "AccessibilityEnhancer\."` 只命中它自己。
6. **`:xposed-module` 构建目标 —— 不存在。** `settings.gradle.kts` 只有 `include(":app")` 与 `include(":billkit")`。`build-xposed-module.sh:35` 与两份 LSP 文档里的 `./gradlew :xposed-module:assembleRelease` 必然失败；文档里的 `xposed-module/build/outputs/apk/release/xposed-module-release.apk` 永远不会有。
7. **`XposedModuleDetector.detect()` 三路检测只有一路可信。** SharedPreferences 标记 `xposed_module_active` 没有写入方；`SystemProperties.set("persist.autobook.xposed.active","true")`（`AutoBookXposedModule.kt:66-76`）在普通应用权限下几乎必然抛异常被 catch；`getModuleVersion()` 去查包 `com.example.autobook.xposed`，该包不存在。只剩堆栈类名一路可能为真。
8. **`xposedminversion` 旧文档写 82 —— 实际 93。**
9. **测试数**：README 写"111 个单测"，实际 **149 个 / 16 个类**。
10. **签名配置路径**：HANDOVER 曾写 `app/keystore.properties` + `app/autobook-keystore.jks`，实际是仓库外的 `~/.autobook-keys/keystore.properties` + `~/.autobook-keys/autobook.jks`。
11. **工具版本**：HANDOVER 曾写 Kotlin 1.9.0 / AGP 8.7.3，实际 Kotlin 2.0.21 / AGP 8.7.0。

## 验证

单元测试强制重跑（不吃缓存）：

```
GRADLE_USER_HOME=/tmp/gh2 ./gradlew --offline --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 49s
39 actionable tasks: 39 executed
```

16 个测试类合计 **149 tests / 0 failures / 0 errors / 0 skipped**：

AmountInputTest 6、AmountParserTest 11、BackupTest 10、BillEditingTest 4、CaptureLimitsTest 11、CaptureModePolicyTest 8、CategorySuggesterTest 9、CostCsvTest 12、ExportRangeTest 7、MiniJsonTest 6、NoteTemplateTest 6、OcrNormalizerTest 6、PageClassifierTest 34、RefundWorkflowTest 11、RootPollPolicyTest 2、SampleExportTest 6。

> 构建环境注意：`~/.gradle` 当前挂在只读文件系统，wrapper 无法创建 `.lck`（报 `java.io.FileNotFoundException: .../gradle-8.14-bin.zip.lck (只读文件系统)`）。必须在**同一次 shell 调用**里先 `cp -a ~/.gradle/{caches,wrapper,daemon,gradle.properties} /tmp/gh2/`，再 `GRADLE_USER_HOME=/tmp/gh2 ./gradlew --offline ...`。`/tmp` 是 tmpfs，跨调用会丢。

产物元数据（未重新构建，复核 2026-10-22 13:49 的 dist 文件）：

| 项 | debug | release |
|---|---|---|
| 文件 | dist/AutoBook-0.6.2-debug.apk | dist/AutoBook-0.6.2-release.apk |
| 大小 | 54,674,941 B | 45,767,745 B |
| versionCode / versionName | 21 / 0.6.2 | 21 / 0.6.2 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 | 26 / 35 / 35 |
| sha256 | `50e6bf4dd6378cd75900ede1d2a2427e0866b1565a006ea04d1f47f2b2245185` | `3287f1edde500fac2dc5e5d09490af07fea2147084bcf7c674e9128c483e6bcb` |
| 签名 | 〔2026-10-22 16:15 订正〕实为 Android Debug 证书，SHA-256 `08b0f9a09430a9f8c121402fe5774dfc978e7e28b074b42390c2a358d0566cd7`（此前误记为"未签名"） | 已签名，证书 SHA-256 `bedb36b7527aa4759262d8b737d7031b57f1db191c68eab4d55829f0598a5ed6`（与 0.5.9/0.6.0/0.6.1 一致） |

`assets/xposed_init` 两个包都存在。debug 包内有完整 17 个图标资源条目（2 个 `mipmap-anydpi-v26/*.xml` + 5 档 × `ic_launcher`/`ic_launcher_round`/`ic_launcher_foreground` 共 15 个 PNG）；release 资源被 AGP/R8 重命名（badging 显示 `application-icon-120:'res/BW.xml'`），图标仍在但无法按文件名检索。

## 限制

- **本 run 没有设备**。LSP 是否真的拦住撤权、`killApplication` 是否真的被拦、120ms 与 600ms 的手感差异，全部**未验证**。
- 〔后记 2026-10-22〕"新图标是否在桌面上生效"这一项已单独结案：0.6.2/0.6.3 当时确实**没换成功**
  （自适应前景层从未重建），0.6.4 重建后由用户真机确认刷新，见 `runs/20261022-163800-icon-rebuild/`。
  本 run 记录的"未验证"是当时的事实，不再改动。
- 0.6.2 之前就没有 `runs/` 目录与 `deliverables/` 快照，这个 run 是补的"元数据证据"，不构成功能验收。
- 0.6.1 遗留的 Root 真机成功采集、开机恢复、功耗三项继续空缺。
- `XposedModuleDetector` 的激活判定不可靠（见上文第 7 条），因此"设置页显示已激活"不能作为 Hook 生效的证据；真正验证要在系统设置里手动关闭无障碍开关并看日志是否出现 `阻止系统解绑无障碍服务`。
- OpenSpec CLI 不可用，`openspec/changes/enhanced-accessibility-lsp/` 为手工撰写并人工核对结构；Git 元信息不可用（本目录非 Git 仓库），改用源文件 sha256 记录。
- 本 run 未修改任何源码，因此 `source_sha256` 记录的是**核查时**的文件状态，不是变更。

来源run：runs/20261022-145102-doc-audit；计划：openspec/changes/enhanced-accessibility-lsp；APK：dist/AutoBook-0.6.2-debug.apk、dist/AutoBook-0.6.2-release.apk。
