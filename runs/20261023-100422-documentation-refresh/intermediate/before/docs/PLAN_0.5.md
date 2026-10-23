# 0.5 改进方案（识别 · 规则 · 导入导出 · 速度 · 交互）

> 后续工作入口已汇总为 [plan1.0](PLAN_1.0.md)（2026-10-23，待审阅）；本文件保留历史，旧状态与新计划冲突时以新计划为准。

> 2026-10-23 v0.6.7（26）新图及正式出包（待验收）：已将用户Auto.png更新到五档legacy/自适应及独立monochrome，保留白底、adaptive54dp/legacy55%留白；alpha≤1%透明噪点清理，原图未改。包含本地规则B0–B5/B7与U3–U10、60dp扁长dock。204单测/22类全绿，debug及签名release均成功；主代理独立核对0.6.7/26、新图可见RGB及全部alpha一致、release证书与0.6.6一致。产物dist/AutoBook-0.6.7-{debug,release}.apk，证据runs/20261023-085355-new-icon-release-0.6.7/。本次无在线设备，未验证新图在桌面实际刷新或release真机运行；此前API35预览证据仍有效但不等同新图验收。

> 2026-10-22 U系列预览补遗（待验收）：剩余U3–U10已实现，保留U1/U2当前轻透60dp dock及图标留白。新增外观主题/字号/降低动效、大字号与560dp布局、速度手势/页面及金额过渡、首页真实模式引导/待确认优先、完整月查询、monochrome/桌面快捷方式/识别磁贴/月度widget。204单测/22类全绿及debug构建通过；API35实际核查手动保存编辑取消、删除撤销、草稿重建保留、磁贴启停、widget添加点击、2.6字号/宽屏/深色及抓手分支。版本仍0.6.6/25，无release/dist。安全锁屏、完整TalkBack、低API及真机观感未实测；widget Doze跨日可延迟。证据 `runs/20261022-231606-u-series/outputs/verification.md`，用户验收未完成。

> 2026-10-22 本地规则系统预览补遗（待验收，优先于下方历史待做状态）：B0–B5及B7已实现；B3并入B7，B6/B8不在本轮。统一评分/独立分类账户来源、JSON覆盖、样本index.txt、近7天计数、餐段与手改立即学习/人工接受3次学习均已接入；设置→本地规则管理。成功人工入库才学，历史明确修改不累计频次。关闭学习同时暂停既有学习推荐。194单测/20类全绿，之后debug构建成功；版本保持0.6.6/25，无release/dist更新。证据 `runs/20261022-225837-local-rules-integration/`。模拟器视觉及用户验收由主代理继续，不宣称已验收。


> **0.6.4（23）追加（2026-10-22 图标重建轮，**有代码改动**）**：本版不涉及 A~F/U 任何计划项，
> 只修一个「看起来早就做了、其实从未生效」的资源问题——
> - 根因：`mipmap-anydpi-v26/ic_launcher.xml` 只渲染 `@color/ic_launcher_background` +
>   `@mipmap/ic_launcher_foreground` 两层，API 26+ 桌面走这里；而旧脚本只写 legacy 的
>   `ic_launcher.png`/`ic_launcher_round.png`，**从不生成 `ic_launcher_foreground.png`**，
>   所以 2026-10-22 13:48 的新图只进了 legacy 层，前景层还是 10-21 的旧图
> - 做法：删掉全部 **15 个**旧 PNG；`generate-icons.sh` 重写为一次生成 legacy（48~192，不透明）+
>   自适应前景（108~432，白底转透明、68dp 安全区、居中）+ 自适应背景色（`#C0E1D7`→`#FFFFFF`），
>   末尾自带尺寸/透明性校验（判透明用 `%[opaque]`，`%[channels]` 在 IM7 返回 `srgb 3.0` 会误判）
> - 删除 `regenerate-icons.sh`（与 generate 重叠且同样漏前景层，是这次事故的直接来源）
>
> 验证状态：**167 单测通过 / 18 类**（16:38 随出包复跑，`--rerun-tasks` 强制重跑 `BUILD SUCCESSFUL in 54s`），
> debug/release 均 **23/0.6.4**，debug 包前景层五档 8590/14634/20982/35825/53182 字节（旧包
> 3659/6591/10242/20653/34715），release `res/BW.xml` 的 foreground → `res/as.png`（432x432、
> `opaque=False`）、背景色 `#ffffffff`。证据 `runs/20261022-163800-icon-rebuild/`（`outputs/` 为空，
> **零真机证据**：桌面是否刷新、68dp 在三种裁切下是否够边距都没验）。变更记录
> `openspec/changes/rebuild-launcher-icons/`，逐项见 [`PROGRESS.md`](PROGRESS.md) 第九节。

> **0.6.3（22）追加（2026-10-22 保活接通轮，**有代码改动**）**：上一轮核查出的两处死代码已接通，
> 不新增 A/B/C/D/E 计划项，只把 U（稳定性）维度兑现了一半——
> - `AccessibilityGuardian` 注册为 `specialUse` 前台服务，由 `CaptureModeManager.reconcile()` 按
>   `accessibilityActive` 起停，`BillAccessibilityService.onServiceConnected()` 幂等兜底；
>   通知 id `1002`（root 是 `1001`）、异常提醒 `9001`、巡检 `CHECK_INTERVAL_MS = 30_000L`
> - 新增 `capture/GuardianPolicy.kt`：三档 `Health{OK, DISABLED, UNBOUND}`，修掉旧实现
>   "`lastCheckEnabled` 初值 false → 启动时已关永不报警"与"只查设置不查绑定"两个真缺陷；
>   `DISABLED` 2 次（约 60s）、`UNBOUND` 4 次（约 2 分钟）、重复提醒每 20 次、计数封顶 10000
> - `AccessibilityEnhancer` 重写为只保留实测项（通知权限、电池优化豁免、守护是否在跑、厂商识别与跳转）
> - `XposedModuleDetector` 改三档 `HookState`，删掉永假 SP 路 / 不存在的包 / 3 条谎言能力
> - 权限页新增「无障碍保活」组；`AndroidManifest.xml` 增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
> - 三个脚本修好（`build-xposed-module.sh`、`build-all.sh`、`check-lsp-status.sh`）
>
> **U 维度只兑现一半**：能"发现掉线并提醒"，不能自动恢复服务、不能豁免 LMK、不能提优先级。
> 验证状态：**167 单测通过 / 18 类**（15:57 `--rerun-tasks`），debug/release 均 22/0.6.3，
> 证据 `runs/20261022-155747-guardian-hardening/`（`outputs/` 为空，**零真机证据**）。
> 逐项见 [`PROGRESS.md`](PROGRESS.md) 第八节。

> **0.6.2（21）追加（2026-10-22 文档核查轮，未改代码）**：本版实际只有三件事——
> ①无障碍事件分级去抖（`BillAccessibilityService.kt:29-31` 定义 `DEBOUNCE_MS=600L`、
> `WINDOW_DEBOUNCE_MS=120L`，`:95-96` 按 `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` 选档）；
> ②LSP 增强功能内置进主 App（`app/src/main/assets/xposed_init` +
> `app/src/main/java/com/example/autobook/xposed/AutoBookXposedModule.kt`，真实 Hook 4 处）；
> ③新图标（`mipmap-*` 各档 `ic_launcher` / `ic_launcher_round`）。
>
> **F/U 维度没有新增**。原计划里被这一版"顺手宣称"的东西有出入，逐条订正：
> - 文档曾写 Hook `PowerManagerService` 强制豁免电池优化 → **代码里没有**；`PowerManager` 只作为普通 API 调用出现在
>   `capture/AccessibilityEnhancer.kt:105`、`root/RootWatcher.kt:128`
> - 文档曾写 `AccessibilityGuardian` 守护服务（30s 检查）已交付 → **当时类存在但未接线**（0.6.3 已接通，见上方 0.6.3 段）：
>   `AndroidManifest.xml` 没有该 `<service>`，全工程无 `AccessibilityGuardian.start(...)` 调用；
>   `AccessibilityEnhancer` 也无外部调用点
> - 文档曾写 `:xposed-module` 构建目标 → **不存在**，`settings.gradle.kts` 只有 `:app`、`:billkit`；
>   `build-xposed-module.sh:35`、`docs/LSP_MODULE.md`、`docs/LSP_QUICKSTART.md` 里的
>   `./gradlew :xposed-module:assembleRelease` 必然失败
> - `xposedminversion` 是 **93**（`AndroidManifest.xml:49-50`），不是文档里的 82
>
> 验证状态：**149 单测通过**（2026-10-22 14:51 本轮复跑，16 类，0 失败 0 错误 0 跳过）；
> debug/release 双包已核对 versionCode 21 / versionName 0.6.2；**0.6.2 没有任何真机或模拟器功能证据**
> （`runs/20261022-145102-doc-audit/` 是文档核查 run，`outputs/` 为空；`deliverables/` 无 0.6.2 快照）。逐项进度见 [`PROGRESS.md`](PROGRESS.md)。

> 0.6.1（20）追加：首次选择Root/无障碍并持久保存，设置可切换、总开关暂停保留选择。采集路径互斥，Root不再要求无障碍，无障碍不探测su；按模式显示授权/状态。旧Root配置迁移，备份不覆盖当前选择。F6旧版“无障碍+Root截图OCR”已被此模式方案替代，OCR仅Root模式使用。具体证据见`openspec/changes/choose-capture-mode/`，状态待验收。

## 当前状态核对（2026-10-22，优先于下方历史记录）

**0.6.4（23）为当前版本**：167 单测通过（18 类）、debug/release 双包已出（23/0.6.4）、无真机证据。
相对 0.6.3 没有 A~F/U 任何维度变化——只重建了桌面图标（图标替换本来也不属 U 维度）；
0.6.3 相对 0.6.2 只有 U（稳定性）维度变化——守护服务接通、判定三档化。两段追加见文首。

0.6.2（21）当时的状态（历史）：149 单测通过、双包已出、无真机证据；F/U 维度无新增。

0.6.0（19）已完成D6/F5/F3/E3/F13实施和主代理核查，待用户验收。141个单测通过，debug与签名release构建成功；API35模拟器已验证手动新增、原记录编辑、删除撤销、退款口径、退款CSV阻止提示和零金额禁存/取消不落库。

| 范围 | 代码核查结论（截至 0.6.1） | 0.6.2 变化 | 0.6.3 变化 | 0.6.4 变化 |
|---|---|---|---|---|
| A | A1已实现，A5对应样本工具已实现；A2/A3/A4待做 | 无 | 无 | 无 |
| B | B0–B5及B7已实现待验收；B1含index.txt；B3并入B7；B6/B8未做 | 194单测全绿 | debug预览成功 | runs/20261022-225837-local-rules-integration |
| C | C1/C2已实现；C3/C4/C5/C6/C7待做 | 无 | 无 | 无 |
| D | D1/D5已实现；D6正常/空闲/息屏节拍已修，间隔用户可选三档仍未做；D2/D3/D4/D7待做 | 无 | 无 | 无 |
| E | E1逐级返回主体已实现，首页二次返回未做；E2已实现；E3已实现编辑和每笔5秒撤销，E4仅箭头切月、无横滑，E5未补应用内进度过渡 | 无 | 无 | 无 |
| F | F1/F2/F4/F6/F15已修；F5截断三链路及各诊断已贯通；F3独立退款、F13手动记账已实现；F7缺权限横幅、F8有JSON备份但系统备份关闭、F9仅数据库缓解；F10/F14待做；F11/F12合并D6 | F5 相关：无障碍侧去抖改为按事件分级，截断与诊断链路未动 | 无 | 无（图标不属 F） |
| U | U1触感主体已实现；U2当前为真玻璃固定参数，强度滑块已移除、全宽遮罩已删除；U4/U5部分基础能力，其他仍待做 | 无（图标替换不属 U 维度） | **U 兑现一半**：死代码 Guardian/Enhancer 接通（30s 巡检、掉线提醒、三档判定、权限页保活组）；仍做不到自动恢复/LMK 豁免/提优先级 | 无 |

0.5.9额外完成：dock独立缓存阴影、深色轮廓底色、中性灰选中块、12sp标签、Tab语义与切页状态保存；色块以原中心向外扩3dp，约3dp外缘留白。当前不以“滚动遮罩/玻璃强度滑块”作为验收条件；选中块不跟随系统强调色。低Android版本不扩展验证。

测试和构建通过只代表相应验证范围，不等同用户验收；root续航、开机恢复和真实第三方支付识别仍需真机证据。证据：`runs/20261022-095145-core-ledger/verification.md`。退款不自动关联原账、不改写历史收入，含退款的iCost CSV导出明确阻止并建议JSON备份。以下进度块保留为历史，不代表最新行为。

**0.6.4 的证据缺口（2026-10-22 图标重建轮）**：证据只有两类——①单元测试（`--rerun-tasks` 强制重跑
**167/0/0/0，18 类**，`BUILD SUCCESSFUL in 54s`）；②产物元数据（两包 `aapt2 dump badging` 均为 23/0.6.4，
debug 包 17 个 `ic_launcher*` 条目且前景层五档字节 8590/14634/20982/35825/53182 与源文件一致，
release `res/BW.xml` 的 foreground → `res/as.png` 432x432 `opaque=False`、`color/ic_launcher_background`
= `#ffffffff`）。**功能性证据仍为零**：`runs/20261022-163800-icon-rebuild/outputs/` 是空的（当轮
`adb devices` 为空）。**「APK 里换了文件」不等于「桌面换了图标」** —— 这条后来被补上了：用户 2026-10-22 真机装上 0.6.4
即看到新图标（白底彩色圆环），0.6.2/0.6.3 的图标问题闭环。仍未做的是 68dp 安全区在圆形/方形/
圆角矩形三种裁切下的边距目视（用户未逐形状检查）。0.6.2/0.6.3 的功能缺口与 0.6.1 遗留的
Root 真机采集/开机恢复/功耗，同样一项没补。

