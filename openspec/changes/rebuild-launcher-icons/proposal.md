# 彻底重建桌面图标：修掉自适应前景层从未更新的问题

> 历史变更记录：描述该轮目标与当时状态，不作为当前操作指南；最新实现/验收边界见 `docs/PROGRESS.md`，后续任务见 `docs/PLAN_1.0.md`。保留原始需求与验证过程，不代表用户已验收。

当前补遗状态：**0.6.5（24）留白调整已构建并核查，待用户验收**。见文末补遗与 `runs/20261022-173414-icon-padding/`。下方0.6.4状态为历史记录。

状态：**已交付并经用户真机确认图标生效**（2026-10-22：装上 0.6.4 后桌面立刻变成白底彩色圆环，
无需清缓存）。残余：68dp 安全区在三种裁切形状下的边距未逐形状核对；当轮 `adb devices` 为空，
确认方式是用户口头反馈、无截图。

0.6.2 与 0.6.3 的文档都写"换上了新图标"，但用户真机上的桌面图标一直是旧的。核查结论是
**不是缓存问题，是资源从来没被真正替换过**：

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`（与 `ic_launcher_round.xml` 同结构）是
  `<background android:drawable="@color/ic_launcher_background" />` +
  `<foreground android:drawable="@mipmap/ic_launcher_foreground" />`。
  **API 26+ 的桌面只渲染这两层**；`mipmap-*/ic_launcher.png` 只是 legacy 兜底。
- 新图只在 2026-10-22 13:48 被写进 `ic_launcher.png` / `ic_launcher_round.png`；
  `ic_launcher_foreground.png` 五档的 mtime 全是 2026-10-21 15:03，`values/colors.xml` 同样是
  2026-10-21 15:03（旧背景色 `#C0E1D7`）。
- 两个生成脚本（`generate-icons.sh` 用 `convert`、`regenerate-icons.sh` 用 `magick`）都只写
  legacy 那两个文件，**从不生成 `ic_launcher_foreground.png`，也不改 `colors.xml`**。

按用户要求"把原来的图标彻底删掉"，本变更不做新旧并存、不保留旧图标作兜底，而是把 15 个旧 PNG
全删后用一份脚本重建整套。

做法：

1. 删除 `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/` 下全部 `ic_launcher.png`、
   `ic_launcher_round.png`、`ic_launcher_foreground.png`（共 15 个）。
2. 重写 `generate-icons.sh`：一次生成 legacy（48/72/96/144/192，不透明）+ 自适应前景层
   （108/162/216/324/432，`-fuzz 5% -trim` 去白边 → 白底转透明 → 缩到 68dp 安全区 → 居中放到
   108dp 透明画布）+ 自适应背景色（`#FFFFFF`）。末尾自带校验，尺寸或透明性不符就 `exit 1`。
3. 删除 `regenerate-icons.sh`（与 generate 重叠、同样漏前景层，是这次事故的直接来源）。

版本 0.6.4（23），产物 `dist/AutoBook-0.6.4-{debug|release}.apk`。

范围外（本变更刻意不做）：不放宽 68dp 安全区（自适应图标在不同形状裁切下会切边，安全区不能靠猜）；
不改 legacy 图标的圆角造型（`ic_launcher_round.png` 目前直接缩放方图，圆角由桌面裁切）；不做
`res/mipmap-anydpi-v33` 的单色主题图标（`monochrome` 层，需要另一份单色矢量素材）。

证据：`runs/20261022-155747-guardian-hardening/` 之前那一轮只有单测与产物元数据；0.6.4 的
核对照样是代码与 APK 元数据级别（`unzip -l` 的字节数、`aapt2 dump xmltree` 的
`foreground` 指向、`opaque=False`、中心像素色），`outputs/` 无法提供真机截图。


## 2026-10-22 留白调整补遗（0.6.5，待验收）

目标：按用户反馈缩小图案，增加白色留白；沿用原图与白底，发布 0.6.5（24）。范围为生成脚本、15 个图标资源、版本及交付记录。状态：待验收。
