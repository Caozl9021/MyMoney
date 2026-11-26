package io.github.mymoney.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mymoney.data.AssetAccount
import io.github.mymoney.data.AssetStore
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.data.Reimburse
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.AbRadius
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbSpring
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AppChoiceDialog
import io.github.mymoney.ui.design.AppDialogChoice
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalGlassContextMenu
import io.github.mymoney.ui.design.LocalLiquidGlassClaritySpec
import io.github.mymoney.ui.design.LocalLiquidGlassEnabled
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.LocalReduceTransparency
import io.github.mymoney.ui.design.glassSurfaceAlpha
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal data class ReimburseNotice(
    val text: String,
    val undoBillId: Long? = null,
    val token: Long = System.nanoTime(),
)

internal object ReimburseFeedback {
    var notice by mutableStateOf<ReimburseNotice?>(null)
        private set

    fun show(text: String, undoBillId: Long? = null) {
        notice = ReimburseNotice(text, undoBillId)
    }

    fun clear() {
        notice = null
    }
}

private const val RETURN_PREF = "reimburse_return_account"

internal fun returnCandidates(accounts: List<AssetAccount>): List<AssetAccount> =
    accounts.filter { it.initialized && !it.archived }

internal fun paymentReady(bill: BillEntity, accounts: List<AssetAccount>): Boolean {
    val payment = accounts.firstOrNull { it.id == bill.accountId }
    return bill.assetLinked && payment?.initialized == true
}

internal fun rememberReturnAccount(context: Context, accountId: String) {
    context.getSharedPreferences("mymoney", Context.MODE_PRIVATE).edit().putString(RETURN_PREF, accountId).apply()
}

internal fun preferredReturnId(context: Context, bill: BillEntity, accounts: List<AssetAccount>): String? {
    val candidates = returnCandidates(accounts)
    val saved = context.getSharedPreferences("mymoney", Context.MODE_PRIVATE).getString(RETURN_PREF, null)
    return candidates.firstOrNull { it.id == saved }?.id ?: candidates.firstOrNull()?.id
        ?: candidates.firstOrNull { it.id == bill.accountId }?.id
}

/** 已有有效退回账户就直接核销；否则交给界面选。支付账户没设余额时只改状态。 */
internal fun markReimbursed(context: Context, bill: BillEntity, accounts: List<AssetAccount>, onNeedPicker: () -> Unit) {
    if (!paymentReady(bill, accounts) || returnCandidates(accounts).isEmpty()) {
        requestReimbursement(context, bill, Reimburse.REIMBURSED)
        return
    }
    val kept = returnCandidates(accounts).firstOrNull { it.id == bill.reimburseAccountId }
    if (kept != null) requestReimbursement(context, bill, Reimburse.REIMBURSED, kept.id) else onNeedPicker()
}

internal fun requestReimbursement(context: Context, bill: BillEntity, status: String, reimburseAccountId: String? = null) {
    BillWriter.updateReimbursement(context, bill.id, status, reimburseAccountId) { result ->
        result.onSuccess { text ->
            ReimburseFeedback.show(text, bill.id.takeIf { status == Reimburse.REIMBURSED })
        }
        result.onFailure { failure ->
            ReimburseFeedback.show(failure.message ?: "报销操作失败", null)
        }
    }
}

