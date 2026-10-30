package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import io.github.mymoney.data.MiniJson
import com.example.billkit.BillDraft
import com.example.billkit.BillPipeline
import com.example.billkit.BillType
import com.example.billkit.Packages
import com.example.billkit.UiSnapshot
import java.util.Locale

/**
 * P6.3 纯文本回放：批次六支付结果识别的人工转录夹具。
 *
 * 重要边界：
 * - 主场景为 2026-10-24 两张支付成功截图可见内容的脱敏人工转录；
 *   另含合成的结构/失败反例及既有详情夹具，均非实际节点，
 *   不是真实无障碍节点 dump，也不代表设备上实际收到过这些节点或事件。
 * - 不读写 AppDb / 账本 / 识别历史，不安装 BillKit sink，不调用 BillKit.install。
 *   只直接构造 BillPipeline 并 process 人工 UiSnapshot。
 * - 运行时只允许模拟器（Build fingerprint/model/product 三者同时符合），
 *   真机直接拒绝，避免把纯文本夹具结果误当成真机采集结果。
 *
 * 运行方式（由主代理统一执行，本文件作者未运行）：
 *   ./gradlew :app:assembleDebugAndroidTest \
 *       -PmymoneyTestRunner=io.github.mymoney.PaymentResultsInstrumentation
 *   adb shell am instrument -w \
 *       io.github.mymoney.test/io.github.mymoney.PaymentResultsInstrumentation
 */
