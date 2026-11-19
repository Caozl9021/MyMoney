package io.github.mymoney.data

import android.content.Context
import android.content.SharedPreferences
import com.example.billkit.BillKitConfig

/** 只有少量开关，SharedPreferences 足够；不引入 DataStore/Hilt 之类的依赖。 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mymoney", Context.MODE_PRIVATE)

    /** Every capture-affecting mutation invalidates work queued under the old configuration. */
    val captureRevision: Long get() = prefs.getLong(KEY_CAPTURE_REVISION, 0L)

    private fun captureEdit(block: SharedPreferences.Editor.() -> Unit) = synchronized(captureLock) {
        prefs.edit().apply(block).putLong(KEY_CAPTURE_REVISION, captureRevision + 1).apply()
    }

    /** 纯界面偏好：改它不影响采集，所以不递增 captureRevision，避免白白作废已排队的工作。 */
    private fun uiEdit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
    }

    /** 唯一采集通道为无障碍页面识别：只要总开关开启，页面采集即生效。 */
    var pageEnabled: Boolean
        get() = true
        set(value) = captureEdit { putBoolean(KEY_PAGE_FALLBACK_ENABLED, value) }

    val pageActive: Boolean get() = enabled

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_ENABLED, value) }

    /** 是否自动弹出确认页；false 时只在 App 内产生一条"待确认"草稿 */
    var autoConfirm: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONFIRM, true)
        set(value) = captureEdit { putBoolean(KEY_AUTO_CONFIRM, value) }

    /** 超级岛确认开关（小米澎湃 OS 焦点通知）。 */
    var islandConfirmEnabled: Boolean
        get() = prefs.getBoolean(KEY_ISLAND_CONFIRM_ENABLED, false)
        set(value) = uiEdit { putBoolean(KEY_ISLAND_CONFIRM_ENABLED, value) }

    /** 同一应用两次识别的最小间隔，防止页面滚动时刷屏 */
    var minIntervalMs: Long
        get() = prefs.getLong(KEY_MIN_INTERVAL, 1_500L)
        set(value) = captureEdit { putLong(KEY_MIN_INTERVAL, value) }

    var captureMode: CaptureMode?
        get() = CaptureMode.entries.firstOrNull { it.name == prefs.getString(KEY_CAPTURE_MODE, null) }
        set(value) {
            captureEdit {
                if (value == null) remove(KEY_CAPTURE_MODE) else putString(KEY_CAPTURE_MODE, value.name)
                putBoolean(KEY_ROOT_MODE, value == CaptureMode.ROOT)
            }
        }

    /** Compatibility for old callers and the backup format. */
    var rootMode: Boolean
        get() = captureMode == CaptureMode.ROOT ||
            (captureMode == null && prefs.getBoolean(KEY_ROOT_MODE, false))
        set(value) { captureMode = if (value) CaptureMode.ROOT else CaptureMode.ACCESSIBILITY }

    fun migrateCaptureMode(accessibilityConfigured: Boolean) {
        if (captureMode != null) return
        val migrated = CaptureModePolicy.migrate(
            null,
            prefs.getBoolean(KEY_ROOT_MODE, false),
            accessibilityConfigured || enabled || prefs.getBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, false),
        )
        if (migrated != null) captureMode = migrated
    }

    /** Publish one paused initial snapshot, never a transient enabled page-capture state. */
    fun prepareOrdinarySetup() {
        val current = CaptureModeState(captureMode, enabled, pageEnabled = true)
        val next = CaptureModePolicy.prepareOrdinary(current)
        if (next == current) return
        captureEdit {
            putString(KEY_CAPTURE_MODE, next.mode?.name)
            putBoolean(KEY_ROOT_MODE, false)
            putBoolean(KEY_ENABLED, next.enabled)
            putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, false)
            putBoolean(KEY_PAGE_FALLBACK_ENABLED, true)
        }
    }

    /** Original choices survive upgrades and backups even when outside this build's scope. */
    val configuredWhitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, null)?.toSet() ?: CaptureScope.packages

    /** Only these packages may reach page collection in this build. */
    var whitelist: Set<String>
        get() = CaptureScope.filterPackages(configuredWhitelist)
        set(value) = captureEdit { putStringSet(KEY_WHITELIST, value.toSet()) }

    fun setSupported(pkg: String, supported: Boolean) {
        if (!CaptureScope.supports(pkg)) return
        val next = configuredWhitelist.toMutableSet()
        if (supported) next += pkg else next -= pkg
        whitelist = next
    }

    fun isSupported(pkg: String): Boolean = enabled && pkg in whitelist

    /** 把宿主设置映射成库的配置；改完记得调 BillKit.updateConfig */
    fun toKitConfig(): BillKitConfig = BillKitConfig(
        acceptedPackages = whitelist,
        minIntervalMs = minIntervalMs,
        confirmOnRevisit = true,
    )

    companion object {
        private val captureLock = Any()
        const val KEY_CAPTURE_REVISION = "capture_revision"
        const val KEY_NOTIFICATION_CAPTURE_ENABLED = "notification_capture_enabled"
        const val KEY_SMS_CAPTURE_ENABLED = "sms_capture_enabled"
        const val KEY_HOOK_CAPTURE_ENABLED = "hook_capture_enabled"
        const val KEY_ROOT_NOTIFICATION_ENABLED = "root_notification_enabled"
        const val KEY_ROOT_NOTIFICATION_MIGRATED = "root_notification_migrated"
        const val KEY_PAGE_FALLBACK_ENABLED = "page_fallback_enabled"
        const val PREFS_NAME = "mymoney"
        const val KEY_CAPTURE_MODE = "capture_mode"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_CONFIRM = "auto_confirm"
        const val KEY_ISLAND_CONFIRM_ENABLED = "island_confirm_enabled"
        const val KEY_MIN_INTERVAL = "min_interval_ms"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_OCR_FALLBACK = "ocr_fallback"
        const val KEY_ROOT_MODE = "root_mode"
        const val KEY_DISMISSED_CAPABILITY_NOTICES = "dismissed_capability_notices"
    }
}
