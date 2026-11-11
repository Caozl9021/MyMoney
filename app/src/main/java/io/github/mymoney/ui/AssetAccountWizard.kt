package io.github.mymoney.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.AssetMath
import io.github.mymoney.data.AssetStore
import io.github.mymoney.data.MiniJson
import io.github.mymoney.ui.design.AbCardDensity
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbSwitch
import io.github.mymoney.ui.design.AccountAvatar
import io.github.mymoney.ui.design.AccountPresets
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InlineMessage
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.SectionLazyColumn
import io.github.mymoney.ui.design.SectionRole
import io.github.mymoney.ui.design.SubPageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加账户向导：选类型 →（可选）选具体 → 账户设置。
 * 保存即开通（空余额记为 0），风格与资产/设置列表一致。
 */
@Composable
fun AssetAccountWizard(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val accounts by AssetStore.accounts.collectAsStateWithLifecycle()
    val existingNames = remember(accounts) { accounts.filter { !it.archived }.map { it.name }.toSet() }
    var step by rememberSaveable { mutableStateOf("kind") }
    var kind by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var aliases by rememberSaveable { mutableStateOf("") }
    var included by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val debt = AssetMath.isDebt(kind)

    fun goBack() {
        if (busy) return
        error = null
        when (step) {
            "settings" -> step = if (AccountPresets.skipsSpecificStep(kind)) "kind" else "specific"
            "specific" -> step = "kind"
            else -> onBack()
        }
    }

    fun applyPreset(presetName: String, presetAliases: String, presetKind: String) {
        kind = presetKind
        name = presetName
        aliases = presetAliases
        amount = ""
        included = true
        error = null
        step = "settings"
    }

    ActiveBackHandler { goBack() }
    val reduceMotion = io.github.mymoney.ui.design.LocalReduceMotion.current
    fun stepDepth(s: String): Int = when (s) {
        "kind" -> 0
        "specific" -> 1
        else -> 2
    }
    AnimatedContent(
        targetState = step,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val duration = if (reduceMotion) 0 else io.github.mymoney.ui.design.AbMotion.normal
            val goingBack = stepDepth(targetState) < stepDepth(initialState)
            slideInHorizontally(tween(duration)) { if (goingBack) -it else it } togetherWith
                slideOutHorizontally(tween(duration)) { if (goingBack) it else -it }
        },
        label = "wizardStepTransition",
    ) { shownStep ->
        val title = when (shownStep) {
            "kind" -> "选择账户类型"
            "specific" -> AccountPresets.kinds.firstOrNull { it.kind == kind }?.label ?: "选择账户"
            else -> "账户设置"
        }
        SubPageScaffold(title, onBack = { goBack() }, reserveDock = false) { padding ->
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
                            AccountPresets.kinds.forEachIndexed { index, item ->
                                ListRow(
                                    title = item.label,
                                    subtitle = item.subtitle,
                                    dividerAbove = index > 0,
                                    chevron = true,
                                    onClick = {
                                        kind = item.kind
                                        error = null
                                        val presets = AccountPresets.forKind(item.kind)
                                        if (AccountPresets.skipsSpecificStep(item.kind) && presets.size == 1) {
                                            applyPreset(presets.first().name, presets.first().aliases, item.kind)
                                        } else {
                                            step = "specific"
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                "specific" -> {
                    val presets = AccountPresets.forKind(kind)
                    item(contentType = SectionRole.HEADER) { GroupHeader("具体账户") }
                    item {
                        InsetGroup {
                            presets.forEachIndexed { index, preset ->
                                val taken = preset.name in existingNames
                                ListRow(
                                    title = preset.name,
                                    subtitle = if (taken) "已添加" else null,
                                    dividerAbove = index > 0,
                                    leading = {
                                        AccountAvatar(name = preset.name, size = AbCardDensity.settingsIcon)
                                    },
                                    trailing = {
                                        if (taken) Text("已添加", color = LocalAbPalette.current.tertiaryLabel)
                                    },
                                    onClick = if (taken) null else {
                                        { applyPreset(preset.name, preset.aliases, preset.kind) }
                                    },
                                )
                            }
                        }
                    }
                }
                else -> {
                    item { AssetFormField("账户名称", name, "可改名称，不可与已有账户重复") { name = it; error = null } }
                    item { AssetFormField("别名（逗号分隔）", aliases, "仅明确填写的别名参与精确匹配") { aliases = it; error = null } }
                    item {
                        InsetGroup {
                            ListRow(
                                title = "纳入资产总览",
                                trailing = { AbSwitch(included) { included = it } },
                            )
                        }
                    }
                    item {
                        AssetFormField(
                            if (debt) "当前欠款（元）" else "当前余额（元）",
                            amount,
                            if (debt) "可留空视为欠款 0；欠款填正数，溢缴填负数" else "可留空视为余额 0；透支填负数",
                        ) { amount = it; error = null }
                    }
                    if (error != null) item { InlineMessage(error.orEmpty(), isError = true, announce = true) }
                    item(contentType = SectionRole.ACTION) {
                        PrimaryButton("保存账户", modifier = Modifier.fillMaxWidth(), busy = busy, onClick = {
                            if (busy) return@PrimaryButton
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val trimmed = name.trim()
                                        require(trimmed.isNotEmpty()) { "请输入账户名称" }
                                        val aliasList = aliases.split(',', '，')
                                            .map(String::trim).filter(String::isNotEmpty).distinct()
                                        val cents = if (amount.isBlank()) 0L else AssetMath.parseCents(amount)
                                        val position = if (debt) Math.negateExact(cents) else cents
                                        AssetStore.openAccount(
                                            name = trimmed,
                                            kind = kind,
                                            aliases = MiniJson.encode(aliasList),
                                            included = included,
                                            positionCents = position,
                                        )
                                    }
                                }.onSuccess(onSaved).onFailure { error = it.message ?: "保存失败" }
                                busy = false
                            }
                        })
                    }
                }
                }
            }
        }
    }
}
