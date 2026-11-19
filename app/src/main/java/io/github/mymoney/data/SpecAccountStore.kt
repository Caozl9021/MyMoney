package io.github.mymoney.data

import android.content.Context
import android.util.Log
import com.example.billkit.BillDraft
import com.example.billkit.spec.SpecAccountMapper
import com.example.billkit.spec.SpecAsset
import com.example.billkit.spec.SpecAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把规则原文里的账户名换成账本里真实存在的账户。
 */
object SpecAccountStore {

    private const val TAG = "SpecAccountStore"

    suspend fun fill(context: Context, draft: BillDraft, preferred: String? = null): BillDraft {
        if (preferred != null) return if (draft.account == preferred) draft else draft.copy(account = preferred)
        val raw = draft.account?.takeIf(String::isNotBlank) ?: return draft
        val accounts = runCatching { LedgerConfig.accounts }.getOrNull().orEmpty()
        val assets = runCatching { runBlockingAccounts(context) }.getOrDefault(emptyList())
        val resolved = SpecAccountMapper.resolve(raw, accounts, assets)
        if (resolved == null && draft.account == null) return draft
        return draft.copy(account = resolved)
    }

    private suspend fun runBlockingAccounts(context: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.applicationContext.assets.open(SpecAssets.ASSETS)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            SpecAsset.parseAll(raw).map { it.name }
        }.onFailure { Log.w(TAG, "读取内置资产表失败：${it.message}") }.getOrDefault(emptyList())
    }
}
