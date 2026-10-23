# 内置 LSP 模块：机制与限制

按 2026-10-23 的 **0.6.8 / 27** 源码核对。安装步骤见 [快速开始](LSP_QUICKSTART.md)，勾选范围见 [作用域配置](LSP_SCOPE_CONFIG.md)。

## 模块位置与实际 Hook

LSP 代码内置在主 App，没有独立 `:xposed-module` 工程或模块 APK。`settings.gradle.kts` 仅包含 `:app`、`:billkit`；Manifest 声明 `xposedmodule`，`xposedminversion=93`，`assets/xposed_init` 指向 `com.example.autobook.xposed.AutoBookXposedModule`。

入口仅处理 `packageName == "android"`。代码包含四个候选方法签名：

| 系统类 | 方法签名 | 命中条件与行为 |
|---|---|---|
| `AccessibilityManagerService` | `unbindService(AccessibilityService)` | 服务类名为 AutoBook 的 `BillAccessibilityService` 时跳过原方法 |
| `AccessibilityManagerService` | `removeServiceLocked(AccessibilityServiceConnection,int)` | 连接组件名包含 AutoBook 包名时跳过原方法 |
| `ActivityManagerService` | `killApplication(String,int,String)` | 第一个参数等于 `com.example.autobook` 时跳过原方法 |
| `ActivityManagerService` | `killApplication(String,int,int,String)` | 仅在上一签名不存在时尝试的兼容重载，命中条件相同 |

四个候选签名不等于同时注册四个 Hook。ROM 类名、方法签名和调用路径可能不同；部分异常被捕获或静默忽略，完成日志也不能证明全部挂钩成功。具体代码见 `app/src/main/java/com/example/autobook/xposed/AutoBookXposedModule.kt`。

没有 `PowerManagerService`、`Context.getSharedPreferences` 或第三方支付 App 的 Hook。模块不能提供电池优化强制豁免、LMK 低内存回收保护、进程优先级提升或服务自动恢复。系统框架 Hook 的兼容性与稳定性仍需实际 ROM 验证，不能承诺不影响系统。

## App 中的三档状态

`XposedModuleDetector` 当前使用系统属性与调用栈两路检测，旧 SharedPreferences 标记路径已删除：

| 状态 | 代码判据 | 能说明什么 |
|---|---|---|
| `NOT_DETECTED` | 两路未命中 | 没有检测证据，不能反推 Hook 一定未加载 |
| `FRAMEWORK_PRESENT` | 调用栈出现 Xposed/LSPosed 等类名 | 检测到框架，未确认 AutoBook 模块 |
| `MODULE_CONFIRMED` | `persist.autobook.xposed.active=true` | 读到模块设置的标记 |

模块在尝试注册 Hook **之前**写持久系统属性，写入可能因权限或系统策略失败，也没有在禁用时清除的路径。因此即便显示「已确认」，也不能单靠它证明本次启动的 Hook 注册和拦截成功；需结合本次 LSPosed 日志及实际命中记录。调用栈同样不能证明模块激活。

## 无障碍守护与厂商设置

守护服务是独立于 LSP 的普通 App 能力。`CaptureModeManager` 按无障碍模式与识别开关起停 `AccessibilityGuardian`，`onServiceConnected` 幂等兜底；Manifest 已注册 `specialUse` 前台服务。

`GuardianPolicy` 每30秒检查：健康为 `OK`；系统设置关闭连续2次（约60秒）、设置已开但未绑定连续4次（约2分钟）后通知提醒。实际时机受系统调度影响。守护进程自身被杀后无法继续巡检，`onDestroy` 不自动重启服务。

设置 → 权限中的「无障碍保活」可查看守护状态、请求电池优化豁免及打开厂商自启动入口。`AccessibilityEnhancer` 检查电池优化、通知、设置与绑定状态，不能直接读取厂商自启动开关。下面是源码提供的检查提示，具体菜单随 ROM 变化：

| 厂商 | 手动检查 |
|---|---|
| 小米/Redmi | 自启动、省电策略无限制、最近任务锁定 |
| 华为/荣耀 | 启动管理关闭自动管理并允许所需活动、允许后台活动、最近任务锁定 |
| OPPO | 允许后台运行、自启动与关联启动、最近任务锁定 |
| vivo | i管家自启动、允许后台高耗电、最近任务锁定 |
| 三星/其他 | 检查电池后台限制与自启动设置；入口不可解析时按页面提示手动查找 |

这些操作由用户在系统设置完成，LSP 不能代替。Root 采集与无障碍采集互斥；选择 Root 时不依赖 LSP。

## 验证与剩余工作

`check-lsp-status.sh` 可辅助读取属性、日志和安装 APK 的入口文件，但输出需按上述限制解释。看不到命中日志可能是没有发生目标调用、日志不可见或已轮转；不能据此认定失败。看到某一次拦截也只验证该路径。

当前没有真实 ROM 的 Hook 拦截、LMK 场景或性能功耗实测。无障碍120ms窗口调度、600ms内容去抖/1000ms上界与一次400ms根节点重试属于普通采集代码，**不依赖 LSP**，也不是付款到弹卡的总时延。最新验证及待验收状态见 [PROGRESS.md](PROGRESS.md)。
