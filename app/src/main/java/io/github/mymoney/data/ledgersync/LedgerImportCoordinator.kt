package io.github.mymoney.data.ledgersync

import android.content.Context
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.rulesync.FamilyTransport
import io.github.mymoney.data.rulesync.FamilyTransportProvider
import io.github.mymoney.data.rulesync.RuleSyncConfig
import io.github.mymoney.data.rulesync.RuleSyncSecrets
import io.github.mymoney.data.rulesync.RuleSyncSettings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * W3 按需导入协调器：拉取同空间来源列表 / 来源冻结快照，生成预览并在用户确认后原子导入。
 * 只读来源、不改来源账本；导入只在本机新增，重复导入幂等、双边变更保留冲突。
 */
object LedgerImportCoordinator {

    private fun transport(context: Context, config: RuleSyncConfig): FamilyTransport =
        FamilyTransportProvider.select(context, config)

    private fun requirePairing(context: Context): Pair<RuleSyncConfig, String> {
        val config = RuleSyncSettings.load(context)
        if (!config.paired) throw IOException("请先连接家庭服务")
        val token = RuleSyncSecrets.loadToken(context) ?: throw IOException("设备令牌不存在，请重新配对")
        return config to token
    }

    /** 列出可导入的来源设备（同空间、非本机、未撤销且有数据）。 */
    suspend fun sources(context: Context): List<LedgerImportSource> = withContext(Dispatchers.IO) {
        val (config, token) = requirePairing(context)
        LedgerSyncApi.importSources(config.serverUrl, token, transport(context, config))
    }

    /** 拉取某来源的冻结快照并生成本机导入预览。 */
    suspend fun preview(
        context: Context, source: LedgerImportSource,
    ): LedgerImport.Preview = withContext(Dispatchers.IO) {
        val (config, token) = requirePairing(context)
        val snap = LedgerSyncApi.importSource(
            config.serverUrl, token, source.deviceId, source.ledgerId, transport(context, config),
        )
        LedgerImport.previewDatabase(
            AppDb.get(context), source.deviceName, snap.sourceLedgerId, snap.sourceRevision, snap.items,
        )
    }

    /** 用户确认后提交导入（原子）。返回提交后的预览，用于展示实际写入了多少。 */
    suspend fun commit(
        context: Context, source: LedgerImportSource,
    ): LedgerImport.Preview = withContext(Dispatchers.IO) {
        val (config, token) = requirePairing(context)
        val snap = LedgerSyncApi.importSource(
            config.serverUrl, token, source.deviceId, source.ledgerId, transport(context, config),
        )
        val report = LedgerImport.commit(
            AppDb.get(context), source.deviceName, snap.sourceLedgerId, snap.sourceRevision, snap.items,
        )
        // 导入后把新账单推到本机副本（本机账本与服务端本机副本保持一致）
        runCatching { LedgerSyncRepository.syncAllLocal(context) }
        runCatching { LedgerSyncCoordinator.refreshStatus(context) }
        report
    }
}
