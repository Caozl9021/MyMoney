package com.example.mymoney.lsp

import android.content.Context
import android.os.Process
import com.example.mymoney.capture.CaptureModeManager
import com.example.mymoney.capture.HookTargetMonitor
import com.example.mymoney.data.CaptureMode
import com.example.billkit.root.RootShell
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ScopeRestartUiState(val busy: Boolean = false, val message: String? = null)

/** No automatic restart, persistence, or scope edits. An explicit button press owns this operation. */
internal object ScopeAppRestarter {
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ScopeRestartUiState())
    val state = mutableState.asStateFlow()

    fun restart(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val allowRoot = CaptureModeManager.state.value.mode == CaptureMode.ROOT
        mutableState.value = ScopeRestartUiState(busy = true)
        scope.launch {
            try {
                // Same Android per-user UID partition used by the authenticated LSP provider.
                val userId = Process.myUid() / 100_000
                val starts = ScopeRestartPolicy.targets.mapNotNull { target ->
                    runCatching {
                        val component = app.packageManager.getLaunchIntentForPackage(target)?.component?.flattenToShortString()
                        component?.let { ScopeRestartCommands.start(target, it, userId) }?.let { target to it }
                    }.getOrNull()
                }.toMap()
                val report = ScopeRestartPolicy.restart(
                    allowRoot = allowRoot,
                    available = { it in starts },
                    rootGranted = {
                        withContext(Dispatchers.IO) {
                            val result = RootShell.exec("id -u", timeoutMs = 4_000)
                            result.ok && result.output.trim() == "0"
                        }
                    },
                    stop = { target -> withContext(Dispatchers.IO) {
                        val command = ScopeRestartCommands.stop(target, userId)
                        if (command == null) false else {
                            val result = RootShell.exec(command, timeoutMs = 6_000)
                            result.ok && !Regex("error|exception|permission denial", RegexOption.IGNORE_CASE).containsMatchIn(result.output)
                        }
                    } },
                    start = { target -> withContext(Dispatchers.IO) {
                        val command = starts[target]
                        if (command == null) false else {
                            val result = RootShell.exec(command, timeoutMs = 12_000)
                            ManagerLaunchPolicy.acceptedShellStart(result.code, result.output)
                        }
                    } },
                )
                var message = if (report.rootDenied) "未取得 Root 权限，未重启应用" else report.items.joinToString(" · ") {
                    val label = if (it.app == HookProtocol.APP_WECHAT) "微信" else "支付宝"
                    label + when (it.outcome) {
                        ScopeRestartOutcome.RESTARTED -> "已重启"
                        ScopeRestartOutcome.UNAVAILABLE -> "无可用启动入口"
                        ScopeRestartOutcome.STOP_FAILED -> "停止失败"
                        ScopeRestartOutcome.START_FAILED -> "启动未确认，请手动打开"
                    }
                }
                if (report.items.any { it.outcome == ScopeRestartOutcome.RESTARTED }) {
                    // Bring the existing MyMoney task back without clearing the settings route.
                    val returned = withContext(Dispatchers.IO) {
                        val result = RootShell.exec("am start-activity --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n com.example.mymoney/.ui.MainActivity", timeoutMs = 6_000)
                        ManagerLaunchPolicy.acceptedShellStart(result.code, result.output)
                    }
                    HookTargetMonitor.detect(app)
                    if (!returned) message += "；请返回本页查看检测结果"
                }
                mutableState.value = ScopeRestartUiState(message = message)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = ScopeRestartUiState(message = "操作未完成，请手动打开应用并重新检测")
            } finally {
                if (mutableState.value.busy) mutableState.value = ScopeRestartUiState(message = "重启未完成，请检查 Root 权限后重试")
                running.set(false)
            }
        }
    }
}
