package io.github.mymoney.capture.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.billkit.BillDraft
import io.github.mymoney.R
import io.github.mymoney.capture.ConfirmIntents
import io.github.mymoney.capture.ConfirmLaunch
import org.json.JSONObject
import java.util.Locale

/**
 * 超级岛（澎湃 OS4 焦点通知）确认呈现宿主 —— **方案A**。
 *
 * 定位：位于 [io.github.mymoney.capture.ConfirmDelivery] 的呈现选择处，作为**可选的呈现宿主**
 * （实验开关 [io.github.mymoney.data.Settings.islandConfirmEnabled] 默认关闭）。它只负责“把待确认
 * 账单送到用户眼前”：小岛胶囊显示摘要，展开显示金额/方向/商户/（可能重复）/两个文字按钮。
 *
 * **点击语义（方案A）**：岛内「记一笔」「忽略」两个按钮点击、以及点岛本身，**统一走同一个
 * contentIntent → [io.github.mymoney.ui.ConfirmActivity]**，由确认页承担真正的保存/忽略
 * （复用 [io.github.mymoney.data.BillWriter] 业务与幂等）。**岛内不做直接写账**——因为 K2 探针实测
 * 证实：MIUI 平台未把 textButton 的 PendingIntent 绑定到点击（连开源库 buildResourceBundle 都不收），
 * 强行做岛内直接保存会退化成“打开 App”，不如显式地把用户带到确认页。（详见 design.md「K3 呈现设计定稿」）
 *
 * 结构严格复用 K2 探针 r9/r11 已实测上岛的官方 payload：
 *   { "param_v2": {...}, "scene": "template_v2", "isShowNotification": true }
 *   extras 三件套：miui.focus.param / miui.focus.actions / miui.focus.pics
 *   protocol=3、真实 business、enableFloat=true、ChatInfo 模板、param_island 内嵌岛区、textButton 双按钮。
 *
 * 失败/不可用一律返回 false，让 [io.github.mymoney.capture.ConfirmDelivery] 继续走原
 * overlay→Activity→待确认通知 三段降级，绝不静默丢弃草稿。
 */
object IslandHost {

    private const val TAG = "MyMoneyIsland"
    const val CHANNEL_ID = "mymoney_island_confirm"

    /** 真实业务名（不是 miui.focus.custom）。库示例里是自由字符串。 */
    private const val BUSINESS = "mymoney_pay"
    private const val SCENE = "template_v2"
    private const val PIC_KEY = "mymoney_icon"

    /** pics Bundle 里图标注册的真实 key：MIUI 平台要求带 miui.focus.pic_ 前缀，picInfo.pic / chatInfo.picProfile 也引用同一带前缀 key。 */
    private val PIC_BUNDLE_KEY = "miui.focus.pic_$PIC_KEY"

    /** 岛图标用小图标资源：复用 App 品牌钱袋 ic_brand_small（与现有通知 setSmallIcon 一致）。 */
    private fun brandIconRes(context: Context): Int = R.drawable.ic_brand_small

