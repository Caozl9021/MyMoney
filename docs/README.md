# 文档索引

145 J阶段：移除未消费账本查询和旧会话写盘；过期观察释放自有预留、保留节流，等待参数不变。1105JVM/82隔离Room/3实际卡回归通过；当前交付和未测边界见[验证](../runs/20261105-193908-capture-cleanup/outputs/verification.md)，待用户验收。

当前候选1.9.0/145，待用户验收；更新日期2026-11-05。I阶段144已实施并同签名覆盖真机，真实应用行为待验收。

| 当前入口 | 内容 |
| --- | --- |
| [PROJECT_SCOPE](PROJECT_SCOPE.md) | 当前版本、两种识别分工、数据与未测边界 |
| [ACCEPTANCE](ACCEPTANCE.md) | 144再次确认/账本标签、143外观与G阶段真实使用待验收项目 |
| [PROGRESS](PROGRESS.md) | 各构建实施/验证/验收状态与历史证据 |
| [PLAN_1.0](PLAN_1.0.md) | 当前剩余项及原1.0发布合同 |
| [OPENSPEC_STATUS](OPENSPEC_STATUS.md) | 35个变更的当前关联与历史范围 |
| [架构](../ARCHITECTURE.md) | 准入/协调/投递/存储和OCR生命周期 |
| [验证](../TEST_REPORT.md) | JVM、合成设备、真实支付边界 |

| 功能与工程 | 文档 |
| --- | --- |
| 页面OCR、选图及Q阶段 | [OCR_ASSISTANCE](OCR_ASSISTANCE.md) |
| 识别规则、备注与学习 | [LOCAL_RULES](LOCAL_RULES.md) |
| UI/重复提示间距 | [UI_COMPONENTS](UI_COMPONENTS.md) |
| 资产/账期 | [ASSET_MANAGEMENT](ASSET_MANAGEMENT.md)、[LEDGER_MONTH_PERIOD](LEDGER_MONTH_PERIOD.md) |
| 导入导出 | [QIANJI_IMPORT](QIANJI_IMPORT.md)、[ICOST_IMPORT](ICOST_IMPORT.md) |
| 家庭服务/更新 | [FAMILY_BACKUPS](FAMILY_BACKUPS.md)、[M1–M5计划](HOME_SYNC_AND_APP_UPDATE_PLAN.md) |
| Root/凭证 | [ROOT_PLAN](ROOT_PLAN.md)、[LSP_MODULE](LSP_MODULE.md)、[LSP_SCOPE_CONFIG](LSP_SCOPE_CONFIG.md) |
| 构建/包与版本 | [部署](../DEPLOYMENT.md)、[PACKAGING](PACKAGING.md)、[VERSIONING](VERSIONING.md) |
| 测试环境 | [TEST_ENVIRONMENT](TEST_ENVIRONMENT.md)、[旧环境清理记录](LEGACY_EMULATOR_CLEANUP_REVIEW.md) |

M3/M4交接、BUG记录和旧self入口保留为历史；历史图和旧run不表示143已在真实支付/各ROM/API上通过。完整矩阵与用户最终验收继续开放。
