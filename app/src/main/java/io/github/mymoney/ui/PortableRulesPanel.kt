package io.github.mymoney.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.AppDataEntity
import io.github.mymoney.data.SpecRuleStore
import io.github.mymoney.data.GitHubRuleUpdates
import io.github.mymoney.ui.design.*
import com.example.billkit.spec.PortableRulePackage
import com.example.billkit.spec.PersonalRuleCodec
import com.example.billkit.spec.SpecRuleModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal fun ruleActionError(error: Exception): String = when(error) {
    is SecurityException -> "无法读取文件，请先下载到手机，再重新选择。"
    is java.io.IOException -> "文件读写失败，请重新选择完整文件，并确认手机存储空间足够。"
    is IllegalArgumentException, is IllegalStateException -> error.message ?: "规则内容不完整，请检查后重试。"
    else -> "操作未完成，原规则已保留，请稍后重试。"
}

internal fun ruleTimestamp(value: String?): String = value?.let {
    runCatching { java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(it)) }.getOrDefault(it)
} ?: "未提供"

internal fun friendlyRuleName(rule: SpecRuleModel): String = when {
    "_redpackage_expense_" in rule.systemRuleName -> "发红包"
    "_redpackage_income_" in rule.systemRuleName -> "收红包"
    "_transfer_expense_" in rule.systemRuleName -> "转账付款"
    "_transfer_income_" in rule.systemRuleName -> "转账收款"
    "_refund_" in rule.systemRuleName -> "退款"
    "_expense_" in rule.systemRuleName -> if(rule.type=="NOTICE") "付款通知" else "付款支出"
    "_income_" in rule.systemRuleName -> if(rule.type=="NOTICE") "收款通知" else "收款收入"
    else -> rule.name.removePrefix("麦米·")
}

