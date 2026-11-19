package io.github.mymoney.data.ledgersync

import android.content.Context
import java.util.UUID

/**
 * W2 账本同步的本地配置：ledgerId 是设备级稳定 UUID（终身不变，随备份/恢复保留），
 * spaceId/epoch 复用规则同步的配对结果。ledgerId 一旦生成就不应改变，否则同一台设备
 * 会被当成两个账本。
 */
object LedgerSyncSettings {
    private const val PREFS = "ledger_sync"
    private const val KEY_LEDGER_ID = "ledger_id"

    /** 取得本机账本稳定 id；不存在则生成并持久化。 */
    fun ledgerId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = p.getString(KEY_LEDGER_ID, "") ?: ""
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        p.edit().putString(KEY_LEDGER_ID, generated).apply()
        return generated
    }

    fun setLedgerId(context: Context, id: String) {
        if (id.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LEDGER_ID, id)
            .apply()
    }

    /**
     * 实时同步使用的账本 id：云备份配对后用 `config.deviceId`，与服务端
     * `ledger_backups.device_id` 天然对齐（Overlay 无需额外映射表，换机接替也连续）。
     * 未配对或旧配置下回退到本机随机 ledgerId。
     */
    fun effectiveLedgerId(context: Context): String {
        val deviceId = io.github.mymoney.data.rulesync.RuleSyncSettings.load(context).deviceId
        return deviceId.takeIf { it.isNotBlank() } ?: ledgerId(context)
    }
}
