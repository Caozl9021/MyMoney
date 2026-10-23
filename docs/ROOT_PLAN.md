# Root 采集路径

本文按 2026-10-23 的 **0.6.8 / 27** 源码核对，说明已实现路径及验证边界。总进度见 [PROGRESS.md](PROGRESS.md)。

## 选择与启动

首次进入必须选择 Root 或无障碍；之后可在设置切换。`capture_mode` 保存模式，`enabled` 单独保存识别开关。`CaptureModeManager` 统一协调应用启动、开机广播、服务重建及设置变化。

- Root 模式通过 `RootForegroundService` 运行 `RootWatcher`，忽略无障碍事件。
- 无障碍模式不调用 `su`，也不执行 Root 截图 OCR。
- Root 授权被拒后停止服务并保留模式选择，用户明确重试才再次申请；通知的「停止」只暂停识别。

## 已实现链路

| 阶段 | 源码与行为 |
|---|---|
| 前台识别 | `billkit/.../root/RootUiDumpSource.kt`：`dumpsys window` 取包名/Activity，过滤系统窗口及白名单外应用 |
| 页面读树 | 同文件：通过 `su -c` 执行 `uiautomator dump`，临时 XML 位于 `/data/local/tmp/ab_ui.xml`，读取前移除旧文件，失败不复用旧页 |
| 文本归一 | 跳过编辑节点及其子树，按行桶与横坐标排序；默认最多200条/2000字，传递真实截断标志 |
| 识别 | `RootWatcher` 将 `ROOT_DUMP` 快照交给共用 `BillPipeline`；未变化页面退避 |
| OCR 兜底 | `needsOcr` 且 OCR 开关打开时截图，3秒冷却；裁状态栏以下上半屏、缩放、ML Kit 中文识别，再交同一规则链 |
| 投递 | 共用确认卡、待确认队列与通知兜底；有权限时优先悬浮确认卡 |

轮询间隔由 `RootPollPolicy` 约束：识别到草稿后至少1200ms，空闲/失败/页面不变至少3000ms，息屏或锁屏30000ms。设置更长基础间隔时前两档随之延长。它们是每轮结束后的等待值，不能当作抓取耗时或弹卡时延保证。

`RootShell` 有超时参数，但当前先同步读完输出再 `waitFor`，不能把该参数宣称为所有阻塞场景的硬超时。Root 也受进程回收、ROM、授权与页面暴露文本的影响；没有性能或功耗保证。

## 操作与诊断

在自己的设备上授予 AutoBook Root 权限，开启识别，进入支付成功页。设置 → Root 轮询查看状态；设置 → 诊断查看最近文本、来源、截断和跳过原因。需要排查时主动保存样本，样本可能包含页面原文。

以下命令在项目根目录的终端执行，多个设备时需增加 `adb -s <序列号>`：

```bash
adb devices
adb shell su -c 'id -u'
adb shell dumpsys window
adb logcat -d -s AutoBook:I
```

`id -u` 返回 `0` 表示该次命令取得 Root。手工读树可在设备授权允许时使用 `adb shell uiautomator dump /sdcard/autobook-ui.xml`，再用 `adb pull /sdcard/autobook-ui.xml` 拉回；这只是读树排查，不等于 App 内完整 Root 链路验证。

## 验证边界

当前代码已接通，已有节拍、采集预算等自动回归和模拟器拒绝授权路径证据；真实 Root 支付成功采集、重启恢复、长期后台稳定性和息屏功耗仍待真机验证。最新 [0.6.8 记录](../runs/20261023-092336-brand-jpg-wechat-release/outputs/verification.md) 中的微信样本来自截图人工转录，不是 Root dump，也不是实际付款事件抓取。

项目仅读取用户屏幕与本地账本，不读取第三方应用私有数据库或凭据。LSP 是另一条可选的无障碍增强路径，见 [机制与限制](LSP_MODULE.md)。
