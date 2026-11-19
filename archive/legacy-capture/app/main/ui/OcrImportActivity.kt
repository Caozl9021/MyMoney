package io.github.mymoney.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.billkit.BillDraft
import com.example.billkit.visual.VisualBillParser
import io.github.mymoney.data.*
import io.github.mymoney.ocr.OcrWorkQueue
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.*
import java.security.MessageDigest

/** User-selected images only. No accessibility, gallery scan or automatic ledger write. */
class OcrImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val operation = ViewModelProvider(this)[OcrImportOperation::class.java]
        setContent {
            MyMoneyTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) operation.read(applicationContext, uri)
                }
                val draft = operation.draft
                if (draft != null) {
                    ConfirmSheet(draft = draft, editableDetails = true, showImport = false,
                        reviewNotice = operation.notice,
                        saving = operation.saving, errorMessage = operation.error,
                        onSave = { edited -> operation.save(applicationContext, edited) { finish() } },
                        onImport = {}, onIgnore = { finish() }, onDismiss = { finish() },
                        ignoreLabel = "取消")
                } else {
                    SettingsContentPage("从截图补记", onBack = { finish() }) {
                        InsetGroup {
                            ListRow(title = "选择一张交易截图", subtitle = if (!io.github.mymoney.ocr.LocalOcrEngine.isSupported)
                                "当前OCR需要Android 11及以上和64位进程" else "仅在本机识别，核对后保存；原图不会保存到备份。")
                        }
                        operation.notice?.let { GroupFooter(it) }
                        operation.error?.let { GroupFooter(it) }
                        PrimaryButton(if (operation.busy) "正在识别…" else "选择截图",
                            onClick = { picker.launch(arrayOf("image/*")) }, enabled = !operation.busy && io.github.mymoney.ocr.LocalOcrEngine.isSupported,
                            modifier = Modifier.fillMaxWidth())
                        if (operation.busy) TextButton(onClick = { operation.cancel() }) { Text("停止识别") }
                        GroupFooter("识别只提供草稿，请核对金额、交易类型与时间。截图未显示的信息不会猜测。")
                    }
                }
            }
        }
    }
}

class OcrImportOperation : ViewModel() {
    var draft by mutableStateOf<BillDraft?>(null); private set
    var busy by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private var work: Job? = null
    private var epoch = 0
    fun cancel() { epoch++; work?.cancel(); busy = false; notice = "识别已停止" }
    fun read(context: android.content.Context, uri: Uri) {
        if (busy || saving) return
        if (!io.github.mymoney.ocr.LocalOcrEngine.isSupported) {
            error = "当前OCR需要Android 11及以上和64位进程，原有记账功能可继续使用"; return
        }
        val generation = ++epoch; error = null; notice = null; busy = true
        work = viewModelScope.launch {
            try {
                val bytes = OcrWorkQueue.readImage(context, uri)
                val visual = withTimeout(15_000) { OcrWorkQueue.recognize(context, bytes) { epoch == generation } }
                ensureActive(); if (epoch != generation) return@launch
                run {
                    val parsed = VisualBillParser.parse(visual, System.currentTimeMillis())
                    val recognized = parsed.draft ?: error(parsed.issue ?: "未识别到明确的单笔成功交易")
                    val suggestion = CategorySuggester.suggest(recognized, candidates = ExternalRuleStore.candidates())
                    // Image digest is only a user-visible import duplicate hint, never a transaction alias.
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    val source = "io.github.mymoney.manual.ocr.$digest"
                    val existing = withContext(Dispatchers.IO) { AppDb.get(context).bills().allOnce() }
                    val duplicateNotice = when {
                        existing.any { it.sourcePkg == source } -> "相同截图已有记账记录；如不是另一笔交易，请取消，避免重复记账"
                        existing.any { it.priceCents == recognized.priceCents && !recognized.merchant.isNullOrBlank() &&
                            it.merchant == recognized.merchant && (!recognized.hasExplicitTime ||
                            kotlin.math.abs(it.timeMillis - recognized.timeMillis) <= 60_000) } -> "已有同金额、同商户记录，请核对是否重复记账"
                        else -> null
                    }
                    ensureActive(); if (epoch != generation) return@launch
                    // Independent import observation, never use today's import time for PAGE 60s matching.
                    draft = recognized.copy(sourcePkg = source,
                        category = suggestion.category, subCategory = suggestion.subCategory, account = suggestion.account,
                        transactionKey = null, orderTail = null)
                    notice = (listOfNotNull("截图识别结果，请核对后保存", duplicateNotice) + parsed.warnings).distinct().joinToString("；")
                }
            } catch (_: CancellationException) {
                if (epoch == generation) notice = "识别已停止或超时，请重试"
            } catch (failure: Exception) { if (epoch == generation) error = failure.message ?: "图片识别失败，请重试" }
            finally { if (epoch == generation) busy = false }
        }
    }
    fun save(context: android.content.Context, edited: BillDraft, done: () -> Unit) {
        if (saving) return
        saving = true; error = null
        BillWriter.saveWithResult(context, edited, onDone = { saving = false; done() },
            onError = { saving = false; error = it.message ?: "保存失败" })
    }
}
