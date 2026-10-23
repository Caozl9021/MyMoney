# AGENTS.md — AutoBook 项目交接说明

> 2026-10-23 v0.6.8（27，待验收）：所有品牌入口更新为最新Auto.jpg，保留四彩边框及54dp/55%留白，独立单色负空间避免实心块；包括桌面/主题、通知、磁贴、shortcut、widget、确认卡。微信截图缺支付方式导致旧规则不命中，新增严格极简成功页分类；无障碍窗口120ms不再被内容事件推迟，内容最大等待1秒且根节点仅重试一次。221单测/24类全绿、双构建及26包内资源/版本/签名核查通过；API35确认卡/磁贴/widget目视通过。截图转录非节点dump，真实微信付款瞬时弹框仍待真机验证。证据 `/home/caozl/Projects/AutoBook/runs/20261023-092336-brand-jpg-wechat-release/outputs/verification.md`；包 `dist/AutoBook-0.6.8-{debug,release}.apk`。


> 2026-10-23 v0.6.7（26）新图及正式出包（待验收）：已将用户Auto.png更新到五档legacy/自适应及独立monochrome，保留白底、adaptive54dp/legacy55%留白；alpha≤1%透明噪点清理，原图未改。包含本地规则B0–B5/B7与U3–U10、60dp扁长dock。204单测/22类全绿，debug及签名release均成功；主代理独立核对0.6.7/26、新图可见RGB及全部alpha一致、release证书与0.6.6一致。产物dist/AutoBook-0.6.7-{debug,release}.apk，证据runs/20261023-085355-new-icon-release-0.6.7/。本次无在线设备，未验证新图在桌面实际刷新或release真机运行；此前API35预览证据仍有效但不等同新图验收。

> 2026-10-22 U系列预览补遗（待验收）：剩余U3–U10已实现，保留U1/U2当前轻透60dp dock及图标留白。新增外观主题/字号/降低动效、大字号与560dp布局、速度手势/页面及金额过渡、首页真实模式引导/待确认优先、完整月查询、monochrome/桌面快捷方式/识别磁贴/月度widget。204单测/22类全绿及debug构建通过；API35实际核查手动保存编辑取消、删除撤销、草稿重建保留、磁贴启停、widget添加点击、2.6字号/宽屏/深色及抓手分支。版本仍0.6.6/25，无release/dist。安全锁屏、完整TalkBack、低API及真机观感未实测；widget Doze跨日可延迟。证据 `runs/20261022-231606-u-series/outputs/verification.md`，用户验收未完成。

> 2026-10-22 本地规则系统预览补遗（待验收，优先于下方历史待做状态）：B0–B5及B7已实现；B3并入B7，B6/B8不在本轮。统一评分/独立分类账户来源、JSON覆盖、样本index.txt、近7天计数、餐段与手改立即学习/人工接受3次学习均已接入；设置→本地规则管理。成功人工入库才学，历史明确修改不累计频次。关闭学习同时暂停既有学习推荐。194单测/20类全绿，之后debug构建成功；版本保持0.6.6/25，无release/dist更新。证据 `runs/20261022-225837-local-rules-integration/`。模拟器已核查本地规则布局、餐段保存、学习开关持久化及坏JSON导入保护；真实支付/悬浮确认及用户验收待完成。

> 给后续会话 / 接手的 Agent 看。读完这一份就应该能不问你任何问题直接干活。
> 最后更新：2026-10-22（**v0.6.6：dock 动效/轻透材质与中性浅色背景，待验收**）。查"改到哪了"先看 `docs/PROGRESS.md`，查"怎么改"才看本文件。
>
> v0.6.2 实情（已逐条核对代码，优先于下面那句历史宣传）：无障碍按事件分级去抖（`TYPE_WINDOW_STATE_CHANGED` 120ms / `TYPE_WINDOW_CONTENT_CHANGED` 600ms）已实现；内置 LSP 模块**只 Hook 了 AccessibilityManagerService 的 `unbindService`/`removeServiceLocked` 与 ActivityManagerService 的 `killApplication`（String,int,String）及其 (String,int,int,String) 重载共 4 处**（入口 `assets/xposed_init` → `com.example.autobook.xposed.AutoBookXposedModule`，仅对 `packageName == "android"` 生效）。**没有** Hook `PowerManagerService`（"强制豁免电池优化"从未实现）、**没有** Hook `Context.getSharedPreferences`。`AccessibilityGuardian` / `AccessibilityEnhancer` 是**未接线的死代码**（Manifest 未注册 `<service>`、全工程无 `start()` 调用）。`settings.gradle.kts` 只有 `:app` 与 `:billkit`，**没有** `:xposed-module`，任何 `./gradlew :xposed-module:assembleRelease` 都会失败。构建产物 `dist/AutoBook-0.6.2-{debug|release}.apk`（实测 53MB / 44MB，versionCode 21）。0.6.2 **没有任何真机/模拟器证据**，LSP 是否真的生效未验证。完整细节见 `docs/PROGRESS.md`。


> 2026-10-22 v0.5.9（versionCode 18）dock 调整补遗（待验收，优先于下文历史描述）：Android 13+ 保持 68dp 胶囊、左右50dp、底距导航栏16dp；外阴影改为独立软件位图缓存，18dp 绘制边距，CLEAR 仅作用缓存位图。图标/点击区域共用等分网格，滑块以对应格中心向四周外扩3dp（外缘留白约3dp），标签12sp，选中块中性灰、不随系统强调色变化，动画收敛；Tab 声明选中语义，重复点击不震动。MainActivity 以稳定 ComposeView ID + SaveableStateHolder 保存各 Tab 状态并恢复当前 Tab。当前实际外观页没有玻璃强度滑块，折射/色散/模糊固定；下文 v0.5.5/0.5.8 的强度、挤压和双网格说明为历史。计划与验证证据见 openspec/changes/refine-floating-dock/。


> 0.6.0（versionCode 19）本次补齐（待验收）：D6用RootPollPolicy明确正常/空闲/息屏节拍；三采集链路传递真实截断，成功/失败/节流/去重诊断均显示。新增REFUND，退款独立列示，结余=收入-原支出+退款，不自动改历史INCOME；退款金额与到账时间优先，未完成/失败/取消不入账。iCost当前三类型模板不支持退款，范围含退款时明确阻止并提示，不导错账。明细支持手动新增/点击编辑，编辑保持id/createdAt等，新增独立身份；左滑删除每笔独立5秒撤销。Room仍v2（仅新增DAO方法，type本来为字符串）。版本真实更新，APK放dist，核查证据见openspec/changes/complete-core-ledger-workflows/。


> 0.6.1（20）模式选择补遗（优先于历史双采集/F6 OCR描述，待验收）：首次明确选择ROOT/ACCESSIBILITY保存capture_mode；Settings.captureMode与enabled独立，root_mode仅兼容旧备份/迁移。CaptureModeManager统一发布StateFlow及起停，App/boot/service重建均检查模式+总开关；ROOT忽略所有无障碍事件，ACCESSIBILITY不调用su且无Root截图OCR。旧root_mode=true迁移ROOT，系统可靠配置旧无障碍时迁移ACCESSIBILITY，否则选择页。MainActivity首次隐藏dock，选择后显示。设置模式/状态/权限和首页按所选模式引导，Root拒绝后停服务保留选择，明确重试才再申请。通知「停止」暂停enabled不清mode；备份恢复不覆盖已有明确mode。APK在dist/AutoBook-0.6.1-{debug|release}.apk，实际0.6.1/20。


> 0.6.2（21）无障碍增强与内置LSP模块（**代码已实现，真机未验证，待验收**）：无障碍服务按事件类型分级去抖（`TYPE_WINDOW_STATE_CHANGED` 120ms 快速响应付款成功页、`TYPE_WINDOW_CONTENT_CHANGED` 600ms 过滤滚动，见 `capture/BillAccessibilityService.kt:29-31` 与 `:95-96`）。**LSP 增强功能内置到主 App**（无需独立模块 APK）：在 LSPosed 中激活主 App 即可启用，实际 Hook 4 处——`AccessibilityManagerService.unbindService(AccessibilityService)`、`AccessibilityManagerService.removeServiceLocked(AccessibilityServiceConnection,int)`、`ActivityManagerService.killApplication(String,int,String)` 及其 `(String,int,int,String)` 重载；模块只处理 `packageName == "android"`，命中 `com.example.autobook` 时置 `param.result = null`。
> **核查修正（2026-10-22，逐行对照代码）**：① 原文所说 Hook `PowerManagerService`「强制豁免电池优化」**不存在**（全工程 grep `PowerManager` 只有 `AccessibilityEnhancer.kt:105` 与 `RootWatcher.kt:128` 的普通 API 调用），也没有 Hook `Context.getSharedPreferences`；② `AccessibilityGuardian`（30s 检查）与 `AccessibilityEnhancer` 是**未接线死代码**——Manifest 未注册 `<service>`、全工程无 `AccessibilityGuardian.start(...)`、`AccessibilityEnhancer.` 无任何外部调用点；③ 所以"守护服务""厂商优化"尚未接入运行时；④ `XposedModuleDetector` 的 SharedPreferences 标记无写入方，`SystemProperties.set` 在普通应用权限下几乎必然抛异常被 catch，`getModuleVersion()` 查的包 `com.example.autobook.xposed` 并不存在，激活状态只剩"堆栈跟踪"一路可信；⑤ `settings.gradle.kts` 无 `:xposed-module`，三份 LSP 文档里的 `./gradlew :xposed-module:assembleRelease` 与 `build-xposed-module.sh:35` 必然失败；⑥ Manifest `xposedminversion` 是 **93**（旧文档写 82）。
> 版本：0.6.2/21。单个 APK：`dist/AutoBook-0.6.2-{debug|release}.apk`（实测 debug 53MB / release 44MB，含 `assets/xposed_init`，release dex 中入口类命中 6 次）。**0.6.2 无任何真机/模拟器验证证据。**完整核查记录见 `docs/PROGRESS.md`。


