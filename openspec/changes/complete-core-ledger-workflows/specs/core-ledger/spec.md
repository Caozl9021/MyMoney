# Core ledger

## ADDED Requirements

### Requirement: 省电节拍有效
轮询 MUST 在有效抓取后采用正常间隔，在无前台/非白名单/内容未变/失败时退避至至少3秒，息屏时不得采集，解锁可恢复。
#### Scenario: 空转与息屏
- WHEN 前台不在白名单或页面未变化
- THEN 使用空闲节拍；息屏使用30秒休眠且跳过su。

### Requirement: 截断诊断贯通
所有采集来源 MUST 将条数/字符/节点上限截断状态送至UiSnapshot，成功与失败结论 MUST 显示截断。
#### Scenario: 超限页面
- WHEN 文本超过采集/归一化上限
- THEN 标记truncated并保留有序受限文本，诊断结论可见。

### Requirement: 退款不冒充收入
明确的成功退款 MUST 识别为REFUND，优先于正号收入；退款申请/失败/取消 MUST 不成为成功退款。退款 MUST 独立显示、备份恢复保留，结余纳入退回现金但收入不增加。
#### Scenario: 退款到账
- WHEN 成功退款50元
- THEN 显示退款50元，收入不增加；既有历史记录不自动改写。
#### Scenario: 导出退款
- WHEN iCost导出范围含退款
- THEN 明确提示模板限制，不导出错误类型、不静默丢行、不崩溃。

### Requirement: 新增与编辑账单
用户 MUST 可在明细页新增并点击编辑，修改金额/类型/商户/分类/账户/备注；无效金额禁止保存；编辑保持原id/createdAt，关闭不写入。
#### Scenario: 新建并编辑
- WHEN 新建19.99元再改为29.99元
- THEN 分别准确保存1999/2999分且仅有同一笔记录。

### Requirement: 删除可撤销
滑动删除 MUST 提供至少5秒撤销，撤销恢复原记录且不重复；连续删除及离页不得丢失尚未提交操作。
#### Scenario: 撤销删除
- WHEN 用户删除一笔后点击撤销
- THEN 记录和总额恢复，重开后仍在。

### Requirement: 可核查交付
交付 MUST 提供相关回归测试、debug/release构建、实际APK版本及模拟器证据，状态待用户验收。
