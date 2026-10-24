package com.example.autobook.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.example.autobook.lsp.LspProtocol
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fresh nonce handshake with system_server. Never trusts persisted properties or framework traces. */
object XposedModuleDetector {
    enum class HookState { NOT_DETECTED, FRAMEWORK_PRESENT, MODULE_CONFIRMED }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(HookState.NOT_DETECTED)
    val state: StateFlow<HookState> = _state.asStateFlow()
    private val _detail = MutableStateFlow("尚未收到本次系统回执；不能据此判断 LSPosed 是否安装")
    val detail: StateFlow<String> = _detail.asStateFlow()
    private var nonce: String? = null
    private var sentAt = 0L
    private var expectedVersion = ""

    @Synchronized
    fun detect(context: Context): HookState {
        val now = SystemClock.elapsedRealtime()
        if (nonce != null && now - sentAt in 0..LspProtocol.TIMEOUT_MS) return _state.value
        val request = UUID.randomUUID().toString()
        nonce = request
        sentAt = now
        _state.value = HookState.NOT_DETECTED
        _detail.value = "正在向系统框架请求本次模块状态…"
        val app = context.applicationContext
        scope.launch {
            try {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                synchronized(this@XposedModuleDetector) {
                    if (nonce != request) return@launch
                    expectedVersion = "${info.versionName}/${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()}"
                }
                app.sendBroadcast(Intent(LspProtocol.ACTION).setPackage("android")
                    .putExtra("nonce", request).putExtra("uid", Process.myUid()))
            } catch (_: Exception) {
                // Timeout has the same truthful outcome: no authenticated response.
            }
            delay(LspProtocol.TIMEOUT_MS)
            synchronized(this@XposedModuleDetector) {
                if (nonce == request) {
                    nonce = null
                    _detail.value = "未收到本次模块回执；请核对 LSPosed 启用与系统框架作用域，升级后需重启系统"
                }
            }
        }
        return _state.value
    }

    @Synchronized
    fun receive(reply: Bundle) {
        if (!LspProtocol.acceptsReply(nonce, reply.getString("nonce"), sentAt, SystemClock.elapsedRealtime())) return
        nonce = null
        val loaded = reply.getString("version") ?: "未知"
        val registrations = reply.getString("registrations") ?: "无注册明细"
        _state.value = if (loaded == expectedVersion) HookState.MODULE_CONFIRMED else HookState.FRAMEWORK_PRESENT
        _detail.value = (if (loaded == expectedVersion) "已确认当前模块 $loaded" else "系统仍加载 $loaded，当前安装 $expectedVersion；需重启系统") +
            "\n$registrations\n本次启动拦截 ${reply.getInt("interceptions")} 次；注册不代表实际保护有效"
        val generation = sentAt
        scope.launch {
            delay(LspProtocol.FRESH_MS)
            synchronized(this@XposedModuleDetector) {
                if (sentAt == generation) {
                    _state.value = HookState.NOT_DETECTED
                    _detail.value = "系统回执已过期；点击重新检测"
                }
            }
        }
    }
}
