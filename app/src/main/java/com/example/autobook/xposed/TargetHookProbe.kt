package com.example.autobook.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.example.autobook.lsp.HookProtocol
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/** Target-side registration report. Never observes or synthesizes a transaction. */
internal object TargetHookProbe {
    private data class Registration(
        val app: String,
        val count: Int,
        val modulePath: String?,
        val error: String?,
        val log: (String) -> Unit,
    )

    @Volatile private var pending: Registration? = null
    private var receiverRegistered = false
    private var attachHook: XC_MethodHook.Unhook? = null

    /** Called only from the selected target's main process after capture hooks were installed. */
    @Synchronized
    fun install(app: String, registered: Int, modulePath: String?, error: String?, log: (String) -> Unit) {
        if (app !in HookProtocol.hookedPackages || receiverRegistered) return
        val registration = Registration(app, registered.coerceIn(0, 32), modulePath, error, log)
        pending = registration
        if (attachHook == null) {
            // handleLoadPackage normally precedes Application.attach. Do not require the host app
            // Context or a target-specific Application class to be available at that time.
            try {
                attachHook = XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val current = pending ?: return
                            val context = param.thisObject as? Context ?: return
                            register(context, current)
                        }
                    })
            } catch (e: Throwable) {
                log("目标状态生命周期注册失败：${e.javaClass.simpleName}")
            }
        }
        // Installation can also run after attach on a compatible loader/framework.
        val current = runCatching {
            XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication") as? Context
        }.getOrNull()
        if (current != null) register(current, registration)
    }

    @Synchronized
    private fun register(candidate: Context, registration: Registration) {
        if (receiverRegistered) return
        var worker: HandlerThread? = null
        try {
            val context = candidate.applicationContext ?: candidate
            if (context.packageName != registration.app) return
            val thread = HandlerThread("AutoBook-TargetProbe").apply { start() }
            worker = thread
            val handler = Handler(thread.looper)
            // Read the loaded archive once, before a later APK replacement removes its path.
            // The worker queues this before it handles any probe, keeping target startup light.
            var version = "unknown"
            handler.post {
                version = loadedVersion(context, registration.modulePath)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ignored: Context, intent: Intent) {
                    try {
                        if (intent.action != HookProtocol.PROBE_ACTION || intent.`package` != registration.app) return
                        val nonce = intent.getStringExtra(HookProtocol.EXTRA_NONCE) ?: return
                        if (nonce.length !in 16..100) return
                        val error = registration.error ?: if (version == "unknown") HookProtocol.ERROR_VERSION_UNKNOWN else null
                        context.contentResolver.call(Uri.parse("content://${HookProtocol.AUTHORITY}"),
                            HookProtocol.STATUS_METHOD, null,
                            HookProtocol.statusBundle(registration.app, nonce, version, registration.count, error))
                    } catch (e: Throwable) {
                        registration.log("目标加载状态回报失败：${e.javaClass.simpleName}")
                    }
                }
            }
            val filter = IntentFilter(HookProtocol.PROBE_ACTION)
            // The receiver is exported only for the signature-authorized AutoBook sender.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter,
                HookProtocol.PERMISSION, handler, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter, HookProtocol.PERMISSION, handler)
            receiverRegistered = true
            runCatching { attachHook?.unhook() }
            attachHook = null
            pending = null
            registration.log("目标加载状态接收器已注册：${registration.app}，采集方法 ${registration.count} 个")
        } catch (e: Throwable) {
            if (!receiverRegistered) worker?.quitSafely()
            // Leave the lifecycle hook/pending registration available for a later context retry.
            registration.log("目标加载状态接收器尚不可用：${e.javaClass.simpleName}")
        }
    }

    private fun loadedVersion(context: Context, path: String?): String = try {
        val archive = path?.let { context.packageManager.getPackageArchiveInfo(it, 0) }
        if (archive == null) "unknown"
        else "${archive.versionName}/${if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()}"
    } catch (_: Throwable) {
        "unknown"
    }
}
