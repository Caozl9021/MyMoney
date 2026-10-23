# AutoBook 进度总账（0.5.8 → 0.6.6）

> 2026-10-23 新增规划：[plan1.0](PLAN_1.0.md) 已汇总本次确认卡、周/月统计、识别历史、账单操作及plan0.5结转；仅文档，新增功能未实施，规划待审阅。

> 2026-10-23 v0.6.8（27，待验收）：所有品牌入口更新为最新Auto.jpg，保留四彩边框及54dp/55%留白，独立单色负空间避免实心块；包括桌面/主题、通知、磁贴、shortcut、widget、确认卡。微信截图缺支付方式导致旧规则不命中，新增严格极简成功页分类；无障碍窗口120ms不再被内容事件推迟，内容最大等待1秒且根节点仅重试一次。221单测/24类全绿、双构建及26包内资源/版本/签名核查通过；API35确认卡/磁贴/widget目视通过。截图转录非节点dump，真实微信付款瞬时弹框仍待真机验证。证据 `/home/caozl/Projects/AutoBook/runs/20261023-092336-brand-jpg-wechat-release/outputs/verification.md`；包 `dist/AutoBook-0.6.8-{debug,release}.apk`。


> 2026-10-23 v0.6.7（26）新图及正式出包（待验收）：已将用户Auto.png更新到五档legacy/自适应及独立monochrome，保留白底、adaptive54dp/legacy55%留白；alpha≤1%透明噪点清理，原图未改。包含本地规则B0–B5/B7与U3–U10、60dp扁长dock。204单测/22类全绿，debug及签名release均成功；主代理独立核对0.6.7/26、新图可见RGB及全部alpha一致、release证书与0.6.6一致。产物dist/AutoBook-0.6.7-{debug,release}.apk，证据runs/20261023-085355-new-icon-release-0.6.7/。本次无在线设备，未验证新图在桌面实际刷新或release真机运行；此前API35预览证据仍有效但不等同新图验收。

> 2026-10-22 U系列预览补遗（待验收）：剩余U3–U10已实现，保留U1/U2当前轻透60dp dock及图标留白。新增外观主题/字号/降低动效、大字号与560dp布局、速度手势/页面及金额过渡、首页真实模式引导/待确认优先、完整月查询、monochrome/桌面快捷方式/识别磁贴/月度widget。204单测/22类全绿及debug构建通过；API35实际核查手动保存编辑取消、删除撤销、草稿重建保留、磁贴启停、widget添加点击、2.6字号/宽屏/深色及抓手分支。版本仍0.6.6/25，无release/dist。安全锁屏、完整TalkBack、低API及真机观感未实测；widget Doze跨日可延迟。证据 `runs/20261022-231606-u-series/outputs/verification.md`，用户验收未完成。

> 2026-10-22 本地规则系统预览补遗（待验收，优先于下方历史待做状态）：B0–B5及B7已实现；B3并入B7，B6/B8不在本轮。统一评分/独立分类账户来源、JSON覆盖、样本index.txt、近7天计数、餐段与手改立即学习/人工接受3次学习均已接入；设置→本地规则管理。成功人工入库才学，历史明确修改不累计频次。关闭学习同时暂停既有学习推荐。194单测/20类全绿，之后debug构建成功；版本保持0.6.6/25，无release/dist更新。证据 `runs/20261022-225837-local-rules-integration/`。模拟器已安装：本地规则布局、默认餐段保存、学习开关重启持久化、错误JSON经SAF导入保护均核查通过。真实支付确认/悬浮窗及备份设备往返待走查，用户验收仍未完成。

> 维护规则：每交付一个版本，在本文档对应章节补一段"已完成 / 已核查 / 未完成 / 与文档口径的差异"。
> 本文档只记录**可复核的事实**，每条结论后面能跟一个命令或一个文件行号。
> 最后更新：2026-10-22（**0.6.6：dock 视频参考优化，待验收**）。
>
> 阅读顺序：先看第一节"当前版本事实"，再看第九节"0.6.4 做了什么"、第八节"0.6.3 做了什么"。
> 第三~五节是 0.6.2 核查轮的历史记录，其中"未接线"的结论**已在 0.6.3 处理**，看的时候别当成现状。

---

## 一、当前版本事实（2026-10-22，0.6.6；待验收）

- 版本 `0.6.6 / 25`；浅色背景从 `#F2F2F7` 改中性 `#F7F7F7`，白色卡片与深色主题保持层次。
- dock 参考视频降低折射/色散/模糊至16dp/12dp/0.16/5，中性 tint 浅0.10/深0.28；双层软件缓存阴影与0.65dp共享中性细高光，API<33取消重复投影并把宿主elevation降5dp。
- 滑块连续弹簧位移，距离驱动最多横伸8dp/纵压2dp，边界钳制；文字图标独立。未新增拖动手势；保留Tab语义、重复点击无触感及页面状态保存。
- 单测强制重跑 **167/0失败/0错误/0跳过，18类，16s**。随后强制双构建成功（139任务，1m23s），两包均0.6.6/25，签名分别与0.6.5一致。详细核对见 run verification 与 apk-verification.json。
- 首次release增量Kotlin编译无法解析多个未改顶层函数，日志保留；随后 `--rerun-tasks` 强制双构建，详见 `logs/build-rerun.log`。
- 主代理沙箱外adb确认无连接设备。本轮未安装、未启动新模拟器，无新版浅深主题/连续点击/屏幕导航实测证据；视觉效果待用户验收。
- 证据：`runs/20261022-180000-dock-reference/`；交付：`deliverables/0.6.6-dock-reference/manifest.json`。无Git提交号、OpenSpec CLI不可用，按四文件结构和场景手工核查。

- dist/AutoBook-0.6.6-debug.apk：54,678,665 B；SHA-256 `3505d4bcb4708c58a701c3771ec6d1e01fe58236a1d94f5cb03ca2c4ca6c2797`。
- dist/AutoBook-0.6.6-release.apk：45,764,085 B；SHA-256 `d6707f4f4310c58e1fcbffccd18346ec272ce98d27ae3bddafc59d93c3701e7e`。

### 上一版本0.6.5事实（历史，待验收）

