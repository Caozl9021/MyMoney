package io.github.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.billkit.spec.PersonalRule
import com.example.billkit.spec.PersonalRuleEngine
import com.example.billkit.spec.SpecDraftAdapter
import io.github.mymoney.data.AppDataEntity
import io.github.mymoney.data.SpecRuleStore
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun ruleAppName(app: String) = if(app=="com.tencent.mm") "微信" else "支付宝"
internal fun personalTypeName(type: String) = when(type) { "Income" -> "收入"; "Refund" -> "退款"; else -> "支出" }
internal fun personalRulePreview(rule: PersonalRule, raw: String): String? {
    val hit = PersonalRuleEngine.match(listOf(rule.copy(enabled=true)),rule.app,rule.inputType,raw) ?: return null
    if(SpecDraftAdapter.toDraft(hit.bill,rule.app,rule.id,System.currentTimeMillis())==null) return null
    return "${personalTypeName(hit.bill.type)} ¥${String.format(Locale.US,"%.2f",hit.bill.priceCents/100.0)}\n商户：${hit.bill.shopName.ifBlank { "未提供" }}\n支付方式：${hit.bill.accountNameFrom.ifBlank { "未提供" }}"
}

@Composable
internal fun PersonalRuleEditor(id: String, initial: PersonalRule?, records: List<AppDataEntity>, onClose: () -> Unit, onSaved: (String) -> Unit) {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var name by rememberSaveable(id) { mutableStateOf(initial?.name.orEmpty()) }
    var app by rememberSaveable(id) { mutableStateOf(initial?.app ?: "com.tencent.mm") }
    var source by rememberSaveable(id) { mutableStateOf(initial?.inputType ?: "PAGE") }
    var type by rememberSaveable(id) { mutableStateOf(initial?.billType ?: "Expend") }
    var keyword by rememberSaveable(id) { mutableStateOf(initial?.keyword.orEmpty()) }
    var amountSource by rememberSaveable(id) { mutableStateOf(initial?.amountSource ?: "AUTO") }
    var merchant by rememberSaveable(id) { mutableStateOf(initial?.merchant.orEmpty()) }
    var method by rememberSaveable(id) { mutableStateOf(initial?.method.orEmpty()) }
    var exampleTitle by rememberSaveable(id) { mutableStateOf("支付成功") }
    var exampleDescription by rememberSaveable(id) { mutableStateOf("使用零钱支付¥12.34") }
    var exampleMerchant by rememberSaveable(id) { mutableStateOf("示例商户") }
    var sampleId by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    var chooseSample by remember { mutableStateOf(false) }
    var more by rememberSaveable(id) { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var verified by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val selected=records.firstOrNull { it.id==sampleId }
    val rule=PersonalRule(id,name.trim(),app,source,type,keyword.trim(),amountSource,merchant.trim(),method.trim(),initial?.enabled ?: true)
    val signature=listOf(name,app,source,type,keyword,amountSource,merchant,method,exampleTitle,exampleDescription,exampleMerchant,selected?.data,sampleId).joinToString("\u0000")
    val canSave=verified==signature && preview!=null
    val dirty = name!=initial?.name.orEmpty() || keyword!=initial?.keyword.orEmpty() ||
        app!=(initial?.app ?: "com.tencent.mm") || source!=(initial?.inputType ?: "PAGE") || type!=(initial?.billType ?: "Expend") ||
        amountSource!=(initial?.amountSource ?: "AUTO") || merchant!=initial?.merchant.orEmpty() || method!=initial?.method.orEmpty() ||
        sampleId!=null || exampleTitle!="支付成功" || exampleDescription!="使用零钱支付¥12.34" || exampleMerchant!="示例商户"
    fun requestClose() { if(!busy) { if(dirty) showDiscard=true else onClose() } }

    fun commit(delete: Boolean=false) {
        if(busy) return
        busy=true
        scope.launch {
            try {
                if(delete) SpecRuleStore.deletePersonalRule(context,id) else {
                    check(canSave) { "请先预览当前规则" }
                    SpecRuleStore.savePersonalRule(context,rule)
                }
                onSaved(if(delete) "个人规则已删除" else if(rule.enabled) "个人规则已保存，后续识别生效" else "个人规则已保存，保持关闭")
            } catch(t: Exception) { error=ruleActionError(t) }
            finally { busy=false }
        }
    }
    Dialog(onDismissRequest={ requestClose() }, properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val p=LocalAbPalette.current
        Box(Modifier.fillMaxSize().background(p.pageBg)) {
            SubPageScaffold(title=if(initial==null) "添加规则" else "编辑规则",onBack={ requestClose() }) { padding ->
                LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(start=AbSpacing.page,end=AbSpacing.page,
                    top=padding.calculateTopPadding(),bottom=padding.calculateBottomPadding()),verticalArrangement=Arrangement.spacedBy(AbSpacing.m)) {
                    item {
                        InsetGroup {
                            ListRow(title="从未识别记录开始",subtitle="可选，最近的页面或凭证可自动带入示例",chevron=true,onClick={ chooseSample=true })
                        }
                    }
                    item { InsetGroup {
                        InputRow("名称",name,{ name=it.take(40) },placeholder="例如：美团还款")
                        InsetDivider()
                        Column(Modifier.padding(AbSpacing.l),verticalArrangement=Arrangement.spacedBy(AbSpacing.s)) {
                            Text("应用",style=AbType.footnote,color=p.secondaryLabel)
                            Segmented(listOf("微信","支付宝"),if(app=="com.tencent.mm") 0 else 1,{ app=if(it==0) "com.tencent.mm" else "com.eg.android.AlipayGphone";sampleId=null })
                            Text("记为什么",style=AbType.footnote,color=p.secondaryLabel)
                            Segmented(listOf("支出","收入","退款"),listOf("Expend","Income","Refund").indexOf(type),{ type=listOf("Expend","Income","Refund")[it] })
                        }
                        InputRow("包含文字",keyword,{keyword=it.take(80)},placeholder="例如：美团金融还款",dividerAbove=true)
                    } }
                    item { GroupFooter("匹配页面、标题、说明或商户中的文字；只处理成功且金额明确的交易。页面规则仅用于交易详情，需要开启页面识别；转账等特殊交易仍由内置规则处理。") }
                    item { InsetGroup {
                        ListRow(title="更多条件",subtitle="识别来源、金额位置与固定商户",chevron=true,onClick={more=!more})
                        if(more) {
                            Column(Modifier.padding(AbSpacing.l),verticalArrangement=Arrangement.spacedBy(AbSpacing.s)) {
                                Text("识别来源",style=AbType.footnote,color=p.secondaryLabel)
                                Segmented(listOf("支付页面","支付凭证","交易通知"),listOf("PAGE","DATA","NOTICE").indexOf(source),{source=listOf("PAGE","DATA","NOTICE")[it];sampleId=null})
                                Text("从哪里读金额",style=AbType.footnote,color=p.secondaryLabel)
                                Segmented(listOf("自动","标题","说明"),listOf("AUTO","TITLE","DESCRIPTION").indexOf(amountSource),{amountSource=listOf("AUTO","TITLE","DESCRIPTION")[it]})
                            }
                            InputRow("固定商户",merchant,{merchant=it.take(80)},placeholder="留空时自动读取",dividerAbove=true)
                            InputRow("支付方式",method,{method=it.take(80)},placeholder="留空时自动读取",dividerAbove=true)
                        }
                    } }
                    item { GroupHeader("先用一条示例检查") }
                    item { InsetGroup {
                        if(selected!=null) {
                            val input=PersonalRuleEngine.input(selected.app,selected.type,selected.data)
                            ListRow(title="已选${ruleAppName(selected.app)}记录",subtitle=listOfNotNull(input?.title,input?.description,input?.merchant).filter { it.isNotBlank() }.joinToString("\n"),subtitleMaxLines=6)
                            ListRow(title="改用手工示例",dividerAbove=true,onClick={sampleId=null})
                        } else {
                            InputRow("标题",exampleTitle,{exampleTitle=it.take(200)},placeholder="例如：支付成功")
                            InputRow("说明",exampleDescription,{exampleDescription=it.take(500)},placeholder="支付方式或交易说明",dividerAbove=true)
                            InputRow("商户",exampleMerchant,{exampleMerchant=it.take(80)},placeholder="示例商户名称",dividerAbove=true)
                        }
                    } }
                    item { GroupFooter("这里只测试识别结果，不会生成账单。没有可选记录时可以手工填写示例，金额请包含¥或“元”。") }
                    item { SecondaryButton("预览结果",onClick={
                        error=null;verified=null;preview=null
                        try {
                            rule.validate()
                            val raw=selected?.data ?: PersonalRuleEngine.example(rule,exampleTitle,exampleDescription,exampleMerchant)
                            val result=personalRulePreview(rule,raw)
                            require(result!=null) { "没有匹配到示例。请检查包含文字、交易类型和金额，失败或进行中的交易不能使用。" }
                            preview=result;verified=signature
                        } catch(t: Exception) { error=t.message }
                    },modifier=Modifier.fillMaxWidth(),enabled=!busy) }
                    preview?.let { result -> item { InsetGroup {
                        ListRow(title=if(canSave) "预览成功" else "内容已变化，请重新预览",subtitle=result)
                    } } }
                    error?.let { value -> item { GroupFooter(value) } }
                    item { PrimaryButton("保存规则",onClick={commit()},modifier=Modifier.fillMaxWidth(),enabled=canSave&&!busy,busy=busy) }
                    item { GroupFooter("保存后用于接下来的交易；更新官方规则包会保留这条个人规则。") }
                    if(initial!=null) item { DestructiveButton("删除这条规则",onClick={showDelete=true},modifier=Modifier.fillMaxWidth(),enabled=!busy) }
                }
            }
        }
        if(chooseSample) {
            val eligible=records.filter { !it.match && PersonalRuleEngine.input(it.app,it.type,it.data)!=null }
            AppDialog(title = "选择未识别记录", onDismiss = { chooseSample=false }) {
                if(eligible.isEmpty()) {
                    Text("暂无可用记录，可以先手工填写示例。只支持当前微信、支付宝的支付页面、标准支付凭证或交易通知。")
                } else {
                    InsetGroup {
                        eligible.forEachIndexed { index, row ->
                            val value=PersonalRuleEngine.input(row.app,row.type,row.data)!!
                            ListRow(
                                title="${ruleAppName(row.app)} · ${SimpleDateFormat("MM-dd HH:mm",Locale.US).format(Date(row.time))}",
                                subtitle=value.merchant.ifBlank { value.title },
                                dividerAbove=index>0,
                                onClick={
                                    sampleId=row.id;app=row.app;source=row.type
                                    if(keyword.isBlank()) keyword=value.merchant.take(80)
                                    if(name.isBlank()) name=(ruleAppName(row.app)+" · "+value.merchant.ifBlank { "个人规则" }).take(40)
                                    type=when { value.title.contains("退款") -> "Refund"; value.title.contains("收款") -> "Income"; else -> "Expend" }
                                    chooseSample=false
                                },
                            )
                        }
                    }
                }
                SecondaryButton("返回填写", onClick = { chooseSample=false }, modifier = Modifier.fillMaxWidth())
            }
        }
        if(showDiscard) DiscardChangesDialog(
            onContinue = { showDiscard=false },
            onDiscard = onClose,
        )
        if(showDelete) AppAlertDialog(
            title = "删除这条个人规则？",
            message = "不影响已有账单和其他规则，删除后不再使用这条规则识别新交易。",
            onDismiss = { showDelete=false },
            confirmLabel = "删除",
            onConfirm = { commit(true) },
            destructive = true,
            busy = busy,
        )
    }
}
