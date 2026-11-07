# My Money 个人云备份服务

## 155进度复核（2026-11-06，仅文档）

当前候选 **1.9.0/155，Room11/JSON10，pending_acceptance**。删除历史、更多下移右对齐、context请求精简和Go HTTP复用主体已实现；155尚无用户验收。154返回修复/原“更多正常显示”分项通过保留。

本轮复核候选SHA五项通过，现存JVM XML为1180/0/0/0；服务端76 pytest、部署镜像155和Mi10显式直连实测引用原记录，未重跑/操作在线环境。已有实机证据是单选删除，批删能力不等于真实多选已测；内嵌通道和分段耗时未闭环，HTTP请求减少不等于连接慢已解决。

静态待收尾：R1删除重试未保留operationId；R2删后分页仍用旧offset；R3文件已不在时回收状态不能收尾；R4新备份未失效context缓存。详见[实现复核与后续](../runs/20261106-181513-155-progress-review/outputs/implementation-review.md)。本轮只同步文档，不改源码/APK/旧证据，不操作手机或服务。

Python3.12+、PostgreSQL和文件卷。按原155记录部署镜像`mymoney-home-sync:cloud-backup-155`，新增删除/context协议；保留App153/154既有接口兼容：JSON8/9/10完整账本快照、稳定设备来源、一次性短时连接码、授权接替和严格本来源备份隔离。154只改App导航；155增量迁移增加墓碑/回收列与删除操作表。原规则/ledger API与表兼容保留，但退出当前主UI；不把旧六表描述当完整当前结构。

## 本地开发与迁移

推荐先读[部署说明](../deploy/home-sync/README.md)。

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -c constraints.txt '.[test]'
# 从私有环境提供MYMONEY_DATABASE_URL/MYMONEY_DB_SCHEMA/MYMONEY_BACKUP_DIR
.venv/bin/python -m app.migrate
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8080
```

`app.migrate`幂等应用当前schema，正常升级不清表。全量依赖构建曾受constraints与FastAPI组合冲突影响；本机增量镜像依赖既有基础镜像，不代表全新机器已可复现此依赖安装。凭据不内置、不写日志；参考本机配置模板，不打印连接串、管理令牌、授权码或账本正文。

## 身份与备份端点

`/v1/backup-capabilities`声明新身份协议和ownBackupsOnly。管理端`/v1/admin/connect-code`签发完整MMJOIN2码；手机authorize后仅得到登记grant和来源辨认元数据，不自动创临时设备。complete按安装UUID/operationId/请求hash幂等登记，接替用generation约束且旧凭证失效。只存秘密hash，重放旧操作不能恢复旧权限。

本人token仅可列出/下载/重试修复本人sourceId快照，猜同空间其他backupId也拒绝。上传提交前复核当前token，文件校验/落盘与PG索引成功后才返回成功。列表按uploaded_at而非手机snapshot_at排序。API细节见[当前OpenSpec接口](../openspec/changes/sync-home-rules-and-backup-ledger/api.md)。

管理页为`/admin/cloud-backup`，管理令牌是`MYMONEY_ADMIN_TOKEN`。出码服务地址填手机可达的服务根地址（本机使用`http://<家庭服务地址>`），不能填管理页路径。未配置默认服务地址/网络key时在当前会话填写；未把本机connect-code.env密钥自动复制进服务环境。业务码与网络入网授权分别生效。

## 测试和历史数据

```bash
# 使用专用测试库，设置MYMONEY_TEST_DATABASE_URL后运行
.venv/bin/pytest -q
```

测试会创建/清理`mymoney_home_test`、`mymoney_home_backup_test`、`mymoney_device_enrollment_test`、`mymoney_backup_admin_test`等明确测试schema，不得与业务schema重名或并发跑同组用例。最终65 passed引用[153原run](../runs/20261106-055644-personal-cloud-backup-implementation/outputs/verification.md)，本轮只更新文档未重跑。

2026-11-06用户明确授权清表重来，先留专用schema快照再清业务表并部署；未清手机/其他schema/备份卷/网络节点。清前21来源、当轮Mi10重连后1来源/2份58笔快照均为当时采样，不当实时盘点。常规升级无此清表授权，PG和文件卷需联合备份，回退旧容器不会自动还原被清数据。

153/154未获用户验收；小米14只确认安装154成功，未代打开或验证。自动备份、扫码等未实现。原服务说明留档如下。

<details>
<summary>此前文档原文（历史状态，不作为当前操作指引）</summary>

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

</details>