| 项 | 值 | 复核方式 |
|---|---|---|
| versionName / versionCode | `0.6.5` / `24` | `app/build.gradle.kts`；两个 APK 的 `aapt2 dump badging` |
| compileSdk / minSdk / targetSdk | 35 / 26 / 35 | Gradle 配置与 APK badging |
| 交付包 | `dist/AutoBook-0.6.5-debug.apk`（54,678,665 B）；`dist/AutoBook-0.6.5-release.apk`（45,764,081 B） | run `apk-verification.json` |
| debug sha256 | `c0df5c7e8d705fb6ecddbcbb4a0709604850e44858fc75ddb3d552e396993dc7` | `sha256sum` |
| release sha256 | `8e4271c7f8e83b95649fb8f21036c922258e7f1518304ccbfd98b94f1aa9ae35` | `sha256sum` |
| release 签名 SHA-256 | `bedb36b7527aa4759262d8b737d7031b57f1db191c68eab4d55829f0598a5ed6`（与0.6.4一致） | `apksigner verify --print-certs` |
| debug 签名 SHA-256 | `08b0f9a09430a9f8c121402fe5774dfc978e7e28b074b42390c2a358d0566cd7`（与0.6.4一致） | 同上 |
| 单测与构建 | 强制重跑 **167 / 0失败 / 0错误 / 0跳过，18类**（39s）；随后双构建成功（18s） | run logs 与18个XML |
| 图标 | 原图保留；adaptive 108dp画布内容最大边54dp，legacy原图trim后最大边55%；15个PNG校验通过 | `generate-icons.sh` 与 `logs/icon-generation.log` |
| APK内资源 | debug 15个PNG与源文件逐字节一致；两包adaptive五档alpha包围盒相同，xxxhdpi为 `210x216+111+108`；白底 `#ffffffff` | `apk-verification.json`、aapt2 resources及xmltree |
| 真机效果 | 0.6.5 留白和三种裁切边距 **未验证，待用户验收**；0.6.4用户仅确认旧版图标已刷新 | 不复用旧版确认作为新版验收 |
| 证据 | `runs/20261022-173414-icon-padding/`；`deliverables/0.6.5-icon-padding/manifest.json` | manifest、verification、logs |

以下环境说明及各旧版本章节保留历史记录。

