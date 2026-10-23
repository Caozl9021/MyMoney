# AGENTS.md — AutoBook 开发与交接约定

更新：2026-10-23。当前App **0.7.0 / 29，已验收（2026-10-23用户验收通过）**（plan1.0批次1紧凑确认卡）；0.6.9/28（批次0：F9/F16/F17）已经用户真机验收。最新计划为 [plan1.0](docs/PLAN_1.0.md)，批次2起尚未实施。版本事实与证据见 [PROGRESS](docs/PROGRESS.md)，用户操作见 [README](README.md)，构建安装见 [DEPLOYMENT](DEPLOYMENT.md)。本文件替代旧版堆叠补遗，旧版本细节保留在进度、CHANGELOG与run证据中。

## 1. 产品与边界

Android本地自动记账：读取用户当前支付/购物页面文本，经规则生成草稿，用户确认后存本地账本，可导出iCost CSV。

- 不复用第三方App代码或资源；不读写第三方私有数据库，不Hook其Flutter/Dart层，不自动点它的导入确认，不绕会员。
- 识别跳过可编辑节点；不申请按键过滤、跨窗口读取或无障碍截图能力。Root OCR仅在明确Root模式使用。
- 无网络服务及云端学习；规则、账单、学习和备份本地处理。样本/诊断导出先考虑脱敏。
- Root和无障碍互斥，首次选择并保存`capture_mode`；总开关独立，暂停不清模式。不要恢复“两路同时采集”的旧方案。
- 系统及第三方兼容性必须以实测说明，不承诺不会被杀、固定弹框时延或外部导入一定完成。

## 2. 规划、委派与验收

需要规划实施的工作先读已有OpenSpec，更新同一变更而非重复建计划。在`openspec/changes/<id>/`维护proposal.md、design.md、tasks.md及`specs/<capability>/spec.md`，列需求、场景、可观察验收与证据。

主代理形成完整计划后，按用户指定配置委派实施：**gpt-6.1-sol，medium**，说明任务ID、可修改文件及验证交付物；不可静默换配置。主代理复核实际改动和证据，缺项补齐后交用户。用户明确接受前始终“待验收”，测试通过不代表验收通过。当前OpenSpec CLI不可用时手工检查结构并披露；若环境已有CLI则按其约定校验。

普通文档更新不触发App版本递增或出包。功能/UI正式交付按实际范围更新versionName并递增versionCode；先相应单测再构建，包置`dist/AutoBook-<version>-<debug|release>.apk`，核对包内版本、签名及摘要，保留历史交付。

## 3. 工程与关键入口

模块仅`:app`依赖`:billkit`，不存在`:xposed-module`；LSP内置主APK。识别核心不依赖数据库、不联网，OCR具体实现由app注入。

| 区域 | 入口与职责 |
|---|---|
| billkit | `BillKit.kt`接收`UiSnapshot`，节流/去重/诊断；`PageClassifier`→`AmountParser`→`FieldExtractor`→`BillPipeline`；`TextCollector`采集并报告截断 |
| app装配 | `App.kt`装配规则推荐/备注/投递；`CaptureModeManager`统一模式、总开关和起停 |
| 采集 | `capture/BillAccessibilityService`和`AccessibilityCaptureSchedule`；Root路径`root/RootForegroundService`→`RootWatcher` |
| 确认投递 | `capture/ConfirmDelivery`优先`overlay/ConfirmOverlay`，Activity兜底，失败保留待确认并通知；`ui/ConfirmSheet`共用UI；只有「忽略」会丢弃草稿，dismiss（下滑/点空白/返回/熄屏）放回待确认；备注建议由`data/NoteSuggester`本地计算 |
| 存储 | `data/Bill.kt` Room v2，保留MIGRATION_1_2；`BillWriter`唯一写入/更新与落库前查重；`PendingStore`最多20条草稿，不是识别历史 |
| 本地规则 | `LedgerConfig`、`CategorySuggester`及规则相关文件，独立分类/账户来源、JSON覆盖、餐段、手动与频次学习；用例见本地规则文档 |
| 备份/导出 | `Backup`/`BackupStore`、`MiniJson`，iCost `export/CostCsv`/`CostShare`/`ExportRange` |
| UI | `ui/MainActivity`宿主、四Tab和设置子页；`ui/design`统一token、字体、图标、组件、玻璃dock；`systems`含磁贴/widget/shortcut |
| LSP | `assets/xposed_init`→`xposed/AutoBookXposedModule`，只处理系统android；详情见LSP专题 |

路径以`app/src/main/java/com/example/autobook/`或`billkit/src/main/java/com/example/billkit/`为根。

## 4. 当前能力与待办

