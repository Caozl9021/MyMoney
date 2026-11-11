package io.github.mymoney

import io.github.mymoney.data.Reorder
import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderTest {
    @Test
    fun move_swaps_within_bounds() {
        assertEquals(listOf("b", "a", "c"), Reorder.move(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("b", "c", "a"), Reorder.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("a", "b", "c"), Reorder.move(listOf("a", "b", "c"), 1, 1))
        assertEquals(listOf("a", "b", "c"), Reorder.move(listOf("a", "b", "c"), -1, 1))
    }

    @Test
    fun subset_slots_preserve_unlisted() {
        val active = listOf("A", "U1", "B", "U2", "C")
        val orderedDisplayed = listOf("B", "C", "A")
        val set = orderedDisplayed.toSet()
        val slots = active.mapIndexedNotNull { index, id -> if (id in set) index else null }
        val merged = active.toMutableList()
        slots.forEachIndexed { i, slot -> merged[slot] = orderedDisplayed[i] }
        assertEquals(listOf("B", "U1", "C", "U2", "A"), merged)
    }
}