**注**：本机 `~/.gradle` 位于只读文件系统，直接 `./gradlew` 会报
`java.io.FileNotFoundException: .../gradle-8.14-bin.zip.lck (只读文件系统)`。
本次复跑用的是同一命令内 `cp -a ~/.gradle/{caches,wrapper,daemon,gradle.properties} /tmp/gh2/` +
`export GRADLE_USER_HOME=/tmp/gh2` + `./gradlew --offline --rerun-tasks :app:testDebugUnitTest`。
**2026-10-22 16:12 补记**：首轮出包（15:56）后只改了一处**类注释**——`xposed/AutoBookXposedModule.kt` 的 KDoc 原写"1. 防止系统撤销无障碍权限 2. 防止厂商省电策略杀服务 3. 保持无障碍服务常驻"，后两条代码里没有，已改为真实的两件事 + 显式的"做不到"三条，其余零逻辑改动。为让 `dist/` 与源码严格对应，用 `GRADLE_USER_HOME=/tmp/gh5 ./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 重跑（`BUILD SUCCESSFUL in 2m 11s`、146 tasks: 19 executed / 127 up-to-date、单测仍 167/0/0/0），`dist/` 两个包已覆盖，上表字节数不变、sha256 为上表新值。
注意 `/tmp` 是 tmpfs，**拷贝必须与构建在同一次 shell 调用里**，否则下一次调用就没有 `/tmp/gh2`，
报 `Plugin [id: 'com.android.application', version: '8.7.0', apply: false] was not found`
（因为只拷了 wrapper 没拷 `caches` 里的插件解析缓存，且 `--offline` 不允许下载）。
另外 `| tail` 会把失败吞成 exit 0，务必 `set -o pipefail` 并取 `${PIPESTATUS[0]}`。

---

## 二、各版本进度

| 版本 | 主题 | 代码 | 单测 | 构建 | 真机/模拟器 | 用户验收 |
|---|---|---|---|---|---|---|
| 0.5.8 / 0.5.9(17-18) | 底部 dock 网格与阴影 | ✅ | ✅ | ✅ | 模拟器逐像素 | ⏳ 待验收 |
| 0.6.0 (19) | 退款、编辑、撤销、截断诊断 | ✅ | 141 ✅ | ✅ | API35 模拟器已验 | ⏳ 待验收 |
| 0.6.1 (20) | Root/无障碍模式二选一 | ✅ | 149 ✅ | ✅ | API35 模拟器已验 | ⏳ 待验收 |
| 0.6.2 (21) | 无障碍分级去抖 + 内置 LSP + 新图标 | ✅ | 149 ✅ | ✅ | ❌ 无任何真机/模拟器证据 | ⏳ 待验收 |
| 0.6.3 (22) | 清理死代码 + 无障碍保活接通 | ✅ | **167 ✅**（18 类） | ✅ | ❌ 无任何真机/模拟器证据 | ⏳ 待验收 |
| 0.6.4 (23) | 桌面图标彻底重建（自适应前景层此前从未更新） | ✅ | **167 ✅**（18 类） | ✅ | ✅ **图标已真机确认**（2026-10-22 用户实测；无截图）；68dp 三种裁切边距未核对 | ⏳ 待验收 |
| 0.6.6 (25) | dock轻透材质、连续滑块及中性浅色背景 | ✅ | **167 ✅**（18类） | ✅ | ❌ 无设备实测 | ⏳ 待验收 |
| 0.6.5 (24) | 图标缩小并增加四周白边 | ✅ | **167 ✅**（18类） | ✅ | ❌ 新版无设备验证 | ⏳ 待验收 |

现有 run 证据目录：`runs/20261022-092633-dock-review/`、`runs/20261022-093519-dock-indicator/`、
`runs/20261022-095145-core-ledger/`（141 例）、`runs/20261022-101537-capture-mode/`（149 例）、
`runs/20261022-145102-doc-audit/`（0.6.2 文档核查，149 例重跑，**只有元数据证据、无功能验证**）、
`runs/20261022-155747-guardian-hardening/`（0.6.3 保活接通，167 例 + 构建，**`outputs/` 为空、无功能验证**）、
`runs/20261022-163800-icon-rebuild/`（0.6.4 图标重建，167 例 `--rerun-tasks` 重跑 + 构建 + APK 图标字节核对，
**`outputs/` 同样为空、无功能验证**）。

**注意区分证据强度**：前面四个 run 都带 `outputs/` 里的模拟器截图与 UI XML（真实操作过），
`20261022-145102-doc-audit`、`20261022-155747-guardian-hardening`、`20261022-163800-icon-rebuild`
的 `outputs/` 都是空的——它们只证明了"单测能过、产物元数据对得上"。
**0.6.2 / 0.6.3 / 0.6.4 的功能验收仍然都没有。**

---

## 三、0.6.2 逐项状态（历史记录，其中"未接线"三项已在 0.6.3 处理）

### 3.1 无障碍分级去抖（已实现）

`app/src/main/java/com/example/autobook/capture/BillAccessibilityService.kt:29-31` 定义
`DEBOUNCE_MS = 600L`、`WINDOW_DEBOUNCE_MS = 120L`；同文件 `:95-96` 按事件类型分流：

- `TYPE_WINDOW_STATE_CHANGED → WINDOW_DEBOUNCE_MS`（120ms，付款成功页抢时间）
- `TYPE_WINDOW_CONTENT_CHANGED → DEBOUNCE_MS`（600ms，过滤滚动抖动）

这是 0.6.2 里**唯一有代码证据、且不影响既有行为**的改动。

### 3.2 内置 LSP 模块（代码在，效果未验证）

- `app/src/main/assets/xposed_init` → `com.example.autobook.xposed.AutoBookXposedModule`
- 入口类 `app/src/main/java/com/example/autobook/xposed/AutoBookXposedModule.kt`（208 行）实现
  `IXposedHookLoadPackage`，`handleLoadPackage` 仅在 `lpparam.packageName == "android"` 时 hook。
- 实际共 **4 处** `XposedHelpers.findAndHookMethod`：
  1. `AccessibilityManagerService.unbindService(AccessibilityService)`
  2. `AccessibilityManagerService.removeServiceLocked(AccessibilityServiceConnection, int)`
  3. `ActivityManagerService.killApplication(String, int, String)`
  4. `ActivityManagerService.killApplication(String, int, int, String)`（重载）
  命中 `TARGET_PACKAGE == "com.example.autobook"` 时 `param.result = null`。

**没有做的（文档此前写成已做）**：

- ❌ 没有 Hook `PowerManagerService`（全工程 grep `PowerManager` 只在
  `capture/AccessibilityEnhancer.kt:105` 与 `root/RootWatcher.kt:128` 的普通 API 调用里）。
  "强制豁免电池优化"这条能力不存在。
- ❌ 没有 Hook `Context.getSharedPreferences`，也没有 Hook `BillAccessibilityService.onUnbind/onDestroy`。
- ❌ 模块不写 App 的 SharedPreferences，所以 `XposedModuleDetector` 三路检测里的
  "SharedPreferences 标记 `xposed_module_active`"这一路**没有写入方**。
- ❌ `setModuleActiveFlag()` 用 `XposedHelpers.callStaticMethod(SystemProperties, "set",
  "persist.autobook.xposed.active", "true")`；普通应用无 system 权限，`SystemProperties.set`
  几乎必然抛异常并被 catch，这一路标记大概率也写不进去。
  结果是：设置页那个"LSP 模块已激活"状态**可信度存疑**，只剩"堆栈跟踪"一路还能真实命中。
- ❌ `XposedModuleDetector.getModuleVersion()` 去查包名 `com.example.autobook.xposed`，
  但模块是内置在主 App 里的，**不存在这个包**，该函数必然返回空/未知。

### 3.3 守护与厂商优化（0.6.2 时是死代码；**已在 0.6.3 接通**，见第八节）

- `capture/AccessibilityGuardian.kt` 存在（`start/stop/isAccessibilityEnabled`，
  `CHECK_INTERVAL_MS = 30_000L`，前台服务 `START_STICKY`），但：
  - `AndroidManifest.xml` **没有注册**该 `<service>`；
  - 全工程**没有任何** `AccessibilityGuardian.start(...)` 调用；
  - `App.kt` 只建了 `autobook_guardian` 通知渠道，没有启服务。
- `capture/AccessibilityEnhancer.kt` 存在（厂商识别、电池优化豁免引导、自启动引导），但
  全工程**没有任何调用点**（grep `AccessibilityEnhancer.` 只命中它自己）。
  `AccessibilityGuardian` 的唯一引用者就是 `AccessibilityEnhancer.getStatus`，
  所以 Guardian + Enhancer 整条链是**未接线的死代码**。
- 唯一真实触点：`BillAccessibilityService.kt:63` 在 `onServiceConnected()` 里跑一次
  `XposedModuleDetector.detect(this)`，只用于 `Log.i(TAG, "... 增强状态=$enhancementStatus")`；
  `onDestroy()`（`:147`）只做清理，**不重启服务**。

**结论**：0.6.2 的实际能力 = 120ms/600ms 分级去抖 + 主 App 携带 xposed_init 与 Hook 类 +
新图标。所谓"守护服务""厂商优化""电池优化强制豁免"都还没有接进任何运行时路径。
（**0.6.3 已把前两项接通**；"电池优化强制豁免"确认是文档谎言，从来没实现过，也不打算用 Hook 实现。）

### 3.4 相关文档已订正（2026-10-22）

3.1~3.3 的核查结论已回写到：`AGENTS.md`（头部 + §2.1/§2.3/§2.4/§11 + 文末交付补充）、
`README.md`（测试数、APK 指向、0.6.2 章节）、`CHANGELOG.md`、`HANDOVER.md`、`QUICKREF.md`、
`TEST_REPORT.md`、`DEPLOYMENT.md`、`agent.md`、`docs/LSP_MODULE.md`、`docs/LSP_QUICKSTART.md`、
`docs/LSP_SCOPE_CONFIG.md`、`docs/ENHANCEMENT_SUMMARY.md`、`docs/PLAN_0.5.md`（文首 0.6.2 状态块）、
`docs/ROOT_PLAN.md`（Root 路径的真机验证缺口表）、`docs/ICOST_IMPORT.md`（退款导出阻止的提示）、
`docs/IOS27_UI_PLAN.md`（把"待决策"改标为"已实施完毕的历史文档"），
并新增变更记录 `openspec/changes/enhanced-accessibility-lsp/`（proposal/design/tasks/spec）
与核查 run `runs/20261022-145102-doc-audit/`。
所有订正处统一用 `〔核查修正〕` 标记，便于后续 grep 复核。

### 3.5 新图标（0.6.2 记录；**实际生效于 0.6.4**）

0.6.2 时以为"图标已换"：`app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/` 下
`ic_launcher.png`、`ic_launcher_round.png`、`ic_launcher_foreground.png` 三个文件都在，
配 `mipmap-anydpi-v26/ic_launcher.xml`；源图 `AutoBook.png`(56KB) / `AutoBook-converted.png`(263KB)，
脚本 `generate-icons.sh`、`regenerate-icons.sh`（后者已于 0.6.4 删除）。

〔0.6.4 订正〕**这个判断是错的**：文件"都在"不等于"都换过"。两个脚本只写 `ic_launcher.png` 与
`ic_launcher_round.png`（legacy 层，API 26 以下才用），从不写 `ic_launcher_foreground.png`，
也不改 `values/colors.xml` 的背景色。而 API 26+ 桌面只渲染 `mipmap-anydpi-v26/ic_launcher.xml` 里的
`background` + `foreground` 两层，于是 legacy 换了、真机显示的自适应层仍是 2026-10-21 的旧图。
0.6.4 已删掉全部 15 个旧 PNG 重建，见第九节。

---

## 四、0.6.2 构建目标与脚本的失效点（**0.6.3 已全部修掉**）

- `settings.gradle.kts` 只有 `include(":app")` 与 `include(":billkit")`，**没有 `:xposed-module` 模块**。
  订正前，以下位置在调用这个不存在的任务（行号为订正前的位置，现文档里只剩订正说明本身）：
  - `docs/LSP_MODULE.md:222` `./gradlew :xposed-module:assembleRelease`
  - `docs/LSP_QUICKSTART.md:51` 同上；`:34-35` 的 `./build-xposed-module.sh` 内部也调它
  - **`build-xposed-module.sh:35`（至今未改，仍然会失败）**
  执行必失败（`Project 'xposed-module' not found`）。这些是"独立模块方案"时期的遗留，
  与"已内置到主 App"的现状冲突。
  **2026-10-22 已订正文档侧**：`docs/LSP_MODULE.md`、`docs/LSP_QUICKSTART.md` 里的构建/安装/卸载
  命令都改成主 App 路径并明确说明 `:xposed-module` 不存在。**脚本 `build-xposed-module.sh` 本身未改**
  （本轮不改代码/脚本），跑它仍会失败；要么删掉它，要么改成 `:app:assembleRelease` 的别名。
- `check-lsp-status.sh` 里 `adb shell "unzip -l ..."` 依赖设备端有 `unzip`，多数 ROM 没有，
  脚本会静默无输出。

**2026-10-22 0.6.3 已修**：
- `build-xposed-module.sh` 全文重写为"clean → 单测 → assembleDebug → assembleRelease + 校验产物"，
  不再调 `:xposed-module`；安装指南改为"只装一个 APK、作用域只勾 `android`、判据是日志里出现
  `阻止系统解绑无障碍服务`"。
- `build-all.sh` 重写：版本号从 `app/build.gradle.kts` 用 `grep -oP 'versionName\s*=\s*"\K[^"]+'`
  读出（不再硬编码），`~/.gradle` 不可写时自动 `mktemp -d` + `cp -a` 后设 `GRADLE_USER_HOME`，
  并新增 `check_version()` 用 `aapt2 dump badging` 核对 dist 文件名与 APK 实际版本一致，不一致直接 exit 1。
- `check-lsp-status.sh` 重写：`set -uo pipefail` + `adb get-state` 前置校验；第 3 步统计
  `阻止系统解绑无障碍服务|阻止移除无障碍服务|阻止系统杀进程` 命中数；第 4 步改
  `adb shell pm path` + `adb pull` 到 `mktemp -d` 后本地 `unzip -p … assets/xposed_init`，用完 `rm -rf`。
- 三个脚本均通过 `bash -n` 语法检查。

---

## 五、待办（按优先级）

1. ~~**P0** 让 `XposedModuleDetector` 的激活判定变成真实可靠的~~（**0.6.3 部分完成**：改为三档
   `HookState`，删掉永假的 SP 路与不存在的包检测，UI 改为"已确认 / 检测到框架但未确认 / 未检测到"，
   不再给假绿勾。**剩余**：`MODULE_CONFIRMED` 大概率长期不出现，因为模块侧 `SystemProperties.set`
   无权限——这是权限现实，若要真确认需让模块 Hook 到 App 进程自身，属设计取舍）。
2. ~~**P0** 决定 `AccessibilityGuardian` / `AccessibilityEnhancer` 的去留~~（**0.6.3 已做**：
   选了"接通"，Manifest 注册 + `CaptureModeManager.reconcile()` 起停 + `GuardianPolicy` 三档 +
   权限页保活组 + Enhancer 删假数据）。
3. **P1** 补 0.6.2 / 0.6.3 / 0.6.4 的真机验证：LSPosed 激活后系统是否真的撤销不了无障碍、杀不掉进程；
   120ms 快速响应在付款成功页是否真的更快；**0.6.4 重建的图标在桌面是否真的刷新**（旧图浅薄荷绿底、
   新图白底彩色圆环，一眼可分；部分 ROM 需重启桌面）；守护服务能否常驻、掉线通知能否弹、"假开启"能否识别。
   **这是当前最大的缺口。**
4. ~~**P1** 清理 `:xposed-module` 的过期构建说明（三份 docs + 一个脚本）~~（**0.6.3 已做**）。
5. ~~**P2** 补 0.6.2 的 run 目录与 verification.md~~（**已做**：`runs/20261022-145102-doc-audit/`，
   `manifest.json` + `verification.md` + `logs/unit-tests.log` + `test-results/`。但 `outputs/`
   为空——它是元数据核查 run，不是功能验收 run）。
6. ~~**P1** 修 `build-xposed-module.sh`~~（**0.6.3 已做**：改成构建主 App，不再调不存在的模块）。
7. ~~**P2** `check-lsp-status.sh` 的 `adb shell unzip` 改成拉回本地再查~~（**0.6.3 已做**：`adb pull` + 本地 `unzip`）。
8. **P2** 观察 `XposedModuleDetector` 在真机上的实际档位；若 `MODULE_CONFIRMED` 永不出现，
   考虑让模块 Hook 到 App 进程自身来写标记（取舍见 `openspec/changes/enhanced-accessibility-lsp/design.md`）。
9. **P2** 守护服务的常驻通知现在只是状态展示，是否加「暂停守护」动作待定。
10. ~~**P1** 图标在真机上一直是旧的~~（**0.6.4 已做并经真机确认**：根因是自适应前景层从未重新生成，
    已删 15 个旧 PNG 重建、背景色改白；用户 2026-10-22 装机后桌面立即显示新图。
    **剩余**：68dp 安全区在圆形/方形/圆角矩形三种裁切下的边距未逐形状目视）。

---

## 六、与其它文档的关系

| 文档 | 用途 | 与本文档的分工 |
|---|---|---|
| `AGENTS.md` | 主交接说明（架构/规则/坑） | 讲"怎么改"，本文档讲"改到哪了" |
| `README.md` | 对外功能与安装说明 | 面向使用者，不写核查细节 |
| `CHANGELOG.md` | 每版本变更条目 | 只写"这版加了什么" |
| `HANDOVER.md` / `QUICKREF.md` | 快速上手与交接摘要 | 速查，不承担事实核查 |
| `runs/<run-id>/verification.md` | 单次交付的验证证据 | 每次交付一份，本文档做索引与汇总 |
| `docs/PLAN_0.5.md` | 0.5 系列逐项计划与状态表 | A/B/C/D/E/F/U 维度的细账（0.6.3 在 U 维度兑现一半） |
| `docs/ROOT_PLAN.md` | root 路径的取舍与命令 | 讲 root 怎么用；本文档记它验证到哪一步 |
| `docs/IOS27_UI_PLAN.md` | UI 重做的设计依据 | 已实施完毕，只作留档；进度看本文档 |
| `openspec/changes/<change>/` | 单次变更的 proposal/design/tasks/spec | 一个变更一份；本文档是跨变更的总账。0.6.2 是 `enhanced-accessibility-lsp/`，0.6.3 是 `guardian-keepalive-wiring/`，0.6.4 是 `rebuild-launcher-icons/` |

---

## 七、2026-10-22 文档核查轮到底改了什么

**结论：只改文档，零代码改动**（用户当轮明确要求"先不要改代码"）。**本节记录的是 0.6.2 核查轮；
有代码改动的 0.6.3 见第八节。**

改动的文档共 16 份 + 新增 1 份 + 新增 1 个 run 目录 + 1 个 openspec 变更：

| 类别 | 文件 | 改了什么 |
|---|---|---|
| 新增 | `docs/PROGRESS.md`（本文件） | 跨版本进度总账，六~七节 |
| 新增 | `openspec/changes/enhanced-accessibility-lsp/` 四个文件 | 0.6.2 的变更记录（proposal/design/tasks/spec） |
| 新增 | `runs/20261022-145102-doc-audit/` | 元数据核查 run（manifest + verification + 单测日志 + 16 个 XML；`outputs/` 只有一个说明它为什么为空的 README） |
| 主交接 | `AGENTS.md` | 头部 0.6.2 实情 + 6 条核查修正；目录树；§2.1 版本号；§2.3 测试清单 111→149 并补 5 个类；§2.4 补 0.6.0/0.6.1/0.6.2 三行；§11 单测数；文末交付补充 |
| 对外 | `README.md` | 测试树补 5 类、111→149；界面标题 0.6.1→0.6.2；APK 指向 0.6.2/21；新增"0.6.2 无障碍增强与内置 LSP"章节 |
| 变更日志 | `CHANGELOG.md` | PowerManagerService / Guardian / `settings.gradle.kts` 三处失实订正，补核查注记 |
| 交接 | `HANDOVER.md` | 签名路径、Kotlin/AGP 版本、xposedminversion 82→93、日期 2024→2026、`service/AutoBookAccessibilityService.kt`→`capture/BillAccessibilityService.kt`、Hook 段重写 |
| 速查 | `QUICKREF.md` | "已完成"补 149 单测 + 核查修正段；待办拆"需真机/不依赖真机" |
| 测试 | `TEST_REPORT.md` | APK 精确字节/sha256/证书、149 单测实测、图标 17 条目、release 资源重命名说明、已知问题改"只有元数据证据" |
| 部署 | `DEPLOYMENT.md` | 构建产物、作用域只勾 `android`、按代码实际日志文案重写判读段、技术限制第 3 条、下一步 5 条 |
| 导航 | `agent.md` | 全文重写（原写"77 个用例 / v0.4.2/6"） |
| LSP 三件套 | `docs/LSP_MODULE.md`、`docs/LSP_QUICKSTART.md`、`docs/LSP_SCOPE_CONFIG.md` | 删掉 `xposed-module` 独立工程/构建/安装链路；Hook 表改 4 行真实 + 3 行"不存在"；作用域只勾 `android` |
| 版本总结 | `docs/ENHANCEMENT_SUMMARY.md` | 日期、文件清单、Hook 表、性能/可靠性数字全部改"未测"、验收清单 |
| 计划 | `docs/PLAN_0.5.md` | 文首 0.6.2 状态块 + 状态表加"0.6.2 变化"列 + 证据缺口段 |
| Root / iCost / UI | `docs/ROOT_PLAN.md`、`docs/ICOST_IMPORT.md`、`docs/IOS27_UI_PLAN.md` | 各加进度注记，不改技术内容 |

### 收尾阶段又补的几处（同一轮）

核对"文档里提到的文件/日志/数字是否真的存在"时又发现并订正了 6 处：

1. `AGENTS.md` `capture/BillAccessibilityService.kt` 描述仍写"600ms 去抖" → 改为按事件分级 120/600ms 并给出 `:29-31`、`:95-96` 行号。
2. `AGENTS.md` 指向的"仓库根 `真机验证0.4.1.md`" **文件不存在**（`ls` 报 No such file）→ 改为指向 `Samples.kt` / `PageClassifierTest` 里的支付宝 `支出19元` 夹具。
3. `AGENTS.md` §8 仍写"root 与无障碍可以同时开" → 标注 0.6.1 起二选一互斥。
4. `AGENTS.md` §10 路线图前两条仍把已交付项列为待办 → 明细编辑（0.6.0）、统计页切月（0.5.0）划掉，只留"分类筛选""环比"。
5. `AGENTS.md` §2.3 `CostCsvTest` 写 11 → 实际 **12**（改后 16 类合计正好 149）。
6. `QUICKREF.md` 的"预期日志"与 `HANDOVER.md` 的同段仍列 `LSPosed 模块已加载` / `Hook 无障碍服务成功` 两条**代码里不存在**的日志 → 换成 `AutoBookXposedModule.kt` 的真实文案，并说明真正的生效判据是 `阻止系统解绑无障碍服务`；`QUICKREF.md` 的 `./gradlew assembleRelease` 也补成 `:app:assembleRelease`。
另：`runs/20261022-145102-doc-audit/outputs/` 放了一份 `README.md` 说明它为什么为空，避免后来者误以为是漏了截图。

**没有动的**：所有 `.kt` / `.xml` / `build.gradle.kts` / `settings.gradle.kts` / `*.sh`。
因此 `build-xposed-module.sh:35` 仍然会失败，`check-lsp-status.sh` 仍然静默无输出，
`AccessibilityGuardian` 仍然没接线——这些是**代码侧待办**（见第五节，**均已在 0.6.3 处理**）。

---

## 八、0.6.3 做了什么（2026-10-22，**本轮有代码改动**）

**用户当轮原话**（m00892）："不留未接的死代码，确定保活无障碍模块的正常"。
即：①把零调用点的 `AccessibilityGuardian` / `AccessibilityEnhancer` 删掉或真正接通；②让无障碍采集模块的保活真正可用。
选了"接通"这条路（删除会让 0.6.2 文档里承诺的"守护"能力彻底消失，而用户诉求正是要保活可用）。

### 8.1 代码改动（10 个源文件 + 2 个测试文件）

| 文件 | 改动 |
|---|---|
| `app/src/main/AndroidManifest.xml` | 新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限；注册 `<service android:name=".capture.AccessibilityGuardian" android:exported="false" android:foregroundServiceType="specialUse">` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明 |
| `capture/GuardianPolicy.kt`（**新增**） | 纯逻辑 `object`：`enum Health{OK,DISABLED,UNBOUND}`、`fun health(enabledInSettings, serviceBound)`、`nextMisses(current, health)`、`alertThreshold(health)`（DISABLED=2、UNBOUND=4）、`shouldAlert(misses, health)`、`message(health)`；`REALERT_EVERY_MISSES=20`、`MISS_CAP=10_000` |
| `capture/AccessibilityGuardian.kt`（**重写**） | 真前台服务：`NOTIFICATION_ID=1002`、`ALERT_NOTIFICATION_ID=9001`、`CHANNEL_ID="autobook_guardian"`、`CHECK_INTERVAL_MS=30_000L`；companion `ensureChannel/shouldRun/start/ensureRunning/stop/isEnabledInSettings/isAccessibilityEnabled`；`onCreate` 里 `runCatching{startInForeground()}` 失败即 `stopSelf()`（防 Android 12+ `ForegroundServiceStartNotAllowedException` 崩溃），成功才 `running=true` + **立刻巡检一次** + `postDelayed(30s)`；`onStartCommand` 不满足 `shouldRun` 就 `stopSelf()`；`onDestroy` 清 handler；`onAccessibilityLost(health)` 先判通知权限再发 HIGH 通知（`ConfirmDelivery.CHANNEL_ID`、`ic_warn`、点进 `ACTION_ACCESSIBILITY_SETTINGS`） |
| `capture/AccessibilityEnhancer.kt`（**重写**） | `enum Manufacturer(brand, hasSpecialSettings)` 11 项 + `fromBrand(brand)`（纯函数，可单测）+ `detect()`；`KeepAliveStatus(batteryOptimizationIgnored, notificationsEnabled, accessibilityEnabled, accessibilityChecked, guardianRunning, manufacturer)` + `isFullyOptimized`；`getStatus()` 全部实测；`isBatteryOptimizationIgnored`/`batteryOptimizationIntent`（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 不可解析则退 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`）/`autoStartIntent`/`resolveAutoStartIntent`/`optimizationGuide`。**删掉** `autoStartEnabled=null`、`notificationEnabled=true // TODO`、`attemptAutoRepair()`（主线程 `Thread.sleep(500)` 后恒 `false`）、`logStatus()`、`guideUserToOptimize()` |
| `capture/XposedModuleDetector.kt`（**重写**） | `enum HookState{NOT_DETECTED, FRAMEWORK_PRESENT, MODULE_CONFIRMED}` + `state: StateFlow<HookState>` + `fun detect(context)`。**删除**永假的 `checkBySharedPreferences`、查不存在包 `com.example.autobook.xposed` 的 `getModuleVersion()`、5 条含"防止低内存回收/自动恢复服务/优先级提升"的 `getEnhancedFeatures()` |
| `capture/CaptureModeManager.kt` | `reconcile()` 末尾：`if (next.accessibilityActive) AccessibilityGuardian.ensureRunning(context) else AccessibilityGuardian.stop(context)` |
| `capture/BillAccessibilityService.kt` | `onServiceConnected()` 里 `detect()` 结果进日志；新增 `if (settings.enabled && captureMode==ACCESSIBILITY) AccessibilityGuardian.ensureRunning(this)` 幂等兜底 |
| `App.kt` | `ensureGuardianChannel()` 从手写渠道逻辑改为一行 `AccessibilityGuardian.ensureChannel(this)` |
| `ui/SettingsScreen.kt` | 无障碍行三分支（已连接 / 设置里开着但服务没连上 / 未开启）；新增「无障碍保活」组（守护服务 / 电池优化 / 厂商自启动 `${manufacturer.name}`，footer 用 `optimizationGuide` + 明说"系统不提供检查自启动是否已开的接口"）；LSP 组三档文案 + 局限说明；新增 `openAppDetailsSettings()`；`isAccessibilityEnabled` 委托 Guardian，另加 `isAccessibilityChecked` |
| `xposed/AutoBookXposedModule.kt` | **只改类 KDoc**（16:12 收尾）：原注释写"功能：1. 防止系统撤销无障碍权限 2. 防止厂商省电策略杀服务 3. 保持无障碍服务常驻"，后两条代码里不存在，改为真实的"只做两件事"（拦解绑/移除 + 拦 killApplication）并显式写出"做不到"三条（无 `PowerManagerService`、拦不住 LMK、不重启任何东西）。零逻辑改动 |
| `app/src/test/.../GuardianPolicyTest.kt`（**新增 12 例**） | 三档 health、计数清零/累加、DISABLED 2 次 vs UNBOUND 4 次、OK 永不报警、重复提醒窗口、计数封顶、**"服务启动时无障碍已关也能报警"回归**、两种 message 可区分 |
| `app/src/test/.../AccessibilityEnhancerTest.kt`（**新增 6 例**） | `fromBrand`（含 `Build.BRAND` 为 null 传空串兜底 OTHER）、`isFullyOptimized` 真值表、`optimizationGuide` 非空 |

