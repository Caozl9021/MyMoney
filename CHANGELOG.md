# AutoBook 版本更新日志

按发布版本记录历史；当前事实见[进度](docs/PROGRESS.md)，后续计划见[plan1.0](docs/PLAN_1.0.md)。旧条目提及的HANDOVER/ENHANCEMENT_SUMMARY已于2026-10-23合并清理，保留名称仅说明当时改动。0.6.2早期能力宣称的订正过程见进度历史章节。LSP“四处”应理解为四种候选签名，killApplication重载为回退。

## v0.6.8 (2026-10-23) - 全入口品牌图标与微信成功页识别（待验收）

- 所有应用品牌入口统一来自最新 `Auto.jpg`：桌面／主题图标、确认与 Root／无障碍守护通知、识别磁贴、长按快捷方式、小组件头部及确认卡徽章。通知／磁贴使用独立 24dp 白色 alpha 小图，彩色图用于大图和应用内品牌位置；功能导航及分类图标保留动作语义，桌面图标留白不变。彩色保留四彩圆角边框和内部白色；单色另将近白色转为负空间，保留边框与内部字形并滤除 JPEG 白噪声。
- 补充微信极简付款成功页的严格识别支持，覆盖缺少支付方式字段的成功页，并保留失败、未完成、列表及金额歧义的拒绝约束。
- 无障碍事件改为有界调度，窗口事件的 120ms 抓取时机不再被后续内容事件反复重置；保持模式、白名单、Root／OCR、去重及投递权限边界。
- 微信回归样本来自截图人工转录及拆行场景，证明文本规则与调度策略覆盖；截图可见文字不等于实际无障碍节点，尚不能据此宣称已在真机复现并解决所有付款瞬间漏报。
- 版本 0.6.8（27）；保留 0.6.7 安装包和历史证据。统一单测、构建与包内核查由本轮交付记录提供，用户验收仍待完成。

## v0.6.7 (2026-10-23) - 本地规则、外观体验与系统入口（待验收）

- 桌面图标改用用户提供的 `Auto.png`；仅清除 alpha≤1% 的几乎不可见杂点后按透明边界裁切，保留图内原色及白色，统一生成五档 legacy、自适应前景和单色遮罩。自适应内容 54dp、legacy 55% 留白与白底不变。
- 本地规则统一评分并分别记录分类与账户来源；支持 JSON 覆盖、样本索引、近 7 天计数、餐段和人工确认学习。仅成功人工入库后学习，关闭学习同时暂停已有学习推荐。
- 新增跟随系统／浅色／深色主题、三档字号与降低动效；优化大字号、宽屏布局、确认卡手势、页面及金额过渡、首页待确认与实际模式引导、按完整月份查询统计。
- 新增 Android 13+ 独立单色图标、桌面长按「记一笔」、识别磁贴及本月原支出小组件；退款不计入原支出，小组件跟随账单变化和日期刷新，Doze 下跨日刷新可能延迟。
- 保留已调整的 60dp、左右 36dp 轻透 dock 与 54dp 图标留白。版本 0.6.7（26），输出 debug 与签名 release，旧版安装包保留。
- 预览已在 API 35 模拟器核查保存／取消／删除撤销、草稿重建保留、磁贴启停、小组件添加点击与浅深／大字号布局；安全锁屏、完整 TalkBack、低 API 设备及真机观感仍待验证。本次出包不代表用户验收通过。

## v0.6.6 (2026-10-22) - 参考视频优化 dock 与中性浅色背景（待验收）

- 浅色页面背景改为 KSU 截图参考的中性 `#F7F7F7`，保留白卡层次；深色背景不变。
- dock 降低折射、色散、模糊及深色底色强度，双层缓存柔和阴影与独立中性细高光；旧版 Android 减弱双重投影。
- 选中块连续可打断位移，移动中轻微横伸纵压并收稳，快速反向从当前位置接续；图标文字独立，重复点击无触感。此次未新增拖动手势。
- 保留 68dp 高、左右 50dp、导航栏上 16dp、色块静止外缘 3dp、Tab 选中语义及页面状态保存。版本 0.6.6（25），证据见 `docs/PROGRESS.md`，视觉效果待设备与用户验收。

