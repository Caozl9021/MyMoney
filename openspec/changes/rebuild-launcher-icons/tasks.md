# 工作项

- [x] T1 定位根因：对比三套图标文件的 mtime，确认 `ic_launcher_foreground.png` 五档仍为
      2026-10-21 15:03、`values/colors.xml` 同为 10-21，而 legacy 两个 PNG 是 10-22 13:48；
      读 `mipmap-anydpi-v26/ic_launcher.xml` 确认 API 26+ 只渲染 background + foreground。
- [x] T2 核查两个生成脚本：`generate-icons.sh` 与 `regenerate-icons.sh` 的输出目标都只有
      `ic_launcher.png` / `ic_launcher_round.png`，全文无 `ic_launcher_foreground`、无写 `colors.xml`。
- [x] T3 用**文本方式**核对源图（禁止读图）：`file` 确认 `AutoBook.png` 实为 JPEG 1254x1254、
      `AutoBook-converted.png` 为真 PNG 1254x1254；`-fuzz 5% -trim` 包围盒 849x874+202+185；
      直方图确认 `#FFFFFF` 占 1,103,569 px、四角采样全为纯白 → 源图是彩绘 + 白底，非透明。
- [x] T4 删除 15 个旧 PNG（5 档 × `ic_launcher.png` / `ic_launcher_round.png` /
      `ic_launcher_foreground.png`），删后 `find` 确认 0 个残留。
- [x] T5 重写 `generate-icons.sh`：`set -euo pipefail`、`cd "$(dirname "$0")"`、支持 `--dry-run`；
      legacy 5 档（48/72/96/144/192）；自适应前景 5 档（108/162/216/324/432），
      流程 `-fuzz 5% -trim +repage` → `-fuzz 5% -transparent white` → 缩到 68dp 安全区 →
      `-background none -gravity center -extent`；写 `values/colors.xml`（`#FFFFFF`）；
      末尾校验循环，不符 `exit 1`。
- [x] T6 修校验的通道判定：`%[channels]` 在 ImageMagick 7 返回 `srgb 3.0`/`srgba 4.0`，
      改用 `%[opaque]`（`True`/`False`）——legacy 断言 `True`、前景层断言 `False`，15 条全 ✓。
- [x] T7 删除 `regenerate-icons.sh`（与 generate 重叠且同样漏前景层，是事故直接来源）。
- [x] T8 `app/build.gradle.kts` 版本升 `versionCode = 23` / `versionName = "0.6.4"`。
- [x] T9 全量 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`：
      `BUILD SUCCESSFUL in 36s`，`146 actionable tasks: 28 executed, 1 from cache, 117 up-to-date`；
      单测 **167 / 0 / 0 / 0，18 类**。
- [x] T10 产物核对：两包 `aapt2 dump badging` 均 `versionCode='23' versionName='0.6.4'`；
      debug 54,732,457 B / release 45,812,545 B；release 证书 `bedb36b7…8a5ed6`（与前版一致）、
      debug 证书 `08b0f9a0…566cd7`；两包已 `cp` 进 `dist/`。
- [x] T11 APK 内图标核对：debug 包 17 个 `ic_launcher*` 条目，前景层五档
      **8590/14634/20982/35825/53182** 字节（旧包 3659/6591/10242/20653/34715）；
      release 包 `aapt2 dump xmltree --file res/BW.xml` 的 `<foreground>` → `res/as.png`
      实测 432x432 `opaque=False`、中心 `srgba(48,129,250,1)`；
      `aapt2 dump resources` 中 `color/ic_launcher_background` = `#ffffffff`。
- [x] T12 几何核对：前景层非透明内容包围盒 264x272+84+80（居中、四周留白），四角 `srgba(0,0,0,0)`。
- [x] T13 文档回写：`CHANGELOG.md`（新增 v0.6.4 条目）、`TEST_REPORT.md` / `DEPLOYMENT.md`
      （全文改 0.6.4）、`HANDOVER.md`（标题/图标段/产物树/v0.6.4 版本历史，历史改新→旧）、
      `QUICKREF.md`（0.6.4 + 图标 FAQ 重写）、`README.md`（安装段 + 新增 0.6.4 章节）、
      `docs/PROGRESS.md`（第一节事实表 + 3.5 订正 + 新增第九节 + 待办第 10 条）。
- [x] T14 **真机验证**：装上 `dist/AutoBook-0.6.4-release.apk`，确认桌面图标变成白底彩色圆环
      （旧图是浅薄荷绿底）。**2026-10-22 用户实测通过**——装上即刷新，无需清缓存/重启桌面。
      余项：圆形/方形/圆角矩形三种裁切形状下 68dp 安全区边距未逐形状核对，拆到 T14b。
- [ ] T14b 三种裁切形状的边距目视（用户未做，本机无设备）。
- [ ] T15 若真机上图标仍是旧的：先用 `unzip -l dist/AutoBook-0.6.4-debug.apk | grep
      ic_launcher_foreground` 确认包内是 53182 字节（不是 34715），再排查桌面缓存/重启桌面。

验收：debug 与 release 两包图标资源齐全且为 0.6.4 新图、背景色为白、单测 167 全绿；
**真机桌面刷新已由用户确认（2026-10-22）**；三种裁切边距仍未核对。

证据：`runs/20261022-163800-icon-rebuild/`（本变更的 run：`--rerun-tasks` 强制重跑 167 单测 +
双构建 + APK 图标资源核对，18 个 XML 与 manifest 里 16 个源文件 sha256 都在；`outputs/` 故意为空
—— `adb devices` 为空，没有真机也没有模拟器）。0.6.3 的 `runs/20261022-155747-guardian-hardening/`
是另一件事（保活接通），别混用。


## 2026-10-22 留白调整补遗（0.6.5，待验收）

- [x] T11 修改生成脚本并重建五档资源；adaptive 54dp、legacy 55%，15个资源尺寸/透明性/包围盒/中心偏差≤1px全通过；dry-run不写文件。
- [x] T12 更新版本 0.6.5/24；强制重跑167单测/18类全绿（39s），随后双构建成功（18s）；APK版本及与0.6.4签名一致，包内前景层五档alpha包围盒和白底均核对。证据 `runs/20261022-173414-icon-padding/`，状态待验收。
- [x] T13 主代理复核：167 tests/0失败，release aapt2版本0.6.5/24，debug APK取出xxxhdpi前景432x432、content210x216+111+108，bash -n通过。运行manifest/verification/APK摘要及交付索引已整理；状态保持待用户验收。

## 2026-10-23 新图续包
用户提供Auto.png；本次同源legacy/adaptive/monochrome更新与0.6.7发布统一纳入 `openspec/changes/complete-u-series/` T8，避免另建冲突变更。旧图验收不代表新图验收。
