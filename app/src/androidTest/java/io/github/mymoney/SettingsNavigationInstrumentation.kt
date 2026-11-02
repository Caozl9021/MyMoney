package io.github.mymoney

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import io.github.mymoney.data.update.AppUpdateStore
import io.github.mymoney.ui.AppUpdatePage
import io.github.mymoney.ui.MainActivity
import io.github.mymoney.ui.MyMoneyTheme
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.LiquidTabBar
import io.github.mymoney.ui.design.TabItem
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose fallback clicks and all About states; emulator-only, no network calls. */
class SettingsNavigationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val checks = mutableListOf<String>()
        var activity: MainActivity? = null
        var failure: Throwable? = null
        val field = AppUpdateStore::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val states = field.get(AppUpdateStore) as MutableStateFlow<AppUpdateStore.State>
        val original = states.value
        fun search(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) search(node.getChild(i), text)?.let { return it }
            return null
        }
        fun click(text: String) {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var node: AccessibilityNodeInfo? = null
            while (node == null && android.os.SystemClock.uptimeMillis() < deadline) {
                node = search(uiAutomation.rootInActiveWindow, text)
                if (node == null) Thread.sleep(100)
            }
            check(node != null) { "Missing $text" }
            val target = requireNotNull(node)
            val rect = android.graphics.Rect()
            target.getBoundsInScreen(rect)
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            try {
                check(uiAutomation.injectInputEvent(down, true))
                check(uiAutomation.injectInputEvent(up, true))
            } finally { down.recycle(); up.recycle() }
            Thread.sleep(200)
        }
        try {
            require(android.os.Build.HARDWARE.contains("ranchu")) { "Disposable emulator only" }
            uiAutomation // Connect before creating Compose accessibility delegates.
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val selectedEvents = mutableListOf<Int>()
            runOnMainSync {
                val content = activity!!.findViewById<ComposeView>(R.id.main_content)
                val root = content.parent as ViewGroup
                for (i in 0 until root.childCount) if (root.getChildAt(i) !== content) root.getChildAt(i).visibility = View.GONE
                content.setContent {
                    MyMoneyTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            LiquidTabBar(items=listOf(TabItem("首页",AbIcons.Home),TabItem("统计",AbIcons.Chart),
                                TabItem("资产",AbIcons.Wallet),TabItem("设置",AbIcons.Settings)), selected=3,
                                onSelect={ selectedEvents += it }, modifier=Modifier.padding(bottom=48.dp).height(60.dp))
                        }
                    }
                }
            }
            Thread.sleep(600)
            repeat(5) { click("设置") }
            click("首页")
            runOnMainSync { check(selectedEvents == listOf(3,3,3,3,3,0)) { "$selectedEvents" } }
            checks += "compose-fallback-delivers-five-reselects-and-normal-selection"
            AppUpdateStore.init(targetContext)
            Thread.sleep(300)
            val opened = AtomicInteger()
            runOnMainSync {
                activity!!.findViewById<ComposeView>(R.id.main_content).setContent {
                    MyMoneyTheme { AppUpdatePage(onBack={}, onOpenDevelopment={opened.incrementAndGet()}) }
                }
            }
            for (status in AppUpdateStore.Status.entries) {
                states.value = AppUpdateStore.State(status=status, progress=50)
                Thread.sleep(800)
                val picture = uiAutomation.takeScreenshot()
                File(targetContext.getExternalFilesDir(null), "about-state-$status.png").outputStream().use {
                    picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                val before = opened.get()
                click("开发调试")
                check(opened.get() == before + 1) { "Development unreachable at $status" }
                check(states.value.status == status) { "Navigation changed update state" }
                checks += "about-development-clickable-without-update-side-effect:$status"
            }
        } catch (t: Throwable) { failure = t }
        finally {
            states.value = original
            val result = JSONObject().put("checks",JSONArray(checks)).put("error",failure?.stackTraceToString())
            File(targetContext.getExternalFilesDir(null),"settings-components.json").writeText(result.toString(2))
            activity?.let { runOnMainSync { it.finish() } }
            finish(if(failure == null) -1 else 0, Bundle().apply { putString("result",result.toString()) })
        }
    }
}
