package com.example.billkit.spec

import java.math.BigDecimal

/** User-authored literal conditions, never executable code or user supplied regular expressions. */
data class PersonalRule(
    val id: String, val name: String, val app: String = "com.tencent.mm", val inputType: String = "PAGE",
    val billType: String = "Expend", val keyword: String, val amountSource: String = "AUTO",
    val merchant: String = "", val method: String = "", val enabled: Boolean = true, val updatedAt: Long? = null,
) {
    fun validate() {
        require(updatedAt == null || updatedAt > 0) { "规则修改时间无效" }
        require(Regex("personal_[A-Za-z0-9_-]{6,80}").matches(id)) { "个人规则标识无效" }
        require(name.isNotBlank() && name.length <= 40) { "请填写40字以内的规则名称" }
        require(app in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) { "请选择微信或支付宝" }
        require(inputType in setOf("PAGE", "DATA", "NOTICE")) { "请选择支付页面、支付凭证或交易通知" }
        require(billType in setOf("Expend", "Income", "Refund")) { "请选择支出、收入或退款" }
        require(keyword.trim().length in 2..80 && keyword.any { it.isLetter() }) { "匹配文字需为2至80字，不能只有数字" }
        require(amountSource in setOf("AUTO", "TITLE", "DESCRIPTION")) { "金额来源无效" }
        require(merchant.length <= 80 && method.length <= 80) { "商户或支付方式不能超过80字" }
    }
    fun json(): Map<String, Any?> = mapOf("id" to id, "name" to name, "app" to app, "inputType" to inputType,
        "billType" to billType, "keyword" to keyword, "amountSource" to amountSource,
        "merchant" to merchant, "method" to method, "enabled" to enabled, "updatedAt" to updatedAt)
}

object PersonalRuleCodec {
    const val MAX_BYTES = 256 * 1024
    fun decodeRows(value: Any?): List<PersonalRule> {
        val rows = value as? List<*> ?: error("个人规则列表无效")
        require(rows.size <= 100) { "个人规则最多100条" }
        val allowed = setOf("id", "name", "app", "inputType", "billType", "keyword", "amountSource", "merchant", "method", "enabled")
        val parsed = rows.map { raw ->
            val m = raw as? Map<*, *> ?: error("个人规则格式无效")
            require(m.keys.containsAll(allowed) && (m.keys - allowed).all { it == "updatedAt" }) { "个人规则字段缺失或包含不支持的内容" }
            fun text(key: String) = m[key] as? String ?: error("个人规则字段无效：$key")
            PersonalRule(text("id"), text("name"), text("app"), text("inputType"), text("billType"), text("keyword"),
                text("amountSource"), text("merchant"), text("method"), m["enabled"] as? Boolean ?: error("规则开关无效"), m["updatedAt"]?.let { v ->
                    require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble()) { "规则修改时间无效" }; v.toLong()
                }).also { it.validate() }
        }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "个人规则标识重复" }
        return parsed
    }
    fun export(rules: List<PersonalRule>): String {
        rules.forEach { it.validate() }
        return SpecJson.encode(mapOf("kind" to "MyMoneyPersonalRules", "schemaVersion" to 1, "rules" to rules.map { it.json() }))
    }
    fun decode(text: String): List<PersonalRule> {
        require(text.toByteArray().size <= MAX_BYTES) { "个人规则文件超过256KB" }
        val m = SpecJson.decodeObject(text)
        require(m.keys == setOf("kind", "schemaVersion", "rules") && m["kind"] == "MyMoneyPersonalRules" &&
            (m["schemaVersion"] as? Number)?.toDouble() == 1.0) { "请选择本应用导出的个人规则文件" }
        return decodeRows(m["rules"])
    }
}

