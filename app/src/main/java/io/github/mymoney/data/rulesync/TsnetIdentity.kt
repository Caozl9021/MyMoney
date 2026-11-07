package io.github.mymoney.data.rulesync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * U3.4 — persistent, encrypted tsnet node identity.
 *
 * The tsnet node's private key and registration state are what make this install a
 * stable tailnet member. The plan requires them to live in an app-private noBackup
 * directory and be protected at rest by an "atomic StateStore" keyed by the Android
 * Keystore.
 *
 *  - Live state lives in [workDir] (plaintext, only while the node runs).
 *  - At rest it is sealed into [sealedFile] as one AES/GCM blob ([TsnetSeal]) with a
 *    Keystore-backed key; the plaintext work dir is then erased.
 *  - On restart [unseal] restores the work dir. A corrupt / Keystore-invalidated seal
 *    is detected and discarded rather than presented as a (wrong) identity.
 *
 * All paths are under `context.noBackupFilesDir` (excluded from Android cloud backup),
 * so the node key is never synced off-device.
 */
object TsnetIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "mymoney_tsnet_state_key"
    private const val STORE_PREFS = "rule_sync"
    private const val KEY_HAS_IDENTITY = "tsnet_has_identity"

    fun workDir(context: Context): File =
        File(context.noBackupFilesDir, "tsnet-work")

    fun sealedFile(context: Context): File =
        File(context.noBackupFilesDir, "tsnet.sealed")

    /**
     * The flag is only written when the state is sealed. A node that is still running
     * (or was killed before the first seal) keeps its identity in the plaintext work
     * dir, so checking the flag alone reported "需要重新授权" on a healthy node.
     */
    fun hasIdentity(context: Context): Boolean =
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HAS_IDENTITY, false) ||
            workDirPopulated(context) ||
            sealedFile(context).isFile

    private fun markIdentity(context: Context, present: Boolean) {
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_IDENTITY, present).apply()
    }

    fun workDirPopulated(context: Context): Boolean {
        val d = workDir(context)
        return d.isDirectory && File(d, "tsnet").isDirectory
    }

    /** Seal the work dir at rest and erase plaintext. No-op-safe; false only on I/O error. */
    @Synchronized
    fun seal(context: Context): Boolean {
        val work = workDir(context)
        if (!work.isDirectory) return true
        val ok = TsnetSeal.seal(key(), work, sealedFile(context))
        if (ok) {
            runCatching { work.deleteRecursively() }
            markIdentity(context, true)
        }
        return ok
    }

    /** Ensure a usable plaintext work dir exists (unsealing if needed). */
    @Synchronized
    fun unseal(context: Context): Boolean {
        val work = workDir(context)
        if (workDirPopulated(context)) return true
        val seal = sealedFile(context)
        if (!seal.isFile) return false
        return try {
            TsnetSeal.restorePreserving(key(), seal, work)
        } catch (e: Exception) {
            // This may be transient I/O or a locked/unavailable Keystore. Preserve
            // the only encrypted identity; do not silently register another node.
            throw java.io.IOException("节点身份暂时无法解封，请重试；原身份已保留", e)
        }
    }

    @Synchronized
    fun wipe(context: Context) {
        runCatching { sealedFile(context).delete() }
        runCatching { workDir(context).deleteRecursively() }
        markIdentity(context, false)
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val builder = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(builder); generateKey()
            }
        }
        return (store.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}
