package com.example.autobook.data

/**
 * 备份文件的纯数据层：只做「对象 ⇄ JSON 文本」，不碰 Context / Room。
 *
 * 分成两半是为了能测：
 * - 这里（[Backup]）纯逻辑，JVM 单测直接跑；
 * - 读写文件、查库、写偏好，在 `BackupStore` 里。
 *
 * 格式约定：
 * - 顶层 `format` 是版本号；格式2包含识别历史，兼容格式1；
 * - 账单用短键（`p` 金额、`t` 时间……）压体积；一个几千条的账本 JSON 也就几百 KB；
 * - 可选字段按 null/默认值处理；损坏列表、金额、类型、状态及版本在写库前拒绝，
 *   由调用方显示原因，避免覆盖恢复时静默丢失。
 */
object Backup {

    const val FORMAT = 2
    const val KIND = "AutoBookBackup"

    data class Bill(
        val priceCents: Long,
        val type: String,
        val timeMillis: Long,
        val merchant: String?,
        val method: String?,
        val orderTail: String?,
        val sourcePkg: String,
        val strategy: String,
        val fingerprint: String,
        val createdAt: Long,
        val category: String?,
        val subCategory: String?,
        val account: String?,
        val note: String?,
        val recognitionId: String? = null,
        val id: Long? = null,
    )

    data class Ledger(
        val expense: List<CategoryGroup>,
        val income: List<CategoryGroup>,
        val accounts: List<String>,
        val noteTemplate: String,
        val localRules: LocalRuleSnapshot? = null,
        val hasExpense: Boolean = true,
        val hasIncome: Boolean = true,
        val hasAccounts: Boolean = true,
        val hasNoteTemplate: Boolean = true,
    )

    /** 只备份"用户重新配起来很烦"的开关；识别开不开这类不备份，恢复后由用户自己决定 */
    data class SettingsSnapshot(
        val whitelist: List<String>,
        val minIntervalMs: Long,
        val ocrFallback: Boolean,
        val rootMode: Boolean,
    )

    data class Snapshot(
        val exportedAt: Long,
        val bills: List<Bill>,
        val ledger: Ledger?,
        val settings: SettingsSnapshot?,
        val history: List<RecognitionEntity>? = null,
    )

    /**
     * 恢复结果：数量统计给用户一句"恢复了 N 条"的反馈；
     * `settings` 原样带回来，由 UI 层决定要不要应用（它还要顺带重启 root 服务、刷新 BillKit 配置）。
     */
    data class RestoreStats(
        val bills: Int,
        val ledger: Boolean,
        val settings: SettingsSnapshot? = null,
        val history: Int = 0,
    )

    // ------------------------------------------------------------- 编码

    fun encode(snapshot: Snapshot): String {
        val root = LinkedHashMap<String, Any?>()
        root["kind"] = KIND
        root["format"] = FORMAT
        root["exportedAt"] = snapshot.exportedAt
        root["bills"] = snapshot.bills.map { b ->
            linkedMapOf(
                "p" to b.priceCents,
                "ty" to b.type,
                "t" to b.timeMillis,
                "me" to b.merchant,
                "mo" to b.method,
                "ot" to b.orderTail,
                "sp" to b.sourcePkg,
                "st" to b.strategy,
                "fp" to b.fingerprint,
                "ca" to b.createdAt,
                "cg" to b.category,
                "sc" to b.subCategory,
                "ac" to b.account,
                "no" to b.note,
                "rid" to b.recognitionId,
                "id" to b.id,
            )
        }
        snapshot.history?.let { root["history"] = it.map(RecognitionCodec::encode) }
        snapshot.ledger?.let { l ->
            root["ledger"] = linkedMapOf(
                "expense" to l.expense.map { g -> linkedMapOf("n" to g.name, "s" to g.subs) },
                "income" to l.income.map { g -> linkedMapOf("n" to g.name, "s" to g.subs) },
                "accounts" to l.accounts,
                "noteTemplate" to l.noteTemplate,
                "localRules" to l.localRules?.let { MiniJson.decode(LocalRuleCodec.encode(it)) },
            ).apply {
                if (!l.hasExpense) remove("expense")
                if (!l.hasIncome) remove("income")
                if (!l.hasAccounts) remove("accounts")
                if (!l.hasNoteTemplate) remove("noteTemplate")
            }
        }
        snapshot.settings?.let { s ->
            root["settings"] = linkedMapOf(
                "whitelist" to s.whitelist,
                "minIntervalMs" to s.minIntervalMs,
                "ocrFallback" to s.ocrFallback,
                "rootMode" to s.rootMode,
            )
        }
        return MiniJson.encode(root)
    }

    // ------------------------------------------------------------- 解码

