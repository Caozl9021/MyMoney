# AutoBook 0.6.1（20）模式选择核查

状态：实施及主代理核查完成，待用户验收。

## 实现

首次模式未明确时显示Root/无障碍二选一，点击继续才保存，首次页隐藏原生dock；已选模式重开、Activity重建和升级保留，用户可在设置更改。选择保存在SharedPreferences capture_mode，识别总开关enabled独立，暂停不清模式；卸载或清数据会重置。旧root_mode=true迁移Root，可靠已配置无障碍时迁移无障碍，未知时不猜。

CaptureModeManager统一模式/启停与StateFlow，App/开机/服务重建均检查mode+enabled。Root选中时无障碍事件与已排队采集不处理；无障碍模式不探测su、不调用Root OCR，stop也不执行su清理。Root授权失败后停服务并保留选择及提示，不随普通prefs变更循环请求；主动重选、开启识别或授权成功触发明确重试。通知停止暂停总开关，不清模式。

首页、设置状态、权限页和OCR提示与模式一致，Root不要求无障碍，无障碍不要求Root；未真正就绪时不会显示正在监听。备份恢复不覆盖已有明确模式选择。账本和dock造型不变。

## 验证

149个单测全部通过（新增8个模式迁移/首次启用/暂停切换/互斥/备份策略测试）；debug和签名release构建成功。两个APK和模拟器安装元数据均0.6.1/20，release证书SHA256与前版一致：bedb36b7527aa4759262d8b737d7031b57f1db191c68eab4d55829f0598a5ed6。

API35模拟器保留原数据覆盖安装：未选模式显示首次页且无dock；只点选项仍停留，继续后进入首页；选择无障碍后force-stop重开不再出现选择页。设置切Root后权限页无无障碍服务行；Root授权失败时服务停止，状态提示Root未运行且不误称监听。暂停Root后重开仍ROOT/disabled，改为无障碍仍保持暂停，权限页没有Root要求。最终覆盖安装修订版也保留模式；切回Root启用后拒绝授权状态和首页仅提示Root/悬浮窗。运行日志无崩溃。未清账本或应用数据，最终模拟器留Root选择供查看。

截图与XML在本run的outputs，配置前后快照和root-denied-services.txt提供状态证据。

## 限制

真实Root成功授权后的支付页识别、开机恢复、功耗以及真实无障碍服务采集仍需手机验证；互斥路径经代码守卫和纯策略测试。旧配置迁移与备份策略经单测，未单独修改设备旧模式键模拟升级。OpenSpec CLI不可用，手工核对结构和场景；Git元信息不可用，记录源文件与APK摘要。模式永久保存不代表系统权限不会被系统/用户撤回，权限失效不丢选择。

来源run：runs/20261022-101537-capture-mode；计划：openspec/changes/choose-capture-mode；APK：dist/AutoBook-0.6.1-debug.apk、dist/AutoBook-0.6.1-release.apk。
