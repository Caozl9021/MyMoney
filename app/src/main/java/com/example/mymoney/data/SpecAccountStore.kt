package com.example.mymoney.data

import android.content.Context
import android.util.Log
import com.example.billkit.BillDraft
import com.example.billkit.spec.SpecAccountMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC §8 阶段 6：把规则原文里的账户名换成账本里真实存在的账户。
 *
 * <h3>两个字段各存什么</h3>
 * - [BillDraft.method] 保留**规则原文**（`招商银行信用卡(1234)`、`支付宝余额`、`美团月付`）。
 *   这是 `raw_account_name_from` 的角色：排查"这条账是哪个通道报的"只能靠它，不能丢。
 * - [BillDraft.account] 写**映射后的账本账户**（`信用卡`、`支付宝`）。资产联动只认这个字段。
 *
 * 项目没有为 raw 单独加一列：`method` 本来就是「支付方式」的原文，语义完全对得上，
 * 再加一列会出现两个都说自己是原文的字段。
 *
 * <h3>映射不到为什么置空而不是保留原文</h3>
 * 账本里不存在 `美团月付` 这个账户。把原文写进 `account`，资产侧会去 `resolveAccount`
 * 找一个不存在的账户，结果既挂不上资产、又在界面上显示一个用户没建过的名字。
 * 置空让确认卡自己空着，用户点一下就选好了——比编造一个账户诚实。
 */
object SpecAccountStore {

    private const val TAG = "SpecAccountStore"

    /**
     * 把草稿的账户换成账本账户。
     *
     * @param preferred 其它通道（[CategorySuggester]）已经认出来的账户；非空就不覆盖它，
     *   因为它基于用户自己的学习规则和别名，比本映射表更贴近这台设备的实际配置。
     */
    suspend fun fill(context: Context, draft: BillDraft, preferred: String? = null): BillDraft {
        if (preferred != null) return if (draft.account == preferred) draft else draft.copy(account = preferred)
        val raw = draft.account?.takeIf(String::isNotBlank) ?: return draft
        val accounts = runCatching { LedgerConfig.accounts }.getOrNull().orEmpty()
        val assets = runCatching { runBlockingAccounts(context) }.getOrDefault(emptyList())
        val resolved = SpecAccountMapper.resolve(raw, accounts, assets)
        if (resolved == null && draft.account == null) return draft
        // 原文留在 method；account 只放映射结果，映射不到就是 null。
        return draft.copy(account = resolved)
    }

    /** 原创规则包的账户建议名称，作为用户没有同名账户时的兜底。 */
    private suspend fun runBlockingAccounts(context: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            SpecRuleStore.readAssets(context.applicationContext).map { it.name }
        }.onFailure { Log.w(TAG, "读取规格资产表失败：${it.message}") }.getOrDefault(emptyList())
    }
}
