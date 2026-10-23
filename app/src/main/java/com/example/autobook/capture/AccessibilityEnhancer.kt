package com.example.autobook.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationManagerCompat

/**
 * 无障碍保活的"体检 + 引导"工具。**只做能真实做到的事**：
 *
 * - 读**真实**状态：电池优化是否豁免、通知是否允许、无障碍是否启用、守护服务是否在跑；
 * - 一键跳到**系统**对应的设置页（电池优化、厂商自启动），让用户自己完成；
 * - 给出按厂商定制的检查清单。
 *
 * 它**不能**（也曾经假装能）：
 * - 自动把无障碍重新打开 —— 普通应用没有这个权限；
 * - 检测厂商"自启动"开关的真实状态 —— 那没有公开 API，只能引导；
 * - 重启/修复服务 —— 旧版的 `attemptAutoRepair()` 只在主线程 sleep 500ms 然后 `return false`，
 *   纯属摆设，已删除。真正有用的是 [AccessibilityGuardian] 的失效提醒。
 */
object AccessibilityEnhancer {

    /** 厂商 */
    enum class Manufacturer(val brand: String, val hasSpecialSettings: Boolean) {
        XIAOMI("xiaomi", true),
        REDMI("redmi", true),
        HUAWEI("huawei", true),
        HONOR("honor", true),
        OPPO("oppo", true),
        VIVO("vivo", true),
        REALME("realme", true),
        ONEPLUS("oneplus", true),
        SAMSUNG("samsung", false),
        GOOGLE("google", false),
        OTHER("", false);

        companion object {
            /** 纯函数，方便单测；`Build.BRAND` 在 JVM 单测里是 null，所以调用方传空串。 */
            fun fromBrand(brand: String): Manufacturer {
                val normalized = brand.lowercase()
                return Manufacturer.entries.firstOrNull {
                    it.brand.isNotEmpty() && normalized.contains(it.brand)
                } ?: OTHER
            }

            fun detect(): Manufacturer = fromBrand(Build.BRAND.orEmpty())
        }
    }

    /** 保活体检结果。只有这里列出的字段是**实测**的，其余一律不猜。 */
    data class KeepAliveStatus(
        val batteryOptimizationIgnored: Boolean,
        val notificationsEnabled: Boolean,
        /** 系统设置里勾着（可能勾着没连上） */
        val accessibilityChecked: Boolean,
        /** 服务真的连着 */
        val accessibilityEnabled: Boolean,
        val guardianRunning: Boolean,
        val manufacturer: Manufacturer,
    ) {
        /** 能做的都做了（自启动开关没有 API，不在判定里） */
        val isFullyOptimized: Boolean
            get() = batteryOptimizationIgnored && notificationsEnabled && accessibilityEnabled
    }

    fun getStatus(context: Context): KeepAliveStatus = KeepAliveStatus(
        batteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        accessibilityChecked = AccessibilityGuardian.isEnabledInSettings(context),
        accessibilityEnabled = AccessibilityGuardian.isAccessibilityEnabled(context),
        guardianRunning = AccessibilityGuardian.running,
        manufacturer = Manufacturer.detect(),
    )

    // -------------------- 电池优化 --------------------

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求忽略电池优化。优先弹系统对话框（需要 Manifest 声明
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），拿不到就退回电池优化列表页。
     */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent {
        val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (context.packageManager.resolveActivity(direct, 0) != null) return direct
        return Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    // -------------------- 厂商自启动白名单 --------------------

    /**
     * 跳厂商自启动管理页。这些是经验 ComponentName，不同 ROM 版本可能失效，
     * 所以**必须**用 `resolveActivity` 过滤，不可解析就返回 null（调用方退回清单提示）。
     */
    fun autoStartIntent(manufacturer: Manufacturer): Intent? {
        val intent = when (manufacturer) {
            Manufacturer.XIAOMI, Manufacturer.REDMI -> Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            }
            Manufacturer.HUAWEI, Manufacturer.HONOR -> Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
            }
            Manufacturer.OPPO -> Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                )
            }
            Manufacturer.VIVO -> Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                )
            }
            Manufacturer.ONEPLUS -> Intent().apply {
                component = android.content.ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                )
            }
            else -> null
        }
        return intent
    }

    /** 能解析才返回，否则 null。 */
    fun resolveAutoStartIntent(context: Context, manufacturer: Manufacturer): Intent? =
        autoStartIntent(manufacturer)?.takeIf {
            context.packageManager.resolveActivity(it, 0) != null
        }

    /** 按厂商给的检查清单（纯文本，不含任何"已开启"的断言）。 */
    fun optimizationGuide(manufacturer: Manufacturer): List<String> = when (manufacturer) {
        Manufacturer.XIAOMI, Manufacturer.REDMI -> listOf(
            "电池与性能 → 关闭省电模式",
            "应用设置 → 应用管理 → AutoBook → 自启动：开启",
            "应用管理 → AutoBook → 省电策略：无限制",
            "最近任务里下拉锁定 AutoBook",
        )
        Manufacturer.HUAWEI, Manufacturer.HONOR -> listOf(
            "应用 → 应用启动管理 → AutoBook：关闭「自动管理」，全部允许",
            "电池 → 更多电池设置 → 允许后台活动",
            "最近任务里下拉锁定 AutoBook",
        )
        Manufacturer.OPPO -> listOf(
            "电池 → 应用电量管理 → AutoBook：允许后台运行",
            "应用管理 → AutoBook：允许自启动、允许关联启动",
            "最近任务里下拉锁定 AutoBook",
        )
        Manufacturer.VIVO -> listOf(
            "i 管家 → 应用管理 → 自启动：开启 AutoBook",
            "电池 → 后台高耗电：允许 AutoBook",
            "最近任务里下拉锁定 AutoBook",
        )
        Manufacturer.SAMSUNG -> listOf(
            "电池与设备维护 → 电池 → 后台使用限制",
            "把 AutoBook 加入「从不休眠的应用」",
        )
        else -> listOf(
            "关闭电池优化",
            "允许后台运行",
            "最近任务里下拉锁定 AutoBook",
        )
    }
}