**0.6.3 的证据缺口（2026-10-22 保活接通轮）**：证据同样只有两类——①单元测试（`./gradlew :app:testDebugUnitTest` 经 `/tmp/gh4` 绕开只读 `~/.gradle` 后 **167/0/0/0，18 类**，`--rerun-tasks` 强制重跑 `BUILD SUCCESSFUL in 58s`）；②产物元数据（两包 `aapt2 dump badging` 均为 22/0.6.3，release 证书与前版一致，Manifest 里 Guardian 的 `<service>` 与 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 都已核实，release dex 命中 `GuardianPolicy`/`UNBOUND`/`KeepAliveStatus`）。**功能性证据仍为零**：`runs/20261022-155747-guardian-hardening/outputs/` 是空的（当轮 `adb devices` 为空），守护服务能否常驻、掉线通知能否弹、"假开启"能否识别、电池优化与自启动跳转是否落到正确页，全部未验证；0.6.2 的 LSP 撤权拦截、120ms 手感、新图标刷新同样继续空缺，0.6.1 遗留的 Root 真机采集/开机恢复/功耗也还是空的。**0.6.3 新加的只是"发现掉线并提醒"，不是"阻止被杀"。**
〔后记 2026-10-22 17:08〕上面这条"新图标刷新继续空缺"已由 0.6.4 结案：图标根因是自适应前景层从未重建，
重建后用户真机确认刷新。LSP 撤权拦截、120ms 手感、守护服务常驻、Root 真机采集等**仍全部空缺**。

**0.6.2 的证据缺口（2026-10-22 核查）**：本轮只补了两类硬证据——①单元测试（`./gradlew :app:testDebugUnitTest` 经 `/tmp/gh2` 绕开只读 `~/.gradle` 后 **149/0/0/0**）；②产物元数据（两级 APK `aapt2 dump badging` 均为 21/0.6.2，release 证书与前版一致，`assets/xposed_init` 存在）。**功能性证据为零**：没有 `runs/20261022-*-enhanced-lsp/` 这样的 run 目录，没有 `deliverables/` 快照，LSP 是否真的拦住了撤权、无障碍是否真的 120ms、图标是否真的刷新，全部未验证。0.6.1 遗留的 Root 真机成功采集、开机恢复、功耗三项同样继续空缺。

---


> 基线：v0.4.2（versionCode 6），77 个单测全绿。
> 排序原则：先修「用户每天都能感觉到」的，再修「我加规则时才痛」的，最后才是锦上添花。
> 每项标注 **P0/P1/P2**、改动点（文件:行）、成本、验收方式。
>
> **进度**（v0.4.3 / versionCode 7，2026-10-21）——已完成：**E1** 侧滑返回逐级退、
> **D1** root 路径去掉重复 `dumpsys window`、**D5** root 轮询常驻（前台服务 + 开机自启）。
> 三项均已落地并通过 77 个单测 + `assembleDebug`/`assembleRelease`，待在真机验证。
> 其余 A*、B*、C*、D2~D4、E2~E5 仍未实施。
>
> **进度（v0.4.4 / versionCode 8，2026-10-21）** —— 用户 m01463「先把几个 bug 修了」已交付：
> **F1** 改金额少 1 分、**F15** Locale 依赖、**F2** 重启后重复入库、**F4** 统计页吞转账、
> **F5** 截断标记、**F6** 无障碍路径 OCR 兜底、**F7** 通知权限申请、
> 以及 **D6 的第 1、2 条**（熄屏长睡 + 空闲退避）。
> 83 个单测全绿（新增 `AmountInputTest` 6 条），`assembleDebug`/`assembleRelease` 均通过，待真机验证。
> 详见 §七 各条目的「✅ 已在 0.4.4 实现」块。
>
> **进度（v0.5.0 / versionCode 9，2026-10-21）** —— 用户 m01782「开工」已交付 0.4.4 剩下的七项：
> **A1** `GENERIC_BILL`（不再冒充支付宝）、**B1** Debug 页保存样本 / 复制 Kotlin 夹具、
> **C1** 导出范围（本月/上月/最近 3 个月/全部）、**C2** JSON 备份与恢复（SAF + 合并/覆盖两种）、
> **E2** 确认卡下滑关闭 + 进出场动画、**U1** 触感分级 `AbHaptic`、**U2** 玻璃高光/暗边/滚动遮罩 + 强度滑块。
> **111 个单测全绿**（0.4.4 的 83 + 新增 `MiniJsonTest` 6 / `BackupTest` 10 / `ExportRangeTest` 7 /
> `SampleExportTest` 6 中的一部分），`assembleDebug`/`assembleRelease` 均通过。
>
> **进度（v0.5.1 / versionCode 10，2026-10-21）** —— 用户 m02113：「底部 dock 并不是悬浮，
> 现在看 dock 下一层有个底色，像是之前的导航栏，挡着内容」。
> 根因：`PageScaffold` 把 `BottomFade(solidBelow = true)` 塞进了「tab 栏槽位」，
> 而真正的 `LiquidTabBar` 由 `MainActivity` 画在别处 —— 这条遮罩落在胶囊**下方**、
> 底端还是完全不透明的 `pageBg`，于是看起来就是一条旧式导航栏横带，把滚动内容挡掉。
> 修法：删掉 `BottomFade` 与 `PageScaffold` 的 `tabBar` 参数（四个页面都不再接收），
> 遮罩改成 `MainActivity` 里胶囊**正上方 24dp** 的淡渐变（`Transparent → pageBg 0.72`）；
> `MainActivity.onCreate` 加 `enableEdgeToEdge` + `isNavigationBarContrastEnforced = false`
> 去掉三键导航栏后面那层系统灰 scrim；`SubPageScaffold` 的底部留白补齐导航条 inset。
> 111 个单测仍全绿，`assembleDebug`/`assembleRelease` 通过，待真机验证。
>
> **进度（v0.5.2 / versionCode 11，2026-10-21）** —— 用户 m02284：「手势导航，还是有实色横带」。
> 0.5.1 只是把 `BottomFade` 从 `PageScaffold` 挪到了 `MainActivity`，**那条渐变本身才是问题**：
> 它 `fillMaxWidth()` 铺满全宽、底端是 `pageBg` alpha 0.72，浅色下白卡从下面滚过时
> 就是一条横贯全屏的灰带。在 api35 模拟器（1080×2400，density 420，手势导航）上按
> `adb exec-out screencap` 的原始 framebuffer 逐像素测量确认：
> - 24dp 渐变区 `y=2069..2132` 被 `pageBg` 以最高约 25% alpha 覆盖（白卡 255 → 249~254），**全宽**；
> - 胶囊下方 `y=2358..2400` 一直是纯 `pageBg` (242,242,247)，**没有任何异常色带**；
> - 胶囊自身 10dp 投影只有 1.2% 黑、且横向只覆盖胶囊宽度，属正常悬浮投影；
> - 系统侧 `navigationBars` 的 `flags=SUPPRESS_SCRIM` + `isNavigationBarContrastEnforced=false` 已生效，
>   **系统没画 scrim** —— 之前怀疑的「旧导航栏底色」并不存在。
>
> 修法：**整条渐变删掉**。`MainActivity` 的 dock 只剩一个 `navigationBarsPadding()` +
> `padding(bottom = 16dp)` 的 `Box` 装 `LiquidTabBar`，不垫任何全宽底色/渐变；
> 内容与 Tab 的分隔完全交给胶囊自己的玻璃与投影。安装 0.5.2 后同一位置复测：
> `y=2050..2130` 无论是 `pageBg` 还是白卡区域都是原色（255,255,255 / 242,242,247），无横带。
>
> **进度（v0.5.3 / versionCode 12，2026-10-21）** —— 用户 m02520：「还是有，像在悬浮胶囊和内容之间，
> 从手机最底部到高于悬浮胶囊上边高一点点」。这条描述把问题指得很准，顺着它找到了**真正的根因**，
> 与自绘渐变无关：
>
> `PageScaffold` / `SubPageScaffold` 交给内容的 `PaddingValues(bottom = tabBarHeight + navInset + 24dp)`
> 有**两种消费方式**，效果完全不同：
> - `contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())`（`BillsScreen` /
>   `StatsScreen` 一直是这么写的）= **视口仍是整屏高**，内容能从胶囊底下穿过去 → 悬浮感正确；
> - `Modifier.padding(padding)`（`HomeScreen.kt:69`、`SettingsScreen.kt:195` 的 ROOT 页）=
>   **视口被底部 inset 削掉**，内容在距屏底约 110dp（62dp 胶囊 + 24dp 导航条 + 24dp 留白 = 289px，
>   y≈2111）处被硬切，从那里往下整片 `pageBg`，胶囊（顶 y≈2132）正好压在这片纯色区里。
>   数字与用户描述精确吻合：视口底比胶囊顶只高约 21px = 「高于悬浮胶囊上边高一点点」。
>
> 修法：`HomeScreen` 与 `SettingsScreen` 的 ROOT 页去掉 `Modifier.padding(padding)`，
> 改成 `contentPadding` 里放 `padding.calculateBottomPadding()`，让视口保持整屏。
> api35 模拟器逐像素复核：设置页白卡一路铺到 y≈2390（越过胶囊顶 2134），胶囊下方不再是
> `pageBg` 纯色带；首页中段滚动时卡片从 y=2164 起可见；明细页同样。111 个单测全绿，
> `assembleDebug`/`assembleRelease` 通过，待真机验证。
>
> **进度（v0.5.5 / versionCode 14，2026-10-21）** —— 用户 m03982：「玻璃强度调节不管用；增加
> 悬浮胶囊内选中时的色块动画，要 duangduang 的感觉，色块颜色稍微灰一点就行，不需要强调色，
> 选中时图标变色，悬浮胶囊内图标放大，宽度减少，添加悬浮胶囊阴影」；m04265：「主体色跟随手机
> 安卓原生色」。六项全部落地：
>
> | 项 | 根因 / 做法 |
> |---|---|
> | 玻璃强度滑块没用 | 原来只把 `tintAlpha` 乘 `strength`，0.8↔1.0 肉眼无差。改成**折射高度 / 偏移 / 色散 / 模糊 / 透明度全部按 strength 连续插值**，`Appearance.DEFAULT` 提到 1.0，`OPAQUE_THRESHOLD` 以下直接退化成实色 |
> | Tab 选中块 duang | `LiquidTabCells` 改成 `BoxWithConstraints` + 滑动 Blob：`Animatable(selected)` 平移 + `scaleX` 先压到 0.80 再 `spring(dampingRatio=0.38, stiffness=300)` 弹回 |
> | 选中块用灰色 | 新增 `AbPalette.tabIndicator`（浅 `0x1F767680` / 深 `0x2E767680`），不用 accent |
> | 图标变色 + 放大 | `TabCell` 选中时 `graphicsLayer.scale 0.9→1.0`（spring 900）+ 颜色 `tertiaryLabel → label` |
> | 胶囊阴影 | **真坑**：① `LiquidGlassView` 没有 background，View elevation 恒为零阴影；② 自定义 `ViewOutlineProvider` 的 Outline 会被 AGSL 采样进玻璃变成黑斑；③ 阴影画在玻璃**内部**会被库的 `clipPath` 盖掉；④ 超出自身 bounds 的绘制不参与合成（`clipChildren=false` 也救不了）。最终做法：容器四周留 `shadowPadPx = 14dp` 内边距，玻璃与图标用同样 margin 内缩，阴影层（独立 ComposeView，在玻璃之下）用 `android.graphics.BlurMaskFilter` 沿胶囊矩形画两遍 stroke —— 宽淡主投影（alpha 34 / 16dp / blur 8dp）+ 窄实贴边暗边（alpha 22 / 5dp / blur 3dp） |
> | 主色跟随系统 | 新增 `ui/design/SystemAccent.kt`：API 31+ 读 `android.R.color.system_accent1_*`，只换 accent / onAccent / tabIndicator / 玻璃描边上沿，中性色不动。**档位名是 0/10/50/100/200…900/1000**，不是 Material3 的 10/20/40/80/90/95 —— 用错 `getIdentifier` 返回 0，功能静默失效（踩过） |
>
> 模拟器逐像素复核：浅色主题 accent 实测 `rgb(122,144,200)`（framework `system_accent1_400 = #ff7a90c8`），
> 深色主题 `rgb(176,198,255)`；胶囊正上方 y=2120 从背景 242 变到 216（外圈投影可见）；
> 滑块拖到 34% 后胶囊内 223 → 227 即时变化（不用重启）。
>
> `./gradlew :app:testDebugUnitTest` **111 tests / 0 failures**，debug/release 均构建通过。

