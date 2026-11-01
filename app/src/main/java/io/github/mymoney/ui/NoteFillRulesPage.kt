package io.github.mymoney.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.data.*
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun noteTypeName(type:String)=when(type){"INCOME"->"收入";"REFUND"->"退款";else->"支出"}

@Composable
internal fun NoteFillRulesPage(onBack:()->Unit) {
    val config by NoteFillStore.state.collectAsStateWithLifecycle()
    val storedError by NoteFillStore.error.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var editor by rememberSaveable{mutableStateOf<String?>(null)}
    Dialog(onDismissRequest=onBack,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().backgroundForNotes()) {
                SubPageScaffold("备注填充",onBack,reserveDock=false) {padding->
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=AbSpacing.page,end=AbSpacing.page,top=padding.calculateTopPadding(),bottom=padding.calculateBottomPadding()),verticalArrangement=Arrangement.spacedBy(AbSpacing.m)) {
                        item {InsetGroup {ListRow(title="商品说明优先",subtitle="新识别有商品说明时，自动用本次内容填备注",onClick={if(!busy){busy=true;scope.launch{try{withContext(Dispatchers.IO){NoteFillStore.setProductFirst(!config.productFirst)}}catch(t:Exception){error=t.message}finally{busy=false}}}},trailing={AbSwitch(config.productFirst,{checked->if(!busy){busy=true;scope.launch{try{withContext(Dispatchers.IO){NoteFillStore.setProductFirst(checked)}}catch(t:Exception){error=t.message}finally{busy=false}}}})})}}
                        item {GroupFooter("没有商品说明时沿用默认备注模板。下方商家规则优先；不会照搬上一笔商品，也不覆盖手动备注或历史账单。")}
                        (error ?: storedError)?.let {e->item{InsetGroup{ListRow(title=e)}}}
                        item {PrimaryButton("添加商家备注规则",onClick={editor="new"},modifier=Modifier.fillMaxWidth(),enabled=!busy)}
                        item {GroupHeader("商家备注规则 · ${config.rules.size} 条")}
                        if(config.rules.isEmpty())item {InsetGroup{ListRow(title="还没有商家规则",subtitle="可给淘宝闪购选择“使用本次商品说明”，每次填写当次店铺或商品。")}}
                        items(config.rules,key={it.key}) {rule->InsetGroup{ListRow(title=rule.merchant,subtitle="${ruleAppName(rule.app)} · ${noteTypeName(rule.type)} · ${rule.mode.label}"+(if(rule.mode==NoteFillMode.FIXED)"\n${rule.text}"else ""),onClick={editor=rule.key},chevron=true)}}
                        item {GroupFooter("按应用、交易类型和商家全名匹配。固定文字由你明确填写；这些设置随账本备份保存，更新官方规则包不会覆盖。")}
                    }
                }
        }
    }
    editor?.let {key->MerchantNoteRuleEditor(config.rules.firstOrNull{it.key==key},onClose={editor=null},onSaved={editor=null})}
}

@Composable
private fun Modifier.backgroundForNotes():Modifier = this.then(Modifier.background(LocalAbPalette.current.pageBg))

@Composable
private fun NoteModeChoices(mode:NoteFillMode,onChange:(NoteFillMode)->Unit,enabled:Boolean=true) {
    NoteFillMode.entries.forEachIndexed {index,value->
        ListRow(title=value.label,subtitle=when(value){NoteFillMode.PRODUCT->"每笔读取当次内容；没有商品说明时留空";NoteFillMode.FIXED->"始终使用你填写的文字";NoteFillMode.NONE->"备注留空，仍可手动填写"},dividerAbove=index>0,onClick={if(enabled)onChange(value)},trailing={RadioButton(selected=mode==value,onClick={onChange(value)},enabled=enabled)})
    }
}

