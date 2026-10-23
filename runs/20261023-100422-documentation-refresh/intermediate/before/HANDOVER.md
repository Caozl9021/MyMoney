# AutoBook v0.6.4 项目交接文档

## 📋 项目概述

AutoBook 是一个 Android 自动化应用，主要功能包括：
- 自动记账（支付宝、微信等）
- 无障碍服务自动化
- LSPosed 模块增强（保活功能）

## 🎯 本次更新内容（v0.6.2）

### 1. 新应用图标（0.6.4 重建）
- **设计**：彩色圆环 + 白色 ¥ 符号，白色背景
- **状态**：✅ 已完成（0.6.4 才真正生效）
- **位置**：`app/src/main/res/mipmap-*/` 三套 —— `ic_launcher.png` / `ic_launcher_round.png` / **`ic_launcher_foreground.png`**
- **验证**：已打包到 APK 中，五档前景层字节数与源文件逐一对应
- 〔0.6.4 订正〕0.6.2/0.6.3 的图标其实没换成功：`mipmap-anydpi-v26/ic_launcher.xml` 的 `<foreground>`
  指向 `@mipmap/ic_launcher_foreground`，API 26+ 桌面只显示这一层，而当时两个生成脚本都不生成它，
  新图只写进了 legacy 的 `ic_launcher.png` / `ic_launcher_round.png`。0.6.4 删掉全部 15 个旧 PNG 重建，
  `generate-icons.sh` 现在一次生成 legacy + 前景层 + 背景色并自带校验，`regenerate-icons.sh` 已删除

### 2. LSPosed 增强模块
- **功能**：Hook 系统服务，保持无障碍服务激活状态
- **状态**：✅ 代码已实现，待真机验证
- **核心文件**：
  - `app/src/main/java/com/example/autobook/xposed/AutoBookXposedModule.kt`
  - `app/src/main/assets/xposed_init`
  - AndroidManifest.xml 中的 Xposed 模块声明

## 📦 构建产物

### APK 文件
```
dist/
├── AutoBook-0.6.4-debug.apk      # 55MB（54,732,457 B），Android Debug 证书
├── AutoBook-0.6.4-release.apk    # 46MB（45,812,545 B），正式签名（versionCode 23）
├── AutoBook-0.6.3-debug.apk      # 历史版本，保留对照
└── AutoBook-0.6.3-release.apk    # 历史版本，保留对照
```

### 构建命令
```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease

# 构建后自动复制到 dist/
```

### 签名配置
- 位置：`~/.autobook-keys/keystore.properties`（**仓库外**，不进版本库；〔核查修正〕早期文档写的 `app/keystore.properties` 是错的）
- Keystore：`~/.autobook-keys/autobook.jks`（alias `autobook`，自签 100 年）
- 密码：见 `~/.autobook-keys/keystore.properties`
- 文件缺失时 release 不签名，产出 `-unsigned.apk`
- 当前 release 证书 SHA-256：`bedb36b7527aa4759262d8b737d7031b57f1db191c68eab4d55829f0598a5ed6`（0.5.9 / 0.6.0 / 0.6.1 / 0.6.2 / 0.6.3 同一张证书）

## 🔍 已验证功能

### ✅ 基础功能（模拟器测试）
1. **应用安装**：可在 Android 15 模拟器上正常安装
2. **应用启动**：主 Activity 正常启动
3. **图标打包**：新图标已包含在 APK 中（debug 包 17 个 mipmap 条目：2 个 `mipmap-anydpi-v26/*.xml` + 5 档 × `ic_launcher`/`ic_launcher_round`/`ic_launcher_foreground`；release 包资源被 R8 重命名，badging 显示 `application-icon-120:'res/BW.xml'`，无法按名字 grep）
4. **LSP 配置**：xposed_init 文件正确配置（48 字节，内容 `com.example.autobook.xposed.AutoBookXposedModule`；release `classes.dex` 中该类命中 6 次）
5. **单测**：149 例 / 16 类全绿（2026-10-22 14:51 与 14:56 `--rerun-tasks` 各跑一次，均 149/0/0/0）

〔核查修正〕**以上"模拟器测试"是历次版本累积的记录，不是 0.6.2 专属**。0.6.2 这一轮**没有连过任何设备**：
`runs/20261022-145102-doc-audit/` 只有单测日志与产物元数据，`outputs/` 为空。

### ⚠️ 待真机验证功能
1. **图标显示**：需在真实设备上确认启动器图标更新
2. **LSP Hook**：需要已安装 LSPosed 的设备验证保活效果

