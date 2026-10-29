package com.example.autobook.ui

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.flow.first
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.capture.AccessibilityEnhancer
import com.example.autobook.capture.AccessibilityGuardian
import com.example.autobook.capture.BillAccessibilityService
import com.example.autobook.capture.ConfirmDelivery
import com.example.autobook.data.LedgerConfig
import com.example.autobook.data.NoteTemplate
import com.example.autobook.data.SampleExport
import com.example.autobook.data.Settings
import com.example.autobook.export.CostShare
import com.example.autobook.export.ExportRange
import com.example.autobook.data.Backup
import com.example.autobook.data.BackupStore
import com.example.autobook.overlay.OverlayAnchor
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.capture.PaymentNotificationListener
import com.example.autobook.capture.NotificationIngress
import com.example.autobook.data.CaptureMode
import com.example.autobook.data.NotificationPath
import com.example.autobook.data.LocalRuleStore
import com.example.autobook.ui.design.AbCardDensity
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbSwitch
import com.example.autobook.ui.design.AbTextField
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.CapsuleChip
import com.example.autobook.ui.design.EmptyState
import com.example.autobook.ui.design.GroupFooter
import com.example.autobook.ui.design.GroupHeader
import com.example.autobook.ui.design.InsetDivider
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.ListRow
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PageScaffold
import com.example.autobook.ui.design.PrimaryButton
import com.example.autobook.ui.design.SecondaryButton
import com.example.autobook.ui.design.Segmented
import com.example.autobook.ui.design.SubPageScaffold
import com.example.billkit.BillKit
import com.example.billkit.DebugBus
import com.example.billkit.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 设置页内部的页面栈（手写，不引 navigation-compose）。 */
private enum class SettingsPage(val title: String) {
    ROOT("设置"), LEDGER_PERIOD("记账周期"), CATEGORIES("分类"), EXPENSE_CATEGORIES("支出分类"), INCOME_CATEGORIES("收入分类"), CATEGORY_PRESETS("分类预设"), ASSETS("资产"), NOTE("备注"),
    NOTE_TEMPLATE("备注模板"), NOTE_SUGGESTIONS("备注建议"),
    AUTOMATION("自动记账"), CHANNELS("识别设置"), AUTOMATION_MORE("更多设置"), LSP("LSPosed 模块"), SMS("银行短信"), CONFIRMATION("确认卡"),
    RECOGNITION("应用与通道"), LEARNING("学习记忆"), TEMPLATES("通知模板"),
    RULE_FILES("规则文件"), MEALS("餐段设置"), STATISTICS("识别统计"),
    PERMISSIONS("权限与后台运行"), MODE("使用方式"), ROOT_MODE("Root 状态"),
    PENDING("识别历史"), IMPORT_EXPORT("导入导出"), EXPORT_CSV("导出到 iCost"), EXPORT_HELP("导入说明"),
    QIANJI_IMPORT("导入钱迹账单"), BACKUP("备份与恢复"), APPEARANCE("外观"), DEVELOPMENT("开发调试"),
    HELP("识别帮助"), DEBUG("通知诊断"), PAGE_DEBUG("页面诊断"),
}

private fun captureSettingsPage(destination: CaptureSettingsDestination): SettingsPage = when (destination) {
    CaptureSettingsDestination.MODE -> SettingsPage.AUTOMATION
    CaptureSettingsDestination.ROOT_STATUS -> SettingsPage.ROOT_MODE
    CaptureSettingsDestination.SUPPORTED_APPS -> SettingsPage.RECOGNITION
    CaptureSettingsDestination.SMS -> SettingsPage.SMS
}

/**
 * 自动记账页「更多设置」里那些入口的去处。[Route] 之所以不直接用 [SettingsPage]，
 * 是因为 SettingsPage 是这一屏的私有实现细节，自动化页不该依赖它的枚举顺序。
 */
private fun settingsPageFor(route: Route): SettingsPage = when (route) {
    Route.SOURCES -> SettingsPage.CHANNELS
    Route.LSP -> SettingsPage.LSP
    Route.MORE -> SettingsPage.AUTOMATION_MORE
    Route.SUPPORTED_APPS -> SettingsPage.RECOGNITION
    Route.LEARNING -> SettingsPage.LEARNING
    Route.TEMPLATES -> SettingsPage.TEMPLATES
    Route.RULE_FILES -> SettingsPage.RULE_FILES
    Route.MEALS -> SettingsPage.MEALS
    Route.STATISTICS -> SettingsPage.STATISTICS
}