> **进度（v0.5.4 / versionCode 13，2026-10-21）** —— 用户 m02775：「你这是不是跟柔光玻璃差太远了？
> https://github.com/QmDeve/AndroidLiquidGlassView 用这个试试，可以激进一点」。
>
> 此前的 `GlassSurface` 是**假的**：半透明底色 + 顶部高光 + 描边 + 阴影，本质是「磨砂卡片」，
> 没有折射、没有色散，和 iOS 26/27 的 Liquid Glass 不是一回事。0.5.4 换成真玻璃：
> 接开源库 **AndroidLiquidGlassView**（MIT，`com.qmdeve.liquidglass:core:1.1.0`），
> 内部是 `RenderNode` + `RuntimeShader`（AGSL 文件 `liquidglass_effect.agsl`）做**真实折射 + 色散**。
>
> 三个必须绕开的坑（都踩过）：
> 1. **上游 AAR 声明 `minCompileSdk=37`**，本项目 `compileSdk=35`（AGP 8.7 上限）→ 直接依赖会报
>    `requires ... compile against version 37 or later`。解法：解包 AAR 把
>    `META-INF/com/android/build/gradle/aar-metadata.properties` 的 `minCompileSdk` 改成 35，
>    用 python `zipfile` 重打包成 `app/libs/liquidglass-1.1.0.aar`，走 `implementation(files(...))`。
>    （库 class 是 major 65 / Java 21，实测 JDK 17 + Kotlin 2.0.21 + D8 都能正常消费。）
> 2. **采样源必须是兄弟节点，不能互相包含**。库的做法是 `LiquidGlassView.bind(sourceViewGroup)`，
>    在 `onPreDraw` 里把 source 画进 `RenderNode` 再对 shader 采样；如果玻璃自己就在 source 里，
>    会自我反馈/画成黑块。所以 `MainActivity` **放弃 `setContent` 单根**，改手搭 `FrameLayout`：
>    `ComposeView A`（整页内容，当采样源）+ `LiquidGlassTabBar`（`LiquidGlassView` bind A +
>    压在上面的透明 `ComposeView B`，只画四个图标，保证图标不被 shader 掰弯）。
> 3. **API 33 以下库只留透明壳**（`LiquidGlass.init` 里 `SDK_INT >= 33` 才建实现）。
>    API 26~32 必须自己回退：`createLiquidGlassTabBar()` 返回 null 时用原来的 Compose 假玻璃
>    `LiquidTabBar`，**且只能占胶囊大小**（早一版铺了满屏，把整屏触摸全吃掉了）。
>
> 最终参数（`ui/design/LiquidGlassTabBar.kt`，比库默认激进）：`cornerRadius` = 胶囊半高、
> `refractionHeight` 34dp（库上限 50dp）、`refractionOffset` 48dp、`dispersion` 0.78（默认 0.5）、
> `blurRadius` 9、`tintAlpha` 0.06、三个手势特效（draggable/elastic/touchEffect）全关。
> 「设置 → 外观 → 玻璃强度」仍然有用：拖到 `<= OPAQUE_THRESHOLD` 时把玻璃层设成 GONE、画实色胶囊。
>
> api35 模拟器逐像素验证（白卡滚到胶囊后面，逐行找卡片左边缘 x）：
> 胶囊上方 `y=2090..2158` 边缘稳定在 `x=43`（16dp padding 的正常位置）；
> 进入胶囊后 `y=2162→2206` 边缘从 `44` 单调漂到 `75`，再在胶囊下半段回摆 ——
> **像素真的被掰弯了**（这是假玻璃做不到的）；同一处边缘的 R/G/B 三通道还出现了几个像素的分离（色散）。
> 深色下胶囊是 `(15,15,15)` 而非纯黑（玻璃自身亮度叠加）。111 个单测全绿
> （玻璃是 View 层的事，没动识别链路），`assembleDebug`/`assembleRelease` 通过，待真机验证。
>
> **本次修订**（2026-10-21，按用户 m00938 的意见并入）：
> - 规则侧新增 **B0**（规则三层存哪的取舍）、**B5**（餐饮按时段分早/午/晚餐 · 用户点名）、
>   **B6**（商品名抽取，备注可用 `{商品}`）、**B7**（本地自动学习：手改 + 频次两种来源）；
> - 导出侧 **C3** 改写为「iCost 10 列 + 钱迹 13 列」双模板（钱迹官方模板已下载核对），
>   新增 **C6**（列表多选导出）、**C7**（定期提醒 + 一键跳到记账 App 导入界面 · 用户点名）；
> - 速度侧新增 **D6**（熄屏暂停 + 自适应退避）、**D7**（无障碍降级为触发器）。
>
> **第二次修订**（2026-10-21，按用户 m01232 / m01319 的意见并入）：
> - 新增 **§七 F 组**：读代码发现的 **15 个既有缺陷**（F1 改金额少 1 分、F2 重启后重复入库、
>   F3 退款记成收入、F4 转账在统计页消失、F7 权限无运行时申请、F10 每轮 fork root 进程 …）；
> - 新增 **§八 U 组**：对照 **iOS 27 / HyperOS 4 / ColorOS 17 / Android 17** 的
>   「2026 年一个完美 UI 该是什么样」，含 U1 触感、U2 玻璃质感（滚动遮罩 + 透明度滑块）、
>   U4 动效、U5 大字号/无障碍/外观设置页等 10 项。
>
> **先回答两个高频问题**（细节见 **§四 D6** 与 **§六 Q1/Q2**）：
> 1. root 主力模式下**无障碍通常可以关**——有悬浮窗权限时确认卡走 `ConfirmOverlay`，
>    不需要无障碍提供的 BAL 豁免；
> 2. root 轮询**目前确实偏耗电**：间隔 1.2s、每轮一次 `uiautomator dump`（200~500ms），
>    最坏一分钟约 50 次。D6 是省电的必修项。

---

## 一、识别准确性

现状：`PageClassifier` 18 个 `PageKind` + `generic()` 兜底；`AmountParser` 优先级「标签邻接 > 带 ¥ > 全屏唯一裸金额」。
真机上剩下的错判主要来自三处：**兜底把类型认错**、**列表页/弹层被当成详情**、**金额取到了别的数字**。

### A1（P0）~~`generic()` 不要冒充支付宝~~ ✅ 已在 0.5.0 实现

> ✅ **已在 0.5.0 实现**：`PageKind.GENERIC_BILL` 已加（`billkit/PageClassifier.kt:32`），
> `generic()` 返回它（:339），`PageClassifierTest` 有断言；"来源"在 DebugBus 里显示为 `GENERIC_BILL`。

`billkit/PageClassifier.kt:328-337` 现在统一返回 `PageMatch(PageKind.ALIPAY_BILL, "通用账单详情")`。
后果：任何陌生包的账单都会被 UI/CSV 标成「支付宝」，用户在朋友圈截图里也会看到错误的来源。

改法：
- `PageKind` 增加 `GENERIC_BILL`；
- `generic()` 返回 `PageMatch(PageKind.GENERIC_BILL, "通用账单详情")`；
- `BillPipeline` 的方向判定不变（方向只看 sign / 支出收入标签，不依赖 kind）；
- `CostCsv` / UI 的「来源」显示 `GENERIC_BILL → 其他`（不影响 iCost 分类，分类来自 `LedgerConfig`）。

成本：~30 行 + 2 条断言。风险：`PageClassifierTest` 里断言 `ALIPAY_BILL` 的用例需同步。

### A2（P0）给 `generic()` 加分数阈值，替代「三条件全满足」

现在是硬 AND（成功态 + detail 词 + orderish 词），太脆：少一个词就整页不认，多一个词就把列表页放进来。

改法：打分制，进 `generic()` 前先算分：

| 信号 | 分 |
|---|---|
| `AmountParser.status == SUCCESS` | +3 |
| 命中 detail 词（支付时间/交易单号/账单详情…） | +2 |
| 命中 orderish 词 | +2 |
| `findAmount != null` | +3 |
| 命中"支付方式/付款方式" | +1 |
| 命中列表信号（筛选/全部账单/≥3 行日期行） | −5 |
| 命中下单信号（立即支付/提交订单） | −8 |

阈值 `>= 6` 才算账单页。好处：漏一个词的详情页仍能过（3+3=6），列表页被 −5 直接打下去。

成本：~40 行，`scoreGeneric()` 可单测（给每个信号喂一个伪造 items）。验收：现有 77 测试 + 新增 6 条打分用例。

### A3（P1）金额消歧：优先「靠近金额标签/符号」而不是全屏唯一

已知坑：某些详情页同时有「优惠 ¥2.00」「实付 ¥25.80」。现在 `findAmount` 靠优先级能过，但若两个都带标签就会先到先得。

改法：`AmountParser.findAmount` 收集**所有**候选（`AmountHit` 列表），按 `(有标签, 离标签行距离, 有币种符号, 绝对值大)` 排序取第一；`excludedRe` 再加「优惠/已减/立减/红包抵扣/积分抵扣」。

成本：~60 行 + 3 条用例。风险：中（触碰核心解析），必须跑全量测试。

### A4（P2）用字号给金额加权

`TextCollector` 目前只取文本，不取字号。账单详情的金额通常字号最大。
若改成 `Item(text, top, left, textSizePx)`，`findAmount` 可优先选最大字号候选 —— 这是**根治金额取错**的办法。
成本：大（`UiSnapshot.items` 类型变更，波及 billkit 全部规则与 77 个测试）。放到 0.6。

### A5（P0）真机样本一键回灌

见「二、规则建立」B1 —— 同一个功能，准确性和规则建设共用。

---

## 二、规则建立（加一个 App 的成本）

现状：每个 App 的规则是 Kotlin 代码 + 单测夹具，加一个 App = 改 `PageClassifier` + 写 `Samples.kt` + 重新发版。
这台机器上支付宝的包名是 `com.tentent.mm`（不是官方 `com.eg.android.AlipayGphone`），就是典型：**规则和包名都写死，用户只能等发版**。

### B0（P0）规则到底存在哪：三层 + 一个引擎

这是必须先定的事，否则后面 B2/B3/B5/B7 会各写一套，互相打架。提议分三层，各层职责互斥：

| 层 | 位置 | 谁写 | 改一条要发版吗 | 典型内容 |
|---|---|---|---|---|
| ① 内置代码 | `billkit/PageClassifier` + `CategorySuggester` 正则 | 我（开发） | 要 | 各 App 的页面结构、通用分类词表 |
| ② 随包资源 + 覆盖文件 | `billkit/src/main/assets/rules.json`（随包）<br>`files/rules_override.json`（设备上可替换） | 我 / 用户手写 | 不要 | 陌生包名、店铺专属词、某 App 改版后的新结构 |
| ③ 用户偏好 | `ledger_config` 的 `learned_rules` / `time_rules` | App 自动写 + 用户在设置页改 | 不要 | 商户→分类的学习结果、早/午餐时段边界 |

优先级：**③ 用户明确设置 > ② 覆盖文件 > ② 随包默认 > ① 内置代码**。同级冲突取"更具体"的（长正则/长关键词优先），并把命中来源写进 `DebugBus` 结论，例如 `→ 餐饮/午餐 [learned]`、`[json]`、`[builtin]`、`[time]`。**没有这行标记，以后调规则就纯靠猜。**

统一引擎（比现在的 `firstOrNull { regex.containsMatchIn(text) }` 强）：

```kotlin
// app/src/main/java/com/example/autobook/data/CategorySuggester.kt 重构成
data class RuleHit(val category: String, val sub: String?, val score: Int, val source: String)

// 评分：手改学习 +100 / 用户 JSON +60 / 内置正则 +40 / 时间规则 +20（只在餐饮下）
//       关键词长度作为次级权重（越长越具体）；同分取序号靠前的
fun suggestScored(...): List<RuleHit>   // 返回排好序的候选，UI 只在 top-1 与次选冲突时提示
fun suggest(...): Suggestion            // 保留旧签名，= suggestScored().firstOrNull()
```

好处：① 用户改过一次（+100）永远压过我的内置规则（+40），不需要"删除规则"功能；② 打分能解释，诊断页能直接显示"为什么选了这个分类"；③ 现有 9 条 `CategorySuggesterTest` 只要断言 top-1 就不用改。

成本：~120 行 + 6 条打分用例。**建议排在 B1 之后、B2/B3 之前**，因为后两者都要往这个引擎里挂。

### B1（P0）~~Debug 页「保存为样本」~~ ✅ 已在 0.5.0 实现

> ✅ **已在 0.5.0 实现**：新增 `app/src/main/java/com/example/autobook/data/SampleExport.kt`（纯函数，
> `splitLines` / `kotlinFixture` / `sampleFileName` / `toIdentifier`，`$` 会被转义否则 `$25.80` 变模板变量），
> `SettingsScreen` 的 DebugPage 加了「复制为 Kotlin 夹具」「保存样本到文件」两个按钮，
> 样本落在 `files/samples/`。`SampleExportTest` 6 条覆盖。

`ui/SettingsScreen.kt` 的 DebugPage 加两个按钮：
1. **保存样本**：把当前 `DebugBus` 的 `packageName + activityClass + text` 写成
   `files/samples/<pkg>-<yyyyMMdd-HHmmss>.txt`，并在文件夹里维护 `index.txt`；
2. **复制为 Kotlin 夹具**：生成可直接粘进 `app/src/test/java/com/example/autobook/Samples.kt` 的代码块：

```kotlin
val alipayBillTentent = listOf(
    "账单详情", "支付成功", "¥25.80", "商户全称 星巴克咖啡", ...
)
```

成本：~80 行，纯 UI + 文件 IO，不碰识别链。这是后续所有规则工作的**基础设施**，必须先做。

### B2（P1）外置 JSON 规则（不发版加规则）

`billkit/src/main/assets/rules.json`（随包） + `files/rules_override.json`（用户/我可塞进设备）：

```json
{
  "rules": [
    {
      "packages": ["com.tentent.mm", "com.eg.android.AlipayGphone"],
      "kind": "ALIPAY_BILL",
      "require": ["账单详情|交易详情", "支付成功|交易成功"],
      "anyOf": [["交易单号", "订单号", "商户订单号"]],
      "exclude": ["筛选", "全部账单", "立即支付"],
      "strategy": "JSON:支付宝详情"
    }
  ]
}
```

`PageClassifier.classify` 顺序改为：反向信号 → **JSON 规则** → 内置 Kotlin 规则 → `generic()`。
JSON 规则排在前面，就能覆盖内置（互相冲突时以 JSON 为准，并写进 DebugBus 结论）。

成本：~150 行（含 `org.json` 解析）+ 4 条单测（读 asset 跑现有样本）。
风险：规则表达能力有限（只支持 contains / 正则 / 排除），复杂页面（微信多态）继续留在 Kotlin。

### B3（P1）本地学习：商户 → 分类 → **并入 B7，见下**

`CategorySuggester` 现在是纯内置正则 + 清单过滤。加一层：
- 用户在确认卡**手动改过**分类/账户 → 写 `ledger_config` 的 `learned_rules`（`merchant → (category, subCategory, account)`，同 key 覆盖）；
- `suggest()` 顺序：**learned > 内置规则 > 空**；
- 设置页「分类与账户」加一行「清除学习记录」。

成本：~70 行 + 3 条单测。收益：用户改两三次之后就不用再改（AGENTS.md §11 待办 P2 原条目）。

