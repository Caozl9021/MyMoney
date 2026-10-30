package com.example.mymoney.systems

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.mymoney.ui.design.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All component writes are restricted to our three declared launcher aliases. */
internal class AndroidLauncherIconBackend(
    private val context: Context,
    private val useBatch: Boolean = Build.VERSION.SDK_INT >= 33,
) : LauncherIconBackend {
    private val manager get() = context.packageManager
    private fun component(choice: LauncherIconChoice) = ComponentName(context.packageName,
        "${context.packageName}.launcher.Logo${choice.ordinal + 1}")

    override fun enabled(): Set<LauncherIconChoice> = LauncherIconChoice.entries.filterTo(mutableSetOf()) {
        when (manager.getComponentEnabledSetting(component(it))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> it == LauncherIconChoice.GREEN
            else -> false
        }
    }

    override fun apply(choice: LauncherIconChoice) {
        val flags = PackageManager.DONT_KILL_APP
        if (useBatch && Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(LauncherIconChoice.entries.map {
                PackageManager.ComponentEnabledSetting(component(it),
                    if (it == choice) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
            })
        } else {
            // A partial failure can leave two choices, but never intentionally removes the last one.
            manager.setComponentEnabledSetting(component(choice), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, flags)
            check(choice in enabled()) { "New launcher alias was not enabled" }
            LauncherIconChoice.entries.filter { it != choice }.forEach {
                manager.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
            }
        }
    }
}

object LauncherIcons {
    private const val KEY = "launcher_icon"
    private val mutableSelection = MutableStateFlow<LauncherIconChoice?>(null)
    val selection = mutableSelection.asStateFlow()

    /** Read-only startup fallback: actual aliases precede a safe, previously confirmed cache. */
    fun initialThemeSelection(context: Context): LauncherIconChoice =
        runCatching { AndroidLauncherIconBackend(context).enabled().singleOrNull() }.getOrNull()
            ?: LauncherIconChoice.fromId(runCatching { Appearance.prefs(context).all[KEY] as? String }.getOrNull())
            ?: LauncherIconChoice.GREEN

    /** Invoke on a worker thread. A valid system choice wins over a stale preference. */
    @Synchronized
    fun reconcile(context: Context): Result<LauncherIconChoice> {
        val backend = AndroidLauncherIconBackend(context)
        return runCatching {
            LauncherIconController(backend).reconcile(Appearance.prefs(context).all[KEY] as? String)
        }.onSuccess {
            remember(context, it)
            mutableSelection.value = it
        }.onFailure {
            mutableSelection.value = runCatching { backend.enabled().singleOrNull() }.getOrNull()
            android.util.Log.e("AutoBookIcons", "桌面图标状态核对失败", it)
        }
    }

    @Synchronized
    fun select(context: Context, choice: LauncherIconChoice): LauncherIconSwitchResult {
        val result = LauncherIconController(AndroidLauncherIconBackend(context))
            .select(choice, Appearance.prefs(context).all[KEY] as? String)
        mutableSelection.value = result.selection
        if (result.success) result.selection?.let { remember(context, it) }
        else android.util.Log.e("AutoBookIcons", "桌面图标切换失败", result.error)
        return result
    }

    private fun remember(context: Context, choice: LauncherIconChoice) {
        runCatching {
            val prefs = Appearance.prefs(context)
            if (prefs.all[KEY] != choice.id && !prefs.edit().putString(KEY, choice.id).commit()) {
                android.util.Log.w("AutoBookIcons", "图标偏好缓存写入失败，保留系统实际选择")
            }
        }.onFailure { android.util.Log.w("AutoBookIcons", "保留系统实际图标，偏好缓存不可用", it) }
    }
}
