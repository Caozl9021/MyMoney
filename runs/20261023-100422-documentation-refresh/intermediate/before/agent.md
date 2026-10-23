# agent.md

完整内容在 [`AGENTS.md`](AGENTS.md)（同一份，文件名保留以兼容不同工具的查找习惯）。
跨版本进度与"哪些宣称其实没实现"看 [`docs/PROGRESS.md`](docs/PROGRESS.md)。

速览：

- 项目根：`/home/caozl/Projects/AutoBook`（可整体迁移，见 AGENTS.md §11）
- 模块：`billkit`（识别库）+ `app`（App）；**没有** `:xposed-module` 模块（LSP 已内置进 app）
- 构建：`source ~/opt/autobook-env.sh` 后 `./gradlew :app:testDebugUnitTest`（**167 个用例，18 个测试类**）/ `./gradlew :app:assembleDebug`
- 当前版本：**0.6.4（versionCode 23）**，产物 `dist/AutoBook-0.6.4-{debug|release}.apk`；**待验收**（0.6.2 / 0.6.3 没有任何真机或模拟器功能证据；0.6.4 的**桌面图标已由用户真机确认刷新**，其余如 68dp 三种裁切边距仍未核对）
- 0.6.3 重点：`AccessibilityGuardian`/`AccessibilityEnhancer` 已从死代码接通（守护服务随模式起停、30s 巡检、掉线通知、`GuardianPolicy` 三档判定），`XposedModuleDetector` 改为三档 `HookState`；权限页新增「无障碍保活」组。保活能力上限：只能发现掉线并提醒，做不到 LMK 豁免/自动重启
- 0.6.4 重点：**桌面图标彻底重建**。旧的两个图标脚本只写 legacy 的 `ic_launcher.png`/`ic_launcher_round.png`，从不生成 `ic_launcher_foreground.png`，而 API 26+ 桌面只渲染自适应那两层 → 真机一直显示旧图标。已删掉全部 15 个旧 PNG，`generate-icons.sh` 一次生成 legacy + 前景层 + 背景色（`#FFFFFF`）并自带校验；`regenerate-icons.sh` 已删。**2026-10-22 用户真机确认：装上 0.6.4 release 后桌面图标立即变成白底彩色圆环，0.6.2/0.6.3 的「图标没更新」问题闭环**（口头确认、无截图）
- 采集模式：首次选 Root 或无障碍，持久化，互斥（0.6.1 起）
- 红线：不碰第三方 App 的私有数据、不自动点它的界面、不绕会员