踩过的坑：`AccessibilityEnhancer.kt` 里 `Settings`（本项目的 `data.Settings`）与 `android.provider.Settings`
同名冲突，报 `unresolved reference 'ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'`，
解法是 `import android.provider.Settings as AndroidSettings`。UI 中文文案里不能用 ASCII 双引号
（会截断 Kotlin 字符串），一律用 `「」`。

### 8.2 脚本改动（3 个，全部通过 `bash -n`）

- `build-xposed-module.sh`：不再调不存在的 `:xposed-module:assembleRelease`，改为 `clean → 单测 → assembleDebug → assembleRelease` + 校验产物存在。
- `build-all.sh`：版本号从 `app/build.gradle.kts` 读（`grep -oP 'versionName\s*=\s*"\K[^"]+'`），`~/.gradle` 不可写时自动 `mktemp -d` + `cp -a`，新增 `check_version()` 用 `aapt2 dump badging` 核对 dist 文件名与 APK 实际版本。
- `check-lsp-status.sh`：`set -uo pipefail` + `adb get-state` 前置校验；统计三条拦截日志命中数；`adb pull` + 本地 `unzip` 取代 `adb shell unzip`。

### 8.3 验证（**只有单测与产物元数据，零功能验证**）

- 单测：**167 / 0 failures / 0 errors / 0 skipped，18 类**，`--rerun-tasks` 强制重跑 `BUILD SUCCESSFUL in 58s`（`39 actionable tasks: 39 executed`）。
- 构建：`:app:assembleDebug` 与 `:app:assembleRelease` 均成功；`dist/AutoBook-0.6.3-{debug|release}.apk` 与 `app/build/outputs/apk/` 下同名文件 sha256 一致。
- 收尾重跑（16:12，只改了 `AutoBookXposedModule.kt` 的类注释）：`:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` → `BUILD SUCCESSFUL in 2m 11s`（146 tasks: 19 executed / 127 up-to-date），单测仍 167/0/0/0，`dist/` 两包已覆盖（字节数不变，sha256 更新，`manifest.json` 同步加了 `rebuild_2026_10_22_1614` 段与新的 `AutoBookXposedModule.kt` sha256）。
- 元数据：两包 `aapt2 dump badging` 均 `22 / 0.6.3`；release 证书与前版一致；Manifest 里 `<service>` 与权限都在（`aapt2 dump xmltree` 核对）；release dex 命中 `GuardianPolicy`(4)/`UNBOUND`(2)/`accessibilityChecked`(2)/`isEnabledInSettings`(1)/`KeepAliveStatus`(3)，确认未被 R8 裁掉。
- 证据目录：`runs/20261022-155747-guardian-hardening/`（manifest + verification + logs + 18 个 XML；`outputs/` 故意为空）。
- **未验证**：`adb devices` 当轮为空，守护服务常驻、掉线通知、"假开启"识别、权限页读数、电池优化/自启动跳转——全部没有设备证据。

