package com.example.mymoney.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mymoney.capture.CaptureModeManager

/**
 * 开机后把 root 轮询拉回来。
 *
 * 前台服务本身不是开机自启的；用户既然把它设成主力采集方式，
 * 重启手机后就得继续工作，否则每次开机都要手动去设置页点一下。
 * 只在已选择 Root 模式且识别总开关开启时启动。
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
