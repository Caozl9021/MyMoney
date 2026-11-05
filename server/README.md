# My Money 家庭服务

Python 3.12+、PostgreSQL，提供设备配对、规则同步及完整JSON8/JSON9账本快照（JSON9含只读删除日志）。六张业务表位于专用schema，文件卷保存账本快照正文；不提供逐笔账单双向同步。

推荐使用[容器部署说明](../deploy/home-sync/README.md)。本地开发：

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -c constraints.txt '.[test]'
# 从私有环境配置提供MYMONEY_DATABASE_URL、MYMONEY_DB_SCHEMA和MYMONEY_BACKUP_DIR
.venv/bin/alembic -c alembic.ini upgrade head
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8080
```

`MYMONEY_DATABASE_URL`必须显式配置，不内置数据库凭据。应用启动、迁移和测试都不应打印连接串、设备令牌、配对码或账本原文。实际.env、Python环境、账本文件及安装缓存不提交；参考`deploy/home-sync/.env.example`填写自己的配置。

## 测试

在专用测试数据库设置`MYMONEY_TEST_DATABASE_URL`，再运行：

```bash
.venv/bin/pytest -q
```

测试会创建和清理`mymoney_home_test`、`mymoney_home_backup_test` schema，必须使用专用测试数据库或已确认不含用户数据的环境，不能指向同名业务schema。测试包含幂等、冲突、鉴权、备份上传/分页/下载、故障回滚与孤立文件处理。

## 与当前App候选的关系

2026-11-05 App候选1.9.0/143使用JSON9/Room10，服务源码支持JSON8/9；本轮只更新文档，不证明家庭生产已升级或failed已解决，不运行迁移/容器部署。自动任务M5未实施。历史服务测试和部署步骤按原授权与环境执行，上传JSON9前核对实际服务版本与备份卷。
