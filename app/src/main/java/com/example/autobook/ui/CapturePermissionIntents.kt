package com.example.autobook.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.autobook.capture.PaymentNotificationListener

/** Public settings only. Each failure falls through, including an exported Activity denial. */
internal fun notificationAccessIntents(context: Context): List<Intent> = buildList {
    if (Build.VERSION.SDK_INT >= 30) add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, PaymentNotificationListener::class.java).flattenToString()))
    add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

internal fun launchPermissionSettings(context: Context, intents: List<Intent>): Boolean {
    val candidates = intents + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))
    return candidates.any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