/** Everyday tasks first. Package internals and legacy JSON are revealed only on request. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortableRulesPanel(showAdvanced: Boolean, onAdvancedChanged: (Boolean)->Unit) {
    val context=LocalContext.current.applicationContext
    val state by SpecRuleStore.status.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPersonal by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    var noteRules by rememberSaveable { mutableStateOf(false) }
    var officialExpanded by rememberSaveable { mutableStateOf(false) }
    var online by remember { mutableStateOf<GitHubRuleUpdates.Release?>(null) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<AppDataEntity>>(emptyList()) }
    var showSamples by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend ()->String) {
        if(busy) return
        busy=true
        scope.launch {
            try { message=block() } catch(t: Exception) { if(t is kotlinx.coroutines.CancellationException) throw t; message=when(t) {is java.net.UnknownHostException -> "无法连接 GitHub，请检查网络，或下载规则文件后导入"; is java.net.SocketTimeoutException -> "连接 GitHub 超时，请稍后重试"; is javax.net.ssl.SSLException -> "GitHub 安全连接失败，请稍后重试"; is java.io.IOException -> t.message ?: "网络或文件读取失败，请重试"; else -> ruleActionError(t)} }
            finally { busy=false }
        }
    }
    LaunchedEffect(editorId) { if(editorId!=null) records=SpecRuleStore.recentRuleSamples(context) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) action {
            val bytes=withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { PortableRulePackage.readBytes(it,PortableRulePackage.MAX_ZIP) } }
            if(bytes.firstOrNull { it.toInt().toChar() !in listOf(' ','\r','\n','\t') }?.toInt()==123) {
                val text=bytes.toString(Charsets.UTF_8)
                val rules=withContext(Dispatchers.IO) { PersonalRuleCodec.decode(text) }
                val old=state.personalRules.map { it.id }.toSet()
                description="新增 ${rules.count { it.id !in old }} 条个人规则，更新 ${rules.count { it.id in old }} 条。\n保留其他个人规则和官方规则，不重新处理历史交易。"
                pendingPersonal=text;pendingZip=null
            } else {
                val pack=SpecRuleStore.preparePackage(context,bytes)
                description=SpecRuleStore.describePackage(pack)
                pendingZip=bytes;pendingPersonal=null
            }
            "检查通过，请核对后导入"
        }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) action {
            val text=SpecRuleStore.exportPersonalRules(context)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) } }
            "个人规则已导出，可将此文件分享或留作备份"
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(AbSpacing.s)) {
        GroupHeader("官方规则")
        InsetGroup {
            ListRow(title=when {state.error!=null -> "规则暂不可用"; !state.ready -> "正在读取规则";else -> "官方规则 ${state.version}"},
                subtitle=state.error ?: "${state.source} · 已开启 ${state.rules}/${state.available} 条\n发布时间：${ruleTimestamp(state.publishedAt)}")
            Column(Modifier.padding(horizontal=AbSpacing.l,vertical=AbSpacing.s),verticalArrangement=Arrangement.spacedBy(AbSpacing.s)) {
                PrimaryButton(if(busy) "正在处理…" else "检查 GitHub 更新",onClick={action {
                    online=withContext(Dispatchers.IO) { GitHubRuleUpdates.latest() }
                    val found=online
                    when { found==null -> "GitHub 暂未发布规则包，可继续使用当前规则"
                        PortableRulePackage.compareVersions(found.version,state.version)<=0 -> {online=null;"当前已是最新规则 ${state.version}"}
                        else -> "发现规则更新 ${found.version}，可下载查看更新内容" }
                }},modifier=Modifier.fillMaxWidth(),enabled=!busy&&state.ready)
                online?.let { release -> SecondaryButton("下载并预览 ${release.version}",onClick={action {
                    val bytes=withContext(Dispatchers.IO) {GitHubRuleUpdates.download(release)}
                    val pack=SpecRuleStore.preparePackage(context,bytes)
                    description="发布时间：${ruleTimestamp(pack.publishedAt)}\n"+SpecRuleStore.describePackage(pack)
                    pendingZip=bytes;pendingPersonal=null;"下载并检查通过，确认导入后生效"
                }},modifier=Modifier.fillMaxWidth(),enabled=!busy) }
                SecondaryButton("导入官方规则包",onClick={picker.launch(arrayOf("application/zip","application/octet-stream","application/x-zip-compressed"))},modifier=Modifier.fillMaxWidth(),enabled=!busy)
            }
            ListRow(title=if(officialExpanded) "收起官方规则" else "查看 ${state.available} 条官方规则",chevron=true,onClick={officialExpanded=!officialExpanded})
            if(officialExpanded) state.entries.forEach { rule ->
                ListRow(title="${ruleAppName(rule.app)} · ${friendlyRuleName(rule)}",subtitle=if(rule.type=="DATA") "支付凭证" else "交易通知",dividerAbove=true,
                    onClick={details="${friendlyRuleName(rule)}\n来源：${state.source} ${state.version}\n发布时间：${ruleTimestamp(state.publishedAt)}\n\n技术标识：${rule.systemRuleName}"},
                    trailing={AbSwitch(rule.enabled) { value -> action {SpecRuleStore.setRuleEnabled(context,rule.systemRuleName,value);"规则已${if(value) "开启" else "关闭"}"} }})
            }
        }
        message?.let { InsetGroup { ListRow(title=it) } }
        GroupFooter("只在点击时连接 GitHub。下载后先校验并预览，确认导入才生效；联网失败仍使用现有规则。")
        GroupHeader("自定义规则")
        InsetGroup {
            ListRow(title="我的规则 · ${state.personalRules.size} 条",subtitle="补充官方尚未支持的识别，更新官方包会保留")
            Column(Modifier.padding(horizontal=AbSpacing.l,vertical=AbSpacing.s)) {
                SecondaryButton("添加自定义规则",onClick={editorId="personal_"+UUID.randomUUID().toString().replace("-", "")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
                TextButton(onClick={picker.launch(arrayOf("application/json","application/octet-stream"))},enabled=!busy) {Text("导入自定义规则")}
            }
            state.personalRules.forEach { rule ->
                ListRow(title=rule.name,subtitle="${ruleAppName(rule.app)} · ${personalTypeName(rule.billType)}\n最后修改：${rule.updatedAt?.let { ruleTimestamp(java.time.Instant.ofEpochMilli(it).toString()) } ?: "未记录"}",dividerAbove=true,
                    onClick={if(!busy) editorId=rule.id},trailing={AbSwitch(rule.enabled) { value -> action {SpecRuleStore.savePersonalRule(context,rule.copy(enabled=value));"自定义规则已${if(value) "开启" else "关闭"}"} }})
            }
        }
        GroupHeader("备注填充")
        InsetGroup { ListRow(title="备注填充设置",subtitle="商品说明优先、按商家设置",onClick={noteRules=true},chevron=true) }
        InsetGroup {
            ListRow(title="更多选项",subtitle="导出个人规则、检查记录与恢复",chevron=true,onClick={onAdvancedChanged(!showAdvanced)})
            if(showAdvanced) {
                ListRow(title="导出我的规则",subtitle="分享或备份${state.personalRules.size}条个人规则",dividerAbove=true,onClick={if(!busy) export.launch("MyMoney-personal-rules.json")})
                ListRow(title="用已有记录检查",subtitle="只看识别结果，不生成账单",dividerAbove=true,onClick={ action {records=SpecRuleStore.recentRuleSamples(context);showSamples=true;"请选择记录"} })
                ListRow(title="恢复上一版官方规则",subtitle=if(state.canRollback) "保留个人规则和账本" else "暂无可恢复版本",dividerAbove=true,onClick={if(!busy&&state.canRollback) confirm="恢复上一版官方规则"})
                ListRow(title="恢复应用内置规则",subtitle="只切换官方规则，保留我的规则",dividerAbove=true,onClick={if(!busy) confirm="恢复应用内置规则"})
            }
        }
    }
    if(pendingZip!=null || pendingPersonal!=null) AlertDialog(onDismissRequest={if(!busy){pendingZip=null;pendingPersonal=null}},title={Text("导入这些规则？")},
        text={Text(description,modifier=Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(enabled=!busy,onClick={action {
            val zip=pendingZip;val text=pendingPersonal
            if(zip!=null) SpecRuleStore.importPackage(context,zip) else SpecRuleStore.importPersonalRules(context,requireNotNull(text))
            pendingZip=null;pendingPersonal=null;"规则已导入，接下来的交易会使用新规则"
        }}){Text("确认导入")}},dismissButton={TextButton(enabled=!busy,onClick={pendingZip=null;pendingPersonal=null}){Text("取消")}})
    confirm?.let { title -> AlertDialog(onDismissRequest={confirm=null},title={Text("$title？")},text={Text("个人规则、已有账单和用户设置都会保留，不重新处理历史交易。")},
        confirmButton={TextButton(enabled=!busy,onClick={action {
            if(title=="恢复上一版官方规则") SpecRuleStore.rollbackPackage(context) else SpecRuleStore.restoreBuiltin(context)
            confirm=null;"$title 已完成"
        }}){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}}) }
    details?.let { value -> AlertDialog(onDismissRequest={details=null},title={Text("规则说明")},text={Text(value,modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={details=null}){Text("知道了")}}) }
    if(noteRules) NoteFillRulesPage(onBack={noteRules=false})
    editorId?.let { id -> PersonalRuleEditor(id,state.personalRules.firstOrNull { it.id==id },records,onClose={editorId=null},onSaved={message=it;editorId=null}) }
    if(showSamples) AlertDialog(onDismissRequest={showSamples=false},title={Text("选择记录")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        if(records.isEmpty()) Text("暂无保存的记录")
        records.forEach { row -> TextButton(enabled=!busy,onClick={ action { val result=SpecRuleStore.preview(context,row);showSamples=false;result } }) {
            Text("${ruleAppName(row.app)} · ${if(row.match) "已识别" else "未识别"}记录 #${row.id}")
        } }
    }},confirmButton={TextButton(onClick={showSamples=false}){Text("关闭")}})
}
