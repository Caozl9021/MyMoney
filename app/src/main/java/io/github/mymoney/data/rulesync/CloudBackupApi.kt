package io.github.mymoney.data.rulesync

/** The cloud-backup identity protocol deliberately never calls legacy /pair or rule/ledger sync. */
object CloudBackupApi {
    data class Identity(val deviceId: String, val spaceId: String, val epoch: String,
        val credentialGeneration: Long, val name: String = "")
    data class Grant(val grant: String, val spaceId: String, val epoch: String)
    data class Source(val deviceId: String, val name: String, val lastSeenAt: String?,
        val lastBackupAt: String?, val credentialGeneration: Long, val archived: Boolean, val revoked: Boolean)

    /** Advertised server features; every flag defaults off so an old server stays usable. */
    data class Features(val deleteBackups: Boolean = false, val backupContext: Boolean = false)

    data class Context(
        val identity: Identity,
        val installationId: String?,
        val installationMatches: Boolean?,
        val latestBackup: LedgerBackupApi.Entry?,
        val features: Features,
    )

    fun capabilities(server: String, transport: FamilyTransport): Features {
        val result = try { call(server, "GET", "/v1/backup-capabilities", null, null, transport) }
        catch (e: RuleSyncApiException) {
            if (e.code == 404) throw RuleSyncApiException("请先升级服务器以支持云备份和设备身份", e.code)
            throw e
        }
        require((result["deviceIdentityVersion"] as? Number)?.toInt() == 1 && result["ownBackupsOnly"] == true) {
            "请先升级服务器以支持本设备备份隔离"
        }
        return Features(result["deleteBackups"] == true, result["backupContext"] == true)
    }

    /** One request replacing capabilities + self (+ bind) + latest-backup metadata. */
    fun context(server: String, token: String, installationId: String, transport: FamilyTransport): Context {
        val row = try {
            call(server, "GET", "/v1/backup-context?installationId=$installationId", null, token, transport)
        } catch (e: RuleSyncApiException) {
            if (e.code == 404) throw RuleSyncApiException("请先升级服务器以使用新版云备份页", e.code)
            throw e
        }
        val capabilities = row["capabilities"] as? Map<*, *> ?: error("云备份上下文缺少能力信息")
        require((capabilities["deviceIdentityVersion"] as? Number)?.toInt() == 1 && capabilities["ownBackupsOnly"] == true) {
            "请先升级服务器以支持本设备备份隔离"
        }
        return Context(
            identity = identity(row["identity"] as? Map<*, *> ?: error("云备份上下文缺少设备身份")),
            installationId = row["installationId"] as? String,
            installationMatches = row["installationMatches"] as? Boolean,
            latestBackup = (row["latestBackup"] as? Map<*, *>)?.let(LedgerBackupApi::entry),
            features = Features(capabilities["deleteBackups"] == true, capabilities["backupContext"] == true),
        )
    }
    fun self(server: String, token: String, transport: FamilyTransport): Identity =
        identity(call(server, "GET", "/v1/devices/self", null, token, transport))
    fun bind(server: String, token: String, installationId: String, transport: FamilyTransport): Identity = try {
        identity(call(server, "POST", "/v1/devices/self/bind-installation", mapOf("installationId" to installationId), token, transport))
    } catch (e: RuleSyncApiException) {
        if (e.code == 409) throw CloudBackupAuthorizationRequiredException("设备安装身份已变化，请通过连接码明确接替原设备")
        throw e
    }
    fun authorize(server: String, code: String, installationId: String, operationId: String, transport: FamilyTransport): Grant {
        val row = call(server, "POST", "/v1/device-enrollment/authorize", mapOf("code" to code,
            "installationId" to installationId, "operationId" to operationId), null, transport)
        return Grant(text(row, "grant"), text(row, "spaceId"), text(row, "epoch"))
    }
    fun sources(server: String, grant: String, transport: FamilyTransport): List<Source> {
        val row = call(server, "GET", "/v1/device-enrollment/sources", null, grant, transport)
        return (row["items"] as? List<*> ?: error("设备列表无效")).map {
            val source = it as? Map<*, *> ?: error("设备列表无效")
            Source(text(source, "deviceId"), text(source, "name"), source["lastSeenAt"] as? String,
                source["lastBackupAt"] as? String, number(source, "credentialGeneration"),
                source["archived"] == true, source["revoked"] == true)
        }
    }
    fun complete(server: String, grant: String, installationId: String, operationId: String, credential: String,
                 name: String, source: Source?, transport: FamilyTransport): Identity = identity(call(server, "POST",
        "/v1/device-enrollment/complete", mapOf("installationId" to installationId, "operationId" to operationId,
            "credential" to credential, "deviceName" to name, "sourceId" to source?.deviceId,
            "expectedGeneration" to source?.credentialGeneration), grant, transport))
    fun rename(server: String, token: String, name: String, transport: FamilyTransport) {
        call(server, "POST", "/v1/devices/self/rename", mapOf("name" to name), token, transport)
    }
    private fun call(server: String, method: String, path: String, body: Any?, token: String?, transport: FamilyTransport): Map<*, *> =
        RuleSyncApi.request(server, method, path, body, token, transport) as? Map<*, *> ?: error("云备份响应无效")
    internal fun identity(row: Map<*, *>): Identity = Identity(text(row, "deviceId"), text(row, "spaceId"),
        text(row, "epoch"), number(row, "credentialGeneration"), row["name"] as? String ?: "")
    private fun text(row: Map<*, *>, key: String) = (row[key] as? String)?.takeIf { it.isNotBlank() } ?: error("响应缺少$key")
    private fun number(row: Map<*, *>, key: String): Long = (row[key] as? Number)?.toLong() ?: error("响应缺少$key")
}
