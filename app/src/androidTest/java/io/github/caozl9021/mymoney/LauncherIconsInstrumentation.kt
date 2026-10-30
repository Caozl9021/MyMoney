package io.github.caozl9021.mymoney

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.caozl9021.mymoney.data.AppDb
import io.github.caozl9021.mymoney.systems.AndroidLauncherIconBackend
import io.github.caozl9021.mymoney.systems.LauncherIconChoice
import io.github.caozl9021.mymoney.systems.LauncherIconController
import io.github.caozl9021.mymoney.systems.LauncherIcons
import io.github.caozl9021.mymoney.ui.design.Appearance

/** Run only on the dedicated disposable icon AVD. Uses real PackageManager aliases. */
class LauncherIconsInstrumentation : Instrumentation() {
    private var mode = "full"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode") ?: "full"; start() }

    override fun onStart() {
        super.onStart()
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        val context = targetContext
        val pm = context.packageManager
        fun launches(): List<String> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0).map { it.activityInfo.name }
        fun assertChoice(choice: LauncherIconChoice) {
            check(launches() == listOf("${context.packageName}.launcher.Logo${choice.ordinal + 1}")) { "Unexpected launchers: ${launches()}" }
            check(LauncherIcons.selection.value == choice)
            check(Appearance.prefs(context).getString("launcher_icon", null) == choice.id)
        }
        fun ledger(): Map<String, List<String>> {
            val db = AppDb.get(context).openHelper.readableDatabase
            val tables = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use {
                while (it.moveToNext()) tables += it.getString(0)
            }
            return tables.associateWith { table ->
                db.query("SELECT * FROM \"${table.replace("\"", "\"\"")}\"").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {
                            if (cursor.isNull(it)) "<NULL>" else if (cursor.getType(it) == android.database.Cursor.FIELD_TYPE_BLOB)
                                android.util.Base64.encodeToString(cursor.getBlob(it), android.util.Base64.NO_WRAP)
                            else cursor.getString(it)
                        })
                    }.sorted()
                }
            }
        }
        try {
            LauncherIcons.reconcile(context).getOrThrow()
            if (mode == "persist") {
                assertChoice(LauncherIconChoice.BLUE)
                checks += "blue-selection-survives-new-process-or-same-signature-upgrade"
            } else {
                // Let normal startup rule imports finish before taking a read-only baseline.
                Thread.sleep(2000)
                val databaseBefore = ledger()
                val captureBefore = context.getSharedPreferences("mymoney", 0).all.toMap()
                assertChoice(LauncherIconChoice.GREEN)
                checks += "old-version-upgrade-default-green-single-launcher"
                for (choice in listOf(LauncherIconChoice.BLUE, LauncherIconChoice.RED, LauncherIconChoice.GREEN)) {
                    val result = LauncherIcons.select(context, choice)
                    check(result.success) { result.error.toString() }
                    assertChoice(choice)
                    val info = pm.getActivityInfo(ComponentName(context.packageName, launches().single()), PackageManager.GET_META_DATA)
                    check(info.targetActivity == "${context.packageName}.ui.MainActivity")
                    check(info.metaData.getInt("android.app.shortcuts") != 0)
                    check(info.icon != 0)
                    checks += "atomic-roundtrip-${choice.id}-one-alias-target-shortcut-metadata"
                }
                check(!LauncherIcons.select(context, LauncherIconChoice.GREEN).changed)
                checks += "repeat-choice-no-change"

                // Exercise the real pre-33 sequence on API35; this is not a low-API device claim.
                val sequential = LauncherIconController(AndroidLauncherIconBackend(context, useBatch = false))
                for (choice in listOf(LauncherIconChoice.BLUE, LauncherIconChoice.RED, LauncherIconChoice.GREEN)) {
                    check(sequential.select(choice, null).success)
                    LauncherIcons.reconcile(context).getOrThrow()
                    assertChoice(choice)
                }
                checks += "sequential-package-manager-path-roundtrip-on-api35"

                pm.setComponentEnabledSetting(ComponentName(context.packageName, "${context.packageName}.launcher.Logo2"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                check(launches().size == 2)
                LauncherIcons.reconcile(context).getOrThrow()
                assertChoice(LauncherIconChoice.GREEN)
                checks += "interrupted-two-alias-state-recovers-to-saved-choice"

                Appearance.prefs(context).edit().putInt("launcher_icon", 99).commit()
                LauncherIcons.reconcile(context).getOrThrow()
                assertChoice(LauncherIconChoice.GREEN)
                checks += "wrong-preference-type-does-not-crash-or-disable-launcher"

                val main = pm.getActivityInfo(ComponentName(context.packageName, "${context.packageName}.ui.MainActivity"), 0)
                check(main.enabled && main.exported)
                check(pm.resolveActivity(Intent(context, io.github.caozl9021.mymoney.ui.MainActivity::class.java)
                    .setAction("io.github.caozl9021.mymoney.action.ADD_BILL"), 0) != null)
                checks += "actual-main-activity-and-explicit-add-entry-remain-enabled"

                check(ledger() == databaseBefore) { "Database changed during icon operations" }
                check(context.getSharedPreferences("mymoney", 0).all == captureBefore) { "Capture preferences changed" }
                checks += "all-room-tables-and-capture-preferences-unchanged"

                check(LauncherIcons.select(context, LauncherIconChoice.BLUE).success)
                assertChoice(LauncherIconChoice.BLUE)
                checks += "leave-blue-for-process-and-upgrade-persistence-check"
            }
        } catch (t: Throwable) { failure = t }
        finish(if (failure == null) -1 else 0, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putString("checks", checks.joinToString("\n"))
            failure?.let { putString("error", android.util.Log.getStackTraceString(it)) }
        })
    }
}
