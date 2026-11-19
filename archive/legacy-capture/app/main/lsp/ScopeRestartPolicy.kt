package io.github.mymoney.lsp

internal enum class ScopeRestartOutcome { RESTARTED, UNAVAILABLE, STOP_FAILED, START_FAILED }
internal data class ScopeRestartItem(val app: String, val outcome: ScopeRestartOutcome)
internal data class ScopeRestartReport(val items: List<ScopeRestartItem>, val rootDenied: Boolean = false)

/** Only these two trading applications belong to this action; never enumerate other modules' scopes. */
internal object ScopeRestartPolicy {
    val targets = listOf(HookProtocol.APP_WECHAT, HookProtocol.APP_ALIPAY)

    suspend fun restart(
        allowRoot: Boolean,
        available: (String) -> Boolean,
        rootGranted: suspend () -> Boolean,
        stop: suspend (String) -> Boolean,
        start: suspend (String) -> Boolean,
    ): ScopeRestartReport {
        if (!allowRoot) return ScopeRestartReport(emptyList(), rootDenied = true)
        val ready = targets.filter(available).toSet()
        if (ready.isNotEmpty() && !rootGranted()) return ScopeRestartReport(emptyList(), rootDenied = true)
        val items = targets.map { app ->
            val result = when {
                app !in ready -> ScopeRestartOutcome.UNAVAILABLE
                !stop(app) -> ScopeRestartOutcome.STOP_FAILED
                !start(app) -> ScopeRestartOutcome.START_FAILED
                else -> ScopeRestartOutcome.RESTARTED
            }
            ScopeRestartItem(app, result)
        }
        return ScopeRestartReport(items)
    }
}

/** Arguments originate in PackageManager, but must still pass an allowlist before reaching a shell. */
internal object ScopeRestartCommands {
    fun stop(app: String, userId: Int): String? =
        if (app in ScopeRestartPolicy.targets && userId >= 0) "am force-stop --user $userId '$app'" else null

    fun start(app: String, component: String, userId: Int): String? {
        if (app !in ScopeRestartPolicy.targets || userId < 0) return null
        val parts = component.split('/')
        if (parts.size != 2 || parts[0] != app || !Regex("[A-Za-z0-9_.$]+").matches(parts[1])) return null
        return "am start-activity -W --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n '$component'"
    }
}
