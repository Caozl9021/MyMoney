package io.github.mymoney.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.billkit.BillKit
import com.example.billkit.DebugBus
import io.github.mymoney.capture.AccessibilityEnhancer
import io.github.mymoney.capture.AccessibilityGuardian
import io.github.mymoney.capture.BillAccessibilityService
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.capture.ConfirmDelivery
import io.github.mymoney.data.Backup
import io.github.mymoney.data.BackupStore
import io.github.mymoney.data.LocalRuleStore
import io.github.mymoney.data.RecognitionStatus
import io.github.mymoney.data.RecognitionStore
import io.github.mymoney.data.SampleExport
import io.github.mymoney.data.SampleIndex
import io.github.mymoney.data.Settings
import io.github.mymoney.export.CostShare
import io.github.mymoney.export.ExportRange
import io.github.mymoney.overlay.OverlayAnchor
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置页内部的页面栈（手写，不引 navigation-compose）。 */
private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    LEDGER_PERIOD("记账周期"),
    CATEGORIES("分类"),
    EXPENSE_CATEGORIES("支出分类"),
    INCOME_CATEGORIES("收入分类"),
    CATEGORY_PRESETS("分类预设"),
    ASSETS("资产"),
    PENDING("记录历史"),
    AUTOMATION("自动记账"),
    BACKGROUND("后台保活"),
    APPS("监控应用"),
    LEARNING("学习记忆"),
    MEALS("餐段设置"),
    NOTE("备注"),
    NOTE_TEMPLATE("备注模板"),
    IMPORT_EXPORT("迁移与备份"),
    QIANJI_IMPORT("钱迹账单"),
    EXPORT_CSV("导出到 iCost"),
    EXPORT_HELP("导入说明"),
    CLOUD_BACKUP("云备份"),
    LEDGER_BACKUPS("历史备份"),
    CLOUD_BACKUP_MANAGE("连接管理"),
    BACKUP("本地备份文件"),
    APPEARANCE("外观"),
    LIQUID_GLASS("液态玻璃"),
    WIDGETS("桌面小组件"),
    ABOUT("关于"),
    PAGE_DEBUG("页面诊断"),
}

private fun captureSettingsPage(destination: CaptureSettingsDestination): SettingsPage = when (destination) {
    CaptureSettingsDestination.AUTOMATION -> SettingsPage.AUTOMATION
}

private fun automationPageFor(route: AutomationRoute): SettingsPage = when (route) {
    AutomationRoute.BACKGROUND -> SettingsPage.BACKGROUND
    AutomationRoute.APPS -> SettingsPage.APPS
    AutomationRoute.LEARNING -> SettingsPage.LEARNING
    AutomationRoute.MEALS -> SettingsPage.MEALS
}

