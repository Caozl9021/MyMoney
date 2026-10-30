package io.github.caozl9021.mymoney

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.caozl9021.mymoney.ui.CategoryPickerRequest
import io.github.caozl9021.mymoney.ui.CategoryPickerState
import org.junit.Assert.*
import org.junit.Test

class CategoryHoverFeedbackTest {
    @Test fun enteringCandidatesPulsesOnceButStationaryMoveCancelAndReleaseDoNot() {
        val s = CategoryPickerState()
        var pulses = 0
        s.onHoverEnter = { pulses++ }
        s.show(CategoryPickerRequest("餐饮", listOf("早餐", "午餐"), Rect.Zero, null, true))
        s.viewport = Rect(0f, 0f, 150f, 48f)
        s.regions["早餐"] = Rect(4f, 0f, 68f, 48f)
        s.regions["午餐"] = Rect(70f, 0f, 134f, 48f)
        s.move(Offset(30f, 20f)); repeat(100) { s.move(Offset(31f, 20f)) }
        assertEquals(1, pulses)
        s.move(Offset(100f, 20f)); assertEquals(2, pulses)
        s.move(Offset(100f, 60f)); assertEquals(2, pulses)
        s.move(Offset(100f, 20f)); assertEquals(3, pulses)
        assertEquals("餐饮" to "午餐", s.release())
        assertEquals(3, pulses)
        s.cancel(); s.move(Offset(30f, 20f)); assertEquals(3, pulses)
    }

    @Test fun clickModeDoesNotEmitHoverPulsesOrCommitFromPointerMovement() {
        val s = CategoryPickerState()
        var pulses = 0
        s.onHoverEnter = { pulses++ }
        s.show(CategoryPickerRequest("餐饮", listOf("午餐"), Rect.Zero, "午餐", false))
        s.viewport = Rect(0f, 0f, 100f, 48f); s.regions["午餐"] = s.viewport
        s.move(Offset(20f, 20f))
        assertNull(s.hovered); assertEquals(0, pulses); assertNull(s.release())
    }
}