/** 分层设置：首页三组九入口，子页沿用统计页的中性圆角卡片。 */
@Composable
fun SettingsScreen(destination: CaptureSettingsDestination? = null, onDestinationHandled: () -> Unit = {}, onEditorVisibilityChanged: (Boolean) -> Unit = {}, onReturnToOrigin: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val p = LocalAbPalette.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    var pagePath by rememberSaveable { mutableStateOf(listOf(SettingsPage.ROOT)) }
    val page = pagePath.last()
    val pageTransition = updateTransition(pagePath, label = "settingsRoute")
    val visibilityChanged by androidx.compose.runtime.rememberUpdatedState(onEditorVisibilityChanged)
    LaunchedEffect(page) { visibilityChanged(page == SettingsPage.ASSETS) }
    DisposableEffect(Unit) { onDispose { visibilityChanged(false) } }
    val navigationScope = rememberCoroutineScope()
    val backPixels = RememberBackPixels()
    val previews = remember { mutableMapOf<SettingsPage, androidx.compose.ui.graphics.ImageBitmap>() }
    var navigating by remember { mutableStateOf(false) }
    val predictiveEnabled = com.example.autobook.ui.design.LocalPredictiveBackEnabled.current
    LaunchedEffect(predictiveEnabled) { previews.clear() }
    var directEntry by rememberSaveable { mutableStateOf(false) }
    fun navigate(target: SettingsPage) {
        if (navigating || target == page) return
        navigating = true
        val origin = pagePath
        navigationScope.launch {
            try {
                snapshotFlow { !pageTransition.isRunning && pageTransition.currentState == pageTransition.targetState }.first { it }
                withFrameNanos { }
                if (pagePath != origin) return@launch
                backPixels.snapshot()?.let { previews[origin.last()] = it }
                if (pagePath != origin) return@launch
                // Bound retained pixels to the current ancestor path.
                previews.keys.retainAll(pagePath.toSet())
                pagePath = pagePath + target
            } finally { navigating = false }
        }
    }
    fun returnToParent() {
        if (navigating) return
        if (directEntry && pagePath.size <= 2) {
            directEntry = false; pagePath = listOf(SettingsPage.ROOT); onReturnToOrigin()
        } else pagePath = pagePath.dropLast(1).ifEmpty { listOf(SettingsPage.ROOT) }
    }
    LaunchedEffect(destination) {
        destination?.let {
            val target = captureSettingsPage(it)
            directEntry = true
            pagePath = listOf(SettingsPage.ROOT, target)
            onDestinationHandled()
        }
    }
    val healthInput = rememberCaptureHealthInput()
    val health = captureHealth(healthInput)
    fun handleHealthAction(action: CaptureAction) {
        if (action == CaptureAction.LSP) { navigate(SettingsPage.LSP); return }
        if (action == CaptureAction.SELECT_MODE && CaptureModeManager.state.value.mode != null) { navigate(SettingsPage.CHANNELS); return }
        performCaptureAction(context, action, { target ->
            navigate(captureSettingsPage(target))
        }, { navigate(SettingsPage.PENDING) })
    }

    // 贯穿整棵设置树的共享状态
    val captureState by CaptureModeManager.state.collectAsStateWithLifecycle()
    // 子页与首页摘要共享同一状态，返回后计数随存储更新。
    val ruleState by LocalRuleStore.state.collectAsStateWithLifecycle()
    val history by com.example.autobook.data.RecognitionStore.records.collectAsStateWithLifecycle()
    val historyReady by com.example.autobook.data.RecognitionStore.ready.collectAsStateWithLifecycle()
    val pendingCount = history.count { it.status == com.example.autobook.data.RecognitionStatus.PENDING }
    val enabled = captureState.enabled
    val mode = captureState.mode
    val scope = rememberCoroutineScope()
    var autoConfirm by remember { mutableStateOf(settings.autoConfirm) }
    var whitelist by remember { mutableStateOf(settings.whitelist) }
    val serviceBound = healthInput.accessibilityAuthorized && healthInput.accessibilityBound
    var overlayGranted by remember { mutableStateOf(OverlayAnchor.canShow(context)) }
    var notifyGranted by remember { mutableStateOf(ConfirmDelivery.hasNotificationPermission(context)) }
    // 短信通道：RECEIVE_SMS 是运行时权限，开关打开但没授权时通道是哑的，必须显式提示。
    var smsGranted by remember { mutableStateOf(hasSmsPermission(context)) }
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = OverlayAnchor.canShow(context)
                notifyGranted = ConfirmDelivery.hasNotificationPermission(context)
                smsGranted = hasSmsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var checkingRoot by remember { mutableStateOf(false) }
    var rootRequest by remember { mutableIntStateOf(0) }
    var rootDenied by remember { mutableStateOf(false) }
    fun checkRoot(selectAfter: Boolean = false) {
        if (checkingRoot) return
        checkingRoot = true
        val request = ++rootRequest
        scope.launch {
            try {
                val granted = withContext(Dispatchers.IO) { RootShell.isGranted() }
                if (request == rootRequest) {
                    rootGranted = granted
                    rootDenied = !granted
                    if (granted && (selectAfter || CaptureModeManager.state.value.mode == CaptureMode.ROOT))
                        CaptureModeManager.select(context, CaptureMode.ROOT)
                }
            } finally { if (request == rootRequest) checkingRoot = false }
        }
    }
    fun selectMode(next: CaptureMode) {
        // Callable references can outlive the composition that created them; read the live mode.
        val currentMode = CaptureModeManager.state.value.mode
        android.util.Log.i("AutoBookMode", "切换方式: $currentMode -> $next")
        if (next == CaptureMode.ACCESSIBILITY) {
            rootRequest++
            checkingRoot = false
            rootDenied = false
            if (next != currentMode) CaptureModeManager.select(context, next)
        } else if (next != currentMode) {
            rootDenied = false
            checkRoot(selectAfter = true)
        }
    }
    val modeGate = if (rootDenied && mode != CaptureMode.ROOT) ModeGate.NO_ROOT else ModeGate.READY
    LaunchedEffect(page) {
        if (page == SettingsPage.ROOT_MODE) checkRoot()
    }
    val snapshot = captureSettingsSnapshot(healthInput, notifyGranted, rootGranted)

    fun applyKit() {
        BillKit.updateConfig(settings.toKitConfig())
        BillAccessibilityService.instance?.refreshServiceInfo()
    }

    // 系统返回/侧滑：先退子页，再交给 MainActivity 处理 tab，不再直接退出 App


    val back: () -> Unit = { returnToParent() }

    val reduceMotion = com.example.autobook.ui.design.LocalReduceMotion.current
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val contentReady = !pageTransition.isRunning && pageTransition.currentState == pagePath && pageTransition.targetState == pagePath
    BackSurface(enabled = page != SettingsPage.ROOT, onBack = ::returnToParent, routeKey = pagePath, contentReady = contentReady,
        preview = previews[pagePath.dropLast(1).lastOrNull()], pixels = backPixels) {
    val predictiveCommit = LocalPredictiveBackCommit.current
    pageTransition.AnimatedContent(modifier = Modifier.fillMaxSize().clipToBounds(), transitionSpec = {
        val duration = if (reduceMotion || predictiveCommit) 0 else 220
        // Opaque neighbours never blend text from both routes into a future back preview.
        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(duration)) { if (targetState.size < initialState.size) -it else it } togetherWith
            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(duration)) { if (targetState.size < initialState.size) it else -it }
    }) { shownPath ->
    val shown = shownPath.last()
    androidx.compose.runtime.CompositionLocalProvider(LocalBackActive provides (shownPath == pagePath), LocalPageBackManaged provides true) {
    pageStates.SaveableStateProvider(shown) {
    when (shown) {
        SettingsPage.ROOT -> PageScaffold(title = "设置", listState = listState, floatingHeader = true) { padding ->
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                    top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
                item { SettingsNavigationGroup("账本", listOf(
                    SettingsLink("记账周期", "每月${rememberMonthStartDay()}日开始", AbIcons.Clock) { navigate(SettingsPage.LEDGER_PERIOD) },
                    SettingsLink("分类", "支出、收入与二级分类", AbIcons.Tag) { navigate(SettingsPage.CATEGORIES) },
                    SettingsLink("资产", "账户、余额、转账与还款", AbIcons.Wallet) { navigate(SettingsPage.ASSETS) },
                    SettingsLink("备注", "模板与本地备注建议", AbIcons.Note) { navigate(SettingsPage.NOTE) },
                )) }
                item { SettingsNavigationGroup("自动记账", listOf(
                    SettingsLink("自动记账", health.title, AbIcons.Accessibility) { navigate(SettingsPage.AUTOMATION) },
                    SettingsLink("识别历史", if (historyReady) "待确认 $pendingCount 条" else "正在读取…", AbIcons.Clock) { navigate(SettingsPage.PENDING) },
                )) }
                item { SettingsNavigationGroup("数据与应用", listOf(
                    SettingsLink("导入导出", "账单迁移、导出与备份", AbIcons.Export) { navigate(SettingsPage.IMPORT_EXPORT) },
                    SettingsLink("外观", "主题、文字大小与动效", AbIcons.Settings) { navigate(SettingsPage.APPEARANCE) },
                    SettingsLink("开发调试", "识别帮助、通知与页面诊断", AbIcons.Terminal) { navigate(SettingsPage.DEVELOPMENT) },
                )) }
            }
        }
        SettingsPage.LEDGER_PERIOD -> LedgerPeriodPage(back)
        SettingsPage.AUTOMATION -> AutomationPage(
            mode = mode, enabled = enabled, health = health, autoConfirm = autoConfirm,
            onBack = back, onAction = ::handleHealthAction, onSelectMode = ::selectMode,
            onToggleEnabled = { CaptureModeManager.setEnabled(context, it) },
            onToggleConfirm = { autoConfirm = it; settings.autoConfirm = it },
            onNavigate = { navigate(settingsPageFor(it)) }, gate = modeGate, checkingRoot = checkingRoot,
        )
        SettingsPage.CHANNELS -> RecognitionSettingsPage(mode, snapshot, autoConfirm, back,
            ::handleHealthAction, { navigate(SettingsPage.PERMISSIONS) }, { navigate(SettingsPage.ROOT_MODE) })
        SettingsPage.LSP -> LspSettingsPage(mode, captureState.hookEnabled, enabled, back) { action ->
            performCaptureAction(context, action, { navigate(captureSettingsPage(it)) }, { navigate(SettingsPage.PENDING) })
        }
        SettingsPage.SMS -> SmsSettingsPage(snapshot, back)
        SettingsPage.AUTOMATION_MORE -> SettingsMenuPage("更多设置", back, listOf(
            "识别" to listOf(
                SettingsLink("应用范围", "${whitelist.size} 个应用", AbIcons.Store) { navigate(SettingsPage.RECOGNITION) },
                SettingsLink("银行短信", if (captureState.smsEnabled) "已开启" else "按需开启", AbIcons.Note) { navigate(SettingsPage.SMS) },
            ),
            "学习与规则" to listOf(
                SettingsLink("学习记忆", "${ruleState.learned.size} 条已生效记忆", AbIcons.Note) { navigate(SettingsPage.LEARNING) },
                SettingsLink("通知模板", "交易提醒与适用范围", AbIcons.Filter) { navigate(SettingsPage.TEMPLATES) },
                SettingsLink("规则文件", "导入、导出与恢复内置规则", AbIcons.Layers) { navigate(SettingsPage.RULE_FILES) },
                SettingsLink("餐段设置", "早餐、午餐、晚餐与夜宵", AbIcons.Clock) { navigate(SettingsPage.MEALS) },
            ),
            "帮助" to listOf(
                SettingsLink("识别统计", "最近 7 天", AbIcons.Chart) { navigate(SettingsPage.STATISTICS) },
                SettingsLink("帮助与诊断", "识别说明与问题排查", AbIcons.Search) { navigate(SettingsPage.HELP) },
            ),
        ))
        SettingsPage.CONFIRMATION -> SettingsContentPage("确认卡", back) {
            InsetGroup {
                        ListRow(title = "识别后弹出确认卡", subtitle = "关闭后保存在待确认中",
                            leading = { com.example.autobook.ui.design.SettingsIconBadge("识别后弹出确认卡", AbIcons.Note) },
                            trailing = { AbSwitch(autoConfirm) { autoConfirm = it; settings.autoConfirm = it } },
                            onClick = { autoConfirm = !autoConfirm; settings.autoConfirm = autoConfirm })

            }
        }
        SettingsPage.NOTE -> SettingsMenuPage("备注", back, listOf("备注设置" to listOf(
            SettingsLink("备注模板", "编辑内容、选择预设并预览", AbIcons.Note) { navigate(SettingsPage.NOTE_TEMPLATE) },
            SettingsLink("备注建议", "查看建议来源与本地学习状态", AbIcons.Filter) { navigate(SettingsPage.NOTE_SUGGESTIONS) },
        )))
        SettingsPage.NOTE_SUGGESTIONS -> SettingsContentPage("备注建议", back) {
            InsetGroup {
                ListRow(title = "同商户备注", subtitle = "优先建议本机相同商户、相同类型账单中使用过的备注。")
                ListRow(title = "餐段习惯", subtitle = "商户为空时，参考近 90 天相同餐段的支出；点选后仅补充当前为空的商户、分类和账户。", dividerAbove = true)
                ListRow(title = "模板结果", subtitle = "使用备注模板渲染的结果；最多显示 6 条建议。", dividerAbove = true)
            }
            InsetGroup {
                ListRow(title = "本地学习", subtitle = if (ruleState.learningEnabled) "已开启 · 管理学习记忆" else "已关闭 · 历史习惯建议暂停，模板仍可用",
                    chevron = true, onClick = { navigate(SettingsPage.LEARNING) })
            }
        }
        SettingsPage.IMPORT_EXPORT -> SettingsMenuPage("导入导出", back, listOf(
            "导入账单" to listOf(
                SettingsLink("钱迹账单", "选择 XLSX，预览后导入", AbIcons.Export) { navigate(SettingsPage.QIANJI_IMPORT) },
            ),
            "导出账单" to listOf(
                SettingsLink("导出到 iCost", "按时间范围生成 CSV", AbIcons.Export) { navigate(SettingsPage.EXPORT_CSV) },
            ),
            "备份与恢复" to listOf(
                SettingsLink("完整备份与恢复", "保存账单、资产和本地配置", AbIcons.Layers) { navigate(SettingsPage.BACKUP) },
            ),
        ))
        SettingsPage.EXPORT_HELP -> SettingsContentPage("导入说明", back) {
            InsetGroup {
                ListRow(title = "1 · 准备分类与账户", subtitle = "AutoBook 与 iCost 中的分类、账户名称应对应；转账的另一账户需在 iCost 中补选。")
                ListRow(title = "2 · 选择范围并导出", subtitle = "按设置的记账周期选择当前、上一期或最近3期，也可导出全部或最近50条。默认每月1日起算。生成官方10列CSV。", dividerAbove = true)
                ListRow(title = "3 · 在 iCost 确认导入", subtitle = "发起分享后，在目标 App 完成导入与核对；分享成功不代表已经导入。", dividerAbove = true)
            }
            InsetGroup {
                ListRow(title = "范围包含退款时", subtitle = "iCost 模板不支持退款，会阻止整次 CSV 导出。请使用 JSON 备份保存完整账目。")
            }
        }
        SettingsPage.DEVELOPMENT -> SettingsMenuPage("开发调试", back, listOf("帮助与诊断" to listOf(
            SettingsLink("识别帮助", "使用说明与常见问题", AbIcons.Search) { navigate(SettingsPage.HELP) },
            SettingsLink("通知诊断", "通知事件、处理结论与脱敏样本", AbIcons.Terminal) { navigate(SettingsPage.DEBUG) },
            SettingsLink("页面诊断", "页面文字、识别结论与样本", AbIcons.Ocr) { navigate(SettingsPage.PAGE_DEBUG) },
        )))

        SettingsPage.ASSETS -> AssetsScreen(onBack = back)

        SettingsPage.CATEGORIES -> SettingsMenuPage("分类", back, listOf("收支分类" to listOf(
            SettingsLink("支出分类", "管理一级与二级支出分类", AbIcons.Tag) { navigate(SettingsPage.EXPENSE_CATEGORIES) },
            SettingsLink("收入分类", "管理一级与二级收入分类", AbIcons.Tag) { navigate(SettingsPage.INCOME_CATEGORIES) },
            SettingsLink("分类预设", "常用预设、补齐餐饮与恢复默认", AbIcons.Layers) { navigate(SettingsPage.CATEGORY_PRESETS) },
        )))
        SettingsPage.EXPENSE_CATEGORIES -> LedgerConfigPage(onBack = back, fixedTab = 0)
        SettingsPage.INCOME_CATEGORIES -> LedgerConfigPage(onBack = back, fixedTab = 1)
        SettingsPage.CATEGORY_PRESETS -> LedgerConfigPage(onBack = back, presetsOnly = true)

        SettingsPage.RECOGNITION, SettingsPage.LEARNING, SettingsPage.TEMPLATES,
        SettingsPage.RULE_FILES, SettingsPage.MEALS, SettingsPage.STATISTICS -> RecognitionRulesPage(
            section = when (shown) {
                SettingsPage.LEARNING -> RecognitionRuleSection.LEARNING
                SettingsPage.TEMPLATES -> RecognitionRuleSection.TEMPLATES
                SettingsPage.RULE_FILES -> RecognitionRuleSection.FILES
                SettingsPage.MEALS -> RecognitionRuleSection.MEALS
                SettingsPage.STATISTICS -> RecognitionRuleSection.STATISTICS
                else -> RecognitionRuleSection.APPS
            },
            whitelist = whitelist,
            healthInput = healthInput,
            onBack = back,
            onToggleApp = { pkg ->
                settings.setSupported(pkg, pkg !in whitelist)
                whitelist = settings.whitelist
                applyKit()
            },
            onAddApp = { pkg ->
                settings.setSupported(pkg, true)
                whitelist = settings.whitelist
                applyKit()
            },
            onOpenDebug = { navigate(SettingsPage.DEBUG) },
        )

        SettingsPage.NOTE_TEMPLATE -> NoteTemplatePage(onBack = back)

        SettingsPage.APPEARANCE -> AppearancePage(onBack = back)

        SettingsPage.MODE -> CaptureModeSettingsPage(mode, back) {
            selectMode(it)
            back()
        }

        SettingsPage.PERMISSIONS -> PermissionsPage(
            mode = mode,
            rootGranted = rootGranted,
            onCheckRoot = { checkRoot() },
            onRootStatus = { navigate(SettingsPage.ROOT_MODE) },
            serviceBound = serviceBound,
            overlayGranted = overlayGranted,
            notifyGranted = notifyGranted,
            onNotifyGranted = { notifyGranted = it },
            smsGranted = smsGranted,
            onSmsGranted = { smsGranted = it },
            onBack = back,
        )

        SettingsPage.ROOT_MODE -> RootModePage(
            rootGranted = rootGranted,
            onBack = back,
            onCheckRoot = { checkRoot() },
        )

        SettingsPage.EXPORT_CSV -> ExportPage(
            onBack = back,
            onOpenHelp = { navigate(SettingsPage.EXPORT_HELP) },
        )

        SettingsPage.QIANJI_IMPORT -> QianjiImportPage(back)
        SettingsPage.BACKUP -> BackupPage(
            onBack = back,
            onRestored = { snap ->
                if (snap != null) BackupStore.applySettings(context, snap)
                whitelist = settings.whitelist
                autoConfirm = settings.autoConfirm
                CaptureModeManager.reconcile(context)
                applyKit()
            },
        )

        SettingsPage.HELP -> HelpPage(onBack = back, onDebug = { navigate(SettingsPage.DEBUG) },
            onPageDebug = { navigate(SettingsPage.PAGE_DEBUG) },
            onMode = { navigate(SettingsPage.AUTOMATION) }, onPermissions = { navigate(SettingsPage.PERMISSIONS) },
            onApps = { navigate(SettingsPage.RECOGNITION) }, onPending = { navigate(SettingsPage.PENDING) })
        SettingsPage.DEBUG -> DebugPage(onBack = back, notifications = true)
        SettingsPage.PAGE_DEBUG -> DebugPage(onBack = back, notifications = false)
        SettingsPage.PENDING -> RecognitionHistoryScreen(onBack = back, initialFilter = com.example.autobook.data.RecognitionStatus.PENDING)
    }
    }
    }
    }
    }
}

