package com.example.billkit.notification

import com.example.billkit.spec.SpecJson
import java.math.BigDecimal
import java.security.MessageDigest

/** Literal format only. Placeholders never become executable regex supplied by a package. */
data class PortableExpenseTemplate(
    override val id: String,
    val ruleId: String,
    override val evidenceReference: String,
    val channelHashes: List<String>,
    val expectedCategory: String?,
    val expectedPlatformTemplate: String?,
    val title: String,
    val bodyPattern: String,
    val rewardPatterns: List<String>,
) : VerifiedExpenseNotificationTemplate {
    override val packageName = "com.eg.android.AlipayGphone"
    override val channelId = ""
    override val platformTemplate = ""
    override val structuredExpense = true
    override fun matchesMetadata(input: NotificationObservation): Boolean =
        input.packageName == packageName && !input.channelId.isNullOrBlank() &&
            sha256(input.channelId) in channelHashes && input.category == expectedCategory && input.template == expectedPlatformTemplate
    private fun literalPattern(value: String, placeholder: String): String =
        value.split(placeholder).joinToString("((?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,2})?)") { Regex.escape(it) }
    private val expense = Regex("^" + literalPattern(bodyPattern,"{amount}") + "(.*)$")
    private val rewards = rewardPatterns.map { Regex("^" + literalPattern(it,"{rewardAmount}") + "$") }
    override fun matches(observation: NotificationObservation) = extract(observation) != null
    override fun extract(observation: NotificationObservation): NotificationExpenseFields? {
        if (observation.title?.trim() != title) return null
        val body = (observation.bigText?.takeIf { it.isNotBlank() } ?: observation.text)?.trim() ?: return null
        if (unsafe.containsMatchIn(body)) return null
        val match = expense.matchEntire(body) ?: return null
        if (rewards.none { it.matches(match.groupValues[2]) }) return null
        val amount = match.groupValues[1]
        val cents = runCatching { BigDecimal(amount).movePointRight(2).longValueExact() }.getOrNull() ?: return null
        if (cents !in 1..100_000_000L) return null
        return NotificationExpenseFields(amount)
    }
    companion object {
        private val unsafe = Regex("失败|取消|撤销|待支付|待付款|待扣款|未支付|未付款|未扣款|未完成|待完成|待确认|尚未|支付中|付款中|扣款中|处理中|进行中|冻结|验证码|退款|转账|收入|收款|美元|港币|USD|HKD|EUR|[$€]",RegexOption.IGNORE_CASE)
        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class NotificationTemplateConfig(val templates: List<PortableExpenseTemplate>, private val samples: List<Map<*, *>>) {
    /** Run with an isolated production parser, without publishing or creating any transaction. */
    fun validateSamples(): Int {
        val parser = NotificationParser(templates)
        val positive = mutableSetOf<String>(); val negative = mutableSetOf<String>(); val ids = mutableSetOf<String>()
        samples.forEach { row ->
            val sampleId = row["id"] as? String ?: error("通知样本ID缺失")
            require(ids.add(sampleId)) { "通知样本ID重复" }
            require(row["provenance"] in setOf("synthetic","captured-redacted","transcribed") && (row["evidence"] as? String).orEmpty().isNotBlank()) { "通知样本来源缺失" }
            val target = row["templateId"] as? String ?: error("通知样本模板缺失")
            require(templates.any { it.id==target }) { "通知样本引用未知模板" }
            val raw = row["observation"] as? Map<*,*> ?: error("通知样本缺失")
            fun text(k: String) = raw[k] as? String
            val o = NotificationObservation(sampleId,text("packageName") ?: "com.eg.android.AlipayGphone","test",sampleId,1,null,"0",
                1793506849867L,1793506850100L,text("title"),text("text"),text("bigText"),text("channelId"),text("category"),text("template"),
                raw["isGroupSummary"]==true,raw["isMessagingStyle"]==true,raw["isOngoing"]==true,raw["isReconnectBaseline"]==true,raw["truncated"]==true)
            val result = parser.parse(o)
            val cents = row["expectCents"]
            if (cents != null) {
                require(cents is Number && cents.toDouble()==cents.toLong().toDouble() && result is NotificationParseResult.Candidate &&
                    result.templateId==target && result.draft.priceCents==cents.toLong()) { "通知正例不符：$sampleId" }
                positive.add(target)
            } else { require(result !is NotificationParseResult.Candidate) { "通知反例误命中：$sampleId" }; negative.add(target) }
        }
        require(templates.all { it.id in positive && it.id in negative }) { "每个通知模板必须有正反例" }
        return samples.size
    }
    companion object {
        const val FILE = "notification-templates.json"
        const val CAPABILITY = "notification-template-v1"
        fun decode(text: String): NotificationTemplateConfig {
            require(text.toByteArray().size<=256*1024) { "通知配置超过256KB" }
            val root = SpecJson.decodeObject(text)
            require(root.keys==setOf("schemaVersion","templates","samples") && (root["schemaVersion"] as? Number)?.toDouble()==1.0) { "通知配置版本或字段不支持" }
            val rows = root["templates"] as? List<*> ?: error("通知模板缺失")
            require(rows.size<=16) { "通知模板最多16条" }
            val templates = rows.map { value ->
                val m = value as? Map<*,*> ?: error("通知模板格式无效")
                require(m.keys==setOf("id","ruleId","evidence","channelHashes","category","platformTemplate","title","bodyPattern","rewardPatterns")) { "通知模板字段无效" }
                fun str(k:String)=m[k] as? String ?: error("通知字段缺失：$k")
                fun strings(k:String)=(m[k] as? List<*>)?.map { it as? String ?: error("通知列表字段无效") } ?: error("通知列表缺失")
                val id=str("id"); val rule=str("ruleId");val hashes=strings("channelHashes"); val title=str("title");val body=str("bodyPattern");val rewards=strings("rewardPatterns")
                require(Regex("[a-z][a-z0-9_-]{2,79}").matches(id) && rule=="maimi_alipay_notice_expense_v1") { "通知模板ID或关联规则不支持" }
                require(str("evidence").length in 1..200 && hashes.size in 1..16 && hashes.distinct().size==hashes.size && hashes.all { Regex("[a-f0-9]{64}").matches(it) }) { "通知渠道或证据无效" }
                require(listOf("category","platformTemplate").all { m[it]==null || m[it] is String && (m[it] as String).length in 1..160 }) { "通知元数据无效" }
                require(title.length in 1..40 && !title.contains('\n')) { "通知标题无效" }
                require(body.length in 8..100 && body.split("{amount}").size==2 && body.endsWith("元的支出") &&
                    body.substringBefore("{amount}").isNotBlank() && !body.replace("{amount}","").any { it=='{' || it=='}' || it=='\n' || it.isDigit() }) { "通知支出格式无效" }
                require(rewards.size in 1..32 && rewards.distinct().size==rewards.size && rewards.all { reward ->
                    reward.isEmpty() || reward.length<=120 && reward.startsWith("，点击领") &&
                        reward.split("{rewardAmount}").size==2 && Regex("[{}0-9\\n]|支出|消费|扣款|付款|收入|退款|转账|收款|支付|你有一笔").containsMatchIn(reward.replace("{rewardAmount}","").replace("支付宝积分", "积分").replace("支付红包", "红包")).not() &&
                        (reward.endsWith("红包。") || reward.endsWith("积分。"))
                }) { "奖励尾句必须是领取奖励格式，不得包含其他交易或金额" }
                PortableExpenseTemplate(id,rule,str("evidence"),hashes,m["category"] as? String,m["platformTemplate"] as? String,title,body,rewards)
            }
            require(templates.map { it.id }.distinct().size==templates.size) { "通知模板ID重复" }
            val samples=(root["samples"] as? List<*>)?.map { it as? Map<*,*> ?: error("通知样本格式错误") } ?: error("通知样本缺失")
            require(samples.size<=256) { "通知样本过多" }
            return NotificationTemplateConfig(templates,samples)
        }
    }
}

/** Only immutable, fully validated generations are published. A parser snapshots once per call. */
object NotificationTemplateRegistry {
    @Volatile private var current: List<VerifiedExpenseNotificationTemplate> = listOf(AlipayExpenseNotificationTemplate())
    fun snapshot(): List<VerifiedExpenseNotificationTemplate> = current
    fun install(templates: List<VerifiedExpenseNotificationTemplate>) { current=templates.toList() }
}