> **0.6.3（22）死代码清理 + 无障碍保活接通**（2026-10-22，**代码已改，真机仍未验证，待验收**）：上一轮核查发现的 `AccessibilityGuardian` / `AccessibilityEnhancer` 已不再是死代码——
> ① `AndroidManifest.xml` 注册 `<service android:name=".capture.AccessibilityGuardian" android:exported="false" android:foregroundServiceType="specialUse">` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限。
> ② `CaptureModeManager.reconcile()` 末尾按 `next.accessibilityActive` 起停 `AccessibilityGuardian.ensureRunning/stop`——这是 `App.onCreate` / `BootReceiver` / 设置页开关 / prefs 监听唯一必经路径；`BillAccessibilityService.onServiceConnected()` 再补一次幂等 `ensureRunning` 兜底。
> ③ 新增 `capture/GuardianPolicy.kt`（纯逻辑、可单测）修掉旧缺陷：旧 `lastCheckEnabled` 初值 `false`，服务启动时无障碍已关**永不报警**；现改为累积连续异常次数，首检即生效。并把状态分三档 `Health{OK, DISABLED, UNBOUND}`——`UNBOUND` 专门对付"设置里打着勾但服务已被厂商解绑"的假开启，容忍 4 次（约 2 分钟）才提醒，`DISABLED` 是 2 次（约 60s）。
> ④ `AccessibilityEnhancer` 重写为只保留实测项，删掉 `autoStartEnabled = null`、`notificationEnabled = true // TODO`、`attemptAutoRepair()`（主线程 `Thread.sleep(500)` 后 `return false`）、`logStatus()`、`guideUserToOptimize()`。
> ⑤ `XposedModuleDetector` 重写为三档 `HookState{NOT_DETECTED, FRAMEWORK_PRESENT, MODULE_CONFIRMED}`，删掉永假的 SharedPreferences 路、查不存在包 `com.example.autobook.xposed` 的 `getModuleVersion()`、5 条含"防止低内存回收/自动恢复服务/优先级提升"的 `getEnhancedFeatures()`。
> ⑥ 权限页新增「无障碍保活」组（守护服务状态 / 电池优化豁免 / 厂商自启动引导），LSP 文案改为三档且给出局限说明。`build-xposed-module.sh`（删掉必失败的 `:xposed-module:assembleRelease`）、`build-all.sh`（版本号改从 gradle 读 + `aapt2` 核对）、`check-lsp-status.sh`（`adb shell unzip` 改 `adb pull`）三脚本同步修好。
> 验证：**167 单测 / 0 失败 / 18 类**（`--rerun-tasks`，`BUILD SUCCESSFUL in 58s`），debug+release 构建成功，产物 `dist/AutoBook-0.6.3-{debug|release}.apk`（`versionCode=22 / versionName=0.6.3`，release 证书 `bedb36b7…8a5ed6` 与前版一致）。证据 `runs/20261022-155747-guardian-hardening/`（`outputs/` 故意为空——**没有任何真机/模拟器证据**）。
> **保活的能力边界（别过度承诺）**：能做的只有"掉线被发现并提醒用户"（30s 巡检 + 通知），以及引导用户手动做电池优化豁免/厂商自启动。**做不到**：低内存 LMK 不被杀、被杀后自动重启（`onDestroy` 不重启服务）、提高进程优先级。**没接也不打算接** `PowerManagerService` Hook。


> **0.6.4（23）桌面图标彻底重建**（2026-10-22，**用户真机确认图标已换、其余未验证，待验收**）：用户实测「图标怎么还没更新」，核查后确认根因**不是缓存**——`mipmap-anydpi-v26/ic_launcher.xml` 只渲染 `@color/ic_launcher_background` + `@mipmap/ic_launcher_foreground` 两层（API 26+ 桌面走这里，`mipmap-*/ic_launcher.png` 只是 API 26 以下的 legacy 兜底），而旧的 `generate-icons.sh` / `regenerate-icons.sh` **只写 legacy 那两个 PNG，从不生成 `ic_launcher_foreground.png`，也不改 `colors.xml`**——所以 2026-10-22 13:48 的新图只进了 legacy 层，前景层一直是 10-21 的旧图。做法：删掉全部 **15 个**旧 PNG（5 档 × `ic_launcher`/`ic_launcher_round`/`ic_launcher_foreground`），重写 `generate-icons.sh` 一次生成三样（legacy 48/72/96/144/192 不透明；自适应前景 108/162/216/324/432，`-fuzz 5% -trim` 去白边 → 白底转透明 → 缩到 68dp 安全区 → 居中放到 108dp 画布；背景色 `#C0E1D7`→`#FFFFFF`），脚本末尾自带校验（尺寸或透明性不符即 `exit 1`；判透明必须用 `%[opaque]`，ImageMagick 7 的 `%[channels]` 返回 `srgb 3.0` 与 `RGB` 比必错）。`regenerate-icons.sh` 已删除。核对方式（不读图）：debug 包前景层五档字节 **8590/14634/20982/35825/53182**（旧包 3659/6591/10242/20653/34715）；release 资源被 R8 重命名，用 `aapt2 dump xmltree dist/AutoBook-0.6.4-release.apk --file res/BW.xml` 追 `<foreground>` → `res/as.png`（432x432、`opaque=False`、中心 `srgba(48,129,250,1)`），`aapt2 dump resources` 里 `color/ic_launcher_background` = `#ffffffff`。167 单测仍全绿（18 类），两包 `versionCode='23' versionName='0.6.4'`，release 证书与前版一致。**真机结论（2026-10-22 用户实测）**：装上 0.6.4 后桌面图标已变成新图（白底彩色圆环），不再需要清桌面缓存——这条已闭环。**仍未单独核对**：68dp 安全区在圆形/方形/圆角矩形三种裁切下是否都留够边距（用户未逐形状检查，我方无设备）。证据：`openspec/changes/rebuild-launcher-icons/`。

> **0.6.5（24）桌面图标增加留白**（2026-10-22，**待验收**）：沿用原图及白色背景，自适应前景内容从 68dp 缩至 54dp，108dp 画布不变；legacy 原图去白边后缩至画布 55% 居中。五档 × 三套 PNG 同次生成，脚本新增内容包围盒与中心偏差≤1px 校验。0.6.4 的用户确认仅证明当时新图已生效，**不代表 0.6.5 留白已获真机验收**。最新构建和证据见 `docs/PROGRESS.md`。

> **0.6.6（25）dock 视频参考优化（待验收）**：浅色 pageBg 改 `#F7F7F7`，深色不变。真玻璃折射高度16dp/偏移12dp/色散0.16/模糊5，浅/深中性色 tint 0.10/0.28；软件位图双层轻投影保持CLEAR隔离与18dp余量，只在尺寸变化分配。共享0.65dp中性边缘高光；API<33专用半透明胶囊取消GlassSurface重复阴影，宿主elevation5dp。滑块位置spring阻尼0.92/刚度480，距离驱动最大横伸8dp/纵压2dp，边界钳制，连续切换可打断；未新增拖动手势。保留68dp/50dp/16dp+inset、静止3dp留白、语义/触感与页面保存。构建及核验证据 `runs/20261022-180000-dock-reference/`，交付索引 `deliverables/0.6.6-dock-reference/`；无连接设备，视觉与快速反向观感待真机验收。

---

## 0. 一句话

一个**Android 自动记账 App**：读第三方支付/购物 App 的账单页面文本 → 规则识别 → 用户确认 → 存本地账本，并可导出 CSV 交给 iCost 记账 App 导入。

原始动机是逆向分析 iCost（`com.gostraight.smallAccountBook.fltandroid`）后**重写**一套同样的链路，但**不复用它的代码/资源**，也不碰它的私有数据。

---

## 1. 目录

```
autobook\
├─ AGENTS.md                 ← 本文件
├─ README.md                 功能/架构/合规/路线图（对外表述）
├─ CHANGELOG.md              版本更新日志
├─ docs\ICOST_IMPORT.md      CSV 导出到 iCost 的边界与模板说明
├─ docs\PROGRESS.md          **跨版本进度总账（0.5.8→0.6.2 事实核查）**，查"改到哪了"看它
├─ docs\ROOT_PLAN.md         root 路径的说明与取舍
├─ docs\LSP_MODULE.md        LSP 增强模块完整文档（技术原理）
├─ docs\LSP_QUICKSTART.md    LSP 模块快速开始（5分钟上手）
├─ docs\LSP_SCOPE_CONFIG.md  作用域配置（只勾 android）
├─ docs\ENHANCEMENT_SUMMARY.md  0.6.2 版本总结（无障碍优化+内置LSP）
├─ openspec\changes\         变更记录：refine-floating-dock / complete-core-ledger-workflows
│                            / choose-capture-mode / enhanced-accessibility-lsp（0.6.2）
│                            / guardian-keepalive-wiring（0.6.3）/ rebuild-launcher-icons（0.6.4）
├─ build.gradle.kts · settings.gradle.kts · gradle.properties · local.properties
├─ build-all.sh              统一构建脚本（构建到 dist/）
├─ dist\                     构建产物目录（当前 0.6.4）
│  ├─ AutoBook-0.6.4-debug.apk    (55MB / 54,732,457 B, 含内置 LSP + 无障碍守护 + 新图标)
│  ├─ AutoBook-0.6.4-release.apk  (46MB / 45,812,545 B, 同上)
│  ├─ AutoBook-0.6.3-{debug,release}.apk  (历史版本，图标实际没换成功)
│  └─ AutoBook-0.6.2-{debug,release}.apk  (历史版本)
├─ billkit\                  Android Library：识别链路（可单独发 AAR）
└─ app\                      Android App：平台胶水 + UI + 存储 + 导出 + LSP 模块
   ├─ src/main/java/.../xposed/  LSP 增强模块（内置）
   ├─ src/main/assets/xposed_init  Xposed 入口声明
   └─ libs\liquidglass-1.1.0.aar   ← 真玻璃库（本地 AAR，改过 minCompileSdk）
```

