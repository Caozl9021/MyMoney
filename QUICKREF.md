# AutoBook 快速参考

当前 **0.6.8 / 27，待用户验收**；详情见 [README](README.md)、[部署指南](DEPLOYMENT.md) 与 [测试报告](TEST_REPORT.md)。[plan1.0](docs/PLAN_1.0.md) 为规划待审阅，新增内容未实施。

## 常用命令

```bash
source "$HOME/opt/autobook-env.sh"
cd "$HOME/Projects/AutoBook"
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
adb install -r dist/AutoBook-0.6.8-release.apk
```

上例安装的是已有 release 候选包；本地 debug 构建输出在 `app/build/outputs/apk/debug/app-debug.apk`。JDK 17、SDK 35；如需改用可写缓存，在 source 环境后设置 `GRADLE_USER_HOME`，详见部署指南。debug 与 release 通常签名不同，升级时沿用相同签名的包。

## 使用与排查

1. 首次选择无障碍或 Root；两者互斥。总开关暂停识别时仍保留模式选择。
2. 按首页引导处理所选模式权限。无障碍需在系统中启用服务；Root 需 `su` 授权。直接悬浮确认需悬浮窗权限。
3. 核对确认卡后保存；可手动新增/编辑账单，删除后短时可撤销。关闭“识别后自动弹确认卡”后只进入待确认队列。
4. 未弹卡时先查待确认队列，再到“设置 → 诊断”查看来源、识别/投递结论与截断信息。漏报时尽量停在原成功页保存样本，避免进入详情页覆盖最近样本。
5. “设置 → 本地规则管理”可导入 JSON、调整餐段、查看学习和覆盖计数。成功人工保存才学习；关闭学习会暂停已有学习推荐。
6. 导出与备份入口在设置。iCost CSV 不支持退款，包含退款的范围会被阻止导出；覆盖恢复前先保留备份。

```bash
source "$HOME/opt/autobook-env.sh"
adb shell dumpsys package com.example.autobook
adb logcat | rg 'AutoBook|AB-Guardian|Xposed'
```

LSP 可选且内置于主 APK；作用域只勾系统框架 `android`，实测方法和能力限制见 [LSP 文档](docs/LSP_MODULE.md)。设置状态和 Hook 注册日志不能单独证明拦截生效。

## 当前证据

- Auto.jpg 为最新品牌图；自适应 54dp/108dp、legacy 55% 留白，独立单色负空间。
- 221 项单测/24 类全绿，双构建、版本、签名及每包 26 项图标资源核查通过。
- API35 当前目视覆盖确认卡、QS 磁贴和 widget 新图；其他 UI 操作来自旧版预览，不等于当前完整回归。
- 微信极简成功页与调度修复已有代码/单测；真实微信付款事件、瞬时悬浮弹框、LSP 和厂商保活仍待真机核查。

[最新验证](runs/20261023-092336-brand-jpg-wechat-release/outputs/verification.md) · [交付索引](deliverables/0.6.8/README.md) · [进度](docs/PROGRESS.md) · [交接约定](AGENTS.md)
