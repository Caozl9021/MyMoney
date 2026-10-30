package io.github.caozl9021.mymoney

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import io.github.caozl9021.mymoney.data.AppDb
import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.systems.LauncherIconChoice
import io.github.caozl9021.mymoney.systems.LauncherIcons
import io.github.caozl9021.mymoney.ui.MainActivity
import io.github.caozl9021.mymoney.ui.MyMoneyTheme
import io.github.caozl9021.mymoney.ui.design.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

/** Real Compose frame sampling, restricted to a disposable emulator with synthetic bills. */
class BrandThemeInstrumentation : Instrumentation() {
    private var seedOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); seedOnly = arguments?.getString("mode") == "seed"; start()
    }
    override fun onStart() {
        super.onStart()
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        var activity: MainActivity? = null
        val prefs = Appearance.prefs(targetContext)
        val original = prefs.all.toMap()
        data class Frame(val primary: Int, val onPrimary: Int, val container: Int, val onContainer: Int,
            val secondary: Int, val tertiary: Int, val tint: Int, val handle: Int, val reduce: Boolean)
        val frames = CopyOnWriteArrayList<Frame>()
        val dark = mutableStateOf(false)
        fun snapshot(): List<List<String>> {
            val db = AppDb.get(targetContext).openHelper.readableDatabase
            val tables = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use {
                while (it.moveToNext()) tables += it.getString(0)
            }
            return tables.map { table -> db.query("SELECT * FROM \"$table\"").use { c ->
                buildList { add(table); while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") {
                    if(c.isNull(it)) "<NULL>" else if(c.getType(it) == android.database.Cursor.FIELD_TYPE_BLOB)
                        android.util.Base64.encodeToString(c.getBlob(it), android.util.Base64.NO_WRAP) else c.getString(it)
                }) }.sorted()
            } }
        }
        fun awaitPrimary(value: Int) {
            val end = android.os.SystemClock.uptimeMillis() + 4000
            while (frames.lastOrNull()?.primary != value && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(20)
            check(frames.lastOrNull()?.primary == value) { "Theme did not settle: ${frames.lastOrNull()} expected $value" }
            Thread.sleep(100)
        }
        try {
            require(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.HARDWARE.contains("ranchu")) {
                "Only disposable emulator supported"
            }
            if (seedOnly) {
                runBlocking {
                    val dao = AppDb.get(targetContext).bills()
                    check(dao.allOnce().isEmpty()) { "Seed requires an empty disposable ledger" }
                    val now = System.currentTimeMillis()
                    listOf(12345L to "EXPENSE", 45000L to "INCOME", 1200L to "REFUND").forEachIndexed { i, pair ->
                        dao.insert(BillEntity(priceCents=pair.first, type=pair.second, timeMillis=now-i*1000,
                            merchant="主题验证", method=null, orderTail=null, sourcePkg="qa.theme", strategy="synthetic",
                            fingerprint="brand-theme-fixture-$i", createdAt=now, note="主题验证${i+1}"))
                    }
                }
                checks += "three-synthetic-ledger-rows-for-home-visual-check-only"
            } else {
                Thread.sleep(2000)
                val before = snapshot()
                val capture = targetContext.getSharedPreferences("mymoney", 0).all.toMap()
                LauncherIcons.reconcile(targetContext).getOrThrow()
                check(LauncherIcons.select(targetContext, LauncherIconChoice.BLUE).success)
                // Actual enabled component wins stale cache, including at cold theme initialization.
                prefs.edit().putString("launcher_icon", "green").commit()
                check(LauncherIcons.initialThemeSelection(targetContext) == LauncherIconChoice.BLUE)
                LauncherIcons.reconcile(targetContext).getOrThrow()
                checks += "initial-theme-follows-enabled-alias-before-stale-preference"
                runOnMainSync { prefs.edit().putBoolean("reduce_motion", false).commit() }
                activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                runOnMainSync {
                    val view = ComposeView(activity!!)
                    activity!!.setContentView(view)
                    view.setContent {
                        MyMoneyTheme(darkTheme=dark.value) {
                            val s = MaterialTheme.colorScheme
                            val p = LocalAbPalette.current
                            val h = LocalTextSelectionColors.current.handleColor
                            val reduce = LocalReduceMotion.current
                            SideEffect {
                                check(p.accent == s.primary && p.onAccent == s.onPrimary)
                                frames += Frame(s.primary.toArgb(), s.onPrimary.toArgb(), s.primaryContainer.toArgb(),
                                    s.onPrimaryContainer.toArgb(), s.secondary.toArgb(), s.tertiary.toArgb(),
                                    s.surfaceTint.toArgb(), h.toArgb(), reduce)
                            }
                        }
                    }
                }
                awaitPrimary(BrandColors.roles(LauncherIconChoice.BLUE, false).primary.toArgb())
                check(frames.all { it.primary == BrandColors.roles(LauncherIconChoice.BLUE, false).primary.toArgb() })
                checks += "first-composition-blue-settled-without-green-flash"
                for (night in listOf(false, true)) {
                    runOnMainSync { dark.value = night }
                    awaitPrimary(BrandColors.roles(LauncherIcons.selection.value!!, night).primary.toArgb())
                    for (choice in listOf(LauncherIconChoice.GREEN, LauncherIconChoice.RED, LauncherIconChoice.BLUE)) {
                        val start = frames.size
                        val old = frames.last().primary
                        check(LauncherIcons.select(targetContext, choice).success)
                        val expected = BrandColors.roles(choice, night)
                        awaitPrimary(expected.primary.toArgb())
                        val transition = frames.drop(start)
                        check(transition.any { it.primary != old && it.primary != expected.primary.toArgb() }) {
                            "No animated intermediate frame for $choice/$night"
                        }
                        check(transition.all { it.primary == it.secondary && it.primary == it.tertiary &&
                            it.primary == it.tint && it.primary == it.handle }) { "Unsynchronized roles" }
                        check(transition.all { ColorContrast.ratio(it.primary,it.onPrimary) >= 4.5 &&
                            ColorContrast.ratio(it.container,it.onContainer) >= 4.5 }) { "Intermediate contrast failure" }
                        checks += "actual-compose-${choice.id}-${if(night) "dark" else "light"}-shared-frames-readable"
                    }
                }
                runOnMainSync { prefs.edit().putBoolean("reduce_motion", true).commit() }
                Thread.sleep(200)
                check(frames.last().reduce)
                val old = frames.last().primary
                val start = frames.size
                check(LauncherIcons.select(targetContext, LauncherIconChoice.RED).success)
                val expected = BrandColors.roles(LauncherIconChoice.RED, true).primary.toArgb()
                awaitPrimary(expected)
                check(frames.drop(start).all { it.primary == old || it.primary == expected })
                checks += "reduced-motion-no-intermediate-colors"
                check(snapshot() == before)
                check(targetContext.getSharedPreferences("mymoney", 0).all == capture)
                checks += "all-room-tables-and-capture-preferences-unchanged"
                checks += "sampled-${frames.size}-compose-frames"
            }
        } catch (t: Throwable) { failure = t }
        finally {
            runOnMainSync {
                activity?.finish()
                for(key in listOf("reduce_motion")) {
                    val editor = prefs.edit()
                    if (key in original) editor.putBoolean(key, original[key] as Boolean) else editor.remove(key)
                    editor.commit()
                }
            }
            if(!seedOnly) LauncherIcons.select(targetContext, LauncherIconChoice.GREEN)
        }
        finish(if(failure == null) -1 else 0, Bundle().apply {
            putString("status", if(failure == null) "passed" else "failed")
            putString("checks", checks.joinToString("\n"))
            failure?.let { putString("error", android.util.Log.getStackTraceString(it)) }
        })
    }
}
