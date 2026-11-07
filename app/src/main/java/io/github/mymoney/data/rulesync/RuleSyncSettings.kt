package io.github.mymoney.data.rulesync

import android.content.Context

data class RuleSyncConfig(
    val serverUrl: String = "",
    val spaceId: String = "",
    val epoch: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val initialMergePending: Boolean = false,
    /** 走内嵌 tsnet 节点而不是系统直连；旧配置没有这一项，默认 false = 直连。 */
    val useEmbedded: Boolean = false,
    /** 内嵌模式使用的控制面地址；留空用上游默认。 */
    val ctrlUrl: String = "",
) {
    val paired: Boolean get() = serverUrl.isNotBlank() && spaceId.isNotBlank() && epoch.isNotBlank() && deviceId.isNotBlank()
}

object RuleSyncSettings {
    private const val PREFS = "rule_sync"
    private const val KEY_SERVER = "server_url"
    private const val KEY_SPACE = "space_id"
    private const val KEY_EPOCH = "epoch"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_INITIAL_MERGE_PENDING = "initial_merge_pending"
    private const val KEY_USE_EMBEDDED = "use_embedded"
    private const val KEY_CTRL_URL = "ctrl_url"

    fun load(context: Context): RuleSyncConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RuleSyncConfig(
            serverUrl = p.getString(KEY_SERVER, "") ?: "",
            spaceId = p.getString(KEY_SPACE, "") ?: "",
            epoch = p.getString(KEY_EPOCH, "") ?: "",
            deviceId = p.getString(KEY_DEVICE, "") ?: "",
            deviceName = p.getString(KEY_DEVICE_NAME, "") ?: "",
            initialMergePending = p.getBoolean(KEY_INITIAL_MERGE_PENDING, false),
            useEmbedded = p.getBoolean(KEY_USE_EMBEDDED, false),
            ctrlUrl = p.getString(KEY_CTRL_URL, "") ?: "",
        )
    }

    fun save(context: Context, config: RuleSyncConfig, token: String? = null) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(p.edit()
            .putString(KEY_SERVER, config.serverUrl.trim().trimEnd('/'))
            .putString(KEY_SPACE, config.spaceId)
            .putString(KEY_EPOCH, config.epoch)
            .putString(KEY_DEVICE, config.deviceId)
            .putString(KEY_DEVICE_NAME, config.deviceName)
            .putBoolean(KEY_INITIAL_MERGE_PENDING, config.initialMergePending)
            .putBoolean(KEY_USE_EMBEDDED, config.useEmbedded)
            .putString(KEY_CTRL_URL, config.ctrlUrl.trim())
            .commit()) { "无法保存连接配置" }
        if (token != null) RuleSyncSecrets.saveToken(context, token)
    }

    fun setInitialMergePending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INITIAL_MERGE_PENDING, pending)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        RuleSyncSecrets.clearToken(context)
    }

    fun clearToken(context: Context) {
        RuleSyncSecrets.clearToken(context)
    }
}

object RuleSyncSecrets {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "mymoney_rule_sync_token"
    private const val PREFS = "rule_sync"
    private const val KEY_TOKEN = "token_encrypted"

    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val builder = android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            val generator = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE,
            )
            generator.init(builder)
            generator.generateKey()
        }
        return (store.getEntry(ALIAS, null) as java.security.KeyStore.SecretKeyEntry).secretKey
    }

    fun saveToken(context: Context, token: String) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val encrypted = android.util.Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, encrypted).commit()) { "无法保存连接凭证" }
    }

    fun loadToken(context: Context): String? = runCatching {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null) ?: return null
        val bytes = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply()
    }
}