模块依赖：`app → billkit`（单向）。**billkit 不依赖 Context、不碰数据库、不联网**；OCR 实现在 app 侧，通过接口注入。**LSP 增强功能内置在 app 模块中**，仅 Hook 系统框架，不修改任何第三方 App。

### 1.1 `billkit`（com.example.billkit）

| 文件 | 职责 |
|---|---|
| `BillKit.kt` | 唯一入口：`install(config, sink, ocrEngine)` / `offer(UiSnapshot)` / `offerOcr(...)` / `updateConfig` / `isAccepted`。内部负责节流、去重、回调 sink、写 DebugBus |
| `BillKitConfig` | `acceptedPackages` / `ocrEnabled` / `minIntervalMs(1500)` / `dedupeWindowMs(90_000)` |
| `BillSink` | `fun interface { fun onDraft(bill: BillDraft) }`，宿主落点 |
| `Bill.kt` | `BillType{EXPENSE,INCOME,TRANSFER}`、`BillDraft`（含 `fingerprint`、`amountYuan`、`category/subCategory/account/note` 可空字段） |
| `UiSnapshot.kt` | 统一输入：`(packageName, activityClass, items, truncated, source{ACCESSIBILITY,ROOT_DUMP,ROOT_OCR})`，`joined()` |
| `TextCollector.kt` | 无障碍树 DFS → 有序文本；**跳过 `isEditable` 节点**；上限 200 条 / 2000 字；按 `(top/24, left)` 排序 |
| `PageClassifier.kt` | 页面规则表：9 家 App、`PageKind` 19 种（含 `GENERIC_BILL`）、`PageMatch(kind, strategy)`；返回 null 表示"不是账单页" |
| `AmountParser.kt` | 金额/时间/状态。`findAmount → AmountHit(cents, source, sign)`；`Status{SUCCESS,FAILED,CANCELLED,PENDING,UNKNOWN}` |
| `FieldExtractor.kt` | 标签→值邻接抽取（商户/支付方式/单号/对方），`orderIdTail` 只留后 6 位 |
| `OcrNormalizer.kt` | OCR 文本归一化：丢状态栏（仅前两行判断）/合并孤立 `¥`/推荐位截断/去噪 |
| `BillPipeline.kt` | 编排：分类 → 状态必须 SUCCESS → 金额 → 方向 → 时间 → 字段 → `Outcome.Draft/Skipped`；`Outcome.needsOcr` |
| `DebugBus.kt` | `StateFlow<State?>`，最近一次抓取（包名/activity/来源/结论/全文）；`annotateOutcome(suffix)` 追加投递结果 |
| `ocr/OcrEngine.kt` | 接口 `suspend recognize(Bitmap): List<String>` |
| `ocr/BitmapTools.kt` | `cropTopHalf`（状态栏以下 + 上半屏）、`downscale(1440)` |
| `ocr/OcrFallback.kt` | 截图 → 裁剪缩放 → 识别 → 归一化 → `UiSnapshot(ROOT_OCR)` |
| `root/RootShell.kt` | `su -c` 执行器（超时 + 字节流），`isGranted()` |
| `root/RootUiDumpSource.kt` | `foreground()`（dumpsys window）+ `dump()`（uiautomator dump → 解析 XML → 有序文本） |
| `root/RootScreenshot.kt` | `screencap -p` → Bitmap（二进制管道，不落盘） |

### 1.2 `app`（com.example.autobook）

