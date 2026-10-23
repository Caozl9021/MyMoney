# 设计
主代理维护AGENTS、agent、PROGRESS、历史计划入口和总索引；gpt-6.1-sol/medium代理A维护README/QUICKREF/DEPLOYMENT/TEST_REPORT；代理B维护Root/LSP/iCost/本地规则文档。代理互不越界。
重复HANDOVER与ENHANCEMENT_SUMMARY审读后将独有信息归入AGENTS/PROGRESS/LSP，迁移全部活动引用再删。IOS27_UI_PLAN保留历史设计，不将未经核查的系统设计宣称作为当前规范。PLAN_0.5保留旧需求与ID。
只读核查配置、脚本、源码与最新run；文档链接和版本事实检查替代业务测试。旧验证材料不覆盖。OpenSpec CLI不可用则手工检查并披露。
