# LSP 快速开始

适用 **AutoBook 0.6.8 / 27**（2026-10-23）。LSP 是已 Root 且已有兼容 LSPosed 环境时可选的无障碍增强；普通无障碍使用不需要它。Root 采集模式也不依赖它。兼容性和真机拦截效果尚待验证。

## 安装

只安装主 App。已有同签名版本时覆盖安装；debug/release 签名可能不同，遇到不匹配先检查已装版本并备份账本，避免卸载丢数据。

在项目根目录运行：

```bash
adb devices
adb install -r dist/AutoBook-0.6.8-release.apk
```

需要自行构建时，先配置 JDK17/SDK35 环境，再运行：

```bash
source ~/opt/autobook-env.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
# 输出 app/build/outputs/apk/debug/app-debug.apk
```

本机环境脚本是可选的机器配置；其他机器应配置自己的环境。正式签名配置保存在仓库外，不在文档中复制凭据。`build-xposed-module.sh` 已改为构建主 App；日常版本化出包使用 `build-all.sh`。不存在独立模块构建任务。

## 启用

1. 在 AutoBook 选择无障碍模式，打开识别开关并开启系统无障碍服务。
2. 在 LSPosed 管理器的模块列表启用 **AutoBook**。
3. 作用域只选择 **系统框架 (`android`)**，见 [作用域配置](LSP_SCOPE_CONFIG.md)。
4. 重启设备，使系统框架重新加载模块。更新 Hook 代码后同样需要重启。
5. 回到 AutoBook 设置 → 权限，检查无障碍连接、悬浮窗及通知权限；按需要配置电池优化和厂商自启动。

## 检查与排障

优先查看 LSPosed 管理器的模块日志，搜索 `AutoBook-Xposed`。终端可辅助检查：

```bash
unzip -p dist/AutoBook-0.6.8-release.apk assets/xposed_init
adb logcat -d | rg 'AutoBook-Xposed|AB-Guardian'
bash check-lsp-status.sh
```

入口文件应为 `com.example.autobook.xposed.AutoBookXposedModule`。日志中的「开始 Hook 系统框架」证明执行过入口；「阻止系统解绑无障碍服务」「阻止移除无障碍服务」或「阻止系统杀进程」才是对应调用发生过拦截的证据。日志访问范围及保留时间因设备而异，找不到日志不能单独判断失败。

App 状态分为未检测、检测到框架、模块标记已确认。标记写入早于 Hook 注册且会持久化，显示状态不能代替本次拦截验证，详见 [机制与限制](LSP_MODULE.md)。方法不存在等错误需结合设备型号、Android/ROM版本、LSPosed版本和日志定位。

实际识别验证应在支付成功页观察确认卡，并保存诊断样本与日志。120ms 是窗口事件调度值，不能保证120ms内弹卡。0.6.8 微信回归采用截图转录文本，真实付款瞬时弹框仍待验证。

## 禁用

在 LSPosed 中取消勾选 AutoBook 并重启，之后仍可使用普通无障碍模式。无需卸载主 App，也没有独立模块 APK 可卸载。出现系统兼容性问题时先禁用模块并保留日志。
