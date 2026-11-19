package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import com.example.billkit.*
import com.example.billkit.notification.*
import com.example.billkit.spec.*
import io.github.mymoney.capture.*
import io.github.mymoney.data.*
import io.github.mymoney.overlay.ConfirmOverlay
import kotlinx.coroutines.*
import java.io.File

/** Synthetic fixtures; refuses physical devices. Live delivery is a separate explicit phase. */
class NotificationTemplatesInstrumentation : Instrumentation() {
    private var phase="run"
    private val results=mutableListOf<String>()
    private fun file(n:String)=File(targetContext.filesDir,"notify-test-$n")
    override fun onCreate(arguments:Bundle?) {phase=arguments?.getString("phase") ?: "run";super.onCreate(arguments);start()}
    override fun onStart() {
        var error:Throwable?=null
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {"emulator only"}
            runBlocking {
                when(phase) {
                    "baseline" -> {
                        SQLiteDatabase.openDatabase(targetContext.getDatabasePath("mymoney.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
                            db.execSQL("INSERT INTO bills(priceCents,type,timeMillis,merchant,sourcePkg,strategy,fingerprint,createdAt,assetLinked,assetKey,transferFeeCents) SELECT 1234,'EXPENSE',1793410000000,'通知模板升级保护样本','test.rules','test','notify-sentinel',1793410000000,0,'',0 WHERE NOT EXISTS (SELECT 1 FROM bills WHERE fingerprint='notify-sentinel')")
                        }
                        SpecRuleStore.ensureReady(targetContext)
                        SpecRuleStore.savePersonalRule(targetContext,PersonalRule("personal_notify_fixture","升级保留个人规则",keyword="个人升级样本"))
                        SpecRuleStore.importPackage(targetContext,file("old.zip").readBytes())
                        file("baseline").writeText(protectedHash());results.add("112_nonempty_business_data_external_old_package_and_personal_rule_baseline")
                    }
                    "cold" -> {
                        SpecRuleStore.ensureReady(targetContext)
                        check(SpecRuleStore.status.value.source=="导入" && SpecRuleStore.status.value.version=="1.1.0")
                        check(candidate());check(SpecRuleStore.status.value.personalRules.single().id=="personal_notify_fixture")
                        check(protectedHash()==file("baseline").readText());results.add("cold_restart_restores_external_notification_snapshot_and_personal_rule_without_business_changes")
                    }
                    "live" -> live()
                    else -> runCases()
                }
            }
        }catch(t:Throwable){error=t}
        finish(if(error==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("status",if(error==null)"passed" else "failed");putString("results",results.joinToString("\n"));putString("error",error?.stackTraceToString())
        })
    }
    private fun observation()=NotificationObservation("notify-synthetic","com.eg.android.AlipayGphone","test","synthetic-notify",1,null,"0",1793506849867,1793506850100,"交易提醒","你有一笔17.53元的支出，点击领2元水燃费红包。",null,"mipush|com.eg.android.AlipayGphone|100060",null,null)
    private fun candidate()=NotificationParser().parse(observation()) is NotificationParseResult.Candidate
    private suspend fun runCases() {
        SpecRuleStore.ensureReady(targetContext)
        check(protectedHash()==file("baseline").readText());check(SpecRuleStore.status.value.source=="导入" && SpecRuleStore.status.value.version=="1.0.0")
        check(candidate());results.add("112_to_113_keeps_old_external_package_and_enables_builtin_template_fallback")
        val personal=SpecRuleStore.exportPersonalRules(targetContext)
        val bytes=file("new.zip").readBytes();val pack=SpecRuleStore.preparePackage(targetContext,bytes)
        check(pack.validate()==148 && pack.notificationConfig!!.templates.size==1)
        check(protectedHash()==file("baseline").readText());results.add("notification_package_prepare_cancel_148_production_samples_without_side_effects")
        SpecRuleStore.importPackage(targetContext,bytes);check(candidate())
        check(SpecRuleStore.status.value.version=="1.1.0");results.add("new_package_activates_notification_template_on_android")
        val rule="maimi_alipay_notice_expense_v1"
        SpecRuleStore.setRuleEnabled(targetContext,rule,false);check(!candidate())
        SpecRuleStore.setRuleEnabled(targetContext,rule,true);check(candidate());results.add("official_notice_switch_controls_native_template_immediately")
        fun variant(empty:Boolean):ByteArray {
            val f=pack.files.toMutableMap()
            val config=(MiniJson.decode(f.getValue("notification-templates.json")) as Map<*,*>).entries.associate{it.key.toString() to it.value}.toMutableMap()
            if(empty) {config["templates"]=emptyList<Any>();config["samples"]=emptyList<Any>()}
            else {
                config["templates"]=(config["templates"] as List<*>).map { raw ->
                    val t=(raw as Map<*,*>).entries.associate{it.key.toString() to it.value}.toMutableMap()
                    t["channelHashes"]=(t["channelHashes"] as List<*>)+PortableExpenseTemplate.sha256("synthetic-channel-v2");t
                }
                config["samples"]=(config["samples"] as List<*>).map {raw ->
                    val row=(raw as Map<*,*>).entries.associate{it.key.toString() to it.value}.toMutableMap()
                    val o=(row["observation"] as Map<*,*>).entries.associate{it.key.toString() to it.value}.toMutableMap()
                    if(o["channelId"]=="mipush|com.eg.android.AlipayGphone|100060")o["channelId"]="synthetic-channel-v2"
                    row["observation"]=o;row
                }
            }
            f["notification-templates.json"]=MiniJson.encode(config)
            val m=(MiniJson.decode(f.getValue("manifest.json")) as Map<*,*>).entries.associate {it.key.toString() to it.value}.toMutableMap()
            m["version"]=if(empty)"1.1.2" else "1.1.1";m["packageVersion"]=m["version"];m["files"]=f.filterKeys {it!="manifest.json"}.mapValues {PortableRulePackage.hash(it.value.toByteArray())};f["manifest.json"]=MiniJson.encode(m)
            return java.io.ByteArrayOutputStream().also {out->java.util.zip.ZipOutputStream(out).use {z->f.forEach {(n,v)->z.putNextEntry(java.util.zip.ZipEntry(n));z.write(v.toByteArray());z.closeEntry()} }}.toByteArray()
        }
        val other=observation().copy(channelId="synthetic-channel-v2");val existingParser=NotificationParser()
        check(existingParser.parse(other) !is NotificationParseResult.Candidate)
        SpecRuleStore.importPackage(targetContext,variant(false));check(existingParser.parse(other) is NotificationParseResult.Candidate)
        SpecRuleStore.rollbackPackage(targetContext);check(existingParser.parse(other) !is NotificationParseResult.Candidate && candidate())
        results.add("package_only_update_adds_channel_to_existing_parser_and_rollback_removes_it_without_apk_change")
        SpecRuleStore.importPackage(targetContext,variant(true));check(!candidate())
        SpecRuleStore.rollbackPackage(targetContext);check(candidate());results.add("complete_notification_snapshot_replacement_and_rollback_without_apk_update")
        val state=File(targetContext.filesDir,"rule-packages/state.json");val before=state.readText();val blocked=File(state.path+".tmp");check(blocked.mkdir())
        try {try {SpecRuleStore.setRuleEnabled(targetContext,rule,false);error("must fail")}catch(_:java.io.IOException){}
            check(state.readText()==before && candidate())
        }finally {blocked.delete()}
        results.add("failed_durable_commit_keeps_previous_live_notification_generation")
        SpecRuleStore.restoreBuiltin(targetContext);check(candidate());check(SpecRuleStore.exportPersonalRules(targetContext)==personal)
        SpecRuleStore.importPackage(targetContext,bytes);check(candidate());check(SpecRuleStore.exportPersonalRules(targetContext)==personal)
        check(protectedHash()==file("baseline").readText());results.add("restore_builtin_and_reimport_preserve_personal_rule_and_all_business_tables_preferences")
        val memory=Room.inMemoryDatabaseBuilder(targetContext,AppDb::class.java).build()
        try {
            val hit=NotificationParser().parse(observation()) as NotificationParseResult.Candidate
            val input=CaptureCoordinator.Input(hit.draft,"NOTIFICATION",observation().observationKey,"notify-memory",observation().receivedAt,observation().reviewSlotKey)
            val first=memory.withTransaction {CaptureCoordinator.acceptInTransaction(memory,input)}
            val again=memory.withTransaction {CaptureCoordinator.acceptInTransaction(memory,input)}
            check(first.created && !again.created && first.row.id==again.row.id)
            check(memory.recognitions().allOnce().single().priceCents==1753L && memory.bills().allOnce().isEmpty())
            results.add("native_candidate_enters_real_coordinator_idempotently_without_auto_booking")
        }finally{memory.close()}
    }
    private suspend fun live() {
        SpecRuleStore.ensureReady(targetContext)
        val prefs=targetContext.getSharedPreferences("mymoney",0)
        prefs.edit().putBoolean("enabled",true).putBoolean("notification_capture_enabled",true).putBoolean("auto_confirm",true).commit()
        check(PaymentNotificationListener.isAuthorized(targetContext));check(ConfirmOverlay.canShow(targetContext))
        val db=AppDb.get(targetContext);val beforeRows=db.recognitions().allOnce().size;val beforeBills=db.bills().allOnce().size;val beforeAck=db.capture().deliveries().count{it.state==CaptureDeliveryState.ACKNOWLEDGED}
        val now=System.currentTimeMillis();val o=observation().copy(eventId="synthetic-live-$now",notificationKey="synthetic-live-$now",postTime=now,receivedAt=now,configurationToken=NotificationIngress.configurationToken(targetContext))
        repeat(3){NotificationIngress.offer(targetContext,o)}
        withTimeout(15000){while(db.capture().deliveries().count{it.state==CaptureDeliveryState.ACKNOWLEDGED}!=beforeAck+1)delay(100)}
        delay(700)
        check(db.recognitions().allOnce().size==beforeRows+1);check(db.bills().allOnce().size==beforeBills);check(ConfirmOverlay.isShowing())
        val row=db.recognitions().allOnce().first {it.priceCents==1753L && it.recognizedAt>=now}
        check(row.status==RecognitionStatus.PENDING);results.add("three_live_notification_callbacks_create_one_1753_pending_overlay_zero_bills")
        val parser=NotificationParser();val receipt=(parser.parse(o) as NotificationParseResult.Candidate).draft.copy(merchant="合成凭证商户",method="合成账户",strategy="spec:synthetic-notify-receipt")
        val accepted=CaptureCoordinator.accept(targetContext,CaptureCoordinator.Input(receipt,"HOOK","synthetic-receipt-$now",receivedAt=now+1000))!!
        CaptureCoordinator.deliver(targetContext,accepted){true}
        check(db.capture().deliveries().count{it.state==CaptureDeliveryState.ACKNOWLEDGED}==beforeAck+1 && db.bills().allOnce().size==beforeBills)
        results.add("later_same_amount_receipt_keeps_cross_source_review_and_no_second_presentation")
        File(targetContext.filesDir,"notify-test-live-result").writeText("one overlay; 1753 cents; zero added bills")
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
