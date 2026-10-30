package io.github.mymoney.systems

/** Stable IDs are independent of artwork filenames and must survive upgrades. */
enum class LauncherIconChoice(val id: String, val title: String) {
    GREEN("logo1", "青绿"), BLUE("logo2", "蓝白"), RED("logo3", "红金");

    companion object {
        fun fromId(id: String?): LauncherIconChoice? = entries.firstOrNull { it.id == id }
    }
}

/** Only launcher aliases may be changed by this backend, never the actual Activity. */
interface LauncherIconBackend {
    fun enabled(): Set<LauncherIconChoice>
    fun apply(choice: LauncherIconChoice)
}

data class LauncherIconSwitchResult(
    val selection: LauncherIconChoice?,
    val success: Boolean,
    val changed: Boolean = false,
    val error: Throwable? = null,
)

/** System component state is authoritative; the preference assists abnormal-state recovery. */
class LauncherIconController(private val backend: LauncherIconBackend) {
    fun reconcile(savedId: String?): LauncherIconChoice {
        val enabled = backend.enabled()
        val target = enabled.singleOrNull() ?: LauncherIconChoice.fromId(savedId) ?: LauncherIconChoice.GREEN
        if (enabled != setOf(target)) backend.apply(target)
        check(backend.enabled() == setOf(target)) { "Launcher aliases did not converge" }
        return target
    }

    fun select(choice: LauncherIconChoice, savedId: String?): LauncherIconSwitchResult {
        val previous = try { reconcile(savedId) } catch (e: Exception) {
            return LauncherIconSwitchResult(actual(), false, error = e)
        }
        if (choice == previous) return LauncherIconSwitchResult(previous, true)
        return try {
            backend.apply(choice)
            check(backend.enabled() == setOf(choice)) { "Launcher change not applied" }
            LauncherIconSwitchResult(choice, true, changed = true)
        } catch (e: Exception) {
            // The adapter enables the new alias before disabling any old one on pre-33 systems.
            // Restore a known usable choice; failed rollback must never be reported as success.
            runCatching { backend.apply(previous) }.onFailure { e.addSuppressed(it) }
            LauncherIconSwitchResult(actual(), false, error = e)
        }
    }

    private fun actual(): LauncherIconChoice? = runCatching { backend.enabled().singleOrNull() }.getOrNull()
}
