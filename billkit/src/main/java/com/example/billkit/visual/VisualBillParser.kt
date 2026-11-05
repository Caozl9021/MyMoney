package com.example.billkit.visual

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** Visual evidence extraction. It neither authorizes capture nor writes/merges a ledger record. */
object VisualBillParser {
    private val moneyLabels = listOf("实付款", "实付金额", "实际付款", "实际支付", "支付金额", "付款金额", "转账金额", "支出金额", "收款金额", "到账金额", "收入金额", "交易金额")
    private val outgoingMoneyLabels = listOf("实付款", "实付金额", "实际付款", "实际支付", "支付金额", "付款金额", "支出金额")
    private val incomingMoneyLabels = listOf("收款金额", "到账金额", "收入金额")
    private val merchantLabels = listOf("商户名称", "商户全称", "商家名称", "店铺名称", "收款方名称", "收款方全称", "交易对方", "收款方")
    private val methodLabels = listOf("支付方式", "付款方式", "支付工具", "扣款方式", "资金渠道")
    private val productLabels = listOf("商品说明", "商品名称", "交易说明")
    private val timeLabels = listOf("支付时间", "付款时间", "交易时间", "转账时间", "收款时间")
    private val orderLabels = listOf("交易单号", "转账单号", "支付宝交易号", "订单编号", "订单号")
    private val currencyLabels = listOf("交易币种", "币种")
    private val allLabels = moneyLabels + merchantLabels + methodLabels + productLabels + timeLabels + orderLabels + currencyLabels
    private val failed = Regex("待支付|待付款|等待付款|未支付|未付款|未扣款|支付中|付款中|转账中|支付失败|付款失败|交易失败|交易关闭|交易已关闭|订单已取消|确认付款|确认支付|立即付款")
    private val sensitive = Regex("密码|验证码|身份验证|指纹验证|人脸验证|面容验证|刷脸验证")
    private val refund = Regex("退款成功|退款到账|已退款|已退金额|累计退款|退款处理中|退款失败|原付款金额")
    private val special = Regex("充值成功|提现成功|还款成功|交易类型[:：]?(充值|提现|还款)")
    private val listPage = Regex("全部账单|账单列表|收支明细列表|交易记录列表")
    private val expenseStatus = Regex("^(?:当前状态|交易状态)?[:：]?(支付成功|付款成功|转账成功|扣款成功|已支付|已付款)(?:[，,].*)?$")
    private val genericSuccess = Regex("^(?:当前状态|交易状态)?[:：]?交易成功$")
    private val incomeStatus = Regex("^(?:当前状态|交易状态)?[:：]?(收款成功|收款到账|已收款|已收钱|收入到账|已到账)$")
    private val explicitExpense = Regex("^(?:交易类型[:：]?)?支出$")
    private val explicitIncome = Regex("^(?:交易类型[:：]?)?收入$")
    private val moneyPattern = Regex("^([+−-]?)[¥￥]?([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})(?:\\.([0-9]{1,2}))?元?$")
    private val ignoredMoney = Regex("优惠|奖励|红包|积分|返现|立减|余额|手续费|服务费|原价|订单金额|退款|已退|最高|最多")
    private val notMerchant = Regex("成功|确认收款|等待|待支付|退款|付款方式|支付方式|返回|完成|查看|详情|订单|交易状态|支付奖励|收款方服务|广告|领取|优惠|积分|余额|全部账单")
    private val date = Regex("(20[0-9]{2})[年/.-]([0-9]{1,2})[月/.-]([0-9]{1,2})日?\\s*([0-9]{1,2})[:：]([0-9]{2})(?:[:：]([0-9]{2}))?")
    private data class Amount(val cents: Long, val sign: String, val line: VisualLine)
    private data class Read(val lines: List<VisualLine>, val amount: Long?, val type: BillType?, val fields: VisualFields, val issue: String?, val success: Boolean)

