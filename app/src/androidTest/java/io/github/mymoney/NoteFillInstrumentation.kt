package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.system.Os
import androidx.room.Room
import androidx.room.withTransaction
import com.example.billkit.*
import com.example.billkit.spec.*
import io.github.mymoney.capture.CaptureCoordinator
import io.github.mymoney.data.*
import kotlinx.coroutines.*
import io.github.mymoney.overlay.ConfirmOverlay
import android.graphics.Bitmap
import java.io.File

/** Protected synthetic data on an ephemeral emulator only; no physical device execution. */
class NoteFillInstrumentation:Instrumentation() {
    private var phase="run"
    private val results=mutableListOf<String>()
    private fun file(n:String)=File(targetContext.filesDir,"note-test-$n")
    override fun onCreate(arguments:Bundle?){phase=arguments?.getString("phase") ?: "run";super.onCreate(arguments);start()}
    override fun onStart(){var error:Throwable?=null
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")){"emulator only"}
            LedgerConfig.init(targetContext)
            if(phase!="baseline") NoteFillStore.init()
            runBlocking {when(phase){
                "baseline" -> {
                    AssetStore.init(targetContext);AssetStore.awaitReady()
                    val db=AppDb.get(targetContext)
                    SQLiteDatabase.openDatabase(targetContext.getDatabasePath("mymoney.db").path,null,SQLiteDatabase.OPEN_READWRITE).use{sql->sql.execSQL("INSERT INTO bills(priceCents,type,timeMillis,merchant,sourcePkg,strategy,fingerprint,createdAt,assetLinked,assetKey,transferFeeCents,note) SELECT 1234,'EXPENSE',1793410000000,'备注升级保护商户','test.note','test','note-sentinel',1793410000000,0,'',0,'已有手动备注' WHERE NOT EXISTS (SELECT 1 FROM bills WHERE fingerprint='note-sentinel')")}
                    db.assets().putMeta(AssetMeta("pending-restore-config",""))
                    SpecRuleStore.ensureReady(targetContext)
                    SpecRuleStore.importPackage(targetContext,file("rules.zip").readBytes())
                    SpecRuleStore.savePersonalRule(targetContext,PersonalRule("personal_note_fixture","升级保留个人规则",keyword="备注升级样本"))
                    file("baseline").writeText(protectedHash())
                    file("rule-state").writeText(File(targetContext.filesDir,"rule-packages/state.json").readText())
                    results.add("113_nonempty_ledger_manual_note_external_package_personal_rule_baseline")
                }
                "restore-ui" -> {NoteFillStore.restore(file("config").readText());results.add("ui_fixture_policy_restored")}
                "card-regression" -> cardRegression()
                "confirm-ui" -> showConfirmation()
                "setup-ui" -> {
                    AssetStore.init(targetContext);AssetStore.awaitReady()
                    NoteFillStore.save(NoteFillConfig(true,listOf(MerchantNoteRule(Packages.ALIPAY,"淘宝闪购"))))
                    results.add("fresh_emulator_confirmation_fixture_ready")
                }
                "inspect-confirm" -> {
                    NoteFillStore.init()
                    val db=AppDb.get(targetContext)
                    val all=db.recognitions().allOnce()
                    results.add("persisted_rows="+all.map{listOf(it.id,it.transactionKey,it.note,it.noteOrigin,it.status)}.toString())
                    results.add("persisted_policy="+LedgerConfig.noteFillPolicy)
                    val id=file("ui-history-id").readText()
                    val row=db.recognitions().byId(id) ?: error("Expected history ID missing: "+id)
                    check(row.note=="Manual" && row.noteOrigin==NoteTemplate.USER && row.status==RecognitionStatus.PENDING)
                    check(db.bills().allOnce().size==file("ui-before-bills").readText().toInt())
                    check(NoteFillStore.state.value.rules.any{it.merchant=="淘宝闪购" && it.mode==NoteFillMode.NONE})
                    results.add("actual_inline_rule_save_keeps_manual_pending_note_and_zero_added_ledger_bills")
                }
                "cold" -> {
                    SpecRuleStore.ensureReady(targetContext)
                    check(protectedHash()==file("cold-hash").readText())
                    check(NoteFillCodec.encode(NoteFillStore.state.value)==file("config").readText())
                    check(NoteFillStore.prepare(page()).note==page().productName)
                    check(SpecRuleStore.status.value.personalRules.single().id=="personal_note_fixture")
                    results.add("cold_restart_keeps_note_modes_and_personal_rules_with_business_data_unchanged")
                }
                else -> runCases()
            }}
        }catch(t:Throwable){error=t}
        finish(if(error==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{
            putString("status",if(error==null)"passed" else "failed");putString("results",results.joinToString("\n"));putString("error",error?.stackTraceToString())
        })
    }
    private fun page():BillDraft {
        val goods="示例·胡辣汤·水煎包(园区店)外卖订单"
        val snapshot=UiSnapshot(Packages.ALIPAY,"com.alipay.mobile.bill.detail.BillDetailActivity",listOf("账单详情","淘宝闪购","支出17.53元","交易成功","网上消费红包","-0.47","支付时间","2026-11-01 12:20:49","付款方式","工商银行储蓄卡(0000)","商品说明",goods,goods,"支付奖励","立即领取2积分","更多"))
        return (BillPipeline().process(snapshot,1793506849000) as BillPipeline.Outcome.Draft).draft
    }
    private suspend fun cardRegression() {
        uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        check(ConfirmOverlay.canShow(targetContext))
        val db=AppDb.get(targetContext)
        SpecRuleStore.ensureReady(targetContext)
        val existing=SpecRuleStore.status.value.personalRules
        val legacy=PersonalRule("personal_clock_test","时间验证",keyword="测试商家")
        SpecRuleStore.savePersonalRule(targetContext,legacy)
        val dated=SpecRuleStore.status.value.personalRules.first {it.id==legacy.id}
        check(dated.updatedAt!=null)
        delay(30)
        SpecRuleStore.savePersonalRule(targetContext,dated.copy(enabled=false))
        check(SpecRuleStore.status.value.personalRules.first{it.id==legacy.id}.updatedAt==dated.updatedAt)
        delay(30)
        SpecRuleStore.savePersonalRule(targetContext,dated.copy(name="时间验证修改"))
        check(SpecRuleStore.status.value.personalRules.first{it.id==legacy.id}.updatedAt!!>dated.updatedAt!!)
        SpecRuleStore.deletePersonalRule(targetContext,legacy.id)
        check(SpecRuleStore.status.value.personalRules==existing)
        results.add("personal_content_edit_sets_time_toggle_preserves_time_other_rules_unchanged")
        val before=db.bills().allOnce().size
        val now=System.currentTimeMillis()
        val draft=page().copy(timeMillis=now,note="Manual",noteOrigin=NoteTemplate.USER,transactionKey="synthetic-card-regression-$now",hasExplicitTime=true)
        val accepted=CaptureCoordinator.accept(targetContext,CaptureCoordinator.Input(draft,"PAGE","synthetic-card-regression-$now",receivedAt=now))!!
        val prefs=targetContext.getSharedPreferences("mymoney",0);val old=prefs.all["auto_confirm"]
        try {
            prefs.edit().putBoolean("auto_confirm",true).commit()
            check(CaptureCoordinator.deliver(targetContext,accepted){true}=="overlay")
            delay(1800)
            fun nodes():List<android.view.accessibility.AccessibilityNodeInfo> {
                val out=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun visit(n:android.view.accessibility.AccessibilityNodeInfo?){if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i))}
                uiAutomation.windows.forEach{visit(it.root)};return out
            }
            suspend fun click(label:String,description:Boolean=false) {
                val n=withTimeout(8000) {var node:android.view.accessibility.AccessibilityNodeInfo?=null
                    while(node==null){node=nodes().firstOrNull{if(description)it.contentDescription?.toString()?.startsWith(label)==true else it.text?.toString()==label};if(node==null)delay(150)};node}
                var target:android.view.accessibility.AccessibilityNodeInfo?=n
                while(target!=null&&!target.isClickable)target=target.parent
                check(target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true){"click failed: $label"}
                results.add("clicked="+label)
                delay(600)
            }
            suspend fun expectNote(text:String) {
                try {withTimeout(10000){while(nodes().none{it.isEditable&&it.text?.toString()==text})delay(150)}}
                catch(t:Throwable){results.add("expected_note="+text+" actual="+nodes().filter{it.isEditable}.map{it.text?.toString()});uiAutomation.takeScreenshot()?.let{b->file("card-failed.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()};throw t}
            }
            results.add("accepted_note="+accepted.row.note+" origin="+accepted.row.noteOrigin)
            expectNote("Manual")
            uiAutomation.takeScreenshot()?.let {b->file("card-regression-collapsed.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            check(nodes().none{it.text?.toString()=="填充方式" || it.text?.toString()=="来源信息"})
            click("用作备注：商品 · ",true)
            check(ConfirmOverlay.isShowing())
            expectNote(draft.productName!!)
            check(nodes().none{it.text?.toString()=="使用这条备注？"})
            uiAutomation.takeScreenshot()?.let {b->file("card-regression-filled.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            click("撤销建议",true)
            expectNote("Manual")
            click("用作备注：淘宝闪购",true)
            expectNote("淘宝闪购")
            click("撤销建议",true)
            expectNote("Manual")
            click("更多")
            check(nodes().any{it.text?.toString()=="来源信息"})
            click("设置此商家的备注")
            check(ConfirmOverlay.isShowing())
            uiAutomation.takeScreenshot()?.let {b->file("card-regression.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            check(db.bills().allOnce().size==before)
            results.add("real_overlay_long_product_suggestion_direct_fill_undo_more_and_inline_editor_no_dialog_zero_bills")
        } finally {
            ConfirmOverlay.dismiss()
            prefs.edit().apply{if(old==null)remove("auto_confirm")else putBoolean("auto_confirm",old as Boolean)}.commit()
        }
    }
    private suspend fun showConfirmation() {
        val prefs=targetContext.getSharedPreferences("mymoney",0)
        val old=prefs.all["auto_confirm"]
        check(ConfirmOverlay.canShow(targetContext))
        val stop=file("stop-ui");stop.delete()
        val before=AppDb.get(targetContext).bills().allOnce().size
        try {
            prefs.edit().putBoolean("auto_confirm",true).commit()
            val now=System.currentTimeMillis()
            val draft=NoteFillStore.prepare(page().copy(timeMillis=now,transactionKey="synthetic-note-ui-$now",hasExplicitTime=true))
            val accepted=CaptureCoordinator.accept(targetContext,CaptureCoordinator.Input(draft,"PAGE","synthetic-note-ui-$now",receivedAt=now))!!
            file("ui-history-id").writeText(accepted.row.id)
            file("ui-before-bills").writeText(before.toString())
            check(CaptureCoordinator.deliver(targetContext,accepted){true}=="overlay")
            withTimeout(10000){while(!ConfirmOverlay.isShowing())delay(100)}
            delay(1800)
            uiAutomation.takeScreenshot()?.let {bitmap->file("confirm.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            withTimeout(180000){while(!stop.exists())delay(200)}
            check(AppDb.get(targetContext).bills().allOnce().size==before)
            results.add("actual_confirmation_product_note_inline_editor_zero_booked_bills")
        }finally {
            ConfirmOverlay.dismiss()
            prefs.edit().apply{if(old==null)remove("auto_confirm")else putBoolean("auto_confirm",old as Boolean)}.commit()
        }
    }
    private suspend fun runCases() {
        SpecRuleStore.ensureReady(targetContext)
        check(protectedHash()==file("baseline").readText())
        check(File(targetContext.filesDir,"rule-packages/state.json").readText()==file("rule-state").readText())
        check(NoteFillStore.state.value==NoteFillConfig())
        results.add("113_to_114_preserves_all_old_business_data_preferences_external_and_personal_rules")
        val originalBills=AppDb.get(targetContext).bills().allOnce()
        val raw=page();val first=NoteFillStore.prepare(raw)
        check(first.note==raw.productName && first.priceCents==1753L && first.merchant=="淘宝闪购")
        check(first.noteOrigin==NoteFillPolicy.PRODUCT);results.add("real_page_shape_current_product_note_keeps_merchant_money_and_identity")
        val product=MerchantNoteRule(Packages.ALIPAY,"淘宝闪购")
        NoteFillStore.save(NoteFillConfig(false,listOf(product)))
        check(NoteFillStore.prepare(raw.copy(productName="下一笔不同店商品")).note=="下一笔不同店商品")
        check(NoteFillStore.prepare(raw.copy(productName=null,productSource=null)).note==null)
        results.add("same_platform_each_order_uses_current_goods_missing_goods_never_reuses_last_item")
        NoteFillStore.saveRule(product.copy(mode=NoteFillMode.FIXED,text="常用固定备注"))
        check(NoteFillStore.prepare(raw).note=="常用固定备注")
        NoteFillStore.saveRule(product.copy(mode=NoteFillMode.NONE))
        check(NoteFillStore.prepare(raw).note==null)
        NoteFillStore.deleteRule(product.key);NoteFillStore.setProductFirst(false)
        check(NoteFillStore.prepare(raw).note==LedgerConfig.noteTemplate.replace("{商户}","淘宝闪购"))
        NoteFillStore.setProductFirst(true);check(NoteFillStore.prepare(raw).note==raw.productName)
        results.add("save_edit_fixed_off_delete_and_global_switch_are_immediate_and_not_transaction_writes")
        val memory=Room.inMemoryDatabaseBuilder(targetContext,AppDb::class.java).build()
        try {
            val noGoods=NoteFillStore.prepare(raw.copy(productName=null,productSource=null,transactionKey="same-note-order"))
            val incoming=NoteFillStore.prepare(raw.copy(transactionKey="same-note-order"))
            val a=memory.withTransaction{CaptureCoordinator.acceptInTransaction(memory,CaptureCoordinator.Input(noGoods,"NOTIFICATION","note-observation","note-event-1",1793506849000))}
            val b=memory.withTransaction{CaptureCoordinator.acceptInTransaction(memory,CaptureCoordinator.Input(incoming,"PAGE","note-page","note-event-2",1793506849100))}
            check(a.row.id==b.row.id && !b.created && b.row.note==raw.productName && b.row.noteOrigin==NoteFillPolicy.PRODUCT)
            val cleared=b.row.copy(note=null,noteOrigin=NoteTemplate.USER,protectedFields=CaptureFields.NOTE)
            memory.recognitions().update(cleared)
            val c=memory.withTransaction{CaptureCoordinator.acceptInTransaction(memory,CaptureCoordinator.Input(incoming,"PAGE","note-page-later","note-event-3",1793506849200))}
            check(c.row.note==null && c.row.noteOrigin==NoteTemplate.USER)
            check(memory.bills().allOnce().isEmpty() && memory.recognitions().allOnce().size==1)
            results.add("real_coordinator_same_order_upgrades_automatic_note_once_and_preserves_manual_clear_zero_bills")
        }finally{memory.close()}
        val config=NoteFillConfig(true,listOf(product,MerchantNoteRule(Packages.ALIPAY,"具体商家",mode=NoteFillMode.FIXED,text="固定备注"),MerchantNoteRule(Packages.WECHAT,"关闭商家",mode=NoteFillMode.NONE)))
        NoteFillStore.save(config)
        val prior=LedgerConfig.noteFillPolicy
        val directory=File(targetContext.applicationInfo.dataDir,"shared_prefs");check(File(directory,"ledger_config.xml").exists())
        val diskBefore=File(directory,"ledger_config.xml").readText()
        val permissions=Os.stat(directory.path).st_mode and 511
        try {
            Os.chmod(directory.path,365) // 0555: fail preference rename/write under target UID.
            try {NoteFillStore.setProductFirst(false);error("disk commit should fail")}catch(_:IllegalStateException){}
            check(NoteFillStore.state.value==config && LedgerConfig.noteFillPolicy==prior)
            check(File(directory,"ledger_config.xml").readText()==diskBefore)
        }finally{Os.chmod(directory.path,permissions)}
        NoteFillStore.init();check(NoteFillStore.state.value==config)
        check(protectedHash()==file("baseline").readText());results.add("actual_preference_disk_commit_failure_keeps_previous_disk_memory_and_runtime_policy")
        val personal=SpecRuleStore.exportPersonalRules(targetContext)
        SpecRuleStore.restoreBuiltin(targetContext);SpecRuleStore.importPackage(targetContext,file("rules.zip").readBytes())
        check(NoteFillStore.state.value==config && SpecRuleStore.exportPersonalRules(targetContext)==personal)
        results.add("official_rule_package_restore_import_does_not_cover_note_or_personal_rules")
        val exported=BackupStore.exportJson(targetContext)
        check(NoteFillCodec.decode(Backup.decode(exported).ledger!!.noteFillPolicy!!)==config)
        NoteFillStore.save(NoteFillConfig(false))
        BackupStore.restore(targetContext,exported,BackupStore.Mode.MERGE)
        check(NoteFillStore.state.value==config)
        check(AppDb.get(targetContext).bills().allOnce()==originalBills)
        results.add("real_json8_export_merge_restore_recovers_all_three_modes_and_preserves_existing_ledger_notes")
        val old=Backup.Snapshot(1793506849000,emptyList(),settings=null,ledger=Backup.Ledger(emptyList(),emptyList(),emptyList(),"{商户}",hasExpense=false,hasIncome=false,hasAccounts=false,hasNoteTemplate=false))
        BackupStore.restore(targetContext,Backup.encode(old),BackupStore.Mode.MERGE)
        check(NoteFillStore.state.value==config && AppDb.get(targetContext).bills().allOnce()==originalBills)
        results.add("old_backup_missing_policy_preserves_current_configuration_in_actual_restore_path")
        file("config").writeText(NoteFillCodec.encode(config));file("cold-hash").writeText(protectedHash())
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
        val ledger=targetContext.getSharedPreferences("ledger_config",0)
        ledger.all.toSortedMap().filterKeys {it!="note_fill_policy"}.forEach{(key,value)->out.append("ledger:").append(key).append(value.toString())}
        return java.security.MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
