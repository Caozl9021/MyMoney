package com.example.mymoney.lsp

/** Entry availability is deliberately independent of target-module readiness. */
internal enum class ManagerOpenResult { STANDALONE, PARASITIC, ROOT_PARASITIC, NO_ENTRY, UNCONFIRMED, FAILED }

internal object ManagerLaunchPolicy {
    suspend fun open(
        standalone: List<() -> Boolean>,
        allowRoot: Boolean,
        daemonRunning: suspend () -> Boolean,
        parasitic: () -> Boolean,
        rootParasitic: suspend () -> Boolean,
    ): ManagerOpenResult {
        for (attempt in standalone) {
            if (runCatching { attempt() }.getOrDefault(false)) return ManagerOpenResult.STANDALONE
        }
        if (!allowRoot) return ManagerOpenResult.NO_ENTRY
        // Never open the ordinary system bugreport screen on a device without a confirmed daemon.
        if (!daemonRunning()) return ManagerOpenResult.UNCONFIRMED
        if (runCatching { parasitic() }.getOrDefault(false)) return ManagerOpenResult.PARASITIC
        return if (rootParasitic()) ManagerOpenResult.ROOT_PARASITIC else ManagerOpenResult.FAILED
    }

    fun acceptedShellStart(code: Int, output: String): Boolean = code == 0 &&
        output.contains("Starting: Intent") &&
        !Regex("error|exception|permission denial|status:\\s*timeout", RegexOption.IGNORE_CASE).containsMatchIn(output)
}
