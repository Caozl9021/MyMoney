package io.github.mymoney.data

import android.content.Context
import android.util.Log
import com.example.billkit.BillDraft
import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecRuleEngine
import com.example.billkit.spec.SpecRuleModel
import com.example.billkit.spec.SpecRuleBundle
import com.example.billkit.spec.SpecDraftAdapter
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 原创规则包的宿主：完整包预验证、系统规则事务替换、原始输入保留及草稿安全转换。 */
object SpecRuleStore {

    private const val TAG = "SpecRuleStore"
    private const val RULES_VERSION_KEY = "specRulesVersion"

    /** 一次规则命中的结果，已转成项目内部的草稿形态。 */
    data class Hit(
        val draft: BillDraft,
        val ruleName: String,
        val ruleId: String,
        val autoRecord: Boolean,
    )

    data class Status(
        val ready: Boolean = false,
        val rules: Int = 0,
        val buildMillis: Long = 0L,
        val error: String? = null,
        val version: String = "",
        val available: Int = 0,
        val experimental: Int = 0,
        val capabilities: Set<String> = emptySet(),
    )

    @Volatile private var engine = SpecRuleEngine()
    private val lock = Mutex()

    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()

    @Volatile private var initialized = false
    @Volatile var latestOutcome: SpecRuleEngine.Outcome = SpecRuleEngine.Outcome.NO_MATCH
        private set

    /** 已在库里的规则覆盖了哪些「包名|类型」，决定某条数据要不要交给引擎。 */
    @Volatile private var covered: Set<String> = emptySet()

    fun isReady(): Boolean = initialized && engine.isReady()

    fun covers(packageName: String, type: String): Boolean = CaptureScope.supports(packageName) && "$packageName|$type" in covered

    fun coversNotice(packageName: String): Boolean = covers(packageName, "NOTICE")

    /** 应用启动时调一次。失败不抛给调用方——规则通道坏掉不能拖垮整条采集链路。 */
    suspend fun init(context: Context) {
        runCatching { ensureReady(context) }.onFailure {
            Log.w(TAG, "规则通道初始化失败：${it.message}")
            mutableStatus.value = mutableStatus.value.copy(ready = false, error = it.message)
        }
    }

