package io.github.mymoney.ui

import androidx.compose.ui.geometry.Rect
import io.github.mymoney.ui.design.AppDialogChoice
import io.github.mymoney.ui.design.GlassContextMenuState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppLiquidGlassStateTest {

    @Test fun reimburseFeedbackUpdatesDistinctTokensAndClearsCleanly() {
        ReimburseFeedback.clear()
        assertNull(ReimburseFeedback.notice)

        ReimburseFeedback.show("已按每月1日统计")
        val first = ReimburseFeedback.notice
        assertNotNull(first)
        assertEquals("已按每月1日统计", first?.text)
        assertNull(first?.undoBillId)

        ReimburseFeedback.show("已按每月1日统计")
        val second = ReimburseFeedback.notice
        assertNotNull(second)
        assertTrue(second != first || second !== first)

        ReimburseFeedback.show("已标为已报销", undoBillId = 42L)
        assertEquals(42L, ReimburseFeedback.notice?.undoBillId)

        ReimburseFeedback.clear()
        assertNull(ReimburseFeedback.notice)
    }

    @Test fun glassContextMenuStateOpensBumpsGenerationAndDismisses() {
        val state = GlassContextMenuState()
        assertFalse(state.open)
        assertEquals(0, state.generation)

        val anchor = Rect(10f, 20f, 110f, 60f)
        state.show(
            title = "排序",
            options = listOf(AppDialogChoice("TIME", "按时间")),
            selectedKey = "TIME",
            anchor = anchor,
            onSelect = {},
        )
        assertTrue(state.open)
        assertEquals(1, state.generation)
        assertEquals("排序", state.request?.title)
        assertEquals(anchor, state.request?.anchor)

        state.dismiss()
        assertFalse(state.open)

        state.show(options = emptyList(), anchor = anchor, onSelect = {})
        assertFalse(state.open)
        assertEquals(1, state.generation)
    }

    @Test fun ledgerTimeMillisEncodesExactCalendarTime() {
        val millis = ledgerTimeMillis(LocalDate.of(2026, 11, 24), 7, 8, ZoneOffset.UTC)
        val decoded = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(2026, decoded.year)
        assertEquals(11, decoded.monthValue)
        assertEquals(24, decoded.dayOfMonth)
        assertEquals(7, decoded.hour)
        assertEquals(8, decoded.minute)
    }
}
