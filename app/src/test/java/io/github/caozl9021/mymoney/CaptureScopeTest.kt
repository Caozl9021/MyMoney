package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.CaptureScope
import com.example.billkit.Packages
import org.junit.Assert.*
import org.junit.Test

class CaptureScopeTest {
    @Test fun legacyLibraryWhitelistDoesNotExpandSelfCapture() {
        val effective = CaptureScope.filterPackages(Packages.defaultWhitelist)
        assertEquals(setOf(Packages.WECHAT, Packages.ALIPAY), effective)
        assertFalse(CaptureScope.supports(Packages.MYBANK))
        assertFalse(CaptureScope.supports(Packages.UNIONPAY))
        assertFalse(CaptureScope.supports("com.android.phone"))
    }

    @Test fun customAliasAndUnrelatedAppsCannotBecomeAcceptedByFiltering() {
        val configured = setOf("com.tencent.mm.clone", "com.eg.android.AlipayGphone.clone", "com.jingdong.app.mall", "")
        assertTrue(CaptureScope.filterPackages(configured).isEmpty())
        assertFalse(CaptureScope.supports("COM.TENCENT.MM"))
    }

    @Test fun explicitEmptyScopeStaysEmptyRatherThanReactivatingDefaults() {
        assertTrue(CaptureScope.filterPackages(emptySet()).isEmpty())
        assertEquals(setOf(Packages.ALIPAY), CaptureScope.filterPackages(setOf(Packages.ALIPAY, "com.android.phone")))
    }
}
