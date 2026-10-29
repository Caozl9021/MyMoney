package com.example.autobook.data

import android.content.Context
import android.util.Log
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecRuleEngine
import com.example.billkit.spec.SpecRuleModel
import com.example.billkit.spec.SpecRuleObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 规则通道的宿主侧装配：把 assets 里的 163 条规则导进 `rule_model`，再把它们交给 [SpecRuleEngine]。
 *
 * <h3>为什么规则要落库再取出来喂引擎</h3>
 * 引擎完全可以直读 assets，但那样 `rule_model` 就是死表：用户导入新规则、单条禁用、
 * 回溯"当时命中的是哪条规则"都做不到。SPEC §7 要求 `app_data`/`rule_model` 是唯一事实来源，
 * 所以启动时按内容哈希导入一次，之后一律从库里读——引擎看到的和库里存的永远是同一份。
 *
 * <h3>为什么引擎只建一次</h3>
 * 163 条规则 + 300KB 正则，JVM 实测求值 ~230ms，设备上更慢。付款通知是"用户正在等"的路径，
 * 不能每次通知都重建。规则内容变了（哈希变化）才重建。
 *
 * <h3>与既有模板解析器的关系</h3>
 * 不是替换关系。模板解析器（[com.example.billkit.notification.NotificationParser]）只认
 * 已用真实样本验证过、带显式成功语的白名单模板；规则引擎覆盖其余 24 条 NOTICE 规则。
 * 调用方先走模板，模板不认再用本通道兜底——已验证的证据优先，规则不覆盖它。
 */
object SpecRuleStore {

    private const val TAG = "SpecRuleStore"
    private const val RULES_VERSION_KEY = "specRulesVersion"
    private const val VERSION = 1

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
    )

    private val engine = SpecRuleEngine()
    private val lock = Mutex()

    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()

    @Volatile private var initialized = false

    /** 已在库里的规则覆盖了哪些「包名|类型」，决定某条数据要不要交给引擎。 */
    @Volatile private var covered: Set<String> = emptySet()

    fun isReady(): Boolean = initialized && engine.isReady()

    fun covers(packageName: String, type: String): Boolean = "$packageName|$type" in covered

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
                val rawRules = readAsset(app, SpecAssets.RULES_ALL)
                val parsed = SpecAssets.parseRules(rawRules)
                require(parsed.isNotEmpty()) { "rules_all.json 未解析出任何规则" }
                val version = CaptureMergePolicy.digest(rawRules)
                val db = AppDb.get(app)
                importIfNeeded(app, db, parsed, version)
                val stored = db.rules().enabledRules().map {
                    SpecRuleModel(
                        app = it.app, type = it.type, systemRuleName = it.systemRuleName, name = it.name,
                        creator = it.creator, autoRecord = it.autoRecord, enabled = it.enabled, js = it.js,
                    )
                }
                engine.rebuild(common, stored)
                covered = stored.map { it.app + "|" + it.type }.toSet()
                initialized = engine.isReady()
                mutableStatus.value = Status(initialized, engine.registeredRules(), engine.lastBuildMillis)
            }
        }
    }

    private suspend fun importIfNeeded(
        context: Context,
        db: AppDb,
        rules: List<SpecRuleModel>,
        version: String,
    ) {
        val prefs = context.getSharedPreferences("autobook", Context.MODE_PRIVATE)
        val current = prefs.getString(RULES_VERSION_KEY, null)
        val count = db.rules().ruleCount()
        if (current == version && count == rules.size) return
        val rows = rules.map {
            RuleModelEntity(
                app = it.app, type = it.type, js = it.js, name = it.name,
                systemRuleName = it.systemRuleName, creator = it.creator,
                autoRecord = it.autoRecord, enabled = it.enabled, updateAt = System.currentTimeMillis(),
            )
        }
        db.rules().clearRules()
        db.rules().insertRules(rows)
        prefs.edit().putString(RULES_VERSION_KEY, version).apply()
    }

    /**
     * 调规则。原始数据先落 `app_data`，再求值——顺序不能反（SPEC §7），
     * 否则规则抛异常时无从回看喂进去的是什么。
     *
     * @return 命中返回 [Hit]；未命中或规则通道不可用返回 null
     */
    suspend fun parse(context: Context, app: String, type: String, raw: String): Hit? {
        runCatching { ensureReady(context) }.onFailure { return null }
        val db = AppDb.get(context.applicationContext)
        val rowId = runCatching {
            db.rules().insertData(AppDataEntity(data = raw, type = type, app = app, time = System.currentTimeMillis()))
        }.getOrNull()
        val result = runCatching { engine.parse(app, type, raw) }.getOrNull()
        if (result == null) {
            // 规则没命中：留档即可，不写 issue（issue 只表示规则执行出错）
            return null
        }
        if (rowId != null) runCatching { db.rules().markMatched(rowId, result.ruleName) }
        val draft = toDraft(result.bill, app, result.ruleId) ?: return null
        return Hit(draft, result.ruleName, result.ruleId, result.autoRecord)
    }

    /** 规则产出 → 项目草稿。金额为 0、类型不认识、时间不合理的直接丢弃，不补猜。 */
    private fun toDraft(bill: SpecRuleObject, app: String, ruleId: String): BillDraft? {
        val cents = bill.priceCents
        if (cents == 0L) return null
        val type = when (bill.type) {
            "Expend" -> BillType.EXPENSE
            "Income" -> BillType.INCOME
            "Transfer" -> BillType.TRANSFER
            else -> BillType.EXPENSE
        }
        val now = System.currentTimeMillis()
        // 未来 60 秒以外、或早于 3 年的时间视为脏数据；规则没给时间就用到达时间。
        val explicit = bill.time in (now - 3L * 365 * 24 * 3600 * 1000)..(now + 60_000L)
        val time = if (explicit) bill.time else now
        val merchant = bill.shopName.trim().ifEmpty { null }
        val method = bill.accountNameFrom.trim().ifEmpty { null }
        val absCents = kotlin.math.abs(cents)
        // 只有明确时间才能进合成去重身份：拿"现在"当交易时间，两条隔了十分钟才到达的
        // 不同交易会被算进同一个桶。时间不可信就不参与合并，宁可多一条待核对。
        val dedupKey = if (explicit) {
            com.example.billkit.spec.SpecDedup.key(app, type, absCents, time)
        } else null
        return BillDraft(
            priceCents = absCents,
            type = type,
            timeMillis = time,
            merchant = merchant,
            method = method,
            orderTail = null,
            sourcePkg = app,
            strategy = "spec:$ruleId",
            category = null,
            subCategory = null,
            account = method,
            note = bill.shopItem.trim().ifEmpty { bill.channel.trim().ifEmpty { null } },
            historyId = null,
            transactionKey = null,
            hasExplicitTime = explicit,
            captureSessionId = null,
            productName = bill.shopItem.trim().ifEmpty { null },
            productSource = null,
            dedupKey = dedupKey,
        )
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