@Composable
fun SettingsScreen(
    rootRequest: Long? = null,
    onRootRequestHandled: (Long) -> Unit = {},
    destination: CaptureSettingsDestination? = null,
    onDestinationHandled: () -> Unit = {},
    onEditorVisibilityChanged: (Boolean) -> Unit = {},
    onReturnToOrigin: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    var pagePath by rememberSaveable { mutableStateOf(listOf(SettingsPage.ROOT)) }
    val page = pagePath.last()
    var predictiveSnapEpoch by remember { mutableIntStateOf(0) }
    val pageTransition = key(predictiveSnapEpoch) { updateTransition(pagePath, label = "settingsRoute") }
    val visibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    var historyDetailVisible by remember { mutableStateOf(false) }
    LaunchedEffect(page, historyDetailVisible) {
        visibilityChanged(page == SettingsPage.ASSETS || (page == SettingsPage.PENDING && historyDetailVisible))
    }
    DisposableEffect(Unit) { onDispose { visibilityChanged(false) } }

    val navigationScope = rememberCoroutineScope()
    val backPixels = RememberBackPixels()
    val previews = remember { mutableMapOf<SettingsPage, androidx.compose.ui.graphics.ImageBitmap>() }
    var navigating by remember { mutableStateOf(false) }
    var navigationRevision by remember { mutableIntStateOf(0) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val rootExitGate = remember { SettingsRootExitGate() }

    fun invalidateNavigation() {
        navigationRevision++
        navigationJob?.cancel()
        navigationJob = null
        navigating = false
        rootExitGate.invalidate()
    }

    val predictiveEnabled = LocalPredictiveBackEnabled.current
    LaunchedEffect(predictiveEnabled) { previews.clear() }
    var directEntry by rememberSaveable { mutableStateOf(false) }

    fun navigate(target: SettingsPage) {
        if (navigating || target == page) return
        rootExitGate.invalidate()
        if (!predictiveEnabled) {
            navigationRevision++
            previews.keys.retainAll(pagePath.toSet())
            pagePath = pagePath + target
            return
        }
        val ticket = ++navigationRevision
        navigating = true
        val origin = pagePath
        navigationJob = navigationScope.launch {
            try {
                snapshotFlow { !pageTransition.isRunning && pageTransition.currentState == pageTransition.targetState }.first { it }
                withFrameNanos { }
                if (pagePath != origin || navigationRevision != ticket) return@launch
                backPixels.snapshot()?.let { previews[origin.last()] = it }
                if (pagePath != origin || navigationRevision != ticket) return@launch
                previews.keys.retainAll(pagePath.toSet())
                pagePath = pagePath + target
            } finally {
                if (navigationRevision == ticket) navigating = false
            }
        }
    }

    fun returnToParent() {
        if (navigating) return
        rootExitGate.invalidate()
        if (directEntry && pagePath.size <= 2) {
            directEntry = false
            pagePath = listOf(SettingsPage.ROOT)
            onReturnToOrigin()
        } else {
            pagePath = pagePath.dropLast(1).ifEmpty { listOf(SettingsPage.ROOT) }
        }
    }

    LaunchedEffect(rootRequest, destination) {
        if (rootRequest != null) {
            onRootRequestHandled(rootRequest)
            if (destination != null) onDestinationHandled()
            invalidateNavigation()
            rootExitGate.request {
                invalidateNavigation()
                directEntry = false
                previews.clear()
                pagePath = listOf(SettingsPage.ROOT)
            }
        } else destination?.let {
            invalidateNavigation()
            directEntry = true
            pagePath = listOf(SettingsPage.ROOT, captureSettingsPage(it))
            onDestinationHandled()
        }
    }

    val healthInput = rememberCaptureHealthInput()
    val health = captureHealth(healthInput)
    fun handleHealthAction(action: CaptureAction) {
        performCaptureAction(
            context = context,
            action = action,
            onSettings = { target -> navigate(captureSettingsPage(target)) },
            onPending = { navigate(SettingsPage.PENDING) },
        )
    }

    val captureState by CaptureModeManager.state.collectAsStateWithLifecycle()
    val ruleState by LocalRuleStore.state.collectAsStateWithLifecycle()
    val history by RecognitionStore.records.collectAsStateWithLifecycle()
    val historyReady by RecognitionStore.ready.collectAsStateWithLifecycle()
    val pendingCount = history.count { it.status == RecognitionStatus.PENDING }
    val enabled = captureState.enabled

    var autoConfirm by remember { mutableStateOf(settings.autoConfirm) }
    LaunchedEffect(healthInput.autoConfirm) { autoConfirm = healthInput.autoConfirm }
    var islandConfirm by remember { mutableStateOf(settings.islandConfirmEnabled) }
    var whitelist by remember { mutableStateOf(settings.whitelist) }
    var overlayGranted by remember { mutableStateOf(OverlayAnchor.canShow(context)) }
    var notifyGranted by remember { mutableStateOf(ConfirmDelivery.hasNotificationPermission(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = OverlayAnchor.canShow(context)
                notifyGranted = ConfirmDelivery.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun applyKit() {
        BillKit.updateConfig(settings.toKitConfig())
        BillAccessibilityService.instance?.refreshServiceInfo()
    }

    val back: () -> Unit = { returnToParent() }
    val reduceMotion = LocalReduceMotion.current
    val pageStates = rememberSaveableStateHolder()
    val contentReady = !pageTransition.isRunning && pageTransition.currentState == pagePath && pageTransition.targetState == pagePath

    BackSurface(
        enabled = page != SettingsPage.ROOT && !(page == SettingsPage.PENDING && historyDetailVisible),
        onBack = ::returnToParent,
        routeKey = pagePath,
        contentReady = contentReady,
        preview = previews[pagePath.dropLast(1).lastOrNull()],
        pixels = backPixels,
        onPredictiveCommit = { predictiveSnapEpoch++ },
    ) {
        val predictiveCommit = LocalPredictiveBackCommit.current
        key(predictiveSnapEpoch) {
            pageTransition.AnimatedContent(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                transitionSpec = {
                    liquidNavigationTransition(
                        goingBack = targetState.size < initialState.size,
                        reduceMotion = reduceMotion,
                        predictiveCommit = predictiveCommit,
                    )
                },
            ) { shownPath ->
            val shown = shownPath.last()
            CompositionLocalProvider(
                LocalBackActive provides (shownPath == pagePath),
                LocalPageBackManaged provides true,
                LocalSettingsRootExitGate provides rootExitGate,
            ) {
                pageStates.SaveableStateProvider(shown) {
                    when (shown) {
                        SettingsPage.ROOT -> PageScaffold(title = "设置", listState = listState, floatingHeader = true) { padding ->
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(
                                    start = AbSpacing.page,
                                    end = AbSpacing.page,
                                    top = padding.calculateTopPadding(),
                                    bottom = padding.calculateBottomPadding(),
                                ),
                                verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap),
                            ) {
                                item {
                                    SettingsNavigationGroup(
                                        title = "账本",
                                        links = listOf(
                                            SettingsLink("记账周期", AbIcons.Clock, trailingText = "每月${rememberMonthStartDay()}日") {
                                                navigate(SettingsPage.LEDGER_PERIOD)
                                            },
                                            SettingsLink("分类", AbIcons.Tag) { navigate(SettingsPage.CATEGORIES) },
                                            SettingsLink("资产", AbIcons.Wallet) { navigate(SettingsPage.ASSETS) },
                                            SettingsLink(
                                                "记录历史",
                                                AbIcons.Clock,
                                                trailingText = if (historyReady && pendingCount > 0) "$pendingCount 待处理" else null,
                                            ) { navigate(SettingsPage.PENDING) },
                                        ),
                                    )
                                }
                                item {
                                    SettingsNavigationGroup(
                                        title = "自动记账",
                                        links = listOf(
                                            SettingsLink("自动记账", AbIcons.Accessibility, trailingText = health.shortStatus) {
                                                navigate(SettingsPage.AUTOMATION)
                                            },
                                            SettingsLink("备注", AbIcons.Note) { navigate(SettingsPage.NOTE) },
                                        ),
                                    )
                                }
                                item {
                                    SettingsNavigationGroup(
                                        title = "数据与应用",
                                        links = listOf(
                                            SettingsLink("迁移与备份", AbIcons.Export) { navigate(SettingsPage.IMPORT_EXPORT) },
                                            SettingsLink("外观", AbIcons.Settings) { navigate(SettingsPage.APPEARANCE) },
                                            SettingsLink("关于", AbIcons.Info, trailingText = appVersion.ifBlank { null }) {
                                                navigate(SettingsPage.ABOUT)
                                            },
                                        ),
                                    )
                                }
                            }
                        }

                        SettingsPage.LEDGER_PERIOD -> LedgerPeriodPage(back)

                        SettingsPage.AUTOMATION -> AutomationPage(
                            enabled = enabled,
                            health = health,
                            autoConfirm = autoConfirm,
                            islandConfirm = islandConfirm,
                            islandSupported = Build.VERSION.SDK_INT >= 26,
                            accessibilityAuthorized = healthInput.accessibilityAuthorized,
                            accessibilityBound = healthInput.accessibilityBound,
                            overlayGranted = overlayGranted,
                            appCount = whitelist.size,
                            learnedCount = ruleState.learned.size,
                            onBack = back,
                            onAction = ::handleHealthAction,
                            onToggleEnabled = { CaptureModeManager.setEnabled(context, it) },
                            onToggleConfirm = { autoConfirm = it; settings.autoConfirm = it },
                            onToggleIsland = { islandConfirm = it; settings.islandConfirmEnabled = it },
                            onNavigate = { navigate(automationPageFor(it)) },
                        )

                        SettingsPage.BACKGROUND -> BackgroundKeepAlivePage(
                            notifyGranted = notifyGranted,
                            onNotifyGranted = { notifyGranted = it },
                            onBack = back,
                        )

                        SettingsPage.APPS, SettingsPage.LEARNING, SettingsPage.MEALS -> RecognitionRulesPage(
                            section = when (shown) {
                                SettingsPage.LEARNING -> RecognitionRuleSection.LEARNING
                                SettingsPage.MEALS -> RecognitionRuleSection.MEALS
                                else -> RecognitionRuleSection.APPS
                            },
                            whitelist = whitelist,
                            onBack = back,
                            onToggleApp = { pkg ->
                                settings.setSupported(pkg, pkg !in whitelist)
                                whitelist = settings.whitelist
                                applyKit()
                            },
                        )

                        SettingsPage.NOTE -> NoteFillRulesPage(
                            onBack = back,
                            onOpenTemplate = { navigate(SettingsPage.NOTE_TEMPLATE) },
                        )
                        SettingsPage.NOTE_TEMPLATE -> NoteTemplatePage(onBack = back)

                        SettingsPage.IMPORT_EXPORT -> SettingsMenuPage(
                            title = "迁移与备份",
                            onBack = back,
                            groups = listOf(
                                "备份与迁移" to listOf(
                                    SettingsLink("云备份", AbIcons.Layers) { navigate(SettingsPage.CLOUD_BACKUP) },
                                    SettingsLink("钱迹账单", AbIcons.Export) { navigate(SettingsPage.QIANJI_IMPORT) },
                                    SettingsLink("导出到 iCost", AbIcons.Export) { navigate(SettingsPage.EXPORT_CSV) },
                                    SettingsLink("本地备份文件", AbIcons.Layers) { navigate(SettingsPage.BACKUP) },
                                ),
                            ),
                        )

                        SettingsPage.CATEGORIES -> SettingsMenuPage(
                            title = "分类",
                            onBack = back,
                            groups = listOf(
                                "收支分类" to listOf(
                                    SettingsLink("支出分类", AbIcons.Tag) { navigate(SettingsPage.EXPENSE_CATEGORIES) },
                                    SettingsLink("收入分类", AbIcons.Tag) { navigate(SettingsPage.INCOME_CATEGORIES) },
                                    SettingsLink("分类预设", AbIcons.Layers) { navigate(SettingsPage.CATEGORY_PRESETS) },
                                ),
                            ),
                        )
                        SettingsPage.EXPENSE_CATEGORIES -> LedgerConfigPage(onBack = back, fixedTab = 0)
                        SettingsPage.INCOME_CATEGORIES -> LedgerConfigPage(onBack = back, fixedTab = 1)
                        SettingsPage.CATEGORY_PRESETS -> LedgerConfigPage(onBack = back, presetsOnly = true)

                        SettingsPage.ASSETS -> AssetsScreen(onBack = back)
                        SettingsPage.PENDING -> RecognitionHistoryScreen(
                            onBack = back,
                            onDetailVisibilityChanged = { historyDetailVisible = it },
                        )

                        SettingsPage.CLOUD_BACKUP, SettingsPage.LEDGER_BACKUPS, SettingsPage.CLOUD_BACKUP_MANAGE -> CloudBackupPage(
                            destination = when (shown) {
                                SettingsPage.LEDGER_BACKUPS -> CloudBackupDestination.HISTORY
                                SettingsPage.CLOUD_BACKUP_MANAGE -> CloudBackupDestination.CONNECTION
                                else -> CloudBackupDestination.HOME
                            },
                            onBack = back,
                            onOpenHistory = { navigate(SettingsPage.LEDGER_BACKUPS) },
                            onOpenConnection = { navigate(SettingsPage.CLOUD_BACKUP_MANAGE) },
                            onOpenLocalFiles = { navigate(SettingsPage.BACKUP) },
                        )

                        SettingsPage.QIANJI_IMPORT -> QianjiImportPage(back)
                        SettingsPage.EXPORT_CSV -> ExportPage(
                            onBack = back,
                            onOpenHelp = { navigate(SettingsPage.EXPORT_HELP) },
                        )
                        SettingsPage.EXPORT_HELP -> SettingsContentPage("导入说明", back) {
                            InsetGroup {
                                ListRow(title = "1 · 对齐名称", subtitle = "分类和账户名需与 iCost 对应，转账另一账户在 iCost 内补选。")
                                ListRow(title = "2 · 导出分享", subtitle = "选择周期范围生成 CSV 并分享到 iCost。")
                                ListRow(title = "3 · 退款限制", subtitle = "iCost 不支持退款，含退款的范围请改用完整备份。")
                            }
                        }

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

                        SettingsPage.APPEARANCE -> AppearancePage(
                            onBack = back,
                            onOpenLiquidGlass = { navigate(SettingsPage.LIQUID_GLASS) },
                            onOpenWidgets = { navigate(SettingsPage.WIDGETS) },
                        )
                        SettingsPage.LIQUID_GLASS -> LiquidGlassSettingsPage(onBack = back)
                        SettingsPage.WIDGETS -> WidgetsSettingsPage(onBack = back)
                        SettingsPage.ABOUT -> AppUpdatePage(back, onOpenDebug = { navigate(SettingsPage.PAGE_DEBUG) })
                        SettingsPage.PAGE_DEBUG -> PageDebugPage(onBack = back)
                    }
                }
            }
        }
        }
    }
}

// ---------------------------------------------------------------------------
// 后台保活
// ---------------------------------------------------------------------------

@Composable
private fun BackgroundKeepAlivePage(
    notifyGranted: Boolean,
    onNotifyGranted: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        onNotifyGranted(ConfirmDelivery.hasNotificationPermission(context))
    }

    var keepAlive by remember { mutableStateOf<AccessibilityEnhancer.KeepAliveStatus?>(null) }
    LaunchedEffect(Unit) { keepAlive = AccessibilityEnhancer.getStatus(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keepAlive = AccessibilityEnhancer.getStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SubPageScaffold(title = "后台保活", onBack = onBack) { padding ->
        SectionColumn(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
        ) {
            InsetGroup {
                ListRow(
                    title = "守护服务",
                    trailing = {
                        Text(
                            text = when {
                                keepAlive?.guardianRunning == true -> "运行中"
                                keepAlive?.accessibilityEnabled == true -> "未运行"
                                keepAlive?.accessibilityChecked == true -> "未连接"
                                else -> "未开启"
                            },
                            style = AbType.footnote,
                            color = p.secondaryLabel,
                        )
                    },
                    chevron = true,
                    onClick = {
                        if (AccessibilityGuardian.shouldRun(context)) {
                            AccessibilityGuardian.ensureRunning(context)
                        }
                        if (!AccessibilityGuardian.isAccessibilityEnabled(context)) {
                            openAccessibilitySettings(context)
                        }
                        keepAlive = AccessibilityEnhancer.getStatus(context)
                    },
                )
                ListRow(
                    title = "电池优化",
                    trailing = {
                        Text(
                            text = when (keepAlive?.batteryOptimizationIgnored) {
                                true -> "已豁免"
                                false -> "未豁免"
                                null -> "读取中"
                            },
                            style = AbType.footnote,
                            color = p.secondaryLabel,
                        )
                    },
                    chevron = true,
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
                        title = if (manufacturer.hasSpecialSettings) "自启动（${manufacturer.name}）" else "自启动",
                        trailing = {
                            Text(
                                text = if (manufacturer.hasSpecialSettings) "去设置" else "无需设置",
                                style = AbType.footnote,
                                color = p.secondaryLabel,
                            )
                        },
                        chevron = manufacturer.hasSpecialSettings,
                        onClick = {
                            val intent = AccessibilityEnhancer.resolveAutoStartIntent(context, manufacturer)
                            if (intent != null) {
                                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            } else {
                                openAppDetailsSettings(context)
                            }
                        },
                    )
                }
                ListRow(
                    title = "待确认通知",
                    trailing = {
                        Text(
                            text = if (notifyGranted) "已允许" else "未允许",
                            style = AbType.footnote,
                            color = p.secondaryLabel,
                        )
                    },
                    chevron = true,
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
        }
    }
}

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------

@Composable
private fun ExportPage(onBack: () -> Unit, onOpenHelp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = LocalAbPalette.current
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
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
                onSuccess = { n -> if (n > 0) "已导出 $n 条" else "暂无记录" },
                onFailure = { error -> error.message ?: "导出失败" },
            )
            busy = false
        }
    }

    SubPageScaffold(title = "导出到 iCost", onBack = onBack) { padding ->
        SectionColumn(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
        ) {
            GroupHeader("范围")
            InsetGroup {
                androidx.compose.foundation.layout.Column(Modifier.padding(AbSpacing.m)) {
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

            InsetGroup {
                ListRow(
                    title = "导出${ExportRange.label(kind, monthStartDay)}",
                    subtitle = if (busy) "生成中…" else null,
                    chevron = true,
                    onClick = { run { CostShare.shareRange(context, kind, today = today, monthStartDay = monthStartDay, zone = exportClock.zone) } },
                )
                ListRow(
                    title = "最近 50 条",
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
                ListRow(title = "导入说明", chevron = true, onClick = onOpenHelp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 外观
// ---------------------------------------------------------------------------

@Composable
private fun AppearancePage(
    onBack: () -> Unit,
    onOpenLiquidGlass: () -> Unit,
    onOpenWidgets: () -> Unit,
) {
    SubPageScaffold(title = "外观", onBack = onBack) { padding ->
        SectionColumn(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
        ) {
            val context = LocalContext.current
            val revision = appearanceRevision()
            val prefs = remember(revision) { Appearance.prefs(context) }
            val p = LocalAbPalette.current
            ThemeColorPicker()
            GroupHeader("明暗模式")
            Segmented(
                listOf("跟随系统", "浅色", "深色"),
                listOf("SYSTEM", "LIGHT", "DARK").indexOf(prefs.getString("theme", "SYSTEM")).coerceAtLeast(0),
                { index ->
                    prefs.edit().putString("theme", listOf("SYSTEM", "LIGHT", "DARK")[index]).apply()
                    io.github.mymoney.systems.SystemIntegration.refresh(context)
                },
            )
            GroupHeader("文字大小")
            Segmented(
                listOf("标准", "大", "特大"),
                listOf("STANDARD", "LARGE", "EXTRA_LARGE").indexOf(prefs.getString("text_size", "STANDARD")).coerceAtLeast(0),
                { index -> prefs.edit().putString("text_size", listOf("STANDARD", "LARGE", "EXTRA_LARGE")[index]).apply() },
            )
            val liquidGlass = prefs.getBoolean(Appearance.LIQUID_GLASS, Appearance.DEFAULT_LIQUID_GLASS)
            val reduceTransparency = LocalReduceTransparency.current
            InsetGroup {
                ListRow(
                    title = "降低动效",
                    trailing = {
                        AbSwitch(prefs.getBoolean("reduce_motion", false)) {
                            prefs.edit().putBoolean("reduce_motion", it).apply()
                        }
                    },
                )
                ListRow(
                    title = "预测性返回",
                    trailing = {
                        AbSwitch(prefs.getBoolean(Appearance.PREDICTIVE_BACK, Appearance.DEFAULT_PREDICTIVE_BACK)) {
                            prefs.edit().putBoolean(Appearance.PREDICTIVE_BACK, it).apply()
                        }
                    },
                )
                ListRow(
                    title = "液态玻璃",
                    chevron = true,
                    trailing = {
                        Text(
                            text = when {
                                reduceTransparency -> "系统关闭"
                                liquidGlass -> "已开启"
                                else -> "已关闭"
                            },
                            style = AbType.footnote,
                            color = p.secondaryLabel,
                        )
                    },
                    onClick = onOpenLiquidGlass,
                )
                ListRow(
                    title = "桌面小组件",
                    chevron = true,
                    onClick = onOpenWidgets,
                )
            }
        }
    }
}

@Composable
private fun WidgetsSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val manager = remember(context) { android.appwidget.AppWidgetManager.getInstance(context) }
    val pinSupported = remember(manager) { manager.isRequestPinAppWidgetSupported }
    val widgetPrefs = remember(context) { io.github.mymoney.systems.WidgetPrefs.prefs(context) }
    var surfaceStyle by remember { mutableStateOf(io.github.mymoney.systems.WidgetThemeTokens.defaultSurfaceStyle(context)) }

    fun pin(providerClass: Class<*>, label: String) {
        if (!pinSupported) {
            ReimburseFeedback.show("当前桌面不支持直接添加，请回到桌面长按空白处添加「$label」")
            return
        }
        val component = android.content.ComponentName(context, providerClass)
        val ok = runCatching { manager.requestPinAppWidget(component, null, null) }.getOrDefault(false)
        if (!ok) {
            ReimburseFeedback.show("请回到桌面长按空白处添加「$label」")
        }
    }

    SubPageScaffold(title = "桌面小组件", onBack = onBack) { padding ->
        SectionColumn(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
        ) {
            GroupHeader("默认材质")
            Segmented(
                options = io.github.mymoney.systems.WidgetSurfaceStyle.entries.map { it.title },
                selected = io.github.mymoney.systems.WidgetSurfaceStyle.entries.indexOf(surfaceStyle),
                onSelect = { idx ->
                    val next = io.github.mymoney.systems.WidgetSurfaceStyle.entries[idx]
                    surfaceStyle = next
                    io.github.mymoney.systems.WidgetPrefs.setDefaultSurface(widgetPrefs, next)
                    io.github.mymoney.systems.SystemIntegration.refresh(context)
                },
            )

            data class WidgetSpec(
                val title: String,
                val cls: Class<*>,
            )
            val specs = remember {
                listOf(
                    WidgetSpec("极速记账", io.github.mymoney.systems.QuickEntryWidget::class.java),
                    WidgetSpec("七日支出趋势", io.github.mymoney.systems.SevenDayExpenseWidget::class.java),
                    WidgetSpec("资产与报销看板", io.github.mymoney.systems.AssetReimburseWidget::class.java),
                    WidgetSpec("本期支出", io.github.mymoney.systems.MonthlyExpenseWidget::class.java),
                )
            }

            GroupHeader("添加到桌面")
            InsetGroup {
                specs.forEach { spec ->
                    ListRow(
                        title = spec.title,
                        trailing = {
                            Text(
                                text = if (pinSupported) "添加" else "说明",
                                style = AbType.footnote,
                                color = p.accent,
                            )
                        },
                        onClick = { pin(spec.cls, spec.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassSettingsPage(onBack: () -> Unit) {
    SubPageScaffold(title = "液态玻璃", onBack = onBack) { padding ->
        SectionColumn(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
        ) {
            val context = LocalContext.current
            val revision = appearanceRevision()
            val prefs = remember(revision) { Appearance.prefs(context) }
            val p = LocalAbPalette.current
            val liquidGlass = prefs.getBoolean(Appearance.LIQUID_GLASS, Appearance.DEFAULT_LIQUID_GLASS)
            val clarityPref = prefs.getFloat(Appearance.LIQUID_GLASS_CLARITY, Appearance.DEFAULT_LIQUID_GLASS_CLARITY).coerceIn(0f, 1f)
            val perZonePref = prefs.getBoolean(Appearance.LIQUID_GLASS_PER_ZONE_CLARITY, false)
            val dockPref = prefs.getFloat(Appearance.CLARITY_DOCK, clarityPref).coerceIn(0f, 1f)
            val headerPref = prefs.getFloat(Appearance.CLARITY_HEADER, clarityPref).coerceIn(0f, 1f)
            val sheetPref = prefs.getFloat(Appearance.CLARITY_SHEET, clarityPref).coerceIn(0f, 1f)
            val dialogPref = prefs.getFloat(Appearance.CLARITY_DIALOG, clarityPref).coerceIn(0f, 1f)
            var clarity by remember(clarityPref) { mutableFloatStateOf(clarityPref) }
            var dockClarity by remember(dockPref) { mutableFloatStateOf(dockPref) }
            var headerClarity by remember(headerPref) { mutableFloatStateOf(headerPref) }
            var sheetClarity by remember(sheetPref) { mutableFloatStateOf(sheetPref) }
            var dialogClarity by remember(dialogPref) { mutableFloatStateOf(dialogPref) }
            var previewText by remember { mutableStateOf("液态玻璃通透效果") }
            var previewZoneClarity by remember(clarityPref, perZonePref, headerPref) {
                mutableFloatStateOf(if (perZonePref) headerPref else clarityPref)
            }
            var previewBaseLight by remember { mutableFloatStateOf(0.32f) }
            var previewBaseDark by remember { mutableFloatStateOf(0.44f) }
            var previewBaseBlurDp by remember { mutableFloatStateOf(4.5f) }
            val reduceTransparency = LocalReduceTransparency.current

            fun toggleLiquidGlass(enabled: Boolean) {
                prefs.edit().putBoolean(Appearance.LIQUID_GLASS, enabled).apply()
                io.github.mymoney.systems.SystemIntegration.refresh(context)
            }
            fun togglePerZone(enabled: Boolean) {
                val editor = prefs.edit().putBoolean(Appearance.LIQUID_GLASS_PER_ZONE_CLARITY, enabled)
                if (enabled && !prefs.contains(Appearance.CLARITY_DOCK)) {
                    dockClarity = clarity
                    headerClarity = clarity
                    sheetClarity = clarity
                    dialogClarity = clarity
                    editor.putFloat(Appearance.CLARITY_DOCK, clarity)
                        .putFloat(Appearance.CLARITY_HEADER, clarity)
                        .putFloat(Appearance.CLARITY_SHEET, clarity)
                        .putFloat(Appearance.CLARITY_DIALOG, clarity)
                }
                editor.apply()
            }
            val reduceMotion = LocalReduceMotion.current

            InsetGroup {
                ListRow(
                    title = "液态玻璃",
                    onClick = { toggleLiquidGlass(!liquidGlass) },
                    trailing = {
                        AbSwitch(liquidGlass) { toggleLiquidGlass(it) }
                    },
                )
            }

            AnimatedVisibility(
                visible = liquidGlass && !reduceTransparency,
                enter = if (reduceMotion) fadeIn(tween(140, easing = AbEasing.EaseOut))
                else expandVertically(AbSpring.morphSize(false)) + fadeIn(tween(150, easing = AbEasing.EaseOut)),
                exit = if (reduceMotion) fadeOut(tween(110, easing = AbEasing.EaseOut))
                else shrinkVertically(AbSpring.morphSize(false)) + fadeOut(tween(100, easing = AbEasing.EaseOut)),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap),
                ) {
                    InsetGroup {
                        ClaritySliderRow(
                            label = if (perZonePref) "全局基准" else "通透度",
                            value = clarity,
                            contentDescription = "液态玻璃通透度",
                            onValueChange = { next ->
                                clarity = next
                                previewText = "液态玻璃通透效果"
                                previewZoneClarity = next
                                previewBaseLight = 0.32f
                                previewBaseDark = 0.44f
                                previewBaseBlurDp = 4.5f
                                val editor = prefs.edit().putFloat(Appearance.LIQUID_GLASS_CLARITY, next)
                                if (perZonePref) {
                                    dockClarity = next
                                    headerClarity = next
                                    sheetClarity = next
                                    dialogClarity = next
                                    editor.putFloat(Appearance.CLARITY_DOCK, next)
                                        .putFloat(Appearance.CLARITY_HEADER, next)
                                        .putFloat(Appearance.CLARITY_SHEET, next)
                                        .putFloat(Appearance.CLARITY_DIALOG, next)
                                }
                                editor.apply()
                            },
                        )
                        ClarityLivePreviewStrip(
                            clarity = if (perZonePref) previewZoneClarity else clarity,
                            label = previewText,
                            baseLight = previewBaseLight,
                            baseDark = previewBaseDark,
                            baseBlurDp = previewBaseBlurDp,
                        )
                        ListRow(
                            title = "分区域单独调节",
                            onClick = { togglePerZone(!perZonePref) },
                            trailing = {
                                AbSwitch(perZonePref) { togglePerZone(it) }
                            },
                        )
                    }

                    AnimatedVisibility(
                        visible = perZonePref,
                        enter = if (reduceMotion) fadeIn(tween(140, easing = AbEasing.EaseOut))
                        else expandVertically(AbSpring.morphSize(false)) + fadeIn(tween(150, easing = AbEasing.EaseOut)),
                        exit = if (reduceMotion) fadeOut(tween(110, easing = AbEasing.EaseOut))
                        else shrinkVertically(AbSpring.morphSize(false)) + fadeOut(tween(100, easing = AbEasing.EaseOut)),
                    ) {
                        InsetGroup {
                            ClaritySliderRow(
                                label = "底部导航栏",
                                value = dockClarity,
                                contentDescription = "底部导航栏通透度",
                                onValueChange = { next ->
                                    dockClarity = next
                                    previewText = "底部导航栏"
                                    previewZoneClarity = next
                                    previewBaseLight = 0.42f
                                    previewBaseDark = 0.22f
                                    previewBaseBlurDp = 4.5f
                                    prefs.edit().putFloat(Appearance.CLARITY_DOCK, next).apply()
                                },
                            )
                            ClaritySliderRow(
                                label = "页面悬浮元素",
                                value = headerClarity,
                                contentDescription = "页面悬浮元素通透度",
                                onValueChange = { next ->
                                    headerClarity = next
                                    previewText = "页面悬浮元素"
                                    previewZoneClarity = next
                                    previewBaseLight = 0.32f
                                    previewBaseDark = 0.44f
                                    previewBaseBlurDp = 4.5f
                                    prefs.edit().putFloat(Appearance.CLARITY_HEADER, next).apply()
                                },
                            )
                            ClaritySliderRow(
                                label = "底部弹出卡",
                                value = sheetClarity,
                                contentDescription = "底部弹出卡通透度",
                                onValueChange = { next ->
                                    sheetClarity = next
                                    previewText = "底部弹出卡"
                                    previewZoneClarity = next
                                    previewBaseLight = 0.72f
                                    previewBaseDark = 0.70f
                                    previewBaseBlurDp = 16f
                                    prefs.edit().putFloat(Appearance.CLARITY_SHEET, next).apply()
                                },
                            )
                            ClaritySliderRow(
                                label = "弹窗与浮出菜单",
                                value = dialogClarity,
                                contentDescription = "弹窗与菜单通透度",
                                onValueChange = { next ->
                                    dialogClarity = next
                                    previewText = "弹窗与浮出菜单"
                                    previewZoneClarity = next
                                    previewBaseLight = 0.86f
                                    previewBaseDark = 0.84f
                                    previewBaseBlurDp = 18f
                                    prefs.edit().putFloat(Appearance.CLARITY_DIALOG, next).apply()
                                },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.xs),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                AbTextButton(
                                    text = "全部恢复默认",
                                    color = p.accent,
                                    onClick = {
                                        val def = Appearance.DEFAULT_LIQUID_GLASS_CLARITY
                                        clarity = def
                                        dockClarity = def
                                        headerClarity = def
                                        sheetClarity = def
                                        dialogClarity = def
                                        previewText = "液态玻璃通透效果"
                                        previewZoneClarity = def
                                        previewBaseLight = 0.32f
                                        previewBaseDark = 0.44f
                                        previewBaseBlurDp = 4.5f
                                        prefs.edit()
                                            .putFloat(Appearance.LIQUID_GLASS_CLARITY, def)
                                            .putFloat(Appearance.CLARITY_DOCK, def)
                                            .putFloat(Appearance.CLARITY_HEADER, def)
                                            .putFloat(Appearance.CLARITY_SHEET, def)
                                            .putFloat(Appearance.CLARITY_DIALOG, def)
                                            .apply()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaritySliderRow(
    label: String,
    value: Float,
    contentDescription: String,
    onValueChange: (Float) -> Unit,
) {
    val p = LocalAbPalette.current
    val clarityLabel = when {
        value <= 0.25f -> "雾面"
        value >= 0.80f -> "清透"
        else -> "${kotlin.math.round(value * 100).toInt()}%"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AbSpacing.l, vertical = AbSpacing.xs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = AbType.body, color = p.label, modifier = Modifier.weight(1f))
            Text(
                clarityLabel,
                style = AbType.footnote.tabular(),
                color = p.secondaryLabel,
                modifier = Modifier.widthIn(min = 42.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        AbLiquidSlider(
            value = value,
            contentDescription = contentDescription,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 实时通透度预览条：以彩色图形与账单文字作衬底，拖动全局或任意分区滑杆时直接展示对应区域的雾面与清透反差。 */
@Composable
private fun ClarityLivePreviewStrip(
    clarity: Float,
    label: String,
    baseLight: Float,
    baseDark: Float,
    baseBlurDp: Float,
) {
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val layer = rememberGraphicsLayer()
    val useBlur = android.os.Build.VERSION.SDK_INT >= 31
    val shape = RoundedCornerShape(AbRadius.row)
    val glassShape = RoundedCornerShape(AbRadius.capsule)
    var cardOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var hostOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = AbSpacing.l, end = AbSpacing.l, bottom = AbSpacing.m)
            .height(60.dp)
            .clip(shape)
            .background(if (isDark) Color(0xFF15171C) else Color(0xFFF0F4F3))
            .onGloballyPositioned { coords ->
                if (coords.isAttached) hostOrigin = coords.boundsInRoot().topLeft
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .then(
                    if (useBlur) Modifier.drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    } else Modifier,
                )
                .padding(horizontal = AbSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFFE67E22)))
            Box(Modifier.size(24.dp).clip(CircleShape).background(p.accent))
            Column(Modifier.weight(1f)) {
                Text("餐饮 · 咖啡馆", style = AbType.caption1.copy(fontWeight = FontWeight.SemiBold), color = p.label)
                Text("支出 ¥38.00", style = AbType.caption2, color = p.expense)
            }
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF3B82F6)))
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFFE04F5F)))
        }
        val paneAlpha = glassSurfaceAlpha(baseLight, baseDark, isDark, clarity)
        val previewBlurDp = glassBlurRadiusDp(clarity, baseDp = baseBlurDp.coerceAtMost(14f), minDp = 1.8f, maxDp = 16f)
        val glassTintColor = if (isDark) p.cardBg else Color.White
        Box(
            Modifier
                .padding(horizontal = AbSpacing.l)
                .height(38.dp)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) cardOffset = coords.boundsInRoot().topLeft - hostOrigin
                }
                .shadow(8.dp, glassShape, clip = false, ambientColor = p.glassShadow, spotColor = p.glassShadow)
                .clip(glassShape),
            contentAlignment = Alignment.Center,
        ) {
            if (useBlur) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val blurPx = previewBlurDp * density
                            renderEffect = androidx.compose.ui.graphics.BlurEffect(blurPx, blurPx, androidx.compose.ui.graphics.TileMode.Clamp)
                        }
                        .drawWithContent {
                            translate(-cardOffset.x, -cardOffset.y) {
                                drawLayer(layer)
                            }
                        },
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(glassTintColor.copy(alpha = paneAlpha))
                    .border(0.9.dp, io.github.mymoney.ui.design.liquidSpecularRimBrush(isDark), glassShape),
            )
            Text(
                label,
                style = AbType.footnote.copy(fontWeight = FontWeight.Medium),
                color = p.label,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 本地备份文件
// ---------------------------------------------------------------------------

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
                        ?: error("无法写入文件")
                }
            }
            message = if (ok.isSuccess) "备份已保存" else "保存失败：${ok.exceptionOrNull()?.message}"
            busy = false
        }
    }

    val recoveryPoint = remember {
        File(context.filesDir, "ledger-backups").listFiles()
            ?.filter { it.name.startsWith("pre-restore-") && it.extension == "json" }
            ?.maxByOrNull { it.lastModified() }
    }
    val exportRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && recoveryPoint != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            recoveryPoint.inputStream().use { it.copyTo(output) }
                        } ?: error("无法写入文件")
                    }
                }
                message = if (result.isSuccess) "恢复前快照已导出" else "导出失败：${result.exceptionOrNull()?.message}"
                busy = false
            }
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
                        ?: error("无法读取文件")
                }
                text to Backup.decode(text)
            }
            parsed.onSuccess { pending = it; message = null }
                .onFailure { message = "无效的备份文件：${it.message}" }
            busy = false
        }
    }

    SubPageScaffold(title = "本地备份文件", onBack = onBack) { padding ->
        SectionColumn(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
        ) {
            InsetGroup {
                ListRow(
                    title = "导出备份文件",
                    subtitle = if (busy) "处理中…" else null,
                    chevron = true,
                    onClick = { if (!busy) createLauncher.launch(BackupStore.fileName()) },
                )
                if (recoveryPoint != null) {
                    ListRow(
                        title = "导出最近恢复前快照",
                        chevron = true,
                        onClick = { if (!busy) exportRecovery.launch("MyMoney-pre-restore.json") },
                    )
                }
                ListRow(
                    title = "从备份恢复",
                    subtitle = if (busy) "处理中…" else null,
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
            if (snap.exportedAt <= 0) "未知"
            else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(snap.exportedAt))
        }
        AppDialog(title = if (confirmReplace) "确认覆盖恢复？" else "恢复备份", onDismiss = { pending = null; confirmReplace = false }) {
            Text(
                text = if (confirmReplace) {
                    "覆盖恢复将替换现有账单、记录与资产（共 ${snap.bills.size} 条账单），不可撤销。"
                } else {
                    "时间：$whenText\n账单：${snap.bills.size} 条\n账户：${snap.assets?.accounts?.size ?: 0} 个"
                },
                style = AbType.body,
                color = p.secondaryLabel,
            )
            if (confirmReplace) {
                AppDialogActions(
                    confirmLabel = "确认覆盖",
                    onConfirm = {
                        pending = null
                        confirmReplace = false
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                BackupStore.restore(context, json, BackupStore.Mode.REPLACE)
                            }
                            message = result.fold(
                                onSuccess = { "已覆盖导入 ${it.bills} 条账单" },
                                onFailure = { "恢复失败：${it.message}" },
                            )
                            result.getOrNull()?.let { onRestored(it.settings) }
                            busy = false
                        }
                    },
                    dismissLabel = "返回",
                    onDismiss = { confirmReplace = false },
                    destructive = true,
                    busy = busy,
                )
            } else {
                PrimaryButton("合并导入", onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            BackupStore.restore(context, json, BackupStore.Mode.MERGE)
                        }
                        message = result.fold(
                            onSuccess = { "已合并导入 ${it.bills} 条账单" },
                            onFailure = { "恢复失败：${it.message}" },
                        )
                        result.getOrNull()?.let { onRestored(it.settings) }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                SecondaryButton("覆盖恢复…", onClick = { confirmReplace = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                SecondaryButton("取消", onClick = { pending = null }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 页面诊断
// ---------------------------------------------------------------------------

@Composable
private fun PageDebugPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val debug by DebugBus.state.collectAsStateWithLifecycle()
    var sampleSaved by remember { mutableStateOf<String?>(null) }

    SubPageScaffold(title = "页面诊断", onBack = onBack) { padding ->
        SectionColumn(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
        ) {
            if (debug == null) {
                EmptyState(
                    icon = AbIcons.Search,
                    title = "暂无识别记录",
                    message = "打开账单详情页后显示识别结果。",
                )
            } else {
                GroupHeader("最近识别")
                InsetGroup {
                    ListRow(title = "应用", subtitle = debug!!.packageName)
                    ListRow(title = "页面", subtitle = debug!!.activityClass ?: "—")
                    ListRow(title = "来源", subtitle = debug!!.source)
                    ListRow(title = "结论", subtitle = debug!!.outcome)
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
                    Text(debug!!.text, style = AbType.mono, color = p.secondaryLabel)
                }
                Spacer(Modifier.height(AbSpacing.m))
                SecondaryButton("复制页面文本", onClick = { copy(context, debug!!.text) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(AbSpacing.s))
                SecondaryButton(
                    text = "复制为 Kotlin 夹具",
                    onClick = {
                        val items = SampleExport.splitLines(debug!!.text)
                        val name = SampleExport.toIdentifier(
                            debug!!.packageName.substringAfterLast('.').ifBlank { "sample" } + "Bill",
                        )
                        copy(context, SampleExport.kotlinFixture(name, items))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AbSpacing.s))
                SecondaryButton(
                    text = "保存样本",
                    onClick = {
                        sampleSaved = if (saveSample(context, debug!!)) "已保存" else "保存失败"
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sampleSaved != null) GroupFooter(sampleSaved!!)
            }
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

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

private fun saveSample(context: Context, debug: DebugBus.State): Boolean = runCatching {
    val dir = File(context.filesDir, "samples").apply { mkdirs() }
    SampleIndex.save(dir, debug)
    true
}.getOrDefault(false)

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("麦米", text))
}