### 8.4 保活到底能做到什么（写死在文档和 UI 里的口径）

**能做**：30s 巡检 + 掉线发通知（`DISABLED` 约 60s、`UNBOUND` 约 2 分钟）+ 引导用户手动做电池优化豁免与厂商自启动授权。

**做不到**（别再说成能做）：低内存 LMK 不被杀、被杀后自动重启（`onDestroy` 不重启服务）、提高进程优先级、Hook `PowerManagerService` 改电源策略。

### 8.5 本轮改动的文档

`AGENTS.md`（头部 + 0.6.3 段 + §2.1/§2.3/§2.4/§11 + 文末交付补充）、`README.md`（测试树/167 单测/APK 指向 + 新增 0.6.3 章节）、`CHANGELOG.md`（新增 v0.6.3 条目）、`HANDOVER.md`（文件树 + v0.6.3 版本历史）、`QUICKREF.md`（全文重写为 0.6.3）、`TEST_REPORT.md`（全文重写）、`DEPLOYMENT.md`（全文重写）、`agent.md`（167 例 / 0.6.3）、`docs/LSP_MODULE.md`（+第十节 0.6.3 变更）、`docs/LSP_QUICKSTART.md`（+第十一节保活分工）、`docs/ENHANCEMENT_SUMMARY.md`（+0.6.3 补记）、`docs/PROGRESS.md`（本节）、`openspec/changes/enhanced-accessibility-lsp/tasks.md`（T7/T8 勾掉，proposal/spec/design 补"已在 0.6.3 接通"，`design.md` 里"不做自动恢复守护"那条理由标注为已被用户诉求推翻）、
并**新增** `openspec/changes/guardian-keepalive-wiring/` 四个文件（`proposal.md` 说明为什么选"接通"而不是删除、
`design.md` 记录三档判定的阈值理由与"刻意不做"三件事、`tasks.md` T1-T13 已勾 + T14-T16 待真机、
`specs/guardian-keepalive/spec.md` 6 条 Requirement）。

