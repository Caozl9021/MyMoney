package io.github.mymoney.data.rulesync

import android.content.Context

/**
 * Device-identity verification for the personal cloud backup page.
 *
 * Before Y every backup call re-ran capabilities + self + bind (three serial
 * requests) and the home page did that twice, so opening it meant seven round
 * trips. One short-lived verification result is now shared: a successful check
 * is reused for [TTL_MS] and reused across list/preview/restore, and a server
 * that supports /v1/backup-context answers identity + capabilities + newest
 * backup in a single request.
 */
internal object CloudBackupSession {
    private const val TTL_MS = 30_000L

    private data class Cached(val key: String, val atMs: Long, val context: CloudBackupApi.Context)
    private var cached: Cached? = null

    fun reset() {
        synchronized(this) { cached = null }
    }

    fun verify(context: Context, transport: FamilyTransport, force: Boolean = false): CloudBackupApi.Context {
        val config = RuleSyncSettings.load(context)
        if (!config.paired) throw CloudBackupAuthorizationRequiredException("请先连接云备份服务器")
        val token = RuleSyncSecrets.loadToken(context)
            ?: throw CloudBackupAuthorizationRequiredException("设备凭证不可用，请重新授权连接")
        val installation = CloudBackupIdentity.installationId(context)
        val key = listOf(config.serverUrl.trimEnd('/'), token.hashCode(), installation,
            config.deviceId, config.spaceId, config.epoch).joinToString("|")
        synchronized(this) {
            val hit = cached
            if (!force && hit != null && hit.key == key && System.currentTimeMillis() - hit.atMs < TTL_MS) return hit.context
        }
        val result = try {
            // 先打合并的 context：支持时一次请求拿到身份、能力与最新备份；404 才退回旧三连。
            val checked = try {
                reconcile(CloudBackupApi.context(config.serverUrl, token, installation, transport),
                    config, token, installation, transport)
            } catch (missing: RuleSyncApiException) {
                if (missing.code != 404) throw missing
                legacy(config, token, installation, transport,
                    CloudBackupApi.capabilities(config.serverUrl, transport))
            }
            requireMatch(checked.identity, config)
            checked
        } catch (e: RuleSyncApiException) {
            if (e.code == 401 || e.code == 403) reset()
            throw e
        }
        synchronized(this) { cached = Cached(key, System.currentTimeMillis(), result) }
        return result
    }

    /** A never-bound installation is bound once; a different binding still needs a new code. */
    private fun reconcile(checked: CloudBackupApi.Context, config: RuleSyncConfig, token: String,
                          installation: String, transport: FamilyTransport): CloudBackupApi.Context {
        if (checked.installationId == null) {
            val bound = CloudBackupApi.bind(config.serverUrl, token, installation, transport)
            return checked.copy(identity = bound, installationId = installation, installationMatches = true)
        }
        if (checked.installationMatches == false) {
            throw CloudBackupAuthorizationRequiredException("设备安装身份已变化，请通过连接码明确接替原设备")
        }
        return checked
    }

    /** Servers without /v1/backup-context keep the old three-request verification. */
    private fun legacy(config: RuleSyncConfig, token: String, installation: String,
                       transport: FamilyTransport, features: CloudBackupApi.Features): CloudBackupApi.Context {
        val self = CloudBackupApi.self(config.serverUrl, token, transport)
        val bound = CloudBackupApi.bind(config.serverUrl, token, installation, transport)
        return CloudBackupApi.Context(bound, installation, true,
            latestBackup = null, features = features).also {
            if (bound.deviceId != self.deviceId || bound.spaceId != self.spaceId || bound.epoch != self.epoch) {
                throw CloudBackupAuthorizationRequiredException("设备身份响应不一致，请重新授权")
            }
        }
    }

    private fun requireMatch(identity: CloudBackupApi.Identity, config: RuleSyncConfig) {
        if (identity.deviceId != config.deviceId || identity.spaceId != config.spaceId || identity.epoch != config.epoch) {
            throw CloudBackupAuthorizationRequiredException("服务器身份已变化，请重新授权")
        }
    }
}