    /** A user-supplied screenshot is never a real-time PAGE event. Missing date remains explicit. */
    fun parse(text: VisualText, now: Long): VisualBillResult {
        val read = read(text, now)
        if (read.issue != null) return result(read)
        if (!read.success) return result(read, "未识别到明确的交易成功状态")
        if (read.amount == null) return result(read, "未识别到唯一有效的实付金额")
        val type = read.type ?: return result(read, "交易方向不明确，请手动核对")
        val f = read.fields
        val draft = BillDraft(read.amount, type, f.timeMillis ?: now, f.merchant, f.method,
            null, "io.github.mymoney.manual.ocr", "visual-screenshot-v1",
            hasExplicitTime = f.timeMillis != null, productName = f.productName,
            productSource = f.productName?.let { "explicit-product-v1:OCR" })
        return result(read).copy(draft = draft)
    }

    /** Existing trusted node evidence supplies success context, never a missing image amount. */
    fun supplement(text: VisualText, expected: BillDraft, now: Long): VisualBillResult {
        val read = read(text, now)
        if (read.issue != null) return result(read)
        if (read.amount == null) return result(read, "截图未识别到可核对金额")
        if (read.amount != expected.priceCents) return result(read, "截图金额与当前账单不一致，请核对")
        if (expected.type !in setOf(BillType.EXPENSE, BillType.INCOME)) return result(read, "此交易类型暂不支持自动截图补全")
        if (read.type != null && read.type != expected.type) return result(read, "截图交易方向与当前账单不一致")
        if (expected.hasExplicitTime && read.fields.timeMillis != null && expected.timeMillis != read.fields.timeMillis)
            return result(read, "截图交易时间与当前账单不一致")
        return result(read).copy(type = expected.type)
    }

    private fun result(read: Read, issue: String? = read.issue) = VisualBillResult(
        issue = issue, explicitTime = read.fields.timeMillis != null, fields = read.fields,
        amountCents = read.amount, type = read.type,
        warnings = if (issue == null && read.fields.timeMillis == null) listOf("截图未识别到交易时间，请核对记账时间") else emptyList(),
    )

