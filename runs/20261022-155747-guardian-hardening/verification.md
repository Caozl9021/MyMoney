# 0.6.3 — 去掉未接线死代码，让无障碍保活真正可用

状态：**单测与构建已验证，功能待真机验收**（`adb devices` 为空，本机没有设备，保活链路一次都没在真机上跑过）。

## 实现

### 1. `AccessibilityGuardian` 从死代码变成真前台服务

上一版的问题（已核实）：类存在、`AndroidManifest.xml` **没有** `<service>` 注册、全工程**没有任何** `start()` 调用点，唯一引用者是自己也被判死的 `AccessibilityEnhancer.getStatus`。等于一份写着"守护"的注释。

现在：

- `AndroidManifest.xml` 注册 `<service android:name=".capture.AccessibilityGuardian" android:exported="false" android:foregroundServiceType="specialUse">` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明；
- `CaptureModeManager.reconcile()` 末尾按 `next.accessibilityActive` 起停（这是模式/开关变化唯一必经的路径，含 `App.onCreate`、`BootReceiver`、设置页开关、SharedPreferences 监听）；
- `BillAccessibilityService.onServiceConnected()` 里再做一次幂等 `ensureRunning` 兜底（用户直接在系统设置里重开无障碍时，App 进程可能没重建，只能靠服务自己拉）；
- 权限页「无障碍保活 → 守护服务」可手动拉起。

同时修掉旧实现的逻辑缺陷：

- 旧 `lastCheckEnabled` 初值 `false`，只在"由开变关"时计数 —— **服务启动时无障碍已经关着的话永远不报警**。新 `GuardianPolicy` 直接累积"连续异常次数"，首检即生效；
- 旧实现只查 `ENABLED_ACCESSIBILITY_SERVICES`（设置里有没有打勾），分不出"打勾了但服务被解绑"这种厂商假开启。新实现把 `Health` 分成 `OK / DISABLED / UNBOUND` 三档，`UNBOUND` 阈值放宽到 4 次（约 2 分钟）避免冷启动误报，`DISABLED` 是 2 次（约 60s）；
- 忽略提醒后按 `REALERT_EVERY_MISSES = 20`（约 10 分钟）再提醒，计数封顶 10,000 防止长跑溢出。

### 2. `AccessibilityEnhancer` 只留能真实做到的事

删掉的部分（都是假的或纯摆设）：

- `autoStartEnabled: Boolean?` 恒为 `null`，从来没被赋值；
- `notificationEnabled = true // TODO: 检查通知权限` —— 硬编码；
- `attemptAutoRepair()`：`disableSelf()` + 主线程 `Thread.sleep(500)` 然后 `return false`，什么都修不了，还会卡主线程；
- `logStatus()`、`guideUserToOptimize()`：无人调用。

保留并全部改成实测：`isBatteryOptimizationIgnored()`（`PowerManager`）、`notificationsEnabled`（`NotificationManagerCompat`）、`accessibilityChecked` / `accessibilityEnabled`、`guardianRunning`。

### 3. `XposedModuleDetector` 不再假装知道

旧实现三路检测里，SharedPreferences 那一路**没有任何写入方**（模块只 Hook 系统框架，不写 App 的 prefs），查包 `com.example.autobook.xposed` 的那个包**根本不存在**，`getEnhancedFeatures()` 还返回 5 条带 ✅ 的断言，其中"防止低内存时被系统回收""自动恢复失效的无障碍服务""优先级提升"三条代码里没有。

现在只保留两路，产出三档 `HookState`：`NOT_DETECTED` / `FRAMEWORK_PRESENT`（堆栈里看到框架，但不确定模块启用）/ `MODULE_CONFIRMED`（`persist.autobook.xposed.active == "true"` 才认）。UI 文案相应改为"已确认 / 检测到框架但未确认 / 未检测到"，并加一句"显示未确认不等于模块没生效"。

### 4. 权限页新增「无障碍保活」组

只在无障碍模式下显示，三行全部来自实测：守护服务状态、电池优化豁免（可一键跳系统对话框，拿不到就退电池优化列表页）、厂商自启动（按 `Build.BRAND` 给经验 ComponentName，`resolveActivity` 解析不到就退应用详情页）。footer 明说"系统不提供检查自启动是否已开的接口，只能手动确认"。

### 5. 三个脚本

- `build-xposed-module.sh`：删掉必失败的 `:xposed-module:assembleRelease`（`settings.gradle.kts` 里只有 `:app` 和 `:billkit`），改为构建主 App；
- `build-all.sh`：版本号从 `app/build.gradle.kts` 读，`~/.gradle` 不可写时自动切临时 `GRADLE_USER_HOME`，出包后用 `aapt2 dump badging` 核对 dist 文件名与 APK 实际版本；
- `check-lsp-status.sh`：`adb shell "unzip -l"` 在多数 ROM 静默失败，改为 `adb pull` + 本地 `unzip`；新增设备状态前置校验与"阻止系统解绑无障碍服务"命中计数。

## 验证

