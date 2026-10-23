# 0.6.6 dock 视频参考优化验证

状态：实施与构建验证完成，主代理核查完成、待用户验收。

- `:app:testDebugUnitTest --offline --rerun-tasks`：167/0失败/0错误/0跳过，18类，16s；XML在test-results。
- 首次debug成功、release旧增量缓存无法解析未改顶层函数；保留logs/build.log。强制双构建`--rerun-tasks :app:assembleDebug :app:assembleRelease`成功，139任务全部执行，1m23s；logs/build-rerun.log。
- 两包aapt2元数据均0.6.6/25，apksigner验证成功且分别与0.6.5相同签名；见apk-verification.json。
- 无Git仓库，无法记录提交版本。OpenSpec CLI不可用，手工核对proposal/design/tasks/specs四文件、T9–T11与场景。T12已由主代理完成代码和证据复核。
- 主代理沙箱外adb检查无连接设备；未安装、未卸载、未清数据、未启动新模拟器。outputs为空，无真实设备视频或截图。167测试主要覆盖账单业务，不能证明dock视觉/帧率。

## 实施静态核查

- 点击位置animateFloatAsState弹簧(.92/480)可重定向；距离驱动最大8dp横伸/2dp纵压，x钳制保证首末外缘≥3dp。静止保留3dp四边留白。
- 图标文字独立于形变背景；selected semantics与重复点击跳过触感保持；本轮无新增拖动手势。
- 68dp高、50dp侧距、16dp底距+导航inset及现有SaveableStateHolder未改。
- 玻璃16dp/12dp/.16/blur5；浅深tint .10/.28。阴影alpha22/12分两层缓存，CLEAR仅在软件bitmap。0.65dp共享中性高光不采系统accent。
- API<33改专用Box，避免GlassSurface额外阴影，宿主elevation5dp；共享动画与细高光。
- 浅色pageBg #F7F7F7，白卡层次保留；深色pageBg保持黑色。

## 仍待核查

设备验证浅深主题透明度、背景滚动折射、快速反向/连续点击、边缘及投影裁切、导航遮挡、页面保存、API<33兜底。用户最终验收尚未完成。

## 主代理独立复核
重新汇总18类XML：167测试、0失败/错误/跳过；重新用aapt2核实release 0.6.6/25。逐段检查动画布局范围、重复点击过滤、独立图标层、玻璃薄底色、缓存阴影CLEAR及fallback单投影，未发现需补齐代码项。视觉观感和帧率不能由这些检查证明，仍待用户真机验收。