    private fun read(text: VisualText, now: Long): Read {
        fun reject(reason: String) = Read(emptyList(), null, null, VisualFields(), reason, false)
        if (text.width !in 1..8192 || text.height !in 1..8192 || text.lines.size > 500) return reject("图片识别结果超出处理范围")
        if (text.lines.sumOf { it.text.length.toLong() } > 32_768L) return reject("图片文字过多，请裁剪为单笔账单")
        val lines = text.lines.map { it.copy(text = normalize(it.text)) }.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return reject("未识别到可用文字")
        val joined = lines.joinToString("\n") { it.text.replace(" ", "") }
        if (sensitive.containsMatchIn(joined)) return reject("验证或密码页面不参与记账识别")
        if (failed.containsMatchIn(joined)) return reject("交易尚未成功或已失败，不生成账单")
        if (refund.containsMatchIn(joined)) return reject("退款截图请通过现有退款流程核对，不自动修改原账")
        if (special.containsMatchIn(joined)) return reject("此特殊资金流请手动核对")
        if (listPage.containsMatchIn(joined)) return reject("请使用单笔账单截图，不识别账单列表")
        val inlineCurrencies = lines.mapNotNull { line ->
            currencyLabels.firstOrNull { line.text.startsWith(it) }?.let { label ->
                line.text.removePrefix(label).trim().trimStart(':', '：').trim().takeIf(String::isNotBlank)
            }
        }
        val currencies = (values(lines, currencyLabels).map { it.text } + inlineCurrencies)
            .map { it.replace(" ", "").uppercase() }
        if (currencies.any { it !in setOf("CNY", "RMB", "人民币", "元") }) return reject("截图币种不是明确人民币，请手动核对")
        val statuses = lines.filter { scoreAllowed(it) }.map { it.text.replace(" ", "") }
        val expense = statuses.any(expenseStatus::matches)
        val income = statuses.any { incomeStatus.matches(it) || explicitIncome.matches(it) }
        if (expense && income) return reject("截图包含相互冲突的交易方向")
        val success = expense || statuses.any(incomeStatus::matches) || statuses.any(genericSuccess::matches)
        val labeled = values(lines, moneyLabels).mapNotNull(::money)
        val labeledAmounts = labeled.map { it.cents }.distinct()
        if (labeledAmounts.size > 1) return reject("截图包含不同的实付金额，请核对")
        val headers = headerAmounts(lines, text.width, text.height)
        val prominent = headers.map { it.cents }.distinct()
        if (prominent.size > 1) return reject("截图包含多个主金额，请核对")
        if (labeledAmounts.singleOrNull() != null && prominent.singleOrNull() != null && labeledAmounts.single() != prominent.single())
            return reject("主金额与实付金额字段不一致")
        val amount = labeledAmounts.singleOrNull() ?: prominent.singleOrNull()
        val directions = buildSet {
            if (income || values(lines, incomingMoneyLabels).any { money(it) != null }) add(BillType.INCOME)
            if (expense || statuses.any(explicitExpense::matches) || values(lines, outgoingMoneyLabels).any { money(it) != null }) add(BillType.EXPENSE)
            if ((headers + labeled).any { it.sign == "+" }) add(BillType.INCOME)
            if ((headers + labeled).any { it.sign in setOf("-", "−") }) add(BillType.EXPENSE)
        }
        if (directions.size > 1) return reject("截图交易状态、金额符号或方向字段相互冲突")
        val type = directions.singleOrNull()
        val times = values(lines, timeLabels).mapNotNull { parseTime(it.text, now) }.distinct()
        if (times.size > 1) return reject("截图包含多个交易时间，请核对")
        val merchant = unique(values(lines, merchantLabels).map { it.text }.filter(::plausibleMerchant))
            ?: merchantByHeader(lines, headers.firstOrNull()?.line, text.width)
        val methods = values(lines, methodLabels).map { it.text }.filter { it.length in 1..80 && !allLabels.any { label -> it.startsWith(label) } }
        val products = values(lines, productLabels).map { it.text }.filter { it.length in 1..300 && !Regex("优惠券|红包奖励|支付奖励|领取|广告").containsMatchIn(it) }
        val orders = values(lines, orderLabels).map { it.text.replace(" ", "") }.filter { it.matches(Regex("[A-Za-z0-9_-]{10,100}")) }
        return Read(lines, amount, type, VisualFields(merchant, unique(methods), unique(products), times.singleOrNull(), unique(orders)), null, success)
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replace('−', '-')
    private fun unique(values: List<String>): String? = values.distinct().singleOrNull()
    private fun scoreAllowed(line: VisualLine, minimum: Float = .80f) = line.confidence?.let { it.isFinite() && it in minimum..1f } ?: true
    private fun geometry(line: VisualLine) = line.right > line.left && line.bottom > line.top
    private fun labelOnly(text: String) = allLabels.any { text.trimEnd(':', '：') == it }

    /** The ordering of OCR blocks is not used when valid geometry is present. */
    private fun values(lines: List<VisualLine>, labels: List<String>): List<VisualLine> = lines.flatMapIndexed { index, line ->
        if (!scoreAllowed(line)) return@flatMapIndexed emptyList()
        val label = labels.firstOrNull { line.text.startsWith(it) } ?: return@flatMapIndexed emptyList()
        val rest = line.text.removePrefix(label)
        if (rest.isNotEmpty() && rest.first() !in charArrayOf(':', '：', ' ', '¥', '￥') && !rest.first().isDigit()) return@flatMapIndexed emptyList()
        val own = rest.trim().trimStart(':', '：').trim()
        if (own.isNotEmpty()) return@flatMapIndexed listOf(line.copy(text = own))
        if (geometry(line)) {
            val h = line.bottom - line.top
            val cy = (line.top + line.bottom) / 2.0
            val right = lines.filter { it !== line && geometry(it) && scoreAllowed(it) && it.left >= line.right - h / 2 &&
                abs((it.top + it.bottom) / 2.0 - cy) <= maxOf(h, it.bottom - it.top) * .60 && !labelOnly(it.text) }.sortedBy { it.left }
            if (right.isNotEmpty()) return@flatMapIndexed listOf(right.first().copy(text = right.joinToString(" ") { it.text },
                confidence = right.mapNotNull { it.confidence }.minOrNull()))
            val below = lines.filter { geometry(it) && scoreAllowed(it) && it.top >= line.bottom && it.top - line.bottom <= h * 1.5 &&
                abs(it.left - line.left) <= h * 2 && !labelOnly(it.text) }.minByOrNull { it.top }
            if (below != null) return@flatMapIndexed listOf(below)
        } else {
            lines.getOrNull(index + 1)?.takeIf { scoreAllowed(it) && !labelOnly(it.text) }?.let { return@flatMapIndexed listOf(it) }
        }
        emptyList()
    }

    private fun money(line: VisualLine): Amount? {
        if (!scoreAllowed(line, .90f)) return null
        val raw = line.text.replace(" ", "").replace("人民币", "").replace("CNY", "")
        val match = moneyPattern.matchEntire(raw) ?: return null
        val numeric = match.groupValues[2].replace(",", "") + match.groupValues[3].let { if (it.isBlank()) "" else ".$it" }
        val cents = runCatching { BigDecimal(numeric).movePointRight(2).longValueExact() }.getOrNull() ?: return null
        if (cents !in 1..100_000_000L) return null
        return Amount(cents, match.groupValues[1], line)
    }

    private fun headerAmounts(lines: List<VisualLine>, width: Int, height: Int): List<Amount> {
        val candidates = lines.mapIndexedNotNull { index, line ->
            if (ignoredMoney.containsMatchIn(line.text)) return@mapIndexedNotNull null
            if (!geometry(line) && index > 0 && ignoredMoney.containsMatchIn(lines[index-1].text)) return@mapIndexedNotNull null
            if (geometry(line) && lines.any { other -> other !== line && other.right <= line.left &&
                ignoredMoney.containsMatchIn(other.text) && geometry(other) &&
                abs((line.top + line.bottom - other.top - other.bottom) / 2.0) <= maxOf(line.bottom-line.top,other.bottom-other.top)*.65 }) return@mapIndexedNotNull null
            val amount = money(line) ?: return@mapIndexedNotNull null
            val decorated = line.text.any { it in "¥￥元+-" }
            val large = geometry(line) && line.top in (height * .08).toInt()..(height * .60).toInt() &&
                (line.left + line.right)/2.0 in width*.15..width*.85 && line.bottom-line.top >= width*.04 && line.text.contains('.')
            if (decorated || large) amount else null
        }
        if (candidates.isEmpty()) return emptyList()
        val maxHeight = candidates.maxOf { it.line.bottom-it.line.top }
        return if (maxHeight > 0) candidates.filter { it.line.bottom-it.line.top >= maxHeight*.8 } else candidates
    }

    private fun plausibleMerchant(value: String) = value.length in 2..80 && !notMerchant.containsMatchIn(value) &&
        !allLabels.any { value.startsWith(it) } && !date.containsMatchIn(value) && !value.matches(Regex("[0-9.,:+-]+")) &&
        value !in setOf("零钱", "零钱通", "微信支付", "支付宝", "银行卡", "储蓄卡", "信用卡") && value.none { it in "¥￥" }

    private fun merchantByHeader(lines: List<VisualLine>, header: VisualLine?, width: Int): String? {
        if (header == null || !geometry(header)) return null
        val nearby = lines.filter { scoreAllowed(it) && geometry(it) && plausibleMerchant(it.text) &&
            abs((it.left+it.right-header.left-header.right)/2.0) < width*.20 &&
            ((it.bottom <= header.top && header.top-it.bottom < width*.25) ||
                (it.top >= header.bottom && it.top-header.bottom < width*.18)) }
        return nearby.minByOrNull { minOf(abs(it.bottom-header.top),abs(it.top-header.bottom)) }?.text
    }

    private fun parseTime(value: String, now: Long): Long? {
        val m = date.find(value) ?: return null
        return runCatching {
            val v=m.groupValues
            LocalDateTime.of(v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toInt(),v[5].toInt(),v[6].toIntOrNull() ?: 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                .takeIf { it in 946_656_000_000L..now+300_000L }
        }.getOrNull()
    }
}
