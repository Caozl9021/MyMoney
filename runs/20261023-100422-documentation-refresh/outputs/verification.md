# 文档更新核查（待用户验收）

仅文档变更，无业务代码/资源/签名/版本/构建改动。

- 根入口README、AGENTS、agent、速查、部署、测试报告与六篇专题已核对当前源码和既有证据。
- PROGRESS当前状态与历史区分；plan0.5/早期UI草案保留历史标识，plan1.0仍为未实施规划；历史OpenSpec proposal增加当前入口提示，未改旧任务/验证记录。CHANGELOG保留历史发布内容。
- 新增docs/README.md统一导航；删除HANDOVER.md、docs/ENHANCEMENT_SUMMARY.md两个重复入口，内容快照在intermediate/before，有效内容合并入交接/部署/LSP/进度；未清理输入、旧run或deliverables。
- 关键订正：四种候选Hook重载回退、检测标记不证明拦截、Root超时边界、自动弹卡非自动入库、单笔iCost先本地保存与分享失败提示边界。
- 本地Markdown链接（剔除代码块/行内代码后的真实链接）全部有效；两个现有APK摘要仍与原证据一致。详情见documentation-checks.json。
- 不运行与文档无关的单测/构建，221测试是引用既有run而非本轮重跑。OpenSpec CLI不可用，手工核查四文件结构。

Root/LSP真实ROM、微信瞬时付款投递、iCost最终导入及完整辅助功能实测缺口未因文档更新而消失。
