package com.example.autobook.data

import android.content.Context
import com.example.billkit.BillKitConfig
import com.example.billkit.Packages

/** 只有少量开关，SharedPreferences 足够；不引入 DataStore/Hilt 之类的依赖。 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("autobook", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 是否自动弹出确认页；false 时只在 App 内产生一条"待确认"草稿 */
    var autoConfirm: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONFIRM, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONFIRM, value).apply()

    /** 同一应用两次识别的最小间隔，防止页面滚动时刷屏 */
    var minIntervalMs: Long
        get() = prefs.getLong(KEY_MIN_INTERVAL, 1_500L)
        set(value) = prefs.edit().putLong(KEY_MIN_INTERVAL, value).apply()

    /** OCR 兜底：分类未命中 / 命中但无金额时，截图识别一次（仅 root 路径可用） */
    var ocrFallback: Boolean
        get() = prefs.getBoolean(KEY_OCR_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_OCR_FALLBACK, value).apply()

    var captureMode: CaptureMode?
        get() = CaptureMode.entries.firstOrNull { it.name == prefs.getString(KEY_CAPTURE_MODE, null) }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CAPTURE_MODE) else putString(KEY_CAPTURE_MODE, value.name)
                putBoolean(KEY_ROOT_MODE, value == CaptureMode.ROOT)
            }.apply()
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

    fun selectCaptureMode(mode: CaptureMode) {
        val next = CaptureModePolicy.select(CaptureModeState(captureMode, enabled), mode)
        prefs.edit().putString(KEY_CAPTURE_MODE, mode.name)
            .putBoolean(KEY_ROOT_MODE, mode == CaptureMode.ROOT)
            .putBoolean(KEY_ENABLED, next.enabled).apply()
    }

    var whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, null) ?: Packages.defaultWhitelist
        set(value) = prefs.edit().putStringSet(KEY_WHITELIST, value).apply()

    fun setSupported(pkg: String, supported: Boolean) {
        val next = whitelist.toMutableSet()
        if (supported) next += pkg else next -= pkg
        whitelist = next
    }

    fun isSupported(pkg: String): Boolean = enabled && pkg in whitelist

    /** 把宿主设置映射成库的配置；改完记得调 BillKit.updateConfig */
    fun toKitConfig(): BillKitConfig = BillKitConfig(
        acceptedPackages = whitelist,
        ocrEnabled = ocrFallback && captureMode == CaptureMode.ROOT,
        minIntervalMs = minIntervalMs,
    )

    companion object {
        const val PREFS_NAME = "autobook"
        const val KEY_CAPTURE_MODE = "capture_mode"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_CONFIRM = "auto_confirm"
        const val KEY_MIN_INTERVAL = "min_interval_ms"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_OCR_FALLBACK = "ocr_fallback"
        const val KEY_ROOT_MODE = "root_mode"
    }
}