object PersonalRuleEngine {
    data class Input(val title: String, val description: String, val merchant: String, val method: String,
        val amounts: List<String>, val time: Long, val orderId: String = "", val direction: String = "", val businessTime: Long = 0L, val hasBusinessTime: Boolean = false)
    private val unsafe = Regex("失败|取消|撤销|待支付|待付款|未支付|未付款|未扣款|待扣款|未完成|待完成|待确认|尚未|待到账|等待|受理中|支付中|付款中|处理中|进行中|验证码|冻结|过期|退还通知")
    private val foreign = Regex("USD|美元|港币|HKD|EUR|欧元|[$€]", RegexOption.IGNORE_CASE)
    private val money = Regex("[¥￥]\\s*([0-9][0-9,.]*)(?![0-9.,])|(?<![0-9.,])([0-9][0-9,.]*)\\s*元")
    private fun text(value: Any?) = value as? String ?: ""
    private fun objectValue(value: Any?): Map<*, *>? = when(value) {
        is Map<*, *> -> value
        is String -> runCatching { SpecJson.decodeObject(value) }.getOrNull()
        else -> null
    }
    private fun clock(value: Any?): Long = (value as? Number)?.toLong()?.takeIf { it > 0 }?.let { if(it < 100_000_000_000L) it*1000 else it } ?: 0
    private fun transactionTime(value: Any?): Long? {
        val n = when(value) {
            is String -> value.takeIf { Regex("[1-9][0-9]{9,12}").matches(it) }?.toLongOrNull()
            is Number -> value.toDouble().takeIf { it.isFinite() && it>0 && it==kotlin.math.floor(it) }?.toLong()
            else -> null
        } ?: return null
        return clock(n).takeIf { it>0 }
    }
    fun input(app: String, type: String, raw: String): Input? { return runCatching {
        require(raw.length <= 65_536)
        val root = SpecJson.decode(raw)
        if(type == "PAGE") {
            val m=root as? Map<*, *> ?: return null
            if(listOf("LauncherUI","Chatting","ChatRoom","Conversation").any { text(m["activity"]).contains(it,true) }) return null
            val values=m["items"] as? List<*> ?: return null
            if(values.size>1024 || values.any { it !is String } || m["truncated"]==true) return null
            val items=values.map { it as String }
            if(!com.example.billkit.PageClassifier().permitsCustomRule(items)) return null
            if(com.example.billkit.AmountParser.status(items)!=com.example.billkit.AmountParser.Status.SUCCESS) return null
            val refund=com.example.billkit.AmountParser.isCompletedRefund(items)
            val hit=(if(refund) com.example.billkit.AmountParser.findRefundAmount(items) else com.example.billkit.AmountParser.findAmount(items)) ?: return null
            val fields=com.example.billkit.FieldExtractor.extract(items)
            return Input(items.firstOrNull().orEmpty(),items.joinToString("\n"),fields.merchant ?: fields.counterParty.orEmpty(),fields.method ?: Regex("使用\\s*(.{1,80}?)\\s*支付\\s*[¥￥]").find(items.joinToString("\n"))?.groupValues?.get(1).orEmpty(),
                listOf(BigDecimal.valueOf(hit.cents,2).toPlainString()),clock(m["t"]),fields.orderId.orEmpty(),
                if(refund) "Refund" else if(hit.sign<0) "Expend" else if(hit.sign>0) "Income" else "",
                (if(refund) com.example.billkit.AmountParser.findRefundTime(items) else com.example.billkit.AmountParser.findTime(items)) ?: 0L,
                (if(refund) com.example.billkit.AmountParser.findRefundTime(items) else com.example.billkit.AmountParser.findTime(items)) != null)
        }
        if(type == "NOTICE") {
            val m = root as? Map<*, *> ?: return null
            if(m["envelopeVerified"] != true || text(m["title"]) != if(app=="com.tencent.mm") "微信支付" else "交易提醒") return null
            return Input(text(m["title"]),text(m["text"]),"","",emptyList(),clock(m["observedAt"] ?: m["t"]))
        }
        if(type != "DATA") return null
        if(app == "com.tencent.mm") {
            val m = (root as? Map<*, *>)?.get("mMap") as? Map<*, *> ?: return null
            if(text(m["source"]) != "微信支付" || (m.containsKey("currency") && m["currency"] !in setOf("CNY","RMB","人民币"))) return null
            val desc = text(m["description"])
            val shop = Regex("(?m)^收款方[：:]\\s*(.{1,80})$").find(desc)?.groupValues?.get(1)
                ?: text(m["display_name"]).takeUnless { it in setOf("微信支付", "") }.orEmpty()
            val method = Regex("(?m)^使用\\s*(.{1,80}?)\\s*支付\\s*[¥￥]").find(desc)?.groupValues?.get(1).orEmpty()
            val explicit = if(m.containsKey("transactionTime")) transactionTime(m["transactionTime"]) ?: return null else 0L
            Input(text(m["title"]),desc,shop,method,emptyList(),clock(m["t"]),text(m["orderId"]),businessTime=explicit,hasBusinessTime=explicit>0)
        } else if(app == "com.eg.android.AlipayGphone") {
            val row = (root as? List<*>)?.singleOrNull() as? Map<*, *> ?: return null
            val pl = objectValue(row["pl"]) ?: return null
            val content = objectValue(pl["content"]) ?: return null
            if(listOf(pl,content).any { it.containsKey("currency") && it["currency"] !in setOf("CNY","RMB","人民币") }) return null
            val fields = linkedMapOf<String,String>()
            val values = content["content"] as? List<*> ?: return null
            if(values.size > 64) return null
            for(value in values) {
                val f = value as? Map<*, *> ?: return null
                val key = text(f["title"]).trim().trimEnd('：',':')
                if(key.isBlank() || fields.containsKey(key)) return null
                fields[key] = text(f["content"])
            }
            if(fields.containsKey("币种") && fields["币种"] !in setOf("CNY","RMB","人民币")) return null
            val directions = listOfNotNull(fields["收支类型"],fields["交易类型"],fields["方向"]).map {
                when(it) { "支出","消费","付款" -> "Expend"; "收入","收款" -> "Income"; "退款" -> "Refund"; else -> "invalid" }
            }.distinct()
            if(directions.size>1 || "invalid" in directions) return null
            val explicit = if(fields.containsKey("交易时间戳")) transactionTime(fields["交易时间戳"]) ?: return null else 0L
            Input(text(pl["title"]),fields.entries.joinToString("\n") { "${it.key}：${it.value}" },
                fields["收款方"] ?: fields["付款方"] ?: fields["商户名称"].orEmpty(),fields["付款方式"].orEmpty(),
                listOfNotNull(pl["money"],content["money"]).map { it.toString() },clock(row["mct"]),fields["订单号"].orEmpty(),directions.firstOrNull().orEmpty(),explicit,explicit>0)
        } else null
    }.getOrNull() }

