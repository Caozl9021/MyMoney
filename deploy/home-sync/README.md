# My Money 家庭同步服务

## 目录与数据库

- 连接自行管理的 PostgreSQL 数据库，连接串通过 `MYMONEY_DATABASE_URL` 提供；已在 PostgreSQL 17 验证。
- 服务使用独立 schema `mymoney_home`，不读写现有业务表。
- 建议两个角色：
  - `mymoney_migrator`：只用于 `alembic upgrade head`，拥有 schema 建表权限。
  - `mymoney_app`：运行角色，只授予 `mymoney_home` 下表的 SELECT/INSERT/UPDATE/DELETE。
- 密码只放在本机 `deploy/home-sync/.env`，不要提交。

首次初始化：

```bash
cd deploy/home-sync
cp .env.example .env
# 编辑 .env 中的数据库密码、admin token、备份目录
docker compose build
# 首次建表；容器内已包含 alembic.ini 与 migrations
docker compose run --rm home-sync alembic -c alembic.ini upgrade head
docker compose up -d
```

默认端口为 `8080`。当前compose使用host网络，服务绑定所有接口；请用宿主防火墙限制访问来源，或通过家庭HTTPS反向代理接入。反代上游为 `http://127.0.0.1:8080`。
备份目录建议使用宿主目录 bind mount，便于宿主备份工具复制；不要只放在 Docker named volume。

## 修改服务端口

`.env` 里有两个端口相关变量，必须一起改：

```bash
MYMONEY_PORT=18080
MYMONEY_BASE_URL=http://127.0.0.1:18080
```

- `MYMONEY_PORT`：容器内 uvicorn 实际监听端口。
- `MYMONEY_BASE_URL`：只是本机 CLI 调用 `pair-code` 的地址，不会改变服务监听端口。

修改后重建并启动：

```bash
docker compose up -d --build
curl http://127.0.0.1:18080/health
```

反向代理的 upstream 也要同步改成 `http://127.0.0.1:18080`。

## 生成配对码

默认是服务端内存中的一次性短期码，有效期 5 分钟，最多尝试 10 次；服务重启后失效。

- 可选固定码 + 永久有效：在 `.env` 设置自己的随机配对码：

```bash
MYMONEY_PAIRING_CODE=REPLACE_WITH_RANDOM_PAIRING_CODE
MYMONEY_PAIRING_TTL_SECONDS=0
```

`0` 表示码不过期、不因输错或成功配对而作废，可在多台设备上重复使用。仅建议在家庭内网或已由反向代理保护的地址使用；修改 `MYMONEY_PAIRING_CODE` 后执行 `docker compose up -d --force-recreate` 轮换。

- 固定码 + 短期有效：只设置 `MYMONEY_PAIRING_CODE`，保留默认 `MYMONEY_PAIRING_TTL_SECONDS=300`。服务启动后 5 分钟内可在 App 输入；过期后通过下面的 admin 接口重新签发（仍返回同一个固定码）。
- 每次随机签发：

```bash
# 在 deploy/home-sync 目录，确保 .env 已加载
curl -s -X POST http://127.0.0.1:8080/v1/admin/pair-code \
  -H "X-Admin-Token: $MYMONEY_ADMIN_TOKEN"
# 返回 {"code":"XXXXXXXX","expiresInSeconds":300}
```

也可以使用容器内 CLI：

```bash
docker compose exec home-sync python -m app.cli pair-code
```

拿到 `code` 后，在 App 的「设置 → 数据与应用 → 家庭同步与备份」中输入服务地址和该配对码。

## M4 手动账本快照

App 配对后可主动“立即备份”、查看家庭备份、下载并预览后选择合并或覆盖。不会自动上传、自动恢复或删除历史备份。App 保留规则/备注配置、权限及配对状态，恢复前将本机账本保存至私有目录；私有快照随卸载消失，应另行导出长期保管。主账本仍为 Room9、JSON8。

容器内备份路径固定为 `/data/backups`，`.env` 的 `MYMONEY_BACKUP_DIR` 仅指定宿主 bind mount 源。升级须 `docker compose up -d --build --force-recreate`，不需要新增表或迁移。宿主目录需允许容器写入。反向代理须允许至多 200MiB 请求体，并设置足够上传超时；不要记录 Authorization 或请求正文。

上传：`PUT /v1/backups/{uuid}`，Bearer 鉴权，原始 `application/json`、Content-Length、`X-Content-SHA256`、`X-App-Version`、`X-Space-Epoch`；仅接受未压缩 JSON8，最多 200MiB。服务核对实际长度、SHA、格式、实际账单数，从内容读取快照时间。服务只做结构校验，客户端下载后仍须完整解码和关联校验。只有 `.part` 写入并 fsync、UUID 完整文件原子落盘、PG 索引提交后才返回成功。相同 UUID/摘要重传幂等，异内容 409；服务不可达或磁盘失败不会删除旧快照。

`GET /v1/backups?offset=0&limit=50` 返回本空间元数据（`id,deviceId,deviceName,appVersion,backupSchema,sha256,sizeBytes,billCount,snapshotAt,uploadedAt`）、hasMore、nextOffset。`GET /v1/backups/{uuid}` 鉴权下载完整 JSON，服务先核对文件长度及 SHA；设备撤销后所有备份接口拒绝。未提供 HTTP 删除接口，首期默认保留全部历史。

只读文件巡检：

```bash
docker compose exec -T home-sync python -m app.cli backup-audit
```

命令列出未入索引完整文件、`.part`、缺失及损坏文件，不删任何内容。rename 后 DB 失败会留下孤立完整文件，同 UUID/摘要重试可补齐索引；服务重启遗留 `.part` 不列入备份列表。磁盘不足请扩容或先审查候选，不应删除最后一份有效备份。

## PG 与文件卷联合备份/恢复

1. 暂停家庭 API 写入（停止 home-sync），保持 PG 运行。记录镜像版本、schema、当前 epoch 及备份时间。复制 `MYMONEY_BACKUP_DIR` 完整文件树，并用 PG17 的 `pg_dump --format=custom --schema=mymoney_home` 保存 schema；密码通过私有环境配置或 `.pgpass` 提供，不写命令历史。PG 与文件来自同一停写窗口。
2. 在独立数据库/空目录先做恢复演练：`pg_restore` 恢复 schema、复制完整文件树，将新实例指向该目录；运行 `backup-audit`，要求 missing/corrupt 为空，并逐份下载核对 SHA、字节数、条数及账本关联。仅文件存在不算通过。
3. 真正恢复旧 PG 快照时，在恢复事务里为每个 `sync_spaces.epoch` 生成新的 UUID（例如 `UPDATE mymoney_home.sync_spaces SET epoch=gen_random_uuid()`），保存新 epoch 的只读记录，再启动服务。手机须重新对账；不能以旧 cursor 静默续传。演练不要对生产 schema 做轮换或清空。
4. 旧空间设备令牌和备份索引由 PG 恢复；若 PG 与文件卷不匹配，下载会报文件不可用，不能声明恢复成功。保留原始备份与恢复前数据直到用户验收。另一磁盘/主机上的副本才覆盖宿主磁盘故障风险。

构建通过仓库内 `server/constraints.txt` 固定 M4 已验证的 Python3.12/Linux 运行依赖版本；更新依赖时应重新执行隔离协议/备份测试再更新约束。不将本机路径或认证配置加入约束文件。