| 目录/文件 | 职责 |
|---|---|
| `App.kt` | **装配点**：`PendingStore.init` / `LedgerConfig.init` / `ConfirmDelivery.ensureChannel` / `BillKit.install(...)`；sink 里做 自动分类+备注模板+投递 |
| `data/Bill.kt` | Room：`BillEntity`(含 category/subCategory/account/note)、`BillDao`(observeRecent/recentOnce/range/distinctMethods/insert/delete/countRecentFingerprint)、`AppDb` **version=2 + MIGRATION_1_2** |
| `data/Settings.kt` | 识别开关、白名单、`autoConfirm`、`minIntervalMs`、`ocrFallback`、`toKitConfig()`；prefs 文件 `autobook` |
| `data/LedgerConfig.kt` | 分类/账户清单（`CategoryGroup(name, subs)`）、`noteTemplate`、旧版扁平清单自动迁移；prefs 文件 `ledger_config` |
| `data/CategorySuggester.kt` | 商户/备注 → 分类(含二级)，支付方式 → 账户；**只返回清单里存在的名字** |
| `data/NoteTemplate.kt` | 备注模板渲染：`{商户} {支付方式} {分类} {账户} {时间}`，清理悬空分隔符 |
| `data/BillWriter.kt` | 唯一落库入口（**落库前查重**：10 分钟内同指纹或同包+同金额+同单号尾号视为重复 + 从待确认队列移除）、`toDraft(entity)` |
| `data/MiniJson.kt` | 手写 JSON 编解码（备份用）。**不用 `org.json`**：在 JVM 单测里它是 android.jar 空桩，`put()` 返回 null |
| `data/Backup.kt` | 备份格式（`kind=AutoBookBackup` / `format=1` / 短键账单数组 + ledger + settings），纯函数、可单测 |
| `data/BackupStore.kt` | 备份落盘/恢复：`exportJson` / `restore(mode=MERGE|REPLACE)` / `applySettings` / `fileName` |
| `data/SampleExport.kt` | Debug 页样本导出：`splitLines` / `kotlinFixture`（转义 `$`、引号、反斜杠）/ `sampleFileName` / `toIdentifier` |
| `data/PendingStore.kt` | 待确认队列（SharedPreferences JSON，上限 20），`StateFlow<List<BillDraft>>`；prefs 文件 `pending_drafts` |
| `capture/BillAccessibilityService.kt` | 无障碍服务：过滤（开关/包名/事件 32|2048）→ **按事件分级去抖**（`TYPE_WINDOW_STATE_CHANGED` 120ms / `TYPE_WINDOW_CONTENT_CHANGED` 600ms，`:29-31` 定义、`:95-96` 选档）→ `TextCollector` → `BillKit.offer` |
| `capture/ConfirmDelivery.kt` | 投递：**统一弹底部半屏确认卡**，优先 `ConfirmOverlay`（悬浮窗直弹，绕开 BAL），没权限才退回 `ConfirmActivity`，再不行落队列+通知；返回 `overlay`/`confirm`/`confirm-bg`/`pending*` 进 DebugBus；通知渠道 `autobook_confirm` |
| `capture/ConfirmIntents.kt` | 草稿 → ConfirmActivity 的 extras（唯一构造点） |
| `overlay/ConfirmOverlay.kt` | **确认卡的悬浮窗宿主**：`TYPE_APPLICATION_OVERLAY` + `ComposeView`，自己实现 `LifecycleOwner`/`SavedStateRegistryOwner`（用 `setViewTreeLifecycleOwner` / `setViewTreeSavedStateRegistryOwner` 扩展）。识别完直接浮在对方 App 上，无 Activity、无通知 |
| `overlay/OverlayAnchor.kt` | 1×1 全透明悬浮窗，仅为「退回 Activity 方案」时争取 BAL 豁免，3s 自动撤除（**不承载 UI**）。`hold()` 会在后台线程 post 到主线程再 `addView` |
| `ui/ConfirmSheet.kt` | 确认卡本体（压暗层 + 底部圆角卡 + 金额/分类/账户/备注 + 三个动作），Activity 与悬浮窗共用**同一份** Composable；**下滑跟手关闭**（只绑抓手区，滑过 1/4 屏高松手才关）+ 进场 spring |
| `export/CostCsv.kt` | iCost 官方 10 列模板导出（见 §7） |
| `export/CostShare.kt` | 四级投递 `VIEW_TARGET → SEND_TARGET → VIEW_CHOOSER → SEND_CHOOSER`；`shareBills` / `shareRecent` / `shareRange` |
| `export/ExportRange.kt` | 导出时间区间：本月 / 上月 / 最近 3 个月 / 全部，左闭右开；`span()` + `describe()` |
| `ocr/MlKitOcrEngine.kt` | `OcrEngine` 的 ML Kit 中文实现 |
| `root/RootWatcher.kt` | **单例**，root 轮询节拍（默认 1200ms）：前台包名 → 白名单 → dump → `BillKit.offer` → needsOcr 时截图送 `offerOcr`（3s 冷却）。暴露 `status: StateFlow<Status>` 给设置页看实时结果 |
| `root/RootForegroundService.kt` | 前台服务（`specialUse`）：常驻跑 `RootWatcher`，带一条低调的常驻通知 + 「停止」动作。**root 模式的主入口** |
| `root/BootReceiver.kt` | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 后，若 `Settings.rootMode` 为真则重新拉起前台服务 |
| `ui/design/Tokens.kt` | **设计系统底座**：`AbPalette`（浅/深两套语义色，含 `tabIndicator` 选中块灰）、`AbSpacing`/`AbRadius`/`AbSize`/`AbMotion`、`swatchFor(name)`、`Appearance`（`glassStrength` 0..1 玻璃强度，老 `reduce_transparency` 自动迁移）、`LocalAbPalette`/`LocalReduceTransparency`/`LocalGlassStrength` |
| `ui/design/Haptic.kt` | `AbHaptic`：`tick()`/`select()`/`confirm()`/`warn()` 四档触感（按 SDK 选 CONFIRM/REJECT/KEYBOARD_TAP…），`rememberAbHaptic()` |
| `ui/AmountInput.kt` | 确认卡金额解析/格式化：`parseAmountCents`（**`roundToLong` 不是 `toLong`**，否则 19.99 少 1 分）、`formatForInput`（`Locale.US`） |
| `ui/design/Typography.kt` | `AbType` 字号表（largeTitle 34 / navTitle 17 / headline 17 / body 17 / footnote 13 / caption1 12 / amountHero 44 …，金额一律 `.tabular()` 等宽数字）+ `AbType.sectionHeader` |
| `ui/design/Icons.kt` | `AbIcons`：手写 24×24 线性图标（Chevron/Check/Close/Home/List/Chart/Settings/Tag/Wallet/Export/Shield/Terminal…），不依赖 material-icons |
| `ui/design/Components.kt` | `GlassSurface`/`GlassIconButton`/`InsetGroup`/`InsetDivider`/`GroupHeader`/`GroupFooter`/`ListRow`/`AbSwitch`/`Segmented`/`PrimaryButton`/`SecondaryButton`/`IconBadge`/`EmptyState`/`CapsuleChip`/`AbTextField`/`InputRow` |
| `ui/design/Chrome.kt` | `LargeTitleBar`（滚动收缩的大标题）+ `LiquidTabBar`（**假玻璃兜底**，仅 API<33 用）+ `LiquidTabCells`（Tab 图标行，真假玻璃共用）+ `PageScaffold` + `SubPageBar`/`SubPageScaffold`（二级页导航栏）。**`PageScaffold` 不再画 Tab 栏、不画底部遮罩**；两个 Scaffold 的底部留白都是 `tabBarHeight + navInset + 24dp` |
| `ui/design/SystemAccent.kt` | **跟随系统强调色**：API 31+ 读 `android.R.color.system_accent1_{0,50,100,200,400,700}`（framework 档位是 0/10/50/100/200…900/1000，**不是** 10/20/40/80/90/95，用错名字 `getIdentifier` 返回 0 会静默失效），只换 accent/onAccent/tabIndicator/玻璃描边上沿，中性色不动 |
| `ui/design/LiquidGlassTabBar.kt` | **真液体玻璃 Tab 栏**（API 33+）：`LiquidGlassView.bind(内容 ComposeView)` + 透明图标 `ComposeView`；参数集中在构造函数（`refractionHeight` 34dp / `refractionOffset` 48dp / `dispersion` 0.78 / `blurRadius` 9 / `tintAlpha` 0.06）；`createLiquidGlassTabBar()` 在 API<33 返回 null |
| `ui/design/BillRow.kt` | `formatAmount(cents, signed, type)` / `timeOfDay` / `iconForCategory` / `BillListRow` / `SwipeDeleteBackground` |
| `ui/MainActivity.kt` | 壳：**手搭 `FrameLayout`**（不是 `setContent` 单根）＝ 内容 `ComposeView`（兼采样源）+ `LiquidGlassTabBar`（API 33+）/ `ComposeView` 装 `LiquidTabBar`（API<33，只占胶囊大小）；四个页面不再接收 `tabBar` 参数；`onCreate` 里 `enableEdgeToEdge` + `isNavigationBarContrastEnforced = false` |
| `ui/HomeScreen.kt` | 月度概览卡 + 最近 8 条 + 快捷入口；用 `InsetGroup` 包 `BillListRow` |
| `ui/BillsScreen.kt` | 按 `LocalDate` 分组的实色卡列表 + **左滑删除**（`SwipeToDismissBox`，滑过即删库） |
| `ui/StatsScreen.kt` | **12 个月可回看**（左右箭头翻月、点中间回本月）+ 支出/收入/结余 + 按分类/按账户条形占比 |
| `ui/SettingsScreen.kt` | **入口页 + 手写子页栈**（`SettingsPage` 枚举，10 页）：开关组 / 分类与账户 / 备注模板 / 支持的应用 / 权限 / Root 轮询 / 导出 / 备份与恢复 / 外观 / 诊断 |
| `ui/ConfirmActivity.kt` | **iOS 式底部 Sheet**：压暗层 + 圆角卡片 + 可编辑大金额 + `FlowRow` 分类/二级/账户 + 备注 + 三个动作 |
| `ui/LedgerConfigCard.kt` | 分类与账户管理**子页**（`LedgerConfigPage`）：一级列表 → 二级增删；账户 chips 网格 |
| `ui/SummaryCard.kt` | 实色月度概览卡（不再用渐变）：大金额 + 收入/结余/笔数三列 |
| `ui/Theme.kt` | `AutoBookTheme`：把 `AbPalette` 映射到 M3 ColorScheme（避免残留组件露出默认紫蓝）+ 提供 CompositionLocal |

---

## 2. 构建与测试

### 2.0 当前环境（Arch Linux，用户级安装，**无 sudo**）

工具链全部装在用户目录，不碰系统包管理器：

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `~/opt/jdk-17.0.20.1+1` | Temurin 17.0.20.1 |
| Gradle | `~/opt/gradle-8.14` | 8.14（wrapper 也指向腾讯云镜像，已可用） |
| Android SDK | `~/Android/Sdk` | platform-tools / android-35 / build-tools 35.0.0 |

每次开新 shell 前先 source 环境（已写在 `~/opt/autobook-env.sh`）：

```bash
source ~/opt/autobook-env.sh   # 设置 JAVA_HOME / ANDROID_HOME / PATH / GRADLE_USER_HOME
cd ~/Projects/AutoBook
./gradlew :app:testDebugUnitTest      # 当前167个单测（18 类）
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` 已改为 `sdk.dir=/home/caozl/Android/Sdk`（**换机器/换用户必须改**）
- **网络**：`services.gradle.org`、GitHub Releases 基本下不动（限速到 KB/s）。已把
  `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向
  `https://mirrors.cloud.tencent.com/gradle/gradle-8.14-bin.zip`；JDK 用清华 Adoptium 镜像。
  Maven/Google 仓库（`repo1.maven.org`、`dl.google.com`）直连正常，不需要额外镜像。
- 首次构建约 5 分钟（下载依赖），之后增量 30s 内。

**打包（release）**：签名信息在 `~/.autobook-keys/keystore.properties`（仓库外，不进版本库），
`app/build.gradle.kts` 自动读取；文件不存在时 release 就不签名，产出 `-unsigned.apk`。

```bash
./gradlew :app:assembleRelease     # 产物 app/build/outputs/apk/release/app-release.apk，R8 开启约 4~5 分钟
./gradlew :app:testDebugUnitTest   # 出包前务必先跑
```

密钥库：`~/.autobook-keys/autobook.jks`（alias `autobook`，口令 `autobook123`，自签 100 年）。
**这个 jks 和口令要自己备份**——换签名后旧版本无法覆盖安装，用户数据也会因签名不符无法迁移。
APK 装到 `dist/`（`AutoBook-<versionName>-<debug|release>.apk`）方便拷贝。

### 2.1 构建参数与约定

- AGP 8.7 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26 / targetSdk 35 / versionName 0.6.7（versionCode 26）
- **必须 JDK 17**（AGP 8.7 不吃更高的 JDK）
- 自动化程度：改完代码先跑单测，再 assemble；不要跳过单测（规则改动很容易误伤）

### 2.2 历史：原 Windows 机器（已迁移，仅留档）

```powershell
$env:JAVA_HOME="C:\Program Files\BellSoft\LibericaJDK-17"
& "<gradle 8.14>\bin\gradle.bat" -p <项目根> :app:testDebugUnitTest
& "<gradle 8.14>\bin\gradle.bat" -p <项目根> :app:assembleDebug
```

