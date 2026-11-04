package io.github.mymoney.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.amountTint
import io.github.mymoney.ui.design.formatAmount
import io.github.mymoney.ui.design.rememberAbHaptic
import androidx.compose.ui.unit.lerp
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import com.example.billkit.BillDraft
import com.example.billkit.BillType

/** Same-window editor preserves the amount trajectory; the shell hides its sibling dock while open. */
@Composable
fun BillEditorDialog(original: BillEntity?, newSource: String, newTime: Long, sourceAmountBounds: Rect? = null, sourceAmountFontSize: androidx.compose.ui.unit.TextUnit? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val haptic = rememberAbHaptic()
    val draft = remember(original?.id, newSource) {
        original?.let(BillWriter::toDraft) ?: BillDraft(
            priceCents = 0, type = BillType.EXPENSE, timeMillis = newTime,
            merchant = null, method = null, orderTail = null,
            sourcePkg = newSource, strategy = "手动记账",
        )
    }
    val operationKey = original?.let { "edit:${it.id}" } ?: newSource
    val operation = remember(operationKey) { EditorSaveOperations.getOrPut(operationKey) { EditorSaveOperation() } }
    val saving = operation.saving
    val error = operation.error
    DisposableEffect(operationKey, haptic) {
        val feedbackHaptic: () -> Unit = { haptic.confirm() }
        operation.onStored = feedbackHaptic
        onDispose {
            if (operation.onStored === feedbackHaptic) operation.onStored = null
            if (!operation.saving && !operation.completed && operation.exitRequested == null) EditorSaveOperations.remove(operationKey)
        }
    }
    val reduceMotion = LocalReduceMotion.current
    // The sheet stays in its final geometry while a single overlay moves from the source.
    // This avoids measuring a target which is itself travelling in a second animation.
    val source = remember(original?.id) { sourceAmountBounds?.takeIf { it.width > 0 && it.height > 0 && it.top >= 0 } }
    val shared = original != null && source != null && !reduceMotion
    var exiting by remember(original?.id) { mutableStateOf(false) }
    var reverseDraft by remember(original?.id) { mutableStateOf<BillDraft?>(null) }
    var currentTarget by remember(original?.id) { mutableStateOf<Rect?>(null) }
    var reverseSource by remember(original?.id) { mutableStateOf<Rect?>(null) }
    var reverseTarget by remember(original?.id) { mutableStateOf<Rect?>(null) }
    var reverseSourceFont by remember(original?.id) { mutableStateOf<androidx.compose.ui.unit.TextUnit?>(null) }
    var target by remember(original?.id) { mutableStateOf<Rect?>(null) }
    var targetFont by remember(original?.id) { mutableStateOf<androidx.compose.ui.unit.TextUnit?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sourceFont = remember(original?.id) { sourceAmountFontSize ?: AbType.amountEntry.fontSize / density.fontScale.coerceAtLeast(1f) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    var interrupted by remember(original?.id) { mutableStateOf(false) }
    val progress = remember(original?.id) { Animatable(if (shared) 0f else 1f) }
    LaunchedEffect(target, targetFont, reduceMotion, interrupted, exiting) {
        if (exiting) return@LaunchedEffect
        if (!shared || interrupted) progress.snapTo(1f)
        else if (target != null && targetFont != null) progress.animateTo(1f, tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }

    val transitionScope = rememberCoroutineScope()
    val latestClose by rememberUpdatedState(onClose)
    val latestOriginal by rememberUpdatedState(original)
    val latestSource by rememberUpdatedState(sourceAmountBounds)
    val latestSourceFont by rememberUpdatedState(sourceAmountFontSize)
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun leave(saved: Boolean) {
        if (exiting) return
        exiting = true
        operation.exitRequested = saved
        reverseDraft = if (saved) operation.savedDraft ?: draft else draft
        reverseTarget = currentTarget ?: target
        transitionScope.launch {
            // Let the committed Room row update the hidden summary before measuring the return point.
            if (saved && original != null) {
                val stored = operation.savedDraft
                kotlinx.coroutines.withTimeoutOrNull(500) {
                    androidx.compose.runtime.snapshotFlow { latestOriginal?.let { row -> stored == null || (row.priceCents == stored.priceCents && row.type == stored.type.name) } == true }
                        .first { it }
                }
                androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
            }
            reverseSource = latestSource?.takeIf { it.top >= 0 && it.height > 0 } ?: source
            reverseSourceFont = latestSourceFont ?: sourceFont
            progress.snapTo(1f)
            progress.animateTo(0f, tween(if (reduceMotion) 0 else 280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            focus.clearFocus(force = true)
            keyboard?.hide()
            EditorSaveOperations.remove(operationKey)
            latestClose()
        }
    }
    LaunchedEffect(operation.completed) { if (operation.completed || operation.exitRequested != null) leave(saved = operation.completed) }
    val displayedProgress = if (!exiting && (interrupted || reduceMotion)) 1f else progress.value
    val trajectorySource = if (exiting) reverseSource else source
    val trajectoryTarget = if (exiting) reverseTarget else target
    val hasMovingAmount = !reduceMotion && trajectorySource != null && (shared || exiting)
    Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.boundsInRoot().topLeft }) {
        ConfirmSheet(
            draft = draft,
            amountVisibility = displayedProgress >= 1f || !hasMovingAmount,
            onAmountBounds = { next ->
                currentTarget = next
                val previous = target
                if (previous == null) target = next
                else if (!exiting && progress.value < 1f && previous != next) interrupted = true
            },
            onAmountFontSize = { size ->
                if (!exiting && targetFont != null && targetFont != size && progress.value < 1f) interrupted = true
                targetFont = size
            },
            sharedAmountProgress = if (original != null || exiting) displayedProgress else null,
            managedEditorExit = true,
            editorExiting = exiting,
            editableDetails = true,
            saveLabel = if (original == null) "记一笔" else "保存修改",
            ignoreLabel = "取消",
            noteSuggestions = rememberNoteSuggestions(excludeId = original?.id),
            showImport = false,
            saving = saving,
            errorMessage = error,
            assetHint = if (original != null && !original.assetLinked) "这笔旧账尚未计入余额，修改不会自动补扣；如需纳入，请在账单操作页选择“计入账户余额”。" else null,
            onSave = { edited ->
                // 同步立刻置位；快速双击无法排入两个数据库写操作。
                if (!operation.saving && !operation.completed) {
                    operation.saving = true
                    operation.error = null
                    BillWriter.saveFromEditor(app, edited, original) { result ->
                        operation.saving = false
                        result.fold(
                            onSuccess = {
                                operation.savedDraft = edited
                                operation.onStored?.invoke()
                                operation.completed = true
                            },
                            onFailure = {
                                operation.error = it.message ?: "保存失败，请重试"
                                Toast.makeText(app, operation.error, Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                }
            },
            onImport = {},
            onIgnore = { if (!operation.saving) leave(saved = false) },
        )
        if (hasMovingAmount && trajectorySource != null && displayedProgress < 1f) {
            val destination = trajectoryTarget ?: trajectorySource
            val fraction = displayedProgress
            val x = trajectorySource.left + (destination.left - trajectorySource.left) * fraction - hostOrigin.x
            val y = trajectorySource.top + (destination.top - trajectorySource.top) * fraction - hostOrigin.y
            val shownDraft = reverseDraft ?: draft
            BillAmountDisplay(shownDraft.priceCents, shownDraft.type.name,
                modifier = Modifier.graphicsLayer { translationX = x; translationY = y }.clearAndSetSemantics {},
                amountFontSize = lerp(if (exiting) reverseSourceFont ?: sourceFont else sourceFont, targetFont ?: sourceFont, fraction))
        }
    }
}

/** Retain an in-flight write across Activity recreation without persisting a stuck busy flag after process death. */
private class EditorSaveOperation {
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var completed by mutableStateOf(false)
    var savedDraft: BillDraft? = null
    var exitRequested: Boolean? = null
    var onStored: (() -> Unit)? = null
}
private val EditorSaveOperations = mutableMapOf<String, EditorSaveOperation>()