@Composable
internal fun ReturnAccountDialog(bill: BillEntity, accounts: List<AssetAccount>, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = returnCandidates(accounts)
    AppChoiceDialog(
        title = "退回账户",
        options = options.map { AppDialogChoice(it.id, it.name, if (it.id == bill.accountId) "原支付账户" else null) },
        selectedKey = preferredReturnId(context, bill, accounts),
        onChoose = { id ->
            rememberReturnAccount(context, id)
            requestReimbursement(context, bill, Reimburse.REIMBURSED, id)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ReimburseChoiceDialog(
    bill: BillEntity,
    anchor: Rect? = null,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accounts by AssetStore.accounts.collectAsStateWithLifecycle()
    val contextMenu = LocalGlassContextMenu.current
    var picking by remember(bill.id) { mutableStateOf(false) }

    val options = when (bill.reimburseStatus) {
        Reimburse.PENDING -> listOf(
            AppDialogChoice(Reimburse.REIMBURSED, "已报销"),
            AppDialogChoice(Reimburse.NONE, "取消可报销", destructive = true),
        )
        Reimburse.REIMBURSED -> buildList {
            if (paymentReady(bill, accounts) && returnCandidates(accounts).isNotEmpty()) add(AppDialogChoice("change", "更改退回账户"))
            add(AppDialogChoice(Reimburse.PENDING, "撤销报销"))
        }
        else -> listOf(AppDialogChoice(Reimburse.PENDING, "标为可报销"))
    }

    fun handleChoice(status: String) {
        when {
            status == "change" || status == Reimburse.REIMBURSED && paymentReady(bill, accounts) &&
                returnCandidates(accounts).none { it.id == bill.reimburseAccountId } -> picking = true
            status == Reimburse.REIMBURSED -> {
                markReimbursed(context, bill, accounts) { picking = true }
                if (!picking) onDismiss()
            }
            else -> {
                requestReimbursement(context, bill, status)
                onDismiss()
            }
        }
    }

    if (anchor != null && contextMenu != null) {
        val currentDismiss by rememberUpdatedState(onDismiss)
        val currentAccounts by rememberUpdatedState(accounts)
        LaunchedEffect(bill.id, anchor, picking) {
            if (!picking) {
                contextMenu.show("报销", options, null, anchor) { handleChoice(it) }
                snapshotFlow { contextMenu.open }.first { !it }
                if (!picking) currentDismiss()
            } else {
                val returnOpts = returnCandidates(currentAccounts).map {
                    AppDialogChoice(it.id, it.name, if (it.id == bill.accountId) "原支付账户" else null)
                }
                if (returnOpts.isEmpty()) {
                    currentDismiss()
                } else {
                    contextMenu.show(
                        "退回账户",
                        returnOpts,
                        preferredReturnId(context, bill, currentAccounts),
                        anchor,
                    ) { id ->
                        rememberReturnAccount(context, id)
                        requestReimbursement(context, bill, Reimburse.REIMBURSED, id)
                        currentDismiss()
                    }
                    snapshotFlow { contextMenu.open }.first { !it }
                    currentDismiss()
                }
            }
        }
        return
    }

    if (picking) {
        ReturnAccountDialog(bill, accounts, onDismiss)
        return
    }
    AppChoiceDialog(
        title = "报销",
        options = options,
        selectedKey = null,
        onChoose = ::handleChoice,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ReimburseNoticeBar(modifier: Modifier = Modifier) {
    val notice = ReimburseFeedback.notice
    var lastNotice by remember { mutableStateOf<ReimburseNotice?>(null) }
    if (notice != null) lastNotice = notice

    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalAbPalette.current
    val isDark = palette.pageBg.luminance() < 0.1f
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val clarity = LocalLiquidGlassClaritySpec.current.dialog

    val scope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }

    LaunchedEffect(notice) {
        if (notice != null) {
            dragOffsetY.snapTo(0f)
            delay(if (notice.undoBillId != null) 5_500L else 3_200L)
            if (ReimburseFeedback.notice == notice) ReimburseFeedback.clear()
        }
    }

    val enterSpec = if (reduceMotion) {
        fadeIn(tween(150, easing = AbEasing.EaseOut))
    } else {
        fadeIn(tween(170, easing = AbEasing.EaseOut)) +
            slideInVertically(spring(dampingRatio = 0.96f, stiffness = 460f)) { it / 2 } +
            scaleIn(spring(dampingRatio = 0.96f, stiffness = 460f), initialScale = 0.92f)
    }
    val exitSpec = if (reduceMotion) {
        fadeOut(tween(120, easing = AbEasing.EaseOut))
    } else {
        fadeOut(tween(120, easing = AbEasing.EaseOut)) +
            slideOutVertically(tween(140, easing = AbEasing.EaseOut)) { it / 2 } +
            scaleOut(tween(120, easing = AbEasing.EaseOut), targetScale = 0.92f)
    }

    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = enterSpec,
        exit = exitSpec,
    ) {
        val active = notice ?: lastNotice ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(AbRadius.capsule)
        val panelColor = if (effectiveGlass) {
            (if (isDark) palette.elevatedBg else palette.cardBg).copy(
                alpha = glassSurfaceAlpha(0.86f, 0.82f, isDark, clarity),
            )
        } else {
            palette.elevatedBg
        }
        val borderModifier = if (effectiveGlass) {
            Modifier.border(0.75.dp, io.github.mymoney.ui.design.liquidSpecularRimBrush(isDark), shape)
        } else {
            Modifier.border(0.75.dp, palette.separator, shape)
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .pointerInput(active, reduceMotion) {
                    val tracker = VelocityTracker()
                    val dismissPx = with(density) { 24.dp.toPx() }
                    detectVerticalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            scope.launch { dragOffsetY.stop() }
                        },
                        onDragCancel = {
                            scope.launch {
                                if (reduceMotion) dragOffsetY.snapTo(0f) else dragOffsetY.animateTo(0f, AbSpring.sheetFloat(false))
                            }
                        },
                        onDragEnd = {
                            val y = dragOffsetY.value
                            val velocity = tracker.calculateVelocity().y
                            if (y > dismissPx || velocity > 450f) {
                                ReimburseFeedback.clear()
                            } else {
                                scope.launch {
                                    if (reduceMotion) dragOffsetY.snapTo(0f) else dragOffsetY.animateTo(0f, AbSpring.sheetFloat(false))
                                }
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val next = dragOffsetY.value + dragAmount
                            val damped = if (next < 0f) next * 0.2f else next
                            tracker.addPosition(change.uptimeMillis, Offset(0f, damped))
                            scope.launch { dragOffsetY.snapTo(damped.coerceAtLeast(-24f)) }
                        },
                    )
                }
                .graphicsLayer { translationY = dragOffsetY.value }
                .shadow(
                    elevation = if (effectiveGlass) 18.dp else 8.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = palette.glassShadow,
                    spotColor = palette.glassShadow,
                )
                .clip(shape)
                .background(panelColor)
                .then(
                    if (!effectiveGlass) Modifier
                    else Modifier.drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    palette.glassHighlight.copy(alpha = if (isDark) 0.14f else 0.24f),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = size.height * 0.45f,
                            ),
                        )
                    },
                )
                .then(borderModifier),
        ) {
            AnimatedContent(
                targetState = active.text to active.undoBillId,
                transitionSpec = {
                    fadeIn(tween(130, easing = AbEasing.EaseOut)) togetherWith
                        fadeOut(tween(90, easing = AbEasing.EaseOut))
                },
                label = "reimburse-notice-swap",
            ) { (text, undoId) ->
                Row(
                    modifier = Modifier.padding(
                        start = AbSpacing.l,
                        end = if (undoId != null) AbSpacing.s else AbSpacing.l,
                        top = 4.dp,
                        bottom = 4.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                ) {
                    Text(
                        text,
                        style = AbType.footnote,
                        color = palette.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp),
                    )
                    undoId?.let { id ->
                        AbTextButton("撤销", onClick = {
                            BillWriter.updateReimbursement(context, id, Reimburse.PENDING) { result ->
                                result.onSuccess { ReimburseFeedback.show(it, null) }
                                result.onFailure { ReimburseFeedback.show(it.message ?: "撤销失败", null) }
                            }
                        })
                    }
                }
            }
        }
    }
}
