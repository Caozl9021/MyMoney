package io.github.caozl9021.mymoney

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.data.Settings
import io.github.caozl9021.mymoney.ui.notificationAccessIntents
import java.util.UUID

/** Unique temporary preference files only. Does not touch production settings, data or grants. */
class OrdinarySetupInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        super.onStart()
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        val prefix = "ordinary_setup_test_${UUID.randomUUID()}_"
        val names = mutableSetOf<String>()
        val isolated = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += prefix + name
                return targetContext.getSharedPreferences(prefix + name, mode)
            }
        }
        try {
            runOnMainSync {
                val settings = Settings(isolated)
                val prefs = isolated.getSharedPreferences("mymoney", Context.MODE_PRIVATE)
                val snapshots = mutableListOf<Map<String, *>>()
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, _ -> snapshots += shared.all }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                try {
                    val revision = settings.captureRevision
                    settings.prepareOrdinarySetup()
                    check(settings.captureMode == CaptureMode.ACCESSIBILITY && !settings.enabled)
                    check(settings.notificationCaptureEnabled && settings.pageEnabled)
                    check(settings.captureRevision == revision + 1)
                    check(snapshots.isNotEmpty() && snapshots.all {
                        it["capture_mode"] == "ACCESSIBILITY" && it["enabled"] == false
                    })
                    checks += "fresh-ordinary-atomic-paused-snapshot"
                } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }

                val before = prefs.all
                settings.prepareOrdinarySetup()
                check(prefs.all == before)
                checks += "repeat-entry-is-noop"

                settings.notificationCaptureEnabled = false
                settings.pageEnabled = false
                settings.smsCaptureEnabled = true
                settings.autoConfirm = false
                settings.whitelist = setOf("test.local.only")
                val old = prefs.all
                settings.prepareOrdinarySetup()
                check(prefs.all == old)
                checks += "old-disabled-channels-pause-cards-whitelist-preserved"

                settings.useRecommendedOrdinarySources()
                check(settings.notificationCaptureEnabled && settings.pageEnabled && !settings.enabled)
                check(settings.smsCaptureEnabled && !settings.autoConfirm && settings.whitelist == setOf("test.local.only"))
                checks += "explicit-recommended-sources-preserve-pause-and-other-choices"

                settings.captureMode = CaptureMode.ROOT
                val root = prefs.all
                settings.prepareOrdinarySetup()
                settings.useRecommendedOrdinarySources()
                check(prefs.all == root)
                checks += "root-settings-not-rewritten"

                val intents = notificationAccessIntents(isolated)
                check(intents.last().action == SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                if (Build.VERSION.SDK_INT >= 30) {
                    check(intents.first().action == SystemSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    check(intents.first().getStringExtra(SystemSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME) ==
                        "io.github.caozl9021.mymoney/io.github.caozl9021.mymoney.capture.PaymentNotificationListener")
                }
                checks += "public-notification-detail-component-and-list-fallback"
            }
        } catch (t: Throwable) { failure = t }
        finally { names.forEach { targetContext.deleteSharedPreferences(it) } }
        finish(if (failure == null) -1 else 0, Bundle().apply {
            putString("checks", checks.joinToString("\n"))
            putString("status", if (failure == null) "passed" else "failed")
            failure?.let { putString("error", android.util.Log.getStackTraceString(it)) }
        })
    }
}
