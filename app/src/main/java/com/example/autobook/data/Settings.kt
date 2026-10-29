package com.example.autobook.data

import android.content.Context
import android.content.SharedPreferences
import com.example.billkit.BillKitConfig
import com.example.billkit.Packages

/** 只有少量开关，SharedPreferences 足够；不引入 DataStore/Hilt 之类的依赖。 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("autobook", Context.MODE_PRIVATE)

    /** Every capture-affecting mutation invalidates work queued under the old configuration. */
    val captureRevision: Long get() = prefs.getLong(KEY_CAPTURE_REVISION, 0L)

    private fun captureEdit(block: SharedPreferences.Editor.() -> Unit) = synchronized(captureLock) {
        prefs.edit().apply(block).putLong(KEY_CAPTURE_REVISION, captureRevision + 1).apply()
    }

    var notificationCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, value) }

    /**
     * 页面通道开关。root 模式下页面通道已经删除（uiautomator 轮询删除），
     * 所以这个开关只在无障碍模式下有实际作用；旧装有值就沿用，全新安装按是否选过方式推断。
     */
    var pageEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAGE_FALLBACK_ENABLED, captureMode != null || rootMode)
        set(value) = captureEdit { putBoolean(KEY_PAGE_FALLBACK_ENABLED, value) }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_ENABLED, value) }

    /**
     * 短信通道（SPEC §8 阶段 2）。
     *
     * 默认关闭：`RECEIVE_SMS` 是敏感权限，读取全部短信，必须由用户显式打开并单独授权。
     * 与通知通道互相独立——两者都是免 root 的通道，可以只开其一。
     */
    var smsCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_CAPTURE_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_SMS_CAPTURE_ENABLED, value) }

    /**
     * LSPosed 采集通道（SPEC §8 阶段 3/4：微信 WCDB / 支付宝长连接）。
     *
     * 默认关闭：它要求用户在 LSPosed 里启用模块并把微信/支付宝加入作用域，
     * 属于「明确的额外授权」，不能跟通知/短信这种一键授权混在一起默认打开。
     */
    var hookCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOOK_CAPTURE_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_HOOK_CAPTURE_ENABLED, value) }

    /**
     * 历史遗留：过去 root 读通知与 [notificationCaptureEnabled] 是两个并行开关，
     * 同一条微信付款可能被两条路各读一遍、再靠跨来源去重兜住。
     *
     * 现在通知只有**一个**开关（[notificationCaptureEnabled]），root 模式只是换了个实现
     * （`dumpsys` 而不是 `NotificationListenerService`）。这个属性保留下来只为两件事：
     * 1. 旧备份恢复时不丢用户当初的选择；
     * 2. 升级后第一次打开 App 时，把老用户显式打开过的值搬进新开关（见 [migrateLegacyRootNotification]）。
     */
    var rootNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOT_NOTIFICATION_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_ROOT_NOTIFICATION_ENABLED, value) }

    /**
     * 一次性把旧的「root 读通知」开关搬进统一的通知开关。
     *
     * 只在 `capture_mode == ROOT` 且通知开关还没被用户碰过时才搬：
     * 搬迁过一次就把旧键置回 false 并打标记，避免以后重复搬。
     */
    fun migrateLegacyRootNotification() {
        if (prefs.getBoolean(KEY_ROOT_NOTIFICATION_MIGRATED, false)) return
        val legacy = prefs.getBoolean(KEY_ROOT_NOTIFICATION_ENABLED, false)
        if (legacy && captureMode == CaptureMode.ROOT && !notificationCaptureEnabled) {
            notificationCaptureEnabled = true
        }
        if (legacy || captureMode == CaptureMode.ROOT) {
            prefs.edit().putBoolean(KEY_ROOT_NOTIFICATION_MIGRATED, true).apply()
        }
        if (legacy) rootNotificationEnabled = false
    }

    /** 是否自动弹出确认页；false 时只在 App 内产生一条"待确认"草稿 */
    var autoConfirm: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONFIRM, true)
        set(value) = captureEdit { putBoolean(KEY_AUTO_CONFIRM, value) }

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

    /** Compatibility for old callers and the backup format; UI uses captureMode. */
    var rootMode: Boolean
        get() = captureMode == CaptureMode.ROOT ||
            (captureMode == null && prefs.getBoolean(KEY_ROOT_MODE, false))
        set(value) { captureMode = if (value) CaptureMode.ROOT else CaptureMode.ACCESSIBILITY }

    fun migrateCaptureMode(accessibilityConfigured: Boolean) {
        if (captureMode != null) return
        val migrated = CaptureModePolicy.migrate(
            null, prefs.getBoolean(KEY_ROOT_MODE, false), accessibilityConfigured,
        )
        if (migrated != null) captureMode = migrated
    }

    /** First-run skip must publish one complete configuration, without briefly starting page capture. */
    fun startWithNotifications() {
        captureEdit {
            putString(KEY_CAPTURE_MODE, CaptureMode.ACCESSIBILITY.name)
            putBoolean(KEY_ROOT_MODE, false)
            putBoolean(KEY_PAGE_FALLBACK_ENABLED, false)
            putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, true)
            putBoolean(KEY_ENABLED, true)
        }
    }

    /** Publish one paused initial snapshot, never a transient enabled page-capture state. */
    fun prepareOrdinarySetup() {
        val current = CaptureModeState(captureMode, enabled, pageEnabled, notificationCaptureEnabled,
            smsCaptureEnabled, hookCaptureEnabled)
        val next = CaptureModePolicy.prepareOrdinary(current)
        if (next == current) return
        captureEdit {
            putString(KEY_CAPTURE_MODE, next.mode?.name)
            putBoolean(KEY_ROOT_MODE, false)
            putBoolean(KEY_ENABLED, next.enabled)
            putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, next.notificationsEnabled)
            putBoolean(KEY_PAGE_FALLBACK_ENABLED, next.pageEnabled)
        }
    }

    fun useRecommendedOrdinarySources() {
        if (captureMode != CaptureMode.ACCESSIBILITY) return
        captureEdit {
            putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, true)
            putBoolean(KEY_PAGE_FALLBACK_ENABLED, true)
        }
    }

    fun selectCaptureMode(mode: CaptureMode) {
        val next = CaptureModePolicy.select(CaptureModeState(captureMode, enabled, pageEnabled, notificationCaptureEnabled, smsCaptureEnabled, hookCaptureEnabled), mode)
        captureEdit {
            putString(KEY_CAPTURE_MODE, mode.name)
            putBoolean(KEY_ROOT_MODE, mode == CaptureMode.ROOT)
            putBoolean(KEY_PAGE_FALLBACK_ENABLED, next.pageEnabled)
            putBoolean(KEY_ENABLED, next.enabled)
            putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, next.notificationsEnabled)
        }
    }

    var whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, null) ?: Packages.defaultWhitelist
        set(value) = captureEdit { putStringSet(KEY_WHITELIST, value.toSet()) }

    fun setSupported(pkg: String, supported: Boolean) {
        val next = whitelist.toMutableSet()
        if (supported) next += pkg else next -= pkg
        whitelist = next
    }

    fun isSupported(pkg: String): Boolean = enabled && pkg in whitelist

    /** 把宿主设置映射成库的配置；改完记得调 BillKit.updateConfig */
    fun toKitConfig(): BillKitConfig = BillKitConfig(
        acceptedPackages = whitelist,
        minIntervalMs = minIntervalMs,
    )

    companion object {
        private val captureLock = Any()
        const val KEY_CAPTURE_REVISION = "capture_revision"
        const val KEY_NOTIFICATION_CAPTURE_ENABLED = "notification_capture_enabled"
        const val KEY_SMS_CAPTURE_ENABLED = "sms_capture_enabled"
        const val KEY_HOOK_CAPTURE_ENABLED = "hook_capture_enabled"
        const val KEY_ROOT_NOTIFICATION_ENABLED = "root_notification_enabled"
        const val KEY_ROOT_NOTIFICATION_MIGRATED = "root_notification_migrated"
        /**
         * 键名沿用历史的 `page_fallback_enabled`：改名会让老用户的开关状态丢失，
         * 而这个键值本身（true/false）语义没变，只是属性名从 pageFallbackEnabled 改成了 pageEnabled。
         */
        const val KEY_PAGE_FALLBACK_ENABLED = "page_fallback_enabled"
        const val PREFS_NAME = "autobook"
        const val KEY_CAPTURE_MODE = "capture_mode"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_CONFIRM = "auto_confirm"
        const val KEY_MIN_INTERVAL = "min_interval_ms"
        const val KEY_WHITELIST = "whitelist"
        /**
         * 已废弃：OCR 兜底随 Root 页面轮询一起删除。
         * 常量保留只为兼容旧备份文件（JSON 里还有 `ocrFallback` 字段，读的时候要认）。
         */
        const val KEY_OCR_FALLBACK = "ocr_fallback"
        const val KEY_ROOT_MODE = "root_mode"
    }
}