> **修订**：B7 是这一项的完整版（多一个"频次学习"来源 + 归一化 + 可回滚 UI），
> **直接做 B7，B3 不单独实现**，避免同一份 `learned_rules` 写两次。

### B4（P2）页面覆盖率自检

设置页加「识别统计」：近 7 天 `DebugBus` 里出现的 `(包名, 结论)` 计数，按 `skip:*` 排序。
让「哪个 App 老失败」变成可看的数字，而不是靠用户回忆。

### B5（P0）餐饮按时段细分早/午/晚餐 ← 用户点名

`CategorySuggester` 现在只用**文本**匹配：`早餐|午餐|晚餐|夜宵|食堂|面馆…` 一律落到 `餐饮/三餐`，
用户实际想要的"看时间自动判早/午/晚"完全没实现。

两级改法，先做 1 再做 2：

**1. 时间兜底（无文本线索时）**——在 `B0` 的打分引擎里加一条时间规则：

| 时段 | 默认边界 | 结论 |
|---|---|---|
| 05:00–10:29 | `breakfast` | 餐饮 / 早餐 |
| 10:30–14:29 | `lunch` | 餐饮 / 午餐 |
| 16:30–20:59 | `dinner` | 餐饮 / 晚餐 |
| 21:00–04:59 | `lateNight` | 餐饮 / 夜宵 |

关键点（**这是 B5 的全部难点，别写错**）：

- 只在**已经判定为「餐饮」一级分类**之后才用时间决定二级，不能拿时间凭空造出餐饮分类；
- 时段边界必须**用户可改**（宵夜党、两班倒、新疆时区都不一样）→ 存 `ledger_config` 的 `mealtime_rules`：
  `[{"sub":"早餐","from":"05:00","to":"10:30"}, …]`，设置页给四个时间选择器；
- 门槛：**文本线索优先于时间**。页面写了"早餐"就用早餐；页面只有商户名"沙县小吃"时，才用时间决定午/晚餐；
- 需要餐桌之外的"餐饮"证据：命中餐饮类商户词（沙县/兰州/米线/麻辣烫/肯德基…）或一级分类已被更高分规则定为餐饮。

**2. 时段内互斥（可选，P2）**——允许用户把某店铺钉死在某餐："这家店总是午餐"，
写进 `learned_rules` 的 `meal` 字段，届时压过时间规则（评分 +100，同 B7）。

成本：~80 行（时间规则 + `mealtime_rules` 存取 + 4 个时间选择器）+ 5 条单测（四个边界 + 跨零点）。
风险：低（纯增量，不动金额/状态）。验收：把系统时间设到 12:30，识别一笔"沙县小吃 19 元"→ 自动选中「餐饮/午餐」。

> **提醒**：`AmountParser` 解析出的 `timeMillis` 才是权威时间（账单页上的支付时间），
> 不要用 `System.currentTimeMillis()` —— 补记隔夜账单时两者差很远。`BillPipeline` 已有 `draft.timeMillis`，直接用。

### B6（P1）商品名抽取，备注模板加 `{商品}` ← 用户点名

用户想要"备注可以识别商户或者是产品"。现状 `NoteTemplate` 只有
`{商户} {支付方式} {分类} {账户} {时间}`（`data/NoteTemplate.kt:14`），没有商品。

**难点先说清楚**：外卖/网购类账单页里，"商品"通常长这样——
`招牌牛肉面 x1 ¥22.00`、`商品：农夫山泉 550ml*24瓶`、`共 2 件商品`。
**它不是"标签：值"结构**，`FieldExtractor` 现有的"标签邻接"抽不出来。

改法（放在 `billkit/FieldExtractor.kt`，保持无 Context）：

```kotlin
/** 商品行：以「x 数量」或「×N」结尾，或位于「商品/商品名/购买内容」标签之后 */
private val productLineRe = Regex("""^(?!\d)[^\s].{1,30}\s*[x×*]\s*\d{1,3}$""")
private val productLabelRe = Regex("""(商品名?称?|购买内容|套餐内容)[：: ](.{2,30})$""")
fun products(items: List<String>): List<String>   // 最多 3 条，去重、截断 20 字
```

- `BillDraft` 加 `val products: List<String> = emptyList()`（**默认值，向后兼容，现有 77 个测试不受影响**）；
- `NoteTemplate` 加占位符 `{商品}` = `products.joinToString("+")`，并加预设
  `"{商户}·{商品}"` / `"{商品}"`；
- 抽不到就为空，模板里 `{商品}` 走现有"清悬空分隔符"逻辑，不会出现 `星巴克·`。

成本：~90 行（含正则调优）+ 4 条单测（有 x1 尾、无尾、多件、抽不到）。
风险：中——**必须只在确认卡"备注"里体现，绝不参与金额/分类判定**，商品名里全是数字（`550ml`），
很容易被 `AmountParser` 当成金额。这条是红线。

### B7（P1）本地自动学习：两种来源，一套存储

B3 只写了"用户手改"这一种来源。实际能用的学习需要两种，且要能**回滚**：

| 来源 | 触发 | 权重 | 存储 |
|---|---|---|---|
| 手改（强） | 用户在确认卡把某商户的分类从 A 改成 B，然后保存 | +100 | `learned_rules[key]` 覆盖写 |
| 频次（弱） | 同一 `merchant` 连续 3 次自动分类结果被用户**原封不动接受** | +60 | 计数达 3 才转正，之前只存 `pending_learn` 计数 |

存储形态（`ledger_config`，继续用 `org.json` 手写序列化，与现有代码风格一致）：

```json
"learned_rules": [
  {"match":"星巴克","category":"餐饮","subCategory":"咖啡茶饮","account":"微信钱包","hits":12,"updatedAt":1759999999}
]
```

- `match` 是**商户名归一化后的关键词**（去空格/去分店后缀/去`(xx店)`），不是全等——否则"星巴克(东富路店)"和"星巴克"会学成两条；
- 同一 `match` 覆盖写，`hits++`；
- 设置页「分类与账户」底部加「已学习 N 条」→ 子页可逐条删除 + 「全部清除」；
- **只学用户自己的设备**，不上传（本来就无网络权限）。

成本：~120 行 + 4 条单测。与 B3 的区别：B3 只做手改 + 完全覆盖，B7 是 B3 的超集，**建议直接做 B7、B3 并入**。

### B8（P2）备注模板扩展（低成本、立竿见影）

`NoteTemplate` 现在最多 60 字、5 个占位符。加：`{日期}`（`MM-dd`）、`{星期}`、`{二级分类}`、`{商品}`（B6）、
`{原分类}`（用户改之前的值，便于日后回溯）。设置页预设从 4 条扩到 8 条。
成本：~30 行 + 2 条测试。

---

## 三、导出 / 导入多样性

现状：只有一个写死的 iCost 10 列 CSV，只导「最近 50 条」，**没有导入、没有备份**。
换签名/换机就丢全部账单（§2.0 已警告过签名风险）。

### C1（P0）~~导出范围可选~~ ✅ 已在 0.5.0 实现

> ✅ **已在 0.5.0 实现**：新增 `app/src/main/java/com/example/autobook/export/ExportRange.kt`
> （左闭右开的 `Span`，`LAST_3_MONTHS` 含本月），`CostShare.shareRange()` 走 `BillDao.range`；
> ExportPage 顶部改成 4 档 `Segmented` + 区间说明。`ExportRangeTest` 7 条覆盖跨年/闰年/上月边界。

ExportPage 加分段选择：`本月 / 上月 / 最近 3 个月 / 全部`，走已有 `BillDao.range(from, to)`。
0 条时给「该区间没有账单」而不是生成空文件。
成本：~50 行。验收：导出条数 == 列表里该区间条数。

### C2（P0）~~备份 / 恢复（JSON）~~ ✅ 已在 0.5.0 实现

> ✅ **已在 0.5.0 实现**：`data/MiniJson.kt`（手写编解码，**因为 org.json 在 JVM 单测里是空桩**）、
> `data/Backup.kt`（纯格式 + 校验：kind/format/bills 三道检查、缺指纹按 `BillDraft` 规则补）、
> `data/BackupStore.kt`（`MERGE`/`REPLACE` 两种，`applySettings` 返回白名单是否变化）；
> `BillDao` 加 `allOnce/insertAll/byFingerprint/deleteAll`（**未改表结构、未升 version**）；
> 设置页新增「备份与恢复」子页（`CreateDocument`/`OpenDocument` 两个 SAF launcher，恢复前弹确认框）。
> `MiniJsonTest` 6 + `BackupTest` 10 覆盖往返/坏文件/版本太新/缺字段。

- **备份**：`{ version, exportedAt, bills:[...], ledgerConfig:{groups,accounts,noteTemplate} }` 一个 JSON，走 `ACTION_CREATE_DOCUMENT`（SAF，不加依赖）写到用户选的位置；
- **恢复**：`ACTION_OPEN_DOCUMENT` 读回来，两条路：`merge`（按 fingerprint 去重追加）/ `replace`（清库后导入，二次确认）；
- 文件放 `cacheDir` 后分享只用于「快速备份到网盘」，主路径用 SAF 直写更稳。

成本：~200 行（Room 序列化 + SAF + 设置页两个入口）。这是**数据安全的兜底**，优先级等同 P0。

### C3（P1）导出模板可选：iCost 10 列 + 钱迹 13 列 ← 用户点名

**钱迹官方模板已下载核对**（`https://res.qianjiapp.com/Qianji_MuBan_V10.csv`，UTF-8 **带 BOM**，
CRLF 换行，共 13 列，官方示例行确认了取值格式）：

```
时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片
2022/7/12 21:20,三餐,午餐,支出,28.58,微信,,去肯德基吃汉堡,不计收支,,,种草,http://...
```

| 列 | 我们写什么 | 备注 |
|---|---|---|
| 时间 | `yyyy/M/d H:mm`（**不是** iCost 的 `yyyy年MM月dd日 HH:mm:ss`） | 官方示例 `2022/7/12 21:20` |
| 分类 / 二级分类 | `bill.category` / `bill.subCategory` | **必须是钱迹里已有的分类名**（同 iCost 的坑） |
| 类型 | `支出 / 收入 / 转账` | 钱迹导入支持这 3 种；**“报销/还款”我们不用** |
| 金额 | 正数 `%.2f` | 同 iCost |
| 账户1 / 账户2 | `bill.account` / 空 | |
| 备注 | `bill.note ?: merchant ?: strategy` | |
| 账单标记 | 空（可选值 `不计收支` 等，我们不用） | 留空即正常计入 |
| 手续费 / 优惠券 | 空 | 我们没解析出这两项 |
| 标签 | 空（钱迹格式 `#标签1#标签2#`） | |
| 账单图片 | 空 | |

