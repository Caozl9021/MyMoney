# AutoBook 增强无障碍完整方案 - 总结

> 完成时间：2026-10-22（〔核查修正〕原文写 2024-01-22，是错的）
> 包含：无障碍优化（120ms响应）+ 内置 LSP 增强 + 完整文档
>
> **2026-10-22 核查注记（未改代码）**：本文早期版本把 LSP 写成"独立 `xposed-module` 工程 + 独立
> APK"，并列出 6 处 Hook、给出了一整套没有数据来源的性能/可靠性指标。核对代码后：LSP **已内置进
> 主 App**、真实 Hook **只有 4 处**、`PowerManagerService` 与 `Context.getSharedPreferences`
> **不存在**、`AccessibilityGuardian`/`AccessibilityEnhancer` 在 0.6.2 时是未接线死代码
> （**已在 0.6.3 接通**，见文末 §0.6.3）。文中的百分比指标
> （存活率 99%、内存 15MB、电量 0.2%/h 等）**没有任何实测来源**，已标注。证据见
> [`PROGRESS.md`](PROGRESS.md)。订正处用 `〔核查修正〕` 标出。

---

## 📦 本次完成的所有内容

### 1. 无障碍服务优化（核心改进）

**文件**：`app/src/main/java/com/example/autobook/capture/BillAccessibilityService.kt`

**改进点**（`:29-31` 定义常量，`:95-96` 分级选择）：
```kotlin
// 原来：所有事件统一 600ms 去抖
DEBOUNCE_MS = 600L

// 现在：按事件类型分级
TYPE_WINDOW_STATE_CHANGED -> 120ms   // 窗口切换（付款成功页）
TYPE_WINDOW_CONTENT_CHANGED -> 600ms // 内容变化（滚动）
```

**效果**：付款成功页的去抖等待从 600ms 降到 120ms（**5 倍**）。
注意这是"去抖窗口"变短，不等于端到端"确认卡 200ms 内弹出"——后者没有实测数据。

---

### 2. LSP 增强（内置在主 App，可选启用）

#### 实际文件清单（内置结构）

```
app/src/main/java/com/example/autobook/xposed/
└── AutoBookXposedModule.kt                             # Hook 核心逻辑（208 行）

app/src/main/assets/
└── xposed_init                                         # Xposed 入口声明

app/src/main/java/com/example/autobook/capture/
├── XposedModuleDetector.kt                             # 三档 HookState（0.6.3 重写）
├── GuardianPolicy.kt                                   # 守护判定纯逻辑（0.6.3 新增）
├── AccessibilityGuardian.kt                            # 守护前台服务（0.6.3 已注册并随模式起停）
└── AccessibilityEnhancer.kt                            # 保活体检 + 厂商引导（0.6.3 重写）

docs/
├── LSP_MODULE.md                                       # 完整文档
├── LSP_QUICKSTART.md                                   # 快速开始
├── LSP_SCOPE_CONFIG.md                                 # 作用域配置
└── PROGRESS.md                                         # 跨版本进度总账
```

〔核查修正〕原文列的 `xposed-module/` 独立工程（含 `src/main/res/values/arrays.xml`、
独立 `build.gradle.kts`、`proguard-rules.pro`）、`build-xposed-module.sh`、
`app/src/main/res/drawable/ic_shield.xml`/`ic_warn.xml` 均**不存在或不适用**；
`settings.gradle.kts` 只有 `:app` 与 `:billkit`。

#### Hook 功能列表（按代码实际，只有 4 处）