## 🛠️ 开发环境

### 工具版本
- **Gradle**：8.14
- **JDK**：17.0.20.1
- **Android SDK**：API 35 (Android 15)
- **Kotlin**：2.0.21（〔核查修正〕早期写的 1.9.0 是错的，见根 `build.gradle.kts:3-4`）
- **AGP**：8.7.0（〔核查修正〕早期写的 8.7.3 是错的，见根 `build.gradle.kts:2`）

### 模拟器
- **启动脚本**：`~/opt/start-emulator.sh`
- **设备列表**：
  - autobook_api35 (Android 15)
  - autobook_api37 (未来版本)

### 依赖项
```gradle
// LSPosed API
compileOnly 'de.robv.android.xposed:api:82'
compileOnly 'de.robv.android.xposed:api:82:sources'

// 其他核心依赖见 app/build.gradle.kts
```

## 📱 真机测试指南

### 前置要求
1. **设备要求**：
   - Android 12+ （推荐小米设备 + 澎湃 OS 4）
   - 已 Root（Magisk 或 KernelSU）
   - 已安装 LSPosed 框架

2. **测试设备连接**：
   ```bash
   adb devices
   ```

### 测试步骤

#### 1. 图标验证
```bash
# 完全卸载旧版本
adb uninstall com.example.autobook

# 清除启动器缓存（小米示例）
adb shell pm clear com.miui.home

# 安装新版本
adb install dist/AutoBook-0.6.4-release.apk

# 检查启动器图标是否为新图标
```

**预期结果**：启动器显示彩色圆环 + ¥ 符号的新图标

#### 2. LSP 模块验证
```bash
# 1. 安装 APK
adb install dist/AutoBook-0.6.4-release.apk

# 2. 在设备上操作：
#    - 打开 LSPosed 管理器
#    - 启用 "AutoBook" 模块
#    - 设置作用域：**只勾 "系统框架 (android)"**（〔核查修正〕原文写"系统框架、设置等系统应用"是错的，模块只处理 packageName == "android"）
#    - 软重启系统框架

# 3. 启用 AutoBook 的无障碍服务

# 4. 查看日志
adb logcat -c  # 清除旧日志
adb logcat | grep -E "(AutoBook|Xposed)"
```

**预期日志**（〔核查修正〕按 `AutoBookXposedModule.kt` 实际文案重写，TAG = `AutoBook-Xposed`）：

```
[AutoBook-Xposed] 开始 Hook 系统框架
[AutoBook-Xposed] 系统框架 Hook 完成
[AutoBook-Xposed] AccessibilityManagerService Hook 完成
[AutoBook-Xposed] ActivityManagerService Hook 完成
[AutoBook-Xposed] 已设置系统属性标记        ← 或 "设置系统属性失败: ..."（已知问题，不影响 Hook）
```

命中时（才是真正生效的证据）：
```
[AutoBook-Xposed] 阻止系统解绑无障碍服务
[AutoBook-Xposed] 阻止移除无障碍服务: <component>
[AutoBook-Xposed] 阻止系统杀进程，原因: <reason>
```

早期文档写的 `LSPosed 模块已加载` / `Hook 无障碍服务成功` / `拦截系统对无障碍服务的关闭操作`
这三条**代码里都不存在**，照着对日志会把成功误判成失败。

**预期效果**：
- 无障碍服务在后台保持激活状态
- 设置页面中无障碍服务不会显示"已关闭"
- 系统不会自动杀死无障碍服务

#### 3. 功能测试
```bash
# 使用提供的测试脚本
bash check-lsp-status.sh

# 或手动检查
adb shell dumpsys accessibility | grep -A 5 "AutoBook"
```

### 故障排查

#### 图标未更新
```bash
# 方法1：清除启动器缓存
adb shell pm clear com.miui.home  # 小米
adb shell pm clear com.android.launcher3  # 原生

# 方法2：重启设备
adb reboot

# 方法3：检查 APK 中的图标
unzip -l dist/AutoBook-0.6.4-debug.apk | grep ic_launcher
```

#### LSP 模块未激活
```bash
# 检查模块是否在 APK 中
unzip -p dist/AutoBook-0.6.4-release.apk assets/xposed_init

# 检查 LSPosed 状态
adb shell ls -l /data/adb/modules/  # Magisk 模块目录

# 查看详细日志
adb logcat -s "Xposed:*" "LSPosed:*" "AutoBook:*"
```

