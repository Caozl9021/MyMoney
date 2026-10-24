package com.example.autobook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PrimaryButton
import com.example.autobook.ui.design.SecondaryButton
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 仅呈现宿主确认的保存结果；关闭不再把已保存账单放回待确认。 */
@Composable
fun ConfirmResultSheet(
    draft: BillDraft,
    title: String,
    detail: String?,
    canView: Boolean,
    sharing: Boolean = false,
    shareMessage: String? = null,
    onDismiss: () -> Unit,
    onView: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.clearFocus(force = true); keyboard?.hide() }
    if (androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(onBack = onDismiss)
    }
    val type = when (draft.type) {
        BillType.EXPENSE -> "支出"
        BillType.INCOME -> "收入"
        BillType.TRANSFER -> "转账"
        BillType.REFUND -> "退款"
    }
    val date = remember(draft.timeMillis) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(draft.timeMillis))
    }
    // 金额来自真实结果，用整数分格式化，避免浮点或舍入改变原金额。
    val amount = remember(draft.priceCents) {
        val cents = draft.priceCents
        "%d.%02d".format(Locale.US, cents / 100, cents % 100)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .semantics { contentDescription = "完成，关闭记账结果" },
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val amountSize = minOf(44f, (minOf(maxWidth.value, 560f) - 48f) / ((amount.length + 2) * 0.64f))
                .coerceAtLeast(20f) / density.fontScale.coerceAtLeast(1f)
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.92f)
                    .clip(RoundedCornerShape(topStart = AbRadius.sheet, topEnd = AbRadius.sheet))
                    .background(p.cardBg)
                    // Consume empty card taps so only the surrounding scrim dismisses.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .testTag("confirm-result-sheet")
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AbSpacing.xl, vertical = AbSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(AbRadius.capsule)).background(p.green.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AbIcons.Check, contentDescription = null, tint = p.green, modifier = Modifier.size(18.dp)) }
                    Text(title, style = AbType.headline, color = p.label,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                }
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = AbType.footnote, color = p.secondaryLabel)
                }
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    draft.merchant?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = AbType.body.copy(fontWeight = FontWeight.Medium), color = p.label)
                    }
                    Text("¥ $amount", style = AbType.amountEntry.copy(fontSize = amountSize.sp, lineHeight = (amountSize * 1.2f).sp), color = p.label, maxLines = 1,
                        modifier = Modifier.semantics { contentDescription = "$type，金额$amount 元" })
                    Text("$type · $date", style = AbType.footnote, color = p.secondaryLabel)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.separator))
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    ResultSummaryLine("分类", listOfNotNull(draft.category, draft.subCategory).joinToString(" · ").ifBlank { "未分类" })
                    ResultSummaryLine("账户", draft.account ?: "未指定")
                }
                PrimaryButton(text = "完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                // Stack optional actions to remain legible at large font sizes.
                if (canView) SecondaryButton(text = "查看账单", onClick = onView, modifier = Modifier.fillMaxWidth())
                if (onShare != null) {
                    SecondaryButton(
                        text = "分享至 iCost", onClick = onShare, enabled = !sharing,
                        busy = sharing, busyLabel = "正在启动分享…", modifier = Modifier.fillMaxWidth(),
                    )
                    Text("分享后请在 iCost 中确认导入", style = AbType.caption1, color = p.secondaryLabel)
                }
                shareMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = AbType.footnote, color = p.secondaryLabel,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

@Composable
private fun ResultSummaryLine(label: String, value: String) {
    val p = LocalAbPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
        Text(label, style = AbType.footnote, color = p.secondaryLabel)
        Text(value, style = AbType.footnote.copy(fontWeight = FontWeight.Medium), color = p.label, modifier = Modifier.weight(1f))
    }
}