internal fun captureModeLabel(mode: CaptureMode?): String = when (mode) {
    CaptureMode.ROOT -> "Root 模式"
    CaptureMode.ACCESSIBILITY -> "普通模式"
    null -> "尚未选择"
}

@Composable
private fun CaptureModeSettingsPage(mode: CaptureMode?, onBack: () -> Unit, onSave: (CaptureMode) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(mode) }
    SubPageScaffold(title = "使用方式", onBack = onBack) { padding ->
        CaptureModeChoice(
            mode = selected,
            onSelect = { selected = it },
            onDone = { selected?.let(onSave) },
            modifier = Modifier.padding(padding),
            doneLabel = "保存模式",
        )
    }
}

// ---------------------------------------------------------------------------
// 入口页顶部状态卡
// ---------------------------------------------------------------------------

@Composable
private fun StatusHeader(health: CaptureHealth, onAction: (CaptureAction) -> Unit) {
    InsetGroup { CaptureHealthCard(health, onAction) }
}

// ---------------------------------------------------------------------------
// 子页：备注模板
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 子页：权限
// ---------------------------------------------------------------------------

@Composable
private fun PermissionsPage(
    mode: CaptureMode?,
    rootGranted: Boolean?,
    onCheckRoot: () -> Unit,
    onRootStatus: () -> Unit,
    serviceBound: Boolean,
    overlayGranted: Boolean,
    notifyGranted: Boolean,
    onNotifyGranted: (Boolean) -> Unit,
    smsGranted: Boolean,
    onSmsGranted: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sourceHealth = rememberCaptureHealthInput()
    // Android 13+ 通知是运行时权限：不申请的话，弹不出确认卡时连提醒都发不出去，
    // 用户只会在待确认队列里"凭空"看到一条记录。
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onNotifyGranted(ConfirmDelivery.hasNotificationPermission(context))
        if (!granted) openNotificationChannelSettings(context)
    }
    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onSmsGranted(hasSmsPermission(context)) }

    // 保活体检结果：全部来自实测，不含推断。每次回到前台重读一次（用户可能刚去系统里改过）。
    var keepAlive by remember { mutableStateOf<AccessibilityEnhancer.KeepAliveStatus?>(null) }
    LaunchedEffect(Unit) { keepAlive = AccessibilityEnhancer.getStatus(context) }
    val keepAliveOwner = LocalLifecycleOwner.current
    DisposableEffect(keepAliveOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keepAlive = AccessibilityEnhancer.getStatus(context)
            }
        }
        keepAliveOwner.lifecycle.addObserver(observer)
        onDispose { keepAliveOwner.lifecycle.removeObserver(observer) }
    }

    SubPageScaffold(title = "权限与后台运行", onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page)) {
            GroupHeader("识别权限")
            InsetGroup {
                // 通知使用权只在无障碍模式需要。root 模式下通知由 AutoBook 自己读通知栏，
                // 这一行根本不该出现——否则用户会去系统里开一个用不上的授权。
                if (mode != CaptureMode.ROOT) {
                    ListRow(title = "读取其他应用通知", subtitle = when {
                        !sourceHealth.notificationAuthorized -> "未授予通知使用权，与发送提醒权限不同"
                        sourceHealth.notificationBound -> "系统已授权，监听已连接"
                        else -> "系统已授权，监听尚未连接"
                    }, chevron = true, onClick = {
                        performCaptureAction(context, CaptureAction.NOTIFICATION_ACCESS, {}, {})
                    })
                    if (sourceHealth.notificationAuthorized && !sourceHealth.notificationBound) {
                        InsetDivider()
                        ListRow(title = "重新连接通知监听", onClick = { PaymentNotificationListener.requestReconnect(context) })
                    }
                    InsetDivider()
                }
                if (mode == CaptureMode.ROOT) {
                    ListRow(
                        title = "Root 授权",
                        subtitle = when (rootGranted) {
                            true -> "已授权 — 点击重新检测"
                            false -> "未授权 — 点击重试"
                            null -> "点击检测并申请 Root 授权"
                        },
                        chevron = true,
                        onClick = onCheckRoot,
                    )
                    InsetDivider()
                    ListRow(title = "Root 实时状态", chevron = true, onClick = onRootStatus)
                } else if (mode == CaptureMode.ACCESSIBILITY) {
                    val checked = isAccessibilityChecked(context)
                    ListRow(
                        title = "无障碍服务",
                        subtitle = when {
                            serviceBound -> "已连接，正在工作"
                            checked -> "设置里是开启的，但服务没连上 — 点这里重新开关一次"
                            else -> "未开启 — 点这里去开启"
                        },
                        chevron = true,
                        onClick = { openAccessibilitySettings(context) },
                    )
                }
                InsetDivider()
                ListRow(
                    title = "读取银行短信",
                    subtitle = if (smsGranted) {
                        "已授权 · 只接收新短信，不读取历史短信"
                    } else {
                        "未授权 — 短信通道打开后也收不到内容"
                    },
                    chevron = true,
                    dividerAbove = true,
                    onClick = {
                        if (smsGranted) {
                            openAppDetailsSettings(context)
                        } else {
                            smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                        }
                    },
                )
                InsetDivider()
                ListRow(
                    title = "显示在其他应用上层",
                    subtitle = if (overlayGranted) {
                        "已授权 · 允许显示确认卡"
                    } else {
                        "未授权 · 确认页无法打开时保留待确认"
                    },
                    chevron = true,
                    dividerAbove = true,
                    onClick = { openOverlaySettings(context) },
                )
                InsetDivider()
                ListRow(
                    title = "发送待确认提醒",
                    subtitle = if (notifyGranted) {
                        "已允许，弹不出卡时会发提醒"
                    } else {
                        "未允许 — 点这里授权，否则提醒发不出来"
                    },
                    chevron = true,
                    dividerAbove = true,
                    onClick = {
                        if (notifyGranted) {
                            openNotificationChannelSettings(context)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationChannelSettings(context)
                        }
                    },
                )
            }

            // 无障碍保活：只显示**实测**状态 + 跳系统设置，不假装能替用户开
            if (mode == CaptureMode.ACCESSIBILITY) {
                val manufacturer = keepAlive?.manufacturer
                Spacer(Modifier.height(AbSpacing.m))
                GroupHeader("无障碍保活")
                InsetGroup {
                    ListRow(
                        title = "守护服务",
                        subtitle = when {
                            keepAlive?.guardianRunning == true ->
                                "运行中 — 每 30 秒检查无障碍是否还在，掉线会提醒"
                            keepAlive?.accessibilityEnabled == true ->
                                "未运行 — 点这里拉起守护"
                            keepAlive?.accessibilityChecked == true ->
                                "服务没连上（设置里却显示开启）— 点这里去系统里重新开关一次"
                            else -> "待开启 — 先在上面开启无障碍服务"
                        },
                        chevron = true,
                        onClick = {
                            if (AccessibilityGuardian.shouldRun(context)) {
                                AccessibilityGuardian.ensureRunning(context)
                            }
                            // 没连上时必须去系统里重新开关：只 startService 救不回来
                            if (!AccessibilityGuardian.isAccessibilityEnabled(context)) {
                                openAccessibilitySettings(context)
                            }
                            keepAlive = AccessibilityEnhancer.getStatus(context)
                        },
                    )
                    InsetDivider()
                    ListRow(
                        title = "电池优化",
                        subtitle = when (keepAlive?.batteryOptimizationIgnored) {
                            true -> "已豁免 — 后台不易被系统限制"
                            false -> "未豁免 — 点这里去系统里豁免"
                            null -> "读取中…"
                        },
                        chevron = true,
                        dividerAbove = true,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    AccessibilityEnhancer.batteryOptimizationIntent(context)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                    val manufacturer = keepAlive?.manufacturer
                    if (manufacturer != null) {
                        ListRow(
                            title = if (manufacturer.hasSpecialSettings) {
                                "厂商自启动（${manufacturer.name}）"
                            } else {
                                "厂商自启动"
                            },
                            subtitle = if (manufacturer.hasSpecialSettings) {
                                "点这里去系统的自启动管理页"
                            } else {
                                "本机型无单独的自启动开关"
                            },
                            chevron = manufacturer.hasSpecialSettings,
                            dividerAbove = true,
                            onClick = {
                                val intent = AccessibilityEnhancer
                                    .resolveAutoStartIntent(context, manufacturer)
                                if (intent != null) {
                                    runCatching {
                                        context.startActivity(
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                } else {
                                    openAppDetailsSettings(context)
                                }
                            },
                        )
                    }
                }


            }

        }
    }
}

// ---------------------------------------------------------------------------
// 子页：Root 状态
// ---------------------------------------------------------------------------

/**
 * Root 状态页。
 *
 * 页面轮询（uiautomator dump + 截图 OCR）已删除，这里不再有「实时轮询状态」。
 * 现在 root 模式下只剩一件事：用 `dumpsys notification` 读通知栏，
 * 所以这一页只回答两个问题——root 有没有、通知通道在不在跑。
 */
@Composable
private fun RootModePage(
    rootGranted: Boolean?,
    onBack: () -> Unit,
    onCheckRoot: () -> Unit,
) {
    val captureState by CaptureModeManager.state.collectAsStateWithLifecycle()
    val healthInput = rememberCaptureHealthInput()
    SubPageScaffold(title = "Root 状态", onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page)) {
            GroupHeader("状态")
            InsetGroup {
                ListRow(
                    title = "Root 授权",
                    subtitle = when (rootGranted) {
                        null -> "检测中 / 点击重试"
                        true -> "已获得（Magisk / KernelSU）"
                        false -> "未获得 — 点击重试"
                    },
                    chevron = true,
                    onClick = onCheckRoot,
                )
                InsetDivider()
                ListRow(
                    title = "通知通道",
                    subtitle = when {
                        captureState.mode != CaptureMode.ROOT -> "尚未使用 Root 模式"
                        !captureState.notificationsEnabled -> "已关闭"
                        !captureState.enabled -> "已开启 · 自动记账已暂停"
                        healthInput.rootNotificationReady -> "读取正常 · 等待交易通知"
                        healthInput.rootNotificationError != null -> healthInput.rootNotificationError!!
                        else -> "正在准备 · 尚未确认读取成功"
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：导出
// ---------------------------------------------------------------------------

@Composable
private fun ExportPage(onBack: () -> Unit, onOpenHelp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = LocalAbPalette.current
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 0 本月 / 1 上月 / 2 最近 3 个月 / 3 全部
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val ranges = listOf(
        ExportRange.Kind.THIS_MONTH,
        ExportRange.Kind.LAST_MONTH,
        ExportRange.Kind.LAST_3_MONTHS,
        ExportRange.Kind.ALL,
    )
    val kind = ranges[rangeIndex]
    val exportClock = rememberLedgerClock()
    val today = exportClock.date
    val monthStartDay = rememberMonthStartDay()

    fun run(count: suspend () -> Int) {
        if (busy) return
        busy = true
        scope.launch {
            message = runCatching { count() }.fold(
                onSuccess = { n -> if (n > 0) "已导出 $n 条" else "这个范围没有记录" },
                onFailure = { error -> error.message ?: "导出失败，请重试" },
            )
            busy = false
        }
    }

    SubPageScaffold(title = "导出到 iCost", onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page)) {
            GroupHeader("时间范围")
            InsetGroup {
                Column(Modifier.padding(AbSpacing.m)) {
                    Segmented(
                        options = ranges.map { ExportRange.label(it, monthStartDay) },
                        selected = rangeIndex,
                        onSelect = { rangeIndex = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        ExportRange.describe(kind, today, exportClock.zone, monthStartDay),
                        style = AbType.footnote,
                        color = p.tertiaryLabel,
                        modifier = Modifier.padding(top = AbSpacing.s, start = AbSpacing.xs),
                    )
                }
            }

            GroupHeader("导出")
            InsetGroup {
                ListRow(
                    title = "导出${ExportRange.label(kind, monthStartDay)}",
                    subtitle = if (busy) "生成中…" else "分享 CSV 给记账 App",
                    chevron = true,
                    onClick = { run { CostShare.shareRange(context, kind, today = today, monthStartDay = monthStartDay, zone = exportClock.zone) } },
                )
                InsetDivider()
                ListRow(
                    title = "最近 50 条",
                    subtitle = "补录场景，不看时间范围",
                    dividerAbove = true,
                    chevron = true,
                    onClick = { run { CostShare.shareRecent(context, limit = 50) } },
                )
            }
            if (message != null) {
                Text(
                    message!!,
                    style = AbType.footnote,
                    color = p.secondaryLabel,
                    modifier = Modifier.padding(start = AbSpacing.xs, top = AbSpacing.xs),
                )
            }

            InsetGroup {
                ListRow(title = "导入说明", subtitle = "查看操作步骤与格式限制", chevron = true, onClick = onOpenHelp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：外观
// ---------------------------------------------------------------------------

/** Theme, text scale and accessibility preferences apply to both floating navigation surfaces. */
@Composable
private fun AppearancePage(onBack: () -> Unit) {
    val p = LocalAbPalette.current

    SubPageScaffold(title = "外观", onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page)) {
            val context = LocalContext.current
            val revision = com.example.autobook.ui.design.appearanceRevision()
            val prefs = remember(revision) { com.example.autobook.ui.design.Appearance.prefs(context) }
            GroupHeader("主题")
            Segmented(listOf("跟随系统", "浅色", "深色"), listOf("SYSTEM", "LIGHT", "DARK").indexOf(prefs.getString("theme", "SYSTEM")).coerceAtLeast(0), { index -> prefs.edit().putString("theme", listOf("SYSTEM", "LIGHT", "DARK")[index]).apply() })
            GroupHeader("文字大小")
            Segmented(listOf("标准", "大", "特大"), listOf("STANDARD", "LARGE", "EXTRA_LARGE").indexOf(prefs.getString("text_size", "STANDARD")).coerceAtLeast(0), { index -> prefs.edit().putString("text_size", listOf("STANDARD", "LARGE", "EXTRA_LARGE")[index]).apply() })
            ListRow(title = "降低动效", subtitle = "系统移除动画时也会自动启用", trailing = { AbSwitch(prefs.getBoolean("reduce_motion", false)) { prefs.edit().putBoolean("reduce_motion", it).apply() } })
            InsetGroup {
                ListRow(title = "预测性返回", subtitle = "子页随手势预览，关闭后使用普通返回", trailing = {
                    AbSwitch(prefs.getBoolean(com.example.autobook.ui.design.Appearance.PREDICTIVE_BACK, com.example.autobook.ui.design.Appearance.DEFAULT_PREDICTIVE_BACK)) {
                        prefs.edit().putBoolean(com.example.autobook.ui.design.Appearance.PREDICTIVE_BACK, it).apply()
                    }
                })
            }
            GroupHeader("玻璃")
            InsetGroup {
                ListRow(
                    title = "液体玻璃",
                    subtitle = "顶部胶囊与底部标签栏采用轻透材质，随浅色、深色主题调整",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：备份与恢复（SAF，不需要存储权限）
// ---------------------------------------------------------------------------

/**
 * JSON 备份/恢复。
 *
 * 用 SAF（`CREATE_DOCUMENT` / `OPEN_DOCUMENT`）而不是自建文件路径：
 * 不需要任何存储权限，文件落在用户自己选的地方（还能直接扔进网盘目录）。
 * `AndroidManifest` 的 `allowBackup=false` 意味着这是换机/重装的唯一通道。
 *
 * 恢复分两步：先解析并展示"备份里有什么"，用户确认后才落库 —— 一个点错
 * 就把现有账本清空的入口，必须挡一道。
 */
@Composable
private fun BackupPage(onBack: () -> Unit, onRestored: (Backup.SettingsSnapshot?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = LocalAbPalette.current
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, Backup.Snapshot>?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = runCatching {
                val json = BackupStore.exportJson(context)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("无法写入所选文件")
                }
            }
            message = if (ok.isSuccess) "备份已保存" else "保存失败：${ok.exceptionOrNull()?.message}"
            busy = false
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val parsed = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取所选文件")
                }
                text to Backup.decode(text)
            }
            parsed.onSuccess { pending = it; message = null }
                .onFailure { message = "不是有效的备份文件：${it.message}" }
            busy = false
        }
    }

    SubPageScaffold(title = "备份与恢复", onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page)) {
            GroupHeader("备份")
            InsetGroup {
                ListRow(
                    title = "导出备份文件",
                    subtitle = if (busy) "处理中…" else "JSON，含账单、识别历史、账户余额与完整流水、配置",
                    chevron = true,
                    onClick = {
                        if (!busy) createLauncher.launch(BackupStore.fileName())
                    },
                )
            }

            GroupHeader("恢复")
            InsetGroup {
                ListRow(
                    title = "从备份恢复",
                    subtitle = if (busy) "处理中…" else "选择之前导出的 .json 文件",
                    chevron = true,
                    onClick = { if (!busy) openLauncher.launch(arrayOf("*/*")) },
                )
            }

            if (message != null) {
                Spacer(Modifier.height(AbSpacing.s))
                Text(
                    message!!,
                    style = AbType.footnote,
                    color = p.secondaryLabel,
                    modifier = Modifier.padding(start = AbSpacing.xs),
                )
            }
        }
    }

    val snapshot = pending
    if (snapshot != null) {
        val (json, snap) = snapshot
        val whenText = remember(snap.exportedAt) {
            if (snap.exportedAt <= 0) "未知时间"
            else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(snap.exportedAt))
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null; confirmReplace = false },
            title = { Text("恢复备份", style = AbType.headline, color = p.label) },
            text = {
                Text(
                    if (confirmReplace) {
                        "覆盖恢复会替换现有的全部账单，导入 ${snap.bills.size} 条账单。" +
                            (if (snap.history != null) "同时替换全部识别历史，导入 ${snap.history.size} 条历史。" else "这是不含历史的旧备份：保留现有识别历史，失效的账单关联会解除，已记账状态保留。") +
                            (if (snap.assets != null) "同时覆盖全部账户与资产流水，导入 ${snap.assets.accounts.size} 个账户、${snap.assets.entries.size} 条分录。显式空资产将清空当前资产。" else "此备份不含资产：保留当前余额与流水，导入账单不联动，恢复后请对账。") +
                            "钱迹导入身份${snap.imports?.let { "将覆盖为 ${it.size} 条（含删除标记）" } ?: "未包含，将保留本机身份"}。如备份含分类账户和设置，也会恢复这些配置。建议先导出当前备份；此操作不可撤销，确定吗？"
                    } else {
                        "备份时间：${whenText}\n账单：${snap.bills.size} 条\n" +
                            "识别历史：${snap.history?.let { "${it.size} 条" } ?: "旧格式未包含，保留本机历史"}\n" +
                            "资产：${snap.assets?.let { "${it.accounts.size} 个账户、${it.entries.size} 条分录" } ?: "旧格式未包含，保留本机资产；导入后请对账"}\n" +
                            "分类账户清单：${if (snap.ledger != null) "有" else "无"}\n" +
                            "记账周期：${snap.ledger?.monthStartDay?.let { "每月${it}日" } ?: "备份未包含，保留本机周期"}\n" +
                            "钱迹导入身份：${snap.imports?.let { "${it.size} 条（含删除标记）" } ?: "旧格式未包含，跨旧备份重复识别可能不完整"}\n" +
                            "默认合并账单与历史，已有已记账状态保留；分类账户和设置按备份恢复。"
                    },
                    style = AbType.body,
                    color = p.secondaryLabel,
                )
            },
            confirmButton = {
                if (confirmReplace) {
                    androidx.compose.material3.TextButton(onClick = {
                        pending = null
                        confirmReplace = false
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                BackupStore.restore(context, json, BackupStore.Mode.REPLACE)
                            }
                            message = result.fold(
                                onSuccess = { "已覆盖导入账单 ${it.bills} 条、识别历史 ${it.history} 条${if (snap.history == null) "（本机历史已保留）" else ""}" + (if (it.assets) "；资产已恢复" else "") + (it.assetWarning?.let { warning -> "\n$warning" } ?: "") },
                                onFailure = { "恢复失败：${it.message}" },
                            )
                            result.getOrNull()?.let { onRestored(it.settings) }
                            busy = false
                        }
                    }) { Text("确认覆盖", color = p.red) }
                } else {
                    androidx.compose.material3.TextButton(onClick = {
                        pending = null
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                BackupStore.restore(context, json, BackupStore.Mode.MERGE)
                            }
                            message = result.fold(
                                onSuccess = { "已合并导入账单 ${it.bills} 条、识别历史 ${it.history} 条（已有记录不重复导入）" + (if (it.assets) "；资产已恢复" else "") + (it.assetWarning?.let { warning -> "\n$warning" } ?: "") },
                                onFailure = { "恢复失败：${it.message}" },
                            )
                            result.getOrNull()?.let { onRestored(it.settings) }
                            busy = false
                        }
                    }) { Text("合并导入") }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (confirmReplace) {
                        confirmReplace = false
                    } else {
                        pending = null
                    }
                }) { Text(if (confirmReplace) "返回" else "取消", color = p.secondaryLabel) }
            },
        )
        // 覆盖是"先清空"的危险路径，单独给一个入口，不和合并挤在一起
        if (!confirmReplace) {
            androidx.compose.material3.TextButton(
                onClick = { confirmReplace = true },
                modifier = Modifier.padding(start = AbSpacing.page),
            ) { Text("改为覆盖恢复…", style = AbType.footnote, color = p.red) }
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：诊断
// ---------------------------------------------------------------------------

@Composable
private fun HelpPage(
    onBack: () -> Unit, onDebug: () -> Unit, onPageDebug: () -> Unit, onMode: () -> Unit,
    onPermissions: () -> Unit, onApps: () -> Unit, onPending: () -> Unit,
) {
    val p = LocalAbPalette.current
    SubPageScaffold(title = "识别帮助", onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(padding).padding(horizontal = AbSpacing.page)) {
            GroupHeader("识别排查")
            InsetGroup {
                ListRow(title = "通知诊断", subtitle = "通知事件、处理结论与脱敏样本",
                    leading = { com.example.autobook.ui.design.SettingsIconBadge("通知诊断", AbIcons.Search) },
                    chevron = true, onClick = onDebug)
                InsetDivider(inset = AbCardDensity.leadingInset)
                ListRow(title = "页面诊断", subtitle = "页面文字、识别结论与样本",
                    leading = { com.example.autobook.ui.design.SettingsIconBadge("页面诊断", AbIcons.Ocr) },
                    chevron = true, onClick = onPageDebug)
                InsetDivider(inset = AbCardDensity.leadingInset)
                ListRow(title = "识别模式", leading = { com.example.autobook.ui.design.SettingsIconBadge("识别模式", AbIcons.Accessibility) }, chevron = true, onClick = onMode)
                InsetDivider(inset = AbCardDensity.leadingInset)
                ListRow(title = "权限", leading = { com.example.autobook.ui.design.SettingsIconBadge("权限", AbIcons.Shield) }, chevron = true, onClick = onPermissions)
                InsetDivider(inset = AbCardDensity.leadingInset)
                ListRow(title = "应用与通道", leading = { com.example.autobook.ui.design.SettingsIconBadge("应用与通道", AbIcons.Store) }, chevron = true, onClick = onApps)
                InsetDivider(inset = AbCardDensity.leadingInset)
                ListRow(title = "待确认", leading = { com.example.autobook.ui.design.SettingsIconBadge("待确认", AbIcons.Clock) }, chevron = true, onClick = onPending)
            }
            GroupHeader("使用说明")
            InsetGroup {
                ListRow(title = "识别后需要确认", subtitle = "确认保存后才写入账本；关闭弹卡后可在待确认中处理。")
                InsetDivider()
                ListRow(title = "页面没有识别到？", subtitle = "先检查自动记账里的应用清单和权限，再打开支付结果或账单详情。仍有问题时先保存该页诊断样本，再切换页面。")
                InsetDivider()
                ListRow(title = "诊断样本分享前请检查", subtitle = "页面文字可能含商户、订单等信息，请先脱敏。")
            }
        }
    }
}

@Composable
private fun DebugPage(onBack: () -> Unit, notifications: Boolean) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val debug by DebugBus.state.collectAsStateWithLifecycle()
    val notification by NotificationIngress.latest.collectAsStateWithLifecycle()
    val recentNotifications by NotificationIngress.recent.collectAsStateWithLifecycle()
    val notificationSamples by NotificationIngress.sampleOptions.collectAsStateWithLifecycle()
    var notificationEventsExpanded by remember { mutableStateOf(false) }
    var notificationReading by remember { mutableStateOf(false) }
    var notificationReadResult by remember { mutableStateOf<String?>(null) }
    fun notificationTime(at: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(at))
    LaunchedEffect(Unit) { runCatching { NotificationIngress.restoreLatestDiagnostic(context) } }
    val scope = rememberCoroutineScope()
    var notificationPreview by remember { mutableStateOf<String?>(null) }
    var notificationSaved by remember { mutableStateOf<String?>(null) }
    var notificationSaving by remember { mutableStateOf(false) }
    var notificationExport by remember { mutableStateOf<String?>(null) }
    val notificationExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = notificationExport
        notificationExport = null
        if (uri == null || content == null) {
            notificationSaving = false
        } else scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                require(content.length <= 65_536)
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法打开所选文件")
            } }
            notificationSaved = if (result.isSuccess) "已保存到所选位置；可自行检查后提供样本。" else "保存失败：${result.exceptionOrNull()?.message}"
            notificationSaving = false
            notificationPreview = null
        }
    }
    var sampleSaved by remember { mutableStateOf<String?>(null) }

    SubPageScaffold(title = if (notifications) "通知诊断" else "页面诊断", onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page)) {
            if (notifications) {
            GroupHeader("最近通知输入")
            InsetGroup {
                val recent = notification
                if (recent == null) ListRow(title = "尚无通知输入", subtitle = "启用通知监听并授予系统通知使用权后，只记录所选应用的新通知诊断。")
                else {
                    ListRow(title = "应用", subtitle = recent.packageName)
                    InsetDivider()
                    ListRow(title = "接收时间", subtitle = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(recent.receivedAt)))
                    InsetDivider()
                    ListRow(title = "阶段与结论", subtitle = recent.outcome)
                    InsetDivider()
                    ListRow(title = "原因与元数据", subtitle = recent.summary)
                    InsetDivider()
                    ListRow(title = "事件标识", subtitle = recent.traceId)
                }
            }
            if (notification?.outcome?.contains("group-summary") == true) {
            }
            SecondaryButton(text = "读取当前支付通知（仅诊断）", enabled = !notificationReading,
                busy = notificationReading, busyLabel = "读取中…", onClick = {
                    if (!notificationReading) {
                        notificationReading = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { NotificationIngress.captureCurrentDiagnostics(context) }
                            }
                            notificationReadResult = result.fold(
                                onSuccess = { count -> "已读取 $count 条当前支付通知，仅更新诊断和可用样本，不自动记账、弹框、预览或保存。" },
                                onFailure = { "读取失败：${it.message ?: "请检查通知使用权和监听连接"}" },
                            )
                            notificationReading = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth())
            notificationReadResult?.let { GroupFooter(it) }
            InsetGroup {
                ListRow(title = "最近通知事件（${recentNotifications.size} / 20）",
                    subtitle = if (notificationEventsExpanded) "点击收起" else "点击展开应用、时间与处理结论",
                    chevron = true, onClick = { notificationEventsExpanded = !notificationEventsExpanded })
                if (notificationEventsExpanded) {
                    if (recentNotifications.isEmpty()) {
                        InsetDivider()
                        ListRow(title = "暂无事件", subtitle = "可等待新通知，或手动读取当前支付通知作诊断。")
                    } else recentNotifications.forEach { event ->
                        InsetDivider()
                        ListRow(title = event.packageName,
                            subtitle = "${notificationTime(event.receivedAt)} · ${event.outcome}" +
                                if (event.outcome.contains("group-summary")) " · 汇总跳过，子通知单独处理" else "")
                    }
                }
            }
            GroupHeader("可用通知样本（${notificationSamples.size} / 10）")
            InsetGroup {
                if (notificationSamples.isEmpty()) ListRow(title = "暂时没有可预览样本",
                    subtitle = "样本可能已过期，或通知没有可用正文；可读取当前支付通知作诊断。")
                else notificationSamples.forEachIndexed { index, option ->
                    if (index > 0) InsetDivider()
                    ListRow(title = option.packageName,
                        subtitle = "${notificationTime(option.receivedAt)} · ${option.outcome}" +
                            if (option.diagnosticOnly) (if (option.outcome.contains("group-summary")) " · 手动汇总样本，非单笔交易" else " · 手动读取，仅诊断") else " · 新通知输入",
                        chevron = true, onClick = {
                            val sample = NotificationIngress.sampleForExport(option.id)
                            if (sample == null) notificationSaved = "这条样本已过期或权限、来源配置已改变，未保存文件。"
                            else {
                                notificationPreview = sample
                                notificationSaved = null
                            }
                        })
                }
            }
            SecondaryButton(text = "查看最近有效脱敏样本", onClick = {
                val sample = NotificationIngress.sampleForExport()
                if (sample == null) notificationSaved = "当前没有有效的内存通知样本，尚未保存文件。"
                else {
                    notificationPreview = sample
                    notificationSaved = null
                }
            }, modifier = Modifier.fillMaxWidth())
            notificationSaved?.let { GroupFooter(it) }
            } else {
            if (debug == null) {
                EmptyState(
                    icon = AbIcons.Search,
                    title = "还没有识别到页面",
                    message = "打开一个账单详情页，识别结果会出现在这里。",
                )
            } else {
                GroupHeader("最近一次识别")
                InsetGroup {
                    ListRow(title = "应用", subtitle = debug!!.packageName)
                    InsetDivider()
                    ListRow(
                        title = "页面",
                        subtitle = debug!!.activityClass ?: "—",
                        dividerAbove = true,
                    )
                    InsetDivider()
                    ListRow(
                        title = "来源",
                        subtitle = debug!!.source,
                        dividerAbove = true,
                    )
                    InsetDivider()
                    ListRow(
                        title = "结论",
                        subtitle = debug!!.outcome,
                        dividerAbove = true,
                    )
                }
                GroupHeader("页面文本")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AbRadius.group))
                        .background(p.cardBg)
                        .height(240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(AbSpacing.l),
                ) {
                    Text(
                        debug!!.text,
                        style = AbType.mono,
                        color = p.secondaryLabel,
                    )
                }
                Spacer(Modifier.height(AbSpacing.m))
                SecondaryButton(
                    text = "复制页面文本",
                    onClick = { copy(context, debug!!.text) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AbSpacing.s))
                // 规则工作的基础设施：把这一屏变成能直接粘进 Samples.kt 的夹具
                SecondaryButton(
                    text = "复制为 Kotlin 夹具",
                    onClick = {
                        val items = SampleExport.splitLines(debug!!.text)
                        val name = SampleExport.toIdentifier(
                            debug!!.packageName.substringAfterLast('.').ifBlank { "sample" } + "Bill"
                        )
                        copy(context, SampleExport.kotlinFixture(name, items))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AbSpacing.s))
                SecondaryButton(
                    text = "保存样本到文件",
                    onClick = {
                        val saved = saveSample(context, debug!!)
                        sampleSaved = if (saved) "已保存到 files/samples/" else "保存失败"
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sampleSaved != null) {
                    GroupFooter(sampleSaved!!)
                }
            }
            }
        }
    }
    notificationPreview?.let { preview ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!notificationSaving) notificationPreview = null },
            title = { Text("通知脱敏样本预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                        Text(preview, style = AbType.mono, color = p.secondaryLabel)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = !notificationSaving, onClick = {
                    notificationSaving = true
                    notificationExport = preview
                    notificationExportLauncher.launch("AutoBook-notification-${System.currentTimeMillis()}.json")
                }) { Text(if (notificationSaving) "保存中…" else "保存到文件") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(enabled = !notificationSaving,
                    onClick = { notificationPreview = null }) { Text("取消") }
            },
        )
    }

}

