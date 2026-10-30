package com.example.mymoney.xposed

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.mymoney.lsp.HookProtocol
import com.example.billkit.spec.WechatRedpacketReceiptAdapter as Receipt
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Passive, version-scoped successful business callbacks. Never submits or opens a redpacket. */
internal object WechatRedpacketHook {
    private data class Cached(val request: Receipt.Request, val time: Long)
    private val requests = WeakHashMap<Any, Cached>()
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "AutoBook-Redpacket").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var compatible: Boolean? = null

    fun install(loader: ClassLoader, log: (String) -> Unit): Int {
        var count = 0
        try {
            val base = XposedHelpers.findClass(Receipt.PREFIX + "q5", loader)
            val setter = base.getDeclaredMethod("setRequestData", Map::class.java)
            XposedBridge.hookMethod(setter, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable() || !supported(log)) return
                    val instance = param.thisObject ?: return
                    val fields = param.args.firstOrNull() as? Map<*, *> ?: return
                    val request = Receipt.request(instance.javaClass.name, fields) ?: return
                    val now = System.currentTimeMillis()
                    synchronized(requests) {
                        requests.entries.removeAll { now - it.value.time > 600_000 }
                        if (requests.size >= 256 && !requests.containsKey(instance)) return
                        val previous = requests[instance]
                        // A reused object with changed request identity/amount is unusable evidence.
                        requests[instance] = if (previous != null && previous.request != request)
                            Cached(Receipt.Request(null, null), now) else Cached(request, now)
                    }
                }
            })
            count++
        } catch (e: Throwable) { log("红包请求接口不可用：${e.javaClass.simpleName}") }
        for (name in Receipt.classes) {
            try {
                val klass = XposedHelpers.findClass(name, loader)
                val method = klass.getDeclaredMethod("onGYNetEnd", Int::class.javaPrimitiveType,
                    String::class.java, JSONObject::class.java)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable() || !supported(log) || param.thisObject?.javaClass?.name != name) return
                        runCatching {
                            val error = param.args.getOrNull(0) as? Int ?: return
                            if (error != 0) return
                            val json = param.args.getOrNull(2) as? JSONObject ?: return
                            // Whitelist scalar keys only; names, recipient records and full responses never leave WeChat.
                            val fields = mutableMapOf<String, Any?>()
                            for (key in listOf("sendId", "amount", "totalAmount", "isSender")) {
                                if (json.has(key)) fields[key] = json.opt(key)
                            }
                            val cached = synchronized(requests) { requests[param.thisObject] }
                                ?.takeIf { System.currentTimeMillis() - it.time <= 600_000 }?.request
                            val raw = Receipt.response(name, error, fields, cached, System.currentTimeMillis()) ?: return
                            val context = context() ?: return
                            worker.execute {
                                runCatching {
                                    context.contentResolver.call(Uri.parse("content://${HookProtocol.AUTHORITY}"),
                                        HookProtocol.METHOD, null, HookProtocol.bundle(HookProtocol.APP_WECHAT, raw))
                                }.onFailure { log("红包凭证投递失败：${it.javaClass.simpleName}") }
                            }
                        }.onFailure { log("红包回执读取失败：${it.javaClass.simpleName}") }
                    }
                })
                count++
            } catch (e: Throwable) { log("红包回执接口 ${name.substringAfterLast('.')} 不可用：${e.javaClass.simpleName}") }
        }
        log("红包回执已注册 $count 个方法；仅8.0.79/3200输出同单金额证据")
        return count
    }

    private fun supported(log: (String) -> Unit): Boolean {
        compatible?.let { return it }
        val context = context() ?: return false
        return synchronized(this) {
            compatible ?: runCatching {
                val info = context.packageManager.getPackageArchiveInfo(context.applicationInfo.sourceDir, 0)
                    ?: return@runCatching false
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                (info.versionName == "8.0.79" && code == 3200L).also {
                    compatible = it
                    log("红包金额适配 ${Receipt.VERSION}：${if (it) "版本匹配" else "版本不匹配，跳过新回执适配"}")
                }
            }.getOrElse { false }
        }
    }

    private fun context(): Context? = runCatching {
        XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentApplication") as? Context
    }.getOrNull()
}