此外钱迹的导入入口与 iCost 不同：**它没有 ShareReceiveActivity 那种定向 intents**，
官方流程是「用系统文件管理器打开 csv → 选择器里点『导入到钱迹』」。
所以 `CostShare` 对钱迹只需走 `ACTION_VIEW`（mime `text/csv`）让用户从选择器里挑，
**不能假定定向成功**。另外钱迹有公开 Url Scheme（Android ≥3.3）：
`qianji://com.mutangtech.qianji/common/addbill`（直接进记账界面）、`.../common/stats`、`.../common/budget`
——见 [钱迹 Url Scheme 文档](http://docs.litangkj.com/plugin/url_scheme_others.html)，记一笔场景可用。

**代码改法（关键：先抽列，再加模板）**

现在 `export/CostCsv.kt:25-29` 的 `HEADER` 和下面 `builder.append(escape(row))` 的 `row` 是**两处硬编 10 列**，
直接加模板必然漏改一处。先抽成：

```kotlin
data class Column(val header: String, val value: (BillDraft) -> String)

object Templates {
    val iCost: List<Column>   // 现有 10 列
    val qianji: List<Column>  // 上面 13 列
    val generic: List<Column> // 日期,类型,金额,一级分类,二级分类,账户,备注
}
fun build(bills: List<BillDraft>, template: List<Column>, profile: Profile): String
```

`Profile.datePattern` 随之按模板不同（iCost `yyyy年MM月dd日 HH:mm:ss` / 钱迹 `yyyy/M/d H:mm`），
以及 `withBom`（钱迹 true、iCost false）。保留旧 `build(bills, profile)` 签名做兼容，让现有 11 条 `CostCsvTest` 不改。

成本：~150 行 + 5 条用例（钱迹表头/日期格式/类型词/BOM/列数一致）。风险：低（纯格式化，纯函数可测）。

### C4（P1）导入：iCost CSV 反向

标题映射宽松识别（`日期/时间`、`金额`、`类型`、`分类/一级分类`、`账户/账户1`、`备注`），解析后进待确认队列（不直接落库，让用户扫一眼）。
只处理 UTF-8/GBK + 逗号/制表符，不做复杂 Excel。
成本：~150 行 + 4 条用例（含引号转义、BOM、缺失列）。

### C5（P2）记住上次分享目标

`CostShare.shareRecent(onlyTarget: Boolean)` 参数已存在但 UI 没暴露。加一个「只分享给 iCost」开关，默认 true。

### C6（P0）列表多选导出 ← 用户点名

`ui/BillsScreen.kt` 现在只能左滑删，**没有任何多选**。用户要的"单选多选记录导出"需要两层：

1. **单条导出**：`BillRow` 长按 → 弹菜单（`导出这条 / 编辑 / 删除`）；
2. **多选模式**：`BillsScreen` 顶部加「选择」入口 → 行左侧出勾选框、底部换成动作条
   （`已选 N 条 / 全选本页 / 导出 / 删除`）。选中集合用 `Set<Long>`（bill id），退出选择模式清空。

导出走 `CostCsv.build(selected.map { toDraft(it) }, template)` + `CostShare`，
**与时间段导出共用同一个 `exportAndShare(bills, template)` 函数**（C1 也用它），避免两条路径各写一份分享逻辑。

成本：~160 行（多选 UI + 动作条）。风险：低，但 `BillsScreen` 现在有 `SwipeToDismissBox`，
选择模式下必须禁用左滑（否则误删），这是最容易踩的坑。

### C7（P1）定期提醒 + 一键进记账 App 导入界面 ← 用户点名

用户原话大意：每周末提醒"这一周都记好了吗，可以导入 iCost 了"，点提醒直接跳到导入界面；
时间、内容都要能自定义。

**技术选型（不加依赖，按 AGENTS.md 约定）**：`AlarmManager.setInexactRepeating` +
`BroadcastReceiver` + `NotificationCompat`。**不用 WorkManager**（多一个依赖，且精确性反而差）。
`setInexactRepeating(RTC_WAKEUP, ...)` 不需要 `SCHEDULE_EXACT_ALARM` 权限，也不用精确闹钟的审核。

组成：

| 部件 | 内容 |
|---|---|
| 设置页新子页「定期提醒」 | 开关 / 周期（每周·每月）/ 星期几 / 时间 / 提醒文案（可编辑）/ 导出区间（本周·本月）/ 目标 App（iCost·钱迹） |
| `reminder/ExportReminder.kt` | `schedule(context, cfg)` / `cancel(context)`；用 `AlarmManager` 注册 |
| `reminder/ReminderReceiver.kt` | 收到广播 → 按区间生成 CSV 到 `cacheDir/share/` → 建通知（含「立即导入」Action） |
| 通知 Action | `PendingIntent` 指向 `ACTION_VIEW` + `content://`（FileProvider）+ mime `text/csv`，直达系统选择器；iCost 时用现有 `CostShare` 的定向路径 |
| 提醒文案默认值 | 「本周账单已整理好，共 N 笔，点这里导入 iCost」 |

`Settings` 加 `reminder_cfg`（JSON 字符串，`org.json` 手写，与现有风格一致）。
`BootReceiver` 里补一行：开机后按配置重挂闹钟（`AlarmManager` 的注册在重启后会丢，**这是必须的**，否则用户会发现"提醒只响一次"）。

成本：~220 行（含设置子页）+ 2 条单测（配置序列化、下次触发时间计算）。
风险：中——权限（`POST_NOTIFICATIONS` Android 13+ 需运行时申请，目前 `ConfirmDelivery.ensureChannel` 只是建渠道，**没申请过权限**）、
以及重启后重挂。

**顺手能省很多事的替代方案**（如果不想做闹钟）：把提醒做成 App 内的"首页横幅"——
打开 App 时若本周还未导出过，就显示一条「本周已记 12 笔，去导入 →」。
零权限、零后台，缺点是得用户自己打开 App。**建议两条都做**：横幅作为保底，闹钟作为主动提醒。

---

## 四、识别速度

现状（真机可感知）：无障碍路径 `DEBOUNCE_MS = 600`（`capture/BillAccessibilityService.kt:27`）；root 路径 1200ms 轮询、每轮 `dumpsys window` **跑两次**、`uiautomator dump` 200~500ms、OCR 150~400ms。

### D1（P0）~~root 路径去掉重复 `foreground()`~~ ✅ 已在 0.4.3 实现

> 已落地：`billkit/root/RootUiDumpSource.kt` 新增 `fun dump(fg: Foreground): UiSnapshot?`，
> `RootWatcher.tick()` 传已查好的 `fg`；`dump()` 保留为自行查一遍的便捷重载。
> 顺带把 `uiautomator dump` 与 `cat` 合成一条 `su` 命令（省一次 shell fork），
> 并改用 `<?xml` 开头校验代替脆弱的 "dumped to" 字符串判断。

`billkit/root/RootUiDumpSource.kt:40-41` 的 `dump()` 自己又调了一次 `foreground()`，而 `RootWatcher.tick()`（`root/RootWatcher.kt:66` 起）已经调过并校验了包名。
改成 `dump(foreground: Foreground)` 由调用方传入（或给 `dump()` 加一个 `skipForeground` 参数），保留无参重载给测试。
省一次 `dumpsys window`（~50–100ms/轮），逻辑零风险。

### D2（P0）无障碍去抖按事件类型分级

`BillAccessibilityService.onAccessibilityEvent`（:90）对所有事件一律 600ms 后抓。
改：
- `TYPE_WINDOW_STATE_CHANGED`（真正换页）→ **立即抓，不去抖**（或 120ms 兜底）；
- `TYPE_WINDOW_CONTENT_CHANGED` → 保持 600ms 去抖；
- 抓到后先算 `items.joinToString("|").hashCode()`，与上次相同直接 return（把 `RootWatcher` 里的内容哈希去重**下沉到 `BillKit.offer`**，两条路径共用）。

效果：从"进入详情页"到"弹卡"少 500ms 左右。

### D3（P1）`BillKit` 前置廉价闸门

`BillKit.dispatch` 之前先做一次零成本判据：`items.size < 3` 或 `items` 里没有任何数字 → 直接 skip，不跑正则。
省的是列表页/聊天页频繁触发时的 CPU（对续航有意义，对单次延迟意义不大）。

### D4（P1）埋点：记录单次耗时

`BillKit` 里 `val t0 = System.nanoTime()` → 走到 `DebugBus.publish` 时带上 `elapsedUs`；`DebugBus.State` 加一列。
诊断页显示「最近一次识别 xx ms」+ 近 20 次 P50/P95。
有了这个数字，后续优化才不是凭感觉（现在完全没度量）。

### D5（P2）~~root 轮询常驻~~ ✅ 已在 0.4.3 实现

> 用户已确认要「主力 root 模式」（m00731）。已落地：
> - `root/RootWatcher.kt` 重写为**单例**（`start(context, settings, intervalMs)` / `stop()` / `release()`），
>   暴露 `status: StateFlow<Status>`（running / lastPackage / lastResult / lastTickAt / tickCount）给设置页；
> - 新增 `root/RootForegroundService.kt`：`foregroundServiceType="specialUse"` + 常驻通知（带「停止」Action），
>   `START_STICKY`；停止走 `context.stopService()`（后台 `startService` 在 8+ 会抛 `IllegalStateException`）；
> - 新增 `root/BootReceiver.kt`：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 后按 `Settings.rootMode` 拉起；
> - `Settings.rootMode` 新增开关，`App.onCreate` 只在为真时起服务；
> - Manifest 加 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `RECEIVE_BOOT_COMPLETED`。
>
> 选 `specialUse` 而非 `dataSync`：后者 Android 15 有 6h/天限制且不允许从 `BOOT_COMPLETED` 启动。

### D6（P0）省电：熄屏暂停 + 自适应退避 ← 回答"root 耗电多吗"

> **✅ 第 1、2 条已在 0.4.4 实现**（第 3 条「间隔用户可选」未做）：
> - `app/src/main/java/com/example/autobook/root/RootWatcher.kt` 新增 `IDLE_SLEEP_MS = 30_000L`、
>   `RELAXED_INTERVAL_MS = 3_000L`、`private fun isInteractive(context)`（`PowerManager.isInteractive`
>   或 `KeyguardManager.isKeyguardLocked` 为假即视为人不在）；循环里非交互时 `lastHash = 0` 后
>   `delay(IDLE_SLEEP_MS)` 直接 `continue`，跳过 tick（不跑 `su`）。
> - 新增 `private var idleTick`：`tick()` 进函数即置 true，各"白跑"分支（识别关闭/拿不到前台/非白名单/
>   dump 失败/前台已切换/页面未变化）都直接 return 保持 true，只有真的走到 `BillKit.offer` 才置 false。
>   循环据 `if (idleTick) maxOf(intervalMs, RELAXED_INTERVAL_MS) else intervalMs` 退避。
> - 顺带的好处：解锁后 `lastHash` 已被清 0，不会因为"停在原页面"被判成"页面未变化"而漏抓。
> - 设置页「Root 轮询 → 实时状态」在息屏时显示「息屏休眠中」，用户可以确认它真的在睡。
>
> 剩下的第 3 条（省电 3s / 平衡 1.2s / 快速 0.6s 三档）留给 0.5.0。

**先给结论：现在的 root 轮询确实费电，而且是"花在没用的时候"。**

成本怎么算的（`root/RootWatcher.kt`）：

- 间隔 `intervalMs = 1_200L`，`while (isActive) { tick(); delay(1200) }`——**没有熄屏判断**；
- 每轮 tick 至少有 1 次 `su -c dumpsys window | grep ...`（~50~100ms，fork shell）；
- 只要在前台白名单 App 里，每轮再跑一次 `su -c "uiautomator dump; cat"`，**200~500ms**；
- 就算停在支付宝详情页**不动**，也一直每 1.2s 跑一遍 dump（内容哈希能拦住重复识别，但拦不住 dump 本身）。

最坏：`(100 + 500)ms / 1200ms ≈ 50%` 占空比，一分钟约 50 次 dump。这对电池是明显的持续负担，尤其夜里锁屏时。

改法三条，按性价比排序：

1. **熄屏 / 非交互直接停（省 90% 以上）**
   `PowerManager.isInteractive` 为 false 时 `delay(30_000)` 长睡并跳过 dump；
   用 `KeyguardManager.isKeyguardLocked` 一并跳过。**这一条就能解决夜间耗电的主要部分。**
2. **只在白名单 App 在前台时才 dump**（现在已经是：`BillKit.isAccepted` 在前）
   ——但 `dumpsys window` 本身每轮还在跑。改为：前台包名与上一轮相同且**上次结论是列表页/非账单页**时，
   把间隔临时放宽到 `3_000ms`（自适应退避）；只有进入新页面（`foreground()` 的 activityClass 变化）
   或上次是详情页时才用 `1_200ms`。
3. **间隔用户可调**：设置页已暴露 `minIntervalMs`，但 root 间隔是硬编 1200。
   加「root 轮询速度：省电 3s / 平衡 1.2s / 快速 0.6s」。

预期：日常占空比从 ~50% 降到个位数（静止页面 3s 间隔 → ~20%～30% 的 dump 时长，再加熄屏 0）。
成本：~60 行 + 2 条单测（状态机判定）。**建议排在 0.5.0 第一批，因为用户已经把它当主力模式在用。**

### D7（P2）无障碍降级为"触发器"（两条路不叠加跑）

现在无障碍和 root 是**两套独立采集**，同时开着就会对同一页面各跑一遍识别（`BillKit` 的去重能兜住重复草稿，
但 CPU 白花）。root 成为主力后，建议：

- 无障碍只保留 `TYPE_WINDOW_STATE_CHANGED` 作为**唤醒信号**（告诉 `RootWatcher` "页面变了，赶紧抓一次"），
  不再自己 `TextCollector.collect()`；
- 或者干脆二选一：设置页把两者做成互斥单选（`root 优先 / 无障碍优先`），`App.kt` 里按选择 install。

成本：~50 行。收益：省一半识别 CPU，且消除"两条路径谁先到"的不确定性。
**顺带回答用户的「无障碍还需要开吗」——见 §六 Q1。**

---

## 五、交互逻辑

### E1（P0）~~侧滑返回不再直接退出 App~~ ✅ 已在 0.4.3 实现 ← 用户明确点名

> 已落地：`ui/MainActivity.kt:68` `BackHandler(enabled = tab != 0) { forward = false; tab = 0 }`；
> `ui/SettingsScreen.kt:149` `BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }`。
> 「首页二次返回才退出」的提示未做（当前首页直接交给系统退出），留作 E1 的收尾项。

根因：全项目**没有任何 `BackHandler`**。`MainActivity`（`ui/MainActivity.kt:54` AppRoot）只有 `tab` 状态，`SettingsScreen.kt:112` 的 `page` 状态也没接系统返回。所以在任意非首页 tab、任意设置子页，返回/侧滑 = `finish()` 整个 App。

改法（`androidx.activity.compose.BackHandler`，已在 activity-compose 1.9.3 里，**无需加依赖**）：
1. `SettingsScreen`：`page != ROOT` → `BackHandler { page = ROOT }`；
2. `AppRoot`：`tab != 0` → `BackHandler { tab = 0 }`；
3. 首页 `tab == 0` → 二次返回才退出：第一次 `BackHandler` 拦截并 toast/snackbar「再按一次退出」，2s 内再按才 `finish()`。

验收：四个 tab + 八个设置子页逐个侧滑，都不再退出 App；首页连滑两次才退出。

### E2（P0）~~确认卡下滑关闭 + 进出场动画~~ ✅ 已在 0.5.0 实现

> ✅ **已在 0.5.0 实现**：`ui/ConfirmSheet.kt` 用 `Animatable` 做进场（`spring(dampingRatio=0.86f)`）
> 与下滑关闭（只绑在抓手区域，避免和内部竖滚动抢手势），滑过屏高 25% 松手即关闭，
> 否则回弹；压暗层 alpha 跟随位移。两个宿主（`ConfirmActivity` / `ConfirmOverlay`）自动同时生效，
> 悬浮窗的 BACK 键也走同一个 `closeAnimated()`。顺带把「记一笔」换成 `haptic.confirm()`。

`ui/ConfirmSheet.kt` 现在只有「点压暗层关闭」（:99-108），没有下滑手势，也没有动画 —— 与 iOS 手感不符，用户会觉得"卡住了"。
改：
- `Modifier.pointerInput { detectVerticalDragGestures }` 累计位移 > 120dp（或速度 > 1000）→ `onDiscard()`；否则 `animateFloatAsState` 回弹；
- 卡片整体 `offsetY` 跟随手势 + `AnimatedVisibility`（`slideInVertically { it }` / `slideOutVertically { it }`）。

成本：~100 行，两个宿主（Activity / Overlay）自动同时生效。

### E3（P1）账单可点击编辑 + 删除可撤销

现在：`BillsScreen.kt` 左滑 `SwipeToDismissBox`（:155-166）**滑过即删库**，且列表**不能点**（AGENTS.md §11 P1 原条目）。
改：
1. `BillDao` 加 `@Update suspend fun update(bill: BillEntity)`（不改表结构，**不需要升版本**）；
2. 点击行 → 复用 `ConfirmSheet` 做「编辑模式」（已有金额/分类/账户/备注全部控件，只是 onSave 走 update 而不是 insert）；
3. 删库前把被删实体暂存，用 `SnackbarHost` 给「撤销」，5s 内点撤销则 `insert` 回去。

成本：~180 行。风险：低（多一个 DAO 方法）。

### E4（P1）统计页左右滑动切月

`StatsScreen.kt` 已有左右箭头（:198/:222）。加 `Modifier.pointerInput { detectHorizontalDragGestures }` 复用同一份切月逻辑。
成本：~40 行。

### E5（P2）预测性返回动画

`AndroidManifest` 加 `android:enableOnBackInvokedCallback="true"`，配合 E1 的 `BackHandler`（activity-compose 1.9 已兼容）。需要在真机上确认 Compose 过渡不闪。

---

## 六、回答两个高频问题

### Q1：root 模式下，无障碍还需要打开吗？

**结论：通常可以关。** 分场景说清楚，因为这句话对确认卡能否弹出有直接影响：

| 场景 | 无障碍要开吗 | 原因 |
|---|---|---|
| root 主力 + **已给悬浮窗权限** | **不要** | 确认卡走 `ConfirmOverlay`（`TYPE_APPLICATION_OVERLAY`），完全不经过 Activity 启动，不受 BAL 限制，也不需要无障碍的豁免。`ConfirmDelivery.deliver()` 第一分支就是它。 |
| root 主力 + **没给悬浮窗权限** | 建议开 | 退回 `ConfirmActivity` 时需要 BAL 豁免：`ConfirmDelivery.deliver()` 里 `exempt = foreground || anchored || isAccessibilityServiceEnabled`。没悬浮窗权限时 `OverlayAnchor` 也挂不上（同一权限），只能靠"自己的 App 在前台"或无障碍绑定豁免。**说白了：去给悬浮窗权限，比开无障碍更省事、更省电。** |
| 只想要最省电 | 不要 | 无障碍服务虽然不 dump，但系统会持续投递 `TYPE_WINDOW_CONTENT_CHANGED`，大量无关回调也有开销（有 600ms 去抖，但仍要跑 `TextCollector` 遍历节点树）。 |

所以推荐的配置是：**认别开关 ON + root 模式 ON + 悬浮窗权限给上 + 无障碍关掉**。
将来 D7 落地后，如果想让无障碍当"页面变化触发器"，再打开也不迟。

### Q2：root 耗电多吗？

**现在偏多，必须优化（D6）。** 量化：静止详情页时 `(dumpsys 100ms + dump 500ms) / 1200ms ≈ 50%` 占空比，
一分钟约 50 次 `uiautomator dump`；夜里锁屏也照跑，这是最不该发生的一项。

D6 三条（熄屏暂停 / 自适应退避 / 间隔可调）做完后，日常占空比能压到个位数。
在那之前，临时缓解办法：把 root 模式关掉、只在要记大额时打开；或改用无障碍模式（被动事件驱动，空闲时零开销）。

---

## 七、代码审查：15 个已确认的缺陷（F 组）

> 这 15 条不是新功能，是**读代码时发现的既有缺陷**，2026-10-21 逐条用 grep/read 复核过。
> 其中 F11（息屏该停）、F12（间隔自适应）已并入 D6，不再单列。
> 排序按「用户能不能感觉到」→「将来会不会痛」。

### F1（P0）~~在确认卡里改金额会少 1 分~~ ✅ 已在 0.4.4 实现

> 新建 `app/src/main/java/com/example/autobook/ui/AmountInput.kt`：
> `internal fun parseAmountCents(text: String): Long?` 用 `(yuan * 100).roundToLong()`
> （不是 `toLong()`），并清洗 `¥ / ￥ / , / ，` 与空白，非法/非正/超 100 万元返 null
> 由调用方回退原值。`ConfirmSheet` 改为调它。
> 回归测试 `app/src/test/java/com/example/autobook/AmountInputTest.kt` 6 条：
> `19.99→1999 / 0.29→29 / 1.15→115 / 8.70→870 / 35.35→3535`，以及往返一致。

`app/src/main/java/com/example/autobook/ui/ConfirmSheet.kt:88`

```kotlin
val cents = (amountText.toDoubleOrNull()?.times(100))?.toLong() ?: draft.priceCents
```

`toLong()` 是**截断**不是四舍五入。实测：`19.99 → 1998`、`0.29 → 28`、`1.15 → 114`、`8.70 → 869`。
凡是在确认卡里手动改过金额的账单，只要浮点表示略小于整数（约一半小数会），就少 1 分。
而识别链路的 `billkit/.../AmountParser.kt:92` 用的是 `Math.round(d * 100)`，是对的 —— 只有 UI 这一处漏了。

改：`Math.round(amountText.toDoubleOrNull()?.times(100) ?: return) .toLong()`。

### F2（P0）~~同一笔账单重启后会重复入库~~ ✅ 已在 0.4.4 实现

> `app/src/main/java/com/example/autobook/data/BillWriter.kt` 的 `save()` 落库前先查重，
> 窗口 `DEDUPE_WINDOW_MS = 10 * 60 * 1000L`：
> - `BillDao` 新增 `countRecentDuplicate(fp, pkg, cents, tail, since)`：`fingerprint = :fp`
>   **或**（`sourcePkg + priceCents + orderTail` 三者相同且 `tail != null`）——
>   第二条是刻意加的：单号后 6 位相同 + 同包 + 同金额，即使页面时间跨了分钟也算同一笔（顺带缓解 F9）；
> - 命中重复则不 insert，但仍 `PendingStore.remove(fingerprint)`（用户点过「记一笔」就该出队）；
> - `save()` 新增 `onDone: ((Boolean) -> Unit)?`，`true` = 真的插入了，供 UI 区分；
> - 不改 schema、不升数据库版本。

去重指纹只活在内存：`billkit/.../BillKit.kt:137 isDuplicate()` 查 `lastSeen: LinkedHashMap`，进程一死即清。
而 `app/src/main/java/com/example/autobook/data/Bill.kt:61` 的 `countRecentFingerprint(fp, since)` **写好了却零调用**（grep 确认）。

后果：页面停在账单详情页 → 杀进程/重启 → 再抓一次 → 多一条。

改（不动 schema、不升版本）：`app/src/main/java/com/example/autobook/data/BillWriter.kt` 的 `save()` 里先
`if (dao.countRecentFingerprint(draft.fingerprint, now - 10 * 60_000) > 0) return false`。
连带把 F11 的「去重窗口」语义补齐：内存去重挡 90s，数据库去重挡 10min。

### F3（P1）退款被记成「收入」

`billkit/.../BillPipeline.kt:75` 的 `incomeStrong` 含 `"退款" / "已退款" / "退回"`。
退你 50 块 → 记一条 INCOME 50，原来那笔 50 元支出还在 → 支出与收入两个口径同时虚高（净额看着对）。
正解二选一：①**关联原支出冲减**（在 DB 里找近期同商户同金额的 EXPENSE，把它的金额改小）；②新增 `BillType.REFUND`，统计页单列「退款」，不并入收入。
推荐 ①（用户只关心"这个月实际花了多少"），②作为兜底。

### F4（P0）~~转账在统计页彻底消失~~ ✅ 已在 0.4.4 实现

> `app/src/main/java/com/example/autobook/ui/StatsScreen.kt` 单独统计 `transfer` 并新增
> `TransferRow`：一行「转账 N 笔 · 合计 ¥X（不计入收支）」+ 说明文字，中性色不抢眼。
> 只有 transfer 非空时才显示。

`app/src/main/java/com/example/autobook/ui/StatsScreen.kt:63-64` 只 `filter { type == "EXPENSE" }` / `"INCOME"`。
`TRANSFER` 既不进支出也不进收入，也没有单独一块；而明细页 `observeRecent()` 不过滤，所以能看到 —— 用户会以为统计漏了。

改：统计页底部加一块「转账 N 笔 / 合计 X」（中性色，不计入收支），或至少在收支卡下加一行说明。

### F5（P1）~~页面被截断时没人知道~~ ✅ 已在 0.4.4 实现

> `billkit/src/main/java/com/example/billkit/BillKit.kt:135`：
> `if (snapshot.truncated) append(" [截断]")` 接进结论行。Debug 页原样显示结论，
> 所以用户/排查时能直接看到 `draft:19.0 ALIPAY_BILL [截断]`。`DebugBus.State` 未加字段（复用结论文本）。

`billkit/.../TextCollector.kt` 有 200 条 / 2000 字上限，`billkit/.../UiSnapshot.kt:11` 的 `val truncated: Boolean = false` **全项目零读取**，也不进 `DebugBus`。
长页面被截断 → 漏字段 → 识别失败，而调试页显示的文本看起来"挺全的"。

改：`BillKit.dispatch` 里 `if (snapshot.truncated) DebugBus.annotateOutcome(" [截断]")`；`DebugBus.State` 加 `truncated` 字段给调试页显示。

### F6（P1）~~无障碍路径没有 OCR 兜底~~ ✅ 已在 0.4.4 实现

> **没有碰合规红线**：用的是 `root/RootShell` + `root/RootScreenshot`（`su -c screencap`），
> **没有**申报 `canTakeScreenshot`、没有用 `AccessibilityService.takeScreenshot`。
> `app/src/main/java/com/example/autobook/capture/BillAccessibilityService.kt` 新增
> `offerOcrFallback(pkg, activityClass)`：
> - 只在 `BillKit.offer` 返回 `outcome.needsOcr` 且 `BillKit.currentConfig().ocrEnabled` 时触发；
> - 冷却 `OCR_COOLDOWN_MS = 3_000L`（与 root 路径一致）；
> - root 授权探测 `rootAvailable: Boolean?` 只做一次，没 root 就永久跳过，不反复 `su`；
> - 状态栏高度 `STATUS_BAR_DP = 24` 换算成 px 后传给 `BillKit.offerOcr`。
> 即：**只开无障碍但手机已 root 的用户，OCR 兜底现在真的生效了**；没 root 的用户仍是靠规则。

`needsOcr` 只在 `app/src/main/java/com/example/autobook/root/RootWatcher.kt:133` 被处理；
`app/src/main/java/com/example/autobook/capture/BillAccessibilityService.kt` grep 不到 `needsOcr` / `offerOcr`。
纯无障碍模式下 OCR 开关是**装饰品**。

改：`BillAccessibilityService` 拿到 `Skipped` 且 `needsOcr` 时，走「截图权限」路线（`AccessibilityService.takeScreenshot`，API 30+，需在 config 里申报 `canTakeScreenshot`——**注意这与 AGENTS.md §9 的合规红线冲突，需要用户明确同意才做**），或直接提示"root 模式下才有 OCR 兜底"。**默认建议：选择后者（只改文案），不碰红线。**

### F7（P0）~~权限没有任何运行时申请~~ ✅ 已在 0.4.4 实现

> 1. `app/src/main/java/com/example/autobook/ui/SettingsScreen.kt` 的权限页新增「通知」一行：
>    `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`，
>    点击时 API 33+ 走 `Manifest.permission.POST_NOTIFICATIONS`，低版本直接跳系统通知设置；
>    副标题实时显示已授权/未授权。
> 2. `app/src/main/java/com/example/autobook/capture/ConfirmDelivery.kt` 新增
>    `fun hasNotificationPermission(context)` = `NotificationManagerCompat.areNotificationsEnabled()`；
>    `deliver()` 在落队列后先判断它，没权限直接返回 **`pending-no-notify`**（不再是笼统的 `pending`）。
> 3. §10 排查表已加对应行。
> 顶部「一次性横幅」未做，留给 0.5.0。

全项目 grep 不到 `ActivityResultContracts` / `requestPermissions`。
通知权限没给时，`app/src/main/java/com/example/autobook/capture/ConfirmDelivery.kt:156` 的 `notify()` 撞 `SecurityException` 静默 `return false`，最终结论只是 `pending` —— 用户完全不知道为什么没弹。

改：
1. 权限页加「通知权限」行 + `rememberLauncherForActivityResult(RequestPermission())`（API 33+，低版本直接算已授权）；
2. `ConfirmDelivery.deliver()` 返回值区分 `pending-no-notification-permission`；
3. 设置页顶部加一条**一次性横幅**：缺通知权限 / 缺悬浮窗权限时显示，点进对应设置。

### F8（P1）`allowBackup="false"` 让换机等于数据清零

`app/src/main/AndroidManifest.xml:34`。换机/重装 = 账单全丢，且没有任何备份通道。
这把 C2（JSON 备份/恢复）从"锦上添花"变成"该尽早"，同时建议开 `dataExtractionRules` 只允许 SharedPreferences + DB（不含 `cacheDir`）。

### F9（P1）去重指纹本身偏弱

`billkit/.../Bill.kt:31-37`：`sourcePkg|priceCents|timeMillis/60_000|orderTail`。
① 单号只留后 6 位会碰撞；② `timeMillis` 取页面时间，同一笔在不同分钟被抓到 → 指纹不同 → 去重窗口失效。
改：单号保留完整（DB 里存 `orderTail` 仍只展示后 6 位，另存 `orderFull`）；指纹的分钟桶扩展到 5 分钟桶（`timeMillis / 300_000`），碰撞概率与去重窗口的平衡更好。

### F10（P1）每轮 fork 一个 root 进程

`billkit/.../root/RootShell.kt:20` 每次 `ProcessBuilder("su","-c",command)`。
`RootWatcher.tick` 每 1.2s 至少一次（`foreground()`），**空转成本 = 每 1.2s fork 一个 root 进程**。
即使前台是桌面，`isAccepted` 也只能省掉 dump，省不掉这条 su。

改：开一次 `su` 拿 stdin/stdout 常驻，命令一行行喂 + 哨兵行判断结束；进程死了自动重开。
这是"root 耗电"最实在的优化，比调间隔有效得多。**风险**：常驻 shell 的超时/僵尸处理，需要 `RootShell` 加重连与 5s 看门狗。

### F11（P1）→ 已并入 **D6**（息屏必须停）

### F12（P1）→ 已并入 **D6**（间隔自适应）

### F13（P1）「手动记一笔」不存在，但空态文案承诺了它

`app/src/main/java/com/example/autobook/ui/BillsScreen.kt:79` 的空态文案：
「开启识别后，付款完成会自动出现确认卡；也可以在确认卡里手动记账。」
但确认卡只有识别成功后才出现，全 App 没有新增入口。

改：`BillsScreen` 的顶部主按钮 / 空态按钮 → 复用 `ConfirmSheet` 开一张空白草稿（金额空、分类空、时间 = now）。
`ConfirmSheet` 要支持 `draft: BillDraft?`（null = 新建）与 `onSave` 区分 insert/update —— 正好与 E3 共用。

### F14（P1）Debug 页没有「导出诊断」

现在只能「复制页面文本」（`app/src/main/java/com/example/autobook/ui/SettingsScreen.kt` 的 DebugPage）。
改：加一个「导出诊断」按钮，把 `DebugBus` 状态 + 全部设置 + 版本号 + 最近 10 条结论 + 页面文本拼成一段，走 `ACTION_SEND` 分享。
用户反馈问题的成本从"截图+描述"降到"点一下"。

### F15（P0）~~金额格式化依赖默认 Locale~~ ✅ 已在 0.4.4 实现

> `app/src/main/java/com/example/autobook/ui/AmountInput.kt` 的
> `internal fun formatForInput(cents: Long): String = String.format(Locale.US, "%.2f", abs(cents) / 100.0)`；
> 确认卡输入框与 `ConfirmDelivery.notify()` 的通知金额都改用它。
> `AmountInputTest` 里有「格式化后再解析回到同一个值」的往返用例。

`app/src/main/java/com/example/autobook/ui/ConfirmSheet.kt:77` 的 `"%.2f".format(draft.amountYuan)`：
在逗号小数点的 Locale 下会生成 `19,99`，回读 `toDoubleOrNull()` 失败 → 静默回退原值（用户以为"改了没生效"）。
改：`String.format(Locale.US, "%.2f", draft.amountYuan)`，与 `export/CostCsv.kt` 的 `money()` 保持一致。

---

## 八、UI / 交互 / 2026 设计语言（U 组）

> 起笔前先回答用户 m01319 的问题：**2026 年一个"完美"的 UI 到底长什么样。**
> 结论：三家的做法在收敛到同一件事——**材质与动效都必须为"可读 + 可预期"服务，任何花哨都不能抬高一秒内的信息获取成本**。

### 8.0 三家系统的共识与反共识

| | 做法 | 对我们的启示 |
|---|---|---|
| **iOS 27**（[MacRumors 2026-06](https://www.macrumors.com/2026/06/10/how-liquid-glass-is-changing-in-ios-27/)，WWDC 2026） | Liquid Glass 的**修正版**：给玻璃加**深色描边边缘 + 镜面高光**来分层、更有效地**扩散**背后内容、新增**透明度滑块**（取代二元开关）、内容滚到悬浮栏下时出**统一 toolbar** 保证文字可读 | 玻璃的敌人是"背后太花"。我们的列表内容会滚到悬浮 tab bar 下面，**必须有 scroll edge 渐变遮罩**，否则数字会压在图上看不清。这是 iOS 27 一年踩坑换来的结论。 |
| **HyperOS 4**（[ANTARA 2026-09](https://www.antaranews.com/berita/5743405/xiaomi-hadirkan-versi-beta-perdana-hyperos-4)） | **Soft Light Glass**：磨砂透明 + 多层深度 + 真实光折射；长按菜单改深色圆形图标 + 玻璃模糊；动画加弹跳；**全量玻璃渲染只在骁龙 8 Elite 上优化** | 真模糊有硬件门槛。我们 minSdk 26，**不能上全屏实时模糊**（Compose 的 `blur` 在 API<31 无效）。老老实实做"半透明 + 描边 + 高光"的等效玻璃，并把 reduce-transparency 做成用户可调。 |
| **ColorOS 17**（[OPPO 官方 2026-10](https://www.oppo.com/en/newsroom/stories/oppo-color-os-17-smooth/)） | **Living Flow**：① Luminous Effect（光参与交互，触摸处发光）② Fluid Motion（元素在状态间移动/变形，页面是连续体验）③ **Flexible Feedback**（按手势速度/强度动态调动画时长与弹性，而不是播预设） | 我们的动效现在是"点一下换色"，缺三样：**跟手**（手势驱动而非松手才动）、**连续**（共享元素）、**有弹性**。ColorOS 的"按速度调时长"是最便宜的一条，几行代码就能有质感。 |
| **Android 17**（[快科技 I/O 2026](http://m.mydrivers.com/newsview/1122224.html)） | 不照搬液态玻璃，**克制地**在通知栏/控制中心用高斯模糊；Material 3 Expressive + Gemini；生成式小组件 | 官方态度就是"克制"。我们一个记账工具，**信息密度和可读性 > 质感炫技**。 |

**反共识（别做的）**：
- ❌ 全屏实时模糊 / 液态折射 —— 中低端机直接掉帧，违背后台 root 常驻的功耗预算；
- ❌ 3D 图标、生成式 UI、AI 对话入口 —— 用户在这里只要"3 秒确认一笔账"；
- ❌ 满屏玻璃 —— iOS 27 自己都在回退，玻璃越多越难读；
- ❌ 无意义的弹跳动效 —— 记账是高频低情绪场景，弹跳会变成噪音。

**AutoBook 的坐标**：它是**工具型、每天看 3 秒、单手操作**的 App。它的"完美 UI"不取决于视觉惊艳，而取决于三件事：
1. **打开就能看到"本月花了多少 + 昨天那笔对不对"**（信息先行）；
2. **确认卡 1 秒内出现、1 秒内能确认、随时能撤销**（零摩擦）；
3. **在阳光下、在大字号下、在低端机上都不坏**（鲁棒）。

下面 U1~U10 按这个坐标排。

### U1（P0）~~触感与按压反馈：现在几乎为零~~ ✅ 已在 0.5.0 实现

> ✅ **已在 0.5.0 实现**：新增 `ui/design/Haptic.kt` 的 `class AbHaptic` + `rememberAbHaptic()`，
> 四档 `tick/select/confirm/warn` 映射到 `HapticFeedbackConstants`（API 30 以下降级，
> **不是**用 compose 的 `HapticFeedbackType` —— 旧版本没有 CONFIRM/REJECT 常量）。
> 6 处调用点全部迁移：tab/子页返回/列表行/图标按钮 → `tick()` 或 `select()`，
> `PrimaryButton` → `confirm()`。确认卡的「记一笔」「导入 iCost」「忽略」分别接了 confirm/select/warn。

现状（grep 确认）：
- `performHapticFeedback` 全项目仅 **6 处**（`ui/StatsScreen.kt` 1、`ui/design/Chrome.kt` 2、`ui/design/Components.kt` 3），只用 `TextHandleMove` / `LongPress` 两种；
- 所有 `clickable` 都写了 `indication = null`（无 ripple），只靠 `animateColorAsState` 换色。

后果：快速点按（尤其确认卡上的「记一笔」）**没有任何物理反馈**，手指会觉得"点空了"。

改：
1. `ui/design/Tokens.kt` 增 `object AbHaptic { fun tick() / confirm() / warn() / error() }`，分别映射 `TextHandleMove` / `LongPress` / `Reject`(API 30+ 用 `Context.VIBRATOR_MANAGER` 的 `VibrationEffect`) / `LongPress` 双震；
2. **确认卡「记一笔」用 confirm 档**（这是全 App 唯一该有"落袋感"的动作）；
3. `indication = null` 保留（干净），但给所有可点元素加 `Modifier.composed { ... }` 统一 `tick()`；
4. 左滑删除、警告类操作走 `warn()`。

成本：~60 行。这是"手感"里性价比最高的一条 —— iOS 27 / ColorOS 17 的质感有一半来自触感而非像素。

### U2（P0）~~玻璃质感升级：把 iOS 27 的"可读性修正"补上~~ ✅ 已在 0.5.0 实现

> ✅ **已在 0.5.0 实现**：
> ①`GlassSurface`（`ui/design/Components.kt`）加顶部 40% 高度的镜面高光（`0.14f * strength`）
> 与底边 1dp 暗线（`Black 0.10f * strength`）；
> ②滚动遮罩：最初实现为 `PageScaffold` 里一层 `BottomFade(solidBelow = true)`，
> **0.5.1 挪到 `MainActivity` 胶囊正上方 24dp**，**0.5.2 直接删除** —— 实测那条全宽渐变
> 底端 `pageBg` alpha 0.72，浅色下白卡滚过时就是一条全屏灰带（详见顶部 0.5.2 进度块）。
> 现在不加任何遮罩，可读性靠胶囊本身的玻璃质感 + 投影解决；若以后确实需要 scroll edge 效果，
> 做法应是**只在胶囊宽度内**（`padding(horizontal = 30.dp)`）画、高度 ≤12dp、最高 alpha ≤0.35；
> ③`Appearance` 的布尔 `reduce_transparency` 换成 0..1 的 `glass_strength`
> （老 key 自动迁移 `true→0`/`false→1`，默认 0.8），设置页新增**外观子页**带 `Slider`，
> 拖动即时生效（`Appearance.glassStrengthState` 是 `mutableStateOf`，`Theme` 直接 collect）。

现状 `app/src/main/java/com/example/autobook/ui/design/Components.kt:63-100` 的 `GlassSurface`：
半透明底 + 上亮下暗描边 + 10dp 投影，**没有高光层、没有底部暗边、没有滚动遮罩**。

iOS 27 恰恰是在这三处补课。改：
1. **顶部镜面高光**：叠一层 `Brush.verticalGradient(listOf(White.copy(0.14f), Transparent))`，高 40% 高度；
2. **底部暗边**：`Brush.verticalGradient(listOf(Transparent, Black.copy(0.10f)))`，让玻璃有"厚度"；
3. **Scroll edge effect（最关键）**：`ui/design/Chrome.kt:172` 的 `LiquidTabBar` 上方加 24dp 渐变遮罩（`pageBg` → `Transparent`），列表滚到底时数字不会糊在 tab bar 上；
4. **透明度滑块**：`Appearance` 已有 `reduce_transparency` 布尔，改成 0~1 的 `glass_strength`（0 = 完全不透明，1 = 最通透），设置页给 Slider —— 对应 iOS 27 用滑块取代二元开关的做法。老 key 做迁移（true → 0.2，false → 1.0）。

成本：~120 行（含设置页 Slider）。**这是"看起来像 2026"和"看起来像 2019"的分界线。**

### U3（P1）把「光效」用于状态，而不是用于装饰

ColorOS 的 Luminous Effect 是"光参与交互"。我们已经有 `accent`（青绿）与 `swatchFor()` 的稳定分类色，缺的是**状态的光**：
1. 确认卡弹出瞬间，金额下方一圈极淡 accent 光晕（`radialGradient`，alpha 0.10 → 0，300ms 淡入）；
2. 统计页 `BarRow` 的比例变化用 300ms `animateFloatAsState`（现在**完全没动画**，切换月份时柱子瞬变，观感很廉价）；
3. 开关打开时 `AbSwitch` 的 thumb 带 1px accent 描边发光（现在只有位移 + 变色）。

成本：~80 行。**注意克制**：光晕只在"确认"和"比例变化"两个语义点用，别做成霓虹灯。

### U4（P1）动效：跟手 + 连续 + 按速度自适应

现状只有 5 个文件、10 余处动画；`AbMotion`（fast 120 / normal 220 / slow 320）定义了但没统一用；无共享元素、无 `AnimatedVisibility` 进出场、无手势跟手。

改（按性价比排）：
1. **手势跟手**（已在 E2/E4 里，这里补规格）：下滑关闭确认卡时 `offsetY` 直接跟手，松手按**速度**决定关闭/回弹（阈值 120dp 或 1000px/s）；
2. **设置子页进出场**：`SettingsScreen` 现在是 `when(page)` 硬切，**零动画**。加 `AnimatedContent`（横向 slide + fade，与 tab 切换同规格 `AbMotion.normal`）；
3. **共享元素**：明细行 → 编辑卡，金额用 `SharedTransitionLayout` 从列表行"飞"到卡片顶部（compose-bom 2024.10 = Compose 1.7，支持）；
4. **按速度自适应**（ColorOS 的做法）：把 `AbMotion` 从固定时长改成 `spring(stiffness = f(手势速度))`；
5. **尊重系统"移除动画"**：读 `Settings.Global.ANIMATOR_DURATION_SCALE`，为 0 时全部时长压到 0（现在完全不读，无障碍用户开了也没用）。

成本：~200 行。**2/5 两条先做**（几十行，感知最强）。

### U5（P0）鲁棒性：大字号、无障碍、减少动画 —— 现在全线缺失

已确认的硬伤：
- **13 处固定 dp 高度**（`ui/design/Chrome.kt:172` tab bar 62dp 最典型），系统 fontScale 1.3~2.0 时文字被裁；
- `contentDescription` 全项目仅 **7 处**，绝大多数图标按钮对读屏是"未标记按钮"；
- **`ANIMATOR_DURATION_SCALE` 零读取**；
- `AbType` 全部写死 `sp`，没有 fontScale 钳制（记账 App 的金额爆版会直接破布局）；
- 无 `WindowSizeClass` / `widthIn`（折叠屏、平板上是一根拉到边的内容）。

改：
1. `height(AbSize.*)` → `heightIn(min = ...)` + 内部 `padding`，让内容撑开；
2. 所有 `ImageVector` 图标补 `contentDescription`（装饰性图标显式 `null`）；
3. `AbType` 加 `fun clamp(fontScale: Float) = coerceAtMost(1.3f)`，在 `AutoBookTheme` 里用 `CompositionLocalProvider(LocalDensity provides ...)` 钳制金额类样式；
4. 内容区加 `widthIn(max = 560.dp)` 居中（平板/折叠屏展开时）。
5. **新增「外观」设置子页**：主题（跟随系统 / 浅色 / 深色）、玻璃强度（U2 的滑块）、字号（标准 / 大 / 特大）、降低动效开关。

成本：~180 行。**这是一条"看起来不性感但决定专业度"的项**：iOS 27 的发布说明里，无障碍适配和视觉改造是并列的一等公民。

### U6（P1）图标：加 monochrome 层，跟 2026 的主题化图标对齐

`app/src/main/res/mipmap-anydpi-v26/` 只有 `ic_launcher.xml` / `ic_launcher_round.xml`，**没有 monochrome 层**，Android 13+ 的主题化图标会显示成一个白方块底。
改：`ic_launcher.xml` 加 `<monochrome android:drawable="@drawable/ic_launcher_foreground"/>`；顺带做一版跟随壁纸取色的 accent（只让 accent 跟随，分类色仍用 `swatchFor()` 的稳定 hash —— 否则同一分类每月换色，用户会认不出来）。

成本：~30 行（XML）。

### U7（P1）信息层级：首页缺"一件事"

现状 `app/src/main/java/com/example/autobook/ui/HomeScreen.kt`：月度概览卡 + 待确认区 + 最近 8 条 + 快捷入口 + 悬浮 tab bar，五块并列，**没有"最重要的一件事"**。
2026 三家的共同做法是**内容先行、chrome 退让**（iOS 27 的 scroll edge、Android 17 的克制）。

改：
1. 月度卡作为唯一大卡（保留 `amountHero` 44sp tabular）；
2. 「最近记录」的每条**次级行砍掉一半信息**：现在是 `分类 · 二级 · 账户 · HH:mm` 四段拼接，改成 `分类·二级` + 右侧时间，账户只在详情里看；
3. 「识别与权限」快捷入口从首页**移到设置**（已有），首页只保留首次使用的引导；
4. 待确认区（Pending）优先级提到月度卡**之上** —— 有待确认的账才是用户此刻最该处理的事。

成本：~100 行。

### U8（P2）空态与首次体验

- 空态文案修掉（见 F13，要么真加手动入口，要么改文案）；
- 首次启动三步引导：①开 root 模式 → ②给悬浮窗权限 → ③识别一笔试试（每步一个卡片 + "已完成"状态，全部完成自动隐藏）；
- 这个引导也是 F7 权限横幅的落点。

成本：~150 行。

### U9（P2）轻量系统集成：小组件 / 快捷方式 / 磁贴

2026 的系统都在强调"从桌面直达"。我们零 `AppWidget`、零 `Shortcut`、零 `TileService`。
低成本三件套：
1. **长按图标 → 「记一笔」**（`shortcuts.xml`，静态即可，指向 `ConfirmActivity` 的空草稿）；
2. **快捷设置磁贴**：开关识别（`TileService`，~40 行）；
3. **桌面小组件**：本月支出 + 「记一笔」（`GlanceAppWidget`？**需加依赖**，按 AGENTS.md 不加依赖 → 用手写 `RemoteViews` 的经典 `AppWidgetProvider`）。

**注意**：这三项都要发版 + 用户手动添加，收益不如 U1/U2 立竿见影，放最后。

### U10（P2）视觉一致性细节清单

- `ui/SummaryCard.kt` 用 `LocalDate.now()` 算月份，**不能翻月**（与 E4 一起做）；
- `ui/design/BillRow.kt` 的 `SwipeDeleteBackground` 只有红底白字，缺左滑过程中的**渐进揭示**（现在是滑过才看到）；
- 深色模式下 `DarkPalette.pageBg = 0xFF000000` 纯黑，OLED 上玻璃描边几乎不可见 —— 改成 `0xFF0B0B0C`；
- 选中态建议统一用 `accent.copy(alpha = 0.13f)`（现在 tab / chip / 列表行三处 alpha 不完全一致）。

### 8.9 UI 批次小结

| 优先级 | 项 | 一句话 |
|---|---|---|
| **✅ 已做（0.5.0）** | U1 触感、U2 玻璃+滚动遮罩+滑块 | 手感与"2026 感"，两三百行，风险低 |
| **再做（0.6.0/0.6.1）** | U4 动效（跟手+子页转场）、U5 鲁棒性、U3 光效、U6 图标 | 决定专业度 |
| **最后（0.7.0）** | U7 信息层级、U8 引导、U9 系统集成、U10 细节 | 锦上添花 |

---

## 排期建议

| 批次 | 版本 | 内容 | 预估 |
|---|---|---|---|
| 第 1 批（先做） | 0.4.3 ✅ 已交付 | E1 侧滑返回、D1 重复 dumpsys、D5 root 常驻 | — |
| 第 1.5 批 | **0.4.4** ✅ 已交付（部分） | **F1 金额少 1 分、F15 Locale、F4 转账统计、F2 重启重复入库、F7 权限申请、F5 截断标记、F6 无障碍 OCR 兜底**（7 条纯 bug）+ **D6 第 1/2 条（熄屏长睡 + 空闲退避）** | — |
| 第 2 批 | **0.5.0** ✅ 已交付 | **A1 GENERIC_BILL、B1 样本导出、C1 导出范围、C2 JSON 备份恢复、E2 确认卡下滑动画、U1 触感、U2 玻璃高光/滚动遮罩/强度滑块** | — |
| 第 2.5 批 | **0.5.1** ✅ 已交付 | 用户 m02113 报的底部 dock 缺陷（删 `BottomFade` / Tab 栏只由 `MainActivity` 画 / `enableEdgeToEdge` 去 scrim / 子页补导航条 inset） | — |
| 第 2.6 批 | **0.5.2** ✅ 已交付 | 用户 m02284 报的残留横带（删掉胶囊上方那条全宽 24dp 渐变；模拟器逐像素复测确认无横带） | — |
| 第 2.7 批 | **0.5.3** ✅ 已交付 | 用户 m02520 报的横带真正根因（`Modifier.padding(padding)` 削掉 LazyColumn 视口 → 改走 `contentPadding`，内容从胶囊底下穿过） | — |
| 第 2.9 批 | **0.5.5** ✅ 已交付 | 用户 m03982：玻璃强度真的生效（连续插值）、Tab 选中块滑动+挤压回弹的灰色胶囊、图标放大变色、胶囊外圈投影（容器 14dp `shadowPad`）、主色跟随 Material You（m04265） | — |
| 第 2.8 批 | **0.5.4** ✅ 已交付 | 用户 m02775 报的「假玻璃」：接 AndroidLiquidGlassView（真折射 + 色散），`MainActivity` 改手搭 FrameLayout 让玻璃采样兄弟节点，API<33 回退假玻璃 | — |
| 第 3 批 | 0.6.0 | **B0 规则引擎**、B5 早午晚餐（用户点名）、B6 商品名、B7 本地学习、C3 钱迹模板、C6 多选导出、C7 定期提醒、**F3 退款冲减、F13 手动记一笔** | 3 天 |
| 第 4 批 | 0.6.1 | A2 打分阈值、B2 JSON 规则、C4 CSV 导入、E3 详情编辑+撤销、E4 滑动切月、D2 去抖分级、D7 无障碍降级、**F14 导出诊断、U4 动效、U5 鲁棒性+外观页** | 3~4 天 |
| 第 5 批 | 0.7.0 | A3 金额消歧、A4 字号加权、B4 覆盖率自检、B8 模板扩展、D3/D4、C5、**F9 指纹加固、F10 常驻 su、F8 backup 规则、U3 光效、U6 图标、U7~U10** | 3~4 天 |

> 已提前完成：**D1 / D5 / E1**（原排 0.4.3+，实际 0.4.3 已交付）；
> **F1 / F2 / F4 / F5 / F6 / F7 / F15 + D6 前两条**（原排 0.4.4~0.6.0，实际 0.4.4 已交付）。
> 0.4.4 剩下的 **A1 / B1 / C1 / C2 / E2 / U1 / U2** 已在 **0.5.0 全部交付**（versionCode 9，111 个单测）。
> 排序说明：
> - **F1 / F15 / F4 提到最前**——都是一行~十几行的纯 bug（改金额少 1 分、逗号 Locale、转账在统计页消失），
>   改动小、风险低、用户每天都可能踩到，没必要排在功能后面；
> - **D6 提到 D2 前面**——用户已把 root 当主力，耗电是可感知缺陷，比多省 400ms 更重要；
> - **B0 排在 B2/B3 前面**——它决定规则存在哪，不先定会让后面三项各写一套；
> - **U1/U2 与 F 组同批**——都是"手感和观感"，加起来不到 400 行，和 0.4.4 的功能项互不冲突。

## 统一验收清单

1. `./gradlew :app:testDebugUnitTest` 全绿（当前 **111**，B0+B5+B6+B7 落地后应 ≥ 125）；
2. 四个 tab、十个设置子页逐个侧滑返回，均不退出 App；
3. 支付宝 `com.tentent.mm` 详情页：进入后 < 800ms 弹出确认卡，来源显示正确；
4. 任意页面左滑删账后 5s 内可撤销；
5. 备份 → 卸载重装 → 恢复，账单条数与分类/账户清单完全一致；
6. **12:30 识别一笔"沙县小吃 19 元"→ 自动选中「餐饮/午餐」；把时段改到 11:30 起，同一笔变成「餐饮/早午餐」**；
7. **导出的钱迹 CSV 用官方模板列序、`yyyy/M/d H:mm` 日期、带 BOM，能被钱迹「导入到钱迹」识别**；
8. **列表勾选 3 条导出，CSV 恰好 3 行数据**；
9. **设每分钟触发一次的提醒 → 通知出现 → 点「立即导入」能到选择器（或 iCost）；重启手机后提醒仍生效**；
10. **熄屏一小时后看电池详情：AutoBook 耗电占比应 < 1%（改造前会明显偏高）**；
11. **在确认卡里把 19.99 改成 19.99 再保存 → 入库 1999 分（不是 1998）**；
12. **把系统语言切成小数点为逗号的 Locale（如德语）→ 确认卡金额仍显示 `19.99` 并能正确回读**；
13. **统计页能看到「转账」这一块（或明确说明不计入收支），不会看起来像丢账**；
14. **停在账单详情页 → 强制停止 App → 重开 → 不产生重复记录**；
15. **首次启动的引导能跑通（缺通知/悬浮窗权限时有横幅指向设置），通知权限拒绝后结论为 `pending-no-notify`**（0.4.4 实际返回值；横幅仍在待办）；
16. **系统字号调到最大（fontScale 2.0）→ 四个 tab、确认卡、统计页文字不裁切**；
17. **系统设置里「移除动画」打开 → App 内不再有位移/缩放动画**；
18. **列表滚到底时，最下面一行数字不会被悬浮 tab bar 糊住**（滚动遮罩生效）；
19. **陌生包（如 `com.tentent.mm`）识别出的结论行来源显示 `GENERIC_BILL`，不再写成支付宝**；
20. **导出页选「上月」→ 条数等于明细页上月条数；选「全部」→ 等于总条数**；
21. **备份到文件 → 卸载重装 → 合并恢复：条数一致且没有重复；再点「改为覆盖恢复」→ 条数正好等于备份里条数**；
22. **Debug 页点「复制为 Kotlin 夹具」→ 粘贴进 Samples.kt 不报错（`$` 已转义）**；
23. **确认卡向下拖抓手 → 跟手；滑过约四分之一屏高松手 → 关闭；拖一点点松手 → 弹回**；
24. **设置 → 外观 → 把玻璃强度拖到 0：tab 栏与卡片变实色；拖回 80%：恢复半透明（无需重启）**；
25. **「记一笔」的震动比 tab 切换更重（能感觉出来）**。
26. **四个 tab 的胶囊下方没有任何实色横带，滚动内容从胶囊背后穿过去（0.5.1）**；
27. **列表滚到底部时是渐变淡出，不是被一条实色横带硬切**；
28. **设置各子页滚到底，最后一行（如「恢复默认清单」）不被胶囊压住**；
29. **三键导航栏机型上，导航栏区域不出现系统灰色 scrim**。
30. **四个 tab 滚到底时，胶囊正上方不再出现横贯全宽的灰色带（浅色、深色都看一遍）（0.5.2）**；
31. **滚到末尾的内容不再被任何半透明条压暗，只有胶囊本身的玻璃与投影**；
32. **手势导航与三键导航都不出现系统灰 scrim**；
33. **设置各子页最后一行不被胶囊压住（0.5.1 已修，0.5.2 回归确认）**。
34. **首页与设置页**：滚动时卡片从胶囊后面穿过去，胶囊正下方直到屏幕底都是内容而不是整片 `pageBg`（0.5.3 根因：`Modifier.padding(padding)` 削了 `LazyColumn` 视口）；
35. **首页与设置页滚到底**：最后一行完整可见、不被胶囊压住；
36. **浅色 / 深色各看一遍**：都无横贯全宽的色带；
37. **手势导航与三键导航**：两种模式都正常，导航条区域无异常底色。
39. **系统主色跟随**（0.5.5）：改系统壁纸后重进 App，金额、开关、选中 tab、主按钮都换成系统的 Material You 强调色（模拟器上应为 `#7A90C8` 蓝），中性底色不变；
38. **胶囊边缘的折射**（0.5.4）：把白卡或深色内容滚到胶囊后面，胶囊左右两端能看到背后的卡片边缘被「掰弯」/错位几像素，滚动时这个形变跟着动（不是一张静态贴图）；
39. **色散**：胶囊边缘有轻微彩虹边（R/G/B 分离），不是均匀的单色玻璃；
40. **触摸穿透**：玻璃层不吃触摸 —— 点胶囊边缘外侧的内容仍能点到，切 tab 仍正常；
41. **API<33 回退**：Android 12 及以下装上去必须是原来的半透明胶囊（不能白屏、不能整屏点不动）；用 `adb shell settings put` 或改 `supportsLiquidGlass()` 返回值即可在模拟器上验证；
42. **玻璃强度**：设置 → 外观把强度拖到 0，胶囊立刻变实色、色散消失（不用重启）。
