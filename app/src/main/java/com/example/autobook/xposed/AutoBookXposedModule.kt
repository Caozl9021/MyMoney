package com.example.autobook.xposed

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import com.example.autobook.lsp.LspProtocol
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** System-framework-only module. Observers never retain a dead Binder or veto explicit revocation. */
class AutoBookXposedModule : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var modulePath: String? = null
    @Volatile private var systemContext: Context? = null
    @Volatile private var loadedVersion = "未知"
    private val registrations = ConcurrentHashMap<String, String>()
    private val interceptions = AtomicInteger()
    @Volatile private var connection = WeakReference<Any>(null)
    private val target = ComponentName.unflattenFromString(LspProtocol.COMPONENT)!!

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") return
        val loader = lpparam.classLoader
        observe(loader, "com.android.server.accessibility.AccessibilityServiceConnection", "unbindLocked") { p ->
            if (isTarget(p.thisObject)) log("unbindLocked：仅监测，允许解绑以保留撤权/重绑/包更新语义")
        }
        observe(loader, "com.android.server.accessibility.AccessibilityServiceConnection", "onServiceConnected", after = true) { p ->
            if (isTarget(p.thisObject)) connection = WeakReference(p.thisObject)
        }
        observe(loader, "com.android.server.accessibility.AccessibilityManagerService", "disableAccessibilityServiceLocked") { p ->
            if (p.args.firstOrNull() == target) log("disableAccessibilityServiceLocked：仅监测，允许撤权")
        }
        registerKills(loader)
        // At this point the system Context/PackageManager may not exist. Register after boot services.
        observe(loader, "com.android.server.SystemServer", "startOtherServices", after = true) {
            registerProbe()
        }
        registerProbe()
        log("逐项注册结果：${registrationText()}")
    }

    private fun observe(loader: ClassLoader, className: String, method: String, after: Boolean = false,
                        action: (XC_MethodHook.MethodHookParam) -> Unit) {
        val key = "${className.substringAfterLast('.')}.$method"
        try {
            val klass = XposedHelpers.findClass(className, loader)
            val matches = klass.declaredMethods.filter { it.name == method }
            if (matches.isEmpty()) {
                registrations[key] = "不支持（无此方法）"
                return
            }
            var success = 0
            for (candidate in matches) {
                try {
                    XposedBridge.hookMethod(candidate, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!after) safely { action(param) }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (after) safely { action(param) }
                        }
                    })
                    success++
                } catch (e: Throwable) { log("注册 $candidate 失败：${e.javaClass.simpleName}") }
            }
            registrations[key] = "$success/${matches.size} 已注册（仅监测）"
        } catch (e: Throwable) { registrations[key] = "失败：${e.javaClass.simpleName}" }
    }

    private fun registerKills(loader: ClassLoader) {
        val key = "killApplication"
        try {
            val klass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", loader)
            // Match exact known signatures independently, including this ROM's five-argument overload.
            val signatures = listOf(
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!, String::class.java),
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java),
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!)
            )
            for (types in signatures) {
                val label = "$key/${types.size}参"
                try {
                    val method = klass.getDeclaredMethod(key, *types)
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) = safely {
                            // Reject unrelated/system lifecycle kills before reflection or any settings IPC.
                            val reasonIndex = if (types.size == 3) 2 else 3
                            val packageName = param.args[0] as? String
                            val reason = param.args[reasonIndex] as? String
                            if (packageName != LspProtocol.PACKAGE || reason != "background restriction" ||
                                Binder.getCallingUid() != Process.SYSTEM_UID) return@safely
                            val exit = if (types.size == 5) param.args[4] as? Int ?: return@safely else null
                            if (exit != null && exit != 13) return@safely
                            val userId = if (types.size == 3) 0 else param.args[2] as? Int ?: return@safely
                            val ctx = systemContext ?: return@safely
                            val live = connectionState(userId) == CONNECTION_READY
                            if (!live) return@safely
                            val enabled = authorized(ctx, userId)
                            if (LspProtocol.protectKill(packageName, reason, true, enabled, live, exit)) {
                                param.result = null
                                interceptions.incrementAndGet()
                                log("已拦截当前用户授权且连接存活的 background restriction killApplication")
                            }
                        }
                    })
                    registrations[label] = "已注册（窄理由白名单：background restriction）"
                } catch (e: NoSuchMethodException) { registrations[label] = "不支持（无此签名）" }
                catch (e: Throwable) { registrations[label] = "失败：${e.javaClass.simpleName}" }
            }
        } catch (e: Throwable) { registrations[key] = "失败：${e.javaClass.simpleName}" }
    }

    private fun isTarget(value: Any?): Boolean = runCatching {
        value != null && XposedHelpers.getObjectField(value, "mComponentName") == target
    }.getOrDefault(false)

    private companion object {
        const val CONNECTION_READY = "连接已就绪"
    }

    /** Snapshot only: never acquire an accessibility framework monitor from an AMS kill hook.
     * The connection is published after onServiceConnected through a volatile reference.
     */
    private fun connectionState(userId: Int): String {
        val value = connection.get() ?: return "目标连接未观察"
        return try {
            run {
                if (XposedHelpers.getObjectField(value, "mComponentName") != target)
                    return@run "目标组件不符"
                val connectionUser = runCatching { XposedHelpers.getIntField(value, "mUserId") }.getOrElse {
                    val state = (XposedHelpers.getObjectField(value, "mUserStateWeakReference") as? WeakReference<*>)?.get()
                        ?: return@run "目标用户状态不可用"
                    XposedHelpers.getIntField(state, "mUserId")
                }
                if (connectionUser != userId) return@run "授权与当前用户不符（连接用户）"
                // Current ROM uses mClientBinder. Older AOSP used mService. A present-but-null
                // current field means disconnected, so only fall back when the field is absent.
                val binderField = runCatching { XposedHelpers.findField(value.javaClass, "mClientBinder") }
                    .getOrElse { XposedHelpers.findField(value.javaClass, "mService") }
                val binder = binderField.get(value) as? IBinder
                if (binder?.isBinderAlive != true) "死Binder或连接已解绑" else CONNECTION_READY
            }
        } catch (e: Throwable) { "字段不兼容：${e.javaClass.simpleName}" }
    }

    private fun runtimeStatus(ctx: Context, userId: Int): String {
        val connectionStatus = connectionState(userId)
        if (connectionStatus != CONNECTION_READY) return connectionStatus
        return if (authorized(ctx, userId)) "已就绪（当前用户仍授权、目标连接与Binder存活）"
        else "授权与当前用户不符，或授权查询不可用"
    }

    private fun userContext(ctx: Context, userId: Int): Context {
        val user = XposedHelpers.callStaticMethod(UserHandle::class.java, "of", userId)
        return XposedHelpers.callMethod(ctx, "createContextAsUser", user, 0) as Context
    }

    private fun authorized(ctx: Context, userId: Int): Boolean = runCatching {
        // Fail open on unsupported ROM fields/settings. Never write secure settings here.
        val currentUser = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityManager", null), "getCurrentUser") as Int
        if (userId != currentUser) return false
        Settings.Secure.getString(userContext(ctx, userId).contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')
            ?.mapNotNull(ComponentName::unflattenFromString)?.contains(target) == true
    }.getOrDefault(false)

    @Synchronized
    private fun registerProbe() {
        if (systemContext != null) return
        try {
            val thread = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread")
            val ctx = XposedHelpers.callMethod(thread, "getSystemContext") as Context
            val archive = modulePath?.let { ctx.packageManager.getPackageArchiveInfo(it, 0) }
                ?: throw IllegalStateException("loaded APK version unavailable")
            val version = "${archive.versionName}/${if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val nonce = intent.getStringExtra("nonce") ?: return
                    if (nonce.length !in 16..100) return
                    val uid = intent.getIntExtra("uid", -1)
                    if (uid < 0 || ctx.packageManager.getPackagesForUid(uid)?.contains(LspProtocol.PACKAGE) != true) return
                    // Receiver also requires our signature permission; responses go only to our provider.
                    val reply = Bundle().apply {
                        putString("nonce", nonce)
                        putString("version", loadedVersion)
                        putString("registrations", registrationText() + "\n连接检查：" + runtimeStatus(ctx, uid / 100000))
                        putInt("interceptions", interceptions.get())
                    }
                    val token = Binder.clearCallingIdentity()
                    try {
                        userContext(ctx, uid / 100000).contentResolver.call(
                            Uri.parse("content://${LspProtocol.AUTHORITY}"), LspProtocol.METHOD, null, reply)
                    } catch (e: Throwable) { log("状态回执失败：${e.javaClass.simpleName}") }
                    finally { Binder.restoreCallingIdentity(token) }
                }
            }
            val allUsers = XposedHelpers.callStaticMethod(UserHandle::class.java, "of", -1)
            val worker = HandlerThread("AutoBook-LspProbe").apply { start() }
            val handler = Handler(worker.looper)
            try {
                if (Build.VERSION.SDK_INT >= 33) XposedHelpers.callMethod(ctx, "registerReceiverAsUser", receiver,
                    allUsers, IntentFilter(LspProtocol.ACTION), LspProtocol.PERMISSION, handler, Context.RECEIVER_EXPORTED)
                else XposedHelpers.callMethod(ctx, "registerReceiverAsUser", receiver,
                    allUsers, IntentFilter(LspProtocol.ACTION), LspProtocol.PERMISSION, handler)
            } catch (error: Throwable) {
                worker.quitSafely()
                throw error
            }
            loadedVersion = version
            systemContext = ctx
            log("系统握手接收器已注册，加载版本 $version")
        } catch (e: Throwable) { log("系统握手接收器尚不可用：${e.javaClass.simpleName}") }
    }

    private fun registrationText() = registrations.toSortedMap().entries.joinToString("\n") { "${it.key}: ${it.value}" }
    private inline fun safely(block: () -> Unit) { try { block() } catch (e: Throwable) { log("运行时放行：${e.javaClass.simpleName}") } }
    private fun log(message: String) = XposedBridge.log("[AutoBook-Xposed] $message")
}
