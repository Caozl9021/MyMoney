package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import com.example.billkit.*
import com.example.billkit.spec.*
import io.github.mymoney.data.*
import kotlinx.coroutines.runBlocking
import java.io.File

/** Private rule and accounting fixtures on an ephemeral emulator only. */
class PersonalRulesInstrumentation : Instrumentation() {
    private var phase="run"
    private val results=mutableListOf<String>()
    private fun file(name:String)=File(targetContext.filesDir,"personal-test-$name")
    override fun onCreate(arguments:Bundle?) {phase=arguments?.getString("phase") ?: "run";super.onCreate(arguments);start()}
    override fun onStart() {
        var failure:Throwable?=null
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {"emulator only"}
            runBlocking {
                when(phase) {
                    "baseline" -> {
                        SQLiteDatabase.openDatabase(targetContext.getDatabasePath("mymoney.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
                            db.execSQL("INSERT INTO bills(priceCents,type,timeMillis,merchant,sourcePkg,strategy,fingerprint,createdAt,assetLinked,assetKey,transferFeeCents) SELECT 1234,'EXPENSE',1793410000000,'个人规则合成保护样本','test.rules','test','personal-sentinel',1793410000000,0,'',0 WHERE NOT EXISTS (SELECT 1 FROM bills WHERE fingerprint='personal-sentinel')")
                        }
                        file("baseline").writeText(protectedHash());results.add("nonempty_1_1_0_baseline_saved")
                    }
                    "cold" -> {
                        SpecRuleStore.ensureReady(targetContext)
                        check(SpecRuleStore.status.value.personalRules.size==2)
                        check(!SpecRuleStore.status.value.personalRules.first{it.id=="personal_receipt_fixture"}.enabled)
                        check(page().draft.strategy.contains("personal_page_fixture"))
                        check(protectedHash()==file("baseline").readText())
                        results.add("cold_restart_restores_personal_page_registration_disabled_receipt_and_business_data")
                    }
                    else -> runCases()
                }
            }
        }catch(t:Throwable){failure=t}
        finish(if(failure==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("status",if(failure==null) "passed" else "failed");putString("results",results.joinToString("\n"));putString("error",failure?.stackTraceToString())
        })
    }
    private fun snapshot()=UiSnapshot(Packages.WECHAT,"com.tencent.mm.wallet.NewReceiptUI",listOf("支付成功","¥26.77","美团金融还款"))
    private fun page()=BillPipeline().process(snapshot(),1793419110639L) as BillPipeline.Outcome.Draft
    private suspend fun runCases() {
        SpecRuleStore.ensureReady(targetContext)
        check(protectedHash()==file("baseline").readText());results.add("1_1_0_to_1_2_0_preserves_existing_business_data")
        for(id in listOf("personal_receipt_fixture","personal_page_fixture"))
            if(SpecRuleStore.status.value.personalRules.any {it.id==id}) SpecRuleStore.deletePersonalRule(targetContext,id)
        val original=protectedHash()
        val rule=PersonalRule("personal_receipt_fixture","还款凭证",inputType="DATA",keyword="美团金融还款")
        val raw=PersonalRuleEngine.example(rule,"已支付¥26.77","使用零钱支付¥26.77","美团金融还款")
        val row=AppDataEntity(data=raw,type="DATA",app=Packages.WECHAT,time=1793419110639L)
        SpecRuleStore.savePersonalRule(targetContext,rule)
        check(SpecRuleStore.preview(targetContext,row).contains("26.77"));check(protectedHash()==original)
        results.add("form_rule_save_and_preview_do_not_write_bills_history_diagnostics_or_delivery")
        val export=SpecRuleStore.exportPersonalRules(targetContext)
        check(PersonalRuleCodec.decode(export)==listOf(rule))
        check(!export.contains("示例标题"));results.add("personal_file_export_roundtrip_contains_definitions_not_raw_samples")
        val memory=Room.inMemoryDatabaseBuilder(targetContext,AppDb::class.java).build()
        val engine=SpecRuleEngine()
        try {
            fun read(name:String)=targetContext.assets.open(name).bufferedReader().use{it.readText()}
            engine.rebuild(read(SpecAssets.COMMON_JS),SpecRuleBundle.load(::read).rules)
            val hit=SpecRuleStore.parseStored(memory,engine,Packages.WECHAT,"DATA",raw,"test-personal",1793419110639L,listOf(rule))
            check(hit.hit?.ruleId==rule.id && hit.hit?.draft?.priceCents==2677L)
            check(memory.rules().recentData(10).single().match)
            check(memory.bills().allOnce().isEmpty() && memory.recognitions().allOnce().isEmpty())
            results.add("real_raw_persistence_then_personal_match_keeps_confirmation_chain")
            val unsafe=PersonalRuleEngine.example(rule,"支付失败","使用零钱支付¥26.77","美团金融还款")
            check(SpecRuleStore.parseStored(memory,engine,Packages.WECHAT,"DATA",unsafe,"test-personal",1793419110639L,listOf(rule)).hit==null)
            results.add("failed_payment_cannot_be_enabled_by_personal_rule")
        }finally{engine.close();memory.close()}
        val bytes=file("input.zip").readBytes()
        SpecRuleStore.importPackage(targetContext,bytes)
        check(SpecRuleStore.status.value.personalRules==listOf(rule))
        SpecRuleStore.restoreBuiltin(targetContext)
        check(SpecRuleStore.status.value.personalRules==listOf(rule))
        SpecRuleStore.rollbackPackage(targetContext)
        check(SpecRuleStore.status.value.personalRules==listOf(rule))
        results.add("official_import_restore_and_rollback_keep_personal_rules")
        val pageRule=PersonalRule("personal_page_fixture","还款页面",inputType="PAGE",keyword="美团金融还款",merchant="我的页面商户")
        SpecRuleStore.savePersonalRule(targetContext,pageRule)
        check(page().draft.merchant=="我的页面商户")
        check(page().draft.priceCents==2677L && page().draft.strategy.contains(pageRule.id))
        results.add("ordinary_accessibility_pipeline_uses_personal_page_rule_without_root")
        SpecRuleStore.savePersonalRule(targetContext,pageRule.copy(enabled=false))
        val disabled=BillPipeline().process(snapshot(),1793419110639L)
        check(disabled !is BillPipeline.Outcome.Draft || !disabled.draft.strategy.contains(pageRule.id))
        SpecRuleStore.savePersonalRule(targetContext,pageRule)
        results.add("page_toggle_updates_native_registration_immediately")
        SpecRuleStore.importPersonalRules(targetContext,export)
        check(SpecRuleStore.status.value.personalRules.size==2)
        results.add("personal_file_merges_by_stable_id_without_removing_other_rules")
        val stateFile=File(targetContext.filesDir,"rule-packages/state.json")
        val stateBefore=stateFile.readText()
        val blocked=File(targetContext.filesDir,"rule-packages/state.json.tmp");check(blocked.mkdir())
        try {
            try {SpecRuleStore.savePersonalRule(targetContext,pageRule.copy(merchant="失败候选"));error("commit should fail")}catch(_:java.io.IOException){}
            check(stateFile.readText()==stateBefore);check(page().draft.merchant=="我的页面商户")
        }finally{blocked.delete()}
        results.add("failed_personal_commit_keeps_previous_page_behavior_and_file")
        SpecRuleStore.deletePersonalRule(targetContext,rule.id)
        check(SpecRuleStore.status.value.personalRules.size==1)
        SpecRuleStore.importPersonalRules(targetContext,export)
        SpecRuleStore.savePersonalRule(targetContext,rule.copy(enabled=false))
        check(SpecRuleStore.preview(targetContext,row).contains("没有匹配"))
        check(protectedHash()==original)
        results.add("delete_reimport_disable_preserve_existing_business_tables_and_settings")
    }
    private fun protectedHash():String {
        val out=StringBuilder()
        SQLiteDatabase.openDatabase(targetContext.getDatabasePath("mymoney.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            val tables=mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('rule_model','room_master_table','sqlite_sequence','android_metadata') ORDER BY name",null).use {c->while(c.moveToNext())tables.add(c.getString(0))}
            tables.forEach {table ->
                val rows=mutableListOf<String>();db.rawQuery("SELECT * FROM `$table`",null).use {c->while(c.moveToNext())rows.add((0 until c.columnCount).joinToString("|"){if(c.isNull(it))"<null>"else c.getString(it)})}
                out.append(table).append(rows.sorted().joinToString("\n"))
            }
        }
        val prefs=targetContext.getSharedPreferences("mymoney",0)
        listOf("enabled","capture_mode","page_fallback_enabled","hook_capture_enabled","notification_capture_enabled","auto_confirm","whitelist").forEach {key ->
            val v=prefs.all[key];out.append(key).append(if(v is Set<*>)v.map{it.toString()}.sorted().joinToString()else v.toString())
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
