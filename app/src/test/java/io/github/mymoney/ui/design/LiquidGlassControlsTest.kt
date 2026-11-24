package io.github.mymoney.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassControlsTest {

    @Test fun appearanceLiquidGlassDefaultsToEnabled() {
        assertEquals("liquid_glass", Appearance.LIQUID_GLASS)
        assertTrue(Appearance.DEFAULT_LIQUID_GLASS)
        assertEquals("liquid_glass_clarity", Appearance.LIQUID_GLASS_CLARITY)
        assertEquals(0.65f, Appearance.DEFAULT_LIQUID_GLASS_CLARITY, 0.0001f)
        assertEquals("liquid_glass_per_zone_clarity", Appearance.LIQUID_GLASS_PER_ZONE_CLARITY)
    }

    @Test fun resolveClaritySpecHonorsUnifiedVsPerZoneOverrides() {
        val unified = Appearance.resolveClaritySpec(
            perZone = false,
            global = 0.80f,
            dock = 0.20f,
            header = 0.30f,
            sheet = 0.40f,
            dialog = 0.50f,
        )
        assertEquals(0.80f, unified.dock, 0.0001f)
        assertEquals(0.80f, unified.header, 0.0001f)
        assertEquals(0.80f, unified.sheet, 0.0001f)
        assertEquals(0.80f, unified.dialog, 0.0001f)

        val split = Appearance.resolveClaritySpec(
            perZone = true,
            global = 0.80f,
            dock = 0.25f,
            header = 1.4f,
            sheet = -0.2f,
            dialog = 0.55f,
        )
        assertEquals(0.25f, split.dock, 0.0001f)
        assertEquals(1f, split.header, 0.0001f)
        assertEquals(0f, split.sheet, 0.0001f)
        assertEquals(0.55f, split.dialog, 0.0001f)
    }

    @Test fun glassBlurRadiusDpMapsMonotonicallyFromFrostToClear() {
        val frosted = glassBlurRadiusDp(0f, baseDp = 4.5f, minDp = 1.8f, maxDp = 11.0f)
        val balanced = glassBlurRadiusDp(0.65f, baseDp = 4.5f, minDp = 1.8f, maxDp = 11.0f)
        val clear = glassBlurRadiusDp(1f, baseDp = 4.5f, minDp = 1.8f, maxDp = 11.0f)
        assertEquals(11.0f, frosted, 0.001f)
        assertEquals(4.5f, balanced, 0.001f)
        assertEquals(1.8f, clear, 0.001f)
    }

    @Test fun glassSurfaceAlphaScalesMonotonicallyAndPreservesReadabilityBounds() {
        val lightTinted = glassSurfaceAlpha(0.88f, 0.84f, isDark = false, clarity = 0f)
        val lightDefault = glassSurfaceAlpha(0.88f, 0.84f, isDark = false, clarity = 0.65f)
        val lightClear = glassSurfaceAlpha(0.88f, 0.84f, isDark = false, clarity = 1f)
        assertEquals(0.96f, lightTinted, 0.001f)
        assertEquals(0.88f, lightDefault, 0.001f)
        assertTrue(lightTinted > lightDefault && lightDefault > lightClear)
        assertTrue(lightClear >= 0.50f)

        val darkTinted = glassSurfaceAlpha(0.88f, 0.84f, isDark = true, clarity = 0f)
        val darkDefault = glassSurfaceAlpha(0.88f, 0.84f, isDark = true, clarity = 0.65f)
        val darkClear = glassSurfaceAlpha(0.88f, 0.84f, isDark = true, clarity = 1f)
        assertEquals(0.94f, darkTinted, 0.001f)
        assertEquals(0.84f, darkDefault, 0.001f)
        assertTrue(darkTinted > darkDefault && darkDefault > darkClear)
        assertTrue(darkClear >= 0.46f)

        // With live blurred backdrop (low base alpha), max clarity stays strictly clearer than default.
        val backdropTinted = glassSurfaceAlpha(0.50f, 0.64f, isDark = false, clarity = 0f)
        val backdropDefault = glassSurfaceAlpha(0.50f, 0.64f, isDark = false, clarity = 0.65f)
        val backdropClear = glassSurfaceAlpha(0.50f, 0.64f, isDark = false, clarity = 1f)
        assertTrue(backdropTinted > backdropDefault && backdropDefault > backdropClear)
        assertTrue(backdropClear in 0.20f..0.30f)
    }

    @Test fun sliderRubberBandAndPillFrameStayBounded() {
        assertEquals(0f, rubberBandSliderValue(0f), 0.0001f)
        assertEquals(0.5f, rubberBandSliderValue(0.5f), 0.0001f)
        assertEquals(1f, rubberBandSliderValue(1f), 0.0001f)
        assertEquals(-0.05f, rubberBandSliderValue(-1f), 0.0001f)
        assertEquals(1.05f, rubberBandSliderValue(2f), 0.0001f)

        val rest = computeSliderPillFrame(
            canvasWidth = 600f,
            canvasHeight = 144f,
            bleedPx = 24f,
            knobWidth = 84f,
            knobHeight = 60f,
            value = 0.5f,
            velocityPerSec = 0f,
            lift = 0f,
            lens = 0f,
            isDark = false,
        )
        assertEquals(1f, rest.fillAlpha, 0.001f)
        assertEquals(0f, rest.lensMix, 0.001f)
        assertEquals(84f, rest.width, 0.01f)
        assertEquals(60f, rest.height, 0.01f)
        assertEquals(300f, rest.left + rest.width * 0.5f, 0.01f)

        val active = computeSliderPillFrame(
            canvasWidth = 600f,
            canvasHeight = 144f,
            bleedPx = 24f,
            knobWidth = 84f,
            knobHeight = 60f,
            value = 0.65f,
            velocityPerSec = 4f,
            lift = 1f,
            lens = 1f,
            isDark = true,
        )
        assertTrue(active.width > rest.width)
        assertTrue(active.height > rest.height)
        assertTrue(active.fillAlpha < 0.15f)
        assertEquals(1f, active.lensMix, 0.001f)
        assertTrue(active.left >= 0f && active.left + active.width <= 600f)
        assertTrue(active.top >= 0f && active.top + active.height <= 144f)
    }

    @Test fun rubberBandSwitchPositionPreservesInteriorAndCapsOverscroll() {
        assertEquals(0f, rubberBandSwitchPosition(0f), 0.0001f)
        assertEquals(0.45f, rubberBandSwitchPosition(0.45f), 0.0001f)
        assertEquals(1f, rubberBandSwitchPosition(1f), 0.0001f)

        val left = rubberBandSwitchPosition(-0.2f)
        assertTrue(left < 0f && left >= -0.08f)
        assertEquals(-0.08f, rubberBandSwitchPosition(-2f), 0.0001f)

        val right = rubberBandSwitchPosition(1.2f)
        assertTrue(right > 1f && right <= 1.08f)
        assertEquals(1.08f, rubberBandSwitchPosition(3f), 0.0001f)
    }

    @Test fun resolveSwitchTargetUsesPositionAndVelocityProjection() {
        assertFalse(resolveSwitchTarget(0.35f, 0f))
        assertTrue(resolveSwitchTarget(0.65f, 0f))
        assertTrue(resolveSwitchTarget(0.35f, 4f))
        assertFalse(resolveSwitchTarget(0.65f, -4f))
    }

    @Test fun computeSwitchPillFrameStaysWithinCanvasAndLiquefiesWhenActive() {
        val rest = computeSwitchPillFrame(
            canvasWidth = 204f,
            canvasHeight = 180f,
            trackWidth = 156f,
            trackHeight = 84f,
            pillWidth = 93f,
            pillHeight = 66f,
            position = 0f,
            velocityPerSec = 0f,
            lift = 0f,
            lens = 0f,
            isDark = false,
        )
        assertEquals(1f, rest.fillAlpha, 0.001f)
        assertEquals(0f, rest.lensMix, 0.001f)
        assertEquals(0f, rest.rimAlpha, 0.001f)
        assertEquals(90f, rest.top + rest.height * 0.5f, 0.01f)

        val active = computeSwitchPillFrame(
            canvasWidth = 204f,
            canvasHeight = 180f,
            trackWidth = 156f,
            trackHeight = 84f,
            pillWidth = 93f,
            pillHeight = 66f,
            position = 0.5f,
            velocityPerSec = 5f,
            lift = 1f,
            lens = 1f,
            isDark = true,
        )
        assertTrue(active.width > rest.width)
        assertTrue(active.height > rest.height)
        assertTrue(active.fillAlpha < 0.15f)
        assertEquals(1f, active.lensMix, 0.001f)
        assertTrue(active.left >= 0f && active.left + active.width <= 204f)
        assertTrue(active.top >= 0f && active.top + active.height <= 180f)
    }

    @Test fun segmentIndexAndRubberBandAndTargetWorkAcrossCounts() {
        assertEquals(0, segmentIndexForX(10f, 300f, 3))
        assertEquals(1, segmentIndexForX(150f, 300f, 3))
        assertEquals(2, segmentIndexForX(290f, 300f, 3))

        assertEquals(0f, rubberBandSegmentPosition(0f, 2), 0.0001f)
        assertEquals(-0.10f, rubberBandSegmentPosition(-3f, 2), 0.0001f)
        assertEquals(2.10f, rubberBandSegmentPosition(5f, 2), 0.0001f)

        assertEquals(1, resolveSegmentTarget(0.8f, 0f, 3))
        assertEquals(2, resolveSegmentTarget(1.2f, 8f, 3))
        assertEquals(0, resolveSegmentTarget(0.8f, -8f, 3))
    }

    @Test fun computeSegmentPillFrameExpandsIntoBleedSafely() {
        val rest = computeSegmentPillFrame(
            canvasWidth = 624f,
            canvasHeight = 168f,
            bleedPx = 12f,
            paddingPx = 6f,
            gapPx = 6f,
            count = 3,
            position = 1f,
            velocitySegmentsPerSec = 0f,
            lift = 0f,
            lens = 0f,
            isDark = false,
        )
        assertEquals(1f, rest.fillAlpha, 0.001f)
        assertEquals(0f, rest.lensMix, 0.001f)
        assertEquals(84f, rest.top + rest.height * 0.5f, 0.01f)

        val lifted = computeSegmentPillFrame(
            canvasWidth = 624f,
            canvasHeight = 168f,
            bleedPx = 12f,
            paddingPx = 6f,
            gapPx = 6f,
            count = 3,
            position = 1f,
            velocitySegmentsPerSec = 6f,
            lift = 1f,
            lens = 1f,
            isDark = false,
        )
        assertTrue(lifted.width > rest.width)
        assertTrue(lifted.height > rest.height)
        assertTrue(lifted.fillAlpha < 0.15f)
        assertEquals(1f, lifted.lensMix, 0.001f)
        assertTrue(lifted.left >= 0f && lifted.left + lifted.width <= 624f)
        assertTrue(lifted.top >= 0f && lifted.top + lifted.height <= 168f)
    }

    @Test fun computeSegmentPillFramePreservesFullCapsuleOnHoldAndSqueezesOnFastDrag() {
        val rest = computeSegmentPillFrame(
            canvasWidth = 624f,
            canvasHeight = 192f,
            bleedPx = 24f,
            paddingPx = 6f,
            gapPx = 6f,
            count = 3,
            position = 1f,
            velocitySegmentsPerSec = 0f,
            lift = 0f,
            lens = 0f,
            isDark = false,
        )
        val heldFull = computeSegmentPillFrame(
            canvasWidth = 624f,
            canvasHeight = 192f,
            bleedPx = 24f,
            paddingPx = 6f,
            gapPx = 6f,
            count = 3,
            position = 1f,
            velocitySegmentsPerSec = 0f,
            lift = 1f,
            lens = 1f,
            isDark = false,
            dragSqueeze = 0f,
            liftY = 1f,
        )
        assertTrue(heldFull.width > rest.width * 1.15f)
        assertTrue(heldFull.height > rest.height * 1.25f)

        val squeezed = computeSegmentPillFrame(
            canvasWidth = 624f,
            canvasHeight = 192f,
            bleedPx = 24f,
            paddingPx = 6f,
            gapPx = 6f,
            count = 3,
            position = 1f,
            velocitySegmentsPerSec = 5f,
            lift = 1f,
            lens = 1f,
            isDark = false,
            dragSqueeze = 1f,
            liftY = 1f,
        )
        assertTrue(squeezed.width < heldFull.width)
        assertTrue(squeezed.width >= rest.width * 0.76f - 0.1f)
        assertTrue(squeezed.height >= heldFull.height)

        val xOnly = computeSegmentPillFrame(
            canvasWidth = 624f,
            canvasHeight = 192f,
            bleedPx = 24f,
            paddingPx = 6f,
            gapPx = 6f,
            count = 3,
            position = 1f,
            velocitySegmentsPerSec = 0f,
            lift = 0.8f,
            lens = 0.8f,
            isDark = true,
            dragSqueeze = 0f,
            liftY = 0.2f,
        )
        val yOnly = computeSegmentPillFrame(
            canvasWidth = 624f,
            canvasHeight = 192f,
            bleedPx = 24f,
            paddingPx = 6f,
            gapPx = 6f,
            count = 3,
            position = 1f,
            velocitySegmentsPerSec = 0f,
            lift = 0.2f,
            lens = 0.8f,
            isDark = true,
            dragSqueeze = 0f,
            liftY = 0.8f,
        )
        assertTrue(xOnly.width > yOnly.width)
        assertTrue(yOnly.height > xOnly.height)
    }
}
