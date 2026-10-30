package io.github.mymoney.data

/**
 * 备份文件的纯数据层：只做「对象 ⇄ JSON 文本」，不碰 Context / Room。
 *
 * 分成两半是为了能测：
 * - 这里（[Backup]）纯逻辑，JVM 单测直接跑；
 * - 读写文件、查库、写偏好，在 `BackupStore` 里。
 *
 * 格式约定：
 * - 顶层 `format` 是版本号；格式4包含采集身份/来源元数据，兼容格式1/2/3与资产账户流水；
 * - 账单用短键（`p` 金额、`t` 时间……）压体积；一个几千条的账本 JSON 也就几百 KB；
 * - 可选字段按 null/默认值处理；损坏列表、金额、类型、状态及版本在写库前拒绝，
 *   由调用方显示原因，避免覆盖恢复时静默丢失。
 */
object Backup {

    const val FORMAT = 8
    const val KIND = "MyMoneyBackup"

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
        val accountId: String? = null,
        val transferAccountId: String? = null,
        val assetLinked: Boolean = false,
        val assetKey: String = "",
        val transferFeeCents: Long = 0,
        val feeParentKey: String? = null,
        val transferAccount: String? = null,
        val productName: String? = null,
        val productSource: String? = null,
        val noteOrigin: String? = null,
        val noteTemplateSnapshot: String? = null,
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
        val monthStartDay: Int? = null,
    )

    /** 只备份"用户重新配起来很烦"的开关；识别开不开这类不备份，恢复后由用户自己决定 */
    data class SettingsSnapshot(
        val whitelist: List<String>,
        val minIntervalMs: Long,
        val rootMode: Boolean,
    )

    data class Snapshot(
        val exportedAt: Long,
        val bills: List<Bill>,
        val ledger: Ledger?,
        val settings: SettingsSnapshot?,
        val history: List<RecognitionEntity>? = null,
        val assets: AssetSnapshot? = null,
        val capture: CaptureSnapshot? = null,
        val imports: List<ImportIdentity>? = null,
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
        val assets: Boolean = false,
        val assetWarning: String? = null,
    )

    // ------------------------------------------------------------- 编码

    fun encode(snapshot: Snapshot): String {
        validateAssetBills(snapshot.bills, snapshot.assets)
        val root = LinkedHashMap<String, Any?>()
        root["kind"] = KIND
        root["format"] = FORMAT
        root["exportedAt"] = snapshot.exportedAt
        snapshot.imports?.let { rows ->
            ImportIdentityCodec.validate(rows)
            root["imports"] = rows.map { mapOf("key" to it.sourceKey, "hash" to it.contentHash) }
        }
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
                "no" to b.note, "pn" to b.productName, "ps" to b.productSource,
                "noOrigin" to b.noteOrigin, "nt" to b.noteTemplateSnapshot,
                "rid" to b.recognitionId,
                "id" to b.id,
                "aid" to b.accountId, "taid" to b.transferAccountId,
                "al" to b.assetLinked, "ak" to b.assetKey,
                "tf" to b.transferFeeCents, "fpk" to b.feeParentKey, "tac" to b.transferAccount,
            )
        }
        snapshot.assets?.let { root["assets"] = AssetBackupCodec.encode(it) }
        snapshot.history?.let { root["history"] = it.map(RecognitionCodec::encode) }
        snapshot.capture?.let {
            root["capture"] = CaptureMetadataCodec.encode(it, snapshot.history ?: throw IllegalArgumentException("采集元数据缺少history"))
        }
        snapshot.ledger?.let { l ->
            root["ledger"] = linkedMapOf(
                "expense" to l.expense.map { g -> linkedMapOf("n" to g.name, "s" to g.subs) },
                "income" to l.income.map { g -> linkedMapOf("n" to g.name, "s" to g.subs) },
                "accounts" to l.accounts,
                "noteTemplate" to l.noteTemplate,
                "localRules" to l.localRules?.let { MiniJson.decode(LocalRuleCodec.encode(it)) },
            ).apply {
                l.monthStartDay?.let { day ->
                    require(day in 1..31) { "每月起始日须为1–31日" }
                    put("monthStartDay", day)
                }
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
        if (kind != null && kind != KIND) throw IllegalArgumentException("不是麦米备份（kind=$kind）")
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
                note = m.str("no"), productName = m.str("pn"), productSource = m.str("ps"),
                noteOrigin = m.str("noOrigin"), noteTemplateSnapshot = m.str("nt"),
                recognitionId = m.str("rid"),
                id = if (m["id"] == null) null else m.num("id"),
                accountId = if (format >= 3) m.optionalAssetText("aid") else null,
                transferAccountId = if (format >= 3) m.optionalAssetText("taid") else null,
                assetLinked = if (format >= 3 && m.containsKey("al")) m["al"] as? Boolean
                    ?: throw IllegalArgumentException("账单余额联动标志无效") else false,
                assetKey = if (format >= 3) m.optionalAssetText("ak").orEmpty() else "",
                transferFeeCents = if (format >= 3) m.num("tf").also { require(it >= 0) { "转账手续费无效" } } else 0,
                feeParentKey = if (format >= 3) m.optionalAssetText("fpk") else null,
                transferAccount = if (format >= 5) m.optionalAssetText("tac") else null,
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
                monthStartDay = if (l.containsKey("monthStartDay")) {
                    require(format >= 6) { "旧备份版本不能包含记账周期" }
                    l.num("monthStartDay").also { require(l["monthStartDay"] != null && it in 1L..31L) { "每月起始日须为1–31日" } }.toInt()
                } else null,
            )
        }
        val settings = (root["settings"] as? Map<*, *>)?.let { s ->
            SettingsSnapshot(
                whitelist = (s["whitelist"] as? List<*>).orEmpty().mapNotNull { it as? String },
                minIntervalMs = s.num("minIntervalMs"),
                // 旧备份（JSON ≤7）里还有 "ocrFallback"，OCR 删除后直接忽略：
                // 恢复时不该因为一个已废弃的开关而失败。
                rootMode = s["rootMode"] as? Boolean ?: false,
            )
        }
        val assets = if (root.containsKey("assets")) {
            require(format >= 3) { "旧格式不能包含资产段" }
            AssetBackupCodec.decode(root["assets"])
        } else null
        validateAssetBills(bills, assets)
        val history = if (root.containsKey("history")) {
            val entries = root["history"] as? List<*> ?: throw IllegalArgumentException("history必须是数组")
            entries.map(RecognitionCodec::decode).also { rows ->
                require(rows.map { it.id }.distinct().size == rows.size) { "识别历史ID重复" }
                require(rows.map { it.identityKey }.distinct().size == rows.size) { "识别身份重复" }
                val ids = rows.map { it.id }.toSet()
                require(rows.all { it.reviewOf == null || (it.reviewOf != it.id && it.reviewOf in ids) }) { "待核对关联无效" }
            }
        } else null
        val capture = if (root.containsKey("capture")) {
            require(format >= 4) { "旧格式不能包含采集元数据" }
            CaptureMetadataCodec.decode(root["capture"], history ?: throw IllegalArgumentException("采集元数据缺少history"))
        } else null
        return Snapshot(
            exportedAt = (root["exportedAt"] as? Number)?.toLong() ?: 0L,
            bills = bills,
            ledger = ledger,
            settings = settings,
            assets = assets,
            history = history,
            capture = capture,
            imports = if (root.containsKey("imports")) {
                require(format >= 5) { "旧格式不能包含导入身份" }
                ImportIdentityCodec.decode(root["imports"])
            } else null,
        )
    }

    /** Cross-reference checks run before any restore transaction can delete existing data. */
    fun validateAssetBills(bills: List<Bill>, assets: AssetSnapshot?) {
        val keys = bills.map { it.assetKey }.filter(String::isNotEmpty)
        require(keys.distinct().size == keys.size) { "账单资产身份重复" }
        if (assets == null) {
            require(bills.none { it.accountId != null || it.assetLinked || it.transferAccountId != null || it.transferFeeCents != 0L || it.feeParentKey != null }) {
                "账单包含资产关联但缺少资产段"
            }
            return
        }
        val accounts = assets.accounts.associateBy { it.id }
        val byKey = bills.filter { it.assetKey.isNotBlank() }.associateBy { it.assetKey }
        bills.filter { it.type == "TRANSFER" }.forEach { principal ->
            val fees = bills.filter { it.feeParentKey == principal.assetKey && it.feeParentKey != null }
            if (principal.transferFeeCents == 0L) require(fees.isEmpty()) { "零手续费转账包含费用账单" }
            else require(fees.size == 1 && fees.single().type == "EXPENSE" &&
                fees.single().priceCents == principal.transferFeeCents && fees.single().accountId == principal.accountId) {
                "转账手续费账单缺失或金额不一致"
            }
        }
        bills.forEach { b ->
            listOfNotNull(b.accountId, b.transferAccountId).forEach { require(it in accounts) { "账单引用不存在的资产账户" } }
            require(b.transferAccountId == null || (b.type == "TRANSFER" && b.transferAccountId != b.accountId)) { "转账账户无效" }
            require(b.transferFeeCents == 0L || b.type == "TRANSFER") { "手续费关联无效" }
            b.feeParentKey?.let { key ->
                require(b.type == "EXPENSE" && byKey[key]?.type == "TRANSFER") { "手续费引用不存在的转账" }
            }
            val entries = assets.entries.filter { it.billKey == b.assetKey }
            val totals = entries.groupBy { it.accountId }.mapValues { (_, list) ->
                list.fold(0L) { n, e -> try { Math.addExact(n, e.deltaCents) } catch (_: ArithmeticException) { throw IllegalArgumentException("账单流水金额超出范围") } }
            }.filterValues { it != 0L }
            if (b.assetLinked) {
                require(b.assetKey.isNotBlank() && b.accountId != null && accounts[b.accountId]?.initialized == true) { "已联动账单缺少有效账户或身份" }
                if (b.type == "TRANSFER") require(b.transferAccountId != null && accounts[b.transferAccountId]?.initialized == true) { "已联动转账缺少转入账户" }
                val expected = if (b.type == "TRANSFER") mapOf(b.accountId!! to -b.priceCents, b.transferAccountId!! to b.priceCents)
                    else mapOf(b.accountId!! to when (b.type) { "EXPENSE" -> -b.priceCents; "INCOME", "REFUND" -> b.priceCents; else -> 0L })
                require(totals == expected.filterValues { it != 0L }) { "账单与资产流水金额不一致" }
            } else require(totals.isEmpty()) { "未联动账单仍含有效资产分录，未恢复" }
        }
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

    private fun Map<*, *>.optionalAssetText(key: String): String? = when (val raw = this[key]) {
        null -> null
        is String -> raw
        else -> throw IllegalArgumentException("账单${key}必须为文字")
    }

    private fun Map<*, *>.num(key: String): Long {
        val raw = this[key] ?: return 0L
        return when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            else -> throw IllegalArgumentException("备份${key}必须为整数")
        }
    }
    private fun Map<*, *>.str(key: String): String? = this[key] as? String
}
