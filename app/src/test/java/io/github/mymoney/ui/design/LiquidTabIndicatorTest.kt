package io.github.mymoney.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidTabIndicatorTest {
    private val bleedPx = 54f // 18dp @ 3x
    private val dockW = 864f
    private val dockH = 180f // 60dp @ 3x
    private val canvasW = dockW + bleedPx * 2f
    private val canvasH = dockH + bleedPx * 2f
    private val insetPx = 18f // 6dp @ 3x
    private val extraPadPx = 18f // 6dp @ 3x
    private val baseFillAlpha = 0.12f

    private fun frameAt(
        position: Float,
        velocity: Float = 0f,
        lift: Float = 0f,
        bleed: Float = bleedPx,
        isDark: Boolean = false,
    ): TabIndicatorFrame = computeTabIndicatorFrame(
        canvasWidth = if (bleed > 0f) canvasW else dockW,
        canvasHeight = if (bleed > 0f) canvasH else dockH,
        bleedPx = bleed,
        insetPx = insetPx,
        extraPadPx = extraPadPx,
        tabCount = 4,
        position = position,
        velocityTabsPerSec = velocity,
        lift = lift,
        baseFillAlpha = baseFillAlpha,
        isDark = isDark,
    )

    @Test fun tabIndexForXMapsCellsAndClampsBounds() {
        val rowW = 800f
        assertEquals(0, tabIndexForX(-50f, rowW, 4))
        assertEquals(0, tabIndexForX(0f, rowW, 4))
        assertEquals(0, tabIndexForX(199f, rowW, 4))
        assertEquals(1, tabIndexForX(201f, rowW, 4))
        assertEquals(2, tabIndexForX(450f, rowW, 4))
        assertEquals(3, tabIndexForX(799f, rowW, 4))
        assertEquals(3, tabIndexForX(950f, rowW, 4))
        assertEquals(0, tabIndexForX(100f, 0f, 4))
        assertEquals(0, tabIndexForX(100f, rowW, 1))
    }

    @Test fun rubberBandTabPositionPreservesInteriorAndCapsOverscroll() {
        assertEquals(0f, rubberBandTabPosition(0f, 3), 0.0001f)
        assertEquals(1.42f, rubberBandTabPosition(1.42f, 3), 0.0001f)
        assertEquals(3f, rubberBandTabPosition(3f, 3), 0.0001f)

        val slightLeft = rubberBandTabPosition(-0.3f, 3)
        assertTrue(slightLeft < 0f && slightLeft >= -0.10f)
        assertEquals(-0.10f, rubberBandTabPosition(-2f, 3), 0.0001f)

        val slightRight = rubberBandTabPosition(3.3f, 3)
        assertTrue(slightRight > 3f && slightRight <= 3.10f)
        assertEquals(3.10f, rubberBandTabPosition(5f, 3), 0.0001f)
    }

    @Test fun resolveScrubTargetTabProjectsFastFlicksAndClamps() {
        assertEquals(1, resolveScrubTargetTab(currentPos = 1.40f, velocityTabsPerSec = 0.2f, tabCount = 4))
        assertEquals(2, resolveScrubTargetTab(currentPos = 1.40f, velocityTabsPerSec = 6f, tabCount = 4))
        assertEquals(0, resolveScrubTargetTab(currentPos = 0.60f, velocityTabsPerSec = -6f, tabCount = 4))
        assertEquals(0, resolveScrubTargetTab(currentPos = -0.10f, velocityTabsPerSec = -18f, tabCount = 4))
        assertEquals(3, resolveScrubTargetTab(currentPos = 3.10f, velocityTabsPerSec = 18f, tabCount = 4))
    }

    @Test fun restingFrameCentersOnTabWithExactThreeDpDockInset() {
        val cellW = (dockW - insetPx * 2f) / 4f
        val cellH = dockH - insetPx * 2f
        val expectedW = cellW + extraPadPx
        val expectedH = cellH + extraPadPx
        val threeDpPx = 9f

        val tab0 = frameAt(position = 0f, velocity = 0f, lift = 0f)
        assertEquals(expectedW, tab0.width, 0.01f)
        assertEquals(expectedH, tab0.height, 0.01f)
        assertEquals(bleedPx + threeDpPx, tab0.left, 0.01f)
        assertEquals(bleedPx + threeDpPx, tab0.top, 0.01f)
        assertEquals(baseFillAlpha, tab0.fillAlpha, 0.001f)
        assertEquals(0f, tab0.highlightAlpha, 0.001f)
        assertEquals(0f, tab0.rimAlpha, 0.001f)
        assertEquals(0f, tab0.shadowAlpha, 0.001f)
        assertEquals(0f, tab0.lensMix, 0.001f)
        assertEquals(canvasH / 2f, tab0.top + tab0.height / 2f, 0.01f)

        val tab3 = frameAt(position = 3f, velocity = 0f, lift = 0f)
        assertEquals(bleedPx + dockW - threeDpPx, tab3.left + tab3.width, 0.01f)
        assertEquals(canvasH / 2f, tab3.top + tab3.height / 2f, 0.01f)
    }

    @Test fun stationaryLiftExpandsSymmetricallyPastDockHeightWithSheen() {
        val rest0 = frameAt(position = 0f, velocity = 0f, lift = 0f)
        val lifted0 = frameAt(position = 0f, velocity = 0f, lift = 1f)
        assertTrue(lifted0.height > dockH)
        assertTrue(lifted0.width > rest0.width)
        assertTrue(lifted0.left >= 0f && lifted0.left + lifted0.width <= canvasW)
        assertTrue(lifted0.top >= 0f && lifted0.top + lifted0.height <= canvasH)
        assertEquals(rest0.left + rest0.width / 2f, lifted0.left + lifted0.width / 2f, 0.05f)
        assertEquals(rest0.top + rest0.height / 2f, lifted0.top + lifted0.height / 2f, 0.05f)
        assertEquals(canvasH / 2f, lifted0.top + lifted0.height / 2f, 0.01f)
        assertTrue(lifted0.highlightAlpha > 0f)
        assertTrue(lifted0.rimAlpha > 0f)
        assertTrue(lifted0.shadowAlpha > 0f)
        assertEquals(1f, lifted0.lensMix, 0.001f)

        val rest3 = frameAt(position = 3f, velocity = 0f, lift = 0f)
        val lifted3 = frameAt(position = 3f, velocity = 0f, lift = 1f)
        assertEquals(rest3.left + rest3.width / 2f, lifted3.left + lifted3.width / 2f, 0.05f)
        assertTrue(lifted3.left >= 0f && lifted3.left + lifted3.width <= canvasW)
    }

    @Test fun velocityCouplesVolumeConservingSquashAndStretchAndSaturates() {
        val staticLifted = frameAt(position = 1.5f, velocity = 0f, lift = 1f)
        val fastMoving = frameAt(position = 1.5f, velocity = 8f, lift = 1f)
        val fastMovingLeft = frameAt(position = 1.5f, velocity = -8f, lift = 1f)
        val extremeMoving = frameAt(position = 1.5f, velocity = 100f, lift = 1f)
        val glideOnly = frameAt(position = 1.5f, velocity = 8f, lift = 0f)

        assertTrue(fastMoving.width < staticLifted.width)
        assertTrue(fastMoving.height > staticLifted.height)
        assertEquals(fastMoving.width, fastMovingLeft.width, 0.01f)
        assertEquals(fastMoving.height, fastMovingLeft.height, 0.01f)
        assertTrue(fastMoving.height > dockH + 10f)
        assertEquals(canvasH / 2f, fastMoving.top + fastMoving.height / 2f, 0.01f)
        assertTrue(glideOnly.lensMix > 0f)
        assertTrue(extremeMoving.width <= fastMoving.width)
        assertTrue(extremeMoving.width > 1f)
        assertTrue(extremeMoving.width < canvasW)
        assertTrue(extremeMoving.height >= fastMoving.height)
    }

    @Test fun dragSqueezeNarrowsWidthBelowRestWhileSwellingHeight() {
        val runtimeInsetPx = 15f
        val rest = computeTabIndicatorFrame(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            bleedPx = bleedPx,
            insetPx = runtimeInsetPx,
            extraPadPx = 0f,
            tabCount = 4,
            position = 1.5f,
            velocityTabsPerSec = 0f,
            lift = 0f,
            baseFillAlpha = baseFillAlpha,
            isDark = false,
            dragSqueeze = 0f,
        )
        val liftedHold = computeTabIndicatorFrame(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            bleedPx = bleedPx,
            insetPx = runtimeInsetPx,
            extraPadPx = 0f,
            tabCount = 4,
            position = 1.5f,
            velocityTabsPerSec = 0f,
            lift = 1f,
            baseFillAlpha = baseFillAlpha,
            isDark = false,
            dragSqueeze = 0f,
        )
        val dragged = computeTabIndicatorFrame(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            bleedPx = bleedPx,
            insetPx = runtimeInsetPx,
            extraPadPx = 0f,
            tabCount = 4,
            position = 1.5f,
            velocityTabsPerSec = 8f,
            lift = 1f,
            baseFillAlpha = baseFillAlpha,
            isDark = false,
            dragSqueeze = 1f,
        )

        assertTrue(liftedHold.width > liftedHold.height)
        assertTrue(dragged.width < rest.width)
        assertTrue(dragged.width < liftedHold.width)
        assertTrue(dragged.height > liftedHold.height)
        assertEquals(canvasH / 2f, dragged.top + dragged.height / 2f, 0.01f)
    }

    @Test fun rubberBandEdgeDragRemainsInsideCanvasWithoutClipping() {
        val leftRubber = frameAt(position = -0.10f, velocity = -4f, lift = 1f)
        val rightRubber = frameAt(position = 3.10f, velocity = 4f, lift = 1f)

        assertTrue(leftRubber.left >= 0f && leftRubber.left + leftRubber.width <= canvasW)
        assertTrue(leftRubber.top >= 0f && leftRubber.top + leftRubber.height <= canvasH)
        assertEquals(canvasH / 2f, leftRubber.top + leftRubber.height / 2f, 0.01f)
        assertTrue(rightRubber.left >= 0f && rightRubber.left + rightRubber.width <= canvasW)
        assertTrue(rightRubber.top >= 0f && rightRubber.top + rightRubber.height <= canvasH)
        assertEquals(canvasH / 2f, rightRubber.top + rightRubber.height / 2f, 0.01f)
    }

    @Test fun negativeSpringUndershootAndZeroBleedFallbackStaySafe() {
        val undershoot = frameAt(position = 1f, velocity = 0f, lift = -0.15f, isDark = true)
        assertTrue(undershoot.width > 1f)
        assertTrue(undershoot.height > 1f)
        assertTrue(undershoot.fillAlpha in 0f..1f)
        assertEquals(0f, undershoot.highlightAlpha, 0.001f)
        assertEquals(0f, undershoot.rimAlpha, 0.001f)
        assertEquals(0f, undershoot.shadowAlpha, 0.001f)
        assertEquals(0f, undershoot.lensMix, 0.001f)

        val noBleed = frameAt(position = 0f, velocity = 8f, lift = 1f, bleed = 0f)
        assertTrue(noBleed.left >= 1f)
        assertTrue(noBleed.left + noBleed.width <= dockW - 1f)
        assertTrue(noBleed.top >= 1f)
        assertTrue(noBleed.top + noBleed.height <= dockH - 1f)
        assertEquals(dockH / 2f, noBleed.top + noBleed.height / 2f, 0.01f)
    }

    @Test fun computeTabIndicatorFrameUsesExplicitLensOverrideWithoutChangingGeometry() {
        val rest = computeTabIndicatorFrame(
            canvasWidth = 400f,
            canvasHeight = 96f,
            bleedPx = 18f,
            insetPx = 6f,
            extraPadPx = 6f,
            tabCount = 4,
            position = 1f,
            velocityTabsPerSec = 0f,
            lift = 0f,
            baseFillAlpha = 0.09f,
            isDark = false,
            lens = 0f,
        )
        val midLens = computeTabIndicatorFrame(
            canvasWidth = 400f,
            canvasHeight = 96f,
            bleedPx = 18f,
            insetPx = 6f,
            extraPadPx = 6f,
            tabCount = 4,
            position = 1f,
            velocityTabsPerSec = 0f,
            lift = 0f,
            baseFillAlpha = 0.09f,
            isDark = false,
            lens = 0.4f,
        )
        val clampedLow = computeTabIndicatorFrame(
            canvasWidth = 400f,
            canvasHeight = 96f,
            bleedPx = 18f,
            insetPx = 6f,
            extraPadPx = 6f,
            tabCount = 4,
            position = 1f,
            velocityTabsPerSec = 0f,
            lift = 0f,
            baseFillAlpha = 0.09f,
            isDark = false,
            lens = -0.2f,
        )
        val clampedHigh = computeTabIndicatorFrame(
            canvasWidth = 400f,
            canvasHeight = 96f,
            bleedPx = 18f,
            insetPx = 6f,
            extraPadPx = 6f,
            tabCount = 4,
            position = 1f,
            velocityTabsPerSec = 0f,
            lift = 0f,
            baseFillAlpha = 0.09f,
            isDark = false,
            lens = 1.5f,
        )

        assertEquals(0f, rest.lensMix, 0.0001f)
        assertEquals(0.4f, midLens.lensMix, 0.0001f)
        assertEquals(rest.width, midLens.width, 0.0001f)
        assertEquals(rest.height, midLens.height, 0.0001f)
        assertEquals(0f, clampedLow.lensMix, 0.0001f)
        assertEquals(1f, clampedHigh.lensMix, 0.0001f)
    }

    @Test fun staggeredLiftAxesControlWidthAndHeightIndependently() {
        val rest = frameAt(position = 1f, velocity = 0f, lift = 0f)
        val xWobble = computeTabIndicatorFrame(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            bleedPx = bleedPx,
            insetPx = insetPx,
            extraPadPx = extraPadPx,
            tabCount = 4,
            position = 1f,
            velocityTabsPerSec = 0f,
            lift = -0.10f,
            baseFillAlpha = baseFillAlpha,
            isDark = false,
            liftY = 0.25f,
        )

        assertTrue(xWobble.width < rest.width)
        assertTrue(xWobble.height > rest.height)
    }

    @Test fun smoothstep01ClampsAndIsMonotonic() {
        assertEquals(0f, smoothstep01(-0.5f), 0.0001f)
        assertEquals(0f, smoothstep01(0f), 0.0001f)
        assertEquals(0.5f, smoothstep01(0.5f), 0.0001f)
        assertEquals(1f, smoothstep01(1f), 0.0001f)
        assertEquals(1f, smoothstep01(1.5f), 0.0001f)

        var prev = smoothstep01(0f)
        for (i in 1..10) {
            val cur = smoothstep01(i / 10f)
            assertTrue(cur > prev)
            prev = cur
        }
    }
}