## v0.6.5 (2026-10-22) - 桌面图标增加四周留白（待验收）

- 保留原有彩色图案与白底，自适应前景从 68dp 缩至 54dp（108dp 画布不变），线性缩小约 21%。
- legacy 原图去白边后以画布 55% 的最大边居中生成，五档三套共 15 个 PNG 全部重建。
- 生成脚本校验尺寸、透明性、内容包围盒与居中（中心偏差≤1px）；不改记账业务。
- 版本 0.6.5（24）；构建、签名与运行证据见 `docs/PROGRESS.md`。真机观感与裁切边距待用户验收。

## v0.6.4 (2026-10-22) - 彻底重建桌面图标（修掉自适应前景层从未更新的问题）

> **为什么要单独出一版**：用户反馈"图标怎么还没更新"。查下来不是桌面缓存，是资源配置错位 ——
> `mipmap-anydpi-v26/ic_launcher.xml` 的 `<foreground>` 指向 `@mipmap/ic_launcher_foreground`，
> 而 API 26+ 的桌面**只显示这一层**；但旧的两个生成脚本（`generate-icons.sh` / `regenerate-icons.sh`）
> 只写 `ic_launcher.png` 与 `ic_launcher_round.png`，**从来没生成过 `ic_launcher_foreground.png`**。
> 于是 2026-10-22 13:48 换的新图只进了 legacy 那两套，自适应前景层还停在 2026-10-21 15:03 的旧图 →
> 真机（Android 8.0 以上全中）一直显示旧图标。

### 改了什么

- **删掉全部 15 个旧 PNG**：`mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` /
  `ic_launcher_round.png` / `ic_launcher_foreground.png` 全清零，不保留任何旧图 fallback。
- **用新源图重建整套 15 个 PNG**（`AutoBook-converted.png`，1254×1254 真 PNG）：
  - legacy：48/72/96/144/192 px，不透明 RGB；
  - 自适应前景：108/162/216/324/432 px，RGBA，内容缩到 68dp 安全区、居中、白底转透明。
- **自适应背景色** `values/colors.xml`：`#C0E1D7`（浅薄荷绿，旧图遗留色）→ `#FFFFFF`（新图是彩绘 + 白底）。
- **`generate-icons.sh` 全文重写**：一次生成 legacy + 自适应前景 + 背景色三样，并自带校验
  （尺寸与非透明性），漏生成任何一个都会 `exit 1`。支持 `--dry-run`。
- **删除 `regenerate-icons.sh`**：它和 `generate-icons.sh` 功能重叠且同样漏掉前景层，就是这次事故的直接来源。

### ✅ 验证

- 单测：**167 例 / 18 类全绿**（0/0/0），未因本次改动受影响；随后用 `--rerun-tasks` 强制重跑一次
  （`BUILD SUCCESSFUL in 54s`，18 个 XML 归档在 `runs/20261022-163800-icon-rebuild/test-results/`）。
- 构建：`:app:assembleDebug` + `:app:assembleRelease` 成功。
- 产物：`dist/AutoBook-0.6.4-debug.apk`（54,732,457 B，sha256 `021c1daf…27d414`）、
  `dist/AutoBook-0.6.4-release.apk`（45,812,545 B，sha256 `8006cd4e…534422`），均 `versionCode 23 / versionName 0.6.4`。
  签名不变：release `bedb36b7…8a5ed6`、debug `08b0f9a0…566cd7`。
