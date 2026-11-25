package io.github.mymoney.systems

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import io.github.mymoney.R

/**
 * 桌面速记小组件：麦穗品牌图标 + 主题色加号，全区域可点击弹出透明确认卡手记。
 * 支持 1×1 / 1×2 / 2×1 / 2×2 紧凑比例。
 */
class QuickEntryWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SystemIntegration.ACTION_REFRESH_WIDGET,
            Intent.ACTION_MY_PACKAGE_REPLACED -> refreshAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshIds(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        refreshIds(context, manager, intArrayOf(appWidgetId))
    }

    override fun onEnabled(context: Context) {
        refreshAll(context)
    }

    companion object {
        private val SUPPORTED = setOf(
            WidgetSizeBucket.MINI_ICON,
            WidgetSizeBucket.STRIP_VERTICAL,
            WidgetSizeBucket.STRIP_HORIZONTAL,
            WidgetSizeBucket.SQUARE_COMPACT,
        )

        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, QuickEntryWidget::class.java))
            if (ids.isNotEmpty()) refreshIds(app, manager, ids)
        }

        private fun refreshIds(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val surface = WidgetThemeTokens.defaultSurfaceStyle(context)
            val palette = WidgetThemeTokens.palette(context, surface)
            val clickPending = PendingIntent.getActivity(
                context,
                9201,
                SystemIntegration.quickAddIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            for (id in ids) {
                val views = WidgetResponsive.build(
                    manager = manager,
                    appWidgetId = id,
                    supported = SUPPORTED,
                    fallback = WidgetSizeBucket.STRIP_HORIZONTAL,
                ) { bucket, _ ->
                    val layoutId = when (bucket) {
                        WidgetSizeBucket.MINI_ICON -> R.layout.widget_quick_entry_mini
                        WidgetSizeBucket.STRIP_VERTICAL -> R.layout.widget_quick_entry_vertical
                        WidgetSizeBucket.SQUARE_COMPACT -> R.layout.widget_quick_entry_square
                        else -> R.layout.widget_quick_entry_horizontal
                    }
                    RemoteViews(context.packageName, layoutId).apply {
                        WidgetThemeTokens.applySurface(this, surface)
                        WidgetThemeTokens.tintImage(this, R.id.widget_quick_add_bg, palette.accent)
                        WidgetThemeTokens.tintImage(this, R.id.widget_quick_add_icon, palette.onAccent)
                        if (bucket == WidgetSizeBucket.SQUARE_COMPACT) {
                            setTextColor(R.id.widget_quick_label, palette.onAccent)
                        }
                        setOnClickPendingIntent(R.id.widget_root, clickPending)
                    }
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
