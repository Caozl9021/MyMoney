package io.github.mymoney.systems

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

/**
 * Builds responsive RemoteViews mapped to the launcher's allocated SizeF buckets on API 31+,
 * and falls back to OPTION_APPWIDGET_MIN_WIDTH/HEIGHT on API 26-30.
 */
object WidgetResponsive {

    fun build(
        manager: AppWidgetManager,
        appWidgetId: Int,
        supported: Set<WidgetSizeBucket>,
        fallback: WidgetSizeBucket,
        render: (WidgetSizeBucket, SizeF?) -> RemoteViews,
    ): RemoteViews {
        val options = manager.getAppWidgetOptions(appWidgetId)
        if (Build.VERSION.SDK_INT >= 31) {
            val sizes = readSizes(options)
            if (sizes.isNotEmpty()) {
                val map = LinkedHashMap<SizeF, RemoteViews>(sizes.size)
                for (size in sizes) {
                    val bucket = WidgetPolicy.constrainBucket(
                        WidgetPolicy.classifySizeBucket(size.width, size.height),
                        supported,
                        fallback,
                    )
                    map[size] = render(bucket, size)
                }
                return RemoteViews(map)
            }
        }
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160).toFloat()
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140).toFloat()
        val bucket = WidgetPolicy.constrainBucket(
            WidgetPolicy.classifySizeBucket(minW, minH),
            supported,
            fallback,
        )
        return render(bucket, SizeF(minW, minH))
    }

    @Suppress("DEPRECATION")
    private fun readSizes(options: Bundle): List<SizeF> {
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        } else {
            options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        }
        return raw?.filter { it.width > 0f && it.height > 0f }.orEmpty()
    }
}
