package com.example.autobook.lsp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.data.CaptureMode
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

    fun open(context: Context) {
        if (!opening.compareAndSet(false, true)) return
        val app = context.applicationContext
        val allowRoot = CaptureModeManager.state.value.mode == CaptureMode.ROOT
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
                    daemonRunning = {
                        withContext(Dispatchers.IO) {
                            val check = RootShell.exec("pidof lspd", timeoutMs = 4_000)
                            check.ok && Regex("[0-9]+(?:\\s+[0-9]+)*").matches(check.output.trim())
                        }
                    },
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
