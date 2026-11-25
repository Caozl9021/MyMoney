package io.github.mymoney.systems

import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.mymoney.ui.MainActivity
import io.github.mymoney.ui.QuickAddActivity

/** Hooks for successful ledger mutations, mode changes and application startup. */
object SystemIntegration {
    const val ACTION_ADD_BILL = "io.github.mymoney.action.ADD_BILL"
    const val ACTION_QUICK_ADD = "io.github.mymoney.action.QUICK_ADD"
    const val ACTION_OPEN_STATS = "io.github.mymoney.action.OPEN_STATS"
    const val ACTION_OPEN_ASSETS = "io.github.mymoney.action.OPEN_ASSETS"
    const val ACTION_OPEN_PENDING = "io.github.mymoney.action.OPEN_PENDING"
    internal const val ACTION_REFRESH_WIDGET = "io.github.mymoney.action.REFRESH_WIDGET"
    internal const val ACTION_WIDGET_DAY = "io.github.mymoney.action.WIDGET_DAY"
    internal const val ACTION_WIDGET_TOGGLE_MASK = "io.github.mymoney.action.WIDGET_TOGGLE_MASK"
    private var initialized = false
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh(context)
    }

    private val widgetProviders: List<Class<out AppWidgetProvider>> = listOf(
        MonthlyExpenseWidget::class.java,
        QuickEntryWidget::class.java,
        SevenDayExpenseWidget::class.java,
        AssetReimburseWidget::class.java,
    )

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        runCatching {
            ContextCompat.registerReceiver(app, dateReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        refresh(app)
    }

    /** Call after committed insert/update/delete/undo/restore or appearance updates, never before a DB write. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        for (provider in widgetProviders) {
            runCatching {
                app.sendBroadcast(Intent(app, provider).setAction(ACTION_REFRESH_WIDGET))
            }
        }
        refreshTile(context)
    }

    /** CaptureModeManager.reconcile can call this without querying the ledger. */
    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, RecognitionTileService::class.java))
        }
    }

    fun addBillIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_ADD_BILL)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun quickAddIntent(context: Context): Intent = Intent(context, QuickAddActivity::class.java)
        .setAction(ACTION_QUICK_ADD)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openStatsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_STATS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openAssetsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_ASSETS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openPendingIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_PENDING)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
