package io.github.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.AssetMath
import io.github.mymoney.data.AssetStore
import io.github.mymoney.data.MiniJson
import io.github.mymoney.ui.design.AbCardDensity
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbSwitch
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AccountAvatar
import io.github.mymoney.ui.design.AccountLogos
import io.github.mymoney.ui.design.AccountPresets
import io.github.mymoney.ui.design.CategoryIcons
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.IconColorStyleDialog
import io.github.mymoney.ui.design.InlineMessage
import io.github.mymoney.ui.design.InputRow
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalLiquidGlassEnabled
import io.github.mymoney.ui.design.LocalReduceTransparency
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.SectionLazyColumn
import io.github.mymoney.ui.design.SectionRole
import io.github.mymoney.ui.design.SubPageScaffold
import io.github.mymoney.ui.design.badgeLiquidGlaze
import io.github.mymoney.ui.design.solidBadgeBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class WizardDraftState {
    var kind by mutableStateOf("")
    var name by mutableStateOf("")
    var aliases by mutableStateOf("")
    var cardTail by mutableStateOf("")
    var included by mutableStateOf(true)
    var quickSelect by mutableStateOf(true)
    var amount by mutableStateOf("")
    var creditLimit by mutableStateOf("")
    var billingDayText by mutableStateOf("")
    var repaymentDayText by mutableStateOf("")
    var iconKey by mutableStateOf<String?>(null)
    var colorHex by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    fun reset() {
        kind = ""
        name = ""
        aliases = ""
        cardTail = ""
        included = true
        quickSelect = true
        amount = ""
        creditLimit = ""
        billingDayText = ""
        repaymentDayText = ""
        iconKey = null
        colorHex = null
        error = null
        busy = false
    }

    fun applyPreset(
        presetName: String,
        presetAliases: String,
        presetKind: String,
        presetIconKey: String? = null,
        presetColorHex: String? = null,
        presetQuickSelect: Boolean = true,
    ) {
        kind = presetKind
        name = presetName
        aliases = presetAliases
        cardTail = ""
        amount = ""
        creditLimit = ""
        billingDayText = ""
        repaymentDayText = ""
        included = true
        quickSelect = presetQuickSelect
        iconKey = presetIconKey
        colorHex = presetColorHex
        error = null
    }
}

/**
 * 添加账户向导步骤视图（由宿主 [AssetsScreen] 统一承载路由与预测性返回）。
 */
