# 架构说明

## 模块

```
:app  ──依赖──▶  :billkit
```

| 模块 | 职责 |
| --- | --- |
| `:billkit` | 识别核心。`BillKit` 接收 `UiSnapshot`，做节流去重、页面分类、金额解析、字段抽取、规则执行与诊断。不依赖 Android UI，可跑纯 JVM 测试。 |
| `:app` | 宿主。Compose 界面、四条采集通道、确认卡投递、Room 账本、备份与导入导出、内置 LSP 模块。 |

`billkit` 的包名保持 `com.example.billkit`，是内部库模块名，不随应用包名变化。

## 一次支付的数据流

```
微信 / 支付宝 完成支付
        │
        ├─ 通知监听 ─────┐
        ├─ 无障碍页面 ───┤
        ├─ Root 通知 ────┼──▶ 统一来源身份 ──▶ 本地规则（Rhino）
        └─ LSPosed 凭证 ─┘                        │
                                                  ▼
                                          RecognitionStore 落盘
                                                  │
                                                  ▼
                                    确认卡（悬浮窗优先，Activity 兜底）
                                                  │
                              用户点「记一笔」──────┤
                                                  ▼
                                    BillWriter 事务写入
                                    （账单 + 历史状态 + billId）
```

关键点：**先落盘再投递**。识别结果先写进 `RecognitionStore` 成为一条待处理记录，再去弹确认卡。悬浮窗被系统拦下、Activity 启动失败、进程被杀，都不会丢数据——用户下次进「待处理」还能看到并确认。

## 几条不可动摇的约束

### 金额以分存储

`Long`，不是 `Double`。两位小数输入用 `Locale.US` 解析并四舍五入，避免 `19.99 → 1998` 这类浮点误差。

### 只有用户点「记一笔」才入账

识别一律先成草稿。失败、取消、进行中、被忽略的状态都不进账本。下滑、点空白、返回、熄屏会把当前编辑保存成待处理草稿，不会静默丢弃。

### 重复提交必须幂等

`BillWriter` 在同一个事务里写账单与识别历史状态，并回填 `billId`。同一条识别记录重复提交不会产生第二笔账。

### 规则在设备上执行，不联网

`billkit/rules/src/*.js` 是普通 JS，由 Rhino 解释执行，没有原生 `.so`。规则只做文本匹配与字段抽取，不做任意正则求值，也不能绕过成功状态和金额校验。

### 数据库迁移只增不删

Room 当前 v9，保留 1→9 的非破坏迁移链。备份格式 JSON8，兼容 JSON1–7。新增字段时缺省值要能表达「未知」，不能把缺失当成默认事实。

## 目录

```
app/src/main/java/io/github/mymoney/
├── App.kt              应用装配：规则推荐、备注、投递
├── capture/            四条采集通道、调度、投递与守护
├── data/               Room 实体、RecognitionStore、BillWriter、备份、学习
├── export/             iCost CSV 与分享
├── importer/           钱迹 XLSX 导入
├── lsp/                LSPosed 管理器入口、作用域重启
├── overlay/            悬浮确认卡
├── root/               Root 通知服务
├── systems/            磁贴、桌面组件、快捷方式
├── ui/                 Compose 界面、确认卡 UI、设计 token
└── xposed/             内置 LSP 模块入口

billkit/src/main/java/com/example/billkit/
├── BillKit.kt          识别入口
├── page/               PageClassifier
├── amount/             AmountParser
├── field/              FieldExtractor
├── spec/               Rhino 规则引擎与资产
└── notification/       通知信封解析与模板
```

## 数据模型

| 存储 | 内容 |
| --- | --- |
| Room v9 | 账单、识别历史、资产账户与流水 |
| SharedPreferences `mymoney` | 采集模式、开关、外观、学习与餐段配置 |
| JSON8 备份 | 上述全部，外加导入身份、账期与待确认转入账户 |
| `files/samples/` | 用户主动保存的诊断样本，不参与备份 |

`allowBackup` 关闭。数据只通过用户主动导出的 JSON 文件离开设备。
