package io.github.mymoney.importer

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Pure, conservative mapping; parsing and persistence must be handled separately. */
object QianjiRow {
    val requiredHeaders = listOf("ID", "时间", "账本", "分类", "二级分类", "类型", "金额", "币种", "账户1", "账户2", "备注", "已报销", "手续费", "优惠券", "记账者", "账单标记", "标签", "账单图片", "关联账单")
    private val sensitiveFields = listOf("已报销", "手续费", "优惠券", "关联账单")
    private val ignoredFields = listOf("记账者", "账单标记", "标签", "账单图片")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    data class Record(
        val sourceId: String, val timeMillis: Long, val type: String, val cents: Long,
        val category: String?, val subCategory: String?, val account: String?,
        val transferAccount: String?, val note: String?, val omittedFields: List<String>,
    )
    sealed interface Result {
        data class Accepted(val record: Record) : Result
        data class Skipped(val reason: String) : Result
    }

    fun map(cells: Map<String, String>, zone: ZoneId): Result {
        fun value(name: String) = cells[name]?.trim().orEmpty()
        fun optional(name: String) = cells[name]?.takeIf { it.isNotBlank() }
        if (!cells.keys.containsAll(requiredHeaders)) return Result.Skipped("缺少必要表头")
        val id = value("ID").ifEmpty { return Result.Skipped("ID 为空") }
        val kind = when (value("类型")) {
            "支出" -> "EXPENSE"
            "收入" -> "INCOME"
            "转账" -> "TRANSFER"
            else -> return Result.Skipped("不支持的类型：${value("类型")}")
        }
        if (value("币种") != "CNY") return Result.Skipped("不支持的币种")
        val present = sensitiveFields.filter { value(it).isNotEmpty() }
        if (present.isNotEmpty()) return Result.Skipped("暂不支持字段：${present.joinToString("、")}")
        val cents = try {
            val n = BigDecimal(value("金额"))
            if (n.signum() <= 0 || n.stripTrailingZeros().scale() > 2) return Result.Skipped("金额无效或精度超过两位")
            n.movePointRight(2).longValueExact()
        } catch (_: Exception) { return Result.Skipped("金额无效") }
        val time = try {
            val local = LocalDateTime.parse(value("时间"), dateFormat)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size != 1) return Result.Skipped("时间在本地时区不唯一或不存在")
            local.toInstant(offsets.single()).toEpochMilli()
        } catch (_: Exception) { return Result.Skipped("时间格式无效") }
        if (kind == "TRANSFER" && (optional("账户1") == null || optional("账户2") == null)) {
            return Result.Skipped("转账缺少账户")
        }
        return Result.Accepted(Record(id, time, kind, cents, optional("分类"), optional("二级分类"), optional("账户1"), optional("账户2"), optional("备注"), (ignoredFields + cells.keys.filterNot { it in requiredHeaders }).filter { value(it).isNotEmpty() }))
    }
}