@Composable
internal fun MerchantNoteRuleEditor(initial:MerchantNoteRule?,onClose:()->Unit,onSaved:()->Unit) {
    var app by rememberSaveable{mutableStateOf(initial?.app ?: "com.eg.android.AlipayGphone")}
    var merchant by rememberSaveable{mutableStateOf(initial?.merchant.orEmpty())}
    var type by rememberSaveable{mutableStateOf(initial?.type ?: "EXPENSE")}
    var mode by rememberSaveable{mutableStateOf(initial?.mode ?: NoteFillMode.PRODUCT)}
    var text by rememberSaveable{mutableStateOf(initial?.text.orEmpty())}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var discard by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val rule=MerchantNoteRule(app,merchant.trim(),type,mode,if(mode==NoteFillMode.FIXED)text.trim() else "")
    val dirty=rule!=(initial ?: MerchantNoteRule("com.eg.android.AlipayGphone",""))
    fun close(){if(!busy){if(dirty)discard=true else onClose()}}
    val invalid=runCatching{rule.validate()}.exceptionOrNull()?.message
    fun commit(remove:Boolean=false){if(busy)return;busy=true;scope.launch{try{withContext(Dispatchers.IO){if(remove)NoteFillStore.deleteRule(initial!!.key) else NoteFillStore.saveRule(rule,initial?.key)};onSaved()}catch(t:Exception){error=t.message}finally{busy=false}}}
    Dialog(onDismissRequest={close()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().backgroundForNotes()) {
            SubPageScaffold(if(initial==null)"添加商家备注规则" else "编辑商家备注规则",{close()},reserveDock=false){padding->
                LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(start=AbSpacing.page,end=AbSpacing.page,top=padding.calculateTopPadding(),bottom=padding.calculateBottomPadding()),verticalArrangement=Arrangement.spacedBy(AbSpacing.m)) {
                    item {InsetGroup{
                        InputRow("商家",merchant,{merchant=it.take(80)},placeholder="例如：淘宝闪购")
                        Column(Modifier.padding(AbSpacing.l),verticalArrangement=Arrangement.spacedBy(AbSpacing.s)) {
                            Text("应用",style=AbType.footnote,color=LocalAbPalette.current.secondaryLabel)
                            Segmented(listOf("微信","支付宝"),if(app=="com.tencent.mm")0 else 1,{app=if(it==0)"com.tencent.mm"else"com.eg.android.AlipayGphone"})
                            Text("交易类型",style=AbType.footnote,color=LocalAbPalette.current.secondaryLabel)
                            Segmented(listOf("支出","收入","退款"),listOf("EXPENSE","INCOME","REFUND").indexOf(type),{type=listOf("EXPENSE","INCOME","REFUND")[it]})
                        }
                    }}
                    item {GroupFooter("填写识别结果里的完整商家名称，按应用和交易类型精确匹配。淘宝闪购、美团等平台建议选本次商品说明，避免串店。")}
                    item {InsetGroup{NoteModeChoices(mode,{mode=it},!busy);if(mode==NoteFillMode.FIXED)InputRow("固定备注",text,{text=NoteTemplate.limit(it)},placeholder="请填写常用备注",dividerAbove=true)}}
                    item {NoteFillPreview(rule)}
                    error?.let{e->item{InsetGroup{ListRow(title=e)}}}
                    item {PrimaryButton("保存规则",onClick={commit()},modifier=Modifier.fillMaxWidth(),enabled=!busy&&invalid==null)}
                    if(initial!=null)item{DestructiveButton("删除这条备注规则",onClick={deleting=true},modifier=Modifier.fillMaxWidth(),enabled=!busy)}
                    item{GroupFooter("保存后用于之后的新识别，不重写旧账单；取消不会保存修改。")}
                }
            }
        }
    }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃本次修改？")},text={Text("备注规则还没有保存。")},confirmButton={TextButton(onClick=onClose){Text("放弃修改")}},dismissButton={TextButton(onClick={discard=false}){Text("继续编辑")}})
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除这条备注规则？")},text={Text("已有账单备注不变，之后恢复默认填充方式。")},confirmButton={TextButton(onClick={commit(true)}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}

@Composable
private fun NoteFillPreview(rule:MerchantNoteRule) {
    fun preview(product:String?):String=when(rule.mode){NoteFillMode.PRODUCT->product ?: "留空";NoteFillMode.FIXED->rule.text.ifBlank{"请填写固定备注"};NoteFillMode.NONE->"留空"}
    InsetGroup {ListRow(title="效果预览（示例）",subtitle="本次有商品说明：${preview("当次店铺或商品说明")}\n本次没有商品说明：${preview(null)}",subtitleMaxLines=6)}
}

/** Inline because the same confirmation UI is also hosted in a service overlay window. */
@Composable
internal fun InlineMerchantNoteEditor(draft:BillDraft,template:String,onClose:()->Unit,onSaved:(BillDraft)->Unit) {
    val initial=remember(draft.sourcePkg,draft.merchant,draft.type){NoteFillPolicy.ruleFor(NoteFillStore.state.value,draft)}
    var mode by rememberSaveable{mutableStateOf(initial?.mode ?: NoteFillMode.PRODUCT)}
    var text by rememberSaveable{mutableStateOf(initial?.text.orEmpty())}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val rule=MerchantNoteRule(draft.sourcePkg,draft.merchant.orEmpty(),draft.type.name,mode,if(mode==NoteFillMode.FIXED)text.trim()else"")
    val invalid=runCatching{rule.validate()}.exceptionOrNull()?.message
    Column(verticalArrangement=Arrangement.spacedBy(AbSpacing.s)) {
        Text("${ruleAppName(rule.app)} · ${rule.merchant}",style=AbType.footnote,color=LocalAbPalette.current.secondaryLabel)
        InsetGroup{NoteModeChoices(mode,{mode=it},!busy);if(mode==NoteFillMode.FIXED)InputRow("固定备注",text,{text=NoteTemplate.limit(it)},placeholder="填写常用备注",dividerAbove=true)}
        val preview=NoteFillPolicy.apply(draft.copy(note=null,noteOrigin=null),NoteFillConfig(false,listOf(rule)),template)
        Text("当前自动填充预览：${preview.note ?: "留空"}",style=AbType.footnote,color=LocalAbPalette.current.secondaryLabel)
        if(NoteFillPolicy.manual(draft))Text("当前手动备注会保留，规则用于之后的识别。",style=AbType.caption1,color=LocalAbPalette.current.secondaryLabel)
        error?.let{Text(it,style=AbType.footnote,color=LocalAbPalette.current.red)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(AbSpacing.s)) {
            SecondaryButton("取消",onClick=onClose,modifier=Modifier.weight(1f),enabled=!busy)
            PrimaryButton("保存方式",onClick={if(!busy){busy=true;scope.launch{try{withContext(Dispatchers.IO){NoteFillStore.saveRule(rule,initial?.key)};onSaved(NoteFillStore.prepare(draft,template))}catch(t:Exception){error=t.message}finally{busy=false}}}},modifier=Modifier.weight(1f),enabled=!busy&&invalid==null)
        }
    }
}
