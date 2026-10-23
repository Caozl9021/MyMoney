# 2026-10-22 16:38 桌面图标彻底重建（0.6.4 / versionCode 23）

状态：**单测与构建已验证；图标刷新已由用户真机确认（2026-10-22 17:08 补记）**。
取证当时 `adb devices` 为空，没有真机也没有模拟器；下方“限制 1”描述的空白由用户后续实测补上。

验收：桌面图标**已通过**——用户装 `dist/AutoBook-0.6.4-release.apk` 后看到白底彩色圆环新图，
无需清缓存/重启桌面。仍未验收：68dp 安全区在圆形/方形/圆角矩形三种裁切下的边距。

## 实现

1. 删掉全部 **15 个**旧图标 PNG：`app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/` 下的
   `ic_launcher.png`、`ic_launcher_round.png`、`ic_launcher_foreground.png`。
2. 重写 `generate-icons.sh`，一次生成三样东西：
   - legacy 5 档（48/72/96/144/192 px，不透明），供 API 26 以下兜底；
   - 自适应前景层 5 档（108/162/216/324/432 px），流程是 `-fuzz 5% -trim` 去白边 → 白底转透明 →
     缩进 68dp 安全区 → 居中放到 108dp 画布；
   - `res/values/colors.xml` 的 `ic_launcher_background` 写成 `#FFFFFF`（原来是 `#C0E1D7` 浅薄荷绿）。
   脚本末尾自带校验：文件缺失、尺寸不符、或透明度不符合预期（legacy 必须不透明、前景层必须带透明）
   就 `exit 1`。判透明用的是 `%[opaque]`，因为 ImageMagick 7 的 `%[channels]` 返回 `srgb 3.0` / `srgba 4.0`，
   和 `RGB` / `RGBA` 字符串比永远不相等。
3. 删除 `regenerate-icons.sh`——它和 `generate-icons.sh` 功能重叠，而且犯的是同一个错（只写 legacy 层）。
4. `app/build.gradle.kts` 版本改为 `versionCode = 23` / `versionName = "0.6.4"`。

## 根因（为什么之前的"新图标"在真机上没生效）

`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 的内容都是

```xml
<adaptive-icon>
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

API 26+ 桌面**只渲染这层**，`mipmap-*/ic_launcher.png` 只是 API 26 以下的 legacy 兜底。
旧的 `generate-icons.sh` / `regenerate-icons.sh` 只写那两个 legacy PNG，**从不生成
`ic_launcher_foreground.png`，也不改 `colors.xml`**——所以 10-22 13:48 的新图只进了 legacy 层，
前景层一直是 10-21 15:03 的旧图。文件名都在、目录都在，看起来"图标换了"，实际桌面显示的是旧图。

## 验证

| 项目 | 结果 |
|---|---|
| 单元测试 | **167 tests / 0 failures / 0 errors / 0 skipped，18 个测试类**（`--rerun-tasks` 强制重跑，`BUILD SUCCESSFUL in 54s`，`39 actionable tasks: 39 executed`） |
| 构建 | `:app:assembleDebug` 与 `:app:assembleRelease`（R8）均 `BUILD SUCCESSFUL` |
| 产物 | `dist/AutoBook-0.6.4-debug.apk` 54,732,457 B；`dist/AutoBook-0.6.4-release.apk` 45,812,545 B |
| APK 版本 | 两包 `aapt2 dump badging` 均 `versionCode='23' versionName='0.6.4'`，minSdk 26 / compileSdk 35 |
| 签名 | release `bedb36b7…8a5ed6`（与前版一致）；debug `08b0f9a0…566cd7`（Android Debug 证书） |
| 前景层确实换了 | debug 包内 `ic_launcher_foreground.png` 五档 **8590/14634/20982/35825/53182** 字节，旧包是 3659/6591/10242/20653/34715；与源文件字节一一相同 |
| release 也被换掉 | 资源被 R8 重命名：`aapt2 dump xmltree … --file res/BW.xml` 的 `<foreground>` → `@0x7f0c0001` → 解包出的 `res/as.png`（432x432、`opaque=False`、中心 `srgba(48,129,250,1)`） |
| 背景色 | `aapt2 dump resources` 里 `0x7f050040 color/ic_launcher_background () #ffffffff` |
| 旧提示无法误判 | 旧包前景层是 34715 字节，新包 53182；`unzip -l … | grep ic_launcher_foreground` 一眼可分 |

## 限制

1. ~~**没有任何真机/模拟器证据**~~ → **2026-10-22 17:08 用户真机补证**：装上 0.6.4 release 后
   桌面图标确实变成白底彩色圆环，无需清缓存。取证时 `adb devices` 为空，该结论是用户口头确认、
   **没有截图**，所以 `outputs/` 依然为空。三种图标形状（圆形/方形/圆角矩形）下边缘有没有被切掉，
   仍**未验证**。
2. 以上所有结论原本只到"APK 里的文件确实换了"；"桌面显示换成了新图"这一层由用户真机补证。
3. 68dp 安全区是从"自适应图标可见区不超过中间 72dp 圆"这条经验规则推出来的，没有在真机三种裁切下量过边距。
4. 单测覆盖不到图标资源（JVM 单测跑不了 aapt / 资源打包），所以 167 全绿与图标正确性无关。
5. 本机 `~/.gradle` 是只读挂载，构建必须在同一条 shell 里 `cp -a ~/.gradle/{caches,wrapper,daemon,gradle.properties} /tmp/gh7/`
   再 `GRADLE_USER_HOME=/tmp/gh7`；`/tmp` 是 tmpfs，跨 bash 调用会丢。
6. 本仓库不是 Git 仓库（`git status` 报"不是 Git 仓库"），无法用 commit 定位这次改动。

## 下一步

1. ~~装 `dist/AutoBook-0.6.4-release.apk` 到真机，确认桌面图标是新图~~ **已完成（2026-10-22 用户实测）**；
   若日后仍有旧图，先查 `unzip -l` 的前景层字节是不是 53182，再查桌面缓存/重启桌面。
2. **待做**：三种图标形状各看一遍裁切边距（68dp 安全区）。
3. **待做**：补一份带 `outputs/` 截图的**功能验收** run（本轮无设备，图标结论只有口头确认）。

来源 run：本次（`runs/20261022-163800-icon-rebuild/`）；变更记录：`openspec/changes/rebuild-launcher-icons/`；
APK：`dist/AutoBook-0.6.4-debug.apk`、`dist/AutoBook-0.6.4-release.apk`。
