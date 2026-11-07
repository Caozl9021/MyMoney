package io.github.mymoney.data.rulesync

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CloudBackupAuthorizationRequiredException(message: String) : IllegalStateException(message)

/** Personal backup authorization only. No rule merge, ledger push or cross-device import. */
object CloudBackupCoordinator {
    private val mutex = Mutex()
    data class Enrollment(val sources: List<CloudBackupApi.Source>, val name: String,
        val completing: Boolean = false, val submittedSource: CloudBackupApi.Source? = null)
    private fun enrollment(pending: CloudBackupIdentity.Pending, sources: List<CloudBackupApi.Source>): Enrollment {
        val submitted = pending.sourceId?.let { id ->
            val generation = pending.generation ?: error("设备接替记录缺少版本，请重新授权")
            (sources.firstOrNull { it.deviceId == id } ?: CloudBackupApi.Source(id, "原设备", null, null,
                generation, false, false)).copy(credentialGeneration = generation)
        }
        return Enrollment(sources, pending.name, pending.completing, submitted)
    }
    fun deviceName(context: Context): String = RuleSyncSettings.load(context).deviceName.ifBlank { Build.MODEL.ifBlank { "我的手机" } }
    private fun transport(context: Context) = FamilyTransportProvider.select(context, RuleSyncSettings.load(context))

    suspend fun checkConnection(context: Context): CloudBackupApi.Identity =
        checkConnectionState(context).identity

    /**
     * Same verification as [checkConnection], but keeps the whole response so the home page can
     * show the newest backup from the single merged request instead of listing backups again.
     */
    suspend fun checkConnectionState(context: Context): CloudBackupApi.Context = withContext(Dispatchers.IO) { mutex.withLock {
        val pending = try { CloudBackupIdentity.pending(context) } catch (e: IllegalStateException) {
            if (RuleSyncSettings.load(context).paired) return@withLock requireCurrentIdentityState(context)
            throw e
        }
        if (pending?.completing == true) {
            try {
                val identity = CloudBackupApi.self(pending.server, pending.credential, transport(context))
                if (identity.spaceId != pending.spaceId || identity.epoch != pending.epoch ||
                    (pending.sourceId != null && identity.deviceId != pending.sourceId))
                    throw CloudBackupAuthorizationRequiredException("服务器空间已变化，请重新授权")
                saveIdentity(context, identity, pending.credential, pending.name)
                CloudBackupIdentity.clearPending(context)
                return@withLock CloudBackupApi.Context(identity, CloudBackupIdentity.installationId(context), true,
                    latestBackup = null, features = CloudBackupApi.Features())
            } catch (e: RuleSyncApiException) { if (e.code != 401 && e.code != 403) throw e }
        }
        requireCurrentIdentityState(context)
    } }

    /** Also used before backup/list/delete/download; one cached verification serves the whole page. */
    internal fun requireCurrentIdentity(context: Context): CloudBackupApi.Identity =
        requireCurrentIdentityState(context).identity

    internal fun requireCurrentIdentityState(context: Context): CloudBackupApi.Context =
        CloudBackupSession.verify(context, transport(context))

    suspend fun connect(context: Context, text: String): Enrollment? = withContext(Dispatchers.IO) { mutex.withLock {
        CloudBackupSession.reset()
        val invite = FamilyInvite.parse(text)
        require(FamilyInvite.isConnectCode(invite)) { "请粘贴服务器生成的完整连接码" }
        val previous = RuleSyncSettings.load(context)
        if (previous.paired && previous.serverUrl.trimEnd('/') != invite.serverUrl.trimEnd('/')) {
            error("更换服务器前请先在连接管理退出当前连接，原备份会保留")
        }
        PendingInvite.put(invite)
        val config = previous.copy(serverUrl = invite.serverUrl, ctrlUrl = invite.ctrlUrl, useEmbedded = !invite.direct)
        RuleSyncSettings.save(context, config)
        val wire = transport(context)
        CloudBackupApi.capabilities(config.serverUrl, wire)
        if (previous.paired) {
            try { requireCurrentIdentity(context); return@withLock null }
            catch (e: RuleSyncApiException) { if (e.code != 401 && e.code != 403) throw e }
            catch (_: CloudBackupAuthorizationRequiredException) { /* Explicit user authorization can repair stale identity. */ }
        }
        val installation = CloudBackupIdentity.installationId(context)
        var pending = CloudBackupIdentity.pendingForAuthorization(context)
        if (pending == null || pending.server != config.serverUrl) {
            pending = CloudBackupIdentity.newPending(config.serverUrl, Build.MODEL.ifBlank { "我的手机" })
            CloudBackupIdentity.save(context, pending)
        }
        val grant = CloudBackupApi.authorize(config.serverUrl, invite.pairCode, installation, pending.operationId, wire)
        pending = pending.copy(grant = grant.grant, spaceId = grant.spaceId, epoch = grant.epoch)
        CloudBackupIdentity.save(context, pending)
        enrollment(pending, CloudBackupApi.sources(config.serverUrl, grant.grant, wire))
    } }

