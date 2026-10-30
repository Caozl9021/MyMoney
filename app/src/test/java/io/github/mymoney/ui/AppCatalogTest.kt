package io.github.mymoney.ui

import com.example.billkit.Packages
import io.github.mymoney.data.CaptureScope
import org.junit.Assert.*
import org.junit.Test

class AppCatalogTest {
    @Test fun everyDefaultWhitelistPackageHasALabel() {
        // 预设清单里的包名必须有显示名，否则应用清单会出现裸包名。
        CaptureScope.packages.forEach { pkg ->
            assertTrue("缺少显示名：$pkg", appLabel(pkg) != pkg)
        }
    }

    @Test fun labelsAreShortEnoughToStayOnOneLine() {
        // 真机教训：标题里拼上包名会折成三行，而副标题本来就写着包名。
        knownPackages.forEach { pkg ->
            val label = appLabel(pkg)
            assertFalse("显示名不应包含包名：$label", label.contains(pkg))
            assertTrue("显示名过长：$label", label.length <= 8)
            assertFalse(label.contains("\n"))
        }
    }

    @Test fun everyPackageGetsItsOwnLabel() {
        // 京东/美团/淘宝/抖音系的每个包名各有独立短名，不会出现两行同名分不清。
        listOf(Packages.jdGroup, Packages.meituanGroup, Packages.taobaoGroup, Packages.douyinGroup).forEach { group ->
            val labels = group.map { appLabel(it) }
            assertEquals(labels.toSet().size, labels.size)
        }
        assertEquals("京东金融", appLabel(Packages.JD_FINANCE))
        assertEquals("大众点评", appLabel(Packages.DIANPING))
        assertEquals("抖音极速版", appLabel(Packages.DOUYIN_LITE))
    }

    @Test fun selfCatalogKeepsWeChatThenAlipayOnly() {
        assertEquals(listOf(Packages.WECHAT, Packages.ALIPAY), appPackages(emptySet()))
    }

    @Test fun manuallyAddedPackageKeepsItsOwnLabel() {
        assertEquals("com.example.custom", appLabel("com.example.custom"))
    }

    @Test fun legacyAndCustomWhitelistEntriesDoNotExposeOtherSources() {
        val packages = appPackages(Packages.defaultWhitelist + "com.example.custom")
        assertEquals(listOf(Packages.WECHAT, Packages.ALIPAY), packages)
        assertFalse(packages.contains(Packages.JD))
        assertFalse(packages.contains("com.example.custom"))
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test fun catalogNeverReadsInstalledApps() {
        // 目录只来自预设与本地白名单：给一个空白名单，结果仍只含预设包名。
        val packages = appPackages(emptySet())
        assertEquals(knownPackages, packages)
        assertEquals(CaptureScope.packages, packages.toSet())
    }
}
