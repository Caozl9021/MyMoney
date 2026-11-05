# 打包文件存放规则

`dist/` 是本机打包工作区，产物不提交 Git。App 候选、规则候选和发布快照分别保存。`runs/` 保存测试及复现记录，`deliverables/` 保存稳定交付与验收记录；用户明确验收前保持待验收。

```text
dist/
  README.md                       本机导航
  latest-app.json                 最近成功收集的 App 位置
  app/<versionName>-<versionCode>/<run-id>/
    MyMoney-<versionName>-<versionCode>-release.apk
    MyMoney-<versionName>-<versionCode>-debug.apk
    MyMoney-<versionName>-<versionCode>-androidTest.apk  可选
    app-update.json
    manifest.json
    SHA256SUMS
    RELEASE_NOTES.md               可选
  rules/<packageVersion>/<run-id>/
    MyMoney-rules-<packageVersion>.mymoney-rules.zip
    MyMoney-rules-<packageVersion>.mymoney-rules.zip.sha256
  releases/v<versionName>/         App 发布快照
  releases/rules-v<packageVersion>/ 规则发布快照
  notes/v<versionName>/            发布说明草稿
  archive/                        历史重复清单、迁移映射
```

## App 打包

运行 `./build-all.sh`，先完成测试及 Debug/签名 Release 构建，再调用 `tools/release/collect-app-build.py` 收集。默认创建含本地时间和随机后缀的唯一 run-id；同版本再次打包不会覆盖旧目录。正式 APK、Debug APK 均从包内核对版本和包名；更新清单从最终签名 APK 生成，记录大小、摘要及证书。

已有 APK 需要重新收集时，无需重新构建：

```bash
python3 tools/release/collect-app-build.py \
  --release app/build/outputs/apk/release/app-release.apk \
  --debug app/build/outputs/apk/debug/app-debug.apk
```

可加 `--android-test <APK>` 和 `--notes <说明文件>`。可用 `--dist <目录>` 指定独立收集根目录。`build-all.sh` 会读取 `dist/notes/v<versionName>/RELEASE_NOTES-v<versionName>.md`（若存在）。

成功完成核对、清单和摘要生成后才原子更新 `latest-app.json`；它只是导航，不表示用户验收或已发布。失败目录保留作诊断，不进入最新指针。收集 manifest 的提交和 dirty 状态描述收集时工作区，不能单独证明旧 APK 的源码来源；构建来源和测试证据仍记录在原 run 中。

## 独立规则包

日常打包使用默认目录：

```bash
python3 billkit/rules/package.py
```

默认写入 `dist/rules/<packageVersion>/<时间-随机后缀>/`。`--output-dir <目录>` 用于隔离验证或显式准备发布；若同名 ZIP 或摘要已存在，立即失败，不覆盖。纯规则包不构建 APK、不递增 App 版本。

## 发布与历史

- 日常构建不自动写 `releases/`，不上传 GitHub。明确发布时从已核对候选复制附件，记录来源 run、构建号及摘要。
- 发布附件中的 `app-update.json` 必须与同目录 APK 配套，不能复用旧清单；上传目录仅包含本次附件。
- 原有发布快照保持既有路径与内部文件名，不用新候选覆盖旧快照。不同构建号需新建候选目录；同标签快照冲突应停止并核对，不静默替换。
- 旧文件整理使用 `legacy-root`、`legacy-rules`、`legacy-tailscale-129` 标识来源。保留旧文件名及清单内容，按 `archive/migrations/` 的映射定位旧路径；原始 run 和历史证据不批量改写。
- `archive/legacy-root/app-update.json` 是原根目录重复清单，仅供追溯，不用于新发布。
- `.stfolder/` 为 Syncthing 标记，保持原位。根目录不再散放 APK、ZIP、版本清单或发布草稿。
- 不自动清理旧包或按数量删除。清理前先列候选、确认备份和复用依赖，再按用户授权执行。

2026-11-05同步：当前最近成功候选为1.9.0/143，路径以latest-app.json为准；143为pending_acceptance。当前用户验收见ACCEPTANCE.md，本次文档更新不修改候选APK、清单或历史发布快照。

2026-11-05 I阶段：最近成功候选更新为1.9.0/144（pending_acceptance），目录dist/app/1.9.0-144/20261105-191359-zfq8ntqp；稳定快照deliverables/1.9.0/build-144。143及历史发布快照保持，144未发布。

2026-11-05 J阶段：最近候选1.9.0/145（pending_acceptance），dist/app/1.9.0-145/20261105-194517-raocg8et；稳定快照deliverables/1.9.0/build-145，旧候选/发布快照保持，145未发布。
