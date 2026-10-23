# 0.6.7（26）新图双包核查（待验收）

- 来源：用户提供 Auto.png，源 SHA-256 见 manifest.json；运行前后源哈希一致，未 AI 编辑。
- 透明边缘 alpha≤1% 置0后裁切，726×702+274+329；保留其余 alpha 和内部 RGB，不按白色抠图。
- 五档15张PNG、独立monochrome、白底全部同步生成。432px前景和单色 bbox=216×209+108+111，居中偏差≤1px，alpha逐像素差0。白底预览见 new-icon-white-preview.png。
- dry-run 前后所有资源文件SHA与mtime不变；支持默认Auto.png、SOURCE环境变量、--source/位置参数、--res-dir。
- opaque兼容采用额外白边加边界连通白色floodfill，保留封闭内部白色。旧源AutoBook-converted.png已不在项目，因此从0.6.6debug提取旧前景并合成白底作兼容fixture，在/tmp输出全套校验通过；不覆盖新图。
- --offline --rerun-tasks :app:testDebugUnitTest：204单测／22类，0失败／0错误／0跳过，39s。XML在test-results，原始日志logs/tests.log。
- 随后离线 assembleDebug 与 assembleRelease 成功，19s；未改业务源码，未增加依赖。
- 两包 aapt2 包名com.example.autobook、versionName=0.6.7、versionCode=26；签名验证通过，release证书与0.6.6相同。完整哈希、字节数、证书摘要见apk-verification.json。
- 两包Manifest保留Main、磁贴、widget、shortcut和Xposed元数据；assets/xposed_init正确，入口与系统组件仍在DEX；资源表解析实际路径后对照16张PNG像素差均0（含release重命名资源）。API33两种adaptive-icon均含monochrome。
- 旧dist包保留。昨日候选尚无dist0.6.7，未发生同名替换。此次未安装release，未卸载或清除模拟器数据。
- OpenSpec CLI不可用，遵循既有四文件计划人工核查。此处只证明生成、测试、构建及包内容，不代表真机图标观感或功能验收；安全锁屏／完整TalkBack／低API及真机能力边界沿用预览记录，最终待用户验收。
