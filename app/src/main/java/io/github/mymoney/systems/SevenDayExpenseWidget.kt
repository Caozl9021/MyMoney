package io.github.mymoney.systems

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.RemoteViews
import io.github.mymoney.R
import io.github.mymoney.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * 七日支出柱状图桌面小组件：
 * Apple 健康全高圆角轨道 + 主题色柱体（今日高饱和、其余活跃日柔和同色、零消费展示淡胶囊轨道）。
 */
class SevenDayExpenseWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SystemIntegration.ACTION_WIDGET_TOGGLE_MASK -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetPrefs.toggleMasked(WidgetPrefs.prefs(context), id)
                    refreshAsync(context, intArrayOf(id))
                } else {
                    refreshAsync(context)
                }
            }
            SystemIntegration.ACTION_REFRESH_WIDGET,
            SystemIntegration.ACTION_WIDGET_DAY,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> refreshAsync(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        refreshAsync(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetPrefs.delete(WidgetPrefs.prefs(context), appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        refreshAsync(context)
    }

    override fun onDisabled(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(dayIntent(context)) }
    }

    private fun refreshAsync(context: Context, targetIds: IntArray? = null) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                updateLock.withLock {
                    val manager = AppWidgetManager.getInstance(app)
                    val ids = targetIds?.takeIf { it.isNotEmpty() }
                        ?: manager.getAppWidgetIds(ComponentName(app, SevenDayExpenseWidget::class.java))
                    if (ids.isEmpty()) return@withLock

                    val zone = ZoneId.systemDefault()
                    val now = Instant.now()
                    val (from, to) = WidgetPolicy.sevenDayWindow(now, zone)
                    val summary = runCatching {
                        val rows = AppDb.get(app).bills().range(from, to)
                        WidgetPolicy.sevenDaySummary(rows, now, zone)
                    }.getOrElse {
                        WidgetPolicy.sevenDaySummary(emptyList(), now, zone)
                    }

                    val surface = WidgetThemeTokens.defaultSurfaceStyle(app)
                    val palette = WidgetThemeTokens.palette(app, surface)
                    val prefs = WidgetPrefs.prefs(app)
                    val openStatsPending = PendingIntent.getActivity(
                        app,
                        9210,
                        SystemIntegration.openStatsIntent(app),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val subPillRes = when (surface) {
                        WidgetSurfaceStyle.GLASS_DARK -> R.drawable.widget_pill_glass_dark
                        WidgetSurfaceStyle.GLASS_LIGHT -> R.drawable.widget_pill_glass_light
                        WidgetSurfaceStyle.SOLID -> R.drawable.widget_pill_solid
                    }

                    for (id in ids) {
                        val masked = WidgetPrefs.isMasked(prefs, id)
                        val totalText = WidgetPolicy.formatCurrency(summary.totalCents, masked = masked)
                        val avgText = WidgetPolicy.formatCurrency(summary.averageCents, masked = masked)
                        val todayText = if (summary.todayCents > 0L || masked) {
                            WidgetPolicy.formatCurrency(summary.todayCents, masked = masked)
                        } else {
                            "今日未消费"
                        }
                        val subCompact = "日均 $avgText"
                        val subFull = if (todayText == "今日未消费") "日均 $avgText · 今日未消费" else "日均 $avgText · 今日 $todayText"
                        val maskPending = PendingIntent.getBroadcast(
                            app,
                            9220 + (id and 0x0FFF),
                            Intent(app, SevenDayExpenseWidget::class.java)
                                .setAction(SystemIntegration.ACTION_WIDGET_TOGGLE_MASK)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .setData(Uri.parse("mymoney://widget/sevenday/$id/mask")),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )

                        val views = WidgetResponsive.build(
                            manager = manager,
                            appWidgetId = id,
                            supported = SUPPORTED,
                            fallback = WidgetSizeBucket.WIDE_MEDIUM,
                        ) { bucket, size ->
                            val layoutId = when (bucket) {
                                WidgetSizeBucket.STRIP_HORIZONTAL -> R.layout.widget_seven_day_strip
                                WidgetSizeBucket.SQUARE_COMPACT -> R.layout.widget_seven_day_compact
                                WidgetSizeBucket.TALL_CARD -> R.layout.widget_seven_day_tall
                                else -> R.layout.widget_seven_day
                            }
                            val chart = WidgetChartRenderer.renderSevenDayChart(
                                context = app,
                                summary = summary,
                                palette = palette,
                                bucket = bucket,
                                size = size,
                                masked = masked,
                            )
                            RemoteViews(app.packageName, layoutId).apply {
                                WidgetThemeTokens.applySurface(this, surface)
                                setImageViewBitmap(R.id.widget_seven_chart, chart)
                                setContentDescription(R.id.widget_root, "近 7 日支出 $totalText，轻点进入统计")
                                setOnClickPendingIntent(R.id.widget_root, openStatsPending)

                                if (bucket != WidgetSizeBucket.STRIP_HORIZONTAL) {
                                    setTextViewText(R.id.widget_seven_total, totalText)
                                    setTextColor(R.id.widget_seven_total, palette.primaryText)
                                    setTextColor(R.id.widget_seven_title, palette.secondaryText)
                                    setImageViewResource(
                                        R.id.widget_eye,
                                        if (masked) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye_on,
                                    )
                                    WidgetThemeTokens.tintImage(this, R.id.widget_eye, palette.secondaryText)
                                    setContentDescription(R.id.widget_eye, if (masked) "显示金额" else "隐藏金额")
                                    setOnClickPendingIntent(R.id.widget_eye, maskPending)

                                    if (bucket != WidgetSizeBucket.SQUARE_COMPACT) {
                                        val sub = if (bucket == WidgetSizeBucket.WIDE_MEDIUM) subCompact else subFull
                                        setTextViewText(R.id.widget_seven_sub, sub)
                                        setTextColor(R.id.widget_seven_sub, palette.secondaryText)
                                        setInt(R.id.widget_seven_sub, "setBackgroundResource", subPillRes)
                                        DAY_VIEW_IDS.forEachIndexed { index, viewId ->
                                            val bar = summary.bars.getOrNull(index) ?: return@forEachIndexed
                                            val label: CharSequence = if (bar.isToday) {
                                                SpannableString(bar.label).apply {
                                                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                                }
                                            } else {
                                                bar.label
                                            }
                                            setTextViewText(viewId, label)
                                            setTextColor(viewId, if (bar.isToday) palette.accent else palette.secondaryText)
                                        }
                                    }
                                }
                            }
                        }
                        manager.updateAppWidget(id, views)
                    }

                    val nextDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
                        .toInstant().toEpochMilli()
                    app.getSystemService(AlarmManager::class.java).setWindow(
                        AlarmManager.RTC, nextDay, 15 * 60 * 1000L, dayIntent(app),
                    )
                }
            } catch (_: Exception) {
                // Never break background lifecycle if launcher drops a widget during update.
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }

    companion object {
        private val SUPPORTED = setOf(
            WidgetSizeBucket.STRIP_HORIZONTAL,
            WidgetSizeBucket.SQUARE_COMPACT,
            WidgetSizeBucket.TALL_CARD,
            WidgetSizeBucket.WIDE_MEDIUM,
            WidgetSizeBucket.WIDE_FULL,
        )
        private val DAY_VIEW_IDS = intArrayOf(
            R.id.widget_day_0,
            R.id.widget_day_1,
            R.id.widget_day_2,
            R.id.widget_day_3,
            R.id.widget_day_4,
            R.id.widget_day_5,
            R.id.widget_day_6,
        )
        private val updateLock = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun dayIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            9219,
            Intent(context, SevenDayExpenseWidget::class.java).setAction(SystemIntegration.ACTION_WIDGET_DAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
