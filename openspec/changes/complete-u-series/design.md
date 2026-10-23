# 实施决定
沿用当前手写Compose/原生兄弟dock，不加导航/Glance依赖。Appearance独立持久化及Compose状态，Theme统一提供LocalReduceMotion和字体倍率；系统动画设置监听且合并应用偏好，动画组件显式响应，避免只改某一页。普通字号可增长，金额局部约束；最大宽度放scaffold及关键自定义列表，避免背景采样出现窄条。
分工：A负责外观/设计组件/Settings/Confirm/Stats/Main与dock动画；B负责Home/Bills/编辑/Summary/BillRow及引导、共享过渡、翻月；C负责系统入口/Manifest/单色资源和纯逻辑。新公共接口A提供ui.design.LocalReduceMotion:Boolean及Appearance；B的BillsScreen增加可选requestAdd信号，由A在Main消费intent action com.example.autobook.action.ADD_BILL（也可既有safe onNewIntent信号）。C入口统一该action到MainActivity；双方协商精确接口。A/B不碰Manifest，C不编辑Main。
所有新增入口复用现有手动保存路径，自动学习不扩大；确认卡不得取消保存失败保留队列保护。状态更新后widget刷新钩子由C定义，主代理整合少量App/BillWriter/CaptureModeManager调用，代理勿覆盖这些文件。
OpenSpec CLI不可用，沿用已有目录四文件结构手工校验。对历史计划的旧滑块/全宽遮罩、强制Root引导作当前需求修正；U1/U2复核保留，U3-U10完成后仍待验收。

## 0.6.7发布构建
复用仓库外现有签名配置，不输出凭据。先跑204单测再debug/release构建；aapt2核对包名与0.6.7/26、apksigner验证签名并与0.6.6 release证书对比；核对R8产物入口及资源。使用独立runs记录日志、哈希与交付快照。OpenSpec CLI仍不可用，人工校核。

## 2026-10-23 新图及续包
Auto.png按真实alpha处理；透明输入不以白色抠除（保留图形内部色彩/细节），所有legacy/adaptive/monochrome由同源生成。旧不透明白底输入仍兼容。独立新run记录源图哈希与两包，发布版本维持尚未交付0.6.7/26。
