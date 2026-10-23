# 决策

## 为什么是"接通"而不是"删除"

用户当轮明确要求"确定保活无障碍模块的正常"，删除等于把 0.6.2 文档里承诺的守护能力彻底取消。
代码本身逻辑不差（30s 巡检、前台服务、通知渠道都在），缺的只是接线与判定修正，改成可用比删掉划算。

## 守护服务的起停点选在 `CaptureModeManager.reconcile()`

这是 `App.onCreate`、`BootReceiver`、设置页开关切换、SharedPreferences 监听四条路径的唯一汇聚点，
把起停放这里，就不需要每个调用方各自记得拉起守护服务。`BillAccessibilityService.onServiceConnected()`
里再加一次幂等 `ensureRunning()` 作为兜底（服务重连时 reconcile 可能没被触发）。

`reconcile()` 里`else` 分支直接 `stop()`，不再用 `if (running)` 守卫：`stopService()` 对未运行的服务
是安全空操作，而 `running` 是进程内标记，进程重启后不可信。

## 判定改为三档 Health，而不是布尔

`GuardianPolicy.health(enabledInSettings, serviceBound)` 返回 `OK / DISABLED / UNBOUND`：

- `DISABLED`：系统设置里那个开关就是关的 —— 用户自己关的，2 次巡检（约 60s）后提醒即可。
- `UNBOUND`：设置里勾着，但 `BillAccessibilityService.instance == null` —— 这是厂商/系统把它解绑了，
  用户以为自己开着。给 4 次巡检（约 2 分钟）容错，避开冷启动、进程重建、刚开机这几秒的假阳性。

阈值不同的理由是误报代价不对称：`DISABLED` 误报只是多一条通知（用户确实关过），
`UNBOUND` 误报会让人以为服务坏了而去乱点设置。

旧实现用"上一次观测值"做边沿检测，初值 `false` 导致"启动时已关"永不报警；新实现累积连续异常次数，
首检即生效，且 `nextMisses` 在 `OK` 时清零，没有边沿依赖。`REALERT_EVERY_MISSES = 20`（约 10 分钟）
避免每 30s 弹一条；`MISS_CAP = 10_000` 防止长期异常把计数涨到溢出。

## 刻意不做的事

- **不 Hook `PowerManagerService`**：改系统电源策略的副作用不可控，且各 ROM 的
  `isIgnoringBatteryOptimizationsLocked` 签名不一致，维护成本远高于收益。改为引导用户手动加白名单
  （`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，解析不到时退 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`）。
- **不在 `onDestroy` 里重启自己**：`startForegroundService` 在后台受 Android 12+ 限制，
  盲目重启会 crash；真正的复活要靠 `AlarmManager`/`JobScheduler`，那属于另一件事。
- **不改 `setModuleActiveFlag()`**：让模块 Hook 到 App 进程自身写标记是可行的，但会让模块从
  "只碰系统框架"变成"也碰自己的 App"，与当初"只 Hook 系统框架、不修改任何第三方 App"的边界宣言冲突，
  留作后续设计取舍（见 `openspec/changes/enhanced-accessibility-lsp/design.md`）。

## 验证边界

单测能覆盖 `GuardianPolicy` 的全部判定与阈值，覆盖 `Manufacturer.fromBrand` 与 `isFullyOptimized` 真值表；
**覆盖不了**前台服务能否常驻、通知能否弹出、厂商 ROM 的实际行为。这部分 MUST 在真机上做，
所以本变更的状态只能是"待验收"，`runs/20261022-155747-guardian-hardening/outputs/` 故意留空。
