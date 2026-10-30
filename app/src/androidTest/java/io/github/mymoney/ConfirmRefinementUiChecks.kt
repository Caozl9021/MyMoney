package io.github.mymoney

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.mymoney.data.*
import io.github.mymoney.ui.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Only run on the explicitly selected disposable emulator. Synthetic rows are tagged; host restores its app archive. */
internal class ConfirmRefinementUiChecks(private val instrumentation: Instrumentation) {
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val results = mutableListOf<String>()
    private val db get() = AppDb.get(context)
    private fun root(): AccessibilityNodeInfo? { if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache(); return automation.rootInActiveWindow }
    private fun nodes(node: AccessibilityNodeInfo? = root()): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun await(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) { find(text)?.let { return it }; Thread.sleep(100) }
        error("Missing $text: ${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $text" }
        Thread.sleep(350)
    }
    private fun set(text: String, value: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null && !node.isEditable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }) == true) { "Cannot set $text" }
        val end = SystemClock.uptimeMillis() + 5000
        while (nodes().none { it.isEditable && it.text?.toString() == value } && SystemClock.uptimeMillis() < end) Thread.sleep(100)
        check(nodes().any { it.isEditable && it.text?.toString() == value }) { "Input did not update: $text" }
        Thread.sleep(250)
    }
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), "refine-$name.png")
        File(context.getExternalFilesDir(null), "refine-$name.txt").writeText(nodes().joinToString("\n") { "${it.text} | ${it.contentDescription}" })
        automation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    private fun rect(text: String) = Rect().also { await(text).getBoundsInScreen(it) }
    private fun slide(primary: String, candidate: String?, outside: Boolean = false, cancel: Boolean = false, reenter: Boolean = false) {
        val anchor = rect(primary)
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(e, true)); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, anchor.exactCenterX(), anchor.exactCenterY()); Thread.sleep(650)
        await("二级分类：午餐")
        check(nodes().none { it.text?.toString()?.contains("滑动选择") == true })
        capture("bubble")
        val target = candidate?.let { rect("二级分类：$it") }
        val x = target?.exactCenterX() ?: anchor.exactCenterX()
        val y = target?.exactCenterY() ?: anchor.exactCenterY()
        if (target != null) { event(MotionEvent.ACTION_MOVE, x, y); Thread.sleep(100) }
        if (outside || reenter) { event(MotionEvent.ACTION_MOVE, 10f, y + 120f); Thread.sleep(80) }
        if (reenter) { event(MotionEvent.ACTION_MOVE, x, y); Thread.sleep(80) }
        event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, if (outside && !reenter) 10f else x, if (outside && !reenter) y + 120f else y)
        val deadline = SystemClock.uptimeMillis() + 5000
        while (find("二级分类：午餐") != null && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        check(find("二级分类：午餐") == null) { "Picker did not close" }
        Thread.sleep(300)
    }
    private fun launchMain() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("首页")
    }
    private fun chooseTime(cancel: Boolean) {
        val original = nodes().mapNotNull { it.text?.toString() }.firstOrNull { it.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) }
        click("选择记账日期和时间"); await("日期与时间")
        set("小时", "07"); set("分钟", "08")
        click("上个月")
        val first = nodes().firstOrNull { it.contentDescription?.toString()?.matches(Regex("\\d+年\\d+月1日")) == true } ?: error("No date")
        click(first.contentDescription.toString())
        capture(if (cancel) "date-cancel" else "date-select")
        click(if (cancel) "取消" else "确定")
        if (cancel && original != null) check(nodes().any { it.text?.toString() == original }) { "Cancel changed ledger time" }
    }
    fun bubbleStyle(): List<String> {
        LedgerConfig.init(context)
        val original = LedgerConfig.subsOf(BillType.EXPENSE, "餐饮").toList()
        val prefs = io.github.mymoney.ui.design.Appearance.prefs(context)
        prefs.edit().putString("theme", "LIGHT").putString("text_size", "STANDARD").putBoolean("reduce_motion", false).commit()
        fun subs(names: List<String>) {
            LedgerConfig.subsOf(BillType.EXPENSE, "餐饮").toList().forEach { LedgerConfig.removeSubCategory(BillType.EXPENSE, "餐饮", it) }
            names.forEach { LedgerConfig.addSubCategory(BillType.EXPENSE, "餐饮", it) }
        }
        fun openCard() {
            launchMain(); click("记一笔"); await("备注（可选）")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); Thread.sleep(200)
        }
        fun pane(): Rect {
            val end = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < end) {
                nodes().firstOrNull { it.paneTitle?.toString() == "二级分类" }?.let { n -> return Rect().also { n.getBoundsInScreen(it) } }
                Thread.sleep(100)
            }
            error("Bubble pane bounds missing")
        }
        fun rawShot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "refine-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        var down = 0L
        fun event(action: Int, x: Float, y: Float) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(e, true)); e.recycle()
        }
        fun hold(): Rect {
            val anchor = rect("餐饮")
            event(MotionEvent.ACTION_DOWN, anchor.exactCenterX(), anchor.exactCenterY()); Thread.sleep(650); await("二级分类：午餐")
            return anchor
        }
        fun cancelHeld() { event(MotionEvent.ACTION_CANCEL, 10f, 10f); Thread.sleep(250); click("取消"); await("首页") }
        fun list(r: Rect) = listOf(r.left, r.top, r.right, r.bottom)
        val dimensions = linkedMapOf<String, Any?>()
        try {
            subs(listOf("午餐")); openCard(); val heldSingle = hold()
            val single = pane(); dimensions["single"] = list(single); capture("style-single")
            check(single.width() < context.resources.displayMetrics.widthPixels / 2)
            event(MotionEvent.ACTION_UP, heldSingle.exactCenterX(), heldSingle.exactCenterY()); Thread.sleep(300)
            check(find("餐饮 · 午餐") == null) { "Opening under finger selected without movement" }
            click("取消"); await("首页")
            subs(listOf("早餐", "午餐", "晚餐")); openCard(); hold()
            val three = pane(); dimensions["three"] = list(three)
            check(three.width() > single.width() * 2 && three.width() < context.resources.displayMetrics.widthPixels * .8f)
            val breakfast = rect("二级分类：早餐"); val dinner = rect("二级分类：晚餐")
            dimensions["breakfast"] = list(breakfast); dimensions["dinner"] = list(dinner)
            dimensions["density"] = context.resources.displayMetrics.density
            event(MotionEvent.ACTION_MOVE, breakfast.exactCenterX(), breakfast.exactCenterY()); Thread.sleep(300)
            capture("style-three-start")
            val start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_MOVE, dinner.exactCenterX(), dinner.exactCenterY())
            val times = mutableListOf<Long>()
            repeat(5) { n -> times += SystemClock.uptimeMillis() - start; rawShot("style-motion-$n"); Thread.sleep(16) }
            Thread.sleep(300); capture("style-three-settled"); dimensions["motionTimesMs"] = times
            event(MotionEvent.ACTION_UP, dinner.exactCenterX(), dinner.exactCenterY()); await("餐饮 · 晚餐")
            slide("餐饮 · 晚餐", "午餐", outside = true); await("餐饮 · 晚餐")
            slide("餐饮 · 晚餐", "午餐", cancel = true); await("餐饮 · 晚餐")
            slide("餐饮 · 晚餐", "午餐", reenter = true); await("餐饮 · 午餐")
            click("取消"); await("放弃本次修改？"); click("放弃修改"); await("首页")
            subs(listOf("午餐", "很长的二级分类名称需要完整展示", "另外一个长名称选项")); openCard(); hold()
            val long = pane(); dimensions["long"] = list(long); check(long.width() > three.width())
            capture("style-long"); cancelHeld()
            File(context.getExternalFilesDir(null), "refine-style-dimensions.json").writeText(MiniJson.encode(dimensions))
            return listOf("one_three_and_long_content_widths_actual_layout", "shared_inset_slider_spring_middle_frames_recorded", "new_bubble_release_and_slide_out_cancel")
        } catch (failure: Throwable) { capture("style-failure"); throw failure }
        finally { subs(original) }
    }
    fun edge(): List<String> {
        LedgerConfig.init(context)
        repeat(12) { LedgerConfig.addSubCategory(BillType.EXPENSE, "餐饮", "边缘候选$it") }
        launchMain(); click("记一笔"); await("备注（可选）")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); Thread.sleep(250)
        val anchor = rect("餐饮")
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(e, true)); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, anchor.exactCenterX(), anchor.exactCenterY()); Thread.sleep(650); await("二级分类：午餐")
        val edge = context.resources.displayMetrics.widthPixels - 24f * context.resources.displayMetrics.density
        event(MotionEvent.ACTION_MOVE, edge, anchor.exactCenterY())
        val deadline = SystemClock.uptimeMillis() + 25000
        while (find("二级分类：边缘候选11") == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        val last = rect("二级分类：边缘候选11")
        val x = last.exactCenterX().coerceAtMost(edge - 10f)
        event(MotionEvent.ACTION_MOVE, x, last.exactCenterY()); Thread.sleep(150)
        capture("edge-final-candidate")
        event(MotionEvent.ACTION_UP, x, last.exactCenterY())
        await("餐饮 · 边缘候选11")
        click("取消"); await("放弃本次修改？"); click("放弃修改"); await("首页")
        return listOf("horizontal_edge_scroll_all_18_candidates_reachable_final_release_commits")
    }
    fun visual(): List<String> {
        val prefs = io.github.mymoney.ui.design.Appearance.prefs(context)
        prefs.edit().putString("theme", "DARK").putString("text_size", "EXTRA_LARGE").putBoolean("reduce_motion", true).commit()
        launchMain(); click("记一笔"); await("备注（可选）")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); Thread.sleep(250)
        set("支出金额", "123456.78")
        set("备注（可选）", "长备注第一行\n第二行\n第三行")
        capture("dark-large-manual")
        slide("餐饮", "午餐"); await("餐饮 · 午餐"); capture("dark-large-category-selected")
        click("选择记账日期和时间"); await("日期与时间"); capture("dark-large-date")
        click("小时"); Thread.sleep(500); capture("dark-large-date-ime")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); Thread.sleep(200)
        click("取消"); click("取消"); await("放弃本次修改？"); click("放弃修改"); await("首页")
        return listOf("dark_1_3_font_reduced_motion_compact_note_category_calendar_and_ime_cancel")
    }
    fun run(): List<String> {
        try {
            launchMain(); click("记一笔"); await("备注（可选）")
            // Hide the automatic amount IME to inspect the complete compact card.
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); Thread.sleep(250)
            check(nodes().none { it.text?.toString()?.contains("所选账户未设置余额") == true || it.text?.toString()?.contains("长按分类滑选") == true })
            check(nodes().none { it.text?.toString() == "商户" })
            check(nodes().count { it.text?.toString() == "记一笔" } == 1) { "Duplicate title remains" }
            capture("manual-empty")
            slide("餐饮", "午餐"); await("餐饮 · 午餐")
            slide("餐饮 · 午餐", "晚餐", outside = true); await("餐饮 · 午餐")
            slide("餐饮 · 午餐", "晚餐", cancel = true); await("餐饮 · 午餐")
            slide("餐饮 · 午餐", "晚餐", reenter = true); await("餐饮 · 晚餐")
            slide("餐饮 · 晚餐", null); await("餐饮 · 晚餐")
            results += "same_row_hold_slide_release_outside_cancel_system_cancel_reentry_no_move_guard"
            val small = rect("备注（可选）").height()
            set("备注（可选）", "第一行\n第二行\n第三行\n第四行")
            val growEnd = SystemClock.uptimeMillis() + 5000
            while (rect("备注（可选）").height() <= small && SystemClock.uptimeMillis() < growEnd) Thread.sleep(100)
            check(rect("备注（可选）").height() > small)
            set("备注（可选）", "")
            val shrinkEnd = SystemClock.uptimeMillis() + 5000
            while (rect("备注（可选）").height() != small && SystemClock.uptimeMillis() < shrinkEnd) Thread.sleep(100)
            check(rect("备注（可选）").height() == small)
            results += "note_no_icon_compact_grows_and_clear_shrinks"
            chooseTime(true); chooseTime(false)
            set("支出金额", "1.23")
            val before = runBlocking { db.bills().allOnce().map { it.id }.toSet() }
            click("记一笔"); await("首页")
            check(find("支出金额") == null && find("已记一笔") == null)
            val manual = runBlocking { db.bills().allOnce().single { it.id !in before } }
            check(manual.note == null && manual.priceCents == 123L)
            check(java.time.Instant.ofEpochMilli(manual.timeMillis).atZone(java.time.ZoneId.systemDefault()).let { it.hour == 7 && it.minute == 8 })
            capture("manual-saved-no-result")
            results += "manual_time_saved_empty_note_success_direct_dismiss"
            BillResultActivity.open(context, manual.id); await("修改"); click("修改"); await("备注（可选）")
            chooseTime(false)
            val postings = runBlocking { db.assets().entries() }
            click("保存修改"); await("修改")
            val updated = runBlocking { db.bills().byId(manual.id)!! }
            check(updated.id == manual.id && updated.timeMillis != manual.timeMillis && runBlocking { db.assets().entries() } == postings)
            results += "history_edit_time_same_id_without_duplicate_postings_or_success_popup"
            click("修改"); await("备注（可选）"); set("备注（可选）", "失败保留")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_refinement_fail BEFORE UPDATE ON bills WHEN OLD.id = ${updated.id} BEGIN SELECT RAISE(ABORT, 'QA保存失败'); END")
            click("保存修改")
            val failureEnd = SystemClock.uptimeMillis() + 10000
            while (nodes().none { it.text?.toString()?.contains("QA保存失败") == true } && SystemClock.uptimeMillis() < failureEnd) Thread.sleep(100)
            check(nodes().any { it.text?.toString()?.contains("QA保存失败") == true })
            check(await("备注（可选）").text?.contains("失败保留") == true)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_refinement_fail")
            click("保存修改"); await("修改")
            results += "failed_save_keeps_input_and_retry_unlocks"
            launchMain()
            val d = BillDraft(234, BillType.EXPENSE, System.currentTimeMillis(), "测试来源商户", null, null,
                "io.github.mymoney.qa.confirmrefinement.${UUID.randomUUID()}", "synthetic", category = "餐饮", subCategory = "午餐", noteOrigin = NoteTemplate.USER)
            val history = runBlocking { RecognitionStore.record(d) }
            context.startActivity(Intent(context, ConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConfirmActivity.EXTRA_HISTORY_ID, history.id))
            await("备注（可选）"); capture("activity-confirm")
            click("记一笔"); await("首页")
            check(runBlocking { RecognitionStore.find(history.id)?.status } == RecognitionStatus.RECORDED)
            check(find("已记一笔") == null)
            results += "recognition_activity_real_save_direct_finish"
            val overlayDraft = runBlocking { RecognitionStore.record(d.copy(sourcePkg = d.sourcePkg + ".overlay", timeMillis = d.timeMillis + 60001)) }.toDraft()
            check(io.github.mymoney.overlay.ConfirmOverlay.canShow(context)) { "Overlay permission unavailable" }
            check(io.github.mymoney.overlay.ConfirmOverlay.show(context, overlayDraft))
            await("备注（可选）"); capture("overlay-confirm"); click("记一笔")
            val end = SystemClock.uptimeMillis() + 10000
            while (io.github.mymoney.overlay.ConfirmOverlay.isShowing() && SystemClock.uptimeMillis() < end) Thread.sleep(100)
            check(!io.github.mymoney.overlay.ConfirmOverlay.isShowing())
            check(runBlocking { RecognitionStore.find(overlayDraft.historyId!!)?.status } == RecognitionStatus.RECORDED)
            results += "overlay_real_save_direct_dismiss"
            return results
        } catch (failure: Throwable) { capture("failure"); throw IllegalStateException("Completed: $results", failure) }
    }
}