    suspend fun ensureReady(context: Context) {
        if (initialized && engine.isReady()) return
        lock.withLock {
            if (initialized && engine.isReady()) return
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                val common = readAsset(app, SpecAssets.COMMON_JS)
                val bundle = SpecRuleBundle.load { readAsset(app, it) }
                // 包内禁用的实验脚本也必须能构建；不能把坏脚本藏在enabled=false后面。
                SpecRuleEngine().apply {
                    rebuild(common, bundle.rules.map { it.copy(enabled = true) })
                    close()
                }
                com.example.billkit.spec.SpecCategoryEngine().apply {
                    rebuild(common, readAsset(app, SpecAssets.CATEGORY_JS))
                    close()
                }
                val db = AppDb.get(app)
                val candidate = installBundle(app, db, bundle, common)
                engine = candidate
                covered = bundle.rules.filter { it.enabled }.map { it.app + "|" + it.type }.toSet()
                    .intersect(db.rules().enabledRules().filter { it.creator == "maimi" }
                        .map { it.app + "|" + it.type }.toSet())
                initialized = candidate.isReady()
                mutableStatus.value = Status(
                    ready = initialized, rules = candidate.registeredRules(), buildMillis = candidate.lastBuildMillis,
                    version = bundle.version, available = bundle.rules.size,
                    experimental = bundle.rules.count { !it.enabled }, capabilities = covered,
                )
            }
        }
    }

    /** 只有本类写系统JS；用户JSON覆盖属于另一份配置，不作任意JS执行入口。 */
    internal suspend fun installBundle(
        context: Context,
        db: AppDb,
        bundle: SpecRuleBundle,
        common: String,
        beforeInsert: (() -> Unit)? = null,
    ): SpecRuleEngine {
        require(bundle.rules.all { CaptureScope.supports(it.app) }) { "self规则包只能包含微信和支付宝" }
        val prefs = context.getSharedPreferences("mymoney", Context.MODE_PRIVATE)
        val candidate = SpecRuleEngine()
        db.withTransaction {
            val previous = db.rules().allRules()
            val own = previous.filter { it.creator == "maimi" }.associateBy { it.systemRuleName }
            val rows = bundle.rules.map { rule ->
                val old = own[rule.systemRuleName]
                RuleModelEntity(
                    id = old?.id ?: 0,
                    app = rule.app, type = rule.type, js = rule.js, name = rule.name,
                    systemRuleName = rule.systemRuleName, creator = "maimi", autoRecord = false,
                    struct = if (rule.enabled) "maimi:admitted" else "maimi:experimental",
                    enabled = rule.enabled && !(old?.enabled == false && old.struct != "maimi:experimental"),
                    updateAt = old?.updateAt ?: System.currentTimeMillis(),
                )
            }
            candidate.rebuild(common, rows.filter { it.enabled }.map { it.asModel() })
            // 内容比较而非只比较条数；prefs丢失、超前或落后都能恢复。
            val current = previous.filter { it.creator in setOf("system", "maimi") }
            val expected = rows.map { it.copy(id = 0, updateAt = 0) }.sortedBy { it.systemRuleName }
            val actual = current.map { it.copy(id = 0, updateAt = 0) }.sortedBy { it.systemRuleName }
            if (actual != expected) {
                db.rules().clearBundledRules()
                beforeInsert?.invoke() // 专用隔离Android验证用于模拟事务中断。
                db.rules().insertRules(rows)
            }
        }
        // 与Room不能跨存储事务；规则内容在每次初始化仍逐字段校验，marker失败下次安全重试。
        prefs.edit().putString(RULES_VERSION_KEY, bundle.digest).commit()
        return candidate
    }

    private fun RuleModelEntity.asModel() = SpecRuleModel(
        app = app, type = type, systemRuleName = systemRuleName, name = name,
        creator = creator, autoRecord = autoRecord, enabled = enabled, js = js,
    )

    /**
     * @param pending true 表示规则通道当时不可用、原始数据已标 pending 落库等待重放，
     *   调用方不应把它当成「规则拒绝」写进证据——那会把一条真实凭证记成没命中。
     */
    data class ParseResult(val hit: Hit?, val outcome: SpecRuleEngine.Outcome, val pending: Boolean = false)

    /**
     * 调规则。原始数据先落 `app_data`，再求值——顺序不能反（SPEC §7），
     * 否则规则抛异常时无从回看喂进去的是什么。
     *
     * @return 命中返回 [Hit]；未命中或规则通道不可用返回 null
     */
    suspend fun parse(context: Context, app: String, type: String, raw: String): Hit? =
        parseDetailed(context, app, type, raw).hit

    suspend fun parseDetailed(context: Context, app: String, type: String, raw: String): ParseResult {
        fun finish(hit: Hit?, outcome: SpecRuleEngine.Outcome): ParseResult {
            latestOutcome = outcome
            return ParseResult(hit, outcome)
        }
        // 规则没就绪不是「这条数据不存在」的理由：先等一段有界时间；仍然不成就把原始数据落库
        // 并标 pending-eval，等规则通道恢复后由采集方重放。直接返回会让一笔真实凭证在冷启动
        // 窗口里无声消失——宿主被 ContentProvider 冷拉起时最容易撞上这个窗口。
        runCatching { withTimeoutOrNull(READY_WAIT_MS) { ensureReady(context) } }
        if (!isReady()) {
            persistPending(context, app, type, raw)
            latestOutcome = SpecRuleEngine.Outcome.ERROR
            return ParseResult(null, SpecRuleEngine.Outcome.ERROR, pending = true)
        }
        val db = AppDb.get(context.applicationContext)
        return parseStored(db, engine, app, type, raw, mutableStatus.value.version, System.currentTimeMillis())
    }

    /**
     * 规则通道不可用时的原始数据标记。重放按它找回「到达时规则还没就绪」的行；
     * 用 `version` 列存标记而不新增列，避免为此改 Room schema。
     */
    const val PENDING_VERSION = "pending-eval"

    /** 等规则就绪的上限：够一次冷启动的 Rhino 构建，又不至于把投递队列堵死。 */
    private const val READY_WAIT_MS = 15_000L

    /** 钩子凭证的 type，见 [io.github.mymoney.lsp.HookProtocol.TYPE]。 */
    private const val HOOK_DATA_TYPE = "DATA"

    /**
     * 只有钩子凭证值得留 pending：它是**一次性投递**，丢了没有第二次机会。
     * 通知通道下一轮轮询还能从通知栏重读，页面通道由用户当场可见，都不需要这层补账；
     * 给它们也落 pending 只会攒出没人重放的行。
     */
    internal fun shouldPersistPending(type: String): Boolean = type == HOOK_DATA_TYPE

    private suspend fun persistPending(context: Context, app: String, type: String, raw: String) {
        runCatching {
            persistPendingStored(AppDb.get(context.applicationContext), app, type, raw, System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "pending 原始数据落库失败：${it.message}") }
    }

    /**
     * 落 pending 标记，返回行 id；范围外或类型不该留 pending 时返回 null 且不写库。
     *
     * 拆出带 [db] 的版本是为了让隔离内存 Room 能验证这条链路（生产实例的
     * `AppDb.get` 不能也不该被测试替换）。
     */
    internal suspend fun persistPendingStored(
        db: AppDb, app: String, type: String, raw: String, now: Long,
    ): Long? {
        if (!CaptureScope.supports(app) || !shouldPersistPending(type)) return null
        return db.rules().insertData(
            AppDataEntity(data = raw, type = type, app = app, time = now, version = PENDING_VERSION),
        )
    }

    /** 规则就绪后待重放的原始数据。只看窗口内的行，避免把很久以前的凭证当成新交易重放。 */
    suspend fun pendingData(context: Context, since: Long, limit: Int = 20): List<AppDataEntity> =
        withContext(Dispatchers.IO) { pendingData(AppDb.get(context.applicationContext), since, limit) }

    internal suspend fun pendingData(db: AppDb, since: Long, limit: Int = 20): List<AppDataEntity> =
        db.rules().pendingData(PENDING_VERSION, since, limit)

    /** 重放完成后清掉标记；行本身保留，作为「这条数据到达过」的诊断记录。 */
    suspend fun clearPending(context: Context, id: Long) {
        withContext(Dispatchers.IO) { clearPending(AppDb.get(context.applicationContext), id) }
    }

    internal suspend fun clearPending(db: AppDb, id: Long) = db.rules().setVersion(id, "")

    /** 可由隔离Room runner验证“落盘失败禁止求值”，不替换全局数据库。 */
    internal suspend fun parseStored(
        db: AppDb, activeEngine: SpecRuleEngine, app: String, type: String, raw: String,
        version: String, now: Long,
    ): ParseResult {
        fun finish(hit: Hit?, outcome: SpecRuleEngine.Outcome): ParseResult {
            latestOutcome = outcome
            return ParseResult(hit, outcome)
        }
        if (!CaptureScope.supports(app)) return finish(null, SpecRuleEngine.Outcome.REJECTED)
        val rowId = runCatching {
            db.rules().insertData(AppDataEntity(data = raw, type = type, app = app,
                time = now, version = version))
        }.getOrNull()?.takeIf { it > 0 } ?: return finish(null, SpecRuleEngine.Outcome.ERROR)
        // 原始输入持久化失败时绝不继续识别或投递。
        val linked = if (type == "DATA") runCatching {
            RedpacketCorrelationStore.prepare(db, app, raw, rowId, now)
        }.getOrElse { return finish(null, SpecRuleEngine.Outcome.ERROR) } else RedpacketCorrelationStore.Result.Other
        if (linked is RedpacketCorrelationStore.Result.Rejected) {
            runCatching { db.rules().markIssue(rowId) }
            return finish(null, SpecRuleEngine.Outcome.REJECTED)
        }
        // 只有宿主用已经落盘的两个原始行验证后，才可把联合凭证交给原红包规则。
        // Waiting仍可走原完成凭证规则；无金额邀请和孤立准备回执本身不会命中。
        val parsingRaw = (linked as? RedpacketCorrelationStore.Result.Linked)?.raw ?: raw
        val parsed = activeEngine.parseDetailed(app, type, parsingRaw)
        val result = parsed.result
        if (result == null) {
            if (parsed.outcome == SpecRuleEngine.Outcome.ERROR) runCatching { db.rules().markIssue(rowId) }
            return finish(null, parsed.outcome)
        }
        val draft = SpecDraftAdapter.toDraft(result.bill, app, result.ruleId, now)
            ?: return finish(null, SpecRuleEngine.Outcome.REJECTED)
        runCatching { db.rules().markMatched(rowId, result.ruleName) }
        if (linked is RedpacketCorrelationStore.Result.Linked) {
            runCatching { db.rules().markMatched(linked.messageId, result.ruleName) }
            runCatching { db.rules().markMatched(linked.receiptId, result.ruleName) }
        }
        return finish(Hit(draft, result.ruleName, result.ruleId, false), SpecRuleEngine.Outcome.MATCHED)
    }

    /** 诊断用：最近到达但没命中的原始数据（不含正文脱敏，仅本地查看）。 */
    suspend fun recentMisses(context: Context, app: String, limit: Int = 20): List<AppDataEntity> =
        withContext(Dispatchers.IO) { AppDb.get(context.applicationContext).rules().recentMisses(app, limit) }

    suspend fun recentData(context: Context, limit: Int = 20): List<AppDataEntity> =
        withContext(Dispatchers.IO) { AppDb.get(context.applicationContext).rules().recentData(limit) }

    /** assets 里 `spec/` 下的文件；库模块的 assets 会被合进主 APK，路径不变。 */
    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    /** 分类表与资产表（阶段 5/6 用），同样来自 assets。 */
    fun readCategories(context: Context): List<com.example.billkit.spec.SpecCategory> =
        com.example.billkit.spec.SpecCategory.parseAll(readAsset(context, SpecAssets.CATEGORIES))

    fun readAssets(context: Context): List<com.example.billkit.spec.SpecAsset> =
        com.example.billkit.spec.SpecAsset.parseAll(readAsset(context, SpecAssets.ASSETS))
}
