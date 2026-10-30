package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.ui.CategoryHitRegion
import io.github.caozl9021.mymoney.ui.CategorySlideGesture
import io.github.caozl9021.mymoney.ui.categoryPanelSpace
import org.junit.Assert.*
import org.junit.Test

class CategorySlideGestureTest {
    private val regions = listOf(
        CategoryHitRegion("早餐", 10f, 20f, 110f, 68f),
        CategoryHitRegion("午餐", 10f, 76f, 110f, 124f),
        CategoryHitRegion("晚餐", 10f, 132f, 110f, 180f),
    )
    private fun gesture() = CategorySlideGesture(setOf("早餐", "午餐", "晚餐"))

    @Test fun `reverse movement commits only final candidate once`() {
        val g = gesture()
        assertEquals("早餐", g.move(50f, 40f, regions))
        assertEquals("晚餐", g.move(50f, 150f, regions))
        assertEquals("午餐", g.move(50f, 90f, regions))
        assertEquals("午餐", g.release())
        assertNull(g.release())
        assertNull(g.move(50f, 40f, regions))
    }

    @Test fun `outside gaps and exact far edges cancel instead of nearest item`() {
        for ((x, y) in listOf(9f to 40f, 110f to 40f, 50f to 68f, 50f to 72f, 50f to 180f)) {
            val g = gesture()
            g.move(50f, 40f, regions)
            assertNull(g.move(x, y, regions))
            assertNull(g.release())
        }
    }

    @Test fun `system cancel and second pointer cannot commit stale preview`() {
        val g = gesture()
        g.move(50f, 40f, regions)
        g.cancel()
        assertNull(g.release())
        assertNull(g.move(50f, 90f, regions))
    }

    @Test fun `layout scroll remeasures actual targets and rejects foreign names`() {
        val g = gesture()
        assertEquals("早餐", g.move(50f, 40f, regions))
        val scrolled = listOf(CategoryHitRegion("午餐", 10f, 20f, 110f, 68f),
            CategoryHitRegion("不属于此分类", 10f, 76f, 110f, 124f))
        assertEquals("午餐", g.move(50f, 40f, scrolled))
        assertNull(g.move(50f, 90f, scrolled))
        assertNull(g.release())
    }

    @Test fun `release without entering a candidate never picks original value`() {
        assertNull(gesture().release())
    }

    @Test fun `small screen panel stays above held chip instead of covering it`() {
        val space = categoryPanelSpace(800f, 884f, 1280f, 73f, 42f, 28f)
        assertTrue(space.above)
        assertEquals(699f, space.height, 0f)
        assertTrue(73f + space.height < 800f)
    }

    @Test fun `top edge opens below and preserves bottom safe area`() {
        val space = categoryPanelSpace(80f, 160f, 1000f, 60f, 40f, 16f)
        assertFalse(space.above)
        assertEquals(784f, space.height, 0f)
    }
}
