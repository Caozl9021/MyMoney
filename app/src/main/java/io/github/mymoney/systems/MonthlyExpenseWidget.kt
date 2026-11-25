package io.github.mymoney.systems

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import java.util.Locale

/** Native RemoteViews: monthly original expense and the existing manual-entry action. */
class MonthlyExpenseWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SystemIntegration.ACTION_REFRESH_WIDGET, SystemIntegration.ACTION_WIDGET_DAY,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> refreshAsync(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(context)
    }

    override fun onEnabled(context: Context) = refreshAsync(context)

    override fun onDisabled(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(dayIntent(context)) }
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                updateLock.withLock {
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, MonthlyExpenseWidget::class.java))
                if (ids.isEmpty()) return@withLock
                val zone = ZoneId.systemDefault()
                val now = Instant.now()
                val month = SystemEntryPolicy.monthWindow(now, zone, io.github.mymoney.data.LedgerPeriodStore.read(app))
                val amount = runCatching {
                    val rows = AppDb.get(app).bills().range(month.from, month.to)
                    val cents = SystemEntryPolicy.expenseCents(rows, month)
                    String.format(Locale.US, "¥%,d.%02d", cents / 100, cents % 100)
                }.getOrElse { "暂不可用" }
                val surface = WidgetThemeTokens.defaultSurfaceStyle(app)
                val palette = WidgetThemeTokens.palette(app, surface)
                val openHomePending = PendingIntent.getActivity(
                    app,
                    9100,
                    Intent(app, io.github.mymoney.ui.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val quickAddPending = PendingIntent.getActivity(
                    app,
                    9101,
                    SystemIntegration.quickAddIntent(app),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val views = RemoteViews(app.packageName, R.layout.widget_monthly_expense).apply {
                    WidgetThemeTokens.applySurface(this, surface)
                    setTextViewText(R.id.widget_month, "本期支出")
                    setTextColor(R.id.widget_month, palette.secondaryText)
                    setTextViewText(R.id.widget_amount, amount)
                    setTextColor(R.id.widget_amount, palette.primaryText)
                    WidgetThemeTokens.tintImage(this, R.id.widget_add, palette.secondaryText)
                    setContentDescription(R.id.widget_amount, "${month.rangeLabel}原支出 $amount，退款单独计算")
                    setOnClickPendingIntent(R.id.widget_root, openHomePending)
                    setOnClickPendingIntent(R.id.widget_add, quickAddPending)
                }
                manager.updateAppWidget(ids, views)
                // No exact-alarm permission: the midnight refresh may be deferred by Doze.
                // Host's 30-minute update and date/time broadcasts provide additional refreshes.
                val nextDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
                    .toInstant().toEpochMilli()
                app.getSystemService(AlarmManager::class.java).setWindow(
                    AlarmManager.RTC, nextDay, 15 * 60 * 1000L, dayIntent(app),
                )
                }
            } catch (_: Exception) {
                // Host/alarms can disappear while updating. Never break a committed ledger write.
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }

    companion object {
        private val updateLock = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun dayIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 9103,
            Intent(context, MonthlyExpenseWidget::class.java).setAction(SystemIntegration.ACTION_WIDGET_DAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
