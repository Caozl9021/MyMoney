package io.github.mymoney

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import io.github.mymoney.ui.MainActivity
import io.github.mymoney.ui.MyMoneyTheme
import io.github.mymoney.ui.design.PageScaffold
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Exercises real header drawing on a disposable emulator; never runs on a daily-use device. */
class HeaderGlowInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val checks = mutableListOf<String>()
        val active = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val reduce = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val bodies = AtomicInteger()
        val directory = File(targetContext.getExternalFilesDir(null), "header-glow").apply { mkdirs() }
        var activity: MainActivity? = null
        var error: Throwable? = null
        fun shot(name: String): Bitmap = uiAutomation.takeScreenshot().also { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun ringDifference(a: Bitmap, b: Bitmap, bounds: Rect): Long {
            val inset = (10 * targetContext.resources.displayMetrics.density).toInt()
            var total = 0L
            for (y in (bounds.top-inset).coerceAtLeast(0) until (bounds.bottom+inset).coerceAtMost(a.height)) {
                for (x in (bounds.left-inset).coerceAtLeast(0) until (bounds.right+inset).coerceAtMost(a.width)) {
                    if (bounds.contains(x, y)) continue
                    val first = a.getPixel(x,y); val second = b.getPixel(x,y)
                    total += kotlin.math.abs(android.graphics.Color.green(first)-android.graphics.Color.green(second))
                    total += kotlin.math.abs(android.graphics.Color.red(first)-android.graphics.Color.red(second))
                }
            }
            return total
        }
        try {
            require(android.os.Build.HARDWARE.contains("ranchu")) { "Disposable emulator only" }
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            runOnMainSync {
                activity!!.findViewById<ComposeView>(io.github.mymoney.R.id.main_content).setContent {
                    MyMoneyTheme(darkTheme = dark.value) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            io.github.mymoney.ui.design.LocalReduceMotion provides reduce.value,
                        ) {
                            PageScaffold(title="账本", listState=rememberLazyListState(), floatingHeader=true,
                                titleActive=active.value, titleAnimationEnabled=visible.value,
                                titleStateDescription=if(active.value) "自动记账运行中" else "自动记账已暂停",
                                onTitleClick={}) { padding ->
                                SideEffect { bodies.incrementAndGet() }
                                Box(Modifier.fillMaxSize().padding(padding)) { Text("绿色柔光验证 · 合成展示") }
                            }
                        }
                    }
                }
            }
            Thread.sleep(1200)
            shot("harness-start")
            val metrics = targetContext.resources.displayMetrics
            val paint = android.graphics.Paint().apply { textSize = 20 * metrics.scaledDensity }
            var topInset = 0
            runOnMainSync {
                topInset = androidx.core.view.ViewCompat.getRootWindowInsets(activity!!.window.decorView)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0
            }
            // Fixture dimensions use the same 42dp capsule and 16dp horizontal text padding.
            // Read native insets; do not rely on UiAutomation's optional virtual-node text index.
            val bounds = Rect((16 * metrics.density).toInt(), topInset + (7 * metrics.density).toInt(),
                (48 * metrics.density + paint.measureText("账本")).toInt(), topInset + (49 * metrics.density).toInt())
            // Start a deterministic cycle after startup/layout have settled.
            runOnMainSync { active.value = false }; Thread.sleep(400)
            runOnMainSync { active.value = true }; Thread.sleep(900)
            val low = shot("light-running-low")
            val compositionCount = bodies.get()
            Thread.sleep(1300)
            val high = shot("light-running-high")
            val delta = ringDifference(low, high, bounds)
            check(delta > 100) { "No visible breathing: $delta; $bounds" }
            Thread.sleep(6600) // Capture at least two full cycles before checking static states.
            check(bodies.get() == compositionCount) { "Breathing recomposed the body" }
            checks += "actual-outside-capsule-pixels-breathe-without-body-recomposition:$delta"
            runOnMainSync { reduce.value = true }; Thread.sleep(500)
            val static = shot("light-reduced-motion")
            Thread.sleep(2300)
            check(ringDifference(static, shot("light-reduced-motion-later"), bounds) == 0L)
            checks += "reduced-motion-static-pixels"
            runOnMainSync { reduce.value = false; visible.value = false }; Thread.sleep(500)
            val hidden = shot("visibility-disabled")
            Thread.sleep(2300)
            check(ringDifference(hidden, shot("visibility-disabled-later"), bounds) == 0L)
            checks += "covered-page-stops-breathing"
            runOnMainSync { visible.value = true; active.value = false }; Thread.sleep(600)
            val paused = shot("light-paused")
            check(ringDifference(static, paused, bounds) > 100)
            Thread.sleep(2200)
            check(ringDifference(paused, shot("light-paused-later"), bounds) == 0L)
            checks += "paused-removes-green-and-stays-static"
            runOnMainSync { dark.value = true; active.value = true; reduce.value = true }; Thread.sleep(700)
            shot("dark-running-static")
            runOnMainSync { active.value = false }; Thread.sleep(500)
            shot("dark-paused")
            checks += "dark-active-and-paused-captured"
        } catch (t: Throwable) { error = t }
        finally {
            val result = JSONObject().put("checks", org.json.JSONArray(checks)).put("error", error?.stackTraceToString())
            File(directory, "results.json").writeText(result.toString(2))
            activity?.let { runOnMainSync { it.finish() } }
            finish(if(error == null) -1 else 0, Bundle().apply { putString("result", result.toString()) })
        }
    }
}
