package com.example.autobook.xposed

import android.accessibilityservice.AccessibilityService
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * AutoBook 内置 LSP 增强模块。
 *
 * 只做两件事（多一件都不做，别在文档里加戏）：
 * 1. 拦住系统解绑/移除本 App 的无障碍服务
 *    （`AccessibilityManagerService.unbindService` / `removeServiceLocked`）；
 * 2. 拦住针对本 App 包名的 `ActivityManagerService.killApplication`。
 *
 * **做不到**（曾在这里写成"功能"，已删除）：
 * - 改电池优化/省电策略：没有 Hook `PowerManagerService`，也不想替用户改系统策略；
 * - 防低内存回收（LMK）：那条路径不经过 `killApplication`；
 * - 让服务"自动复活"：本类不重启任何东西，真正的复活要靠系统重建或用户手动开。
 *
 * 使用方式：
 * - 在 LSPosed 中激活 AutoBook App；
 * - 作用域**只勾** "系统框架（android）"——本模块 `handleLoadPackage` 首行就判
 *   `packageName == "android"`，勾别的没有任何代码会执行；
 * - 重启后生效；不激活时 App 正常工作（标准无障碍模式）。
 *
 * 注意：
 * - 因为 App 声明为模块，所以不会出现在作用域的应用列表中；
 * - 激活标记靠 `SystemProperties.set` 写 `persist.autobook.xposed.active`，
 *   普通应用权限下大概率失败（见 [setModuleActiveFlag]），所以 App 端的
 *   `MODULE_CONFIRMED` 档位多数时候不会出现，这是权限现实不是 bug。
 */
class AutoBookXposedModule : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "AutoBook-Xposed"
        private const val TARGET_PACKAGE = "com.example.autobook"
        private const val ACCESSIBILITY_SERVICE_CLASS =
            "com.example.autobook.capture.BillAccessibilityService"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只 Hook 系统框架
        if (lpparam.packageName == "android") {
            hookSystemFramework(lpparam)
        }
    }

    /**
     * Hook 系统框架：防止撤销无障碍权限
     */
    private fun hookSystemFramework(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            log("开始 Hook 系统框架")

            // 设置系统属性标记模块已激活
            setModuleActiveFlag()

            hookAccessibilityManagerService(lpparam)
            hookActivityManagerService(lpparam)

            log("系统框架 Hook 完成")
        } catch (e: Throwable) {
            log("Hook 系统框架失败: ${e.message}")
        }
    }

    /**
     * 设置系统属性标记模块已激活
     */
    private fun setModuleActiveFlag() {
        try {
            // 设置自定义属性，让 App 能检测到
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.os.SystemProperties", null),
                "set",
                "persist.autobook.xposed.active",
                "true"
            )
            log("已设置系统属性标记")
        } catch (e: Throwable) {
            log("设置系统属性失败: ${e.message}")
        }
    }

    /**
     * Hook AccessibilityManagerService - 防止系统禁用无障碍服务
     */
    private fun hookAccessibilityManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val amsClass = XposedHelpers.findClass(
                "com.android.server.accessibility.AccessibilityManagerService",
                lpparam.classLoader
            )

            // Hook unbindService 方法，阻止解绑我们的服务
            XposedHelpers.findAndHookMethod(
                amsClass,
                "unbindService",
                AccessibilityService::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val service = param.args[0] as? AccessibilityService
                        if (service?.javaClass?.name == ACCESSIBILITY_SERVICE_CLASS) {
                            log("阻止系统解绑无障碍服务")
                            param.result = null
                        }
                    }
                }
            )

            // Hook removeServiceLocked，防止移除服务
            try {
                XposedHelpers.findAndHookMethod(
                    amsClass,
                    "removeServiceLocked",
                    "com.android.server.accessibility.AccessibilityServiceConnection",
                    Int::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val connection = param.args[0]
                                val componentName = XposedHelpers.callMethod(
                                    connection,
                                    "getComponentName"
                                ).toString()

                                if (componentName.contains(TARGET_PACKAGE)) {
                                    log("阻止移除无障碍服务: $componentName")
                                    param.result = null
                                }
                            } catch (e: Throwable) {
                                // 静默失败
                            }
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {
                // 某些 ROM 没有此方法
            }

            log("AccessibilityManagerService Hook 完成")
        } catch (e: Throwable) {
            log("Hook AccessibilityManagerService 失败: ${e.message}")
        }
    }

    /**
     * Hook ActivityManagerService - 防止系统杀进程
     */
    private fun hookActivityManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader
            )

            // Hook killApplication，阻止杀我们的进程
            try {
                XposedHelpers.findAndHookMethod(
                    amsClass,
                    "killApplication",
                    String::class.java,
                    Int::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val packageName = param.args[0] as? String
                            if (packageName == TARGET_PACKAGE) {
                                val reason = param.args[2] as? String
                                log("阻止系统杀进程，原因: $reason")
                                param.result = null
                            }
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {
                // 某些 ROM 方法签名不同，尝试其他重载
                try {
                    XposedHelpers.findAndHookMethod(
                        amsClass,
                        "killApplication",
                        String::class.java,
                        Int::class.java,
                        Int::class.java,
                        String::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val packageName = param.args[0] as? String
                                if (packageName == TARGET_PACKAGE) {
                                    val reason = param.args[3] as? String
                                    log("阻止系统杀进程，原因: $reason")
                                    param.result = null
                                }
                            }
                        }
                    )
                } catch (e2: Throwable) {
                    // 放弃
                }
            }

            log("ActivityManagerService Hook 完成")
        } catch (e: Throwable) {
            log("Hook ActivityManagerService 失败: ${e.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }
}
