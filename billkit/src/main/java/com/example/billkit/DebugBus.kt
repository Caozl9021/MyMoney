package com.example.billkit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 调试总线：把"最近一次看到了什么、结论是什么"暴露给 UI。
 * 这是这套方案能被维护的关键 —— 页面改版时先看这里，再改规则。
 */
object DebugBus {

    data class State(
        val packageName: String,
        val activityClass: String?,
        val source: String,
        val outcome: String,
        val text: String,
        val atMillis: Long,
    )

    /** Called once per published capture, never on delivery/source annotations. */
    @Volatile var publicationListener: ((State) -> Unit)? = null

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    fun publish(
        snapshot: UiSnapshot,
        outcome: String,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        val next = State(
            packageName = snapshot.packageName,
            activityClass = snapshot.activityClass,
            source = snapshot.source.name,
            outcome = outcome,
            text = snapshot.joined(),
            atMillis = atMillis,
        )
        _state.value = next
        runCatching { publicationListener?.invoke(next) }
    }

    fun clear() {
        _state.value = null
    }

    /**
     * 往当前结论后面追加一段（例如投递结果 `→ overlay`），
     * 让调试面板能直接看出"识别后到底送到哪里了"。
     */
    fun annotateOutcome(suffix: String) {
        val current = _state.value ?: return
        if (current.outcome.endsWith(suffix)) return
        _state.value = current.copy(outcome = current.outcome + suffix)
    }
}
