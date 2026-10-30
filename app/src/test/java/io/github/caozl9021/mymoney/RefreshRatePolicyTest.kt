package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.ui.RefreshRatePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRatePolicyTest {
    private val current = mode(7, 60f)
    private fun mode(id: Int, hz: Float, width: Int = 1200, height: Int = 2670) =
        RefreshRatePolicy.Mode(id, width, height, hz)

    @Test fun `modern rate hint selects fastest current resolution without pinning a mode`() {
        val result = RefreshRatePolicy.request(37, current, listOf(current, mode(12, 90f), mode(42, 120f)))
        assertEquals(RefreshRatePolicy.Request(refreshRate = 120f), result)
    }

    @Test fun `legacy mode request uses discovered id and preserves resolution`() {
        for (api in listOf(26, 30, 33)) {
            val result = RefreshRatePolicy.request(api, current, listOf(current, mode(93, 90f), mode(25, 144f, 1080, 2400)))
            assertEquals(RefreshRatePolicy.Request(modeId = 93), result)
        }
    }

    @Test fun `rate only request begins at API34`() {
        assertEquals(RefreshRatePolicy.Request(refreshRate = 90f),
            RefreshRatePolicy.request(34, current, listOf(mode(93, 90f))))
    }

    @Test fun `ordinary 60Hz and fractional timing noise retain system default`() {
        assertEquals(RefreshRatePolicy.Request(),
            RefreshRatePolicy.request(37, current, listOf(current, mode(8, 59.94f), mode(9, 60.000004f))))
    }

    @Test fun `high refresh in another resolution does not change resolution`() {
        assertEquals(RefreshRatePolicy.Request(),
            RefreshRatePolicy.request(33, current, listOf(current, mode(5, 120f, 1440, 3200))))
    }

    @Test fun `malformed and empty modes are ignored`() {
        for (api in listOf(26, 37)) {
            assertEquals(RefreshRatePolicy.Request(), RefreshRatePolicy.request(api, current, emptyList()))
            assertEquals(RefreshRatePolicy.Request(), RefreshRatePolicy.request(api, current,
                listOf(mode(0, 120f), mode(2, Float.NaN), mode(3, Float.POSITIVE_INFINITY), mode(4, -120f))))
            assertEquals(RefreshRatePolicy.Request(), RefreshRatePolicy.request(api,
                mode(1, 120f, width = 0), listOf(mode(1, 120f, width = 0))))
        }
    }

    @Test fun `equal rates retain current mode on legacy devices`() {
        val alreadyFast = mode(89, 120f)
        assertEquals(RefreshRatePolicy.Request(modeId = 89),
            RefreshRatePolicy.request(26, alreadyFast, listOf(mode(1, 120f), alreadyFast)))
    }

    @Test fun `no 120Hz assumption on 90Hz and 144Hz displays`() {
        for (hz in listOf(90f, 144f)) {
            assertEquals(RefreshRatePolicy.Request(refreshRate = hz),
                RefreshRatePolicy.request(37, current, listOf(current, mode(67, hz))))
        }
    }

    @Test fun `ignored rate hint falls back to discovered matching mode`() {
        assertEquals(RefreshRatePolicy.Request(modeId = 93),
            RefreshRatePolicy.modeFallback(mode(93, 120f), current, 60f))
        assertEquals(RefreshRatePolicy.Request(modeId = 87),
            RefreshRatePolicy.modeFallback(mode(87, 144f), current, 90f))
    }

    @Test fun `effective rate hint and fractional timing tolerance need no fallback`() {
        for (observed in listOf(120f, 119.88f, 144f)) {
            assertEquals(RefreshRatePolicy.Request(),
                RefreshRatePolicy.modeFallback(mode(93, 120f), current, observed))
        }
    }

    @Test fun `resolution change during delay cancels mode fallback`() {
        assertEquals(RefreshRatePolicy.Request(), RefreshRatePolicy.modeFallback(
            mode(93, 120f), mode(19, 60f, 1080, 2400), 60f))
    }

    @Test fun `invalid observations and ordinary displays cannot trigger fallback`() {
        for (observed in listOf(0f, -60f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(RefreshRatePolicy.Request(),
                RefreshRatePolicy.modeFallback(mode(93, 120f), current, observed))
        }
        assertEquals(RefreshRatePolicy.Request(), RefreshRatePolicy.modeFallback(current, current, 30f))
        assertEquals(RefreshRatePolicy.Request(), RefreshRatePolicy.modeFallback(mode(0, 120f), current, 60f))
    }
}
