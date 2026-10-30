package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import io.github.mymoney.capture.AccessibilityGuardian
import io.github.mymoney.capture.ChannelCapabilities
import io.github.mymoney.capture.ChannelSelection
import io.github.mymoney.data.Backup
import io.github.mymoney.data.BackupStore
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.CaptureModeState
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.NotificationPath
import io.github.mymoney.data.Settings
import java.util.UUID

/**
 * 阶段 5 专项：**旧配置升级与旧备份恢复不会激活用户没给过的通道**。
 *
 * 用的是真实的 `Settings`（真实 SharedPreferences 读写）与真实的 `BackupStore.applySettingsTo`，
 * 每条用例一个随机前缀的独立偏好命名空间，跑完清空；**不碰账本、不调 `CaptureModeManager.reconcile`、
 * 不起任何服务**——要证的是配置层的结论，服务层的启停已由 `ChannelPlanInstrumentation` 覆盖。
 *
 * 能证什么、不能证什么：
 * - 能证：升级搬迁只动通知开关、页面通道的默认值不再被「写过 capture_mode」掰开、
 *   `AccessibilityGuardian.shouldRun` 不再按预设判定、恢复旧备份后四条通道的活性仍全为 false。
 * - 不能证：真实支付下这些配置落到哪条实现。模拟器没有 root，`su` 那一半造不出来。
 */
class ConfigurationMigrationInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val jd = "com.jingdong.app.mall"

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            check(listOf("goldfish", "ranchu").any { Build.HARDWARE.contains(it, true) }) {
                "Refusing configuration verification on a physical device"
            }
            test("legacy_root_upgrade_moves_the_notification_switch_only") { context, prefs ->
                check(prefs.edit()
                    .putString(Settings.KEY_CAPTURE_MODE, CaptureMode.ROOT.name)
                    .putBoolean(Settings.KEY_ROOT_NOTIFICATION_ENABLED, true)
                    .putBoolean(Settings.KEY_ENABLED, true).commit())
                val before = Settings(context)
                // 老 ROOT 用户从没被要求过无障碍授权：页面通道在拆绑后也不能自己变开。
                check(!before.pageEnabled) { "旧 ROOT 配置的页面通道默认应为关" }
                before.migrateLegacyRootNotification()
                check(before.notificationCaptureEnabled) { "旧「root 读通知」应搬进统一通知开关" }
                check(!before.rootNotificationEnabled) { "旧键搬完应复位" }
                check(prefs.getBoolean(Settings.KEY_ROOT_NOTIFICATION_MIGRATED, false)) { "应打上只搬一次的标记" }
                check(!before.hookCaptureEnabled) { "升级不该替用户打开凭证通道" }
                check(before.captureMode == CaptureMode.ROOT) { "预设保留，但只作展示" }
                val state = stateOf(before)
                check(state.notificationActive && !state.pageActive && !state.hookActive)
                // 通知实现与预设无关：老 ROOT 配置下 listener 可用就用 listener。
                val listenerUsable = ChannelCapabilities(true, true, listenerAuthorized = true, listenerBound = true, rootAvailable = false)
                check(ChannelSelection.decide(listenerUsable, 0) == NotificationPath.LISTENER)
                // 没有通知使用权、也没有 root：不冒充任何实现。
                check(ChannelSelection.decide(listenerUsable.copy(listenerAuthorized = false, listenerBound = false), 0) == null)
                results += mapOf("observed" to mapOf(
                    "notifications" to before.notificationCaptureEnabled,
                    "hook" to before.hookCaptureEnabled,
                    "page" to before.pageEnabled,
                    "migrated" to prefs.getBoolean(Settings.KEY_ROOT_NOTIFICATION_MIGRATED, false),
                ))
            }
            test("legacy_accessibility_upgrade_keeps_page_capture_on") { context, prefs ->
                check(prefs.edit()
                    .putString(Settings.KEY_CAPTURE_MODE, CaptureMode.ACCESSIBILITY.name)
                    .putBoolean(Settings.KEY_ENABLED, true).commit())
                val settings = Settings(context)
                check(settings.pageEnabled) { "老无障碍用户的页面辅助默认仍为开" }
                check(stateOf(settings).pageActive)
                check(AccessibilityGuardian.shouldRun(context)) { "页面通道开着就该有守护" }
            }
            test("page_switch_is_honoured_under_the_root_preset") { context, prefs ->
                // 阶段 4 之前守护要求 `captureMode == ACCESSIBILITY`，ROOT 预设下页面通道是死开关。
                check(prefs.edit()
                    .putString(Settings.KEY_CAPTURE_MODE, CaptureMode.ROOT.name)
                    .putBoolean(Settings.KEY_ENABLED, true)
                    .putBoolean(Settings.KEY_PAGE_FALLBACK_ENABLED, true).commit())
                val settings = Settings(context)
                check(settings.pageActive) { "ROOT 预设下页面开关打开就该是活的" }
                check(AccessibilityGuardian.shouldRun(context)) { "守护不该再按预设判定" }
                check(prefs.edit().putBoolean(Settings.KEY_PAGE_FALLBACK_ENABLED, false).commit())
                check(!Settings(context).pageActive)
                check(!AccessibilityGuardian.shouldRun(context)) { "关掉页面开关就该停守护" }
            }
            test("old_backup_restore_cannot_activate_any_channel") { context, prefs ->
                val settings = Settings(context)
                check(!settings.pageEnabled && !settings.notificationCaptureEnabled &&
                    !settings.hookCaptureEnabled && !settings.enabled) { "全新配置应四条通道全关" }
                val before = prefs.all.toMap()
                val scopeChanged = BackupStore.applySettingsTo(settings, Backup.SettingsSnapshot(listOf(wechat, jd), 2000, true))
                check(scopeChanged) { "恢复应把范围收窄到本版支持的应用" }
                check(settings.captureMode == CaptureMode.ROOT) { "rootMode 只作展示记号被接住" }
                check(!settings.pageEnabled) { "恢复写了 capture_mode 也不能把页面通道掰开" }
                check(!settings.notificationCaptureEnabled && !settings.hookCaptureEnabled && !settings.enabled)
                check(settings.whitelist == setOf(wechat)) { "范围外的应用不得因恢复进入采集范围" }
                check(settings.configuredWhitelist == setOf(wechat, jd)) { "旧白名单原值保留" }
                val state = stateOf(settings)
                check(!state.notificationActive && !state.pageActive && !state.hookActive)
                // 总开关没开 → 任何实现都选不出来，服务层不会起东西。
                check(ChannelSelection.decide(ChannelCapabilities(
                    state.enabled, state.notificationsEnabled, true, true, true), 0) == null)
                results += mapOf("observed" to mapOf(
                    "mode" to settings.captureMode?.name, "page" to settings.pageEnabled,
                    "notifications" to settings.notificationCaptureEnabled, "hook" to settings.hookCaptureEnabled,
                    "enabled" to settings.enabled, "whitelist" to settings.whitelist.sorted(),
                    "prefsChangedByRestore" to (prefs.all.toMap() != before),
                ))
            }
            test("restore_keeps_channels_the_user_already_chose") { context, prefs ->
                check(prefs.edit()
                    .putBoolean(Settings.KEY_ENABLED, true)
                    .putBoolean(Settings.KEY_NOTIFICATION_CAPTURE_ENABLED, true)
                    .putBoolean(Settings.KEY_PAGE_FALLBACK_ENABLED, true).commit())
                val settings = Settings(context)
                BackupStore.applySettingsTo(settings, Backup.SettingsSnapshot(listOf(wechat), 1500, true))
                check(settings.enabled && settings.notificationCaptureEnabled && settings.pageEnabled) {
                    "恢复既不该开也不该关用户已经选过的通道"
                }
                check(settings.captureMode == CaptureMode.ROOT) { "本地没有预设时才接住旧备份的 rootMode" }
                // 本地已有预设时，恢复不得改写它。
                check(prefs.edit().clear().commit())
                check(prefs.edit().putString(Settings.KEY_CAPTURE_MODE, CaptureMode.ACCESSIBILITY.name).commit())
                BackupStore.applySettingsTo(Settings(context), Backup.SettingsSnapshot(listOf(wechat), 1500, true))
                check(Settings(context).captureMode == CaptureMode.ACCESSIBILITY)
            }
        } catch (error: Throwable) { failure = error }
        val passed = failure == null && results.none { it["status"] == "failed" }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf(
                "status" to if (passed) "passed" else "failed",
                "tests" to results.count { it.containsKey("name") },
                "storage" to "per-case-private-preference-namespace",
                "callsReconcileOrServices" to false,
                "touchesLedger" to false,
                "rootOnDevice" to false,
                "results" to results,
                "error" to failure?.stackTraceToString(),
            )))
        })
    }

    private fun stateOf(settings: Settings) = CaptureModeState(
        settings.captureMode, settings.enabled, settings.pageEnabled, settings.notificationCaptureEnabled,
        settings.smsCaptureEnabled, settings.hookCaptureEnabled,
    )

    private fun test(name: String, action: (Context, SharedPreferences) -> Unit) {
        val prefix = "self-migrate-${UUID.randomUUID()}-"
        val storage = targetContext.getSharedPreferences(prefix + Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                targetContext.getSharedPreferences(prefix + name, mode)
        }
        try {
            check(storage.all.isEmpty())
            action(isolated, storage)
            results += mapOf("name" to name, "status" to "passed")
        } catch (error: Throwable) {
            results += mapOf("name" to name, "status" to "failed", "error" to error.stackTraceToString())
            throw error
        } finally {
            check(storage.edit().clear().commit()) { "Unable to clear this test's isolated preferences" }
        }
    }
}
