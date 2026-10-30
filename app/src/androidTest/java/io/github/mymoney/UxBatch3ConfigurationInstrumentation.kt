package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.room.withTransaction
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.Backup
import io.github.mymoney.data.BackupStore
import io.github.mymoney.data.CategoryGroup
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.MiniJson
import com.example.billkit.BillType
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** Opt-in configuration-only checks. No bill/history writes, no backup database restore. */
class UxBatch3ConfigurationInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        if (args.getString("mode") in listOf("seed-empty-ui", "restore-ui")) {
            retainedUiConfiguration()
            return
        }
        var failure: Throwable? = null
        val results = mutableListOf<String>()
        var originalPrefs: Map<String, Map<String, Any?>>? = null
        var beforeDb: String? = null
        var afterDb: String? = null
        var prefsRestored = false
        var writesStarted = false
        try {
            check(args.getString("allowWrites") == "ux3-config-only") { "Requires allowWrites=ux3-config-only" }
            LedgerConfig.init(targetContext)
            originalPrefs = snapshotPreferences()
            val prefs = targetContext.getSharedPreferences("ledger_config", Context.MODE_PRIVATE)
            beforeDb = databaseDigest()
            writesStarted = true
            LedgerConfig.restore(emptyList(), emptyList(), emptyList(), null)
            // Read through persisted preference text and all public accessors, not a cached UI list.
            check(prefs.getString("expense_groups", null) == "[]")
            check(prefs.getString("income_groups", null) == "[]")
            check(prefs.getString("accounts", null) == "[]")
            repeat(3) {
                check(LedgerConfig.expenseGroups.isEmpty() && LedgerConfig.incomeGroups.isEmpty() && LedgerConfig.accounts.isEmpty())
            }
            results += "explicit-empty-persisted-and-reread"

            LedgerConfig.addGroup(BillType.EXPENSE, "UX3配置分类")
            LedgerConfig.addGroup(BillType.INCOME, "UX3配置收入")
            LedgerConfig.addAccount("UX3配置账户")
            check(LedgerConfig.expenseGroups == listOf(CategoryGroup("UX3配置分类")))
            check(LedgerConfig.accounts == listOf("UX3配置账户"))
            LedgerConfig.removeGroup(BillType.EXPENSE, "UX3配置分类")
            LedgerConfig.removeGroup(BillType.INCOME, "UX3配置收入")
            LedgerConfig.removeAccount("UX3配置账户")
            check(LedgerConfig.expenseGroups.isEmpty() && LedgerConfig.incomeGroups.isEmpty() && LedgerConfig.accounts.isEmpty())
            results += "add-and-delete-last-remains-empty"

            val exported = Backup.Snapshot(0L, emptyList(), Backup.Ledger(
                LedgerConfig.expenseGroups, LedgerConfig.incomeGroups, LedgerConfig.accounts, LedgerConfig.noteTemplate), null)
            val decoded = Backup.decode(Backup.encode(exported)).ledger!!
            LedgerConfig.restore(listOf(CategoryGroup("UX3临时")), listOf(CategoryGroup("UX3临时")), listOf("UX3临时"), null)
            LedgerConfig.restore(decoded.expense, decoded.income, decoded.accounts, decoded.noteTemplate)
            check(LedgerConfig.expenseGroups.isEmpty() && LedgerConfig.incomeGroups.isEmpty() && LedgerConfig.accounts.isEmpty())
            results += "json2-empty-config-roundtrip"

            LedgerConfig.restore(listOf(CategoryGroup("UX3临时")), listOf(CategoryGroup("UX3临时")), listOf("UX3临时"), null)
            val emptyMerge = runBlocking {
                BackupStore.restore(targetContext, Backup.encode(exported), BackupStore.Mode.MERGE)
            }
            check(emptyMerge.bills == 0 && emptyMerge.history == 0 && emptyMerge.settings == null)
            check(LedgerConfig.expenseGroups.isEmpty() && LedgerConfig.incomeGroups.isEmpty() && LedgerConfig.accounts.isEmpty())
            check(databaseDigest() == beforeDb) { "Database changed in empty-config MERGE" }
            results += "production-merge-json2-empty-config-no-database-rows"

            LedgerConfig.restore(listOf(CategoryGroup("UX3保留")), listOf(CategoryGroup("UX3保留")), listOf("UX3保留"), null)
            val old = Backup.decode("""{"format":1,"bills":[],"ledger":{}}""").ledger!!
            LedgerConfig.restore(old.expense.takeIf { old.hasExpense }, old.income.takeIf { old.hasIncome },
                old.accounts.takeIf { old.hasAccounts }, old.noteTemplate.takeIf { old.hasNoteTemplate })
            check(LedgerConfig.expenseGroups == listOf(CategoryGroup("UX3保留")) && LedgerConfig.accounts == listOf("UX3保留"))
            check(Backup.decode("""{"format":1,"bills":[]}""").ledger == null)
            results += "legacy-missing-config-preserves-current"

            val retainedPreferences = prefs.all.toMap()
            val missingMerge = runBlocking {
                BackupStore.restore(targetContext, """{"format":1,"bills":[],"ledger":{},"settings":null}""", BackupStore.Mode.MERGE)
            }
            check(missingMerge.bills == 0 && missingMerge.history == 0 && missingMerge.settings == null)
            check(prefs.all == retainedPreferences) { "Missing-field MERGE changed configuration" }
            check(databaseDigest() == beforeDb) { "Database changed in missing-config MERGE" }
            results += "production-merge-json1-missing-fields-preserves-config-and-database"

            val note = LedgerConfig.noteTemplate
            LedgerConfig.resetToDefault()
            check(LedgerConfig.expenseGroups == LedgerConfig.DEFAULT_EXPENSE)
            check(LedgerConfig.incomeGroups == LedgerConfig.DEFAULT_INCOME)
            check(LedgerConfig.accounts == LedgerConfig.DEFAULT_ACCOUNTS)
            check(LedgerConfig.noteTemplate == note)
            results += "reset-only-lists-note-preserved"

            prefs.edit().putString("expense_groups", "broken").putString("income_groups", "{}").putInt("accounts", 7).commit()
            check(LedgerConfig.expenseGroups == LedgerConfig.DEFAULT_EXPENSE)
            check(LedgerConfig.incomeGroups == LedgerConfig.DEFAULT_INCOME)
            check(LedgerConfig.accounts == LedgerConfig.DEFAULT_ACCOUNTS)
            results += "corrupt-or-wrong-type-falls-back"
        } catch (t: Throwable) {
            failure = t
        } finally {
            if (writesStarted && originalPrefs != null) {
                try {
                    val original = originalPrefs.getValue("ledger_config")
                    restorePreferences(targetContext.getSharedPreferences("ledger_config", Context.MODE_PRIVATE), original)
                    check(snapshotPreferences() == originalPrefs) { "Preferences not restored exactly" }
                    prefsRestored = true
                    afterDb = databaseDigest()
                    check(afterDb == beforeDb) { "Bill/history field digest changed" }
                    results += "finally-all-preference-types-restored-and-database-fields-unchanged"
                } catch (restoreError: Throwable) {
                    if (failure == null) failure = restoreError else failure!!.addSuppressed(restoreError)
                }
            }
        }
        val report = MiniJson.encode(mapOf(
            "source" to "physical-device configuration-only checks", "realPayment" to false,
            "status" to if (failure == null) "passed" else "failed", "checks" to results,
            "preferencesRestored" to prefsRestored, "beforeDatabaseSha256" to beforeDb,
            "afterDatabaseSha256" to afterDb, "reason" to failure?.message,
            "uiDialogsTested" to false,
        ))
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("report", report) })
    }

    /** Retained UI mode stores a typed recovery marker before changing any preference. */
    private fun retainedUiConfiguration() {
        var failure: Throwable? = null
        var digest: String? = null
        val mode = args.getString("mode")!!
        var marker: File? = null
        var retained = false
        try {
            check(args.getString("allowWrites") == "ux3-config-only") { "Requires allowWrites=ux3-config-only" }
            val token = args.getString("runToken") ?: error("runToken required")
            check(token.matches(Regex("[A-Za-z0-9]{1,64}"))) { "Invalid runToken" }
            val markerFile = File(targetContext.filesDir, "ux3-config-$token.json")
            marker = markerFile
            LedgerConfig.init(targetContext)
            val prefs = targetContext.getSharedPreferences("ledger_config", Context.MODE_PRIVATE)
            if (mode == "seed-empty-ui") {
                check(!markerFile.exists()) { "Recovery marker exists; restore-ui first" }
                val allMarkers = targetContext.filesDir.listFiles().orEmpty()
                    .any { it.name.startsWith("ux3-config-") && it.extension == "json" }
                check(!allMarkers) { "Another retained configuration requires restore-ui first" }
                val before = prefs.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
                digest = databaseDigest()
                val backup = MiniJson.encode(mapOf("kind" to "UX3ConfigurationRecovery", "token" to token,
                    "databaseSha256" to digest, "preferences" to encodeTypedPreferences(before)))
                val temporary = File(targetContext.filesDir, "ux3-config-$token.tmp")
                temporary.writeText(backup, Charsets.UTF_8)
                check(temporary.renameTo(markerFile)) { "Could not persist recovery marker" }
                try {
                    check(prefs.edit().putString("expense_groups", "[]").putString("income_groups", "[]")
                        .putString("accounts", "[]").commit()) { "Could not seed empty lists" }
                    check(LedgerConfig.expenseGroups.isEmpty() && LedgerConfig.incomeGroups.isEmpty() && LedgerConfig.accounts.isEmpty())
                    check(databaseDigest() == digest) { "Database changed during configuration seed" }
                    retained = true
                } catch (seedError: Throwable) {
                    // A failed seed never leaves deliberate UI state behind if restoration succeeds.
                    restorePreferences(prefs, before)
                    check(prefs.all == before) { "Seed failure recovery differs; preserve marker" }
                    check(markerFile.delete()) { "Could not remove recovered marker" }
                    throw seedError
                }
            } else {
                check(markerFile.exists()) { "Recovery marker missing" }
                val backup = MiniJson.decode(markerFile.readText(Charsets.UTF_8)) as? Map<*, *> ?: error("Invalid recovery marker")
                check(backup["kind"] == "UX3ConfigurationRecovery" && backup["token"] == token) { "Recovery marker identity mismatch" }
                digest = backup["databaseSha256"] as? String ?: error("Missing database digest")
                check(databaseDigest() == digest) { "Database changed since seed; keep recovery marker and restore database state first" }
                val before = decodeTypedPreferences(backup["preferences"])
                restorePreferences(prefs, before)
                check(prefs.all == before) { "Configuration did not restore exactly; keep marker" }
                check(databaseDigest() == digest) { "Database changed during restore; keep marker" }
                check(markerFile.delete()) { "Configuration restored but marker removal failed" }
            }
        } catch (t: Throwable) { failure = t }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("report", MiniJson.encode(mapOf("mode" to mode,
                "status" to if (failure == null) "passed" else "failed", "reason" to failure?.message,
                "databaseSha256" to digest, "configurationRetainedForUi" to retained,
                "recoveryMarkerPresent" to (marker?.exists() ?: false), "realPayment" to false)))
        })
    }

    private fun encodeTypedPreferences(values: Map<String, Any?>): Map<String, Any?> = values.mapValues { (_, value) ->
        val type = when (value) {
            null -> "null"
            is String -> "string"
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Set<*> -> "strings"
            else -> error("Unsupported preference type")
        }
        mapOf("type" to type, "value" to when (value) {
            is Float -> value.toString() // Preserve exact Float round-trip, including special values.
            is Set<*> -> value.map { it as String }.sorted()
            else -> value
        })
    }

    private fun decodeTypedPreferences(raw: Any?): Map<String, Any?> {
        val values = raw as? Map<*, *> ?: error("Missing typed preferences")
        return values.entries.associate { (rawKey, rawValue) ->
            val key = rawKey as? String ?: error("Invalid preference key")
            val field = rawValue as? Map<*, *> ?: error("Invalid typed preference")
            val value = field["value"]
            key to when (field["type"]) {
                "null" -> null
                "string" -> value as? String ?: error("Invalid string preference")
                "boolean" -> value as? Boolean ?: error("Invalid boolean preference")
                "int" -> (value as? Number)?.toInt() ?: error("Invalid int preference")
                "long" -> (value as? Number)?.toLong() ?: error("Invalid long preference")
                "float" -> (value as? String)?.toFloat() ?: error("Invalid float preference")
                "strings" -> (value as? List<*>)?.map { it as? String ?: error("Invalid set item") }?.toSet()
                    ?: error("Invalid string set preference")
                else -> error("Unknown preference type")
            }
        }
    }

    private fun snapshotPreferences(): Map<String, Map<String, Any?>> {
        val names = File(targetContext.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.extension == "xml" }.map { it.nameWithoutExtension }.toMutableSet()
        names += "ledger_config"
        return names.sorted().associateWith { name ->
            targetContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
        }
    }

    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit()
        prefs.all.keys.filterNot(values::containsKey).forEach(editor::remove)
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                else -> error("Unsupported preference type")
            }
        }
        check(editor.commit()) { "Preference restoration failed" }
    }

    /** Sorted data-class values include every persisted field, including nulls and identities. */
    private fun databaseDigest(): String = runBlocking {
        val db = AppDb.get(targetContext)
        val rows = db.withTransaction {
            listOf(db.bills().allOnce().sortedBy { it.id }, db.recognitions().allOnce().sortedBy { it.id })
        }
        MessageDigest.getInstance("SHA-256").digest(rows.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