### 2.3 测试清单（167，18 类；2026-10-22 16:38 随 0.6.4 出包复跑 167/0/0/0）

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `AmountParserTest` | 11 | 千分位/标签邻接/**订单号不算金额**/`-3.50` 无 ¥ 带符号/`支出19元`/`收入 1,200.00`/正号=收入/日期解析/非法日期丢弃/状态优先级 |
| `PageClassifierTest` | 34 | 每 App 一屏真实文本 → 分类 + 端到端草稿（金额/方向/商户/支付方式/单号尾号）+ 反例（列表页/下单页/进行中/不像账单的陌生包）+ 支付宝真机 `支出19元` + OCR 文本走同一套规则 |
| `OcrNormalizerTest` | 6 | 状态栏、`¥` 拆行、推荐位截断、噪声、上限 |
| `CostCsvTest` | 12 | 表头/10 列/日期文本格式/金额 Locale.US/分类账户取值与兜底/备注优先级/转义/账户推断 |
| `CategorySuggesterTest` | 9 | 一级+二级匹配、账户匹配、收入规则、转账→其他、**清单里没有就不返回** |
| `NoteTemplateTest` | 6 | 占位符替换、空占位符清理、固定文案 |
| `AmountInputTest` | 6 | 确认卡改金额：**两位小数不少 1 分**（19.99/0.29/1.15/8.70）、千分位/带 ¥/空白、非法输入返 null 而不是 0、`Locale.US` 格式化、格式化↔解析往返一致 |
| `SampleExportTest` | 6 | Debug 页样本导出：去空行、`$`/引号/反斜杠转义、包名→合法标识符、文件名不含 `/`、空列表 |
| `MiniJsonTest` | 6 | 备份 JSON 编解码：对象/数组/嵌套往返、中文原样、整数解成 Long、坏 JSON 抛异常而不是返回半成品 |
| `BackupTest` | 10 | 备份格式：完整往返、空账单、**缺指纹按 `BillDraft` 规则补**、缺字段用默认值、坏元素跳过、非本 App / 版本太新 / 没有 bills 都明确报错 |
| `ExportRangeTest` | 7 | 导出区间左闭右开：本月/上月/跨年/最近 3 个月含当月/闰年 2 月/全部/描述文本 |
| `RefundWorkflowTest` | 11 | 退款独立成类型：成功退款优先于正号收入、用退款金额而非原付款、到账时间字段不算状态、订单关闭但退款完成仍识别、同金额退款指纹不同、统计口径保留原支出 |
| `CaptureLimitsTest` | 11 | 采集上限与诊断：200 条/2000 字边界、OCR 推荐位截断上报遗漏字数、BillKit 对 duplicate/throttled/skip 的注解 |
| `CaptureModePolicyTest` | 8 | 模式策略：新装未选择、旧 Root 配置优先于已绑定无障碍、明确选择覆盖旧标记、切换保留暂停且不双开、旧备份不能覆盖当前模式 |
| `BillEditingTest` | 4 | 编辑保持 id/createdAt/采集元数据、改成退款后指纹刷新、同时间同金额手动记账身份独立、重建后身份不丢 |
| `RootPollPolicyTest` | 2 | 正常/空闲/息屏三态独立节拍；空闲退避不会缩短用户设的更长间隔 |
| `GuardianPolicyTest` | 12 | 守护判定：`OK/DISABLED/UNBOUND` 三档、计数清零与累加、两档阈值（2 vs 4）、重复提醒窗口、计数封顶 10000、**"服务启动时无障碍已关也能报警"回归**、两种异常文案要能区分 |
| `AccessibilityEnhancerTest` | 6 | 厂商识别（含 `Build.BRAND` 为 null 时传空串兜底 OTHER）、`isFullyOptimized` 真值表、`optimizationGuide` 非空 |

测试夹具在 `app/src/test/java/com/example/autobook/Samples.kt`（每个 App 一屏真实页面文本）。

### 2.4 真机验证记录

| 版本 | 结果 | 说明 |
|---|---|---|
| 0.4.1 | ❌ | 支付宝账单详情被判成列表页（`skip:not-a-bill-page`）；识别成功后不弹卡，只发通知 → 点通知才跳到确认页 |
| 0.4.2 | ✅ 2026-10-21 | 同一屏（宿主包 `com.tentent.mm`，`东富路家味餐厅 / 支出19元 / 工商银行储蓄卡(5821)`）能出草稿；确认卡**直接浮在支付宝界面上**（`→ overlay`），不再经过通知栏 |
| 0.4.3 | ⏳ 待验证 | root 常驻（前台服务 + 开机自启）、侧滑返回逐级退、手动添加包名。验收：①打开 Root 轮询后退出 AutoBook，通知栏常驻通知在、切到支付宝详情页仍能弹卡；②重启手机后服务自动回来；③设置子页侧滑只回上一级；④在「支持的应用」手动加 `com.tentent.mm` 后能识别 |
| 0.4.4 | ⏳ 待验证 | 6 个 bug 修复 + root 省电。验收：①在确认卡把金额改成 `19.99` 记一笔，入库必须是 1999 分（不再 1998）；②同一笔账单页来回切两次、且中途杀过 App，只入库一条；③统计页有转账的月份能看到「转账 N 笔 · ¥X（不计入收支）」；④只开无障碍也能 OCR 兜底（需 root 截图能力）；⑤权限页有「通知」一行且能申请；⑥息屏一小时 AutoBook 耗电占比 < 1% |
| 0.5.0 | ⏳ 待验证 | 识别/导出/交互一批。验收：①陌生包结论行来源显示 `GENERIC_BILL`；②导出页选「上月」条数等于明细页上月条数；③备份 → 卸载重装 → 恢复，条数一致且不重复；④确认卡下滑关闭跟手、滑过约 1/4 屏高松手才关；⑤设置 → 外观拖玻璃强度到 0 立即变实色（不用重启）；⑥「记一笔」的震动比切 tab 更重 |
| 0.5.1 | ⏳ 待验证 | 底部 dock 修复。验收：①四个 tab 的胶囊下面没有任何实色横带，滚动内容从胶囊后穿过去；②滚到底部时内容淡出而不是硬切；③设置各子页滚到底，最后一行不被胶囊压住；④三键导航栏机型上导航栏区域不出现灰色 scrim |
| 0.5.2 | ⏳ 待验证 | 底部 dock 上方那条全宽渐变彻底删除。验收：①四个 tab 滚到底时，胶囊**正上方**不再出现横贯全宽的灰色带（浅色、深色都看一遍）；②滚到末尾的内容不再被任何半透明条压暗，只有胶囊本身的玻璃与投影；③设置各子页最后一行不被胶囊压住；④手势导航与三键导航都不出现系统灰 scrim |
| 0.5.3 | ⏳ 待验证 | 底部 dock 横带的真正根因修复。验收：①四个 tab 滚动时内容从胶囊后面穿过去，胶囊正下方直到屏幕底部都是内容/卡片而不是整片纯色；②首页与设置页滚到底，最后一行完整显示、不被胶囊压住；③浅色与深色各看一遍都无横贯全宽色带；④手势导航与三键导航都正常 |
| 0.5.5 | ⏳ 待验证 | 玻璃强度 / Tab 动画 / 胶囊阴影 / 系统主色。验收：①设置→外观拖玻璃强度滑块，胶囊通透程度连续变化（不是突变），拖到 0 变实色；②切 tab 时灰色选中块滑过去并挤压回弹（有"duang"），颜色是灰不是强调色；③选中图标比未选中大一圈且颜色更深；④胶囊四周有一圈柔和投影（上下左右都能看到，不是只有一条硬边）；⑤改系统壁纸/主题色后重进 App，主色（金额、开关、选中 tab、按钮）跟着变 |
| 0.5.4 | ⏳ 待验证 | 底部 Tab 胶囊换成真液体玻璃（Android 13+）。验收：①胶囊边缘能看出背后内容被掰弯（把白卡或彩色内容滚到胶囊后面最明显），滚动时折射是活的（不是一张静态图）；②边缘有轻微彩虹色散；③切 tab、按返回、点页面内容都正常（玻璃层不吃触摸，触摸穿透到下面的内容）；④Android 12 及以下机型上退回原来的半透明假玻璃，不能白屏、不能点不动；⑤设置 → 外观把玻璃强度拖到 0，胶囊变实色且不再渲染色散 |
| 0.5.8 | ⏳ 待验证 | Tab 选中块与图标网格解耦。验收：①选中色块铺满胶囊内缘、四块之间没有缝；②四个图标间距相等（模拟器实测 265/449/633/817，间距 184）且整排居中；③切 tab 时灰色滑块落在对应图标正下方（不再偏半格）；④色块上下到胶囊边距一致 |
| 0.6.0 | ⏳ 模拟器已过 / 真机待验证 | 退款独立成类型、手动新增与编辑、左滑删除 5 秒撤销、含退款的 CSV 导出阻止。证据 `runs/20261022-095145-core-ledger/verification.md`（141 单测）。Root 真机成功采集仍缺。 |
| 0.6.1 | ⏳ 模拟器已过 / 真机待验证 | 首次选择 Root/无障碍并持久记忆、设置可切换、两条采集路径互斥。证据 `runs/20261022-101537-capture-mode/verification.md`（149 单测）。Root 真机成功采集、开机恢复、功耗仍缺。 |
| 0.6.2 | ⏳ **完全待验证** | 分级去抖（`TYPE_WINDOW_STATE_CHANGED` 120ms / `TYPE_WINDOW_CONTENT_CHANGED` 600ms）、内置 LSP（4 处 Hook）、新图标。**只有单测（149）与产物元数据证据，没有任何真机或模拟器功能验证**；`runs/20261022-145102-doc-audit/` 是文档核查 run（`outputs/` 为空），不能当功能验收。验收要亲手做：①系统设置里手动关闭 AutoBook 的无障碍开关，日志是否出现 `阻止系统解绑无障碍服务`；②`am force-stop com.example.autobook` 后进程是否回来；③付款成功页弹卡是否明显快于 0.6.1；④桌面图标是否已是新图标（部分 ROM 需重启桌面）。 |
| 0.6.3 | ⏳ **完全待验证** | 死代码清理 + 无障碍保活接通：Guardian 已注册并随模式起停、Enhancer 只留实测项、`XposedModuleDetector` 三档化、权限页「无障碍保活」组、三个脚本修好。**证据只有 167 单测与产物元数据**（`runs/20261022-155747-guardian-hardening/`，`outputs/` 为空）。验收要亲手做：①无障碍模式下从最近任务里划掉 App，重进看守护服务是否自动恢复（`START_STICKY` 只在系统回收时生效，**手动强停不会**回来，这是能力边界不是 bug）；②在系统里手动关掉 AutoBook 的无障碍，约 60s 后应收到「无障碍服务异常」通知；③在设置里保持勾选的状态下用 `adb shell settings put secure enabled_accessibility_services ''` 模拟解绑，看是否报「设置里却显示开启」；④点「电池优化」能否跳到系统对话框；⑤点「厂商自启动」能否跳到对应页（没有就该退应用详情）。 |
| 0.6.4 | ✅ 图标已真机确认 / 其余待验证 | 桌面图标彻底重建：删掉 15 个旧 PNG，`generate-icons.sh` 一次生成 legacy + 自适应前景层 + 背景色（`#FFFFFF`）。**证据只有 167 单测与 APK 元数据**（新包前景层 8590/14634/20982/35825/53182 字节，旧包 3659/6591/10242/20653/34715；release `res/BW.xml` 的 foreground → `res/as.png`，432x432 `opaque=False`）。**①已通过**：2026-10-22 用户真机装上即看到白底彩色圆环的新图标，无需清缓存。②仍未逐形状核对：68dp 安全区在圆形/方形/圆角矩形三种裁切下边距够不够，没人量过。③若日后仍显示旧图，先 `unzip -l dist/AutoBook-0.6.4-debug.apk | grep ic_launcher_foreground` 确认是 53182 而不是 34715，再查桌面缓存。 |