class PaymentResultsInstrumentation : Instrumentation() {
    private var args = Bundle()
    private var now = DEFAULT_NOW
    private var emulatorGuard = "not-checked"
    private val results = mutableListOf<Map<String, Any?>>()
    private val pipeline = BillPipeline()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        if (args.containsKey("now")) now = args.getLong("now")
        start()
    }

    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try {
            emulatorGuard = requireEmulatorOnly()
            runCases()
        } catch (error: Throwable) {
            failure = error
        }
        val failedCount = results.count { it["status"] == "failed" }
        val status = if (failure == null && failedCount == 0) "passed" else "failed"
        val summary = linkedMapOf<String, Any?>(
            "status" to status,
            "mode" to "payment-results",
            "source" to SOURCE,
            "fixtureNote" to "人工转录截图可见文本；非真实无障碍节点 dump，非真实支付抓取",
            "database" to "not-used",
            "sink" to "not-installed",
            "emulatorGuard" to emulatorGuard,
            "now" to now,
            "tests" to results.size,
            "failed" to failedCount,
            "results" to results,
            "error" to failure?.stackTraceToString(),
            "device" to "${Build.MANUFACTURER}/${Build.MODEL}/${Build.PRODUCT}/${Build.FINGERPRINT}",
        )
        val report = Bundle().apply {
            putString("report", MiniJson.encode(summary))
            putString("status", status)
            putString("source", SOURCE)
            putString("error", failure?.stackTraceToString())
            putInt("tests", results.size)
            putInt("failed", failedCount)
        }
        finish(if (status == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }

    // ---------------------------------------------------------------- runtime guard

    /** 真机拒绝运行；只有 fingerprint/model/product 同时呈现模拟器特征才放行。 */
    private fun requireEmulatorOnly(): String {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)
        // AOSP 模拟器 fingerprint 形如 google/sdk_gphone64_x86_64/...（含 sdk/goldfish/ranchu），
        // 不一定含 "emulator" 字样，因此这里同时接受 sdk/goldfish/ranchu/vbox。
        val fingerprintEmulator = listOf("generic", "emulator", "emu", "sdk", "goldfish", "ranchu", "vbox")
            .any { fingerprint.contains(it) }
        val modelEmulator = listOf("sdk", "emulator", "google_sdk", "vbox", "goldfish", "ranchu").any { model.contains(it) }
        val productEmulator = listOf("sdk", "emulator", "google_sdk", "vbox", "goldfish", "ranchu").any { product.contains(it) }
        check(fingerprintEmulator && modelEmulator && productEmulator) {
            "Refusing to run payment-result replay on a real device: " +
                "model=$model product=$product fingerprint=$fingerprint"
        }
        return "emulator-only: model=$model product=$product fingerprint=$fingerprint"
    }

    // ---------------------------------------------------------------- fixtures

    /** 支付宝成功页可见文本人工转录；不是节点 dump。 */
    private val alipayTranscription = listOf(
        "支付成功",
        "¥16.38",
        "获得森林能量",
        "示例餐厅",
        "¥17.00",
        "碰友日立减",
        "-¥0.62",
        "付款方式",
        "工商银行储蓄卡(0000)",
        "恭喜你获得600笔花籽",
        "疯狂碰友节分1亿红包",
        "今日合成最高6.6元红包",
        "27元外卖券",
        "去参与",
        "完成",
    )

    /** 微信极简成功页可见文本人工转录；金额前收款人已脱敏。 */
    private val wechatTranscription = listOf(
        "支付成功",
        "示例收款人（*某）",
        "¥0.01",
        "完成",
    )

    /** 既有可信转账详情方向回归，取自 app JVM 既有样本。 */
    private val trustedWechatTransfer = listOf(
        "转账成功",
        "¥200.00",
        "转账给",
        "张三",
        "转账时间",
        "2024年10月21日 10:20",
        "收款方",
        "张三",
        "转账单号",
        "1000050001202410210123456789",
        "零钱",
    )

    /** 既有可信退款详情方向回归，取自 app JVM 既有测试构造。 */
    private val trustedAlipayRefund = listOf(
        "账单详情",
        "当前状态",
        "退款成功",
        "+19.99",
        "交易时间",
        "2026-10-22 12:00:00",
        "交易单号",
        "123456789",
        "实付款100元",
        "退款金额19.99元",
    )

    /** 普通支付宝账单详情，确认本专项没有把既有支出方向改坏。 */
    private val trustedAlipayBill = listOf(
        "账单详情",
        "¥128.00",
        "创建时间",
        "2024-10-21 12:30",
        "付款方式",
        "花呗",
        "交易成功",
        "商家订单号",
        "2024102112300012345",
    )

    // ---------------------------------------------------------------- cases

    private fun runCases() {
        // 支付宝主截图：同屏还有商户原金额、立减和奖励广告，必须只取 16.38。
        draftCase(
            name = "alipay_transcription_selects_actual_payment_1638_expense",
            pkg = Packages.ALIPAY,
            items = alipayTranscription,
            expected = draftExpected(1638, BillType.EXPENSE, explicitTime = false) + ", orderTail=null",
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1638, type = BillType.EXPENSE, explicitTime = false)
            checkDraft(draft.orderTail == null, actual, "orderTail=null")
        }

        // 拆 ¥ 与数值为两个节点，仍应取到唯一主实付。
        draftCase(
            name = "alipay_split_currency_yen_and_amount_nodes",
            pkg = Packages.ALIPAY,
            items = splitCurrency(alipayTranscription, "¥", "16.38"),
            expected = draftExpected(1638, BillType.EXPENSE, explicitTime = false),
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1638, type = BillType.EXPENSE, explicitTime = false)
        }
        draftCase(
            name = "alipay_split_fullwidth_yen_and_amount_nodes",
            pkg = Packages.ALIPAY,
            items = splitCurrency(alipayTranscription.map { if (it == "¥16.38") "￥16.38" else it }, "￥", "16.38"),
            expected = draftExpected(1638, BillType.EXPENSE, explicitTime = false),
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1638, type = BillType.EXPENSE, explicitTime = false)
        }

        // 微信主截图：1 分支出，method/order 不猜测，时间使用传入 now。
        draftCase(
            name = "wechat_transcription_one_cent_expense_without_guessed_method_or_order",
            pkg = Packages.WECHAT,
            items = wechatTranscription,
            expected = draftExpected(1, BillType.EXPENSE, explicitTime = false) + ", method=null, orderTail=null",
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1, type = BillType.EXPENSE, explicitTime = false)
            checkDraft(draft.method == null, actual, "method=null")
            checkDraft(draft.orderTail == null, actual, "orderTail=null")
        }
        draftCase(
            name = "wechat_split_currency_yen_and_amount_nodes",
            pkg = Packages.WECHAT,
            items = splitCurrency(wechatTranscription, "¥", "0.01"),
            expected = draftExpected(1, BillType.EXPENSE, explicitTime = false) + ", method=null, orderTail=null",
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1, type = BillType.EXPENSE, explicitTime = false)
            checkDraft(draft.method == null, actual, "method=null")
            checkDraft(draft.orderTail == null, actual, "orderTail=null")
        }
        draftCase(
            name = "wechat_split_fullwidth_yen_and_amount_nodes",
            pkg = Packages.WECHAT,
            items = splitCurrency(wechatTranscription.map { if (it == "¥0.01") "￥0.01" else it }, "￥", "0.01"),
            expected = draftExpected(1, BillType.EXPENSE, explicitTime = false) + ", method=null, orderTail=null",
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1, type = BillType.EXPENSE, explicitTime = false)
            checkDraft(draft.method == null, actual, "method=null")
            checkDraft(draft.orderTail == null, actual, "orderTail=null")
        }

        // 帮助入口不是失败状态，不能把可靠成功页抹掉。
        draftCase(
            name = "alipay_help_question_does_not_override_success",
            pkg = Packages.ALIPAY,
            items = alipayTranscription + "支付失败怎么办",
            expected = draftExpected(1638, BillType.EXPENSE, explicitTime = false),
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1638, type = BillType.EXPENSE, explicitTime = false)
        }
        draftCase(
            name = "wechat_help_question_does_not_override_success",
            pkg = Packages.WECHAT,
            items = wechatTranscription + "支付失败怎么办？",
            expected = draftExpected(1, BillType.EXPENSE, explicitTime = false),
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1, type = BillType.EXPENSE, explicitTime = false)
        }

        // 状态反例：失败 / 取消 / 处理中都不入账。
        skippedCase(
            name = "alipay_failed_state_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription + "支付失败",
            expected = "Skipped(any reason; failed state must not draft)",
        )
        skippedCase(
            name = "alipay_cancelled_state_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription + "已取消",
            expected = "Skipped(any reason; cancelled state must not draft)",
        )
        skippedCase(
            name = "alipay_pending_state_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription + "处理中",
            expected = "Skipped(any reason; pending state must not draft)",
        )
        skippedCase(
            name = "wechat_failed_state_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription + "支付失败",
            expected = "Skipped(any reason; failed state must not draft)",
        )
        skippedCase(
            name = "wechat_cancelled_state_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription + "已取消",
            expected = "Skipped(any reason; cancelled state must not draft)",
        )
        skippedCase(
            name = "wechat_pending_state_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription + "处理中",
            expected = "Skipped(any reason; pending state must not draft)",
        )

        // 真实状态与帮助入口同时存在，仍以真实状态为准。
        skippedCase(
            name = "alipay_real_failure_with_help_entry_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription + listOf("支付失败怎么办", "当前状态：支付失败"),
            expected = "Skipped(any reason; failed state must not draft)",
        )
        skippedCase(
            name = "wechat_real_pending_with_help_entry_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription + listOf("支付失败怎么办", "处理中"),
            expected = "Skipped(any reason; pending state must not draft)",
        )

        // 缺成功、缺主额：不能从原价或优惠反推，也不能拿裸数字补。
        skippedCase(
            name = "alipay_missing_success_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription.filterNot { it == "支付成功" },
            expected = "Skipped(any reason; missing success must not draft)",
        )
        skippedCase(
            name = "wechat_missing_success_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription.filterNot { it == "支付成功" },
            expected = "Skipped(any reason; missing success must not draft)",
        )
        skippedCase(
            name = "alipay_missing_main_amount_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription.filterNot { it == "¥16.38" },
            expected = "Skipped(no-amount/ambiguous; no trusted main amount)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )
        skippedCase(
            name = "alipay_bare_main_amount_without_currency_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription.map { if (it == "¥16.38") "16.38" else it },
            expected = "Skipped(no-amount/ambiguous; no trusted main amount)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )
        skippedCase(
            name = "wechat_missing_main_amount_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription.filterNot { it == "¥0.01" },
            expected = "Skipped(no-amount/ambiguous; no trusted main amount)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )

        // 同节点多个主额 / 多个独立主额节点：都必须拒绝。
        skippedCase(
            name = "alipay_multiple_independent_main_amount_nodes_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription.toMutableList().apply { add(2, "¥12.00") },
            expected = "Skipped(no-amount/ambiguous; multiple main amounts)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )
        skippedCase(
            name = "alipay_duplicate_main_amount_nodes_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription.toMutableList().apply { add(2, "¥16.38") },
            expected = "Skipped(no-amount/ambiguous; multiple main amounts)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )
        skippedCase(
            name = "alipay_same_node_multiple_main_amounts_rejected",
            pkg = Packages.ALIPAY,
            items = listOf(
                "支付成功",
                "示例餐厅",
                "碰友日立减",
                "¥16.38 ¥12.00",
                "付款方式",
                "工商银行储蓄卡(0000)",
                "完成",
            ),
            expected = "Skipped(no-amount/ambiguous; multiple main amounts in one node)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )
        skippedCase(
            name = "wechat_multiple_independent_main_amount_nodes_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription + "¥0.02",
            expected = "Skipped(no-amount/ambiguous; multiple main amounts)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )
        skippedCase(
            name = "wechat_same_node_multiple_main_amounts_rejected",
            pkg = Packages.WECHAT,
            items = listOf("支付成功", "示例收款人（*某）", "¥0.01 ¥0.02", "完成"),
            expected = "Skipped(no-amount/ambiguous; multiple main amounts in one node)",
            allowedReasonPrefixes = AMOUNT_LAYER_REJECTION,
        )

        // 错误包、列表页与缺完成动作不能借成功文案命中。
        skippedCase(
            name = "alipay_wrong_package_rejected",
            pkg = "com.example.unrelated",
            items = alipayTranscription,
            expected = "Skipped(not-a-bill-page; wrong package)",
            allowedReasonPrefixes = CLASSIFIER_LAYER_REJECTION,
        )
        skippedCase(
            name = "wechat_wrong_package_rejected",
            pkg = "com.tencent.mm.clone",
            items = wechatTranscription,
            expected = "Skipped(not-a-bill-page; wrong package)",
            allowedReasonPrefixes = CLASSIFIER_LAYER_REJECTION,
        )
        skippedCase(
            name = "alipay_list_page_rejected",
            pkg = Packages.ALIPAY,
            items = alipayTranscription + "筛选",
            expected = "Skipped(not-a-bill-page; list page)",
            allowedReasonPrefixes = CLASSIFIER_LAYER_REJECTION,
        )
        skippedCase(
            name = "wechat_list_page_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription + "全部账单",
            expected = "Skipped(not-a-bill-page; list page)",
            allowedReasonPrefixes = CLASSIFIER_LAYER_REJECTION,
        )
        skippedCase(
            name = "wechat_missing_completion_action_rejected",
            pkg = Packages.WECHAT,
            items = wechatTranscription.filterNot { it == "完成" },
            expected = "Skipped(not-a-bill-page; no completion anchor)",
            allowedReasonPrefixes = CLASSIFIER_LAYER_REJECTION,
        )

        // 既有可信方向回归：不能把转账、退款或普通账单改坏。
        draftCase(
            name = "trusted_wechat_transfer_direction_retained",
            pkg = Packages.WECHAT,
            items = trustedWechatTransfer,
            expected = draftExpected(20000, BillType.TRANSFER, explicitTime = true),
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 20000, type = BillType.TRANSFER, explicitTime = true)
        }
        draftCase(
            name = "trusted_alipay_refund_direction_retained",
            pkg = Packages.ALIPAY,
            items = trustedAlipayRefund,
            expected = draftExpected(1999, BillType.REFUND, explicitTime = true),
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 1999, type = BillType.REFUND, explicitTime = true)
        }
        draftCase(
            name = "trusted_alipay_bill_detail_expense_direction_retained",
            pkg = Packages.ALIPAY,
            items = trustedAlipayBill,
            expected = draftExpected(12800, BillType.EXPENSE, explicitTime = true),
        ) { draft, actual ->
            verifyDraft(draft, actual, cents = 12800, type = BillType.EXPENSE, explicitTime = true)
        }
    }

    // ---------------------------------------------------------------- harness

    private fun process(pkg: String, items: List<String>): BillPipeline.Outcome =
        pipeline.process(
            UiSnapshot(
                packageName = pkg,
                activityClass = null,
                items = items,
                source = UiSnapshot.Source.ACCESSIBILITY,
            ),
            now,
        )

    private fun draftCase(
        name: String,
        pkg: String,
        items: List<String>,
        expected: String,
        verify: (BillDraft, String) -> Unit,
    ) {
        case(name, expected) {
            val outcome = process(pkg, items)
            val actual = describe(outcome)
            val draft = (outcome as? BillPipeline.Outcome.Draft)?.draft
                ?: throw AssertionError("expected Draft; actual=$actual")
            verify(draft, actual)
            actual
        }
    }

    /**
     * 反例只校验"没有生成 Draft"，并可选校验拒绝发生在哪一层（原因前缀）。
     *
     * 原因文案是流水线诊断信息，不是稳定契约；这里只固定到"分类层拒绝"或"金额层拒绝"，
     * 避免把未来核心实现的措辞变化误判成回归，也不假装核验了更细的原因。
     */
    private fun skippedCase(
        name: String,
        pkg: String,
        items: List<String>,
        expected: String,
        allowedReasonPrefixes: List<String> = emptyList(),
    ) {
        case(name, expected) {
            val outcome = process(pkg, items)
            val actual = describe(outcome)
            if (outcome !is BillPipeline.Outcome.Skipped) {
                throw AssertionError("expected Skipped; actual=$actual")
            }
            if (allowedReasonPrefixes.isNotEmpty() &&
                allowedReasonPrefixes.none { outcome.reason.startsWith(it) }
            ) {
                throw AssertionError(
                    "expected rejected by ${allowedReasonPrefixes.joinToString("/")}; actual=$actual",
                )
            }
            actual
        }
    }

    private fun case(name: String, expected: String, action: () -> String) {
        val started = System.currentTimeMillis()
        try {
            val actual = action()
            results += linkedMapOf(
                "name" to name,
                "status" to "passed",
                "expected" to expected,
                "actual" to actual,
                "error" to null,
                "durationMs" to System.currentTimeMillis() - started,
            )
        } catch (error: Throwable) {
            results += linkedMapOf(
                "name" to name,
                "status" to "failed",
                "expected" to expected,
                "actual" to (error.message?.takeIf { it.isNotBlank() } ?: error.toString()),
                "error" to error.stackTraceToString(),
                "durationMs" to System.currentTimeMillis() - started,
            )
        }
    }

    private fun verifyDraft(
        draft: BillDraft,
        actual: String,
        cents: Long,
        type: BillType,
        explicitTime: Boolean,
    ) {
        checkDraft(draft.priceCents == cents, actual, "priceCents=$cents")
        checkDraft(draft.type == type, actual, "type=${type.name}")
        checkDraft(draft.hasExplicitTime == explicitTime, actual, "hasExplicitTime=$explicitTime")
        if (!explicitTime) checkDraft(draft.timeMillis == now, actual, "timeMillis=now($now)")
    }

    private fun checkDraft(condition: Boolean, actual: String, requirement: String) {
        if (!condition) throw AssertionError("expected $requirement; actual=$actual")
    }

    private fun describe(outcome: BillPipeline.Outcome): String = when (outcome) {
        is BillPipeline.Outcome.Draft -> buildString {
            append("Draft(priceCents=").append(outcome.draft.priceCents)
            append(", type=").append(outcome.draft.type.name)
            append(", hasExplicitTime=").append(outcome.draft.hasExplicitTime)
            append(", timeMillis=").append(outcome.draft.timeMillis)
            append(", method=").append(outcome.draft.method)
            append(", orderTail=").append(outcome.draft.orderTail)
            append(", strategy=").append(outcome.draft.strategy)
            append(')')
        }
        is BillPipeline.Outcome.Skipped -> "Skipped(reason=${outcome.reason})"
    }

    private fun splitCurrency(items: List<String>, currency: String, amount: String): List<String> =
        items.flatMap { item -> if (item == "$currency$amount") listOf(currency, amount) else listOf(item) }

    private fun draftExpected(cents: Long, type: BillType, explicitTime: Boolean): String =
        "Draft(priceCents=$cents, type=${type.name}, hasExplicitTime=$explicitTime, " +
            "timeMillis=${if (explicitTime) "page-time" else "now($now)"})"

    companion object {
        private const val DEFAULT_NOW = 1_792_713_600_000L
        private const val SOURCE = "manual_screenshot_transcription_not_real_capture"

        /** 分类层拒绝：错误包/列表页/缺完成锚点必须由 PageClassifier 挡住。 */
        private val CLASSIFIER_LAYER_REJECTION = listOf("not-a-bill-page")

        /**
         * 金额层拒绝：没有唯一可信主额。
         * 极简微信页在分类阶段就要求金额唯一，因此也可能先以 not-a-bill-page 拒绝；
         * 两种层级都算"没有生成支出草稿"，不把实现分层当成契约。
         */
        private val AMOUNT_LAYER_REJECTION = listOf("no-amount", "ambiguous", "not-a-bill-page")
    }
}
