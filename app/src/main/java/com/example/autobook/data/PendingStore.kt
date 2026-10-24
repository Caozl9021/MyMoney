package com.example.autobook.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待确认草稿的落盘队列。
 *
 * 为什么需要：后台（root 轮询）拉起确认页会被 Android 10+ 的
 * 「后台启动 Activity 限制」静默拦截，草稿不能只活在内存里。
 * 现在：拦截了也能在通知栏点开、或回 App 里补记。
 *
 * 用 SharedPreferences 存 JSON 就够（条数上限 20，非高频写）。
 */
object PendingStore {

    private const val PREFS = "pending_drafts"
    private const val KEY = "items"
    private const val MAX = 20

    private lateinit var prefs: SharedPreferences
    private val _items = MutableStateFlow<List<BillDraft>>(emptyList())
    val items: StateFlow<List<BillDraft>> = _items

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _items.value = load()
    }

    fun add(draft: BillDraft) {
        ensureInit()
        val next = (load().filterNot { it.fingerprint == draft.fingerprint } + draft)
            .takeLast(MAX)
        persist(next)
    }

    fun remove(fingerprint: String) {
        ensureInit()
        persist(load().filterNot { it.fingerprint == fingerprint })
    }

    fun findBy(fingerprint: String): BillDraft? = load().firstOrNull { it.fingerprint == fingerprint }

    fun all(): List<BillDraft> {
        ensureInit()
        return _items.value
    }

    fun clear() {
        ensureInit()
        persist(emptyList())
    }

    /** Strict migration read: one bad entry leaves the original preferences untouched. */
    internal fun readForMigration(): List<BillDraft> {
        ensureInit()
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: error("旧待确认数据不是对象")
            BillType.valueOf(item.getString("type"))
            require(item.getLong("priceCents") > 0) { "旧待确认金额无效" }
            fromJson(item) ?: error("旧待确认数据第${index + 1}条损坏，已保留原数据")
        }
    }

    internal fun clearAfterMigration() {
        check(prefs.edit().remove(KEY).commit()) { "旧待确认队列迁移清理失败，请重试" }
        _items.value = emptyList()
    }

    private fun ensureInit() {
        check(::prefs.isInitialized) { "PendingStore.init(context) 未调用" }
    }

    private fun persist(list: List<BillDraft>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
        _items.value = list
    }

    private fun load(): List<BillDraft> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { fromJson(array.optJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toJson(draft: BillDraft): JSONObject = JSONObject().apply {
        put("priceCents", draft.priceCents)
        // 持久化枚举名，REFUND 与原有三类使用相同往返协议。
        put("type", draft.type.name)
        put("timeMillis", draft.timeMillis)
        put("merchant", draft.merchant ?: "")
        put("method", draft.method ?: "")
        put("orderTail", draft.orderTail ?: "")
        put("sourcePkg", draft.sourcePkg)
        put("strategy", draft.strategy)
        // 分类/账户/备注是用户在确认卡里最在意的三项，不落盘的话
        // 从队列/通知回来补记时会被清空（旧版就是在这里丢的）
        put("fingerprint", draft.fingerprint)
        put("category", draft.category ?: "")
        put("subCategory", draft.subCategory ?: "")
        put("account", draft.account ?: "")
        put("note", draft.note ?: "")
    }

    private fun fromJson(json: JSONObject?): BillDraft? {
        json ?: return null
        return try {
            BillDraft(
                priceCents = json.getLong("priceCents"),
                type = runCatching { BillType.valueOf(json.getString("type")) }
                    .getOrDefault(BillType.EXPENSE),
                timeMillis = json.getLong("timeMillis"),
                merchant = json.optString("merchant").ifEmpty { null },
                method = json.optString("method").ifEmpty { null },
                orderTail = json.optString("orderTail").ifEmpty { null },
                sourcePkg = json.optString("sourcePkg"),
                strategy = json.optString("strategy"),
                category = json.optString("category").ifEmpty { null },
                subCategory = json.optString("subCategory").ifEmpty { null },
                account = json.optString("account").ifEmpty { null },
                note = json.optString("note").ifEmpty { null },
            )
        } catch (e: Exception) {
            null
        }
    }
}