#### 无障碍服务被杀死
- 确认 LSP 模块已在 LSPosed 管理器中启用
- 确认作用域包含"系统框架"
- 确认已重启系统框架
- 检查系统电池优化设置，将 AutoBook 加入白名单

## 🔧 技术细节

### LSP 模块工作原理（已按代码核对，2026-10-22）
1. **入口类**：`com.example.autobook.xposed.AutoBookXposedModule`（由 `app/src/main/assets/xposed_init` 声明）
2. **Hook 目标**：只在 `lpparam.packageName == "android"` 时生效，共 4 处 `XposedHelpers.findAndHookMethod`：
   - `AccessibilityManagerService.unbindService(AccessibilityService)` - 阻止解绑服务
   - `AccessibilityManagerService.removeServiceLocked(AccessibilityServiceConnection, int)` - 防止移除连接
   - `ActivityManagerService.killApplication(String, int, String)` - 阻止杀进程
   - `ActivityManagerService.killApplication(String, int, int, String)` - 同上，重载
   命中 `com.example.autobook` 时置 `param.result = null`。
3. 〔核查修正〕**不存在** `PowerManagerService` Hook（"强制豁免电池优化"从未实现），也**不存在** `removeAccessibilityInteractionConnection()`、`Context.getSharedPreferences`、`BillAccessibilityService.onUnbind/onDestroy` 这些 Hook——早期文档把它们写进来了。
4. **作用域**：只需 `系统框架（android 包）`，勾主 App 无效果。

### 关键代码位置
```
app/src/main/java/com/example/autobook/
├── xposed/
│   └── AutoBookXposedModule.kt         # LSP 模块主类（208 行）
├── ui/
│   └── MainActivity.kt                 # 主界面
└── capture/                            # 〔核查修正〕无障碍服务在这里，不是 service/
    ├── BillAccessibilityService.kt     # 无障碍服务（含 120ms/600ms 分级去抖）
    ├── XposedModuleDetector.kt         # 模块激活检测（0.6.3 起三档 HookState）
    ├── GuardianPolicy.kt               # 守护判定纯逻辑（0.6.3 新增，可单测）
    ├── AccessibilityGuardian.kt        # 无障碍守护前台服务（0.6.3 起已注册并随模式起停）
    └── AccessibilityEnhancer.kt        # 保活体检 + 厂商引导（0.6.3 重写，全部实测）

app/src/main/assets/
└── xposed_init                         # LSP 模块入口配置

app/src/main/res/
└── mipmap-*/                         # 5 档 × 3 个 PNG（0.6.4 全部重建）
    ├── ic_launcher.png                 # legacy 图标，不透明（debug 包可直接验证；release 包资源被重命名）
    ├── ic_launcher_round.png           # legacy 圆形图标
    └── ic_launcher_foreground.png      # 自适应前景层（API 26+ 桌面真正显示的就是这一层）
```

### AndroidManifest 配置
```xml
<!-- LSP 模块声明 -->
<meta-data
    android:name="xposedmodule"
    android:value="true" />
<meta-data
    android:name="xposeddescription"
    android:value="AutoBook LSP 增强模块" />
<meta-data
    android:name="xposedminversion"
    android:value="93" />   <!-- 〔核查修正〕实际是 93，早期文档写 82 -->
```

## 📊 测试记录

### 模拟器测试（历史记录，非 0.6.2 专属）
- **日期**：2026-10-22（〔核查修正〕早期文档写成 2024-10-22）；0.6.2 本身**未上模拟器**
- **环境**：Android 15 (API 35) x86_64 模拟器
- **结果**：
  - ✅ APK 安装成功
  - ✅ 应用正常启动
  - ✅ 图标文件已打包（debug 包可直接验证；release 包资源被 R8 重命名）
  - ✅ LSP 配置文件存在（`assets/xposed_init`，两个 APK 都有）
  - ✅ 单测 149 例全绿（16 个测试类，2026-10-22 14:51 复跑）
  - ❌ 无法测试 LSP Hook（模拟器无 LSPosed）

### 真机测试（待完成）
- **目标设备**：小米手机（澎湃 OS 4）
- **测试项**：
  - [ ] 图标显示正确
  - [ ] LSP 模块可激活
  - [ ] Hook 功能生效
  - [ ] 无障碍服务保活
  - [ ] 长时间稳定性

## 🐛 已知问题

### 1. 模拟器限制
- **问题**：x86_64 模拟器无法安装 Magisk/KernelSU/LSPosed
- **原因**：需要修补内核和 boot.img
- **影响**：无法在模拟器上测试 LSP 功能
- **解决**：必须使用真实设备测试