    /**
     * 当前设备/App 是否具备用岛呈现的前提：
     * - API 26+（NotificationChannel 需要）
     * - 通知权限已授予（否则 notify 会抛 SecurityException）
     * 这里只做**发送前**的明确判断；真正的“是否上岛”要看系统是否接受（见调用方回执策略）。
     */
    fun canShow(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "超级岛确认",
                NotificationManager.IMPORTANCE_HIGH,
            )
            ch.description = "识别到账单时用超级岛提醒你，点开核对后记一笔"
            ch.setShowBadge(true)
            mgr.createNotificationChannel(ch)
        }
    }

    private fun notifyId(draft: BillDraft): Int = (draft.historyId ?: draft.fingerprint).hashCode()

    /** 唯一的落地动作：打开确认页（岛按钮、点岛、通知栏都指向它）。 */
    private fun openConfirmPi(context: Context, draft: BillDraft): PendingIntent =
        PendingIntent.getActivity(
            context,
            (draft.historyId ?: draft.fingerprint).hashCode(),
            ConfirmIntents.intent(context, draft),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 摘要文案：方向 + 金额，例如「支出 ¥12.34」。岛只做呈现，商户空就退回源。 */
    private fun summaryLine(draft: BillDraft): String {
        val typeLabel = when (draft.type.name) {
            "INCOME" -> "收入"
            "REFUND" -> "退款"
            "TRANSFER" -> "转账"
            else -> "支出"
        }
        val amount = String.format(Locale.US, "%.2f", draft.priceCents / 100.0)
        return "$typeLabel ¥$amount"
    }

    /** 大岛副标题：商户/账户，缺失时退回来源包名；不伪造识别值。 */
    private fun detailLine(draft: BillDraft, duplicateSuspected: Boolean): String {
        val base = draft.merchant?.takeIf { it.isNotBlank() }
            ?: draft.account?.takeIf { it.isNotBlank() }
            ?: draft.sourcePkg
        return if (duplicateSuspected) "$base ·（可能重复）" else base
    }

    private fun picInfo(pic: String): JSONObject = JSONObject().apply {
        // pic 值必须与 pics Bundle 注册 key 一致，带 miui.focus.pic_ 前缀；
        // 否则大图标槽解析不到，只有 chatInfo 头像分支会画成右下角小徽标（K2 实测根因）。
        val v = if (pic.startsWith("miui.focus.pic_")) pic else "miui.focus.pic_$pic"
        put("type", 1); put("pic", v)
    }

    private fun buildExtras(
        context: Context,
        draft: BillDraft,
        title: String,
        text: String,
        duplicateSuspected: Boolean,
    ): Bundle {
        // 方案A：岛只做呈现+提醒，所有点击（点岛、通知栏）统一走 Notification.contentIntent → 确认页。
        // 岛内**不放任何按钮**：MIUI 焦点通知大岛一旦声明按钮（textButton 或 actions），平台就固定渲染
        // 两个「focus_button_background」按钮槽（青色描边圆环），payload 无法去掉该青环，且 textButton 只
        // 显示文字、actions 只固定青环——都不精致。实测纯展示（无按钮）时大岛干净无青环（见 K4.4）。
        val confirmPi = openConfirmPi(context, draft)

        val payload = buildPayloadJson(title, text)

        // 大图标/头像：品牌钱袋，key 带 miui.focus.pic_ 前缀（与 picInfo.pic / chatInfo.picProfile 一致）
        val pics = Bundle().apply {
            putParcelable(PIC_BUNDLE_KEY, Icon.createWithResource(context, brandIconRes(context)))
        }

        return Bundle().apply {
            putString("miui.focus.param", payload)
            putBundle("miui.focus.pics", pics)
        }
    }

    /**
     * 纯函数：构造 miui.focus.param 的 payload JSON（无 Context 依赖，可在 JVM 单测）。
     * 结构严格对齐 K2 探针 r9/r11 已实测上岛的官方 payload。
     */
    internal fun buildPayloadJson(title: String, text: String): String {
        val param = JSONObject().apply {
            put("protocol", 3)
            put("business", BUSINESS)
            put("updatable", true)
            put("ticker", title)
            put("enableFloat", true)
            put("isShowNotification", true)
            put("islandFirstFloat", false)

            // ChatInfo 模板（type=1）：驱动展开态大卡片与标题/副标题；picProfile 用品牌图标作
            // 大图标右下角小头像徽标（与左侧大图标同源）。注意：去掉 chatInfo 会导致岛无法展开，必须保留。
            put("chatInfo", JSONObject().apply {
                put("type", 1)
                put("title", title)
                put("content", text)
                put("picProfile", PIC_BUNDLE_KEY)
            })

            // 岛参数：bigIslandArea/smallIslandArea 嵌在 param_island 内
            put("param_island", JSONObject().apply {
                put("islandProperty", 2)
                put("islandPriority", 2)
                put("bigIslandArea", JSONObject().apply {
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("picInfo", picInfo(PIC_KEY))
                        put("textInfo", JSONObject().apply { put("title", title) })
                    })
                })
                put("smallIslandArea", JSONObject().apply {
                    put("picInfo", picInfo(PIC_KEY))
                })
            })

            put("bgInfo", JSONObject().apply { put("colorBg", "#FF202020") })

            // 纯展示：不声明 textButton、不声明 actions——避免平台渲染两个青环按钮槽（K4.4 实测定稿）。
        }

        return JSONObject().apply {
            put("param_v2", param)
            put("scene", SCENE)
            put("isShowNotification", true)
        }.toString()
    }

    /**
     * 用超级岛呈现一条待确认账单。
     *
     * @param duplicateSuspected 生成快照时已查过账本得到的“可能重复”结论（调用方只查一次）
     * @return true = 已成功 notify（不代表用户已看到岛；是否真上岛由系统决定，调用方据此决定
     *         是否还落待确认/通知）。false = 本宿主不适用/发送失败，调用方继续原降级链。
     */
    fun show(
        context: Context,
        draft: BillDraft,
        duplicateSuspected: Boolean,
    ): Boolean {
        Log.i(TAG, "show: start")
        if (!canShow(context)) { Log.i(TAG, "show: canShow false"); return false }
        ensureChannel(context)
        val app = context.applicationContext
        val title = summaryLine(draft)
        val text = detailLine(draft, duplicateSuspected)
        Log.i(TAG, "show: buildPi")
        val confirmPi = openConfirmPi(app, draft)
        Log.i(TAG, "show: buildExtras")
        val extras = buildExtras(app, draft, title, text, duplicateSuspected)
        Log.i(TAG, "show: buildExtras done")

        val builder = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(brandIconRes(app))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(confirmPi)
            .setAutoCancel(true)
            .addExtras(extras)

        return try {
            Log.i(TAG, "show: ConfirmLaunch.begin")
            ConfirmLaunch.begin(draft.historyId ?: draft.fingerprint)
            val mgr = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            Log.i(TAG, "show: mgr.notify")
            mgr.notify(notifyId(draft), builder.build())
            Log.i(TAG, "show: mgr.notify returned")
            Log.i(TAG, "island posted id=${draft.historyId ?: draft.fingerprint} business=$BUSINESS title=$title")
            true
        } catch (e: Throwable) {
            // SecurityException（无通知权限）/ 平台不认 payload 等，一律回退原路径
            Log.w(TAG, "island post failed id=${draft.historyId ?: draft.fingerprint}: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 撤岛（保存/忽略/App 内完成后调用；只撤通知，不改账单）。 */
    fun cancel(context: Context, draft: BillDraft) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(notifyId(draft))
        }
    }

    /** 便于单测：导出本次将发送的 param JSON（不真正 notify）。 */
    fun debugParam(context: Context, draft: BillDraft, duplicateSuspected: Boolean): String =
        buildPayloadJson(summaryLine(draft), detailLine(draft, duplicateSuspected))

    /** 便于单测：摘要行（方向+金额）。 */
    internal fun summaryLineFor(draft: BillDraft): String = summaryLine(draft)

    /** 便于单测：副标题行（商户/账户/来源 + （可能重复））。 */
    internal fun detailLineFor(draft: BillDraft, duplicateSuspected: Boolean): String =
        detailLine(draft, duplicateSuspected)
}
