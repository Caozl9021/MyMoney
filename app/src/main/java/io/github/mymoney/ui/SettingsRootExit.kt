package io.github.mymoney.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** A root request must pass the active page's exit guard; stale approvals cannot navigate. */
internal class SettingsRootExitGate {
    private val guards = linkedMapOf<Any, ((() -> Unit) -> Unit)>()
    private var generation = 0L

    fun register(key: Any, guard: (() -> Unit) -> Unit) { guards[key] = guard }
    fun unregister(key: Any) { guards.remove(key) }
    fun invalidate() { generation++ }

    fun request(onAllowed: () -> Unit) {
        val ticket = ++generation
        var used = false
        val allow = {
            if (!used && ticket == generation) {
                used = true
                onAllowed()
            }
        }
        guards.values.lastOrNull()?.invoke(allow) ?: allow()
    }
}

internal val LocalSettingsRootExitGate = staticCompositionLocalOf<SettingsRootExitGate?> { null }

/** Modal editors already cover the dock. Inline pages register their existing leave policy here. */
@Composable
internal fun SettingsRootExitHandler(enabled: Boolean = true, onRequest: (() -> Unit) -> Unit) {
    val gate = LocalSettingsRootExitGate.current
    val active = LocalBackActive.current
    val key = remember { Any() }
    val latest = rememberUpdatedState(onRequest)
    DisposableEffect(gate, enabled, active) {
        if (enabled && active) gate?.register(key) { latest.value(it) }
        onDispose { gate?.unregister(key) }
    }
}
