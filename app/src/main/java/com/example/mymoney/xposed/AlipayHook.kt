package com.example.mymoney.xposed

import android.net.Uri
import com.example.mymoney.lsp.HookProtocol
import com.example.billkit.spec.SpecHookPayload
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 支付宝采集（SPEC §4）：在 `SyncMessage#getData()` 返回后读它自己解析好的长连接消息。
 *
 * 为什么是 getData 而不是网络层：长连接推送是加密的 protobuf，解出来才有意义；
 * 而 `SyncMessage.getData()` 返回的已经是 App 自己解好、准备交给业务层的 JSON，
 * 交易详情（`pl` 里的 title/content/extraInfo）就在里面，且不受版本加密协议变更影响。
 *
 * 规则只读 `JSON.parse(raw)[0].pl`（SPEC §4.2），所以必须**逐元素投递**：
 * 一次推送里可能有好多条消息，整数组丢过去会让除第 0 条以外的交易全部丢失。
 */
internal object AlipayHook {

    private const val CLASS = "com.alipay.mobile.rome.longlinkservice.syncmodel.SyncMessage"
    private const val MAX_ITEMS = 32

    @Volatile private var installedCount = 0

    @Synchronized
    fun install(loader: ClassLoader, log: (String) -> Unit): Int {
        if (installedCount > 0) return installedCount
        val count = try {
            val klass = XposedHelpers.findClass(CLASS, loader)
            val methods = klass.declaredMethods.filter { it.name == "getData" && it.parameterTypes.isEmpty() }
            if (methods.isEmpty()) {
                log("支付宝采集：未找到 getData()，版本可能不兼容")
                return 0
            }
            var hooked = 0
            for (method in methods) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = runCatching {
                            val raw = param.result as? String ?: return@runCatching
                            for (single in SpecHookPayload.alipaySplit(raw, MAX_ITEMS)) {
                                emit(HookProtocol.APP_ALIPAY, single)
                            }
                        }.onFailure { log("支付宝采集回调异常：${it.javaClass.simpleName}") }.let { }
                    })
                    hooked++
                } catch (e: Throwable) { log("支付宝 getData 注册失败：${e.javaClass.simpleName}") }
            }
            hooked
        } catch (e: Throwable) { log("支付宝采集类找不到：${e.javaClass.simpleName}"); 0 }
        installedCount = count
        log("支付宝采集：$count 个 getData 已注册（${if (count > 0) "可用" else "未找到"}）")
        return count
    }

    private fun emit(app: String, payload: String) {
        runCatching {
            val context = XposedHelpers
                .callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication") as? android.content.Context
                ?: return
            context.contentResolver.call(
                Uri.parse("content://${HookProtocol.AUTHORITY}"),
                HookProtocol.METHOD, null, HookProtocol.bundle(app, payload))
        }.onFailure { XposedBridge.log("[AutoBook-Xposed] 支付宝投递失败：${it.javaClass.simpleName}") }
    }
}
