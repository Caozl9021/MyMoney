package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Navigates only this app on an explicitly selected test emulator; no ledger writes. */
class BackGestureInstrumentation : Instrumentation() {
    private var verifyPreference: String? = null
    private var verifyDefault = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); verifyPreference = arguments?.getString("verifyPreference"); verifyDefault = arguments?.getString("verifyDefault") == "true"; start() }
    override fun onStart() {
        var failure: Throwable? = null
        val results = mutableListOf<String>()
        val prefs = io.github.mymoney.ui.design.Appearance.prefs(targetContext)
        val preferenceKey = io.github.mymoney.ui.design.Appearance.PREDICTIVE_BACK
        if (verifyDefault) {
            prefs.edit().remove(preferenceKey).commit()
            val absent = prefs.getBoolean(preferenceKey, io.github.mymoney.ui.design.Appearance.DEFAULT_PREDICTIVE_BACK)
            prefs.edit().putBoolean(preferenceKey, true).commit()
            val explicitOn = prefs.getBoolean(preferenceKey, io.github.mymoney.ui.design.Appearance.DEFAULT_PREDICTIVE_BACK)
            prefs.edit().putBoolean(preferenceKey, false).commit()
            val explicitOff = prefs.getBoolean(preferenceKey, io.github.mymoney.ui.design.Appearance.DEFAULT_PREDICTIVE_BACK)
            val pass = !absent && explicitOn && !explicitOff
            finish(if (pass) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("results", "default_off_and_explicit_choices: absent=$absent explicitOn=$explicitOn explicitOff=$explicitOff")
                putString("error", if (pass) null else "predictive default/choice mismatch")
            })
            return
        }
        if (verifyPreference != null) {
            val expected = verifyPreference.toBoolean()
            val actual = prefs.getBoolean(preferenceKey, io.github.mymoney.ui.design.Appearance.DEFAULT_PREDICTIVE_BACK)
            finish(if (actual == expected) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("results", "predictive_preference_new_process: expected=$expected actual=$actual")
                putString("error", if (actual == expected) null else "preference did not persist")
            })
            return
        }
        prefs.edit().putBoolean(preferenceKey, true).putBoolean("reduce_motion", false).commit()
        fun descend(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) descend(node.getChild(i), text)?.let { return it }
            return null
        }
        fun find(text: String): AccessibilityNodeInfo? = descend(uiAutomation.rootInActiveWindow, text)
        fun await(text: String) {
            val deadline = SystemClock.uptimeMillis() + 30000
            while (find(text) == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            check(find(text) != null) { "UI missing: $text" }
        }
        fun click(text: String, settle: Long = 400) {
            await(text)
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $text" }
            Thread.sleep(settle)
        }
        fun screenshot(name: String) {
            val file = File(targetContext.getExternalFilesDir(null), name)
            uiAutomation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        }
        fun gesture(cancel: Boolean, prefix: String = "") {
            val screen = uiAutomation.takeScreenshot() ?: error("No screenshot")
            val width = screen.width; val y = screen.height * .5f; screen.recycle()
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float) {
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                e.source = InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(e, true))
                e.recycle()
            }
            event(MotionEvent.ACTION_DOWN, 1f)
            for (i in 1..24) {
                event(MotionEvent.ACTION_MOVE, 1f + width * .70f * i / 24); Thread.sleep(20)
                if (i == 16) screenshot(prefix + if (cancel) "back-cancel-drag.png" else "back-commit-drag.png")
            }
            event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, width * .70f)
            Thread.sleep(500)
        }
        try {
            targetContext.startActivity(Intent().setClassName(targetContext.packageName, "io.github.mymoney.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("设置"); click("设置"); await("记账周期"); click("分类"); await("支出分类")
            screenshot("back-before.png")
            gesture(true); await("支出分类"); screenshot("back-cancel.png")
            results += "predictive_cancel_keeps_category_page: passed"
            gesture(false); await("记账周期"); screenshot("back-commit.png")
            results += "predictive_commit_returns_settings: passed"
            click("备注"); click("备注模板"); await("实时预览 · 示例")
            click("{日期}")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("放弃本次修改？"); click("继续编辑")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("放弃本次修改？"); click("放弃修改"); await("备注设置")
            click("备注模板"); await("实时预览 · 示例")
            check(find("{日期}{商户}") == null) { "Discarded template returned from saved page state" }
            screenshot("template-reopen.png")
            results += "template_open_insert_continue_discard_reopen: passed"
            check(find("预设") == null) { "Template presets still visible" }
            await("保存模板")
            results += "template_presets_removed_and_save_visible: passed"
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); await("备注设置")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); await("记账周期")
            click("分类", 0); click("收入分类", 0)
            val nestedDeadline = SystemClock.uptimeMillis() + 5000
            while (find("支出分类") != null && SystemClock.uptimeMillis() < nestedDeadline) Thread.sleep(50)
            check(find("支出分类") == null) { "Rapid nested push did not finish" }
            Thread.sleep(250)
            gesture(false, "nested-"); await("支出分类")
            gesture(false, "nested-root-"); await("记账周期")
            results += "rapid_nested_navigation_returns_each_parent: passed"
            click("分类", 0); await("支出分类")
            gesture(false, "fast-enter-"); await("记账周期")
            results += "fast_return_after_enter_keeps_single_parent: passed"

            fun checkable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isCheckable) return node
                for (i in 0 until node.childCount) checkable(node.getChild(i))?.let { return it }
                return null
            }
            fun toggle(expected: Boolean) {
                await("预测性返回")
                val label = find("预测性返回") ?: error("Predictive label missing")
                val labelBounds = android.graphics.Rect().also(label::getBoundsInScreen)
                val switches = mutableListOf<AccessibilityNodeInfo>()
                fun collect(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.isCheckable) switches += node
                    for (i in 0 until node.childCount) collect(node.getChild(i))
                }
                collect(uiAutomation.rootInActiveWindow)
                val target = switches.minByOrNull { node ->
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    kotlin.math.abs(bounds.centerY() - labelBounds.centerY())
                }
                check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot toggle predictive back" }
                val deadline = SystemClock.uptimeMillis() + 3000
                while (prefs.getBoolean(preferenceKey, true) != expected && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                check(prefs.getBoolean(preferenceKey, true) == expected)
                Thread.sleep(250)
            }
            click("外观"); toggle(false); screenshot("predictive-switch-off.png")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); await("记账周期")
            click("分类"); gesture(true, "disabled-"); await("支出分类")
            gesture(false, "disabled-"); await("记账周期")
            results += "switch_off_immediate_plain_back_and_cancel: passed"
            click("外观"); toggle(true)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); await("记账周期")
            click("分类"); gesture(false, "reenabled-"); await("记账周期")
            results += "switch_on_immediate_preview_and_commit: passed"
            click("外观"); toggle(false)
            screenshot("predictive-final-off.png")
            // A separate instrumentation process verifies the saved value after this process exits.

        } catch (t: Throwable) { screenshot("regression-failure.png"); failure = t }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("results", results.joinToString("\n")); putString("error", failure?.stackTraceToString())
        })
    }
}
