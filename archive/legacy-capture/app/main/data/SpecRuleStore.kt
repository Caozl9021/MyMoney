package io.github.mymoney.data

import com.example.billkit.notification.NotificationTemplateConfig
import com.example.billkit.notification.NotificationTemplateRegistry
import com.example.billkit.notification.PortableExpenseTemplate
import android.content.Context
import android.util.Log
import com.example.billkit.BillDraft
import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecRuleEngine
import com.example.billkit.spec.SpecRuleModel
import com.example.billkit.spec.SpecRuleBundle
import com.example.billkit.spec.SpecDraftAdapter
import androidx.room.withTransaction
import com.example.billkit.spec.PortableRulePackage
import com.example.billkit.spec.RulePackageFiles
import com.example.billkit.spec.PersonalRule
import com.example.billkit.spec.PersonalRuleCodec
import com.example.billkit.spec.PersonalRuleEngine
import com.example.billkit.spec.PersonalPageRules
import io.github.mymoney.data.rulesync.RuleSyncRepository
import java.io.File
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
        val source: String = "内置", val digest: String = "", val publishedAt: String? = null,
        val entries: List<SpecRuleModel> = emptyList(), val personalRules: List<PersonalRule> = emptyList(), val canRollback: Boolean = false, val manualActivation: Boolean = false,
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
            val stored = runCatching { packageFiles(context).state() }.getOrNull()
            mutableStatus.value = mutableStatus.value.copy(ready = false, error = it.message,
                source = if (stored?.active != null) "导入" else "内置",
                version = stored?.versions?.entries?.firstOrNull { row -> row.value == stored.active }?.key.orEmpty(),
                digest = stored?.active.orEmpty(), canRollback = stored?.canRollback == true)
        }
    }

    /** Rebuild the runtime projection after a remote sync pull. */
    suspend fun refreshFromRepository(context: Context) {
        if (!RuleSyncRepository.isReady()) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                val state = packageFiles(app).state()
                activate(app, state, validate = false)
            }
        }
    }

    suspend fun ensureReady(context: Context) {
        if (initialized && engine.isReady()) return
        lock.withLock {
            if (initialized && engine.isReady()) return
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                // Repository is the authority after migration; this also makes direct SpecRuleStore tests safe.
                if (!RuleSyncRepository.isReady()) {
                    runCatching { RuleSyncRepository.init(app) }
                        .onFailure { Log.w(TAG, "规则同步库初始化失败，暂用旧规则源", it) }
                }
                val store = packageFiles(app)
                var state = store.state()
                if (!File(app.filesDir, "rule-packages/state.json").exists()) {
                    val old = AppDb.get(app).rules().allRules().filter { it.creator == "maimi" && !it.enabled && it.struct != "maimi:experimental" }
                    state = state.copy(
                        enabled = old.associate { it.systemRuleName to false } + effectiveEnabled(state),
                        personalRules = effectivePersonalRules(state),
                    )
                    store.commit(state)
                }
                activate(app, state, validate = true)
                PersonalPageRules.install(effectivePersonalRules(state))
                NotificationTemplateRegistry.install(pendingNotificationTemplates)
                initialized = true

            }
        }
    }

    /** 只有本类写系统JS；用户JSON覆盖属于另一份配置，不作任意JS执行入口。 */
    internal suspend fun installBundle(
        context: Context,
        db: AppDb,
        bundle: SpecRuleBundle,
        common: String,
        explicitEnabled: Map<String, Boolean>? = null,
        beforeInsert: (() -> Unit)? = null,
    ): SpecRuleEngine {
        require(bundle.rules.all { CaptureScope.supports(it.app) }) { "规则包只能包含微信和支付宝" }
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
                    enabled = if (explicitEnabled != null) explicitEnabled[rule.systemRuleName] ?: rule.enabled
                        else rule.enabled && !(old?.enabled == false && old.struct != "maimi:experimental"),
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
        return lock.withLock {
            parseStored(db, engine, app, type, raw, mutableStatus.value.version + "@" + mutableStatus.value.digest.take(16) + ";personal@" + activePersonalDigest, System.currentTimeMillis(), activePersonalRules)
        }
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
        version: String, now: Long, personalRules: List<PersonalRule> = emptyList(),
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
        val personal = PersonalRuleEngine.match(personalRules, app, type, parsingRaw)
        val parsed = if (personal != null) SpecRuleEngine.ParseResult(personal, SpecRuleEngine.Outcome.MATCHED)
            else activeEngine.parseDetailed(app, type, parsingRaw)
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

    suspend fun recentRuleSamples(context: Context): List<AppDataEntity> {
        val rows = recentData(context, 20)
        val page = com.example.billkit.DebugBus.state.value ?: return rows
        if (!CaptureScope.supports(page.packageName) || page.text.isBlank() || page.text.length >= 2000 || page.outcome.contains("[截断]")) return rows
        val snapshot = com.example.billkit.UiSnapshot(page.packageName, page.activityClass, page.text.lines())
        return listOf(AppDataEntity(id = -1, data = PersonalRuleEngine.pageRaw(snapshot, page.atMillis), type = "PAGE",
            app = page.packageName, time = page.atMillis, match = page.outcome.startsWith("draft:"))) + rows
    }

    private var pendingNotificationTemplates: List<PortableExpenseTemplate> = emptyList()
    @Volatile private var activePersonalDigest = ""
    @Volatile private var activePersonalRules: List<PersonalRule> = emptyList()
    @Volatile private var activeFiles: Map<String, String>? = null
    fun activeAsset(context: Context, path: String): String = activeFiles?.get(path.removePrefix("spec/")) ?: readAsset(context, path)
    suspend fun categorySource(context: Context): Triple<String, String, String> {
        ensureReady(context)
        return lock.withLock { Triple(activeAsset(context, SpecAssets.COMMON_JS), activeAsset(context, SpecAssets.CATEGORY_JS), status.value.digest) }
    }
    private fun packageFiles(context: Context) = RulePackageFiles(File(context.filesDir, "rule-packages"))

    private fun effectivePersonalRules(state: RulePackageFiles.State): List<PersonalRule> =
        if (RuleSyncRepository.isReady()) RuleSyncRepository.personalRules() else state.personalRules

    private fun effectiveEnabled(state: RulePackageFiles.State): Map<String, Boolean> =
        if (RuleSyncRepository.isReady()) RuleSyncRepository.officialEnabled() else state.enabled

    private suspend fun activate(context: Context, state: RulePackageFiles.State, validate: Boolean, manual: Boolean = false) {
        val pack = state.active?.let { packageFiles(context).load(it) }
        if (validate) pack?.validate()
        val bundle = pack?.bundle ?: SpecRuleBundle.load { readAsset(context, it) }
        val enabled = effectiveEnabled(state)
        val personal = effectivePersonalRules(state)
        val notifications = pack?.notificationConfig ?: NotificationTemplateConfig.decode(readAsset(context, "spec/"+NotificationTemplateConfig.FILE))
        if (validate) notifications.validateSamples()
        val releaseMetadata = if(pack == null) (MiniJson.decode(readAsset(context,"official-rule-release.json")) as Map<*, *>) else null
        val common = pack?.read(SpecAssets.COMMON_JS) ?: readAsset(context, SpecAssets.COMMON_JS)
        val category = pack?.read(SpecAssets.CATEGORY_JS) ?: readAsset(context, SpecAssets.CATEGORY_JS)
        // Validate every script, including a locally disabled one, before any database mutation.
        SpecRuleEngine().apply { try { rebuild(common, bundle.rules.map { it.copy(enabled = true) }) } finally { close() } }
        com.example.billkit.spec.SpecCategoryEngine().apply { try { rebuild(common, category) } finally { close() } }
        val candidate = installBundle(context, AppDb.get(context), bundle, common, explicitEnabled = enabled)
        val oldEngine = engine
        engine = candidate
        oldEngine.close()
        activeFiles = pack?.files
        activePersonalRules = personal
        activePersonalDigest = PortableRulePackage.hash(PersonalRuleCodec.export(personal).toByteArray()).take(16)
        covered = bundle.rules.filter { enabled[it.systemRuleName] ?: it.enabled }.map { it.app + "|" + it.type }.toSet() + personal.filter { it.enabled }.map { it.app + "|" + it.inputType }
        pendingNotificationTemplates = notifications.templates.filter { t -> enabled[t.ruleId] ?: bundle.rules.first { it.systemRuleName==t.ruleId }.enabled }
        mutableStatus.value = Status(ready = true, rules = candidate.registeredRules(), buildMillis = candidate.lastBuildMillis,
            version = pack?.version ?: (releaseMetadata?.get("packageVersion") as? String ?: bundle.version), available = bundle.rules.size, capabilities = covered,
            source = if (pack == null) "内置" else "导入", digest = pack?.digest ?: bundle.digest,
            publishedAt = pack?.publishedAt ?: releaseMetadata?.get("publishedAt") as? String,
            personalRules = personal, entries = bundle.rules.map { it.copy(enabled = enabled[it.systemRuleName] ?: it.enabled) }, canRollback = state.canRollback, manualActivation = manual)
        if (RuleSyncRepository.isReady()) {
            RuleSyncRepository.markProjectionActive(RuleSyncRepository.snapshot().generation)
        }
    }

    suspend fun preparePackage(context: Context, bytes: ByteArray): PortableRulePackage = withContext(Dispatchers.IO) {
        val pack = PortableRulePackage.load(bytes)
        pack.validate()
        val state = packageFiles(context).state()
        require(state.versions[pack.version]?.let { it == pack.digest } != false) { "同版本内容不同，请发布新的规则版本" }
        pack
    }

    fun describePackage(pack: PortableRulePackage): String {
        val current = status.value
        val old = current.entries.associateBy { it.systemRuleName }
        val fresh = pack.bundle.rules.associateBy { it.systemRuleName }
        val added = fresh.keys - old.keys
        val removed = old.keys - fresh.keys
        val updated = fresh.keys.intersect(old.keys).filter { fresh[it]?.copy(enabled = true) != old[it]?.copy(enabled = true) ||
            fresh.keys.indexOf(it) != old.keys.indexOf(it) }
        val downgrade = current.source == "导入" && PortableRulePackage.compareVersions(pack.version, current.version) < 0
        return "${current.source} ${current.version} → 导入 ${pack.version}" +
            (if (downgrade) "\n这是降级操作" else "") +
            "\n新增 ${added.size} · 更新 ${updated.size} · 移除 ${removed.size}" +
            (added.map { "新增：" + fresh.getValue(it).name.removePrefix("麦米·") } +
                updated.map { "更新：" + fresh.getValue(it).name.removePrefix("麦米·") } +
                removed.map { "移除：" + old.getValue(it).name.removePrefix("麦米·") }).joinToString("\n", prefix = "\n") + "\n" + pack.notes +
            "\n通知模板：${pack.notificationConfig?.templates?.size?.toString() ?: "沿用应用内置"}；付款通知开关同时控制这些模板。\n整包同时更新公共脚本、分类与账户建议。保留用户已有配置。\n导入后用于后续识别，不重新处理历史交易。"
    }

    private suspend fun change(context: Context, target: RulePackageFiles.State) {
        val store = packageFiles(context)
        val previous = store.state()
        try {
            activate(context, target, validate = true, manual = true)
            store.commit(target.copy(personalRules = effectivePersonalRules(target), enabled = effectiveEnabled(target)))
            PersonalPageRules.install(effectivePersonalRules(target)) // publish only after durable commit
            NotificationTemplateRegistry.install(pendingNotificationTemplates)
            // commit point; if killed before this, startup rebuilds the previous projection.
        } catch (t: Throwable) {
            runCatching { activate(context, previous, validate = true, manual = true) }.onFailure {
                initialized = false
                mutableStatus.value = status.value.copy(ready = false, error = "规则恢复失败，请回退或恢复内置")
            }
            throw t
        }
        store.prune(target)
    }

    suspend fun importPackage(context: Context, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val pack = preparePackage(context, bytes)
        lock.withLock {
            val store = packageFiles(context); val state = store.state()
            require(state.versions[pack.version]?.let { it == pack.digest } != false) { "同版本内容不同" }
            if (state.active == pack.digest) return@withLock
            store.stage(bytes, pack)
            change(context, state.copy(active = pack.digest, previous = state.active, canRollback = true,
                versions = state.versions + (pack.version to pack.digest)))
        }
    }
    suspend fun rollbackPackage(context: Context) = withContext(Dispatchers.IO) { lock.withLock {
        val state = packageFiles(context).state(); require(state.canRollback) { "没有可回退的规则包" }
        change(context, state.copy(active = state.previous, previous = state.active))
    } }
    suspend fun restoreBuiltin(context: Context) = withContext(Dispatchers.IO) { lock.withLock {
        val state = packageFiles(context).state()
        if (state.active != null) change(context, state.copy(active = null, previous = state.active, canRollback = true))
        else if (!isReady()) change(context, state.copy(active = null))
    } }
    suspend fun setRuleEnabled(context: Context, id: String, enabled: Boolean) = withContext(Dispatchers.IO) { lock.withLock {
        require(status.value.entries.any { it.systemRuleName == id }) { "规则ID不存在" }
        val state = packageFiles(context).state()
        if (RuleSyncRepository.isReady()) {
            RuleSyncRepository.setOfficialRuleEnabled(id, enabled)
            change(context, state.copy(enabled = RuleSyncRepository.officialEnabled()))
        } else {
            change(context, state.copy(enabled = state.enabled + (id to enabled)))
        }
    } }

    suspend fun savePersonalRule(context: Context, rule: PersonalRule) = withContext(Dispatchers.IO) { lock.withLock {
        rule.validate()
        val state = packageFiles(context).state()
        if (RuleSyncRepository.isReady()) {
            RuleSyncRepository.savePersonalRule(rule)
            change(context, state.copy(personalRules = RuleSyncRepository.personalRules()))
        } else {
            val old = state.personalRules.firstOrNull { it.id == rule.id }
            val saved = if(old != null && old.copy(enabled=rule.enabled,updatedAt=null)==rule.copy(updatedAt=null)) rule.copy(updatedAt=old.updatedAt) else rule.copy(updatedAt=System.currentTimeMillis())
            val rules = listOf(saved) + state.personalRules.filterNot { it.id == rule.id }
            require(rules.size <= 100) { "个人规则最多100条" }
            change(context, state.copy(personalRules = rules))
        }
    } }
    suspend fun deletePersonalRule(context: Context, id: String) = withContext(Dispatchers.IO) { lock.withLock {
        val state = packageFiles(context).state()
        if (RuleSyncRepository.isReady()) {
            RuleSyncRepository.deletePersonalRule(id)
            change(context, state.copy(personalRules = RuleSyncRepository.personalRules()))
        } else change(context, state.copy(personalRules = state.personalRules.filterNot { it.id == id }))
    } }
    suspend fun importPersonalRules(context: Context, text: String) = withContext(Dispatchers.IO) { lock.withLock {
        val incoming = PersonalRuleCodec.decode(text)
        val state = packageFiles(context).state()
        if (RuleSyncRepository.isReady()) {
            RuleSyncRepository.importPersonalRules(text)
            change(context, state.copy(personalRules = RuleSyncRepository.personalRules()))
        } else {
            val rules = incoming + state.personalRules.filterNot { old -> incoming.any { it.id == old.id } }
            require(rules.size <= 100) { "合并后个人规则超过100条" }
            change(context, state.copy(personalRules = rules))
        }
    } }
    suspend fun exportPersonalRules(context: Context): String = withContext(Dispatchers.IO) { lock.withLock {
        if (RuleSyncRepository.isReady()) RuleSyncRepository.exportPersonalRules()
        else PersonalRuleCodec.export(packageFiles(context).state().personalRules)
    } }

    /** Pure preview: no raw persistence, correlation write, learning, dedup or delivery. */
    suspend fun preview(context: Context, row: AppDataEntity): String = withContext(Dispatchers.IO) {
        ensureReady(context)
        lock.withLock {
            val personal = PersonalRuleEngine.match(activePersonalRules, row.app, row.type, row.data)
            val parsed = if (personal != null) SpecRuleEngine.ParseResult(personal, SpecRuleEngine.Outcome.MATCHED) else engine.parseDetailed(row.app, row.type, row.data)
            val hit = parsed.result
            if (hit == null) "没有匹配到规则" else
                "${hit.ruleName.removePrefix("麦米·")}\n" +
                    "${when(hit.bill.type) { "Income" -> "收入"; "Refund" -> "退款"; else -> "支出" }} ¥${String.format(java.util.Locale.US, "%.2f", hit.bill.priceCents / 100.0)}\n" +
                    "商户：${hit.bill.shopName.ifBlank { "未提供" }}\n支付方式：${hit.bill.accountNameFrom.ifBlank { "未提供" }}\n仅预览，不生成账单或确认卡"
        }
    }

    /** assets 里 `spec/` 下的文件；库模块的 assets 会被合进主 APK，路径不变。 */
    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    /** 分类表与资产表（阶段 5/6 用），同样来自 assets。 */
    fun readCategories(context: Context): List<com.example.billkit.spec.SpecCategory> =
        com.example.billkit.spec.SpecCategory.parseAll(activeAsset(context, SpecAssets.CATEGORIES))

    fun readAssets(context: Context): List<com.example.billkit.spec.SpecAsset> =
        com.example.billkit.spec.SpecAsset.parseAll(activeAsset(context, SpecAssets.ASSETS))
}
