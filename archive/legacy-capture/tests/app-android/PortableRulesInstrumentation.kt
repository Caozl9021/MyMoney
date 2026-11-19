package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import io.github.mymoney.data.*
import com.example.billkit.spec.*
import kotlinx.coroutines.*
import java.io.File

/** Only the disposable emulator overlay. Never runs on a real phone. */
class PortableRulesInstrumentation : Instrumentation() {
    private var phase = "run"
    private val results = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { phase = arguments?.getString("phase") ?: "run"; super.onCreate(arguments); start() }
    private fun file(name: String) = File(targetContext.filesDir, "portable-test-$name")
    override fun onStart() {
        var failure: Throwable? = null
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "emulator only" }
            runBlocking {
                if (phase == "baseline") {
                    // Baseline uses only SQLite/Android APIs, so it can run against 1.0.1.
                    SQLiteDatabase.openDatabase(targetContext.getDatabasePath("mymoney.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                        db.execSQL("INSERT INTO bills(priceCents,type,timeMillis,merchant,sourcePkg,strategy,fingerprint,createdAt,assetLinked,assetKey,transferFeeCents) SELECT 1234,'EXPENSE',1793410000000,'规则保护合成样本','test.rules','test','portable-rules-sentinel',1793410000000,0,'',0 WHERE NOT EXISTS (SELECT 1 FROM bills WHERE fingerprint='portable-rules-sentinel')")
                        db.execSQL("INSERT INTO app_data(data,type,app,time,`match`,rule,issue,version,image) SELECT '{}','DATA','test.rules',1793410000000,0,'',0,'baseline','' WHERE NOT EXISTS (SELECT 1 FROM app_data WHERE app='test.rules' AND version='baseline')")
                    }
                    file("baseline").writeText(protectedHash())
                    results.add("baseline_business_tables_and_capture_preferences_saved")
                } else {
                    SpecRuleStore.ensureReady(targetContext)
                    if (phase == "cold") {
                        check(SpecRuleStore.status.value.source == "导入")
                        check(SpecRuleStore.status.value.version == "1.0.0")
                        check(SpecRuleStore.status.value.entries.first().enabled == false)
                        check(protectedHash() == file("baseline").readText())
                        results.add("cold_process_preserves_external_package_disabled_rule_and_business_data")
                    } else runCases()
                }
            }
        } catch (t: Throwable) { failure = t }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (failure == null) "passed" else "failed")
            putString("results", results.joinToString("\n"))
            putString("error", failure?.stackTraceToString())
        })
    }
    private suspend fun runCases() {
        check(protectedHash() == file("baseline").readText()) { "business data changed on app upgrade" }
        results.add("1_0_1_to_1_1_0_upgrade_preserves_business_tables_and_capture_settings")
        SpecRuleStore.restoreBuiltin(targetContext)
        val bytes = file("input.zip").readBytes()
        val pack = SpecRuleStore.preparePackage(targetContext, bytes)
        check(pack.validate() == 131)
        val before = protectedHash()
        check(SpecRuleStore.status.value.source == "内置")
        check(protectedHash() == before) // prepare/cancel has no business writes
        results.add("prepare_and_cancel_do_not_install_or_write_business_data")
        SpecRuleStore.importPackage(targetContext, bytes)
        check(SpecRuleStore.status.value.source == "导入" && SpecRuleStore.status.value.version == "1.0.0")
        results.add("real_android_rhino_import_131_samples_and_immediate_activation")
        val id = SpecRuleStore.status.value.entries.first().systemRuleName
        SpecRuleStore.setRuleEnabled(targetContext, id, false)
        SpecRuleStore.importPackage(targetContext, bytes)
        check(!SpecRuleStore.status.value.entries.first { it.systemRuleName == id }.enabled)
        results.add("idempotent_import_preserves_disabled_id")
        val stateBefore = File(targetContext.filesDir,"rule-packages/state.json").readText()
        try { SpecRuleStore.importPackage(targetContext, byteArrayOf(1,2,3)); error("bad package accepted") } catch (_: IllegalArgumentException) {}
        check(File(targetContext.filesDir,"rule-packages/state.json").readText() == stateBefore)
        results.add("invalid_package_leaves_active_state_unchanged")
        fun variant(version: String): ByteArray {
            val files=pack.files.toMutableMap()
            val rules = MiniJson.decode(files.getValue("rules_all.json")) as List<*>
            files["rules_all.json"] = MiniJson.encode(rules.mapIndexed { index, rule ->
                if(index == 0) (rule as Map<*, *>) + ("name" to "合成规则更新测试") else rule
            })
            files["manifest.json"] = MiniJson.encode((MiniJson.decode(files.getValue("manifest.json")) as Map<*, *>) +
                mapOf("version" to version, "packageVersion" to version, "notes" to "synthetic installation fault test",
                    "files" to files.filterKeys { it != "manifest.json" }.mapValues { PortableRulePackage.hash(it.value.toByteArray()) }))
            val out=java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { z -> files.forEach { (name,text) ->
                z.putNextEntry(java.util.zip.ZipEntry(name).apply { time=0L });z.write(text.toByteArray());z.closeEntry()
            } }
            return out.toByteArray()
        }
        try { SpecRuleStore.importPackage(targetContext,variant("1.0.0"));error("same version different bytes accepted") } catch (_: IllegalArgumentException) {}
        check(File(targetContext.filesDir,"rule-packages/state.json").readText() == stateBefore)
        results.add("same_version_different_content_rejected_without_mutation")
        val blockedTemp=File(targetContext.filesDir,"rule-packages/state.json.tmp")
        check(blockedTemp.mkdir())
        try {
            try { SpecRuleStore.importPackage(targetContext,variant("1.0.1"));error("failed pointer commit accepted") } catch (_: java.io.IOException) {}
            check(SpecRuleStore.status.value.version=="1.0.0")
            check(SpecRuleStore.status.value.entries.first().name==pack.bundle.rules.first().name)
            check(File(targetContext.filesDir,"rule-packages/state.json").readText()==stateBefore)
        } finally { blockedTemp.delete() }
        results.add("pointer_write_failure_after_database_install_restores_previous_generation")
        SpecRuleStore.importPackage(targetContext,variant("1.0.1"))
        check(SpecRuleStore.status.value.version=="1.0.1")
        check(SpecRuleStore.status.value.entries.first().name=="合成规则更新测试")
        check(SpecRuleStore.describePackage(pack).contains("降级"))
        SpecRuleStore.importPackage(targetContext,bytes)
        check(SpecRuleStore.status.value.version=="1.0.0")
        results.add("new_rule_snapshot_updates_immediately_and_downgrade_preview_is_explicit")
        val loop=SpecRuleEngine()
        loop.rebuild("",listOf(SpecRuleModel("com.tencent.mm","DATA","loop","loop",js="var loop={get:function(){while(true){} }};")))
        check(loop.parseDetailed("com.tencent.mm","DATA","{}").outcome==SpecRuleEngine.Outcome.ERROR)
        loop.close()
        results.add("android_interpreter_budget_stops_infinite_loop")
        SpecRuleStore.restoreBuiltin(targetContext)
        check(SpecRuleStore.status.value.source == "内置")
        SpecRuleStore.rollbackPackage(targetContext)
        check(SpecRuleStore.status.value.source == "导入")
        check(!SpecRuleStore.status.value.entries.first { it.systemRuleName == id }.enabled)
        results.add("restore_builtin_and_rollback_preserve_disabled_id")
        val row = AppDataEntity(data="{\"mMap\":{\"source\":\"微信支付\",\"title\":\"已支付¥26.77\",\"description\":\"使用零钱支付¥26.77\",\"display_name\":\"美团金融还款\",\"t\":1793419110639}}",
            type="DATA",app="com.tencent.mm",time=1793419110639)
        check(SpecRuleStore.preview(targetContext,row).contains("没有匹配"))
        check(protectedHash() == before)
        results.add("incident_preview_has_zero_ledger_history_diagnostic_learning_or_delivery_writes")
        coroutineScope {
            val inputs=async { repeat(20) { SpecRuleStore.preview(targetContext,row) } }
            val switches=async { repeat(2) { SpecRuleStore.restoreBuiltin(targetContext); SpecRuleStore.rollbackPackage(targetContext) } }
            inputs.await();switches.await()
        }
        check(protectedHash() == before)
        results.add("concurrent_preview_and_generation_switch_no_business_writes")
        // Simulate process death after DB projection was installed but before the authoritative file commit.
        AppDb.get(targetContext).rules().clearBundledRules()
        results.add("incomplete_database_projection_left_for_cold_recovery")
    }
    private fun protectedHash(): String {
        val out = StringBuilder()
        SQLiteDatabase.openDatabase(targetContext.getDatabasePath("mymoney.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables=mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('rule_model','room_master_table','sqlite_sequence','android_metadata') ORDER BY name",null).use { c -> while(c.moveToNext()) tables.add(c.getString(0)) }
            tables.forEach { table ->
                val rows=mutableListOf<String>()
                db.rawQuery("SELECT * FROM `$table`",null).use { c ->
                    while(c.moveToNext()) rows.add((0 until c.columnCount).joinToString("|") { if(c.isNull(it)) "<null>" else c.getString(it) })
                }
                out.append(table).append(rows.sorted().joinToString("\n"))
            }
        }
        val prefs=targetContext.getSharedPreferences("mymoney",0)
        listOf("enabled","capture_mode","page_fallback_enabled","hook_capture_enabled","notification_capture_enabled","auto_confirm","whitelist").forEach {
            val value=prefs.all[it];out.append(it).append(if(value is Set<*>) value.map{v->v.toString()}.sorted().joinToString() else value.toString())
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
