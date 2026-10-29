# 麦米原创规则源

本目录保存可读脚本和确定性生成器，生成 `billkit/src/main/assets/spec/` 五份资产及 `manifest.json`。代码由本次改造按本 App 输入契约与独立业务要求编写，不翻写原 JS/分类表；开发者已看过既有宿主和测试，不宣称严格隔离开发。公开名称、JSON字段和既有采集入口作为兼容契约保留。

```bash
python3 billkit/rules/generate.py
python3 billkit/rules/generate.py --check
python3 billkit/rules/make_fixtures.py
```

`generate.py` 只读取本目录原创源，不读待替换实现。重复生成应字节一致。manifest 摘要覆盖全部五文件，规则稳定 ID 使用 `maimi_` 前缀；creator 为 `maimi`，autoRecord 全为 0，所有结果仍交给现有待确认流程。

## 当前支持范围

16 条规则：微信 DATA（付款/收款/退款）3、支付宝 DATA 3、微信 NOTICE（付款/收款）2、支付宝 NOTICE 2、招行短信（借记卡消费/工资）2，以及微信转账支出/收入、红包支出/收入4条。用户于 2026-10-29 明确要求“不要禁用，参照原来的写”，补充用户转账/红包反馈后，本版 **16 条全部启用**。启用不等于已完成真实交易验证；下表和 `coverage.json` 如实区分证据。

| 场景 | 规则状态 | 当前来源证据 |
| --- | --- | --- |
| 微信 DATA 成功付款 | 启用 | 本 App 独立真机诊断 app_data39 的脱敏结构，另有合成边界 |
| 微信 NOTICE 成功付款 | 启用 | 本 App 独立真机诊断 app_data40 的脱敏正文；当前信封门禁由宿主另测 |
| 微信转账发出支出 | 启用 | 2026-10-29本 App现场两笔0.01元、每笔4回调的脱敏结构；明确收款邀请反例 |
| 其余13场景（含确认收入与红包） | 启用 | 按采集契约独立设计的合成回放，尚无相应真实成功交易验证 |

群收款、提现、充值、还款及其他银行未覆盖；相关输入不猜成收支。微信转账只支持已知发出/确认阶段；红包只支持同一凭证中明确金额与完成状态的分支，不承诺所有微信红包消息形态。微信付款要求可信source、标题与正文金额一致、明确成功状态；不能据 cached 字段猜账。商户必须有明确标签分隔符，没有则留空，不把“微信支付”通道名当商户。

## 独立设备证据及脱敏

本地证据来自 `runs/20261027-193500-spec-device-verification/outputs/verification.md` 的 0.20.5 追加记录，记载用户真实两次 0.01 元扫码付款。原始自家诊断快照为 `runs/20261027-223040-simplify-automation/intermediate/device-before/databases/autobook.db`，只读核验 app_data39/40/41，未读取规则表或第三方数据库。

- `local-device-20261027-app-data-39`：mMap、已支付标题、零钱付款、明确完成状态；姓名替换为测试商户甲。原无分隔符收款方不当作商户，采集时间仅 observedAt。
- `local-device-20261027-app-data-40`：title=微信支付、完整已支付金额；通知时间不是业务交易时间。回放中的 envelopeVerified/observedAt 是新宿主门禁适配输入，不声称历史 raw 自带这些字段。
- 原 app_data41 的 `[2条]` 汇总形态作为拒绝要求，未把汇总当独立交易。

不复制上游 `samples.json`；新样本 128 条（48 正、80 负），包括5条 captured-redacted（原2条、新转账发出1条/收款邀请2条），其余123条 synthetic。每场景3正5负。正例覆盖19.99、0.01与合法千分位；负例覆盖错来源、失败、坏小数、零额、冲突、汇总/未验证信封等。`make_fixtures.py` 所有文本均为独立测试文本，不含真实卡号/单号/姓名。

## 时间、分类和账户

所有通知和短信到达时间、微信采集 t、支付宝推送 mct 都只填 observedAt；未提供明确业务时间则 time=0、hasExplicitTime=false，不参与交易时间强行合并。真实业务时间或订单号存在时才传递，类型区分 Expend/Income/Refund。

分类JS仅提供少量明确词语兼容兜底；不另外定义餐段，不把未知商户默认到固定类别。用户现有分类、学习、JSON覆盖、账户和餐段优先级由宿主保持。categories/assets 是原创建议表，不会写入或覆盖用户配置。

## 0.33.1 转账与红包补齐

规则包版本为 `maimi-rules-2`，原12条稳定ID保留，新规则ID为 `maimi_wechat_transfer_expense_v1`、`maimi_wechat_transfer_income_v1`、`maimi_wechat_redpackage_expense_v1`、`maimi_wechat_redpackage_income_v1`。

独立本地现场来源：`runs/20261029-210258-wechat-no-popup/intermediate/initial-app/databases/autobook.db` 的 app_data27—34。只读核对两笔 transferid 各有 isSend1×2 和 isSend0×2，均为 subtype1。新 fixture 保留结构和0.01金额，姓名/完整单号替换，观测/业务时间固定至测试时刻；不会输出真实账户或订单。

- `type=transfer`、appmsg.type=2000、subtype1、isSend1 为本人发出支出；isSend0 只是收款邀请，绝不收入。
- subtype3 还必须有明确主体：本人/你已确认收款且isSend1为收入；对方/收款方已确认收款且isSend0为原支出确认。无主体“已收款”、主体矛盾或同消息镜像方向不一致拒绝，避免采集器缺isSend默认0造成方向串账。
- 转账身份统一 `wxtransfer_` + 完整transferid，发出/确认阶段相同；业务时间统一begintransfertime，不以确认到达时间改期。单号冲突、缺时间、坏金额、失败/退回均拒绝。
- 红包mMap成功凭证要求可信source、当前标题明确金额及完成语；结构化redPackage还要求appmsg.type=2001、同一wcpayinfo金额feedesc与sendid、发出或本人领取的明确完成状态。红包身份使用 `hongbao_` 前缀。普通祝福、对方领取、待领取、无金额或只有cachedPayMoney均拒绝。此部分只有独立合成语义验证，缺真实红包成功原始凭证。
- 新增14项 `WechatTransferAndRedpackageTest`，覆盖两笔各四回调、阶段/主体矩阵、身份稳定、失败与坏金额/时间、红包成功/未领取/缓存拒绝；原有19项手写语义边界保留。实际宿主合并和投递另由Android专项验证，纯JS重复命中不等于已证明只弹一次。

标准“已支付”mMap红包兼容：标题与当前付款行金额一致、明确支付成功，并且完整交易类型/用途行或精确可信display_name/source标识微信红包时，优先分流到红包支出规则。普通同名商户“红包餐馆”不作为类型证据；金额冲突拒绝。此兼容分支目前仅有手写合成语义测试，没有实际红包成功原始样本。