- 包内核对（debug，可直接按名查）：17 个 `ic_launcher*` 条目齐全，`ic_launcher_foreground.png` 五档字节数
  8590/14634/20982/35825/53182 —— 与源文件一一对应（旧包该文件是 3659/6591/10242/20653/34715）。
- 包内核对（release，资源被 R8 重命名）：`res/BW.xml` 的 `<foreground>` 指向的 `res/as.png` 是
  432×432 RGBA、中心像素 `srgba(48,129,250,1)`，`color/ic_launcher_background` = `#ffffffff`。
- 证据 run：`runs/20261022-163800-icon-rebuild/`（manifest + verification + 单测日志 + 18 个 XML；
  `outputs/` 故意为空 —— 没有设备）。变更记录 `openspec/changes/rebuild-launcher-icons/`。

### ✅ 真机确认（2026-10-22 补记）

- **桌面图标已刷新**：用户装 `dist/AutoBook-0.6.4-release.apk` 后，桌面立刻显示白底彩色圆环新图，
  无需清缓存、无需重启桌面。0.6.2/0.6.3 一直显示旧图标的问题到此闭环。
- 说明：取证时 `adb devices` 为空，这条是用户实测后的口头反馈，**没有截图**，
  故 `runs/20261022-163800-icon-rebuild/outputs/` 继续为空。

### ⚠️ 仍未验证

- `ic_launcher_foreground` 的 68dp 安全区是通用规则，具体到这张图在圆形/方形/泪滴形遮罩下是否被裁到，
  **没有逐形状目视确认**（用户未逐项检查，本机无设备）。

## v0.6.3 (2026-10-22) - 清理死代码，无障碍保活真正接通

> 承接上一轮的核查：0.6.2 的 `AccessibilityGuardian` / `AccessibilityEnhancer` 是零调用点的死代码，
> 而"保活"恰恰是用户最需要的能力。这一版把这条链路接通，并删掉所有"代码里有、实际没接"的东西。
> **仍然没有真机验证**（本机 `adb devices` 为空），功能验收全缺。
> 证据：`runs/20261022-155747-guardian-hardening/`。

### 🎯 核心改动

#### 1. 守护服务（AccessibilityGuardian）真的跑起来了
- `AndroidManifest.xml` 注册 `<service .capture.AccessibilityGuardian>`（`exported=false`、`foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）
- `CaptureModeManager.reconcile()` 按 `next.accessibilityActive` 起停 —— 这是 `App.onCreate`、`BootReceiver`、设置页开关、prefs 监听唯一的公共路径
- `BillAccessibilityService.onServiceConnected()` 追加幂等 `ensureRunning` 兜底（用户直接在系统设置里重开无障碍时也能拉起）
- 权限页「无障碍保活 → 守护服务」可手动拉起

#### 2. 修掉旧守护逻辑的两个真缺陷
- 旧 `lastCheckEnabled` 初值 `false`，只在"由开变关"时计数 → **服务启动时无障碍已关就永远不报警**。新 `GuardianPolicy` 直接累积连续异常次数，首检即生效
- 旧实现只查 `ENABLED_ACCESSIBILITY_SERVICES`，分不出"设置里打着勾但服务已被解绑"的厂商假开启。现在分 `OK / DISABLED / UNBOUND` 三档，`UNBOUND` 容忍 4 次（约 2 分钟，避开冷启动）后才提醒
- 提醒去重：忽略后每 20 次（约 10 分钟）再提醒；计数封顶 10,000

#### 3. 删除假数据与摆设代码
- `AccessibilityEnhancer`：删 `autoStartEnabled`（恒 null）、`notificationEnabled = true // TODO`（硬编码）、`attemptAutoRepair()`（主线程 sleep 后 `return false`）、`logStatus()`、`guideUserToOptimize()`
- `XposedModuleDetector`：删永假的 SharedPreferences 路（模块不写 App prefs）、删查不存在包 `com.example.autobook.xposed` 的 `getModuleVersion()`、删 5 条含"防止低内存回收 / 自动恢复服务 / 优先级提升"的 `getEnhancedFeatures()`
- 状态改为三档 `HookState`：`NOT_DETECTED` / `FRAMEWORK_PRESENT` / `MODULE_CONFIRMED`，UI 不再把"看到框架"说成"模块已启用"

