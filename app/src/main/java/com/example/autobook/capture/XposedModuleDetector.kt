package com.example.autobook.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LSPosed 模块状态检测。
 *
 * 只报告**能验证的**东西，分三档，绝不把"无法确定"说成"已激活"：
 *
 * - [HookState.MODULE_CONFIRMED]：模块自己成功写下 `persist.autobook.xposed.active=true`。
 *   这是唯一能证明"我们的 Hook 确实跑过"的证据。模块用 `SystemProperties.set` 写这个属性，
 *   普通应用权限下大概率失败，所以这一档很少出现，出现即真。
 * - [HookState.FRAMEWORK_PRESENT]：当前进程的调用栈里出现了 Xposed/LSPosed 的类。
 *   说明框架确实注入进来了，但**不能**证明 AutoBook 模块被启用（同一进程里别的模块
 *   也会造成同样的栈）。界面文案必须写成"检测到框架，未确认模块"。
 * - [HookState.NOT_DETECTED]：两路都没命中。
 *
 * 旧的"SharedPreferences 标记"那一路已删除：模块只 Hook 系统框架、
 * 不碰本 App 的 SharedPreferences，那一行永远读不到写入方。
 */
object XposedModuleDetector {

    /** 模块启用状态，语义见类注释 */
    enum class HookState {
        NOT_DETECTED,
        FRAMEWORK_PRESENT,
        MODULE_CONFIRMED,
    }

    private const val SYSTEM_PROPERTIES = "android.os.SystemProperties"
    private const val KEY_MODULE_ACTIVE = "persist.autobook.xposed.active"

    private val _state = MutableStateFlow(HookState.NOT_DETECTED)
    val state: StateFlow<HookState> = _state.asStateFlow()

    /** 是否检测到框架注入（含未确认模块的情况）。UI 用 [state] 分档显示。 */
    fun detect(context: Context): HookState {
        val next = when {
            isModuleConfirmed() -> HookState.MODULE_CONFIRMED
            isFrameworkInProcess() -> HookState.FRAMEWORK_PRESENT
            else -> HookState.NOT_DETECTED
        }
        _state.value = next
        return next
    }

    /** 只认模块亲手写的那个属性；读不到就 false。 */
    private fun isModuleConfirmed(): Boolean = runCatching {
        val clazz = Class.forName(SYSTEM_PROPERTIES)
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, KEY_MODULE_ACTIVE) as? String) == "true"
    }.getOrDefault(false)

    /** 栈里出现框架类名 → 框架注入中。 */
    private fun isFrameworkInProcess(): Boolean = runCatching {
        val trace = Throwable().stackTrace
        trace.any { frame ->
            val name = frame.className
            name.contains("de.robv.android.xposed") ||
                name.contains("EdXposed") ||
                name.contains("LSPosed")
        }
    }.getOrDefault(false)
}
