本目录**故意为空**。

这个 run（`20261022-155747-guardian-hardening`）是 **0.6.3 的代码改造轮**，不是功能验收轮：

- 做的是：把 `AccessibilityGuardian` / `AccessibilityEnhancer` 从零调用点的死代码接成真实链路、
  重写 `XposedModuleDetector`、修三个构建/检查脚本、补 18 个单测、出 0.6.3 的 debug + release 包。
- 没做的是：**没有连接真机或模拟器**（`adb devices` 为空），所以没有任何 UI 截图、
  `uiautomator dump` 的 XML 或 `dumpsys` 输出可放。

对比：`runs/20261022-101537-capture-mode/outputs/` 或 `runs/20261022-093519-dock-indicator/outputs/`
是有设备证据的，那才是功能验收 run。

0.6.3 的功能验收（守护服务能否常驻、掉线通知能否弹、"设置里打勾但被解绑"能否识别、
权限页三行状态读数）仍然全缺，见 `../verification.md` 的"限制"段第 1 条与
`docs/PROGRESS.md` 的待办。
