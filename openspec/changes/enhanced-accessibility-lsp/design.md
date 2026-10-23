# 决策

## 1. 分级去抖

`BillAccessibilityService` 保留一个统一去抖窗口，但按事件类型取不同常量：
`WINDOW_DEBOUNCE_MS = 120L`（`TYPE_WINDOW_STATE_CHANGED`）与
`DEBOUNCE_MS = 600L`（`TYPE_WINDOW_CONTENT_CHANGED` 及其它）。理由：付款成功页的呈现是
一次窗口切换，事件量小、语义明确；而滚动/列表刷新会产生持续的 `CONTENT_CHANGED` 洪峰，
沿用 600ms 才能避免反复 dump 同一屏。

不改采集链路本身（`TextCollector` → `BillKit.offer`），因此识别规则、去重、截断诊断全部不受影响。
不做"按包名/按页面类型"的更细粒度去抖——当前没有证据表明需要。

## 2. LSP 内置化

模块代码放在 `app` 模块内（`app/src/main/java/com/example/autobook/xposed/AutoBookXposedModule.kt`
+ `app/src/main/assets/xposed_init`），不再维护独立 Gradle 模块。理由：模块只 Hook 系统框架、
不对外提供 API，独立 APK 只带来"要装两个包、版本要一起升"的成本，没有收益。

`handleLoadPackage` 只在 `lpparam.packageName == "android"` 时注册 Hook；命中
`com.example.autobook` 时把结果置 null。LSPosed 作用域只应勾 `android`。

## 3. 刻意不做的部分

- **不 Hook `PowerManagerService`**：强制豁免电池优化等于替用户改系统电源策略，收益不明且风险高；
  正确做法仍是引导用户手动关闭电池优化。早期文档把这条写成已实现，属于过度陈述，已订正。
- ~~**不做自动恢复守护**~~〔2026-10-22 更新：**已在 0.6.3 接线**，见
  `openspec/changes/guardian-keepalive-wiring/`〕：当时的判断是"接入它意味着常驻前台服务 + 通知，
  与'无障碍模式不需要常驻通知'的产品取向冲突，故暂不接线"。后来用户明确要求"确定保活无障碍模块正常"，
  权衡后接受"无障碍模式下有一条最低优先级常驻通知"，换取掉线能被发现；原文保留以便对照。
- **不改 App 侧激活检测的三路逻辑**：其中 SharedPreferences 一路没有写入方（模块没 Hook
  `Context.getSharedPreferences`），系统属性一路在普通应用权限下几乎必然失败。本轮只记录问题，
  不在本变更里改代码。

## 4. 验证边界

单测与构建通过只证明"能编译、规则未回归"，不证明 Hook 生效。Hook 的验收必须真机：
在系统设置里手动关闭 AutoBook 的无障碍开关，观察日志是否出现 `阻止系统解绑无障碍服务`。
