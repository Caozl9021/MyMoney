# My Money 家庭服务

Python 3.12+、PostgreSQL，提供设备配对、规则同步及完整JSON8账本快照。六张业务表位于专用schema，文件卷保存账本快照正文；不提供逐笔账单双向同步。

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
