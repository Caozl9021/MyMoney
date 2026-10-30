package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.capture.AccessibilityEnhancer
import io.github.caozl9021.mymoney.capture.AccessibilityEnhancer.Manufacturer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只能测纯逻辑部分：厂商识别与引导清单。
 * 状态读取（电池优化/通知/无障碍）依赖 Android 框架，放到真机验收。
 */
class AccessibilityEnhancerTest {

    @Test
    fun `brand mapping covers the common Chinese manufacturers`() {
        assertEquals(Manufacturer.XIAOMI, Manufacturer.fromBrand("Xiaomi"))
        assertEquals(Manufacturer.REDMI, Manufacturer.fromBrand("Redmi"))
        assertEquals(Manufacturer.HUAWEI, Manufacturer.fromBrand("HUAWEI"))
        assertEquals(Manufacturer.HONOR, Manufacturer.fromBrand("HONOR"))
        assertEquals(Manufacturer.OPPO, Manufacturer.fromBrand("OPPO"))
        assertEquals(Manufacturer.VIVO, Manufacturer.fromBrand("vivo"))
        assertEquals(Manufacturer.REALME, Manufacturer.fromBrand("realme"))
        assertEquals(Manufacturer.ONEPLUS, Manufacturer.fromBrand("OnePlus"))
        assertEquals(Manufacturer.SAMSUNG, Manufacturer.fromBrand("samsung"))
        assertEquals(Manufacturer.GOOGLE, Manufacturer.fromBrand("google"))
    }

    @Test
    fun `unknown or empty brand falls back to OTHER`() {
        assertEquals(Manufacturer.OTHER, Manufacturer.fromBrand(""))
        assertEquals(Manufacturer.OTHER, Manufacturer.fromBrand("SomeOem"))
    }

    @Test
    fun `brand match is case insensitive`() {
        assertEquals(Manufacturer.XIAOMI, Manufacturer.fromBrand("xIaOmI"))
    }

    @Test
    fun `manufacturers with special autostart settings are flagged`() {
        assertTrue(Manufacturer.XIAOMI.hasSpecialSettings)
        assertTrue(Manufacturer.HUAWEI.hasSpecialSettings)
        assertTrue(Manufacturer.OPPO.hasSpecialSettings)
        assertTrue(Manufacturer.VIVO.hasSpecialSettings)
        // 原生/三星没有厂商自启动白名单页
        assertFalse(Manufacturer.GOOGLE.hasSpecialSettings)
        assertFalse(Manufacturer.SAMSUNG.hasSpecialSettings)
        assertFalse(Manufacturer.OTHER.hasSpecialSettings)
    }

    @Test
    fun `every manufacturer gets a non empty guide`() {
        for (manufacturer in Manufacturer.entries) {
            val guide = AccessibilityEnhancer.optimizationGuide(manufacturer)
            assertTrue("$manufacturer 的引导清单不能为空", guide.isNotEmpty())
            assertTrue("$manufacturer 的清单条目不能是空白", guide.all { it.isNotBlank() })
        }
    }

    @Test
    fun `keep alive status requires all three real checks`() {
        val status = AccessibilityEnhancer.KeepAliveStatus(
            batteryOptimizationIgnored = true,
            notificationsEnabled = true,
            accessibilityChecked = true,
            accessibilityEnabled = true,
            guardianRunning = false,
            manufacturer = Manufacturer.OTHER,
        )
        assertTrue(status.isFullyOptimized)

        assertFalse(status.copy(batteryOptimizationIgnored = false).isFullyOptimized)
        assertFalse(status.copy(notificationsEnabled = false).isFullyOptimized)
        assertFalse(status.copy(accessibilityEnabled = false).isFullyOptimized)
        // 守护服务没跑不影响"权限侧是否就绪"，它自己会在 UI 上单独显示
        assertTrue(status.copy(guardianRunning = false).isFullyOptimized)
    }
}