0.4.2 的关键验收点：

1. 支付宝新版账单详情（「全部账单」返回入口 + 大金额 + `支出19元`）能识别，不再误判列表页；
2. 无障碍事件触发后确认卡**立即**出现，无通知、无 Activity 跳转、无画面闪一下；
3. 卡片上的金额/商户/分类/账户与账单一致，点「记一笔」落库、点「忽略」消失且不落库；
4. 没给悬浮窗权限时不能崩，应安静退化到通知兜底（`→ pending*`）。

〔核查修正 2026-10-22〕原文说"原始失败日志留在仓库根的 `真机验证0.4.1.md`"——**这个文件已经不存在了**
（`ls 真机验证0.4.1.md` 报 No such file）。0.4.1 那一屏的真实文本已经沉淀进
`app/src/test/java/com/example/autobook/Samples.kt` 与 `PageClassifierTest`（支付宝 `支出19元` 那条），
修规则请直接用那里的夹具当回归样本。

---

## 3. 运行时数据流（最重要的心智模型）

```
①采集                     ②识别（billkit）                ③命名               ④投递                      ⑤落点
无障碍服务  ─┐                                            CategorySuggester     ConfirmOverlay（悬浮窗直弹）
            ├→ UiSnapshot → BillKit.offer → BillPipeline → (分类/二级/账户) → NoteTemplate →     ↓ 没权限才降级
root 轮询  ─┘        ↑                    ↓                 ↓                     ConfirmActivity → 队列+通知
              OCR 兜底(needsOcr)     Outcome.Draft      BillDraft(含名字)             ↓
                                                                                 Room / CSV→iCost
```

关键约定：

1. **一个草稿只走一条落点，且只有一种 UI**：识别成功后永远弹同一张 `ConfirmSheet` 底部半屏确认卡，没有悬浮小卡、没有全屏页。宿主按可靠性依次尝试：`ConfirmOverlay`（有悬浮窗权限时**直接盖在对方 App 上**，不启动 Activity 所以不受 BAL 约束）→ `ConfirmActivity`（透明 Activity，后台先挂 `OverlayAnchor` 拿豁免）→ 待确认队列 + 通知。`ConfirmDelivery.deliver()` 返回 `overlay` / `confirm` / `confirm-bg` / `pending+notified` / `pending` / `pending-only`，字符串会被 `DebugBus.annotateOutcome(" → xxx")` 追加到结论后面。**排查"没弹窗"先看这行**：`→ overlay` 是正常路径；`pending*` = 既没悬浮窗权限也没 BAL 豁免，已落队列。
2. **节流与去重在 billkit 里**（`minIntervalMs` 1.5s、`dedupeWindowMs` 90s、fingerprint = 包名|金额|分钟|单号尾号 6 位）。宿主不再重复实现。
3. **分类/账户只允许出现在 `LedgerConfig` 清单里**。这是导出后 iCost 能正确归类的前提（见 §6 血泪教训 #5）。
4. 采集层与识别层**完全解耦**：无障碍 / uiautomator dump / OCR 三种来源产出同构的 `UiSnapshot`，规则与单测只写一份。

---

## 4. 规则体系（改这里最多）

### 4.1 `PageClassifier`（billkit）

19 个 `PageKind`：`WECHAT_BILL/PAY/TRANSFER/RECEIVE/LQT/RED_PACKET`、`ALIPAY_BILL/PAY/TRANSFER`、`UNIONPAY_BILL/PAY`、`JD_BILL/PAY`、`MEITUAN_BILL`、`TAOBAO_PAY`、`PDD_BILL`、`DOUYIN_BILL`、`DCEP_BILL`、`GENERIC_BILL`。

- 先过**反向信号**：列表页、下单页（`立即支付`/`提交订单`/`确认付款`）、系统界面（`控制中心`/`WLAN`/`深色模式`）→ 直接 null。
  - 列表信号分**强/弱两档**：`筛选`/`全部订单`/`待付款` 是强信号；`全部账单`/`交易记录` 是弱信号（支付宝新版详情页顶部就挂着「全部账单」返回入口）——**只要出现任一 `detailSignals`（支付时间/付款方式/交易单号/账单详情…），弱信号就不算列表页**。真机踩过：弱信号单独命中 → 整屏判成列表页 → `skip:not-a-bill-page`。
- **陌生包名兜底**：白名单由 `BillKit.isAccepted` 把关，分类器只对进来的页面做内容判断；包名不认识时走 `generic()`——必须同时有 `SUCCESS` 状态 + 详情字段 + 单号/时间字段 + 能抽到金额才认（避免用户误加一个 App 就乱记账）。用户这台机器上支付宝是 `com.tentent.mm`（分身/宿主包），靠的就是这条。
- 再按包名分流，每个 App 都要**强信号 + 至少一个字段**才命中；微信还额外分 零钱通/红包/收款/转账/支付页/账单详情。
- 反例样本在 `PageClassifierTest` 里（`wechatListPage`、`orderConfirmPage`、`pendingPay`）。

### 4.2 `AmountParser`

优先级：**标签邻接 > 带 ¥ > 全屏唯一裸金额**。
- 标签集：`实付[款项]?|实际支付|应付[款项]?|合计|总计|支付金额|付款金额|消费金额|订单金额|账单金额|支出|收入|金额`
- **`支出`/`收入` 既是标签也是方向**：`支出19元` 这种「标签+数字粘在一起、没有币种符号」的写法（支付宝新版账单详情）由 `signOfLabel()` 给出 -1/+1
- 排除行：`原价|订单编?号|交易单号|转账单号|商户单号|流水号|积分|卡号|尾号|余额|可用|额度`
- 符号 `+/-/－/−` → `AmountHit.sign`，**sign 优先决定收入/支出**（微信交易详情就是 `-3.50`）
- 时间支持 `yyyy年M月d日 H:mm[:ss]` / `yyyy-M-d H:mm[:ss]` / `M-d H:mm` / `yyyy年M月d日`

### 4.3 `FieldExtractor`

`标签：值` 必须**紧跟分隔符**（`：: >》` 或空格）才算值；否则 `收款方备注` 会被 `收款方` 吃掉（血泪教训 #4）。
换行取下一行时，下一行不能又是标签。商户有三层兜底，顺序固定：`merchantFromRemark()`（从备注里抠 `给XXX / 向XXX`）→ `counterParty` → `merchantAboveAmount()`（支付宝把商户名单独放在大金额上方一行，`东富路家味餐厅 / 支出19元`）。

### 4.4 `OcrNormalizer`

状态栏只在**前两行**判断（否则账单里的 `09:50` 会被删）、孤立 `¥` 与下一行数字合并、`相关推荐|猜你喜欢|…` 之后截断、纯符号行丢弃、连续重复去重、200 条/2000 字上限。

### 4.5 `CategorySuggester` + `NoteTemplate`

规则集中在 `expenseRules/incomeRules`（正则 → 一级 + 二级），命中后**必须在用户清单里存在**才采用，二级也要在该一级的 subs 里。账户先按清单名包含匹配，再用内置同义词表推导。

---

## 5. 存储

| 介质 | 位置/键 | 内容 |
|---|---|---|
| Room `autobook.db` v2 | `bills` 表 | id/priceCents/type/timeMillis/merchant/method/orderTail/sourcePkg/strategy/fingerprint/createdAt/category/subCategory/account/note |
| SharedPreferences `autobook` | `enabled` `auto_confirm` `min_interval_ms` `whitelist` `ocr_fallback` | 识别开关 |
| SharedPreferences `ledger_config` | `expense_groups` `income_groups` `accounts` `note_template` | 清单与备注模板（JSON） |
| SharedPreferences `pending_drafts` | `items` | 待确认队列（上限 20） |
| `cacheDir/share/*.csv` | — | 导出给 iCost 的临时文件（FileProvider `${applicationId}.share`） |
| SAF 文档（用户选位置） | `autobook-backup-<时间戳>.json` | **JSON 备份**：账单 + 分类/账户清单 + 备注模板 + 白名单/间隔/OCR/root 开关。恢复分 `MERGE`（按指纹去重）与 `REPLACE`（先清空，二次确认） |
| `filesDir/samples/*.txt` | — | Debug 页「保存样本到文件」：一屏页面文本，可直接贴进 `Samples.kt` |

