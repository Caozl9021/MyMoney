package io.github.mymoney.lsp

import android.os.SystemClock
import io.github.mymoney.capture.CaptureModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「这台机器上 LSPosed 到底在不在」的第二条判据：守护进程。
 *
 * 界面原本只看包名（[LsposedManagerLauncher.managerInstalled]），而管理器可以被隐藏成任意包名。
 * 真机上就吃过这个亏：模块在微信进程里跑得好好的，应用却判成「没装 LSPosed」，
 * 凭证采集整行不显示，用户没有任何入口能打开它——红包这一笔因此永远采不到。
 *
 * 这里只回答一个问题，不碰模块是否加载、也不碰作用域：`lspd` 在跑 = 这台机器有 LSPosed。
 * 拿不到 root 就不问（问也问不出来，还会弹一次授权框），结论保持 `null`。
 */
internal object LsposedPresence {

    private val mutable = MutableStateFlow<Boolean?>(null)
    val detected: StateFlow<Boolean?> = mutable.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probing = false
    private var probedAt = Long.MIN_VALUE

    /** 结论缓存一会儿：`su` 是外部进程调用，不该每次重组都问一遍。 */
    private const val TTL_MS = 5 * 60_000L

    /**
     * 有 root 才去问一次。root 结论来自 [CaptureModeManager] 的缓存，没探过就当没有——
     * 界面用不上这个结论，比多弹一个 Magisk 授权框划算。
     */
    fun probeIfRooted() {
        if (CaptureModeManager.rootCapability.value != true) return
        synchronized(this) {
            if (probing) return
            val now = SystemClock.elapsedRealtime()
            if (probedAt != Long.MIN_VALUE && now - probedAt < TTL_MS) return
            probing = true
        }
        scope.launch {
            val running = LsposedManagerLauncher.daemonRunning()
            synchronized(this@LsposedPresence) {
                probing = false
                probedAt = SystemClock.elapsedRealtime()
                mutable.value = running
            }
        }
    }
}
