package io.github.mymoney.lsp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.mymoney.capture.CaptureModeManager
import com.example.billkit.root.RootShell
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-triggered manager navigation only; never edits framework configuration or target processes. */
internal object LsposedManagerLauncher {
    private val opening = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val packages = listOf("org.lsposed.manager", "com.android.shell.lsposed", "io.github.lsposed.manager", "me.weishu.exp")
    private const val HOST = "com.android.shell"
    private const val ACTIVITY = "com.android.shell.BugreportWarningActivity"
    private const val CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER"
    // Fixed protocol arguments, never interpolated from a URI, user input, or third-party package data.
    private const val ROOT_START = "am start-activity -a android.intent.action.MAIN -n com.android.shell/.BugreportWarningActivity -c org.lsposed.manager.LAUNCH_MANAGER"

    /**
     * 这台机器上是否看得到 LSPosed 管理器。只做包可见性判断（Manifest 已声明 queries），
     * 不探测 root、不跑 shell、不发起任何跨进程调用——它要能在界面渲染路径上被调用。
     *
     * 管理器被隐藏的主题/隐私 ROM 上会返回 false，这是已知的保守方向：宁可少显示一个入口，
     * 也不要在没 root 的设备上常驻一个点了没反应的入口。
     */
    fun managerInstalled(context: Context): Boolean = packages.any { candidate ->
        context.packageManager.getLaunchIntentForPackage(candidate) != null
    }

    /**
     * LSPosed 守护进程在不在（`pidof lspd`）。**这是管理器探测之外的第二条证据**。
     *
     * 为什么需要：管理器可以是隐藏式/自定义包名（LSPosed 自己的「隐藏管理器」功能），
     * 上面那四个固定包名一个都对不上；而模块其实好好地在微信里跑。真机上就是这样——
     * 凭证通道整行被藏掉，用户没有任何入口打开它。守护进程与包名无关，能补上这一格。
     *
     * 只有拿到 root 才问得出来，所以调用方必须先确认 root。
     */
    suspend fun daemonRunning(): Boolean = withContext(Dispatchers.IO) {
        val check = runCatching { RootShell.exec("pidof lspd", timeoutMs = 4_000) }.getOrNull()
        check != null && ManagerLaunchPolicy.daemonEvidence(check.code, check.output)
    }

    fun open(context: Context) {
        if (!opening.compareAndSet(false, true)) return
        val app = context.applicationContext
        // 用不用 root shell 去拉起管理器，是「这台机器有没有 root」的事，跟当初选了哪个预设无关。
        // 没探过（null）就按没有处理：先走独立入口与寄生 Activity，两条都不成再谈 root。
        val allowRoot = CaptureModeManager.rootCapability.value == true
        scope.launch {
            try {
                val result = ManagerLaunchPolicy.open(
                    standalone = packages.map { candidate -> {
                        val intent = app.packageManager.getLaunchIntentForPackage(candidate)
                        if (intent == null) false else {
                            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            true
                        }
                    } },
                    allowRoot = allowRoot,
                    daemonRunning = { daemonRunning() },
                    parasitic = {
                        app.startActivity(Intent(Intent.ACTION_MAIN)
                            .setComponent(ComponentName(HOST, ACTIVITY)).setPackage(HOST)
                            .addCategory(CATEGORY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    },
                    rootParasitic = {
                        withContext(Dispatchers.IO) {
                            val launch = RootShell.exec(ROOT_START, timeoutMs = 6_000)
                            ManagerLaunchPolicy.acceptedShellStart(launch.code, launch.output)
                        }
                    },
                )
                val error = when (result) {
                    ManagerOpenResult.NO_ENTRY -> "未找到独立管理器，请从 LSPosed 通知或桌面快捷方式打开"
                    ManagerOpenResult.UNCONFIRMED -> "无法确认 LSPosed 正在运行，请检查 Root 授权或从系统入口打开"
                    ManagerOpenResult.FAILED -> "无法打开 LSPosed 管理器，请从通知或桌面快捷方式打开"
                    else -> null
                }
                if (error != null) Toast.makeText(app, error, Toast.LENGTH_LONG).show()
            } finally {
                opening.set(false)
            }
        }
    }
}
