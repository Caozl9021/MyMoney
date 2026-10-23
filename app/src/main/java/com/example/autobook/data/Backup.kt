package com.example.autobook.data

/**
 * 备份文件的纯数据层：只做「对象 ⇄ JSON 文本」，不碰 Context / Room。
 *
 * 分成两半是为了能测：
 * - 这里（[Backup]）纯逻辑，JVM 单测直接跑；
 * - 读写文件、查库、写偏好，在 `BackupStore` 里。
 *
 * 格式约定：
 * - 顶层 `format` 是版本号，将来加字段时用它判断兼容性（现在是 1）；
 * - 账单用短键（`p` 金额、`t` 时间……）压体积；一个几千条的账本 JSON 也就几百 KB；
 * - 缺字段一律按 null/默认值处理，**不抛异常** —— 手工改坏的备份不该让 App 崩，
 *   但结构性错误（不是 JSON、没有 bills）要抛，由调用方提示用户。
 */
object Backup {

    const val FORMAT = 1
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
    )

    data class Ledger(
        val expense: List<CategoryGroup>,
        val income: List<CategoryGroup>,
        val accounts: List<String>,
        val noteTemplate: String,
        val localRules: LocalRuleSnapshot? = null,
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
    )

    /**
     * 恢复结果：数量统计给用户一句"恢复了 N 条"的反馈；
     * `settings` 原样带回来，由 UI 层决定要不要应用（它还要顺带重启 root 服务、刷新 BillKit 配置）。
     */
    data class RestoreStats(
        val bills: Int,
        val ledger: Boolean,
        val settings: SettingsSnapshot? = null,
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
            )
        }
        snapshot.ledger?.let { l ->
            root["ledger"] = linkedMapOf(
                "expense" to l.expense.map { g -> linkedMapOf("n" to g.name, "s" to g.subs) },
                "income" to l.income.map { g -> linkedMapOf("n" to g.name, "s" to g.subs) },
                "accounts" to l.accounts,
                "noteTemplate" to l.noteTemplate,
                "localRules" to l.localRules?.let { MiniJson.decode(LocalRuleCodec.encode(it)) },
            )
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
        val format = (root["format"] as? Number)?.toInt() ?: FORMAT
        if (format > FORMAT) throw IllegalArgumentException("备份版本 $format 太新，请升级 App")
        val rawBills = root["bills"] as? List<*>
            ?: throw IllegalArgumentException("备份文件里没有 bills")

        val bills = rawBills.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            Bill(
                priceCents = m.num("p"),
                type = m.str("ty") ?: "EXPENSE",
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
            )
        }
        val ledger = (root["ledger"] as? Map<*, *>)?.let { l ->
            Ledger(
                expense = groups(l["expense"]),
                income = groups(l["income"]),
                accounts = (l["accounts"] as? List<*>).orEmpty().mapNotNull { it as? String },
                noteTemplate = l.str("noteTemplate") ?: "{商户}",
                localRules = l["localRules"]?.let { LocalRuleCodec.decode(MiniJson.encode(it)) },
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

    private fun groups(raw: Any?): List<CategoryGroup> =
        (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val name = m.str("n") ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            CategoryGroup(
                name = name,
                subs = (m["s"] as? List<*>).orEmpty().mapNotNull { it as? String }.filter { it.isNotBlank() },
            )
        }

    private fun Map<*, *>.num(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
    private fun Map<*, *>.str(key: String): String? = this[key] as? String
}
