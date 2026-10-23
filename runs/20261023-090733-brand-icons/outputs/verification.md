# 全入口品牌图标静态核查（待验收）

- Auto.png未修改；SHA-256及26张生成PNG的摘要见resource-audit.json。统一脚本保留launcher54dp／legacy55%／透明噪点1%规则。
- 新增五档通知／磁贴白alpha小图：24dp画布、18dp主体；五档彩色品牌图：48dp画布、40dp主体。全部透明、非空、最大边受限、内容居中偏差≤1px；small所有RGB通道均白色，含抗锯齿边缘。
- 通知确认／Root常驻／守护常驻／守护异常四处smallIcon改品牌小图，largeIcon来自新彩色品牌；不改渠道、文案、权限、优先级、点击动作或采集逻辑。异常语义仍由标题与正文表达。
- QS在Manifest与更新时均引用新small图；shortcut使用新彩色图；widget头部24dp ImageView显示新品牌。旧QS通用框线和shortcut加号资源已删除，未改导航／勾选／功能图标。
- 确认卡头部钱包品牌占位替换为同源彩色Image，44dp白底12dp圆角、contentDescription=AutoBook；金额、商户、业务类型文案及功能图标保持原样。
- 新BrandIcons助手仅解码通知large bitmap，无额外行为。Android设置页／最近任务默认沿用application launcher；未发现其他实际存在的品牌图像入口遗漏。不存在的关于页未新增。
- dry-run前后资源所有sha与mtime相等，shell语法通过，Manifest/shortcut/widget三XML均解析通过。日志含每张几何和透明校验；mono与彩色前景alpha差0。
- small深浅预览模拟系统白／黑着色，保持透明轮廓；预览不是设备截图。未跑Gradle，留主代理统一单测及构建；设备通知、QS、小组件显示和细小轮廓识别待实测。未更新版本、CHANGELOG或历史包，未卸载数据。
- OpenSpec CLI不可用，按四文件计划人工核查，最终待用户验收。
