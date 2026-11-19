package io.github.mymoney.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机或应用更新后恢复无障碍守护服务（仅当用户已开启自动记账时启动）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        CaptureModeManager.reconcile(context)
    }
}
