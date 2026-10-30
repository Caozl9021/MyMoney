package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import io.github.mymoney.data.*
import java.util.UUID

/** Real preferences and backup codec, isolated by per-case storage names; no ledger or service access. */
class CaptureScopeInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val jd = "com.jingdong.app.mall"

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var failure: Throwable? = null
        try {
            check(listOf("goldfish", "ranchu").any { Build.HARDWARE.contains(it, true) }) {
                "Refusing scope verification on a physical device"
            }
            test("fresh_defaults_only_two_sources_without_persisting_implicit_choices") { context, prefs ->
                val before = prefs.all.toMap()
                val settings = Settings(context)
                check(settings.whitelist == setOf(wechat, alipay))
                check(settings.configuredWhitelist == setOf(wechat, alipay))
                check(!settings.smsCaptureEnabled && !settings.configuredSmsCaptureEnabled)
                check(settings.toKitConfig().acceptedPackages == setOf(wechat, alipay))
                check(prefs.all == before)
            }
            test("legacy_whitelist_and_sms_choices_are_retained_but_never_activated") { context, prefs ->
                val configured = setOf(wechat, alipay, jd, "com.unionpay", "com.android.phone")
                check(prefs.edit().putStringSet(Settings.KEY_WHITELIST, configured)
                    .putBoolean(Settings.KEY_SMS_CAPTURE_ENABLED, true).putBoolean(Settings.KEY_ENABLED, true)
                    .putString(Settings.KEY_CAPTURE_MODE, CaptureMode.ROOT.name).putString("unrelated", "retained").commit())
                val before = prefs.all.toMap()
                val settings = Settings(context)
                check(settings.whitelist == setOf(wechat, alipay) && settings.configuredWhitelist == configured)
                check(!settings.smsCaptureEnabled && settings.configuredSmsCaptureEnabled)
                check(settings.isSupported(wechat) && !settings.isSupported(jd))
                check(settings.toKitConfig().acceptedPackages == setOf(wechat, alipay))
                check(prefs.all == before)
            }
            test("two_app_ui_toggle_retains_unshown_legacy_choices") { context, prefs ->
                check(prefs.edit().putStringSet(Settings.KEY_WHITELIST, setOf(wechat, alipay, jd)).commit())
                val settings = Settings(context)
                settings.setSupported(wechat, false)
                check(settings.whitelist == setOf(alipay))
                check(settings.configuredWhitelist == setOf(alipay, jd))
                val revision = settings.captureRevision
                settings.setSupported(jd, true)
                check(settings.captureRevision == revision && jd !in settings.whitelist)
                settings.setSupported(wechat, true)
                check(settings.whitelist == setOf(wechat, alipay) && jd in settings.configuredWhitelist)
            }
            test("old_backup_restore_keeps_raw_scope_without_reenabling_other_sources") { context, prefs ->
                check(prefs.edit().putBoolean(Settings.KEY_ENABLED, true)
                    .putString(Settings.KEY_CAPTURE_MODE, CaptureMode.ACCESSIBILITY.name)
                    .putBoolean(Settings.KEY_SMS_CAPTURE_ENABLED, true).commit())
                val settings = Settings(context)
                check(BackupStore.applySettingsTo(settings, Backup.SettingsSnapshot(listOf(wechat, jd), 2000, true)))
                check(settings.configuredWhitelist == setOf(wechat, jd) && settings.whitelist == setOf(wechat))
                check(!settings.isSupported(jd) && !settings.smsCaptureEnabled && settings.configuredSmsCaptureEnabled)
                check(settings.captureMode == CaptureMode.ACCESSIBILITY && settings.minIntervalMs == 2000L)
            }
            test("real_export_settings_path_roundtrips_legacy_whitelist_in_json8") { context, prefs ->
                val configured = setOf(wechat, alipay, jd, "com.unionpay")
                check(prefs.edit().putStringSet(Settings.KEY_WHITELIST, configured)
                    .putBoolean(Settings.KEY_SMS_CAPTURE_ENABLED, true).commit())
                val settings = Settings(context)
                val before = prefs.all.toMap()
                val exported = BackupStore.settingsSnapshot(settings)
                val encoded = Backup.encode(Backup.Snapshot(1_790_000_000_000L, emptyList(), null, exported))
                val decoded = checkNotNull(Backup.decode(encoded).settings)
                check(decoded.whitelist.toSet() == configured)
                check(settings.whitelist == setOf(wechat, alipay) && !settings.smsCaptureEnabled)
                check(!encoded.contains("smsCaptureEnabled")) // JSON8 has never exported this channel switch.
                check(prefs.all == before)
            }
            test("backup_with_only_unsupported_apps_does_not_opt_in_two_defaults") { context, prefs ->
                check(prefs.edit().putBoolean(Settings.KEY_ENABLED, true).commit())
                val settings = Settings(context)
                BackupStore.applySettingsTo(settings, Backup.SettingsSnapshot(listOf(jd), 1500, false))
                check(settings.configuredWhitelist == setOf(jd) && settings.whitelist.isEmpty())
                check(!settings.isSupported(wechat) && !settings.isSupported(jd))
                check(settings.toKitConfig().acceptedPackages.isEmpty())
                check(BackupStore.safeWhitelist(listOf(jd)).isEmpty())
            }
            test("legacy_sms_write_survives_new_settings_instance_without_becoming_active") { context, prefs ->
                val settings = Settings(context)
                settings.smsCaptureEnabled = true
                check(prefs.getBoolean(Settings.KEY_SMS_CAPTURE_ENABLED, false))
                check(!settings.smsCaptureEnabled && settings.configuredSmsCaptureEnabled)
                check(!Settings(context).smsCaptureEnabled)
                settings.smsCaptureEnabled = false
                check(!settings.configuredSmsCaptureEnabled)
            }
        } catch (error: Throwable) { failure = error }
        val passed = failure == null
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf("status" to if (passed) "passed" else "failed",
                "tests" to results.size, "storage" to "per-case-private-preference-namespace",
                "actualSettingsAndBackupSettingsPath" to true, "testCallsGlobalLedgerOrServices" to false,
                "results" to results, "error" to failure?.stackTraceToString())))
        })
    }

    private fun test(name: String, action: (Context, SharedPreferences) -> Unit) {
        val prefix = "self-scope-${UUID.randomUUID()}-"
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
