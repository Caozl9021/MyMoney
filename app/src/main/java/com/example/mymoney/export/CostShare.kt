package com.example.mymoney.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.mymoney.data.AppDb
import com.example.mymoney.data.BillWriter
import com.example.billkit.BillDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把 CSV 交给 iCost（或任意支持 CSV 的记账 App）。
 *
 * 走的是 **ACTION_SEND + content:// URI**，也就是 iCost 在 Manifest 里公开声明的
 * `ShareReceiveActivity` 入口 —— 和"分享给微信"是同一种机制。
 * iCost 会自己弹出导入预览，用户在它自己的界面上确认，我们不碰它的任何数据。
 */
object CostShare {

    private const val TAG = "MyMoney"

    fun isTargetInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(CostCsv.TARGET_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 把 CSV 交给 iCost。尝试顺序（越靠前越容易落在它的导入预览上）：
     *   1. ACTION_VIEW + content:// —— 对文件 URI 通常直达"导入预览"，跳过选来源
     *   2. ACTION_SEND 定向 iCost
     *   3. ACTION_VIEW / ACTION_SEND 弹系统选择器
     *
     * 注意：最后的"确认导入"是 iCost 自己的安全阀，**不会**去自动点它。
     * 返回实际用了哪种方式（null 表示全部失败）。
     */
    fun share(context: Context, csv: String, onlyTarget: Boolean = true): ShareMode? {
        val uri = writeShareFile(context, csv) ?: return null

        // 1) VIEW 定向
        if (onlyTarget) {
            val view = buildView(context, uri).setPackage(CostCsv.TARGET_PACKAGE)
            if (tryStart(context, view)) return ShareMode.VIEW_TARGET
        }

        // 2) SEND 定向
        if (onlyTarget) {
            val send = buildSend(context, uri, csv).setPackage(CostCsv.TARGET_PACKAGE)
            if (tryStart(context, send)) return ShareMode.SEND_TARGET
        }

        // 3) 系统选择器
        if (tryStart(context, Intent.createChooser(buildView(context, uri), "导入到记账 App"))) {
            return ShareMode.VIEW_CHOOSER
        }
        if (tryStart(context, Intent.createChooser(buildSend(context, uri, csv), "导入到记账 App"))) {
            return ShareMode.SEND_CHOOSER
        }
        return null
    }

    enum class ShareMode { VIEW_TARGET, SEND_TARGET, VIEW_CHOOSER, SEND_CHOOSER }

    /** 带用户清单的便捷入口；包含退款时抛 IllegalArgumentException，由页面显示原因。 */
    fun shareBills(
        context: Context,
        bills: List<BillDraft>,
        onlyTarget: Boolean = true,
    ): ShareMode? = share(context, CostCsv.build(bills), onlyTarget)

    private fun buildView(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/csv")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "麦米.csv", uri)
    }

    private fun buildSend(context: Context, uri: Uri, csv: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, csv)
            clipData = ClipData.newUri(context.contentResolver, "麦米.csv", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 导出指定区间的账单（本月/上月/最近 3 个月/全部），返回成功导出的条数 */
    suspend fun shareRange(
        context: Context,
        kind: ExportRange.Kind,
        onlyTarget: Boolean = true,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        monthStartDay: Int = com.example.mymoney.data.LedgerPeriodStore.read(context),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Int {
        val span = ExportRange.span(kind, today, zone, monthStartDay)
        val entities = withContext(Dispatchers.IO) {
            AppDb.get(context).bills().range(span.from, span.to)
        }
        if (entities.isEmpty()) return 0
        val drafts = entities.map { BillWriter.toDraft(it) }
        return if (shareBills(context, drafts, onlyTarget) != null) drafts.size else 0
    }

    /** 一次导出最近的 N 条（"补录"场景），返回成功导出的条数 */
    suspend fun shareRecent(context: Context, limit: Int = 50, onlyTarget: Boolean = true): Int {
        val entities = withContext(Dispatchers.IO) {
            AppDb.get(context).bills().recentOnce(limit)
        }
        if (entities.isEmpty()) return 0

        val drafts = entities.map { BillWriter.toDraft(it) }

        return if (shareBills(context, drafts, onlyTarget) != null) drafts.size else 0
    }

    private fun writeShareFile(context: Context, csv: String): Uri? = try {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "麦米_${System.currentTimeMillis()}.csv")
        file.writeText(csv, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    } catch (e: Exception) {
        Log.w(TAG, "写 CSV 失败: ${e.message}")
        null
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "没有可接收 CSV 的 App: ${e.message}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "分享失败: ${e.message}")
        false
    }
}
