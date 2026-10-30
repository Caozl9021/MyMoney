package com.example.mymoney.xposed

import com.example.mymoney.lsp.HookProtocol
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Captures transaction receipts only in the two selected target applications. */
class MyMoneyXposedModule : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var modulePath: String? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!HookProtocol.shouldCapture(lpparam.packageName, lpparam.processName)) return
        capture(lpparam)
    }

    /**
     * 第三方 App 进程内的采集分支（SPEC §8 阶段 3/4）。
     *
     * 只在主进程里挂 hook：微信的 WCDB 写入发生在主进程（`:push` 等子进程也会写库，
     * 但交易消息由主进程落盘），重复注册会在同一条消息上产生两份投递。
     * 这里不做任何判定，也不碰宿主的数据库——只把 App 自己的结构化凭证投回宿主。
     */
    private fun capture(lpparam: XC_LoadPackage.LoadPackageParam) {
        var error: String? = null
        val count = try {
            when (lpparam.packageName) {
                HookProtocol.APP_WECHAT -> WechatHook.install(lpparam.classLoader, ::log)
                HookProtocol.APP_ALIPAY -> AlipayHook.install(lpparam.classLoader, ::log)
                else -> return
            }
        } catch (e: Throwable) {
            log("采集分支异常：${e.javaClass.simpleName}")
            error = HookProtocol.ERROR_INSTALL_FAILED
            0
        }
        // Even a zero-method registration can report a precise unavailable state.
        try {
            TargetHookProbe.install(lpparam.packageName, count, modulePath,
                error ?: if (count == 0) HookProtocol.ERROR_NO_METHOD else null, ::log)
        } catch (e: Throwable) {
            log("目标状态分支异常：${e.javaClass.simpleName}")
        }
    }

    private fun log(message: String) = XposedBridge.log("[MyMoney-Xposed] $message")
}