**迁移规则**：改 `BillEntity` 必须同时 `version++` 并写 `MIGRATION_x_y`（参考 `MIGRATION_1_2`），**不要**用 `fallbackToDestructiveMigration`（会丢用户账单）。

---

## 6. 血泪教训（真实踩过的坑，改代码前必读）

1. **后台启动 Activity 会被拦**，而且**豁免并不可靠**。Android 10+ 的 BAL 限制：无障碍服务绑定时理论上豁免，实测用户设备上仍被拦；`OverlayAnchor` 挂 1×1 悬浮窗换豁免也只是"有时灵"。真正的解法是**根本不启动 Activity**：有 SYSTEM_ALERT_WINDOW 时用 `ConfirmOverlay` 把确认卡直接画成 `TYPE_APPLICATION_OVERLAY` 窗口（顺带修过两个坑：`addView` 必须在主线程、`ComposeView` 在非 Activity 窗口里要自己 `setViewTreeLifecycleOwner` / `setViewTreeSavedStateRegistryOwner`）。Activity + 通知只是没有悬浮窗权限时的降级路径。
2. **`image/*` 写在 KDoc 块注释里会把 Kotlin 注释搞坏**（Kotlin 支持嵌套块注释，`/*` 提前开启嵌套导致 "Unclosed comment"）。写文档时用"图片"代替。
3. **Android 11+ 包可见性**：没在 Manifest 声明 `<queries><package …/></queries>` 时，`getPackageInfo()` 抛 `NameNotFound`、"装了没装 iCost"永远 false、定向分享失败。已在 `app/src/main/AndroidManifest.xml` 声明 iCost 包名 + `text/csv` 的 SEND intent。
4. **`收款方备注` 不能当 `收款方：备注`**。前缀标签必须校验后面紧跟分隔符，否则商户名会变成"备注"。
5. **CSV 里的分类/账户必须是 iCost 里真实存在的名字**。名字对不上 → iCost 归到"未分类/未知账户" → **记录不进收支日历**（用户反馈的原始问题）。所以才有 `LedgerConfig` 清单 + `CategorySuggester` 只从清单里选。
6. **微信交易详情的金额是 `-3.50`，没有 `¥`**。正则必须允许可选正负号且不依赖币种符号。
7. **Compose 里 `TopAppBar` 需要 `@OptIn(ExperimentalMaterial3Api::class)`**；`HorizontalDivider` 是 material3 1.3 的名字（不是 `Divider`）。
8. **jadx 输出是 UTF-8**，PowerShell 读取必须 `-Encoding UTF8`，否则中文全是乱码（分析 iCost 时踩过）。

---

## 7. iCost 互操作（边界 + 格式）

**只做**：按官方模板导出 CSV，通过 `ACTION_VIEW/ACTION_SEND` 交给 iCost 公开的 `ShareReceiveActivity`，由它自己弹导入预览让用户确认。

**不做**（明确拒绝过，别擅自实现）：写它的 Hive/SQLite、读它的私有数据、Hook 它的 Flutter/Dart 层、自动点它的导入按钮、绕过它的会员/导入确认。

模板（`icost_template.xlsx`，sheet `icost_template`，固定 10 列）：

| 列 | 表头 | 我们写什么 |
|---|---|---|
| A | 日期 | `yyyy年MM月dd日 HH:mm:ss`（**文本**，不是 Excel 序列） |
| B | 类型 | 支出 / 收入 / 转账 |
| C | 金额 | 正数，`%.2f`，`Locale.US` |
| D | 一级分类 | `bill.category` → 兜底 `Profile.fallbackCategory` |
| E | 二级分类 | `bill.subCategory` |
| F | 账户1 | `bill.account` → 兜底从支付方式推断 |
| G | 账户2 | 空（仅转账有意义，留给用户补） |
| H | 备注 | `bill.note ?: merchant ?: strategy` |
| I | 货币 | `CNY` |
| J | 标签 | 空（`#标签1#标签2` 格式） |

细节见 `docs/ICOST_IMPORT.md`。

---

## 8. root 路径（v0.4.3 起是**主力**采集方式）

- **常驻**：`RootForegroundService`（`foregroundServiceType="specialUse"`）里跑 `RootWatcher` 单例。
  用户在「设置 → Root 轮询」打开开关后才启动，开关状态存 `Settings.rootMode`；
  `App.onCreate` 和 `BootReceiver` 只在这个开关为真时才拉起服务。
- **为什么是 specialUse 不是 dataSync**：dataSync 在 Android 15 有 6 小时/天限制、且不允许从
  `BOOT_COMPLETED` 启动，常驻监听会被掐掉。specialUse 需要在 `<service>` 里写
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途（已写）。
- **停止用 `context.stopService()`**，不要发 ACTION_STOP 走 `startService`：Android 8+ 后台
  `startService` 直接抛 `IllegalStateException`。
- `RootShell.isGranted()` 只在用户点/进入设置时检测（避免每次启动弹授权）。
- 轮询间隔默认 1200ms；`uiautomator dump` 单次 200~500ms、`screencap` + OCR 再 150~400ms，**必须带冷却**（现在 3s）。
- **每轮只跑一次 `dumpsys window`**：`RootUiDumpSource.dump(fg)` 接收调用方已查好的前台，
  `dump()` 只是自己查一遍的便捷重载。`root/RootWatcher.tick()` 传 `fg`。
- **dump + cat 合成一条 `su` 命令**（`rm -f; uiautomator dump; cat`）：每次 `su` 要 fork 一个 shell
  （30~80ms），合成一条比分开两条省一次进程创建。命令里先 `rm -f` 是防止 dump 失败时 cat 到上一屏的旧 XML。
  解析仍走 `text`/`content-desc`/`bounds`，跳过 `EditText`；`<?xml` 开头校验失败即返回 null。
- ~~与无障碍**可以同时开**~~ 〔核查修正〕0.6.1 起已改为**二选一互斥**：用户首次进入必须选 ROOT 或
  ACCESSIBILITY，模式存 `capture_mode`；ROOT 模式忽略所有无障碍事件，ACCESSIBILITY 模式不调用 `su`、
  也不做 Root 截图 OCR（`CaptureModeManager` 统一发布 StateFlow 并起停）。上方"两条路同时开、
  靠指纹去重"是 0.6.0 及以前的行为。
- 只适合自用/自签；`su` 调用不可能过任何商店审核。见 `docs/ROOT_PLAN.md`。



---

## 9. 常见故障排查

| 现象 | 查看点 | 处理 |
|---|---|---|
| 抓到了但只收到通知 | 设置页"结论"末尾的 `→ xxx` | `→ pending*` = 没有悬浮窗权限（去权限页给「显示在其他应用上层」）+ Activity 路径也被拦，已落待确认队列；给完权限就是 `→ overlay` 直接弹卡 |
| `skip:not-a-bill-page` | 结论行 + "复制文本" | 规则没覆盖 → 把文本贴进 `Samples.kt` 补 `PageClassifier` |
| `skip:no-amount` | 同上 | 金额标签不在词表 / OCR 拆行 → 改 `AmountParser` |
| `skip:no-status` | 同上 | 新的成功态文案 → 加到 `successWords` |
| `skip:pending:` / `skip:duplicate` / `skip:throttled` | — | 正常行为 |
| 导入 iCost 后不进收支日历 | iCost「全部账单」里搜金额 | 有记录 = 分类/账户名不匹配（改清单）；没有 = 导入没落库（看 iCost 提示） |
| root 模式抓到了但不弹卡 | 设置页"悬浮窗：去授权" + 结论末尾 `→ pending*` | 去系统设置给「显示在其他应用上层」，或改用无障碍模式（有绑定豁免） |
| 结论末尾 `→ pending-no-notify` | 权限页「通知」一行为红色 | 0.4.4 起单独区分：不是 BAL 拦的，是**根本没给通知权限**，提醒发不出去。点该行进系统设置授权 |
| 结论里出现 `[截断]` | 结论行 `draft:19.0 ALIPAY_BILL [截断]` | 页面文本被 `TextCollector` 截到 200 条/2000 字上；识别不准时先怀疑这个，再改规则 |
| 息屏后不知道还在不在跑 | 设置 → Root 轮询 →「实时状态」显示「息屏休眠中」 | 0.4.4 起熄屏/锁屏睡 30s，属正常；解锁后自动恢复 |
| root 模式"设置页里能用，退出就停" | 设置 → Root 轮询，看「已处理页面」是否在涨 | 旧版本（≤0.4.2）的已知问题；0.4.3 起跑在前台服务里，通知栏应有常驻通知 |
| 通知栏常驻通知划不掉 | — | 设计如此（前台服务）；点通知里的「停止」或去设置页关开关 |
| 手动加的包名不识别 | 诊断页「结论」 | 包名要完全一致（含大小写）；加进来只代表允许采集，页面还得满足账单详情条件 |
| 侧滑返回直接退出 App | — | 0.4.3 起：子页 → 上级页 → 首页 → 退出；已在首页才退出 |
| 分类/账户 chip 空 | `LedgerConfig` 清单为空 | 恢复默认清单，或手动加 |

