# LSP 作用域配置

适用 **0.6.8 / 27**（2026-10-23）。只勾选 **系统框架 (`android`)**。

1. 打开 LSPosed 管理器 → 模块 → AutoBook。
2. 启用模块，在作用域中选择系统框架。
3. 重启设备。更新模块代码、启用或禁用后，都需让系统框架重新加载。

模块内置在主 App，入口 `AutoBookXposedModule.handleLoadPackage` 仅在 `lpparam.packageName == "android"` 时调用 Hook 注册逻辑。勾选主 App、微信、支付宝等其他包不会执行这段逻辑，也不会让 App 状态检测更可靠。只勾 AutoBook 而不勾系统框架无法注册本模块的 Hook。

无需独立 APK，也无需修改任何第三方 App 的作用域。App 不负责替用户自动修改 LSPosed 配置。

配置后的检查请按 [快速开始](LSP_QUICKSTART.md) 进行。模块入口、四种候选 Hook 签名、三档检测限制及守护服务职责见 [机制与限制](LSP_MODULE.md)。尤其不能把作用域勾选、入口加载日志或 App 显示「已确认」直接当成真机拦截成功。
