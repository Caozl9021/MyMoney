package io.github.caozl9021.mymoney.systems

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.caozl9021.mymoney.ui.MainActivity

/** Hooks for successful ledger mutations, mode changes and application startup. */
object SystemIntegration {
    const val ACTION_ADD_BILL = "io.github.caozl9021.mymoney.action.ADD_BILL"
    internal const val ACTION_REFRESH_WIDGET = "io.github.caozl9021.mymoney.action.REFRESH_WIDGET"
    internal const val ACTION_WIDGET_DAY = "io.github.caozl9021.mymoney.action.WIDGET_DAY"
    private var initialized = false
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh(context)
    }

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

    /** Call after committed insert/update/delete/undo/restore, never before a DB write. */
    fun refresh(context: Context) {
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(context, MonthlyExpenseWidget::class.java).setAction(ACTION_REFRESH_WIDGET),
            )
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
}