### 2. 图标缓存
- **问题**：升级安装后图标可能不更新
- **原因**：Android 启动器缓存机制
- **解决**：清除启动器数据或重启设备

### 3. LSP 兼容性
- **问题**：不同 Android 版本的 AccessibilityManagerService 实现可能不同
- **影响**：Hook 可能在某些系统版本上失效
- **解决**：需要在目标系统上实际测试和调整

## 📚 相关文档

- `TEST_REPORT.md` - 详细测试报告
- `DEPLOYMENT.md` - 部署指南
- `check-lsp-status.sh` - LSP 状态检查脚本
- `README.md` - 项目说明

## 🔐 安全注意事项

1. **Keystore 保护**：
   - keystore.jks 文件不要提交到版本控制
   - keystore.properties 中的密码敏感信息

2. **LSP 模块风险**：
   - Hook 系统服务可能导致系统不稳定
   - 仅在测试设备上启用
   - 建议提供开关让用户控制是否启用 Hook

3. **权限声明**：
   - 无障碍服务权限需要用户明确授权
   - 应向用户说明权限用途

## 📞 技术支持

### 调试命令
```bash
# 查看应用日志
adb logcat | grep "AutoBook"

# 查看 LSP 日志
adb logcat | grep "Xposed"

# 查看无障碍服务状态
adb shell dumpsys accessibility

# 导出应用数据（调试用）
adb backup -f autobook.ab com.example.autobook

# 安装并查看日志
adb install -r dist/AutoBook-0.6.4-debug.apk && adb logcat -c && adb logcat
```

### 常用 ADB 命令
```bash
# 启动应用
adb shell am start -n com.example.autobook/.ui.MainActivity

# 强制停止
adb shell am force-stop com.example.autobook

# 清除数据
adb shell pm clear com.example.autobook

# 查看应用信息
adb shell dumpsys package com.example.autobook

# 截图（用于验证图标）
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png
```

## 🔄 后续开发建议

### 短期优化
1. **LSP 模块开关**：在应用设置中添加开关，让用户控制是否启用 Hook
2. **错误处理**：增强 LSP 模块的异常捕获和日志记录
3. **兼容性测试**：在多个 Android 版本上测试 Hook 效果
4. **用户反馈**：添加 LSP 模块状态显示界面

### 长期规划
1. **多系统适配**：适配更多国产 ROM（MIUI、ColorOS、HarmonyOS 等）
2. **模块化设计**：将 LSP 功能设计为可选模块
3. **性能优化**：减少 Hook 对系统性能的影响
4. **安全审计**：确保 Hook 代码的安全性

## 📝 版本历史

### v0.6.4 (2026-10-22)
- 🔧 修复：**桌面图标其实一直没换**。`mipmap-anydpi-v26/ic_launcher.xml` 的 `<foreground>` 指向
  `@mipmap/ic_launcher_foreground`，API 26+ 桌面只显示这一层；而 `generate-icons.sh` / `regenerate-icons.sh`
  从来只写 `ic_launcher.png` 与 `ic_launcher_round.png`，导致 10-22 换的新图只进了 legacy 层
- 🗑️ 删除：全部 15 个旧 PNG（5 档 × `ic_launcher` / `ic_launcher_round` / `ic_launcher_foreground`），不留旧图 fallback
- ✨ 重建：用 `AutoBook-converted.png` 重生成整套 15 个 PNG；自适应前景层内容缩到 68dp 安全区、居中、白底转透明
- 🔧 修改：`values/colors.xml` 的 `ic_launcher_background` 从旧图遗留的 `#C0E1D7` 改为 `#FFFFFF`
- 🔧 重写：`generate-icons.sh` 一次生成 legacy + 前景层 + 背景色，自带尺寸/透明性校验（缺失即 `exit 1`），支持 `--dry-run`
- 🗑️ 删除：`regenerate-icons.sh`（与 `generate-icons.sh` 重叠且同样漏掉前景层，是本次事故的直接来源）
- ✅ 验证：167 单测 / 18 类全绿；`dist/AutoBook-0.6.4-{debug|release}.apk`（23）构建成功；
  debug 包五档前景层字节数 8590/14634/20982/35825/53182 与源文件对应；release `res/BW.xml` 的 `<foreground>`
  指向 `res/as.png`（432×432 RGBA），`color/ic_launcher_background` = `#ffffffff`