---

## 九、0.6.4 做了什么（2026-10-22，**本轮有代码改动**）

### 9.1 起因

用户实测反馈：「我这图标怎么还没更新」。上一轮（0.6.2/0.6.3）文档都写"图标已换"，
但真机桌面一直是旧的。核查后确认**不是缓存问题**，是资源从来没被真正替换过。

### 9.2 根因（有 mtime 证据）

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`（`ic_launcher_round.xml` 同结构）内容是
  `<background android:drawable="@color/ic_launcher_background" />` +
  `<foreground android:drawable="@mipmap/ic_launcher_foreground" />`。
  **API 26+ 的桌面只渲染这两层**，`mipmap-*/ic_launcher.png` 是 legacy 兜底。
- 新图只在 **2026-10-22 13:48** 被写进 `ic_launcher.png` / `ic_launcher_round.png`；
  而 `ic_launcher_foreground.png` 五档的 mtime 全是 **2026-10-21 15:03**（旧图），
  `values/colors.xml` 也是 10-21 15:03（旧背景色 `#C0E1D7`）。
- 两个生成脚本（`generate-icons.sh` 用 `convert`、`regenerate-icons.sh` 用 `magick`）都只生成
  legacy 那两个文件，**从不生成 `ic_launcher_foreground.png`，也不改 `colors.xml`**。
  `regenerate-icons.sh` 因此已删除（与 generate 重叠且同样漏前景层，是这次事故的直接来源）。