// ---------------------------------------------------------------------------
// 平台小工具
// ---------------------------------------------------------------------------

/** 无障碍服务是否**真的连着**（不是"系统设置里打着勾"）。 */
private fun isAccessibilityEnabled(context: Context): Boolean =
    AccessibilityGuardian.isAccessibilityEnabled(context)

/** 系统设置里是否勾着本 App 的无障碍服务（可能勾着但没连上）。 */
private fun isAccessibilityChecked(context: Context): Boolean =
    AccessibilityGuardian.isEnabledInSettings(context)

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 厂商自启动页解析不到时的兜底：直接进本 App 的系统详情页 */
private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * 短信权限是否已授予。
 *
 * 只查 RECEIVE_SMS：短信通道只接收新短信，不读历史库，所以不需要 READ_SMS。
 * 权限被用户在系统里撤销后，开关仍然开着但通道是哑的 —— UI 必须能看出这个差别。
 */
private fun hasSmsPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** 通知被用户在系统里关掉时，直接跳到本 App 的通知设置页 */
private fun openNotificationChannelSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * 把一屏页面文本存成 `files/samples/<包名>-<时间戳>.txt`。
 *
 * 就存应用私有目录：不用申请存储权限、卸载即清，用户想取出来时走「复制为 Kotlin 夹具」
 * 或后面要做的「导出诊断」即可。这里返回 false 只是为了让 UI 能显示"没存上"。
 */
private fun saveSample(context: Context, debug: DebugBus.State): Boolean = runCatching {
    val dir = java.io.File(context.filesDir, "samples").apply { mkdirs() }
    com.example.autobook.data.SampleIndex.save(dir, debug)
    true
}.getOrDefault(false)

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("autobook", text))
}

/** Explicitly confirmed, already redacted preview only; never called by the listener itself. */