- ✅ 真机已确认（2026-10-22 用户实测）：装上 0.6.4 后桌面图标立即变成新图（白底彩色圆环），无需清缓存/重启桌面
- ⚠️ 仍未核对：68dp 安全区在三种裁切形状下的边距（无设备、未逐形状检查）

### v0.6.3 (2026-10-22)
- ✨ 新增：`GuardianPolicy` 三档守护判定（正常 / 设置里已关闭 / 设置里开启但服务未连上），修掉"服务启动时无障碍已关则永不报警"的旧缺陷
- ✨ 新增：`AccessibilityGuardian` 注册为 `specialUse` 前台服务，随「无障碍模式 + 识别开启」自动起停，30s 巡检，异常发通知引导重开
- ✨ 新增：权限页「无障碍保活」组（守护服务状态 / 电池优化豁免一键跳转 / 厂商自启动引导）
- 🔧 重写：`AccessibilityEnhancer` 删掉 `autoStartEnabled=null`、`notificationEnabled=true // TODO`、摆设 `attemptAutoRepair()` 等假数据，只留实测项
- 🔧 重写：`XposedModuleDetector` 三档 `HookState`，删掉永假的 SharedPreferences 路与查不存在包的 `getModuleVersion()`
- 🔧 修复：`build-xposed-module.sh`（删掉必失败的 `:xposed-module:assembleRelease`）、`build-all.sh`（版本号从 gradle 读 + `aapt2` 核对）、`check-lsp-status.sh`（`adb pull` 取代 `adb shell unzip`）
- ✅ 验证：167 单测 / 18 类全绿，debug + release 构建成功，`dist/AutoBook-0.6.3-{debug|release}.apk`（22）
- ⚠️ 未验证：守护服务能否常驻、掉线通知能否弹、"假开启"能否识别、权限页读数，**全部没有真机/模拟器证据**

### v0.6.2 (2026-10-22)
- ✨ 新增：彩色圆环 + ¥ 符号的新图标
- ✨ 新增：LSPosed 增强（**内置在主 APK 里**，不再是独立模块；实际 Hook 4 处，见上文"LSP 模块工作原理"）
- ✨ 新增：无障碍按事件分级去抖（`TYPE_WINDOW_STATE_CHANGED` 120ms / `TYPE_WINDOW_CONTENT_CHANGED` 600ms）
- 🔧 优化：构建配置和项目结构
- 📝 文档：添加完整的测试和部署文档 + `docs/PROGRESS.md` 跨版本进度总账
- ⚠️ 未验证：LSP Hook 是否真的阻止撤销/杀进程（`AccessibilityGuardian`/`AccessibilityEnhancer` 已在 0.6.3 接线，见上一条）
- 〔后记〕本条原写"图标在真机启动器上是否刷新未验证"——0.6.2/0.6.3 实际都没换成功，0.6.4 重建后
  **已由用户真机确认刷新**（2026-10-22，见 v0.6.4 一节）

### v0.6.1 (2026-10-22)
- 首次选择 Root / 无障碍模式并持久化，`CaptureModeManager` 统一起停；149 单测，证据 `runs/20261022-101537-capture-mode/verification.md`

### v0.6.0 (2026-10-22)
- 退款独立类型、手动新增/编辑、左滑删除 + 5 秒撤销、截断诊断贯通；141 单测，证据 `runs/20261022-095145-core-ledger/verification.md`

### v0.5.9 及更早
- 基础功能实现、无障碍服务框架、自动记账、dock 调整；详见 `AGENTS.md` §10 与 `CHANGELOG.md`

## ✅ 交接检查清单

- [ ] 代码已提交到版本控制
- [ ] APK 文件已构建并验证
- [ ] 所有文档已更新（README, TEST_REPORT, DEPLOYMENT, HANDOVER）
- [ ] Keystore 和密钥文件已安全保存
- [ ] 开发环境配置已记录
- [ ] 测试脚本可正常运行
- [ ] 已知问题已记录
- [ ] 真机测试步骤已明确
- [ ] 后续开发建议已列出

## 📧 联系信息

如有问题，请参考：
- 项目文档：`/home/caozl/Projects/AutoBook/`
- 测试报告：`TEST_REPORT.md`
- 部署指南：`DEPLOYMENT.md`

---

**文档版本**：v1.1  
**更新日期**：2026-10-22（v0.6.2 文档核查轮，未改代码；订正签名路径、工具版本、Hook 清单、xposedminversion 与日期）  
**状态**：待真机验证
