package io.github.mymoney.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍授权的判据：按组件语义解析，不比较字面量。
 *
 * 真机（LineageOS / Android 16）把已启用的服务写成短类名
 * `io.github.mymoney/.capture.BillAccessibilityService`，而服务其实已经绑定；
 * 旧实现拿「包名/全类名」做字符串全等，于是界面一直报「还差无障碍授权」。
 */
class AccessibilityServiceGateTest {
    private val pkg = "io.github.mymoney"
    private val cls = "io.github.mymoney.capture.BillAccessibilityService"

    @Test
    fun short_class_name_counts_as_enabled() {
        assertTrue(accessibilityServiceEnabled("$pkg/.capture.BillAccessibilityService", pkg, cls))
    }

    @Test
    fun full_class_name_counts_as_enabled() {
        assertTrue(accessibilityServiceEnabled("$pkg/$cls", pkg, cls))
    }

    @Test
    fun other_entries_in_the_list_do_not_hide_ours() {
        val list = "com.example.autobook/.capture.BillAccessibilityService" +
            ":li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService" +
            ":$pkg/.capture.BillAccessibilityService"
        assertTrue(accessibilityServiceEnabled(list, pkg, cls))
    }

    @Test
    fun same_class_in_another_package_is_not_ours() {
        assertFalse(accessibilityServiceEnabled("com.example.autobook/.capture.BillAccessibilityService", pkg, cls))
    }

    @Test
    fun another_service_of_the_same_package_is_not_ours() {
        assertFalse(accessibilityServiceEnabled("$pkg/.capture.OtherAccessibilityService", pkg, cls))
    }

    @Test
    fun empty_missing_or_malformed_lists_are_off() {
        assertFalse(accessibilityServiceEnabled(null, pkg, cls))
        assertFalse(accessibilityServiceEnabled("", pkg, cls))
        assertFalse(accessibilityServiceEnabled("$pkg/", pkg, cls))
        assertFalse(accessibilityServiceEnabled("/$cls", pkg, cls))
        assertFalse(accessibilityServiceEnabled("$pkg/.capture.BillAccessibilityService", pkg, cls + "X"))
    }
}
