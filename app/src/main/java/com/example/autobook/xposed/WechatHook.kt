package com.example.autobook.xposed

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import com.example.autobook.lsp.HookProtocol
import com.example.billkit.spec.WechatCollector
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 微信采集（SPEC §3）：在 `SQLiteDatabase#insertWithOnConflict` 之后读 `ContentValues`。
 *
 * 为什么在写入层而不是读数据库文件：微信自己的消息表是加密的（`EnMicroMsg.db`），
 * 解密要拿设备指纹算密钥，版本一换就废；而**写库这一刻明文就在 `ContentValues` 里**，
 * 不需要解密、不需要文件权限、也不依赖表结构（只按表名和 type 字段分派）。
 *
 * 运行在微信进程内，所以这里只做「摊平 → 交给 :billkit 的采集器 → 投回宿主」，
 * 判定（规则/分类/去重/落库）全部在宿主 App 里做。
 */
internal object WechatHook {

    private const val CLASS = "com.tencent.wcdb.database.SQLiteDatabase"

    @Volatile private var installedCount = 0
    @Volatile private var collector: WechatCollector? = null

    @Synchronized
    fun install(loader: ClassLoader, log: (String) -> Unit): Int {
        if (installedCount > 0) return installedCount
        val emitter: (String, String, String) -> Unit = { app, type, payload -> emit(app, type, payload) }
        val instance = WechatCollector(emitter = emitter)
        collector = instance

        var hooked = 0
        // SPEC §3.6：insert() 与 insertWithOnConflict() 都要挂——微信不同版本走不同重载，
        // 只挂一个会在某些机型上整条通道静默失效。
        for (name in listOf("insertWithOnConflict", "insert")) {
            hooked += hookMethod(loader, name, instance, log)
        }
        hooked += WechatRedpacketHook.install(loader, log)
        installedCount = hooked
        log("微信采集：$hooked 个采集方法已注册（${if (hooked > 0) "可用" else "未找到，版本可能不兼容"}）")
        return hooked
    }

    private fun hookMethod(loader: ClassLoader, name: String, collector: WechatCollector, log: (String) -> Unit): Int {
        return try {
            val klass = XposedHelpers.findClass(CLASS, loader)
            val matches = klass.declaredMethods.filter { it.name == name && it.parameterTypes.firstOrNull() == String::class.java }
            var count = 0
            for (method in matches) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = runCatching {
                            val table = param.args.getOrNull(0) as? String ?: return@runCatching
                            // insert 系列的 ContentValues 恒在第 3 参（table, nullColumnHack, values, ...）
                            val values = param.args.firstOrNull { it is ContentValues } as? ContentValues
                                ?: return@runCatching
                            // 只在真正关心的表上打日志：message 表消息量极大，
                            // 全量日志会淹没 LSPosed 日志并拖慢微信。
                            if (table == "message" || table == "AppMessage") {
                                XposedBridge.log("[AutoBook-Xposed] 微信写库 table=$table type=${values.getAsInteger("type")}")
                            }
                            collector.onInsert(table, flatten(values))
                        }.onFailure { log("微信采集回调异常：${it.javaClass.simpleName}") }.let { }
                    })
                    log("微信 $name 已挂：${method.parameterTypes.joinToString(",") { it.simpleName }}")
                    count++
                } catch (e: Throwable) { log("微信 $name 注册失败：${e.javaClass.simpleName}") }
            }
            count
        } catch (e: Throwable) { log("微信 $name 找不到：${e.javaClass.simpleName}"); 0 }
    }

    /**
     * `ContentValues` → `Map`。这一步必须在微信进程里做：`ContentValues` 是 Android 类，
     * 宿主侧的采集器跑在 JVM 单测里读不到它的值，用 Map 才能让同一份逻辑两端都测。
     */
    private fun flatten(values: ContentValues): Map<String, Any?> {
        val map = HashMap<String, Any?>(values.size().coerceAtLeast(4))
        for (key in values.keySet()) map[key] = values.get(key)
        return map
    }

    /** 投回宿主：走 provider，UID 由内核填，宿主用 getPackagesForUid 校验来源。 */
    private fun emit(app: String, type: String, payload: String) {
        runCatching {
            val context = de.robv.android.xposed.XposedHelpers
                .callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication") as? android.content.Context
                ?: return
            context.contentResolver.call(
                Uri.parse("content://${HookProtocol.AUTHORITY}"),
                HookProtocol.METHOD, null, HookProtocol.bundle(app, payload))
        }.onFailure { XposedBridge.log("[AutoBook-Xposed] 微信投递失败：${it.javaClass.simpleName}") }
    }
}
