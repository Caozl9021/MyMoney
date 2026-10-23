package com.example.autobook.data

import android.content.Context
import com.example.billkit.BillType
import org.json.JSONArray
import org.json.JSONObject

/** 一级分类 + 它下面的二级分类（二级可为空） */
data class CategoryGroup(
    val name: String,
    val subs: List<String> = emptyList(),
)

/**
 * 用户自己的「分类 / 账户」清单 —— 也是导出给 iCost 时使用的名字。
 *
 * 设计是**清单优先**：先把要用的分类（支持二级）、账户列好（抄 iCost 里已有的名字），
 * 识别时拿结果去匹配这份清单，命中就自动选中，没命中让用户在确认页点一下。
 */
object LedgerConfig {

    private const val PREFS = "ledger_config"
    private const val KEY_EXPENSE = "expense_groups"
    private const val KEY_INCOME = "income_groups"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_OLD_EXPENSE = "expense_categories"
    private const val KEY_OLD_INCOME = "income_categories"
    private const val KEY_NOTE_TEMPLATE = "note_template"

    val DEFAULT_EXPENSE = listOf(
        CategoryGroup("餐饮", listOf("三餐", "早餐", "午餐", "晚餐", "夜宵", "外卖", "咖啡茶饮", "零食")),
        CategoryGroup("交通", listOf("公交地铁", "打车", "加油停车")),
        CategoryGroup("购物", listOf("日用品", "服饰", "数码")),
        CategoryGroup("居住", listOf("房租", "水电燃气", "物业")),
        CategoryGroup("娱乐", listOf("电影", "游戏", "会员")),
        CategoryGroup("医疗", listOf("门诊", "药品")),
        CategoryGroup("人情", listOf("红包", "礼物")),
        CategoryGroup("学习", listOf("课程", "书籍")),
        CategoryGroup("通讯", listOf("话费", "宽带")),
        CategoryGroup("其他"),
    )

    val DEFAULT_INCOME = listOf(
        CategoryGroup("工资"),
        CategoryGroup("奖金"),
        CategoryGroup("红包"),
        CategoryGroup("理财收益"),
        CategoryGroup("退款"),
        CategoryGroup("其他"),
    )

    val DEFAULT_ACCOUNTS = listOf(
        "微信钱包", "微信零钱通", "支付宝", "余额宝", "花呗", "白条",
        "储蓄卡", "信用卡", "现金", "数字人民币",
    )

