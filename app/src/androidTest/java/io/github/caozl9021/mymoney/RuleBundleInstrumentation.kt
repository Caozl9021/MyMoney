package io.github.caozl9021.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import io.github.caozl9021.mymoney.data.*
import com.example.billkit.spec.SpecAssets
import com.example.billkit.spec.SpecRuleBundle
import com.example.billkit.spec.SpecRuleEngine
import com.example.billkit.spec.SpecRuleModel
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

/** 仅模拟器、独立内存Room和独立偏好；绝不改目标实例账本。 */
class RuleBundleInstrumentation : Instrumentation() {
    private val results = mutableListOf<Map<String, Any?>>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var error: Throwable? = null
        try {
            check(listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).all { value ->
                listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "vbox").any { value.contains(it, true) }
            }) { "Rules verification refuses real devices" }
            runBlocking { runCases() }
        } catch (t: Throwable) { error = t }
        val passed = error == null && results.none { it["status"] == "failed" }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", if (passed) "passed" else "failed")
            putString("report", MiniJson.encode(mapOf("status" to if (passed) "passed" else "failed",
                "database" to "isolated-in-memory-room9", "tests" to results.size, "results" to results,
                "error" to error?.stackTraceToString())))
        })
    }

    private suspend fun runCases() {
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                targetContext.getSharedPreferences("rule-bundle-instrumentation", mode)
        }
        val prefs = isolated.getSharedPreferences("mymoney", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
        try {
            fun read(name: String) = targetContext.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            val bundle = SpecRuleBundle.load(::read)
            val common = read(SpecAssets.COMMON_JS)
            db.bills().insert(BillEntity(priceCents = 1999, type = "EXPENSE", timeMillis = 1792000000000,
                merchant = "合成测试商户", method = "测试账户", orderTail = null, sourcePkg = "test.rules",
                strategy = "test", fingerprint = "test-rules-preserved", createdAt = 1792000000000))
            db.rules().insertData(AppDataEntity(data = "{\"fixture\":\"retained\"}", type = "DATA",
                app = "test.rules", time = 1792000000000, match = true, rule = "历史命中名称"))
            val legacy = RuleModelEntity(app = "old.system", type = "DATA", js = "var old={get:function(){return null;}};",
                name = "旧系统测试", systemRuleName = "rule_fixture", creator = "system")
            val user = legacy.copy(app = "user.rules", name = "用户自有行", systemRuleName = "user_fixture", creator = "user")
            val removedBank = legacy.copy(app = "com.android.phone", systemRuleName = "maimi_cmb_data_expense_v1", creator = "maimi")
            db.rules().insertRules(listOf(legacy, user, removedBank))
            val protected = protectedHash(db)
            val originalRows = db.rules().allRules()

            test("failed_raw_insert_prevents_rule_execution_and_draft") {
                val failedStorage = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                failedStorage.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture_raw BEFORE INSERT ON app_data BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END")
                val counter = SpecRuleEngine().apply { rebuild("var common={};", listOf(SpecRuleModel(
                    "com.tencent.mm", "DATA", "counter", "counter", js = "var counter={get:function(){throw Error('must not run');}};"))) }
                val result = SpecRuleStore.parseStored(failedStorage, counter, "com.tencent.mm", "DATA", "{}", "test", 1792000000000)
                check(result.hit == null && result.outcome == SpecRuleEngine.Outcome.ERROR)
                check(counter.lastOutcome == SpecRuleEngine.Outcome.NO_MATCH)
                counter.close()
                failedStorage.close()
            }
            test("self_rejects_out_of_scope_before_raw_storage_or_rule_execution") {
                val isolatedDb = Room.inMemoryDatabaseBuilder(targetContext, AppDb::class.java).build()
                val counter = SpecRuleEngine().apply { rebuild("var common={};", listOf(SpecRuleModel(
                    "com.android.phone", "DATA", "outside", "outside", js = "var outside={get:function(){throw Error('must not run');}};"))) }
                for (app in listOf("com.android.phone", "com.jingdong.app.mall", "com.unionpay")) {
                    val result = SpecRuleStore.parseStored(isolatedDb, counter, app, "DATA", "{}", "test", 1792000000000)
                    check(result.hit == null && result.outcome == SpecRuleEngine.Outcome.REJECTED)
                }
                check(counter.lastOutcome == SpecRuleEngine.Outcome.NO_MATCH)
                check(isolatedDb.rules().recentData(10).isEmpty() && isolatedDb.recognitions().allOnce().isEmpty())
                counter.close(); isolatedDb.close()
            }
            test("tampered_asset_rejected_before_database_write") {
                check(runCatching { SpecRuleBundle.load { if (it == SpecAssets.COMMON_JS) read(it) + "\n//tampered" else read(it) } }.isFailure)
                check(db.rules().allRules() == originalRows)
                check(protectedHash(db) == protected)
            }
            test("transaction_interruption_rolls_back_rules_and_preserves_data") {
                check(runCatching { SpecRuleStore.installBundle(isolated, db, bundle, common) { error("synthetic interruption") } }.isFailure)
                check(db.rules().allRules() == originalRows)
                check(protectedHash(db) == protected)
                check(prefs.getString("specRulesVersion", null) == null)
            }
            test("legacy_system_replaced_user_rule_and_app_data_preserved") {
                val engine = SpecRuleStore.installBundle(isolated, db, bundle, common)
                check(engine.isReady())
                check(engine.registeredRules() == bundle.rules.count { it.enabled })
                val rows = db.rules().allRules()
                check(rows.none { it.creator == "system" })
                check(rows.none { it.creator == "maimi" && it.app == "com.android.phone" })
                check(rows.count { it.creator == "maimi" } == bundle.rules.size)
                check(rows.filter { it.creator == "user" } == originalRows.filter { it.creator == "user" })
                check(protectedHash(db) == protected)
                engine.close()
            }
            test("same_bundle_is_idempotent") {
                val before = db.rules().allRules()
                SpecRuleStore.installBundle(isolated, db, bundle, common).close()
                check(db.rules().allRules() == before)
                check(protectedHash(db) == protected)
            }
            test("explicit_disabled_preference_survives_bundle_refresh") {
                val rule = db.rules().allRules().first { it.creator == "maimi" && it.enabled }
                db.rules().insertRules(listOf(rule.copy(enabled = false)))
                val engine = SpecRuleStore.installBundle(isolated, db, bundle, common)
                check(!db.rules().allRules().first { it.id == rule.id }.enabled)
                check(engine.registeredRules() == bundle.rules.count { it.enabled } - 1)
                check(protectedHash(db) == protected)
                engine.close()
            }
            test("stale_or_missing_marker_cannot_hide_corrupt_database_content") {
                val rule = db.rules().allRules().first { it.creator == "maimi" }
                db.rules().insertRules(listOf(rule.copy(name = "corrupted content")))
                prefs.edit().putString("specRulesVersion", bundle.digest).commit()
                SpecRuleStore.installBundle(isolated, db, bundle, common).close()
                check(db.rules().allRules().first { it.id == rule.id }.name == rule.name)
                prefs.edit().clear().commit()
                SpecRuleStore.installBundle(isolated, db, bundle, common).close()
                check(prefs.getString("specRulesVersion", null) == bundle.digest)
                check(protectedHash(db) == protected)
            }
        } finally {
            db.close()
            prefs.edit().clear().commit()
            targetContext.deleteSharedPreferences("rule-bundle-instrumentation")
        }
    }

    private suspend fun test(name: String, body: suspend () -> Unit) {
        val start = System.nanoTime()
        try { body(); results += mapOf("name" to name, "status" to "passed", "millis" to (System.nanoTime() - start) / 1_000_000) }
        catch (t: Throwable) { results += mapOf("name" to name, "status" to "failed", "error" to t.stackTraceToString()); throw t }
    }

    /** 所有业务表逐字段哈希，排除唯一允许改变的规则表与系统元表。 */
    private fun protectedHash(db: AppDb): String {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.filterNot { it == "rule_model" || it.startsWith("sqlite_") || it.startsWith("room_") || it.startsWith("android_") }
        val text = buildString {
            for (table in tables) {
                append(table)
                val rows = sql.query("SELECT * FROM `$table`").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { index ->
                            if (cursor.isNull(index)) "<null>" else cursor.getString(index).let { "${it.length}:$it" }
                        })
                    }
                }
                rows.sorted().forEach { append(it).append('\n') }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