    private fun cents(value: String, signedExpense: Boolean = false): Long? = runCatching {
        val s = value.trim().let { if(signedExpense) it.removePrefix("-") else it }
        require(Regex("(?:(?:0|[1-9][0-9]{0,6})|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?").matches(s))
        BigDecimal(s.replace(",", "")).movePointRight(2).longValueExact().takeIf { it in 1..100_000_000L }
    }.getOrNull()
    private fun amounts(text: String): List<Long>? {
        val result = mutableListOf<Long>()
        for(match in money.findAll(text)) result.add(cents(match.groupValues[1].ifEmpty { match.groupValues[2] }) ?: return null)
        return result
    }
    private fun success(input: Input, type: String): Boolean {
        if(input.direction.isNotBlank() && input.direction != type) return false
        if(input.title.trim() == "交易成功") return input.direction == type
        val patterns = mapOf(
            "Expend" to "(?:已支付|支付成功|付款成功|支付完成|付款完成)",
            "Income" to "(?:已收款|收款成功|收款已到账|已收钱|已存入零钱|已存入经营账户|收款到账)",
            "Refund" to "(?:退款到账|退款成功|退款已到账|原支付退款已到账)")
        val lines = (input.title+"\n"+input.description).lines().map { it.trim().replace(Regex("^(?:交易状态|支付状态|付款状态|收款状态|退款状态)[：:\\s]*"), "") }
        val found = patterns.filter { (_,pattern) -> lines.any { Regex("^$pattern(?:$|[¥￥：:，,\\s].*)").matches(it) } }.keys
        return found == setOf(type) || (found.isEmpty() && input.direction==type && lines.any { it=="交易成功" })
    }
    fun match(rules: List<PersonalRule>, app: String, type: String, raw: String): SpecRuleResult? {
        val input = input(app,type,raw) ?: return null
        val full = input.title+"\n"+input.description
        if(unsafe.containsMatchIn(full) || foreign.containsMatchIn(full) || Regex("转账|提现|充值|转入|转出|手续费").containsMatchIn(full) ||
            Regex("[-−]\\s*[¥￥]|[¥￥]\\s*[-−]|[-−]\\s*[0-9][0-9,.]*\\s*元").containsMatchIn(full)) return null
        val title = amounts(input.title) ?: return null
        val desc = amounts(input.description) ?: return null
        for(rule in rules) {
            if(!rule.enabled || rule.app != app || rule.inputType != type) continue
            if(!((full+"\n"+input.merchant).contains(rule.keyword.trim())) || !success(input,rule.billType)) continue
            val native = input.amounts.map { cents(it,rule.billType=="Expend") ?: return null }
            val all = (title+desc+native).distinct()
            if(all.size != 1) return null
            val selected = when(rule.amountSource) { "TITLE" -> title; "DESCRIPTION" -> desc; else -> title+desc+native }
            if(selected.isEmpty() || selected.any { it != all.single() }) continue
            return SpecRuleResult(SpecRuleObject(type=rule.billType,money=all.single()/100.0,currency="CNY",
                shopName=rule.merchant.ifBlank { input.merchant },accountNameFrom=rule.method.ifBlank { input.method },
                observedAt=input.time,orderId=input.orderId,time=input.businessTime,hasExplicitTime=input.hasBusinessTime),rule.name,rule.id,false)
        }
        return null
    }
    fun pageRaw(snapshot: com.example.billkit.UiSnapshot, now: Long): String = SpecJson.encode(mapOf(
        "items" to snapshot.items,"truncated" to snapshot.truncated,"activity" to snapshot.activityClass,"t" to now))

