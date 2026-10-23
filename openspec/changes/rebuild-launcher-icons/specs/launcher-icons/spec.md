# Rebuild launcher icons

## ADDED Requirements

### Requirement: 自适应图标的前景层必须与 legacy 层同源同期
`mipmap-anydpi-v26/ic_launcher.xml` 引用的 `@mipmap/ic_launcher_foreground` MUST 由同一份源图、
同一次生成流程产出，MUST NOT 出现"legacy 已更新、前景层仍是旧图"的状态。
#### Scenario: 更换源图后重新生成
- WHEN 开发者运行 `./generate-icons.sh`
- THEN 5 档 `ic_launcher.png`、5 档 `ic_launcher_round.png`、5 档 `ic_launcher_foreground.png`
     与 `values/colors.xml` 全部被重写，任一文件缺失或尺寸/透明性不符时脚本以非 0 退出。

#### Scenario: 只用 legacy 图标的旧脚本
- WHEN 仓库里存在只生成 `ic_launcher.png` / `ic_launcher_round.png` 的脚本
- THEN 该脚本 MUST 被删除或修正，否则会再次造成"真机图标不更新"且难以察觉。

### Requirement: 自适应前景层必须带透明通道，背景色必须与源图底色一致
前景层 MUST 把源图的纯色底转为透明（`opaque=False`），自适应背景色 MUST 取自源图底色，
两者合成后不得出现可见的色块边界。
#### Scenario: 圆形裁切
- WHEN 桌面以圆形遮罩渲染自适应图标
- THEN 图形外的区域由 `@color/ic_launcher_background` 填充，不得出现一圈与源图底色不同的边。

#### Scenario: 校验透明性
- WHEN 校验脚本检查前景层
- THEN 使用 `magick -format "%[opaque]"` 断言为 `False`；legacy 图标断言为 `True`。
      MUST NOT 用 `%[channels]` 与 `RGB`/`RGBA` 字符串比较（ImageMagick 7 返回 `srgb 3.0` / `srgba 4.0`，比较必然失败）。

### Requirement: 前景内容必须限制在安全区内
前景层画布 MUST 为 108dp 的 1x–4x（108/162/216/324/432 px），非透明内容的包围盒
MUST 落在居中 54dp 的安全区内（0.6.5 替代此前 68dp 需求）。
#### Scenario: 三种裁切形状
- WHEN 桌面分别用圆形、方形、圆角矩形遮罩
- THEN 图形边缘不被裁掉（安全区按圆形可见区 72dp 再留余量取 54dp）。

### Requirement: 图标变更必须可核对且不得只凭文件名断言
交付时必须能用不读图的方式证明"新图确实进了包"：debug 包按名字列出条目与字节数，
release 包（资源被 AGP/R8 重命名）用 `aapt2 dump xmltree` 追 `foreground` 指向并用
`aapt2 dump resources` 核对背景色。
#### Scenario: release 包资源重命名
- WHEN release 包中 `mipmap` 目录名不可见
- THEN 通过 `aapt2 dump badging` 找到 `application: icon='res/XX.xml'`，再用
     `aapt2 dump xmltree --file res/XX.xml` 取其 `foreground` 引用并验证对应 PNG 的
     尺寸、`opaque`、中心像素色。

### Requirement: 未在真机确认前不得声称图标已生效
文档与 UI MUST 区分"资源已打进 APK"与"真机桌面已刷新"。
#### Scenario: 无设备可用
- WHEN 当轮 `adb devices` 为空
- THEN 相关文档 MUST 标注"真机桌面是否刷新未验证"，不得写成"图标已换"。
- 2026-10-22 起该场景已由用户真机确认（装上 0.6.4 即刷新），文档 MAY 写成"图标已换"，
  但 MUST 同时标注确认方式为**用户口头反馈、无截图**，且不得据此宣称"三种裁切边距也已验证"。


## 2026-10-22 留白调整补遗（0.6.5，待验收）

### Requirement: 图案四周留白增加
前景图案最大边为 54dp（108dp 画布），保持比例并居中；legacy 图案最大边约为画布 55%，背景为白色。

#### Scenario: 用户覆盖安装新版
- WHEN 安装 0.6.5
- THEN 仍显示原有彩色图案，图案较 0.6.4 缩小且白边增加。

#### Scenario: 资源与交付可核查
- WHEN 运行生成和构建
- THEN 五档资源尺寸/透明性/包围盒验证通过，单测通过，debug 与签名 release APK 可用；真机观感由用户验收。