- B0–B5/B7已实现，B3并入B7；手改成功入库立即学，人工接受同一推荐3次后学，可逐条删除；关闭学习暂停既有推荐。餐段默认05:00–10:30早餐、10:30–14:30午餐、16:30–21:00晚餐、21:00–05:00夜宵，14:30–16:30三餐。
- U系列主体已实现：主题/字号/减少动效、宽屏/大字号、首页模式和待确认引导、完整月份查询、系统入口。保留当前**60dp高、36dp侧距、图标21/23dp、标签11sp**的轻透dock；无玻璃强度滑块和全宽滚动遮罩。API33+真玻璃，低API回退半透明胶囊。
- 图稿唯一当前来源`Auto.jpg`，`generate-icons.sh`生成26张资源；彩色保留四彩边框和封闭白色，单色使用独立负空间，adaptive54/108dp、legacy55%。先`--dry-run`可查看输出，原图不改。
- `Settings.autoConfirm`实际控制自动弹确认卡，不是自动入库；关闭后进入待确认队列。
- iCost单笔当前先本地保存再分享，外部导入状态不可等同本地入库；失败提示存在边界，见导出专题。
- 退款为独立REFUND；结余=收入−原支出+退款，转账不算收支；iCost模板不支持退款，含退款明确阻止导出。禁止回写历史收入假装退款已迁移。
- 无障碍窗口120ms，内容600ms且最长等待1秒，根不可用只重试一次400ms；这些是策略期限而非实测弹框总时延。微信极简成功页已有严格规则/转录回归，实际付款瞬间采集与投递仍需真机验证。
- 无障碍守护30秒巡检，DISABLED连续2次、UNBOUND连续4次提醒；不能阻止LMK或自动修复授权。LSP有四种候选Hook签名，killApplication重载为兼容回退，不应称四处始终同时注册，无PowerManagerService Hook。
- 紧凑确认卡、排序、备注气泡已在0.7.0实施并经用户验收；分类长按手势、周月图表、识别历史、账单操作页为**plan1.0待做**。旧待办逐项状态只维护plan1.0，避免重复漂移。

## 5. 环境、测试与签名

当前本机用户级环境：JDK17 `~/opt/jdk-17.0.20.1+1`，Gradle8.14，SDK `~/Android/Sdk`，AGP8.7/Kotlin2.0.21，compile/target35，min26。

```bash
source ~/opt/autobook-env.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

缓存不可写时选可写`GRADLE_USER_HOME`且准备依赖；离线缓存完整才加`--offline`，本次已验证缓存为`/tmp/autobook-icon-padding-gradle`，它是临时路径，不保证未来存在。不要把本机路径写进业务源码。`local.properties`的sdk.dir随机器调整。

签名配置由Gradle从`~/.autobook-keys/keystore.properties`读取；缺失时release为unsigned，不能误交付。备份外部密钥及配置但不要把口令写进文档/日志/版本库。release证书与历史一致才能覆盖安装；debug与release不同签名，不能直接互相覆盖，避免卸载丢数据。

最新237单测/27类全绿、双构建、签名/版本及API35批次1交互证据：`runs/20261023-150000-compact-confirm/`（批次0回放见`runs/20261023-140000-batch0-fix/`，0.6.8资源见`runs/20261023-092336-brand-jpg-wechat-release/`）。`BillKit`同页记忆返回`skip:same-page`，排查漏报时它代表“同一屏已经出过草稿”，不是规则未命中。完整TalkBack、低API、锁屏安全、Root功耗/启动恢复、LSP真机拦截和真实微信支付尚有证据缺口，见TEST_REPORT。

## 6. 易错点与修改约束

1. 金额以分存Long；输入两位小数用正确四舍五入，固定Locale.US，19.99不可变1998分。失败、取消、进行中不得入账。
2. `org.json`在JVM测试会遇Android空桩；需要纯JVM验证的备份/规则编解码沿用`MiniJson`等已有可测实现。
3. 商户标签前缀必须校验分隔符，`收款方备注`不能被误读成商户“备注”。微信详情负金额可无币种符号。
4. KDoc中避免无意写入嵌套块注释起始符；Kotlin支持嵌套注释，容易产生Unclosed comment。
5. Android11+定向分享需要Manifest queries包可见性；iCost包`com.gostraight.smallAccountBook.fltandroid`。分类/账户名需与目标App一致，外部导入结果由目标App和用户确认。
6. 编辑保留原实体ID/创建时间，新增有独立身份；成功落库才学习。删除每笔5秒撤销；恢复、自动确认及历史编辑不可乱累计学习频次。
7. 玻璃缓存CLEAR仅作用独立位图，避免挖穿内容；图标位置/点击区域共用网格。保留Tab状态、语义及重复点击不震动。
8. 不将缺字段补成猜测事实；截图可见文本不是实际无障碍节点dump。调试样本与真实采集来源要标清。

## 7. 故障定位与迁移

诊断看来源、包名/activity、截断、pipeline结论和`→ overlay/confirm/pending*`投递后缀。`not-a-bill-page`排分类，`no-amount`排金额，`duplicate/throttled`看上下文而非直接取消去重。付款成功漏报时先保存该页诊断样本，再进详情，避免覆盖最近状态。无完整识别历史，不承诺过去草稿可恢复。

迁移保留源码/未提交修改、Auto.jpg、原始参考、runs、deliverables、外部签名和本地AAR `app/libs/liquidglass-1.1.0.aar`；build及缓存可重建。更新sdk.dir和JDK17环境；装包后按所选模式授权，无障碍模式不要求Root。JSON备份恢复需验证账单/配置，不依赖allowBackup（当前关闭）。

项目遵守用户的工作区布局：已有目录不强迁移；每次运行独立`runs/YYYYMMDD-HHMMSS-标签/manifest.json`记录输入/环境/提交/状态，稳定交付放`deliverables/<版本>/`并记录来源/摘要。无真实Git仓库时提交填null，不编造SHA。清理先列候选，不删唯一资料或仍有复用依赖；旧run/deliverables不当临时文件删除。