### 9.3 做法

1. 删除 **15 个**旧 PNG（5 档 × `ic_launcher.png` / `ic_launcher_round.png` / `ic_launcher_foreground.png`）。
2. 重写 `generate-icons.sh`：一次生成三样——legacy 图标（48/72/96/144/192，不透明）、
   自适应前景层（108/162/216/324/432，`-fuzz 5% -trim` 去白边 → 白底转透明 →
   缩到 68dp 安全区 → 居中放到 108dp 透明画布）、自适应背景色（`#FFFFFF`）。
   末尾自带校验，尺寸或透明性不符就 `exit 1`。
3. 背景色 `values/colors.xml`：`#C0E1D7`（浅薄荷绿）→ `#FFFFFF`（取源图底色）。

（校验踩坑：ImageMagick 7 的 `%[channels]` 返回 `srgb 3.0` / `srgba 4.0`，不能和 `RGB`/`RGBA` 比；
改用 `%[opaque]` 判 `True`/`False`。）

### 9.4 验证（只有代码与产物证据）

| 项 | 结果 | 复核方式 |
|---|---|---|
| 单测 | 167 / 0 / 0 / 0，18 类 | `--rerun-tasks` 强制重跑 `BUILD SUCCESSFUL in 54s`（先随出包跑过一次 36s），18 个 XML 归档在 `runs/20261022-163800-icon-rebuild/test-results/` |
| 构建 | debug + release 均成功 | `146 actionable tasks: 28 executed, 1 from cache, 117 up-to-date` |
| 交付包 | `dist/AutoBook-0.6.4-debug.apk` 54,732,457 B / `dist/AutoBook-0.6.4-release.apk` 45,812,545 B | `ls -l dist/` |
| 版本 | 两包均 `versionCode='23'` / `versionName='0.6.4'` | `aapt2 dump badging` |
| sha256 | debug `021c1daf…27d414`、release `8006cd4e…534422` | `sha256sum` |
| 签名 | release `bedb36b7…8a5ed6`（与前版同证书）、debug `08b0f9a0…566cd7` | `apksigner verify --print-certs` |
| debug 图标 | 17 个 `ic_launcher*` 条目；前景层五档 **8590/14634/20982/35825/53182** 字节（旧包 3659/6591/10242/20653/34715） | `unzip -l` |
| release 图标 | `res/BW.xml` 的 `<foreground>` → `res/as.png`，实测 432x432 `opaque=False`、中心 `srgba(48,129,250,1)`；`color/ic_launcher_background` = `#ffffffff` | `aapt2 dump xmltree` / `aapt2 dump resources` |
| 前景层几何 | 非透明包围盒 264x272+84+80（居中、四周留白），四角全透明 | `magick -format "%@"` |
| 真机桌面 | **✅ 用户实测已刷新**为白底彩色圆环（2026-10-22，未清缓存）；三种裁切边距未核对 | 用户口头确认，无截图 |

