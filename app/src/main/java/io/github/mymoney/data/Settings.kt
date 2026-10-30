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

    var notificationCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, value) }

    /**
     * 页面通道开关。它只有无障碍一种实现，所以开不开完全由它自己说了算——
     * 不再要求「使用方式是无障碍」（那个互斥在阶段 4 拆掉，root 用户也能用页面辅助）。
     *
     * 键缺失时的默认值只看「当初是不是无障碍预设」（[CaptureModePolicy.defaultPageEnabled]）。
     * 曾经这里是 `captureMode != null || rootMode`，条件长在另一个键上，于是**备份恢复写进一个
     * `capture_mode=ROOT` 就能把页面通道从关掰成开**；恢复不该激活用户没开过的通道。
     */
    var pageEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAGE_FALLBACK_ENABLED, CaptureModePolicy.defaultPageEnabled(captureMode))
        set(value) = captureEdit { putBoolean(KEY_PAGE_FALLBACK_ENABLED, value) }

    /**
     * 页面通道此刻该不该干活：总开关 + 它自己的开关，与 `CaptureModeState.pageActive` 同一判据。
     *
     * 无障碍服务与守护进程曾经各自再判一次 `captureMode == ACCESSIBILITY`，阶段 4 拆掉预设绑定后
     * 那几个判定成了漏网的旧绑定——ROOT 预设下界面和状态都说页面通道在工作，实现层却一律不采。
     */
    val pageActive: Boolean get() = enabled && pageEnabled

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = captureEdit { putBoolean(KEY_ENABLED, value) }

    /** Legacy choice stays portable; the self build never activates SMS collection. */
    val configuredSmsCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_CAPTURE_ENABLED, false)

    var smsCaptureEnabled: Boolean
        get() = CaptureScope.smsSupported && configuredSmsCaptureEnabled
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
        val migrated = prefs.getBoolean(KEY_ROOT_NOTIFICATION_MIGRATED, false)
        if (migrated) return
        val legacy = prefs.getBoolean(KEY_ROOT_NOTIFICATION_ENABLED, false)
        val mode = captureMode
        if (CaptureModePolicy.legacyRootNotificationMoves(legacy, mode, notificationCaptureEnabled, false)) {
            notificationCaptureEnabled = true
        }
        if (legacy || mode == CaptureMode.ROOT) {
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
        captureEdit {
            putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, true)
            putBoolean(KEY_PAGE_FALLBACK_ENABLED, true)
        }
    }

    /**
     * 用户已经点过「知道了」的能力提示（root / LSPosed）。
     *
     * 存 key 的集合而不是一个布尔：以后多一类提示不用再加一个偏好，删掉某一类也不用迁移。
     * 这是纯界面状态，写进去不该让已排队的识别工作作废，所以走 [uiEdit]。
     */
    var dismissedCapabilityNotices: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED_CAPABILITY_NOTICES, null)?.toSet() ?: emptySet()
        set(value) = uiEdit { putStringSet(KEY_DISMISSED_CAPABILITY_NOTICES, value.toSet()) }

    /**
     * 应用一份推荐配置：只写通道开关，不建立任何互斥关系。
     *
     * 与 [selectCaptureMode] 的区别在于它**同时**决定各通道开不开——那是预设的本意。
     * 写入的 `captureMode` 只用于界面高亮与阶段 5 的迁移/备份兼容，不参与任何采集判定。
     */
    fun applyPreset(preset: CaptureMode, hookWanted: Boolean) {
        captureEdit {
            putString(KEY_CAPTURE_MODE, preset.name)
            putBoolean(KEY_ROOT_MODE, preset == CaptureMode.ROOT)
            putBoolean(KEY_ENABLED, true)
            putBoolean(KEY_NOTIFICATION_CAPTURE_ENABLED, true)
            // 普通预设把页面辅助一起打开；Root 预设留空，让用户在通道行里自己决定要不要。
            putBoolean(KEY_PAGE_FALLBACK_ENABLED, preset == CaptureMode.ACCESSIBILITY)
            putBoolean(KEY_HOOK_CAPTURE_ENABLED, hookWanted)
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

    /** Original choices survive upgrades and backups even when outside this build's scope. */
    val configuredWhitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, null)?.toSet() ?: CaptureScope.packages

    /** Only these packages may reach page, notification or Hook collection in this build. */
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
        const val PREFS_NAME = "mymoney"
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
        const val KEY_DISMISSED_CAPABILITY_NOTICES = "dismissed_capability_notices"
    }
}
