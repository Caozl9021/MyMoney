# 悬浮 dock 调整核查（待验收）

保持胶囊 68dp 高、左右50dp、底距导航栏16dp。阴影使用独立软件位图缓存及18dp边距，避免 CLEAR 接触屏幕画布；统一滑块/图标/点击网格；标签改12sp，中性灰选中块，收敛动画，补全Tab角色及选中语义，重复点击不震动。保存每个Tab的可保存状态及Activity所选Tab，多个ComposeView赋稳定ID；深色主题补轻微中性底色。旧版仅去掉重复内边距，没有扩展兼容工作。固定折射/色散/模糊参数，不新增强度滑块。

## 验证

- 指定 gpt-6.1 Sol / medium 子代理完成实施，主代理核查源码、截图和结果。
- :app:testDebugUnitTest：111 tests，0 failures，0 errors，0 skipped。
- :app:assembleDebug：BUILD SUCCESSFUL。debug APK 已覆盖安装到现有模拟器，未清数据。
- Android 15 / API35、1080x2400：浅色外阴影圆润且内侧无黑斑；深色空白子页胶囊可辨；切换统计月份至9月后跨Tab保留；设置外观子页和列表滚动位置跨Tab保留；切换主题重建Activity后仍停留设置外观页且设置Tab选中。
- 设置页末尾说明底部y2095，dock顶部y2117，末项可完整露出。四Tab中心约245/441/638/835像素，等距居中。
- AndroidRuntime错误日志未发现本次崩溃。

## 范围与限制

未验证低于Android13；未进行TalkBack语音和真实硬件触感验证；模拟器账本为空，长列表使用设置页验证。OpenSpec CLI当前不可用，手工核对proposal/design/spec/tasks，未运行CLI校验。当前目录Git元信息不可用，提交版本记录为空，使用源文件及产物SHA256定位本次快照。

来源运行：runs/20261022-092633-dock-review。测试日志、XML、前后截图及源码差异保存在该目录。本次未变更versionName/versionCode，保留0.5.8/17；使用单独文件名区分APK，不覆盖旧dist产物。最终验收权属于用户，状态待验收。