### 9.5 没验证的（2026-10-22 17:08 更新：图标一项已由用户真机确认）

- ~~**真机桌面是否真的刷新**~~ → **已确认**：用户于 2026-10-22 装 `dist/AutoBook-0.6.4-release.apk` 后
  桌面图标变成新图（白底彩色圆环），无需清缓存、无需重启桌面。取证时 `adb devices` 为空，这条结论
  来自用户实测口头确认，**没有截图**，故 `runs/…/outputs/` 仍为空。
- **68dp 安全区在三种裁切形状下是否都留够边距**（圆形 / 方形 / 圆角矩形）。用户未逐形状检查，仍未核对。
- 部分 ROM 换图标后需要重启桌面才刷新——本机无法确认，本次用户设备上没出现（装上即刷新）。

### 9.5.1 本轮证据目录

`runs/20261022-163800-icon-rebuild/`：`manifest.json`（16 个源文件 sha256、两 APK 字节/sha256/签名、
前景层五档新旧字节对照、`icon_rebuild` 段记录根因与删掉的 15 个文件）、`verification.md`、
`logs/unit-tests.log`、`test-results/`（18 个 XML，167/0/0/0）、`outputs/README.md`（说明为什么为空）。
**它是元数据 + 单测证据，不是功能验收**。图标是否刷新这一项后来由用户真机确认（口头，无截图），
其余（68dp 安全区三种裁切边距）仍要装机目视。

### 9.6 本轮改动的文档

`CHANGELOG.md`（新增 v0.6.4 条目 + 证据 run 指针）、`TEST_REPORT.md`（全文改 0.6.4，证据指向
`runs/20261022-163800-icon-rebuild/`）、`DEPLOYMENT.md`（全文改 0.6.4 + 三个 run 的 `outputs/` 皆空说明）、
`HANDOVER.md`（标题/图标段/产物树/v0.6.4 版本历史，历史顺序改为新→旧）、`QUICKREF.md`（0.6.4 + 图标 FAQ 重写）、
`README.md`（安装段 + 新增 0.6.4 章节 + 证据 run）、`agent.md`（0.6.4/23 + 新增 0.6.4 重点一行）、
`AGENTS.md`（0.6.4 段/产物树/openspec 树/§2.1 版本/§2.3 167/§2.4 0.6.4 行/文末验证段）、
`docs/PLAN_0.5.md`（文首 0.6.4 追加段 + 状态表第五列「0.6.4 变化」+ 0.6.4 证据缺口段）、
`docs/LSP_QUICKSTART.md` 与 `docs/ENHANCEMENT_SUMMARY.md`（成品路径改 0.6.4/23）、
`docs/PROGRESS.md`（本节 + 第一节事实表 + 3.5 订正 + 9.5.1 证据目录）。
新增：`openspec/changes/rebuild-launcher-icons/`（proposal/design/tasks/spec）、`runs/20261022-163800-icon-rebuild/`。
**没有动的**：`app/src/**/*.kt`（本轮零 Kotlin 改动，只删/重建 PNG、改 `generate-icons.sh`、删 `regenerate-icons.sh`、
改 `app/build.gradle.kts` 的两行版本号）。


## 十、0.6.5 图标留白调整（待验收）

沿用原图与白底，自适应内容从68dp缩至54dp，108dp画布不变。legacy原图主体最大边874/1254≈69.7%，trim后取画布55%，相对旧版同样缩小约21%；初案75%会反向放大，已在构建前纠正。无记账业务逻辑变更。

资源五档尺寸、透明性、alpha包围盒和居中均核查。`--dry-run`未修改文件。debug与release的foreground引用均追溯至各五档PNG并核对几何；debug全部15个PNG与源资源逐字节一致。单测167例全绿后双构建通过，版本0.6.5/24，签名与0.6.4一致，历史APK保留。

运行证据：`runs/20261022-173414-icon-padding/`，交付索引：`deliverables/0.6.5-icon-padding/manifest.json`。当前目录无Git，commit不可用；OpenSpec CLI不可用，已手工核对计划结构与需求。初次沙箱构建因本机文件锁通信不可用失败，获准沙箱外运行后通过；日志均保留。尚无0.6.5真机证据，状态保持待验收。

### 扁长dock模拟器预览（2026-10-22，待验收）
源码已调整60dp高/36dp侧距，图标选中23dp、未选中21dp、标签11sp；仅debug安装到可见API35模拟器，版本仍0.6.6/25，dist与release未更新。167单测通过，四Tab点击与截图核查见runs/20261022-223607-dock-wide-preview/；用户验收待完成。