    suspend fun resumeEnrollment(context: Context): Enrollment? = withContext(Dispatchers.IO) { mutex.withLock {
        val pending = CloudBackupIdentity.pending(context) ?: return@withLock null
        if (pending.completing) {
            try {
                val identity = CloudBackupApi.self(pending.server, pending.credential, transport(context))
                if (identity.spaceId != pending.spaceId || identity.epoch != pending.epoch ||
                    (pending.sourceId != null && identity.deviceId != pending.sourceId))
                    throw CloudBackupAuthorizationRequiredException("服务器空间已变化，请重新授权")
                saveIdentity(context, identity, pending.credential, pending.name)
                CloudBackupIdentity.clearPending(context)
                return@withLock null
            } catch (e: RuleSyncApiException) { if (e.code != 401 && e.code != 403) throw e }
        }
        if (pending.grant.isBlank()) return@withLock null
        enrollment(pending, CloudBackupApi.sources(pending.server, pending.grant, transport(context)))
    } }

    suspend fun complete(context: Context, source: CloudBackupApi.Source?, name: String): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        var pending = CloudBackupIdentity.pending(context) ?: error("请先粘贴连接码授权")
        require(pending.grant.isNotBlank()) { "请重新获取连接码授权" }
        val selectedName = name.trim().ifBlank { Build.MODEL }.take(80)
        val wire = transport(context)
        if (pending.completing) {
            // A lost receipt does not mean registration failed. Recover the saved request first,
            // without using newly edited labels or another source to change an in-flight operation.
            try {
                val previous = CloudBackupApi.self(pending.server, pending.credential, wire)
                if (previous.spaceId != pending.spaceId || previous.epoch != pending.epoch ||
                    (pending.sourceId != null && previous.deviceId != pending.sourceId)) {
                    throw CloudBackupAuthorizationRequiredException("登记身份已变化，请重新授权")
                }
                saveIdentity(context, previous, pending.credential, pending.name)
                CloudBackupIdentity.clearPending(context)
                return@withLock
            } catch (e: RuleSyncApiException) { if (e.code != 401 && e.code != 403) throw e }
            require(CloudBackupRetryPolicy.sameRequest(pending.name, pending.sourceId, pending.generation,
                selectedName, source?.deviceId, source?.credentialGeneration)) {
                "此连接已提交，正在确认原结果。请重试原请求，不能修改名称或接替目标。"
            }
        } else {
            pending = pending.copy(name = selectedName, completing = true, sourceId = source?.deviceId, generation = source?.credentialGeneration)
            CloudBackupIdentity.save(context, pending)
        }
        val identity = CloudBackupApi.complete(pending.server, pending.grant, CloudBackupIdentity.installationId(context),
            pending.operationId, pending.credential, pending.name, source, wire)
        check(identity.spaceId == pending.spaceId && identity.epoch == pending.epoch) { "授权空间已变化，请重新连接" }
        check(pending.sourceId == null || identity.deviceId == pending.sourceId) { "接替设备响应不一致" }
        saveIdentity(context, identity, pending.credential, pending.name)
        CloudBackupIdentity.clearPending(context)
    } }

    suspend fun cancelEnrollment(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        require(CloudBackupIdentity.pending(context)?.completing != true) { "连接请求已提交，请先重试确认结果" }
        CloudBackupIdentity.clearPending(context)
    } }

    suspend fun rename(context: Context, name: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val config = RuleSyncSettings.load(context)
        val clean = name.trim(); require(clean.isNotBlank() && clean.length <= 80) { "设备名称请填写1至80个字符" }
        val token = RuleSyncSecrets.loadToken(context) ?: error("请重新授权连接")
        CloudBackupApi.rename(config.serverUrl, token, clean, transport(context))
        RuleSyncSettings.save(context, config.copy(deviceName = clean))
    } }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val config = RuleSyncSettings.load(context)
        require(CloudBackupIdentity.pending(context)?.completing != true) { "请先确认正在处理的连接请求，再退出连接" }
        val token = RuleSyncSecrets.loadToken(context) ?: error("设备凭证已丢失，请重新授权接替原设备")
        // Only forget local identity after the server confirms revocation. Offline is not sign-out.
        try { RuleSyncApi.revoke(config.serverUrl, token, transport(context)) }
        catch (e: RuleSyncApiException) {
            // Authentication already revoked (including a deliberate server reset) is a confirmed sign-out.
            if (e.code != 401) throw e
        }
        CloudBackupIdentity.clearPending(context)
        RuleSyncSettings.save(context, config.copy(spaceId = "", epoch = "", deviceId = "", initialMergePending = false))
        RuleSyncSecrets.clearToken(context)
        CloudBackupSession.reset()
        TsnetSession.shutdown()
    } }

    private fun saveIdentity(context: Context, identity: CloudBackupApi.Identity, token: String, name: String) {
        CloudBackupSession.reset()
        val previous = RuleSyncSettings.load(context)
        // Save secret first; if config write fails, persisted pending credentials recover via /self.
        RuleSyncSecrets.saveToken(context, token)
        RuleSyncSettings.save(context, previous.copy(spaceId = identity.spaceId, epoch = identity.epoch,
            deviceId = identity.deviceId, deviceName = identity.name.ifBlank { name }, initialMergePending = false))
    }
}

/** A pending enrollment operation has one immutable name/target/version even after a lost receipt. */
internal object CloudBackupRetryPolicy {
    fun sameRequest(savedName: String, savedSource: String?, savedGeneration: Long?,
        requestedName: String, requestedSource: String?, requestedGeneration: Long?): Boolean =
        savedName == requestedName && savedSource == requestedSource && savedGeneration == requestedGeneration
}