| Hook 点 | 作用 | 状态 |
|---|---|---|
| `AccessibilityManagerService.unbindService(AccessibilityService)` | 阻止解绑服务 | ✅ 已实现 |
| `AccessibilityManagerService.removeServiceLocked(AccessibilityServiceConnection, int)` | 阻止移除连接 | ✅ 已实现 |
| `ActivityManagerService.killApplication(String, int, String)` | 阻止杀进程 | ✅ 已实现 |
| `ActivityManagerService.killApplication(String, int, int, String)` | 同上（重载） | ✅ 已实现 |
| ~~`PowerManagerService.isIgnoringBatteryOptimizationsLocked`~~ | ~~强制豁免~~ | ❌ **不存在**〔核查修正〕 |
| ~~`Context.getSharedPreferences`~~ | ~~写入标记~~ | ❌ **不存在**〔核查修正〕 |
| ~~`BillAccessibilityService.onUnbind/onDestroy`~~ | ~~监控状态~~ | ❌ **不存在**〔核查修正〕 |

命中 `com.example.autobook` 时置 `param.result = null`；模块只在 `packageName == "android"` 时工作。

---

### 3. App 端集成

#### 修改的文件

| 文件 | 修改内容 |
|---|---|
| `App.kt` | 〔0.6.3〕`ensureGuardianChannel()` 改为委托 `AccessibilityGuardian.ensureChannel(this)`；守护服务起停由 `CaptureModeManager.reconcile()` 负责 |
| `BillAccessibilityService.kt` | 服务启动时检测 LSP 模块并写一条 `Log.i`；末尾幂等 `AccessibilityGuardian.ensureRunning(this)` 兜底 |
| `SettingsScreen.kt` | 权限页显示 LSP 三档状态 + 新增「无障碍保活」组（守护状态 / 电池优化 / 厂商自启动） |
| `CaptureModeManager.kt` | 〔0.6.3〕`reconcile()` 按 `next.accessibilityActive` 起停守护服务 |
| `AndroidManifest.xml` | 〔0.6.3〕注册 `.capture.AccessibilityGuardian`（`specialUse`）+ `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `settings.gradle.kts` | 〔核查修正〕**未修改**：至今只有 `include(":app")` 与 `include(":billkit")` |

#### UI 显示效果

**设置 → 权限页**：

```
┌─────────────────────────────────────┐
│ 采集权限                              │
│ ┌─────────────────────────────────┐ │
│ │ 无障碍服务                        │ │
│ │ 已连接                           >│ │
│ └─────────────────────────────────┘ │
│                                      │
│ 增强模块（可选）                      │
│ ┌─────────────────────────────────┐ │
│ │ LSP 增强模块                      │ │
│ │ ✅ 已激活 — 防止系统撤销无障碍权限 │ │
│ └─────────────────────────────────┘ │
│                                      │
│ LSP 模块已激活，可以防止系统/厂商     │
│ 撤销无障碍权限、防止省电策略杀服务。  │
│ 无障碍服务会更加稳定，不易被系统回收。│
└─────────────────────────────────────┘
```

〔核查修正〕上面这段是 `SettingsScreen.kt:775-803` 里**真实的 UI 文案**（不是文档编的），
但文案本身过度承诺：它说"防止省电策略杀服务/不易被系统回收"，而代码里没有
`PowerManagerService` Hook、也没有任何 LMK 相关处理。这段文案本身建议在下一轮改掉
（本轮不改代码）。

---

## 🎯 三种模式完整对比

### 模式矩阵

〔核查修正〕下表的"120ms""⭐⭐⭐⭐⭐""99% 存活率"等**都没有实测来源**，是设计预期值。
保留表格是为了说明设计意图，实际能力见每行末的备注：

| 特性 | 标准无障碍 | LSP增强无障碍 | Root模式 | 实测状态 |
|---|---|---|---|---|
| **响应速度** | 120ms（去抖窗口） | 120ms | 600-1200ms（轮询间隔） | 仅 0.6.1 模拟器点过，未量端到端 |
| **稳定性** | ⭐⭐⭐ | 未知 | ⭐⭐⭐⭐⭐ | 除 Root 外未实测 |
| **电量消耗** | 极低 | 极低 | 中等 | 全部未测 |
| **需要 Root** | ❌ | ✅ | ✅ | — |
| **需要 LSPosed** | ❌ | ✅ | ❌ | — |
| **防止被杀** | ❌ | ⚠️ 部分（只拦 `killApplication`） | ✅ | 未真机验证 |
| **防止撤权** | ❌ | ✅ 代码已实现 | N/A | 未真机验证 |
| **OCR 兜底** | ❌ | ❌ | ✅ | 0.6.0 之前有模拟器记录 |
| **可上架** | ✅ | ❌ | ❌ | — |

### 推荐方案

**普通用户（未 Root）**：
```
标准无障碍模式（120ms）
+ 手动保活优化（关闭电池优化、锁定后台）
```

**Root 用户（追求极致稳定）**：
```
LSP 增强无障碍（120ms + 防撤权）
或
Root 模式（600ms + 前台服务常驻）
```

**开发者/极客用户**：
```
LSP 增强无障碍（最佳平衡）
```

---

## 🚀 立即使用

### 快速开始（5分钟）

〔核查修正〕原命令三分之二会失败（`build-xposed-module.sh` 第 35 行调不存在的
`:xposed-module:assembleRelease`，`xposed-module-release.apk` 不存在）。正确流程：

```bash
# 1. 构建（LSP 已内含在主 App 里，只有这一个产物）
cd /home/caozl/Projects/AutoBook
source ~/opt/autobook-env.sh
./gradlew :app:testDebugUnitTest     # 167 例 / 18 类（0.6.3）
./gradlew :app:assembleDebug

