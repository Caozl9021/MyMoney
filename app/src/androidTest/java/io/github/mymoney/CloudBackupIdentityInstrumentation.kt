package io.github.mymoney

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import io.github.mymoney.data.rulesync.CloudBackupIdentity
import java.util.UUID

/** Isolated preferences only. Never writes real identity, tokens, ledger, or deletes Keystore keys. */
class CloudBackupIdentityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val prefix = "cloud_identity_test_${UUID.randomUUID()}_"
        fun isolated(suffix: String = "") = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                targetContext.getSharedPreferences(prefix + suffix + name, mode)
        }
        val context = isolated()
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            val installation = CloudBackupIdentity.installationId(context)
            check(installation == CloudBackupIdentity.installationId(isolated()))
            check(installation != CloudBackupIdentity.installationId(isolated("other_")))
            checks += "installation-persists-across-context-reload-and-isolated-installations-differ"

            val pending = CloudBackupIdentity.newPending("http://127.0.0.1:18080", "测试设备").copy(
                grant = "test-grant-not-a-real-credential", spaceId = "space", epoch = "epoch")
            CloudBackupIdentity.save(context, pending)
            val prefs = context.getSharedPreferences("cloud_backup_identity", Context.MODE_PRIVATE)
            val ciphertext = prefs.getString("pending", null)!!
            check(!ciphertext.contains(pending.credential) && !ciphertext.contains(pending.grant))
            check(CloudBackupIdentity.pending(isolated()) == pending)
            checks += "pending-credential-and-grant-round-trip-through-real-keystore-without-plaintext-prefs"

            val completing = pending.copy(completing = true, sourceId = UUID.randomUUID().toString(), generation = 7)
            CloudBackupIdentity.save(context, completing)
            check(CloudBackupIdentity.pending(isolated()) == completing)
            checks += "completion-target-and-generation-survive-context-reload"

            check(prefs.edit().putString("pending", "damaged-ciphertext").commit())
            check(runCatching { CloudBackupIdentity.pending(context) }.isFailure)
            check(prefs.getString("pending", null) == "damaged-ciphertext")
            check(CloudBackupIdentity.pendingForAuthorization(context) == null)
            check(!prefs.contains("pending"))
            check(prefs.all.any { it.key.startsWith("damaged_pending_") && it.value == "damaged-ciphertext" })
            check(CloudBackupIdentity.installationId(context) == installation)
            checks += "unreadable-pending-is-preserved-until-explicit-reauthorization-and-installation-id-remains"
        } catch (e: Throwable) { failure = e }
        finally {
            // Only names created above are removed. App identity and Keystore aliases stay untouched.
            for (name in listOf(prefix + "cloud_backup_identity", prefix + "other_cloud_backup_identity"))
                targetContext.deleteSharedPreferences(name)
        }
        finish(if (failure == null) RESULT_OK else RESULT_CANCELED, Bundle().apply {
            putString("checks", checks.joinToString("\n")); putInt("passed", checks.size)
            failure?.let { putString("failure", it.stackTraceToString()) }
        })
    }
    companion object { private const val RESULT_OK = -1; private const val RESULT_CANCELED = 0 }
}