---

## 10. 待办 / 路线图

1. ~~**P1** 明细页支持点击编辑（改分类/账户/金额）~~（0.6.0 已做）；**分类筛选仍未做**
2. ~~**P1** 统计页加月份切换~~（0.5.0 已做：`StatsScreen` 12 个月可回看）；**环比仍未做**
3. **P2** 商户→分类的**本地学习**（用户改过一次就记住，写进 `LedgerConfig` 的规则表）
4. ~~P2 通知栏快捷入口~~（已由 `ConfirmOverlay` 直接弹卡取代；通知现在只是兜底）
5. **P2** 二级分类在统计里的展开
6. **P3** 账单搜索、CSV 导入（反向）
6.5 ~~root 轮询常驻~~（0.4.3 已做：`RootForegroundService` + `BootReceiver`）
6.6 ~~侧滑返回直接退出~~（0.4.3 已做：`MainActivity`/`SettingsScreen` 的 `BackHandler`）
6.7 ~~改金额少 1 分 / 重启后重复入库 / 统计页吞转账~~（0.4.4 已做：`ui/AmountInput.kt`、`BillWriter.save` 查重、`StatsScreen` 转账单列）
6.8 ~~无障碍路径没有 OCR 兜底 / 通知权限从未申请 / 截断无人知~~（0.4.4 已做）
6.9 ~~root 息屏空转~~（0.4.4 已做：`RootWatcher` 息屏睡 30s + 空闲退避到 3s）
6.10 ~~陌生包冒充支付宝~~（0.5.0 已做：`PageKind.GENERIC_BILL`）
6.11 ~~识别样本没法留存~~（0.5.0 已做：Debug 页「复制为 Kotlin 夹具」/「保存样本到文件」）
6.12 ~~导出只能最近 50 条、换机就丢~~（0.5.0 已做：`ExportRange` 四档区间 + JSON 备份/恢复）
6.13 ~~确认卡弹出/关闭生硬~~（0.5.0 已做：下滑跟手 + spring 进场 + 触感分级）
6.14 ~~玻璃只有一层色~~（0.5.0 已做：镜面高光 + 底边暗线 + scroll edge 遮罩 + 强度滑块）
6.15 ~~dock 上方那条全宽横带~~（0.5.2 已做：删掉 `MainActivity` 里 24dp `fillMaxWidth` 渐变；模拟器逐像素复测无横带）
6.16 ~~底部 dock 像「不悬浮」、内容被挡~~（0.5.3 已做：改掉 `HomeScreen` / `SettingsScreen` ROOT 页用 `Modifier.padding(padding)` 削视口的写法，改走 `contentPadding`）
6.17 ~~玻璃是假的（半透明底色 + 高光 + 描边）~~（0.5.4 已做：接 AndroidLiquidGlassView，`RuntimeShader` 真折射 + 色散；API<33 回退假玻璃）

6.18 ~~玻璃强度滑块没用 / Tab 选中块生硬 / 胶囊没有阴影 / 主色写死~~（0.5.5 已做：强度连续插值、滑动挤压回弹的灰色选中块、容器 14dp `shadowPad` 里画的胶囊外圈投影、`SystemAccent` 跟随 Material You）
7. **P3** 换 `:billkit` 为独立 AAR 发布

---

## 11. 换机器 / 迁移目录后的恢复步骤

1. 复制整个 `autobook\`（可以不带 `build\`、`.gradle\`、`.kotlin\`）。
2. 改 `local.properties`：`sdk.dir=<新机器的 Android SDK 路径>`。
3. 设 `JAVA_HOME` 指向 **JDK 17**（AGP 8.7 不吃 JDK 26）。
4. 已有 gradle wrapper（`./gradlew`），无需再生成；Linux 上先 `source ~/opt/autobook-env.sh`。
5. `:app:testDebugUnitTest` 应该 167 个全绿（18 个测试类）；`:app:assembleDebug` 产出 `app\build\outputs\apk\debug\app-debug.apk`。
   注意 `app/libs/liquidglass-1.1.0.aar` 是**二进制文件**，复制工程时别落下；丢了会编译报找不到 `com.qmdeve.liquidglass.*`。
6. 真机首次安装后需要手动做三件事：开无障碍服务、**给悬浮窗权限（不给就只能发通知，不能直弹确认卡）**、给通知权限；然后在设置页把分类/账户清单改成与 iCost 一致的名字。

---

## 12. 上下文补遗（关于 iCost 逆向）

分析产物在 `C:\tmp\apk_re`（**不是**本工程的一部分）：

- `REPORT.md` — iCost 整体逆向报告
- `REPORT_自动记账.md` — 它的智能记账链路详解（无障碍伪装、21 种页面模式、PP-OCRv6 tiny、AI 兜底通道、账户词典）
- `jadx/` — 反编译源码；`libapp_strings.txt` — libapp.so 字符串；`aot.json` — REA 的 Dart AOT 检查结果

这些只作为**设计参考**，代码是重写的。任何"直接搬它代码/资源"的行为都不要做。


## APK 交付补充（2026-10-22 用户要求）

每次功能或UI交付按实际变更更新版本（小修递增patch；大改按实际调整minor/major），同时递增versionCode。安装包必须放`dist/AutoBook-<versionName>-<debug|release>.apk`，用aapt核对文件名与APK实际版本一致。当前0.6.5（24），留白调整待验收。

0.6.0验证：`runs/20261022-095145-core-ledger/verification.md`。141单测通过、debug及签名release构建成功；模拟器新增/编辑/撤销/退款统计/导出阻止/无效金额已验证，root实机仍待验证。dist交付，用户待验收。

0.6.1验证：149单测通过，debug/签名release构建成功，模拟器首次/重开/升级/暂停/切换/Root拒绝均已检查；证据`runs/20261022-101537-capture-mode/verification.md`。Root真机成功采集仍待验证。用户待验收。

0.6.4验证（2026-10-22 图标重建轮）：**167 单测通过 / 0 失败 / 18 类**（随出包复跑，`BUILD SUCCESSFUL in 36s`，146 tasks: 28 executed / 1 from cache / 117 up-to-date），debug 与签名 release 均构建成功；`dist/AutoBook-0.6.4-{debug|release}.apk`（54,732,457 B / 45,812,545 B）的 `aapt2 dump badging` 均为 23/0.6.4，release 证书 `bedb36b7…8a5ed6` 与前版一致、debug 为 `08b0f9a0…566cd7`；debug 包 17 个 `ic_launcher*` 条目且前景层五档字节与源文件一致，release 包 `res/BW.xml` 的 foreground 指向 `res/as.png`（432x432、`opaque=False`、中心 `srgba(48,129,250,1)`），`color/ic_launcher_background` = `#ffffffff`。**真机结论（2026-10-22 用户实测）：桌面图标已刷新为新图（白底彩色圆环），0.6.2/0.6.3 的「图标没更新」问题已闭环**；仅 68dp 安全区在三种裁切下的边距未逐形状核对。证据 `runs/20261022-163800-icon-rebuild/`（`--rerun-tasks` 强制重跑 `BUILD SUCCESSFUL in 54s`，18 个 XML 归档；`outputs/` 故意为空——**没有真机/模拟器**）。变更记录 `openspec/changes/rebuild-launcher-icons/`。用户待验收。

0.6.3验证（2026-10-22 保活接通轮）：**167 单测通过 / 0 失败 / 18 类**（`--rerun-tasks` 强制重跑，`BUILD SUCCESSFUL in 58s`，39 tasks executed），debug 与签名 release 均构建成功；`dist/AutoBook-0.6.3-{debug|release}.apk` 的 `aapt2 dump badging` 均为 22/0.6.3，release 证书 SHA-256 `bedb36b7…8a5ed6` 与前版一致，debug 为 `08b0f9a0…566cd7`；release dex 里 `GuardianPolicy`/`UNBOUND`/`accessibilityChecked`/`KeepAliveStatus` 都在，`assets/xposed_init` 内容不变；Manifest 已含 `AccessibilityGuardian` 的 `<service>`（`exported=false`、`foregroundServiceType=specialUse`）与 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。证据 `runs/20261022-155747-guardian-hardening/`（`outputs/` 为空——**没有真机/模拟器**）。守护服务常驻、掉线通知、"假开启"识别、权限页读数全部未验证。用户待验收。

0.6.2验证（2026-10-22 文档核查轮补充）：**149 单测通过**（`./gradlew :app:testDebugUnitTest`，因 `~/.gradle` 只读，需在同一条命令里 `cp -a ~/.gradle/{caches,wrapper,daemon,gradle.properties} /tmp/gh2/` + `GRADLE_USER_HOME=/tmp/gh2` + `--offline`）；debug/release 双包 `aapt2 dump badging` 均为 21/0.6.2，release 证书 SHA-256 `bedb36b7…8a5ed6` 与前版一致，`assets/xposed_init` 48 字节。**功能层面零证据**：`deliverables/` 无 0.6.2 快照，`runs/20261022-145102-doc-audit/` 只有 `logs/` 与 `test-results/`、`outputs/` 为空（它是元数据核查，不是功能验收），LSP 撤销拦截、120ms 手感、新图标刷新全部未验证。核查明细`docs/PROGRESS.md`，变更记录`openspec/changes/enhanced-accessibility-lsp/`。用户待验收。