| 项目 | 结果 |
|---|---|
| `:app:testDebugUnitTest`（`--rerun-tasks` 强制重跑） | **167 tests / 0 failures / 0 errors / 0 skipped / 18 类**，`BUILD SUCCESSFUL in 58s`，`39 actionable tasks: 39 executed` |
| 新增测试类 | `GuardianPolicyTest` 12（`health` 三档判定、计数清零/累加、两档阈值、重复提醒窗口、计数封顶、"服务启动时已关也能报警"回归）＋ `AccessibilityEnhancerTest` 6（厂商识别、空品牌兜底、`isFullyOptimized` 真值表、引导文案非空） |
| `:app:assembleDebug` | `BUILD SUCCESSFUL` |
| `:app:assembleRelease` | `BUILD SUCCESSFUL`（R8） |
| debug APK | `dist/AutoBook-0.6.3-debug.apk`，54,954,287 B，sha256 `2997b513d55da73afdeb7e9c00b9991260391da6cf258b8648387e5db525c1c6`，签名 `08b0f9a0…566cd7`（Debug 证书，与前版一致） |
| release APK | `dist/AutoBook-0.6.3-release.apk`，45,767,925 B，sha256 `f5bf2982f5f7ba208cb4b7f2942f240d5043767fc3e2160e37f4b67624025f7b`，签名 `bedb36b7…8a5ed6`（与前版一致） |
| 版本 | 两包均 `versionCode='22' versionName='0.6.3'`，minSdk 26 / targetSdk 35 / compileSdk 35 |
| release dex | 含 `GuardianPolicy`(4) / `UNBOUND`(2) / `accessibilityChecked`(2) / `isEnabledInSettings`(1) / `KeepAliveStatus`(3)，`assets/xposed_init` 内容不变 |
| Manifest | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 在、`AccessibilityGuardian` `<service>` 在且 `exported=false` / `foregroundServiceType=0x40000000` |

## 限制

1. **没有任何真机验证**。`adb devices` 为空。以下全部未验证：守护服务能否常驻、掉线通知能否弹出、"设置里打勾但被解绑"是否真能识别、权限页三行状态在真机上读数是否正确、0.6.3 是否比 0.6.2 更稳。
2. **保活能力有客观上限**，不是"接上就万事大吉"：
   - 能做的：阻止系统/厂商解绑无障碍服务需要 LSPosed + 模块生效（仅部分 ROM 有效）；电池优化豁免需要用户手动点；厂商自启动开关**没有 API**，只能跳转引导。
   - 不能做的：低内存 LMK 杀进程（没接 `PowerManagerService` Hook，也不打算接 —— 改电源策略风险高）；被杀后自动重启（`onDestroy` 不重启服务）；提高进程优先级（无实现）。
   - 也就是说"保活"的实质是**掉线能被发现并提醒用户**，不是"永不掉线"。文档与 UI 都按这个口径写。
3. **`XposedModuleDetector` 只认系统属性**，模块侧 `SystemProperties.set` 在普通权限下几乎必然失败，所以 `MODULE_CONFIRMED` 大概率长期显示不出来。这不是 bug，是权限现实；`FRAMEWORK_PRESENT` 与真实日志才是可用信号。
4. 守护服务是 `specialUse` 前台服务，会常驻一条最低优先级通知。这是 Android 8+ 的硬性要求，不是可选项。
5. 本机构建环境：`~/.gradle` 在只读文件系统上，必须 `cp -a` 到临时目录并 `export GRADLE_USER_HOME`；Kotlin 编译守护进程会刷 `kotlin-daemon-client-tsmarker*.tmp: 只读文件系统` 的 `e:` 行，属无害噪音。
6. 非 Git 仓库，无法用 commit 定位；OpenSpec CLI 不可用，`openspec/` 结构与 spec 只能手工核对。

## 待办（下一轮）

1. 真机跑一遍验收清单（见 `openspec/changes/enhanced-accessibility-lsp/tasks.md` 的 T9）。
2. 决定 `AccessibilityGuardian` 的前台服务通知是否要提供「暂停守护」动作（现在是纯状态通知）。
3. `XposedModuleDetector` 若要能真正确认，只能让模块 Hook 到 App 进程本身（当前刻意只 Hook `android`），这是设计取舍，要改需重新评估作用域配置。

## 补记：2026-10-22 16:12 重新出包

第一轮出包后又改了一处**纯注释**：`app/src/main/java/com/example/autobook/xposed/AutoBookXposedModule.kt`
的类 KDoc 原本写着"功能：1. 防止系统撤销无障碍权限 2. 防止厂商省电策略杀服务 3. 保持无障碍服务常驻"，
后两条代码里并不存在（没有 `PowerManagerService` Hook、不重启任何东西），已改成真实的"只做两件事"
并显式列出"做不到"的三条。**没有任何逻辑改动**，但为了让 `dist/` 与源码严格对应，重新跑了一遍：

```
GRADLE_USER_HOME=/tmp/gh5 ./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
→ BUILD SUCCESSFUL in 2m 11s, 146 actionable tasks: 19 executed, 127 up-to-date
→ 单测 18 类 167 tests / 0 failures / 0 errors / 0 skipped
```

`dist/AutoBook-0.6.3-{debug|release}.apk` 已用新产物覆盖，上表两个 sha256 与 `manifest.json` 都是覆盖后的值
（debug `2997b513…25c1c6` / release `f5bf2982…4025f7b`）；字节数与签名证书不变。

来源 run：`runs/20261022-155747-guardian-hardening/`；上一轮文档核查 run：`runs/20261022-145102-doc-audit/`；APK：`dist/AutoBook-0.6.3-{debug|release}.apk`。
