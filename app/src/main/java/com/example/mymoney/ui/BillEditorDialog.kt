package com.example.mymoney.ui

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
import com.example.mymoney.ui.design.LocalReduceMotion
import com.example.mymoney.ui.design.LocalAbPalette
import com.example.mymoney.ui.design.AbType
import com.example.mymoney.ui.design.amountTint
import com.example.mymoney.ui.design.formatAmount
import com.example.mymoney.ui.design.rememberAbHaptic
import androidx.compose.ui.unit.lerp
import com.example.mymoney.data.BillEntity
import com.example.mymoney.data.BillWriter
import com.example.billkit.BillDraft
import com.example.billkit.BillType

/** Same-window editor preserves the amount trajectory; the shell hides its sibling dock while open. */
@Composable
fun BillEditorDialog(original: BillEntity?, newSource: String, newTime: Long, sourceAmountBounds: Rect? = null, onClose: () -> Unit) {
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
    LaunchedEffect(operation.completed) {
        if (operation.completed) {
            onClose()
        }
    }
    DisposableEffect(operationKey, haptic) {
        val feedbackHaptic: () -> Unit = { haptic.confirm() }
        operation.onStored = feedbackHaptic
        onDispose {
            if (operation.onStored === feedbackHaptic) operation.onStored = null
            if (!operation.saving) EditorSaveOperations.remove(operationKey)
        }
    }
    val reduceMotion = LocalReduceMotion.current
    val p = LocalAbPalette.current
    var target by remember { mutableStateOf<Rect?>(null) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(if (reduceMotion || sourceAmountBounds == null) 1f else 0f) }
    LaunchedEffect(target != null, reduceMotion) {
        if (reduceMotion) progress.snapTo(1f)
        else if (target != null) progress.animateTo(1f, tween(380))
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.boundsInRoot().topLeft }) {
        ConfirmSheet(
            draft = draft,
            amountVisibility = progress.value >= 1f,
            onAmountBounds = { target = it },
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
            onIgnore = { if (!operation.saving) onClose() },
        )
        val destination = target
        if (sourceAmountBounds != null && destination != null && progress.value < 1f) {
            val fraction = progress.value
            val x = sourceAmountBounds.left + (destination.left - sourceAmountBounds.left) * fraction - hostOrigin.x
            val y = sourceAmountBounds.top + (destination.top - sourceAmountBounds.top) * fraction - hostOrigin.y
            Text(formatAmount(draft.priceCents),
                style = AbType.amountRow.copy(fontSize = lerp(AbType.amountRow.fontSize, AbType.amountEntry.fontSize, fraction)),
                color = amountTint(p, draft.type.name),
                maxLines = 1,
                modifier = Modifier.graphicsLayer { translationX = x; translationY = y }
                    .clearAndSetSemantics {},
            )
        }
    }
}

/** Retain an in-flight write across Activity recreation without persisting a stuck busy flag after process death. */
private class EditorSaveOperation {
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var completed by mutableStateOf(false)
    var onStored: (() -> Unit)? = null
}
private val EditorSaveOperations = mutableMapOf<String, EditorSaveOperation>()
