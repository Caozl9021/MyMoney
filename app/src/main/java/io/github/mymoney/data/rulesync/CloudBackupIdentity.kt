package io.github.mymoney.data.rulesync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.mymoney.data.MiniJson
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, non-exported identity. Never part of Backup.Snapshot or any ledger JSON. */
internal object CloudBackupIdentity {
    private const val PREFS = "cloud_backup_identity"
    private const val ALIAS = "mymoney_cloud_backup_identity"
    data class Pending(val operationId: String, val credential: String, val server: String, val name: String,
        val grant: String = "", val spaceId: String = "", val epoch: String = "", val completing: Boolean = false,
        val sourceId: String? = null, val generation: Long? = null)
    fun installationId(context: Context): String = synchronized(this) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("installation_id", it).commit()) { "无法保存设备身份" }
        }
    }
    fun pending(context: Context): Pending? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending", null) ?: return null
        val row = try { MiniJson.decode(decrypt(raw)) as? Map<*, *> ?: error("invalid pending") }
        catch (e: Exception) { throw IllegalStateException("设备登记凭证无法解封，请粘贴新的连接码重新授权", e) }
        fun text(key: String) = row[key] as? String ?: ""
        return Pending(text("operationId"), text("credential"), text("server"), text("name"), text("grant"),
            text("spaceId"), text("epoch"), row["completing"] == true, row["sourceId"] as? String,
            (row["generation"] as? Number)?.toLong())
    }
    fun save(context: Context, pending: Pending) {
        val json = MiniJson.encode(mapOf("operationId" to pending.operationId, "credential" to pending.credential,
            "server" to pending.server, "name" to pending.name, "grant" to pending.grant, "spaceId" to pending.spaceId,
            "epoch" to pending.epoch, "completing" to pending.completing, "sourceId" to pending.sourceId,
            "generation" to pending.generation))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pending", encrypt(json)).commit()) { "无法保存设备登记记录" }
    }
    /** Only called after the user explicitly starts a new authorization. Keep damaged ciphertext for recovery. */
    fun pendingForAuthorization(context: Context): Pending? = try { pending(context) } catch (e: IllegalStateException) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("pending", null)
        check(prefs.edit().putString("damaged_pending_${System.currentTimeMillis()}", raw).remove("pending").commit()) { "无法保留原设备登记记录" }
        null
    }
    fun clearPending(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pending").commit()) { "无法更新登记状态" }
    }
    fun newPending(server: String, name: String) = Pending(UUID.randomUUID().toString(), randomCredential(), server, name)
    internal fun randomCredential(): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        return (store.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private fun decrypt(raw: String): String {
        val bytes = Base64.decode(raw, Base64.NO_WRAP); require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