#### 4. 权限页新增「无障碍保活」组（仅无障碍模式显示）
- 守护服务状态 / 电池优化豁免（可一键跳系统对话框）/ 厂商自启动（按 `Build.BRAND` 给经验入口，解析不到退应用详情）
- 新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限
- footer 明说"系统不提供检查自启动是否已开的接口，只能手动确认"

#### 5. 脚本修复
- `build-xposed-module.sh`：删除必失败的 `:xposed-module:assembleRelease`（该模块不存在），改为构建主 App
- `build-all.sh`：版本号从 `app/build.gradle.kts` 读取（不再硬编码）、`~/.gradle` 不可写时自动切临时 `GRADLE_USER_HOME`、出包后用 `aapt2` 核对 dist 文件名与 APK 实际版本
- `check-lsp-status.sh`：`adb shell "unzip -l"`（多数 ROM 无 unzip，静默失败）改为 `adb pull` + 本地 `unzip`；加设备状态前置校验与 Hook 命中计数

### ✅ 验证
- 单测：**167 / 0 / 0 / 0，18 类**（`--rerun-tasks` 强制重跑，`BUILD SUCCESSFUL in 58s`）；新增 `GuardianPolicyTest` 12、`AccessibilityEnhancerTest` 6
- 构建：`:app:assembleDebug`、`:app:assembleRelease`（R8）均成功
- 产物：`dist/AutoBook-0.6.3-debug.apk`（54,954,287 B，sha256 `2997b513…25c1c6`）、`dist/AutoBook-0.6.3-release.apk`（45,767,925 B，sha256 `f5bf2982…4025f7b`），均 `versionCode=22 / versionName=0.6.3`
- 签名：release 证书 `bedb36b7…8a5ed6`（与前版一致）；debug `08b0f9a0…566cd7`
- 说明：首次出包后又把 `xposed/AutoBookXposedModule.kt` 的类注释改成真实口径（原注释写着"防省电策略杀服务/保持常驻"，代码里没有），**无逻辑改动**；为让 `dist/` 与源码严格对应，16:12 重跑一遍单测+双包（`BUILD SUCCESSFUL in 2m 11s`，单测仍 167/0/0/0），上表两个 sha256 是覆盖后的值

### ⚠️ 未验证 / 做不到的
- **零真机验证**：守护服务能否常驻、掉线通知能否弹、"假开启"能否识别、权限页读数是否正确，全部未测
- 保活有客观上限：低内存 LMK 杀进程拦不住、被杀后不自动重启、不提高进程优先级；"保活"的实质是**掉线能被发现并提醒**，不是"永不掉线"
- `MODULE_CONFIRMED` 大概率长期不出现：模块侧 `SystemProperties.set` 在普通权限下几乎必然失败，这是权限现实

## v0.6.2 (2026-10-22) - 无障碍增强与内置 LSP 模块

### 🎯 核心改进

#### 1. 无障碍服务响应速度优化
- 〔核查修正〕"快 5 倍"指**去抖窗口比例**（600ms → 120ms），是设计值，**没有端到端实测**；真实手感未验证
- 按事件类型分级去抖：
  - `TYPE_WINDOW_STATE_CHANGED`：120ms（窗口切换，快速响应付款成功页）
  - `TYPE_WINDOW_CONTENT_CHANGED`：600ms（内容变化，过滤滚动抖动）
- 几乎不会错过付款成功页

