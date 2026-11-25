package io.github.mymoney.systems

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import io.github.mymoney.R
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.LedgerPeriodStore
import io.github.mymoney.ui.WidgetConfigureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * 可配置资产 & 报销看板小组件：
 * 支持混合（净资产 + 待报销）/ 纯资产 / 纯报销三种模式、实底/磨砂样式、金额脱敏及 6 种尺寸自适应。
 */
class AssetReimburseWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SystemIntegration.ACTION_WIDGET_TOGGLE_MASK -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetPrefs.toggleMasked(WidgetPrefs.prefs(context), id)
                    refreshAsync(this, context, intArrayOf(id))
                } else {
                    refreshAsync(this, context)
                }
            }
            SystemIntegration.ACTION_REFRESH_WIDGET,
            SystemIntegration.ACTION_WIDGET_DAY,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> refreshAsync(this, context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(this, context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        refreshAsync(this, context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetPrefs.delete(WidgetPrefs.prefs(context), appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        refreshAsync(this, context)
    }

    override fun onDisabled(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(dayIntent(context)) }
    }

    companion object {
        private val SUPPORTED = WidgetSizeBucket.entries.toSet()
        private val updateLock = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshIds(context: Context, ids: IntArray) {
            refreshAsync(null, context, ids)
        }

        private fun refreshAsync(provider: AppWidgetProvider? = null, context: Context, targetIds: IntArray? = null) {
            val pending = provider?.goAsync()
            val app = context.applicationContext
            scope.launch {
                try {
                    updateLock.withLock {
                        val manager = AppWidgetManager.getInstance(app)
                        val ids = targetIds?.takeIf { it.isNotEmpty() }
                            ?: manager.getAppWidgetIds(ComponentName(app, AssetReimburseWidget::class.java))
                        if (ids.isEmpty()) return@withLock

                        val zone = ZoneId.systemDefault()
                        val now = Instant.now()
                        val month = SystemEntryPolicy.monthWindow(now, zone, LedgerPeriodStore.read(app))
                        val snapshot = runCatching {
                            val db = AppDb.get(app)
                            val accounts = db.assets().accounts()
                            val entries = db.assets().entries()
                            val pendingReimburse = db.bills().pendingReimbursement()
                            val rows = db.bills().range(month.from, month.to)
                            val monthExpense = SystemEntryPolicy.expenseCents(rows, month)
                            val monthLabel = if (month.startDay == 1) "本月原支出" else "本期原支出"
                            WidgetPolicy.buildAssetSnapshot(
                                accounts = accounts,
                                entries = entries,
                                pendingCount = pendingReimburse.count,
                                pendingCents = pendingReimburse.cents,
                                monthExpenseCents = monthExpense,
                                monthLabel = monthLabel,
                            )
                        }.getOrElse {
                            WidgetPolicy.buildAssetSnapshot(emptyList(), emptyList(), 0, 0L, 0L, "本月原支出")
                        }

                        val prefs = WidgetPrefs.prefs(app)
                        val openAssetsPending = PendingIntent.getActivity(
                            app,
                            9301,
                            SystemIntegration.openAssetsIntent(app),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        val openPendingPending = PendingIntent.getActivity(
                            app,
                            9302,
                            SystemIntegration.openPendingIntent(app),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )

                        for (id in ids) {
                            val mode = WidgetPrefs.mode(prefs, id)
                            val surface = WidgetPrefs.surface(prefs, id)
                            val masked = WidgetPrefs.isMasked(prefs, id)
                            val palette = WidgetThemeTokens.palette(app, surface)

                            val maskPending = PendingIntent.getBroadcast(
                                app,
                                9320 + (id and 0x0FFF),
                                Intent(app, AssetReimburseWidget::class.java)
                                    .setAction(SystemIntegration.ACTION_WIDGET_TOGGLE_MASK)
                                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                    .setData(Uri.parse("mymoney://widget/asset/$id/mask")),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                            val tunePending = PendingIntent.getActivity(
                                app,
                                9360 + (id and 0x0FFF),
                                Intent(app, WidgetConfigureActivity::class.java)
                                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                    .setData(Uri.parse("mymoney://widget/asset/$id/configure"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )

                            val views = WidgetResponsive.build(
                                manager = manager,
                                appWidgetId = id,
                                supported = SUPPORTED,
                                fallback = WidgetSizeBucket.SQUARE_COMPACT,
                            ) { bucket, _ ->
                                renderBucket(
                                    app = app,
                                    bucket = bucket,
                                    mode = mode,
                                    surface = surface,
                                    masked = masked,
                                    palette = palette,
                                    snapshot = snapshot,
                                    openAssetsPending = openAssetsPending,
                                    openPendingPending = openPendingPending,
                                    maskPending = maskPending,
                                    tunePending = tunePending,
                                )
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
                    // Never surface launcher/alarm failures out of widget background work.
                } finally {
                    runCatching { pending?.finish() }
                }
            }
        }

        private fun renderBucket(
            app: Context,
            bucket: WidgetSizeBucket,
            mode: AssetWidgetMode,
            surface: WidgetSurfaceStyle,
            masked: Boolean,
            palette: WidgetPalette,
            snapshot: AssetWidgetSnapshot,
            openAssetsPending: PendingIntent,
            openPendingPending: PendingIntent,
            maskPending: PendingIntent,
            tunePending: PendingIntent,
        ): RemoteViews {
            val layoutId = when (bucket) {
                WidgetSizeBucket.MINI_ICON -> R.layout.widget_asset_mini
                WidgetSizeBucket.STRIP_HORIZONTAL -> R.layout.widget_asset_strip_h
                WidgetSizeBucket.STRIP_VERTICAL -> R.layout.widget_asset_strip_v
                WidgetSizeBucket.SQUARE_COMPACT -> R.layout.widget_asset_square
                WidgetSizeBucket.TALL_CARD -> R.layout.widget_asset_tall
                WidgetSizeBucket.WIDE_MEDIUM, WidgetSizeBucket.WIDE_FULL -> R.layout.widget_asset_wide
            }

            val netText = if (snapshot.hasInitializedAccounts) {
                WidgetPolicy.formatCurrency(snapshot.netCents, masked = masked)
            } else {
                "去设余额"
            }
            val assetsText = if (snapshot.hasInitializedAccounts) {
                WidgetPolicy.formatCurrency(snapshot.assetsCents, masked = masked)
            } else {
                "—"
            }
            val debtsText = if (snapshot.hasInitializedAccounts) {
                WidgetPolicy.formatCurrency(snapshot.debtsCents, masked = masked)
            } else {
                "—"
            }
            val pendingAmountText = WidgetPolicy.formatCurrency(snapshot.pendingCents, masked = masked)
            val monthText = WidgetPolicy.formatCurrency(snapshot.monthExpenseCents, masked = masked)
            val hasPending = snapshot.pendingCount > 0

            val primaryLabel = when (mode) {
                AssetWidgetMode.REIMBURSE_ONLY -> "待报销"
                else -> "净资产"
            }
            val primaryValue = when (mode) {
                AssetWidgetMode.REIMBURSE_ONLY -> pendingAmountText
                else -> netText
            }
            val primaryTarget = if (mode == AssetWidgetMode.REIMBURSE_ONLY) openPendingPending else openAssetsPending
            val secondaryTarget = when (mode) {
                AssetWidgetMode.MIXED -> openPendingPending
                AssetWidgetMode.ASSET_ONLY -> openAssetsPending
                AssetWidgetMode.REIMBURSE_ONLY -> openPendingPending
            }

            return RemoteViews(app.packageName, layoutId).apply {
                WidgetThemeTokens.applySurface(this, surface)
                setTextViewText(R.id.widget_primary_label, primaryLabel)
                setTextColor(R.id.widget_primary_label, palette.secondaryText)
                setTextViewText(R.id.widget_primary_amount, primaryValue)
                setTextColor(R.id.widget_primary_amount, palette.primaryText)

                setImageViewResource(
                    R.id.widget_eye,
                    if (masked) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye_on,
                )
                WidgetThemeTokens.tintImage(this, R.id.widget_eye, palette.secondaryText)
                setContentDescription(R.id.widget_eye, if (masked) "显示金额" else "隐藏金额")
                setOnClickPendingIntent(R.id.widget_eye, maskPending)
                setOnClickPendingIntent(R.id.widget_root, primaryTarget)
                setOnClickPendingIntent(R.id.widget_primary_block, primaryTarget)

                if (bucket != WidgetSizeBucket.MINI_ICON &&
                    bucket != WidgetSizeBucket.STRIP_HORIZONTAL &&
                    bucket != WidgetSizeBucket.STRIP_VERTICAL
                ) {
                    WidgetThemeTokens.tintImage(this, R.id.widget_tune, palette.secondaryText)
                    setOnClickPendingIntent(R.id.widget_tune, tunePending)
                    setOnClickPendingIntent(R.id.widget_secondary_block, secondaryTarget)
                }

                when (bucket) {
                    WidgetSizeBucket.SQUARE_COMPACT -> {
                        val badgeText = when (mode) {
                            AssetWidgetMode.MIXED -> if (hasPending) "待报销 ${snapshot.pendingCount} 笔 · $pendingAmountText" else "暂无待报销"
                            AssetWidgetMode.ASSET_ONLY -> "资产 $assetsText · 负债 $debtsText"
                            AssetWidgetMode.REIMBURSE_ONLY -> "${snapshot.pendingCount} 笔待核 · 本月 $monthText"
                        }
                        val emphasize = mode == AssetWidgetMode.MIXED && hasPending
                        WidgetThemeTokens.applyPillBackground(
                            this,
                            R.id.widget_secondary_bg,
                            surface,
                            emphasize,
                            palette,
                        )
                        setTextViewText(R.id.widget_secondary_text, badgeText)
                        setTextColor(
                            R.id.widget_secondary_text,
                            if (emphasize) palette.onAccentContainer else palette.primaryText,
                        )
                    }

                    WidgetSizeBucket.TALL_CARD -> {
                        val primarySub = when (mode) {
                            AssetWidgetMode.REIMBURSE_ONLY -> "共 ${snapshot.pendingCount} 笔待核销"
                            else -> "总资产 $assetsText"
                        }
                        val midTitle = if (mode == AssetWidgetMode.REIMBURSE_ONLY) snapshot.monthLabel else "总负债"
                        val midValue = if (mode == AssetWidgetMode.REIMBURSE_ONLY) monthText else debtsText
                        val bottomText = when (mode) {
                            AssetWidgetMode.MIXED -> if (hasPending) "待报销 ${snapshot.pendingCount} 笔 · $pendingAmountText" else "暂无待报销"
                            AssetWidgetMode.ASSET_ONLY -> "${snapshot.monthLabel} $monthText"
                            AssetWidgetMode.REIMBURSE_ONLY -> if (hasPending) "点击核销待报销账单" else "全部已结清"
                        }
                        val emphasize = (mode == AssetWidgetMode.MIXED || mode == AssetWidgetMode.REIMBURSE_ONLY) && hasPending
                        setTextViewText(R.id.widget_primary_sub, primarySub)
                        setTextColor(R.id.widget_primary_sub, palette.secondaryText)
                        setTextViewText(R.id.widget_middle_title, midTitle)
                        setTextColor(R.id.widget_middle_title, palette.secondaryText)
                        setTextViewText(R.id.widget_middle_value, midValue)
                        setTextColor(R.id.widget_middle_value, palette.primaryText)
                        setInt(
                            R.id.widget_middle_block,
                            "setBackgroundResource",
                            when (surface) {
                                WidgetSurfaceStyle.GLASS_DARK -> R.drawable.widget_pill_glass_dark
                                WidgetSurfaceStyle.GLASS_LIGHT -> R.drawable.widget_pill_glass_light
                                WidgetSurfaceStyle.SOLID -> R.drawable.widget_pill_solid
                            },
                        )
                        WidgetThemeTokens.applyPillBackground(
                            this,
                            R.id.widget_secondary_bg,
                            surface,
                            emphasize,
                            palette,
                        )
                        setTextViewText(R.id.widget_secondary_text, bottomText)
                        setTextColor(
                            R.id.widget_secondary_text,
                            if (emphasize) palette.onAccentContainer else palette.primaryText,
                        )
                    }

                    WidgetSizeBucket.WIDE_MEDIUM, WidgetSizeBucket.WIDE_FULL -> {
                        setTextViewText(R.id.widget_wide_header, mode.title)
                        setTextColor(R.id.widget_wide_header, palette.secondaryText)
                        val leftSub = when (mode) {
                            AssetWidgetMode.REIMBURSE_ONLY -> "共 ${snapshot.pendingCount} 笔待核销"
                            else -> "资产 $assetsText · 负债 $debtsText"
                        }
                        val rightTitle = when (mode) {
                            AssetWidgetMode.MIXED -> "待报销"
                            AssetWidgetMode.ASSET_ONLY -> snapshot.monthLabel
                            AssetWidgetMode.REIMBURSE_ONLY -> snapshot.monthLabel
                        }
                        val rightAmount = when (mode) {
                            AssetWidgetMode.MIXED -> pendingAmountText
                            else -> monthText
                        }
                        val rightSub = when (mode) {
                            AssetWidgetMode.MIXED -> if (hasPending) "共 ${snapshot.pendingCount} 笔 · 去核销" else "暂无待报销"
                            AssetWidgetMode.ASSET_ONLY -> "按本地账期统计"
                            AssetWidgetMode.REIMBURSE_ONLY -> if (hasPending) "点击查看待报销" else "无待报销账单"
                        }
                        val emphasize = mode == AssetWidgetMode.MIXED && hasPending
                        setTextViewText(R.id.widget_primary_sub, leftSub)
                        setTextColor(R.id.widget_primary_sub, palette.secondaryText)
                        WidgetThemeTokens.applyPillBackground(
                            this,
                            R.id.widget_secondary_bg,
                            surface,
                            emphasize,
                            palette,
                        )
                        setTextViewText(R.id.widget_secondary_title, rightTitle)
                        setTextColor(
                            R.id.widget_secondary_title,
                            if (emphasize) palette.onAccentContainer else palette.secondaryText,
                        )
                        setTextViewText(R.id.widget_secondary_amount, rightAmount)
                        setTextColor(
                            R.id.widget_secondary_amount,
                            if (emphasize) palette.onAccentContainer else palette.primaryText,
                        )
                        setTextViewText(R.id.widget_secondary_sub, rightSub)
                        setTextColor(
                            R.id.widget_secondary_sub,
                            if (emphasize) palette.onAccentContainer else palette.secondaryText,
                        )
                    }

                    else -> Unit
                }
            }
        }

        private fun dayIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            9319,
            Intent(context, AssetReimburseWidget::class.java).setAction(SystemIntegration.ACTION_WIDGET_DAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
