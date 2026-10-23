# Auto.jpg全入口品牌资源（待验收）

- 默认来源改Auto.jpg（1254×1254 opaque），原图sha在manifest，生成前后相同；未AI编辑。
- 按白边5%容差裁1067×1064+94+103；彩色分支仅边缘连通背景去白，保留封闭四彩圆角边框、内部白色与所有RGB。
- 单色独立source副本以10%白色容差移除近白色作为负空间，抑制JPEG白噪点；边框、字形和彩色内容组成白alpha遮罩，未将封闭内部填为实心块。量化见negative-space-verification.json。
- 五档legacy／adaptive／small／brand-color和独立mono共26PNG全部重新生成，桌面54dp／legacy55%、small18/24dp、color40/48dp。尺寸、透明性、中心偏差≤1px、小图全部RGB白校验通过；mono与独立负空间source的目标alpha逐像素差0，不要求与彩色alpha相等。
- dry-run前后所有资源sha和mtime相同；bash -n通过。默认、可选source／res-dir接口保持。
- previews/all-brand-preview对应outputs/all-brand-preview.png，已目视彩色边框/内部白色及单色负空间正常；静态预览不代表设备截图。
- 引用审计覆盖通知四处、QS、shortcut、widget、确认卡及application launcher；本次仅脚本／生成资源／CHANGELOG改动，不改其他代码或版本0.6.8/27，无Gradle，由主代理统一测试构建。历史包和运行证据保留，无卸载或release安装。
- OpenSpec CLI不可用，人工核查；用户验收仍待完成。