@Composable
internal fun AssetAccountWizardStep(
    shownStep: String,
    draft: WizardDraftState,
    onStepTo: (String) -> Unit,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val accounts by AssetStore.accounts.collectAsStateWithLifecycle()
    val existingNames = remember(accounts) { accounts.filter { !it.archived }.map { it.name }.toSet() }
    var stylePickerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val debt = AssetMath.isDebt(draft.kind)
    val labelWidth = 76.dp

    val title = when (shownStep) {
        "kind" -> "选择账户类型"
        "specific" -> AccountPresets.kinds.firstOrNull { it.kind == draft.kind }?.label ?: "选择账户"
        else -> "账户设置"
    }

    SubPageScaffold(title, onBack = { if (!draft.busy) onBack() }, reserveDock = false) { padding ->
        SectionLazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap),
        ) {
            when (shownStep) {
                "kind" -> {
                    item(contentType = SectionRole.HEADER) { GroupHeader("账户类型") }
                    item {
                        InsetGroup {
                            AccountPresets.kinds.forEach { item ->
                                val iconTint = AccountLogos.defaultColor(item.kind, item.label)
                                val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
                                ListRow(
                                    title = item.label,
                                    subtitle = item.subtitle,
                                    leading = {
                                        Box(
                                            Modifier
                                                .size(AbCardDensity.settingsIcon)
                                                .clip(CircleShape)
                                                .background(solidBadgeBackground(iconTint))
                                                .badgeLiquidGlaze(effectiveGlass),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = CategoryIcons.vectorFor(item.defaultIconKey),
                                                contentDescription = null,
                                                modifier = Modifier.size(AbCardDensity.settingsIcon * 0.54f),
                                                tint = Color.White,
                                            )
                                        }
                                    },
                                    chevron = true,
                                    onClick = {
                                        draft.kind = item.kind
                                        draft.error = null
                                        val presets = AccountPresets.forKind(item.kind)
                                        if (AccountPresets.skipsSpecificStep(item.kind) && presets.size == 1) {
                                            val p = presets.first()
                                            draft.applyPreset(p.name, p.aliases, item.kind, p.iconKey, p.colorHex, p.quickSelect)
                                            onStepTo("settings")
                                        } else {
                                            onStepTo("specific")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                "specific" -> {
                    val presets = AccountPresets.forKind(draft.kind)
                    val kindInfo = AccountPresets.kindInfo(draft.kind)
                    item(contentType = SectionRole.HEADER) { GroupHeader("常用账户") }
                    item {
                        InsetGroup {
                            presets.forEach { preset ->
                                val taken = preset.name in existingNames
                                ListRow(
                                    title = preset.name,
                                    leading = {
                                        AccountAvatar(
                                            name = preset.name,
                                            size = AbCardDensity.settingsIcon,
                                            kind = preset.kind,
                                            iconKey = preset.iconKey,
                                            colorHex = preset.colorHex,
                                        )
                                    },
                                    trailing = {
                                        if (taken) Text(
                                            "已添加",
                                            style = AbType.footnote,
                                            color = LocalAbPalette.current.tertiaryLabel,
                                        )
                                    },
                                    onClick = if (taken) null else {
                                        {
                                            draft.applyPreset(
                                                presetName = preset.name,
                                                presetAliases = preset.aliases,
                                                presetKind = preset.kind,
                                                presetIconKey = preset.iconKey,
                                                presetColorHex = preset.colorHex,
                                                presetQuickSelect = preset.quickSelect,
                                            )
                                            onStepTo("settings")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    item(contentType = SectionRole.HEADER) { GroupHeader("自定义") }
                    item {
                        val p = LocalAbPalette.current
                        InsetGroup {
                            ListRow(
                                title = "自定义${kindInfo?.label ?: "账户"}",
                                subtitle = "同银行多卡或任意名称",
                                leading = {
                                    Box(
                                        Modifier
                                            .size(AbCardDensity.settingsIcon)
                                            .clip(CircleShape)
                                            .background(p.fill),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = AbIcons.Plus,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = p.accent,
                                        )
                                    }
                                },
                                chevron = true,
                                onClick = {
                                    draft.applyPreset(
                                        presetName = "",
                                        presetAliases = "",
                                        presetKind = draft.kind,
                                        presetIconKey = null,
                                        presetColorHex = null,
                                        presetQuickSelect = kindInfo?.defaultQuickSelect ?: true,
                                    )
                                    onStepTo("settings")
                                },
                            )
                        }
                    }
                }

                else -> {
                    item {
                        InsetGroup {
                            ListRow(
                                title = "图标与配色",
                                leading = {
                                    AccountAvatar(
                                        name = draft.name.ifBlank { AccountPresets.kindInfo(draft.kind)?.label ?: "账户" },
                                        size = AbCardDensity.settingsIcon,
                                        kind = draft.kind,
                                        iconKey = draft.iconKey,
                                        colorHex = draft.colorHex,
                                    )
                                },
                                chevron = true,
                                onClick = { stylePickerOpen = true },
                            )
                            InputRow(
                                label = "名称",
                                value = draft.name,
                                onValueChange = { draft.name = it; draft.error = null },
                                placeholder = "账户名称",
                                dividerAbove = true,
                                labelWidth = labelWidth,
                            )
                            if (draft.kind in setOf("SAVINGS", "CREDIT")) {
                                InputRow(
                                    label = "卡尾号",
                                    value = draft.cardTail,
                                    onValueChange = { draft.cardTail = it.take(8); draft.error = null },
                                    placeholder = "后 4 位（选填）",
                                    dividerAbove = true,
                                    labelWidth = labelWidth,
                                    keyboardType = KeyboardType.Number,
                                )
                            }
                            InputRow(
                                label = "别名",
                                value = draft.aliases,
                                onValueChange = { draft.aliases = it; draft.error = null },
                                placeholder = "逗号分隔（选填）",
                                dividerAbove = true,
                                labelWidth = labelWidth,
                            )
                        }
                    }

                    if (draft.kind == "CREDIT") {
                        item {
                            InsetGroup {
                                InputRow(
                                    label = "信用额度",
                                    value = draft.creditLimit,
                                    onValueChange = { draft.creditLimit = it; draft.error = null },
                                    placeholder = "元（选填）",
                                    labelWidth = labelWidth,
                                    keyboardType = KeyboardType.Decimal,
                                )
                                InputRow(
                                    label = "账单日",
                                    value = draft.billingDayText,
                                    onValueChange = { draft.billingDayText = it.filter(Char::isDigit).take(2); draft.error = null },
                                    placeholder = "每月 1–31 日（选填）",
                                    dividerAbove = true,
                                    labelWidth = labelWidth,
                                    keyboardType = KeyboardType.Number,
                                )
                                InputRow(
                                    label = "还款日",
                                    value = draft.repaymentDayText,
                                    onValueChange = { draft.repaymentDayText = it.filter(Char::isDigit).take(2); draft.error = null },
                                    placeholder = "每月 1–31 日（选填）",
                                    dividerAbove = true,
                                    labelWidth = labelWidth,
                                    keyboardType = KeyboardType.Number,
                                )
                            }
                        }
                    }

                    item {
                        InsetGroup {
                            ListRow(
                                title = "纳入资产总览",
                                trailing = { AbSwitch(draft.included) { draft.included = it } },
                            )
                            ListRow(
                                title = "确认卡显示",
                                dividerAbove = true,
                                trailing = { AbSwitch(draft.quickSelect) { draft.quickSelect = it } },
                            )
                            InputRow(
                                label = if (debt) "当前欠款" else "当前余额",
                                value = draft.amount,
                                onValueChange = { draft.amount = it; draft.error = null },
                                placeholder = if (debt) "元，留空为 0" else "元，留空为 0",
                                dividerAbove = true,
                                labelWidth = labelWidth,
                                keyboardType = KeyboardType.Decimal,
                            )
                        }
                    }

                    if (draft.error != null) item { InlineMessage(draft.error.orEmpty(), isError = true, announce = true) }

                    item(contentType = SectionRole.ACTION) {
                        PrimaryButton("保存账户", modifier = Modifier.fillMaxWidth(), busy = draft.busy, onClick = {
                            if (draft.busy) return@PrimaryButton
                            draft.busy = true
                            draft.error = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val trimmed = draft.name.trim()
                                        require(trimmed.isNotEmpty()) { "请输入账户名称" }
                                        val aliasList = draft.aliases.split(',', '，')
                                            .map(String::trim).filter(String::isNotEmpty).distinct()
                                        val tailClean = draft.cardTail.trim().takeIf { it.isNotEmpty() }
                                        val limitCents = if (draft.kind == "CREDIT" && draft.creditLimit.isNotBlank()) {
                                            AssetMath.parseCents(draft.creditLimit).also { require(it >= 0L) { "信用额度不能为负" } }
                                        } else null
                                        val bDay = if (draft.kind == "CREDIT" && draft.billingDayText.isNotBlank()) {
                                            draft.billingDayText.toIntOrNull()?.also { require(it in 1..31) { "账单日须在 1–31 之间" } }
                                                ?: error("账单日格式无效")
                                        } else null
                                        val rDay = if (draft.kind == "CREDIT" && draft.repaymentDayText.isNotBlank()) {
                                            draft.repaymentDayText.toIntOrNull()?.also { require(it in 1..31) { "还款日须在 1–31 之间" } }
                                                ?: error("还款日格式无效")
                                        } else null
                                        val cents = if (draft.amount.isBlank()) 0L else AssetMath.parseCents(draft.amount)
                                        val position = if (debt) Math.negateExact(cents) else cents
                                        AssetStore.openAccount(
                                            name = trimmed,
                                            kind = draft.kind,
                                            aliases = MiniJson.encode(aliasList),
                                            included = draft.included,
                                            positionCents = position,
                                            quickSelect = draft.quickSelect,
                                            cardTail = tailClean,
                                            creditLimitCents = limitCents,
                                            billingDay = bDay,
                                            repaymentDay = rDay,
                                            iconKey = draft.iconKey,
                                            colorHex = draft.colorHex,
                                        )
                                    }
                                }.onSuccess(onSaved).onFailure { draft.error = it.message ?: "保存失败" }
                                draft.busy = false
                            }
                        })
                    }
                }
            }
        }
    }

    if (stylePickerOpen) {
        val display = draft.name.ifBlank { AccountPresets.kindInfo(draft.kind)?.label ?: "账户" }
        IconColorStyleDialog(
            title = "账户图标与配色",
            previewName = display,
            initialIconKey = draft.iconKey,
            initialColorHex = draft.colorHex,
            defaultIconKey = AccountLogos.defaultIconKey(draft.kind, display),
            defaultColor = AccountLogos.defaultColor(draft.kind, display),
            iconGroups = AccountLogos.iconGroups,
            onDismiss = { stylePickerOpen = false },
            onConfirm = { nextIcon, nextColor ->
                draft.iconKey = nextIcon
                draft.colorHex = nextColor
                stylePickerOpen = false
            },
        )
    }
}