# 2. 安装（只有一个 APK）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. 在 LSPosed 中激活
# - 勾选 AutoBook（不是「AutoBook 增强模块」）
# - 作用域：只勾 android
# - 重启手机

# 4. 验证
adb logcat | grep "AutoBook-Xposed"
# 预期：开始 Hook 系统框架 / AccessibilityManagerService Hook 完成 / ActivityManagerService Hook 完成
# 可能还会有 设置系统属性失败: ... —— 已知问题，不影响 Hook
```

### 验证效果

**测试付款成功页响应速度**：
1. 打开支付宝/微信
2. 进入账单详情页
3. 观察确认卡弹出速度
4. 〔核查修正〕设计上去抖窗口是 120ms，但"200ms 内弹出"（120ms 去抖 + 80ms 处理）
   这个拆解**没有实测依据**，别当验收标准

**测试 LSP 增强效果**：
1. 开启无障碍服务
2. 〔核查修正〕更可靠的验证方式：**手动去系统设置关闭 AutoBook 的无障碍开关**，
   看日志有没有 `阻止系统解绑无障碍服务`（而不是"锁屏 30 分钟再看状态"，
   那既不能证明 Hook 生效，也混淆了"没被杀"与"被拦"）
3. 辅助观察：`adb shell am force-stop com.example.autobook` 后进程是否立刻回来
4. 查看 App → 设置 → 权限 → LSP 模块状态（仅作参考，见下）

---

## 📊 技术指标

### 性能指标

〔核查修正〕下面两表**全部没有实测来源**，是当时凭设计推断填的数字。0.6.2 没有任何
性能/功耗/存活率测量记录。保留原值是错误的，已改为"未测"：

| 指标 | 标准模式 | LSP增强模式 | Root模式 |
|---|---|---|---|
| 平均响应时间 | 未测（去抖 120ms） | 未测（去抖 120ms） | 未测（轮询 1200ms） |
| 内存占用 | 未测 | 未测 | 未测 |
| 电量/小时 | 未测 | 未测 | 未测 |
| CPU 占用 | 未测 | 未测 | 未测 |

### 可靠性指标

| 指标 | 标准模式 | LSP增强模式 | Root模式 |
|---|---|---|---|
| 服务存活率（24h） | 未测 | 未测 | 未测 |
| 权限保持率 | 未测 | 未测 | N/A |
| 崩溃率 | 未测 | 未测 | 未测 |

> 如果后续要填这些数，测量方法：`dumpsys batterystats` 取时段耗电、
> `adb shell dumpsys meminfo com.example.autobook` 取内存、`adb logcat` 统计
> 服务重连次数算存活率。别再用估计值。

---

## 📖 完整文档索引

| 文档 | 用途 | 读者 |
|---|---|---|
| `AGENTS.md` | 项目交接说明（最核心） | 所有接手的开发者 |
| `README.md` | 功能介绍 | 用户 |
| `PROGRESS.md` | **跨版本进度总账 + 宣称与实现的差异** | 所有接手的开发者 |
| `PLAN_0.5.md` | 版本规划 | 开发者 |
| `ROOT_PLAN.md` | Root 方案说明 | 技术用户 |
| `ICOST_IMPORT.md` | CSV 导出规范 | 集成开发者 |
| `LSP_MODULE.md` | LSP 模块完整文档 | 想用增强功能的用户 |
| `LSP_QUICKSTART.md` | 快速开始 | 快速上手 |
| `LSP_SCOPE_CONFIG.md` | 作用域配置（只勾 android） | 快速上手 |

---

## ✅ 验收清单

### 功能完整性

- [x] 无障碍服务 120ms 分级去抖（代码在，`BillAccessibilityService.kt:29-31`、`:95-96`）
- [x] LSP 模块防止撤权（4 处 Hook 中的 2 处，代码在）
- [x] LSP 模块防止被杀（只有 `killApplication` 两处重载，LMK 不受控）
- [x] App 端检测模块激活状态（三路检测在，但只有堆栈一路可信）
- [x] UI 显示增强状态（`SettingsScreen.kt:775-803`）
- [ ] ~~完整构建脚本~~〔核查修正〕`build-xposed-module.sh` **调用了不存在的 Gradle 任务**，不能算完成
- [x] 完整使用文档（本轮已按代码订正）

### 测试覆盖

- [ ] 真机验证：付款成功页响应速度
- [ ] 真机验证：LSP 模块激活检测
- [ ] 真机验证：防撤权效果
- [ ] 真机验证：锁屏 30 分钟后服务存活
- [ ] 真机验证：不同厂商 ROM 兼容性

> 〔核查修正〕单测侧已有两轮证据：0.6.2 的 2026-10-22 14:51 复跑 **149 tests / 0 failures**（16 类）；
> 0.6.3 的 15:57 `--rerun-tasks` 强制重跑 **167 tests / 0 failures**（18 类）。
> 但**单元测试不覆盖 Hook，也不覆盖守护服务的实际常驻行为**——上面 5 项真机验证全部仍然空白。

### 文档完整性

- [x] 技术架构文档
- [x] 使用指南
- [x] 快速开始
- [x] 常见问题
- [x] 开发调试技巧

---

## 🎉 总结

### 本次完成的核心价值

1. **去抖窗口从 600ms 降到 120ms**（`TYPE_WINDOW_STATE_CHANGED` 分支）
   - 付款成功页的等待明显变短；"5 倍"指的是去抖窗口比例，不是端到端提速

2. **提供可选增强能力**（LSP，已内置进主 App）
   - 代码上能防止系统撤销无障碍权限（2 处 Hook）
   - 只拦 `killApplication`，**不覆盖**厂商省电策略与 LMK
   - 不影响标准模式使用

3. **完整的分层架构**
   - 标准模式：适合普通用户
   - LSP 增强：适合 Root 用户
   - Root 模式：适合追求极致稳定

4. **文档**
   - 用户指南 / 开发文档 / 故障排查
   - 〔核查修正〕本轮之前这套文档与实际代码有 6 处不一致（见 `PROGRESS.md`），现已订正

### 下一步可以做

1. **真机测试验证**（这是当前最大的缺口，0.6.2 与 0.6.3 一条功能证据都没有）
   - 不同 Android 版本（11/12/13/14）
   - 不同厂商 ROM（小米/华为/OPPO/vivo）
   - 长时间稳定性测试（24h/7天）

2. ~~**决定 `AccessibilityGuardian` / `AccessibilityEnhancer` 去留**~~
   〔0.6.3 已完成〕选了"接通"这条路：注册 `<service>`、`CaptureModeManager.reconcile()` 真起停、
   `GuardianPolicy` 三档判定、权限页「无障碍保活」组；Enhancer 删掉全部假数据只留实测项。
   **剩下的只是在真机上验证它确实工作。**

3. **优化 LSP Hook**
   - 根据日志调整 Hook 点（不同 ROM 的 `ActivityManagerService.killApplication` 签名可能不同）
   - 兼容更多 ROM，降低 Hook 失败率
   - 修 `setModuleActiveFlag()` 的系统属性写入（普通应用权限下不可能成功，应改用主 App 能读到的方式）

---

**🎯 当前状态**：0.6.3 的**代码与构建**已完成，**单测 167 全绿 / 18 类**（2026-10-22 15:57 `--rerun-tasks`），
但 **LSP 与无障碍保活全部无真机验证**；文档已按代码订正两轮（0.6.2 核查轮、0.6.3 接通轮）。

**📦 交付物**：
- LSP 增强代码（内置在 `app` 模块，无独立 APK）
- 分级去抖的无障碍服务（120ms / 600ms）
- 无障碍守护服务 + 保活体检（0.6.3，随模式起停、30s 巡检、掉线通知）
- 构建产物：`dist/AutoBook-0.6.4-{debug|release}.apk`（23；0.6.3 的 22 保留在 dist 里作历史对照）
- 文档：`docs/LSP_MODULE.md` + `LSP_QUICKSTART.md` + `LSP_SCOPE_CONFIG.md` + `PROGRESS.md`
- 证据：`runs/20261022-155747-guardian-hardening/`（`outputs/` 为空——无设备，不是功能验收）

**⚠️ 现在能用，但请按"待验收"对待**：装得上、跑得起来、单测全绿；防撤权/防杀是否在真机生效，
需要按上面"验证效果"那一节亲手试一次。

---

## 0.6.3 补记（2026-10-22）

本文写于 0.6.2 交付时，当时 `AccessibilityGuardian` / `AccessibilityEnhancer` 是零调用点的死代码。
0.6.3 把这条链接通了，本文相关表述已同步订正，要点如下：

| 项 | 0.6.2 实情 | 0.6.3 |
|---|---|---|
| 守护服务 | 类存在、Manifest 无 `<service>`、无 `start()`，`App.kt` 只建了通知渠道 | 注册 `specialUse` 前台服务，`CaptureModeManager.reconcile()` + `onServiceConnected` 两处起停 |
| 巡检逻辑 | `lastCheckEnabled` 初值 false，启动时已关永不报警；只查设置不查绑定 | `GuardianPolicy` 三档 `OK/DISABLED/UNBOUND`，累积异常次数，首检即生效 |
| 厂商工具 | `autoStartEnabled=null`、`notificationEnabled=true // TODO`、`attemptAutoRepair()` 恒 false | 只留实测项：通知权限、电池优化豁免、守护是否在跑、厂商识别与跳转 |
| 模块检测 | 三路，SP 标记永假、`getModuleVersion()` 查不存在的包 | 三档 `HookState`，只有堆栈与系统属性两路 |
| 测试 | 149 / 16 类 | **167 / 18 类**（新增 `GuardianPolicyTest` 12、`AccessibilityEnhancerTest` 6） |

**没有变的部分（别被这张表误导）**：Hook 仍然只有 4 处，没有 `PowerManagerService`；
守护服务**只发现掉线并提醒**，不自动重启、不豁免 LMK、不提优先级；两条都**零真机验证**。
