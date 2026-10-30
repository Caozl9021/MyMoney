package com.example.mymoney.capture

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.example.mymoney.lsp.HookProtocol
import com.example.mymoney.lsp.HookReceiptCodec
import com.example.mymoney.lsp.HookTargetRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Explicit metadata-only target probes. They do not launch applications or enter capture queues. */
object HookTargetMonitor {
    private const val PREFS = "hook_target_diagnostics"
    private const val RECEIPTS = "last_receipts"
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registry = HookTargetRegistry(HookProtocol.hookedPackages)
    private var prefs: SharedPreferences? = null
    private val mutableState = MutableStateFlow(registry.snapshot())
    val state = mutableState.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (prefs != null) return@synchronized
        val storage = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val receipts = HookReceiptCodec.decode(storage.getString(RECEIPTS, null), HookProtocol.hookedPackages)
        registry = HookTargetRegistry(HookProtocol.hookedPackages, receipts)
        prefs = storage
        mutableState.value = registry.snapshot()
    }

    fun detect(context: Context) {
        val app = context.applicationContext
        init(app)
        val expected = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            "${info.versionName}/${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()}"
        }.getOrDefault(HookTargetRegistry.UNKNOWN_VERSION)
        for (target in HookProtocol.hookedPackages) {
            val nonce = UUID.randomUUID().toString()
            val began = synchronized(lock) {
                registry.begin(target, nonce, expected, SystemClock.elapsedRealtime()).also {
                    mutableState.value = registry.snapshot()
                }
            }
            if (!began) continue
            // Receiver-side signature permission authenticates this sender. Do not require that
            // target apps themselves hold our permission, and do not include stopped packages.
            runCatching {
                app.sendBroadcast(Intent(HookProtocol.PROBE_ACTION).setPackage(target)
                    .putExtra(HookProtocol.EXTRA_NONCE, nonce))
            }
            scope.launch {
                delay(HookTargetRegistry.TIMEOUT_MS + 50L)
                synchronized(lock) {
                    if (registry.expire(target, nonce, SystemClock.elapsedRealtime()))
                        mutableState.value = registry.snapshot()
                }
            }
        }
    }

    fun accept(context: Context, extras: Bundle, callerPackages: Set<String>): Boolean = runCatching {
        init(context)
        val app = extras.getString(HookProtocol.EXTRA_APP) ?: return@runCatching false
        val nonce = extras.getString(HookProtocol.EXTRA_NONCE) ?: return@runCatching false
        val version = extras.getString(HookProtocol.EXTRA_VERSION) ?: return@runCatching false
        val count = extras.getInt(HookProtocol.EXTRA_REGISTERED, -1)
        val error = extras.getString(HookProtocol.EXTRA_ERROR)?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            registry.accept(app, nonce, version, count, error, callerPackages, SystemClock.elapsedRealtime()).also {
                if (it) mutableState.value = registry.snapshot()
            }
        }
    }.getOrDefault(false)

    fun received(context: Context, app: String, at: Long = System.currentTimeMillis()) {
        init(context)
        synchronized(lock) {
            if (registry.received(app, at)) {
                prefs?.edit()?.putString(RECEIPTS, HookReceiptCodec.encode(registry.receiptTimes()))?.apply()
                mutableState.value = registry.snapshot()
            }
        }
    }
}