    /**
     * @throws IllegalArgumentException 不是本 App 的备份、格式版本不认识、或 JSON 语法坏了
     */
    fun decode(text: String): Snapshot {
        val root = MiniJson.decode(text) as? Map<*, *>
            ?: throw IllegalArgumentException("备份文件不是 JSON 对象")
        val kind = root["kind"] as? String
        if (kind != null && kind != KIND) throw IllegalArgumentException("不是 AutoBook 备份（kind=$kind）")
        val rawFormat = root["format"]
        require(rawFormat == null || rawFormat is Number) { "备份版本无效" }
        val format = (rawFormat as? Number)?.toInt() ?: 1
        require(rawFormat == null || (rawFormat as Number).toDouble() == format.toDouble()) { "备份版本无效" }
        require(format >= 1) { "备份版本不支持" }
        if (format > FORMAT) throw IllegalArgumentException("备份版本 $format 太新，请升级 App")
        val rawBills = root["bills"] as? List<*>
            ?: throw IllegalArgumentException("备份文件里没有 bills")

        val bills = rawBills.mapNotNull { item ->
            val m = item as? Map<*, *> ?: throw IllegalArgumentException("账单不是对象")
            Bill(
                priceCents = m.num("p").also { require(it > 0) { "账单金额无效" } },
                type = (m.str("ty") ?: "EXPENSE").also { com.example.billkit.BillType.valueOf(it) },
                timeMillis = m.num("t"),
                merchant = m.str("me"),
                method = m.str("mo"),
                orderTail = m.str("ot"),
                sourcePkg = m.str("sp") ?: "",
                strategy = m.str("st") ?: "backup",
                fingerprint = m.str("fp") ?: fallbackFingerprint(m),
                createdAt = m.num("ca").let { if (it == 0L) m.num("t") else it },
                category = m.str("cg"),
                subCategory = m.str("sc"),
                account = m.str("ac"),
                note = m.str("no"),
                recognitionId = m.str("rid"),
                id = (m["id"] as? Number)?.toLong(),
            )
        }
        val ledger = (root["ledger"] as? Map<*, *>)?.let { l ->
            Ledger(
                expense = if (l.containsKey("expense")) strictGroups(l["expense"]) else emptyList(),
                income = if (l.containsKey("income")) strictGroups(l["income"]) else emptyList(),
                accounts = if (l.containsKey("accounts")) strictStrings(l["accounts"]) else emptyList(),
                noteTemplate = if (l.containsKey("noteTemplate")) l["noteTemplate"] as? String
                    ?: throw IllegalArgumentException("备注模板必须为文本") else "{商户}",
                localRules = l["localRules"]?.let { LocalRuleCodec.decode(MiniJson.encode(it)) },
                hasExpense = l.containsKey("expense"), hasIncome = l.containsKey("income"),
                hasAccounts = l.containsKey("accounts"), hasNoteTemplate = l.containsKey("noteTemplate"),
            )
        }
        val settings = (root["settings"] as? Map<*, *>)?.let { s ->
            SettingsSnapshot(
                whitelist = (s["whitelist"] as? List<*>).orEmpty().mapNotNull { it as? String },
                minIntervalMs = s.num("minIntervalMs"),
                ocrFallback = s["ocrFallback"] as? Boolean ?: true,
                rootMode = s["rootMode"] as? Boolean ?: false,
            )
        }
        return Snapshot(
            exportedAt = (root["exportedAt"] as? Number)?.toLong() ?: 0L,
            bills = bills,
            ledger = ledger,
            settings = settings,
            history = if (root.containsKey("history")) {
                val entries = root["history"] as? List<*> ?: throw IllegalArgumentException("history必须是数组")
                entries.map(RecognitionCodec::decode).also { rows ->
                    require(rows.map { it.id }.distinct().size == rows.size) { "识别历史ID重复" }
                    require(rows.map { it.identityKey }.distinct().size == rows.size) { "识别身份重复" }
                }
            } else null,
        )
    }

    /**
     * 老备份缺指纹时补一个，规则与 `BillDraft.fingerprint` 保持一致 ——
     * 否则恢复进来的记录绕不过落库去重，会和以后识别出的同一条重复。
     */
    private fun fallbackFingerprint(m: Map<*, *>): String {
        val pkg = m.str("sp") ?: ""
        val cents = m.num("p")
        val minute = m.num("t") / 60_000
        val tail = m.str("ot") ?: ""
        val base = "$pkg|$cents|$minute" + if (m.str("ot") != null) "|$tail" else ""
        return base + if (m.str("ty") == "REFUND") "|REFUND" else ""
    }

    private fun strictStrings(raw: Any?): List<String> =
        (raw as? List<*> ?: throw IllegalArgumentException("配置清单必须为数组")).map {
            (it as? String)?.takeIf { value -> value.isNotBlank() }
                ?: throw IllegalArgumentException("配置名称无效")
        }

    private fun strictGroups(raw: Any?): List<CategoryGroup> =
        (raw as? List<*> ?: throw IllegalArgumentException("分类必须为数组")).map { item ->
            val m = item as? Map<*, *> ?: throw IllegalArgumentException("分类无效")
            val name = m.str("n")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("分类名称无效")
            CategoryGroup(name, if (m.containsKey("s")) strictStrings(m["s"]) else emptyList())
        }

    private fun Map<*, *>.num(key: String): Long {
        val raw = this[key] ?: return 0L
        val number = raw as? Number ?: throw IllegalArgumentException("备份${key}必须为整数")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) { "备份${key}必须为整数" }
        return value
    }
    private fun Map<*, *>.str(key: String): String? = this[key] as? String
}
