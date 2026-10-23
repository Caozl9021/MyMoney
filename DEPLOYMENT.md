# AutoBook 构建与部署

当前候选 **0.6.8 / versionCode 27（待用户验收）**，最低 API 26，compile/target SDK 35。[最新验证记录](runs/20261023-092336-brand-jpg-wechat-release/outputs/verification.md) 已记录候选包的测试与资源核查；下列命令用于复现，不代表本次文档更新重新执行了构建。

## 环境与直接构建

使用 JDK 17、Gradle wrapper 8.14 和 Android SDK 35/build-tools 35.0.0。当前工作机的环境脚本会设置 JDK、SDK、PATH 和 Gradle 缓存；其他机器应配置等价环境，并修改 `local.properties` 的 `sdk.dir`。

```bash
source "$HOME/opt/autobook-env.sh"
cd "$HOME/Projects/AutoBook"
# 缓存目录需可写；如需自定义，可在 source 后设置：
# export GRADLE_USER_HOME="/path/to/writable/gradle-home"
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

先运行单测，再打包。新缓存目录可能需要重新下载依赖；仅在缓存完整时才使用 `--offline`。构建时长取决于机器与缓存，不保证固定耗时。

| 构建 | 默认输出 |
|---|---|
| debug | `app/build/outputs/apk/debug/app-debug.apk`，Android Debug 证书签名 |
| release，有外置签名配置 | `app/build/outputs/apk/release/app-release.apk` |
| release，无签名配置 | `app/build/outputs/apk/release/app-release-unsigned.apk`，不能直接作为签名升级包 |

`app/build.gradle.kts` 从仓库外的 `$HOME/.autobook-keys/keystore.properties` 读取 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。密钥与凭据须独立备份，不放进仓库、文档或日志。保持签名证书一致才能覆盖升级并保留原有数据。

## 统一脚本的实际行为

```bash
source "$HOME/opt/autobook-env.sh"
cd "$HOME/Projects/AutoBook"
bash build-all.sh
```

`build-all.sh` 从 Gradle 配置读版本，加载用户环境，依次执行 `clean`、单测、debug 和 release 构建，再复制到 `dist/AutoBook-<版本>-{debug,release}.apk`。它要求签名 release 的 `app-release.apk` 存在；缺少签名配置时不会自动挑选 unsigned 包。已有同版本 dist 文件会被覆盖，需保留的候选应先另存快照。

脚本复制后使用 SDK build-tools 35.0.0 的 `aapt2` 核对版本；找不到该工具时会警告并跳过，不代表版本核查已通过。脚本不核对签名、哈希或图标资源，也不自动建立完整 run 证据。

当前脚本仍固定探测 `$HOME/.gradle` 可写性；不可写时尝试临时缓存并覆盖 `GRADLE_USER_HOME`，不能保证缓存复制完整，也不能保证保留调用者自定义目录。需要精确控制缓存时，使用上一节直接 Gradle 命令。

## 当前候选包与安装

| 文件 | 大小（字节） | SHA-256 |
|---|---:|---|
| [debug](dist/AutoBook-0.6.8-debug.apk) | 54,951,514 | `521a26ad057b0ffc8f33f296ea3e50fec0547ba5afe07ed4775d828c19d6d8b7` |
| [release](dist/AutoBook-0.6.8-release.apk) | 45,925,556 | `4d34c9fa47a9450e2b9679ef898e2997a8f1bd483097408e385875cd278c32aa` |

来源：[包核查 JSON](runs/20261023-092336-brand-jpg-wechat-release/outputs/apk-verification.json)。两包版本均为 0.6.8/27 且签名核查通过；release 证书与 0.6.7 一致。

```bash
source "$HOME/opt/autobook-env.sh"
cd "$HOME/Projects/AutoBook"
adb devices
adb install -r dist/AutoBook-0.6.8-release.apk
```

如连接多台设备，为命令添加 `adb -s <serial>`。签名冲突时先确认设备现有包与候选证书；不要把卸载应用或清除启动器数据作为常规升级步骤，以免丢失账本或桌面布局。图标显示异常时先核对安装版本、图标资源及启动器刷新情况。

首次启动选择无障碍或 Root 模式，并按首页权限引导启用识别。无障碍模式不执行 Root 截图 OCR；Root 模式不消费无障碍事件。确认卡优先悬浮窗，权限或后台启动限制可能使其进入待确认与通知兜底。

## 独立核查与诊断

```bash
source "$HOME/opt/autobook-env.sh"
cd "$HOME/Projects/AutoBook"
"$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging dist/AutoBook-0.6.8-release.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs dist/AutoBook-0.6.8-release.apk
sha256sum dist/AutoBook-0.6.8-debug.apk dist/AutoBook-0.6.8-release.apk
adb shell dumpsys activity services com.example.autobook
adb logcat | rg 'AutoBook|AB-Guardian|Xposed'
```

LSP 是主 APK 内置的可选功能，详见 [模块说明](docs/LSP_MODULE.md) 与 [作用域](docs/LSP_SCOPE_CONFIG.md)。无障碍守护只能巡检异常并提醒，电池豁免/厂商自启动须用户操作，不能承诺永不被杀或被杀后恢复。

## 验证与交付边界

0.6.8 有 221 项单测/24 类、双构建、版本/签名及每包 26 项品牌资源核查。API35 当前目视只覆盖确认卡、QS 磁贴和已放置 widget；旧 UI 操作证据见 [测试报告](TEST_REPORT.md)。真实微信付款成功页的瞬时识别/悬浮确认、通知实际显示、LSP 拦截、厂商保活与低 API 设备兼容仍待验证。

复现运行应建立独立 `runs/<时间>-<标签>/`，保存 manifest、日志、测试结果和设备证据；稳定交付在 `deliverables/` 记录来源与摘要。截图转录只能作为规则样本，不能当实际节点或付款事件证据。用户明确验收前状态保持“待验收”。后续 [plan1.0](docs/PLAN_1.0.md) 新功能尚未实施。
