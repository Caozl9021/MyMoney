package io.github.mymoney.lsp

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

    /**
     * `pidof lspd` 的输出是不是「守护进程确实在跑」。
     *
     * 只在这一点上认字符串：退出码 0 且输出就是一个或多个数字 pid。别的任何内容
     * （报错、空行、su 自己的提示）都不算证据——凭证入口不该凭一段没看懂的文字出现。
     */
    fun daemonEvidence(code: Int, output: String): Boolean =
        code == 0 && Regex("[0-9]+(?:\\s+[0-9]+)*").matches(output.trim())

    fun acceptedShellStart(code: Int, output: String): Boolean = code == 0 &&
        output.contains("Starting: Intent") &&
        !Regex("error|exception|permission denial|status:\\s*timeout", RegexOption.IGNORE_CASE).containsMatchIn(output)
}
