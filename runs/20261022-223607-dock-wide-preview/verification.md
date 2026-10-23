# T13 扁长dock预览实现

状态：实施和构建完成，待主代理模拟器核查及用户验收。

- `AbSize.tabBarHeight = 60.dp`，新增统一 `tabBarSideMargin = 36.dp`，真假玻璃宿主共用。
- Tab图标与标签间距2dp；底部16dp+inset、3dp滑块边缘、材质和动画保持。
- 单测：167/0失败/0错误/0跳过，18类；XML与汇总在test-results。
- 单测通过后只执行`:app:assembleDebug`，构建成功；版本仍0.6.6/25，未写dist，未构建release。
- debug APK：`/home/caozl/Projects/AutoBook/app/build/outputs/apk/debug/app-debug.apk`
- SHA256：`5b0ff6b3720fd0fab5f023ac50bb8226060860453e14953a6fab66197f71b226`
- 初次沙箱内Gradle因wildcard IP初始化失败；使用现有/tmp缓存获准提权后单测和debug构建成功。
- 未操作adb/模拟器；视觉与交互由主代理继续T14。OpenSpec CLI沿计划说明不可用，本次手工核对。
- 项目目录无Git仓库，无法提供提交版本。

## 用户预览追加：缩小dock图标文字

选中23dp/未选中21dp，图标容器24dp，仅dock标签11sp。60dp/36dp及点击区域/语义保持，业务页面字号未变。

167单测/18类/0失败错误跳过；之后assembleDebug成功。新日志为`logs/small-icons-unit-tests.log`、`logs/small-icons-assemble-debug.log`，XML快照`test-results/small-icons/`。未构建release、未写dist、未改版本、未操作adb。

新debug SHA256：`90102b62e7cae40585cfddf11f1a8f9a46fcf22bb8dfee20baba00abb51a5f36`。APK路径同上。

## 主代理可见模拟器核查
autobook_api35（emulator-5554），1080×2400/420dpi，安装debug成功且保留数据。首页/明细/统计/设置入口点击通过，outputs内保存截图；图标与标签略缩小，无截字或导航重叠，保留首页前台。无release/dist新包，版本未变，待用户验收。OpenSpec CLI不可用，已手工核查。