#### 2. LSP 增强功能内置（可选）
- **LSP 功能直接内置到主 App**（无需安装独立模块）
- 只需一个 APK，在 LSPosed 中激活即可启用增强功能（作用域只需 `系统框架 (android)`；模块只在该进程生效）
- Hook 系统框架防止撤销无障碍权限：
  - `AccessibilityManagerService.unbindService(AccessibilityService)` - 阻止解绑服务
  - `AccessibilityManagerService.removeServiceLocked(AccessibilityServiceConnection, int)` - 防止移除连接
  - `ActivityManagerService.killApplication(String, int, String)` - 阻止杀进程
  - `ActivityManagerService.killApplication(String, int, int, String)` - 同上，重载
- 〔核查修正〕原文列的 `PowerManagerService - 强制豁免电池优化` **并未实现**：全工程检索 `PowerManager` 只命中 `capture/AccessibilityEnhancer.kt:105` 与 `root/RootWatcher.kt:128` 的普通 API 调用，`AutoBookXposedModule.kt` 中只有上述 4 处 `XposedHelpers.findAndHookMethod`。
- 不修改任何第三方 App，仅保护 AutoBook 自己的服务
- 未激活时 App 正常工作（标准无障碍模式）

#### 3. App 端集成
- **XposedModuleDetector**：三种方式检测模块激活状态
  - SharedPreferences 标记（LSP Hook 写入）
  - 堆栈跟踪检测
  - 系统属性检测
- 〔核查修正〕这三路里只有"堆栈跟踪"可信：模块只 Hook `packageName == "android"`，**没有** Hook `Context.getSharedPreferences`，所以 SharedPreferences 一路没有写入方；`setModuleActiveFlag()` 调 `SystemProperties.set` 在普通应用权限下几乎必然抛异常被 catch，系统属性一路大概率也写不进去；`getModuleVersion()` 查的包 `com.example.autobook.xposed` 根本不存在（模块内置在主 App 内）。
- **UI 显示**：设置 → 权限页显示 LSP 模块状态（`SettingsScreen.kt:775-796`，仅 ACCESSIBILITY 模式下显示该行）
- **服务增强**：启动时自动检测并记录增强状态（`BillAccessibilityService.kt:63`，仅写一条 `Log.i`）

### 📦 新增文件

```
app/src/main/java/.../xposed/
└── AutoBookXposedModule.kt                 # LSP Hook 核心逻辑（内置）

app/src/main/assets/
└── xposed_init                             # Xposed 入口声明

app/src/main/java/.../capture/
├── XposedModuleDetector.kt                 # 检测模块激活
├── AccessibilityGuardian.kt                # 〔核查修正〕0.6.2 时未接线（0.6.3 已注册为 specialUse 前台服务）
└── AccessibilityEnhancer.kt                # 〔核查修正〕0.6.2 时无外部调用点（0.6.3 已重写并接入权限页）

docs/
├── LSP_MODULE.md                           # 完整技术文档
├── LSP_QUICKSTART.md                       # 5分钟快速开始
├── ENHANCEMENT_SUMMARY.md                  # 总结文档
└── PROGRESS.md                             # 跨版本进度总账（2026-10-22 核查新增）

build-all.sh                                # 统一构建脚本
CHANGELOG.md                                # 本文件
```

### 🔧 修改文件

- `BillAccessibilityService.kt` - 添加分级去抖 + LSP 检测
- `SettingsScreen.kt` - 权限页显示 LSP 模块状态
- `App.kt` - 初始化守护服务通知渠道（〔核查修正〕只建了 `autobook_guardian` 渠道，未启动任何守护服务）
- `app/build.gradle.kts` - 添加 Xposed API 依赖
- `settings.gradle.kts` - 〔核查修正〕**实际未改**：至今只有 `include(":app")` 与 `include(":billkit")`，没有 Xposed 仓库也没有 `:xposed-module` 模块
- `AndroidManifest.xml` - 添加 Xposed 模块元数据（`xposedminversion` 实际为 **93**）
- `proguard-rules.pro` - 保护 Xposed 相关代码
- `AGENTS.md` - 更新版本说明和目录结构