    /** Synthetic editor input is never inserted into the raw-data or accounting tables. */
    fun example(rule: PersonalRule, title: String, description: String, merchant: String): String {
        return if(rule.inputType=="PAGE") SpecJson.encode(mapOf("items" to listOf(title,description,if(merchant.isBlank()) "" else "商户名称：$merchant"),"truncated" to false,"t" to System.currentTimeMillis()))
        else if(rule.inputType=="NOTICE") SpecJson.encode(mapOf("title" to if(rule.app=="com.tencent.mm") "微信支付" else "交易提醒",
            "text" to title+"\n"+description+"\n"+merchant,"envelopeVerified" to true,"t" to System.currentTimeMillis()))
        else if(rule.app=="com.tencent.mm") SpecJson.encode(mapOf("mMap" to mapOf("source" to "微信支付","title" to title,
            "description" to description,"display_name" to merchant,"t" to System.currentTimeMillis())))
        else {
            val money = ((amounts(title).orEmpty()+amounts(description).orEmpty()).distinct().singleOrNull() ?: 0L)/100.0
            SpecJson.encode(listOf(mapOf("mct" to System.currentTimeMillis(),"pl" to mapOf("title" to title,"money" to money,
                "content" to mapOf("content" to listOf(mapOf("title" to "收款方：","content" to merchant),mapOf("title" to "说明：","content" to description)))))))
        }
    }
}
