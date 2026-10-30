package io.github.mymoney

import io.github.mymoney.ui.BackHandoff
import org.junit.Assert.*
import org.junit.Test

class BackHandoffTest {
    @Test fun commitWaitsForDestinationDrawInsteadOfResettingToOldPage() {
        val state = BackHandoff()
        assertTrue(state.begin("child")); state.drag(.7f); state.commit()
        assertEquals(BackHandoff.Phase.COMMITTED, state.phase)
        assertFalse(state.finishDrawn())
        state.drawn("child"); assertFalse(state.finishDrawn())
        assertEquals(BackHandoff.Phase.COMMITTED, state.phase)
        state.drawn("parent"); assertTrue(state.finishDrawn())
        assertEquals(BackHandoff.Phase.IDLE, state.phase)
    }
    @Test fun cancelledGestureKeepsTheRouteAndMayRestart() {
        val state = BackHandoff()
        state.begin("child"); state.drag(.8f); state.reset()
        assertEquals(0f, state.progress)
        assertEquals(BackHandoff.Phase.IDLE, state.phase)
        assertTrue(state.begin("child"))
        state.drag(.3f); assertEquals(.3f, state.progress)
    }
    @Test fun repeatedGestureCannotReplaceAnUnfinishedCommit() {
        val state = BackHandoff()
        state.begin("child"); state.commit()
        assertFalse(state.begin("parent"))
        assertEquals("child", state.startedRoute)
        state.drawn("parent"); assertTrue(state.finishDrawn())
        assertTrue(state.begin("parent"))
    }
    @Test fun outerRouteChangeCannotReleaseCoverBeforeActualContentIsReady() {
        val state = BackHandoff()
        state.begin("child"); state.drag(.8f); state.commit()
        state.drawn("parent", contentReady = false)
        assertFalse(state.finishDrawn())
        assertEquals(BackHandoff.Phase.COMMITTED, state.phase)
        state.drawn("parent", contentReady = true)
        assertTrue(state.finishDrawn())
    }

}
