## 这个 PR 做了什么

<!-- 一两句话说明改动和动机 -->

## 类型

- [ ] 修复 bug
- [ ] 新增识别规则
- [ ] 界面 / 交互
- [ ] 重构（不改变行为）
- [ ] 文档

## 验证

- [ ] `./gradlew :billkit:testDebugUnitTest :app:testDebugUnitTest` 通过
- [ ] `./gradlew :app:assembleDebug` 通过
- [ ] 涉及 UI 的改动附了截图
- [ ] 涉及识别的改动在 `billkit/src/test/resources/spec/samples.json` 加了正例与反例

## 检查清单

- [ ] 金额仍然以分（`Long`）存储，没有引入浮点误差
- [ ] 没有新增网络请求，没有声明 `INTERNET` 权限
- [ ] 没有读写第三方 App 的私有数据，没有自动点击支付按钮
- [ ] 新增第三方依赖已在 `NOTICE` 里补充，且许可证与 GPL-3.0 兼容
- [ ] 提交内容里没有真实账单、商户名、订单号或个人信息