    /** 备注模板可用的占位符 */
    val NOTE_TOKENS = listOf("{商户}", "{支付方式}", "{分类}", "{账户}", "{时间}")

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            migrateOldFlatLists()
        }
    }

    private fun p(): android.content.SharedPreferences = prefs ?: error("LedgerConfig.init 未调用")

    // ---------- 分类 ----------

    val expenseGroups: List<CategoryGroup>
        get() = readGroups(KEY_EXPENSE, DEFAULT_EXPENSE)

    val incomeGroups: List<CategoryGroup>
        get() = readGroups(KEY_INCOME, DEFAULT_INCOME)

    fun groupsFor(type: BillType): List<CategoryGroup> = when (type) {
        BillType.INCOME -> incomeGroups
        else -> expenseGroups
    }

    fun addGroup(type: BillType, name: String) = writeGroups(type, groupsFor(type) + CategoryGroup(name.trim()))

    fun removeGroup(type: BillType, name: String) =
        writeGroups(type, groupsFor(type).filterNot { it.name == name })

    fun addSubCategory(type: BillType, group: String, sub: String) {
        val trimmed = sub.trim()
        if (trimmed.isEmpty()) return
        writeGroups(type, groupsFor(type).map {
            if (it.name == group) it.copy(subs = (it.subs + trimmed).distinct()) else it
        })
    }

    /** Explicit user action; reading old custom lists never appends categories. */
    fun addMealSubCategories() {
        val food = expenseGroups.firstOrNull { it.name == "餐饮" }
        if (food == null) addGroup(BillType.EXPENSE, "餐饮")
        MealRules.names.forEach { addSubCategory(BillType.EXPENSE, "餐饮", it) }
    }

    fun removeSubCategory(type: BillType, group: String, sub: String) {
        writeGroups(type, groupsFor(type).map {
            if (it.name == group) it.copy(subs = it.subs.filterNot { s -> s == sub }) else it
        })
    }

    fun subsOf(type: BillType, group: String?): List<String> =
        groupsFor(type).firstOrNull { it.name == group }?.subs.orEmpty()

    // ---------- 账户 ----------

    val accounts: List<String>
        get() = readStrings(KEY_ACCOUNTS, DEFAULT_ACCOUNTS)

    fun addAccount(name: String) = writeStrings(KEY_ACCOUNTS, DEFAULT_ACCOUNTS, accounts + name.trim())

    fun removeAccount(name: String) =
        writeStrings(KEY_ACCOUNTS, DEFAULT_ACCOUNTS, accounts.filterNot { it == name })

    // ---------- 备注模板 ----------

    /** 导出/记账时"备注"列写什么，例如 `{商户}` 或 `{商户}·{支付方式}` */
    var noteTemplate: String
        get() = p().getString(KEY_NOTE_TEMPLATE, "{商户}") ?: "{商户}"
        set(value) = p().edit().putString(KEY_NOTE_TEMPLATE, value.trim()).apply()

    // ---------- 备份恢复 ----------

    /** 用备份文件里的清单整体覆盖（空列表视为"备份里没有"，保持现状） */
    fun restore(
        expense: List<CategoryGroup>,
        income: List<CategoryGroup>,
        accounts: List<String>,
        noteTemplate: String,
    ) {
        if (expense.isNotEmpty()) writeGroups(BillType.EXPENSE, expense)
        if (income.isNotEmpty()) writeGroups(BillType.INCOME, income)
        if (accounts.isNotEmpty()) writeStrings(KEY_ACCOUNTS, DEFAULT_ACCOUNTS, accounts)
        if (noteTemplate.isNotBlank()) this.noteTemplate = noteTemplate
    }

    // ---------- 重置 ----------

    fun resetToDefault() {
        p().edit()
            .remove(KEY_EXPENSE).remove(KEY_INCOME).remove(KEY_ACCOUNTS)
            .remove(KEY_OLD_EXPENSE).remove(KEY_OLD_INCOME)
            .apply()
    }

    // ---------- 内部 ----------

    private fun readGroups(key: String, fallback: List<CategoryGroup>): List<CategoryGroup> {
        val raw = p().getString(key, null) ?: return fallback
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val name = obj.optString("n").trim()
                if (name.isEmpty()) return@mapNotNull null
                val subs = obj.optJSONArray("s")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty()
                CategoryGroup(name, subs)
            }.ifEmpty { fallback }
        }.getOrDefault(fallback)
    }

    private fun writeGroups(type: BillType, groups: List<CategoryGroup>) {
        val key = if (type == BillType.INCOME) KEY_INCOME else KEY_EXPENSE
        val fallback = if (type == BillType.INCOME) DEFAULT_INCOME else DEFAULT_EXPENSE
        val cleaned = groups.filter { it.name.isNotBlank() }.distinctBy { it.name }
            .ifEmpty { fallback }
        val array = JSONArray()
        cleaned.forEach { group ->
            array.put(
                JSONObject().apply {
                    put("n", group.name)
                    if (group.subs.isNotEmpty()) put("s", JSONArray(group.subs))
                }
            )
        }
        p().edit().putString(key, array.toString()).apply()
    }

    private fun readStrings(key: String, fallback: List<String>): List<String> {
        val raw = p().getString(key, null) ?: return fallback
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(fallback)
    }

    private fun writeStrings(key: String, fallback: List<String>, list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { fallback }
        p().edit().putString(key, JSONArray().apply { cleaned.forEach { put(it) } }.toString()).apply()
    }

    /** 兼容上一版的扁平分类存储（v0.2 的 expense_categories） */
    private fun migrateOldFlatLists() {
        val prefs = p()
        if (prefs.contains(KEY_OLD_EXPENSE)) {
            val old = readStrings(KEY_OLD_EXPENSE, DEFAULT_EXPENSE.map { it.name })
            if (!prefs.contains(KEY_EXPENSE)) {
                writeGroups(BillType.EXPENSE, old.map { CategoryGroup(it) })
            }
            prefs.edit().remove(KEY_OLD_EXPENSE).apply()
        }
        if (prefs.contains(KEY_OLD_INCOME)) {
            val old = readStrings(KEY_OLD_INCOME, DEFAULT_INCOME.map { it.name })
            if (!prefs.contains(KEY_INCOME)) {
                writeGroups(BillType.INCOME, old.map { CategoryGroup(it) })
            }
            prefs.edit().remove(KEY_OLD_INCOME).apply()
        }
    }
}