### 📊 版本信息

- **App**：0.6.2 (versionCode 21)
- **包含内置 LSP 模块**：无需独立安装

### 📦 构建产物

```
dist/
├── AutoBook-0.6.2-debug.apk           (53MB) - 调试版，包含 LSP 功能
└── AutoBook-0.6.2-release.apk         (44MB) - 发布版，包含 LSP 功能
```

### 🚀 使用方式

#### 标准模式（无需 Root）
1. 安装 `AutoBook-0.6.2-release.apk`
2. 选择无障碍模式
3. 开启无障碍服务
4. 享受 120ms 快速响应

#### LSP 增强模式（需要 Root + LSPosed）
1. 安装 `AutoBook-0.6.2-release.apk`（同一个 APK）
2. 在 LSPosed 管理器中找到 AutoBook
3. 勾选启用
4. 〔核查修正〕作用域**只需勾 `系统框架 (android)`**；早期文档要求再勾 `AutoBook (com.example.autobook)` 是错的——模块 `handleLoadPackage` 只在 `lpparam.packageName == "android"` 时 hook，勾主 App 不会产生任何效果
5. 重启手机
6. 打开 AutoBook → 设置 → 权限，查看「LSP 增强模块」状态（〔核查修正〕该状态由 `XposedModuleDetector` 推断，当前只有"堆栈跟踪"一路可靠，显示"已激活"不等于 Hook 真的生效；尚未真机验证）

### 📖 完整文档

- **技术文档**：`docs/LSP_MODULE.md`
- **快速开始**：`docs/LSP_QUICKSTART.md`
- **总结文档**：`docs/ENHANCEMENT_SUMMARY.md`
- **进度总账**：`docs/PROGRESS.md`（2026-10-22 核查新增，含逐条证伪与待办）

### ⚡ 性能对比

| 指标 | v0.6.1 | v0.6.2 标准 | v0.6.2 LSP增强 |
|---|---|---|---|
| 付款成功页响应 | 600ms | 120ms | 120ms |
| 服务稳定性 | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 防止撤销权限 | ❌ | ❌ | ✅ |
| 防止被杀 | ❌ | ❌ | ✅ |
| APK 数量 | 1 | 1 | 1（同一个） |

〔核查修正〕上表的 120ms 与"APK 数量 1"有代码/产物证据；**"服务稳定性 ⭐⭐⭐⭐⭐""防止撤销权限 ✅""防止被杀 ✅"三行是设计预期，没有任何真机验证**，且守护服务在 0.6.2 时未接线（0.6.3 已接通，但仍只做"发现掉线并提醒"），稳定性不应据此标高。核对依据见 `docs/PROGRESS.md` 第三节。

### 💡 架构改进

从 v0.6.2 开始，LSP 增强功能**内置到主 App**，无需独立模块：
- ✅ 用户体验更好：只需安装一个 APK
- ✅ 维护更简单：单一代码库
- ✅ 文件体积增加：仅 ~100KB（Xposed API）
- ✅ 兼容性：未激活时完全不影响正常使用

---

## v0.6.1 (2026-10-22) - 模式选择补遗

### 核心改进
- 首次明确选择 ROOT/ACCESSIBILITY 模式
- `CaptureModeManager` 统一管理状态
- 设置界面按模式分别引导

---

## v0.6.0 (2026-10-22) - 核心账本功能完善

### 核心改进
- 新增 REFUND 退款类型
- 支持手动新增/编辑明细
- 左滑删除 + 5秒撤销
- iCost 导出模板支持

---

## v0.5.9 (2026-10-22) - Dock 调整补遗

### UI 优化
- 胶囊外阴影优化
- Tab 选中块动画收敛
- 状态保存优化

---

更早版本历史请查看 `AGENTS.md`
