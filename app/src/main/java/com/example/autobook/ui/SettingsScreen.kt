package com.example.autobook.ui

import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.autobook.capture.XposedModuleDetector
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
import com.example.autobook.root.RootWatcher
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
import com.example.billkit.Packages
import com.example.billkit.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val shortNames = linkedMapOf(
    Packages.WECHAT to "微信",
    Packages.ALIPAY to "支付宝",
    Packages.UNIONPAY to "云闪付",
    Packages.JD to "京东",
    Packages.MEITUAN to "美团",
    Packages.TAOBAO to "淘宝",
    Packages.PDD to "拼多多",
    Packages.DOUYIN to "抖音",
)

/** 设置页内部的页面栈（手写，不引 navigation-compose）。 */
private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    CATEGORIES("分类与账户"),
    ASSETS("资产管理"),
    NOTE("备注模板"),
    RULES("本地规则"),
    APPS("支持的应用"),
    PERMISSIONS("权限"),
    MODE("识别模式"),
    ROOT_MODE("Root 状态"),
    PENDING("待确认"),
    EXPORT("导出"),
    BACKUP("备份与恢复"),
    APPEARANCE("外观"),
    HELP("帮助"),
    DEBUG("识别排查"),
}

private fun captureSettingsPage(destination: CaptureSettingsDestination): SettingsPage = when (destination) {
    CaptureSettingsDestination.MODE -> SettingsPage.MODE
    CaptureSettingsDestination.ROOT_STATUS -> SettingsPage.ROOT_MODE
    CaptureSettingsDestination.SUPPORTED_APPS -> SettingsPage.APPS
}

/**
 * 设置入口页：iOS 的 inset grouped 列表，每类设置一行，点进二级页。
 *
 * 之前是一页堆 7 张 Card 的长滚动；现在首页只放「开关 + 导航」，
 * 明细都收进子页（D3 决策）。
 */
@Composable
fun SettingsScreen(destination: CaptureSettingsDestination? = null, onDestinationHandled: () -> Unit = {}, onEditorVisibilityChanged: (Boolean) -> Unit = {}, onOpenAssets: (() -> Unit)? = null) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val p = LocalAbPalette.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    var pagePath by rememberSaveable { mutableStateOf(listOf(SettingsPage.ROOT)) }
    val page = pagePath.last()
    val visibilityChanged by androidx.compose.runtime.rememberUpdatedState(onEditorVisibilityChanged)
    LaunchedEffect(page) { visibilityChanged(page == SettingsPage.ASSETS) }
    DisposableEffect(Unit) { onDispose { visibilityChanged(false) } }
    fun navigate(target: SettingsPage) { pagePath = pagePath + target }
    fun returnToParent() { pagePath = pagePath.dropLast(1).ifEmpty { listOf(SettingsPage.ROOT) } }
    LaunchedEffect(destination) {
        destination?.let {
            pagePath = listOf(SettingsPage.ROOT, captureSettingsPage(it))
            onDestinationHandled()
        }
    }
    val healthInput = rememberCaptureHealthInput()
    val health = captureHealth(healthInput)
    fun handleHealthAction(action: CaptureAction) {
        performCaptureAction(context, action, { target ->
            navigate(captureSettingsPage(target))
        }, { navigate(SettingsPage.PENDING) })
    }

    // 贯穿整棵设置树的共享状态
    val captureState by CaptureModeManager.state.collectAsStateWithLifecycle()
    val enabled = captureState.enabled
    val mode = captureState.mode
    val scope = rememberCoroutineScope()
    var autoConfirm by remember { mutableStateOf(settings.autoConfirm) }
    var ocrFallback by remember { mutableStateOf(settings.ocrFallback) }
    var whitelist by remember { mutableStateOf(settings.whitelist) }
    val serviceBound = healthInput.accessibilityAuthorized && healthInput.accessibilityBound
    var overlayGranted by remember { mutableStateOf(OverlayAnchor.canShow(context)) }
    var notifyGranted by remember { mutableStateOf(ConfirmDelivery.hasNotificationPermission(context)) }
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    val rootStatus by RootWatcher.status.collectAsStateWithLifecycle()

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

    fun checkRoot() {
        if (mode != CaptureMode.ROOT) return
        scope.launch {
            rootGranted = null
            rootGranted = withContext(Dispatchers.IO) { RootShell.isGranted() }
            val current = CaptureModeManager.state.value
            if (rootGranted == true && current.rootActive) {
                // 主动检测获授权后，明确重试此前失败的 Root 服务启动。
                CaptureModeManager.select(context, CaptureMode.ROOT)
            } else {
                CaptureModeManager.reconcile(context)
            }
        }
    }
    LaunchedEffect(page, mode) {
        if (page == SettingsPage.ROOT_MODE && mode == CaptureMode.ROOT) checkRoot()
    }

    fun applyKit() {
        BillKit.updateConfig(settings.toKitConfig())
        BillAccessibilityService.instance?.refreshServiceInfo()
    }

    // 系统返回/侧滑：先退子页，再交给 MainActivity 处理 tab，不再直接退出 App
    BackHandler(enabled = page != SettingsPage.ROOT) { returnToParent() }

    val back: () -> Unit = { returnToParent() }

    val reduceMotion = com.example.autobook.ui.design.LocalReduceMotion.current
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    androidx.compose.animation.AnimatedContent(targetState = page, transitionSpec = {
        val duration = if (reduceMotion) 0 else 220
        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(duration)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(duration)) { if (targetState == SettingsPage.ROOT) -it / 8 else it / 8 }) togetherWith
            (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(duration)) + androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(duration)) { if (targetState == SettingsPage.ROOT) it / 8 else -it / 8 })
    }, label = "settingsPage") { shown ->
    pageStates.SaveableStateProvider(shown) {
    when (shown) {
        SettingsPage.ROOT -> PageScaffold(
            title = "设置",
            listState = listState,
            subtitle = if (enabled) "识别已开启" else "识别已关闭",
        ) { padding ->
            LazyColumn(
                state = listState,
                // 底部让位走 contentPadding，不能写 Modifier.padding(padding)：
                // 后者会削掉 LazyColumn 视口，胶囊底下露出整片 pageBg 变成横带。
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = AbSpacing.page,
                    end = AbSpacing.page,
                    bottom = padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
            ) {
                item { StatusHeader(health, ::handleHealthAction) }

                item { GroupHeader("识别") }
                item {
                    InsetGroup {
                        ListRow(title = "启用识别", subtitle = health.title,
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Check, p.accent) },
                            trailing = { AbSwitch(enabled) { CaptureModeManager.setEnabled(context, it) } },
                            onClick = { CaptureModeManager.setEnabled(context, !enabled) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "通知监听（主通道）", subtitle = when {
                            !captureState.notificationsEnabled -> "已关闭，不读取通知"
                            !healthInput.notificationAuthorized -> "App 已启用，需授予系统通知使用权"
                            !healthInput.notificationBound -> "系统已授权，监听尚未连接"
                            else -> "监听已连接；已适配支付宝指定渠道的交易提醒"
                        }, leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Note, p.accent) },
                            trailing = { AbSwitch(captureState.notificationsEnabled) { CaptureModeManager.setNotificationEnabled(context, it) } },
                            onClick = { CaptureModeManager.setNotificationEnabled(context, !captureState.notificationsEnabled) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "通知使用权", subtitle = if (healthInput.notificationAuthorized) "系统已授权 · 与发送通知权限独立" else "系统未授权 · 点击前往设置",
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Shield, p.accent) }, chevron = true,
                            onClick = { handleHealthAction(CaptureAction.NOTIFICATION_ACCESS) })
                        if (captureState.notificationsEnabled && healthInput.notificationAuthorized) {
                            InsetDivider(inset = 62.dp)
                            ListRow(title = "通知监听连接", subtitle = if (healthInput.notificationBound) "已连接" else "未连接 · 点击请求重新连接",
                                leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Note, p.accent) }, chevron = !healthInput.notificationBound,
                                onClick = { if (!healthInput.notificationBound) handleHealthAction(CaptureAction.NOTIFICATION_RECONNECT) })
                        }
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "页面辅助", subtitle = if (captureState.pageFallbackEnabled) captureModeLabel(mode) else "已关闭 · 通知监听仍可独立启用",
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Accessibility, p.accent) },
                            trailing = { AbSwitch(captureState.pageFallbackEnabled) {
                                if (it && mode == null) navigate(SettingsPage.MODE)
                                else CaptureModeManager.setPageFallbackEnabled(context, it)
                            } }, onClick = {
                                if (!captureState.pageFallbackEnabled && mode == null) navigate(SettingsPage.MODE)
                                else CaptureModeManager.setPageFallbackEnabled(context, !captureState.pageFallbackEnabled)
                            })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "识别后弹出确认卡", subtitle = "关闭后保存在待确认中",
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Note, p.accent) },
                            trailing = { AbSwitch(autoConfirm) { autoConfirm = it; settings.autoConfirm = it } },
                            onClick = { autoConfirm = !autoConfirm; settings.autoConfirm = autoConfirm })
                        if (mode == CaptureMode.ROOT) {
                            InsetDivider(inset = 62.dp)
                            ListRow(title = "截图识别", subtitle = "仅 Root：页面文字不足时使用",
                                leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Ocr, p.accent) },
                                trailing = { AbSwitch(ocrFallback) { ocrFallback = it; settings.ocrFallback = it; applyKit() } },
                                onClick = { ocrFallback = !ocrFallback; settings.ocrFallback = ocrFallback; applyKit() })
                        }
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "页面辅助方式", subtitle = captureModeLabel(mode),
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Accessibility, p.accent) }, chevron = true,
                            onClick = { navigate(SettingsPage.MODE) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "支持的应用", subtitle = "已选 ${whitelist.size} 个",
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Store, p.accent) }, chevron = true,
                            onClick = { navigate(SettingsPage.APPS) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "权限", subtitle = when (mode) {
                            CaptureMode.ROOT -> "Root 授权、悬浮窗与通知"
                            CaptureMode.ACCESSIBILITY -> if (serviceBound) "无障碍已连接" else "无障碍未连接"
                            null -> if (captureState.notificationsEnabled) "通知使用权、悬浮窗与发送提醒" else "通知或页面识别权限"
                        }, leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Shield, p.accent) }, chevron = true,
                            onClick = { navigate(SettingsPage.PERMISSIONS) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "本地规则", subtitle = "导入规则、餐段与学习",
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Filter, p.accent) }, chevron = true,
                            onClick = { navigate(SettingsPage.RULES) })
                    }
                }
                item { GroupHeader("数据") }
                item {
                    InsetGroup {
                        ListRow(title = "待确认", subtitle = "查看尚未记账的识别结果",
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Clock, p.accent) }, chevron = true,
                            onClick = { navigate(SettingsPage.PENDING) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "资产管理", subtitle = "余额、负债、转账与还款", chevron = true,
                            onClick = { if (onOpenAssets != null) onOpenAssets() else navigate(SettingsPage.ASSETS) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "分类与账户", leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Tag, p.accent) },
                            chevron = true, onClick = { navigate(SettingsPage.CATEGORIES) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "备注模板", leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Note, p.accent) },
                            chevron = true, onClick = { navigate(SettingsPage.NOTE) })
                        InsetDivider(inset = 62.dp)
                        ListRow(title = "备份与恢复", subtitle = "保存账单、识别历史和配置",
                            leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Layers, p.accent) }, chevron = true,
                            onClick = { navigate(SettingsPage.BACKUP) })
                    }
                }
                item { GroupHeader("外观") }
                item { InsetGroup {
                    ListRow(title = "主题与显示", subtitle = "主题、文字大小与动效",
                        leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Settings, p.accent) }, chevron = true,
                        onClick = { navigate(SettingsPage.APPEARANCE) })
                } }
                item { GroupHeader("导出") }
                item { InsetGroup {
                    ListRow(title = "导出到 iCost", subtitle = "选择账单范围，生成 CSV",
                        leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Export, p.accent) }, chevron = true,
                        onClick = { navigate(SettingsPage.EXPORT) })
                } }
                item { GroupHeader("帮助") }
                item { InsetGroup {
                    ListRow(title = "识别帮助", subtitle = "识别排查与使用说明",
                        leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Search, p.accent) }, chevron = true,
                        onClick = { navigate(SettingsPage.HELP) })
                } }
                item { GroupFooter("数据和识别规则仅保存在本机；本 App 不联网、不上传页面内容。") }
            }
        }

        SettingsPage.ASSETS -> AssetsScreen(onBack = back)

        SettingsPage.CATEGORIES -> LedgerConfigPage(onBack = back)

        SettingsPage.RULES -> LocalRulesPage(onBack = back)

        SettingsPage.NOTE -> NoteTemplatePage(onBack = back)

        SettingsPage.APPEARANCE -> AppearancePage(onBack = back)

        SettingsPage.APPS -> AppsPage(
            whitelist = whitelist,
            onBack = back,
            onToggle = { pkg ->
                val checked = pkg in whitelist
                settings.setSupported(pkg, !checked)
                whitelist = settings.whitelist
                applyKit()
            },
            onAdd = { pkg ->
                settings.setSupported(pkg, true)
                whitelist = settings.whitelist
                applyKit()
            },
        )

        SettingsPage.MODE -> CaptureModeSettingsPage(mode, back) {
            CaptureModeManager.select(context, it)
            rootGranted = null
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
            onBack = back,
        )

        SettingsPage.ROOT_MODE -> RootModePage(
            rootGranted = rootGranted,
            enabled = enabled,
            status = rootStatus,
            onBack = back,
            onCheckRoot = { checkRoot() },
        )

        SettingsPage.EXPORT -> ExportPage(
            onBack = back,
            onOpenBackup = { navigate(SettingsPage.BACKUP) },
        )

        SettingsPage.BACKUP -> BackupPage(
            onBack = back,
            onRestored = { snap ->
                if (snap != null) BackupStore.applySettings(context, snap)
                whitelist = settings.whitelist
                autoConfirm = settings.autoConfirm
                ocrFallback = settings.ocrFallback
                CaptureModeManager.reconcile(context)
                applyKit()
            },
        )

        SettingsPage.HELP -> HelpPage(onBack = back, onDebug = { navigate(SettingsPage.DEBUG) },
            onMode = { navigate(SettingsPage.MODE) }, onPermissions = { navigate(SettingsPage.PERMISSIONS) },
            onApps = { navigate(SettingsPage.APPS) }, onPending = { navigate(SettingsPage.PENDING) })
        SettingsPage.DEBUG -> DebugPage(onBack = back)
        SettingsPage.PENDING -> RecognitionHistoryScreen(onBack = back, initialFilter = com.example.autobook.data.RecognitionStatus.PENDING)
    }
    }
    }
}

internal fun captureModeLabel(mode: CaptureMode?): String = when (mode) {
    CaptureMode.ROOT -> "Root 模式"
    CaptureMode.ACCESSIBILITY -> "无障碍模式"
    null -> "尚未选择"
}

@Composable
private fun CaptureModeSettingsPage(mode: CaptureMode?, onBack: () -> Unit, onSave: (CaptureMode) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(mode) }
    SubPageScaffold(title = "页面辅助方式", onBack = onBack) { padding ->
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
    InsetGroup(Modifier.padding(top = AbSpacing.s)) { CaptureHealthCard(health, onAction) }
}

// ---------------------------------------------------------------------------
// 子页：备注模板
// ---------------------------------------------------------------------------

@Composable
private fun NoteTemplatePage(onBack: () -> Unit) {
    val p = LocalAbPalette.current
    var template by remember { mutableStateOf(LedgerConfig.noteTemplate) }

    SubPageScaffold(title = "备注模板", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            GroupHeader("模板")
            InsetGroup {
                com.example.autobook.ui.design.InputRow(
                    label = "内容",
                    value = template,
                    onValueChange = {
                        template = it
                        LedgerConfig.noteTemplate = it
                    },
                    placeholder = "{商户}",
                )
            }
            GroupFooter("可用占位符：" + LedgerConfig.NOTE_TOKENS.joinToString(" "))

            GroupHeader("预设")
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
            ) {
                NoteTemplate.PRESETS.forEach { preset ->
                    CapsuleChip(
                        text = preset,
                        selected = template == preset,
                        onClick = {
                            template = preset
                            LedgerConfig.noteTemplate = preset
                        },
                    )
                }
            }

            Spacer(Modifier.height(AbSpacing.m))
            GroupHeader("预览")
            InsetGroup {
                ListRow(
                    title = NoteTemplate.render(
                        com.example.billkit.BillDraft(
                            priceCents = 2350,
                            type = com.example.billkit.BillType.EXPENSE,
                            timeMillis = System.currentTimeMillis(),
                            merchant = "瑞幸咖啡",
                            method = "零钱",
                            orderTail = null,
                            sourcePkg = "com.example.demo",
                            strategy = "demo",
                            category = "餐饮",
                            account = "微信",
                        ),
                        template,
                    ).ifBlank { "（空）" },
                    subtitle = "示例：瑞幸咖啡 · 餐饮 · 微信",
                )
            }
            Spacer(Modifier.height(AbSpacing.xl))
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：支持的应用
// ---------------------------------------------------------------------------

@Composable
private fun AppsPage(
    whitelist: Set<String>,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    // 白名单里不在预设表里的包（分身/宿主包，如支付宝的 com.tentent.mm）
    val extra = whitelist.filter { it !in shortNames }

    SubPageScaffold(title = "支持的应用", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            if (extra.isNotEmpty()) {
                GroupHeader("已手动添加")
                InsetGroup {
                    extra.forEachIndexed { index, pkg ->
                        if (index > 0) InsetDivider()
                        ListRow(
                            title = pkg,
                            subtitle = "自定义包名",
                            trailing = {
                                com.example.autobook.ui.design.IconBadge(
                                    icon = AbIcons.Check,
                                    tint = LocalAbPalette.current.green,
                                    size = 26.dp,
                                )
                            },
                            dividerAbove = index > 0,
                            onClick = { onToggle(pkg) },
                        )
                    }
                }
            }

            GroupHeader("选择要监听的应用")
            InsetGroup {
                shortNames.entries.forEachIndexed { index, (pkg, label) ->
                    if (index > 0) InsetDivider()
                    val checked = pkg in whitelist
                    ListRow(
                        title = label,
                        subtitle = pkg,
                        trailing = {
                            com.example.autobook.ui.design.IconBadge(
                                icon = if (checked) AbIcons.Check else AbIcons.Close,
                                tint = if (checked) LocalAbPalette.current.green
                                else LocalAbPalette.current.tertiaryLabel,
                                size = 26.dp,
                            )
                        },
                        dividerAbove = index > 0,
                        onClick = { onToggle(pkg) },
                    )
                }
            }
            GroupFooter("通知监听仅处理已验证的交易模板；页面辅助只处理账单页面，列表和下单页会跳过。")

            GroupHeader("手动添加包名")
            InsetGroup {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    AbTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "com.example.app",
                        onDone = {
                            val pkg = draft.trim()
                            if (pkg.isNotEmpty()) {
                                onAdd(pkg)
                                draft = ""
                            }
                        },
                    )
                }
            }
            GroupFooter(
                "分身/宿主包（比如某些 ROM 上的支付宝是 com.tentent.mm）在预设列表里看不到，" +
                    "在这里直接填包名加进来。包名可以在「诊断」页或系统「应用信息」里查到；" +
                    "加进来后页面仍要满足账单详情的条件才会识别。",
            )
            Spacer(Modifier.height(AbSpacing.xl))
        }
    }
}

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

    // Current authenticated system-server report; refresh on resume and expire stale reports.
    val xposedDetail by XposedModuleDetector.detail.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        com.example.autobook.capture.XposedModuleDetector.detect(context)
    }

    // 保活体检结果：全部来自实测，不含推断。每次回到前台重读一次（用户可能刚去系统里改过）。
    var keepAlive by remember { mutableStateOf<AccessibilityEnhancer.KeepAliveStatus?>(null) }
    LaunchedEffect(Unit) { keepAlive = AccessibilityEnhancer.getStatus(context) }
    val keepAliveOwner = LocalLifecycleOwner.current
    DisposableEffect(keepAliveOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keepAlive = AccessibilityEnhancer.getStatus(context)
                XposedModuleDetector.detect(context)
            }
        }
        keepAliveOwner.lifecycle.addObserver(observer)
        onDispose { keepAliveOwner.lifecycle.removeObserver(observer) }
    }

    SubPageScaffold(title = "权限", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            GroupHeader("识别权限")
            InsetGroup {
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
                if (mode == CaptureMode.ROOT) {
                    ListRow(
                        title = "Root 授权",
                        subtitle = when (rootGranted) {
                            true -> "已授权 — 点击重新检测"
                            false -> "未授权 — 点击重试，可切换到无障碍模式"
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
            GroupFooter(
                when (mode) {
                    CaptureMode.ROOT -> "Root 用于读取账单页面，无需开启无障碍。"
                    CaptureMode.ACCESSIBILITY -> "无障碍用于读取账单页面文字，无需 Root。"
                    null -> "通知监听可独立启用；需要页面补漏时再选择辅助方式。"
                } +
                    "悬浮窗是「识别完立刻弹卡」的前提：" +
                    "有它才能不经通知、不启动 Activity 直接把确认卡盖在对方 App 上，" +
                    "不放任何常驻 UI。",
            )

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
                XposedModuleDetector.detect(context)
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
                GroupFooter(
                    (manufacturer?.let { AccessibilityEnhancer.optimizationGuide(it) }
                        ?: AccessibilityEnhancer.optimizationGuide(AccessibilityEnhancer.Manufacturer.OTHER))
                        .joinToString("；") +
                        "。系统不提供「检查自启动是否已开」的接口，只能按上面手动确认。",
                )

                Spacer(Modifier.height(AbSpacing.m))
                GroupHeader("增强模块（可选）")
                InsetGroup {
                    ListRow(
                        title = "LSP 增强模块",
                        subtitle = xposedDetail,
                        chevron = false,
                        onClick = { XposedModuleDetector.detect(context) },
                    )
                }
                GroupFooter(
                    "解绑与撤权目前仅监测，未提供解绑拦截，尚不能确认解决掉权限。" +
                        "仅对本 App 当前用户仍授权且连接存活的 background restriction 终止提供有限保护；未知原因放行。" +
                        "不会阻止用户撤权、强行停止或包更新，不防低内存回收。" +
                        "安装升级后系统框架仍运行旧模块，需重启系统才能加载新版；点击状态可重新检测。",
                )
            }

            Spacer(Modifier.height(AbSpacing.xl))
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：Root 轮询
// ---------------------------------------------------------------------------

@Composable
private fun RootModePage(
    rootGranted: Boolean?,
    enabled: Boolean,
    status: RootWatcher.Status,
    onBack: () -> Unit,
    onCheckRoot: () -> Unit,
) {
    SubPageScaffold(title = "Root 状态", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            GroupHeader("状态")
            InsetGroup {
                ListRow(
                    title = "Root 授权",
                    subtitle = when (rootGranted) {
                        null -> "检测中 / 点击重试"
                        true -> "已获得（Magisk / KernelSU）"
                        false -> "未获得 — 可重试或切换到无障碍模式"
                    },
                    chevron = true,
                    onClick = onCheckRoot,
                )
                InsetDivider()
                ListRow(
                    title = "识别状态",
                    subtitle = if (!enabled) "识别总开关已暂停" else if (status.running) "后台轮询运行中" else "未运行，请检查 Root 授权",
                )
            }
            GroupHeader("实时状态")
            InsetGroup {
                ListRow(title = "最近结果", subtitle = status.lastResult)
                InsetDivider()
                ListRow(
                    title = "最近包名",
                    subtitle = status.lastPackage ?: "—",
                    dividerAbove = true,
                )
                InsetDivider()
                ListRow(
                    title = "已处理页面",
                    subtitle = "${status.tickCount} 次变化",
                    dividerAbove = true,
                )
            }
            GroupFooter(
                "Root 模式开启识别后通知栏会有一条常驻通知，退出 AutoBook 也继续监听。" +
                    "root 路径没有后台启动豁免，所以更需要悬浮窗权限——有权限时确认卡直接浮在页面上，" +
                    "没有就只能落待确认队列 + 通知。" +
                    "如需改用无障碍，请返回「识别模式」切换。",
            )
            Spacer(Modifier.height(AbSpacing.xl))
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：导出
// ---------------------------------------------------------------------------

@Composable
private fun ExportPage(onBack: () -> Unit, onOpenBackup: () -> Unit) {
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
    val today = remember { LocalDate.now() }

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

    SubPageScaffold(title = "导出", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            GroupHeader("时间范围")
            InsetGroup {
                Column(Modifier.padding(AbSpacing.m)) {
                    Segmented(
                        options = ranges.map { it.label },
                        selected = rangeIndex,
                        onSelect = { rangeIndex = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        ExportRange.describe(kind, today),
                        style = AbType.footnote,
                        color = p.tertiaryLabel,
                        modifier = Modifier.padding(top = AbSpacing.s, start = AbSpacing.xs),
                    )
                }
            }
            GroupFooter("按官方 10 列模板生成 CSV，交给 iCost 自己的导入界面确认。")

            Spacer(Modifier.height(AbSpacing.s))
            GroupHeader("导出")
            InsetGroup {
                ListRow(
                    title = "导出${kind.label}",
                    subtitle = if (busy) "生成中…" else "分享 CSV 给记账 App",
                    chevron = true,
                    onClick = { run { CostShare.shareRange(context, kind) } },
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
            GroupFooter(
                "分类/账户名要与 iCost 里的一致，否则会归到「未分类」而不进收支日历。",
            )

            Spacer(Modifier.height(AbSpacing.m))
            GroupHeader("本机备份")
            InsetGroup {
                ListRow(
                    title = "备份与恢复",
                    subtitle = "换机/重装前导出 JSON（含分类与账户清单）",
                    chevron = true,
                    onClick = { onOpenBackup() },
                )
            }

            Spacer(Modifier.height(AbSpacing.m))
            InsetGroup {
                ListRow(
                    title = "查看导入说明",
                    subtitle = "docs/ICOST_IMPORT.md",
                    chevron = true,
                    onClick = { message = "说明文档在项目仓库的 docs/ICOST_IMPORT.md" },
                )
            }
            Spacer(Modifier.height(AbSpacing.xl))
        }
    }
}

// ---------------------------------------------------------------------------
// 子页：外观（玻璃强度）
// ---------------------------------------------------------------------------

/**
 * 玻璃强度滑块（对应 iOS 27 把二元的 Reduce Transparency 换成连续滑块）。
 *
 * 拖到 0 等于关闭玻璃：`GlassSurface` 退化成实色卡，`LiquidTabBar` 也不再透内容。
 * Compose 做不了真正的背景模糊，所以"模糊感"实际是靠透明度 + 高光 + 暗边骗出来的，
 * 强度滑块调的正是这层透明度 —— 壁纸太花时用户可以自己往低调。
 */
@Composable
private fun AppearancePage(onBack: () -> Unit) {
    val p = LocalAbPalette.current

    SubPageScaffold(title = "外观", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            val context = LocalContext.current
            val revision = com.example.autobook.ui.design.appearanceRevision()
            val prefs = remember(revision) { com.example.autobook.ui.design.Appearance.prefs(context) }
            GroupHeader("主题")
            Segmented(listOf("跟随系统", "浅色", "深色"), listOf("SYSTEM", "LIGHT", "DARK").indexOf(prefs.getString("theme", "SYSTEM")).coerceAtLeast(0), { index -> prefs.edit().putString("theme", listOf("SYSTEM", "LIGHT", "DARK")[index]).apply() })
            GroupHeader("文字大小")
            Segmented(listOf("标准", "大", "特大"), listOf("STANDARD", "LARGE", "EXTRA_LARGE").indexOf(prefs.getString("text_size", "STANDARD")).coerceAtLeast(0), { index -> prefs.edit().putString("text_size", listOf("STANDARD", "LARGE", "EXTRA_LARGE")[index]).apply() })
            ListRow(title = "降低动效", subtitle = "系统移除动画时也会自动启用", trailing = { AbSwitch(prefs.getBoolean("reduce_motion", false)) { prefs.edit().putBoolean("reduce_motion", it).apply() } })
            Spacer(Modifier.height(AbSpacing.m))
            GroupHeader("玻璃")
            InsetGroup {
                ListRow(
                    title = "液体玻璃",
                    subtitle = "底部标签栏采用轻透材质，随浅色、深色主题调整",
                )
            }
            GroupFooter(
                "玻璃保持当前的轻透效果，让内容和文字更清楚。",
            )
            Spacer(Modifier.height(AbSpacing.xl))
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
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
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
            GroupFooter(
                "备份不含识别开关这类「用一次就改」的设置；恢复后会按备份里的 root 模式/白名单重配。",
            )

            Spacer(Modifier.height(AbSpacing.m))
            GroupHeader("恢复")
            InsetGroup {
                ListRow(
                    title = "从备份恢复",
                    subtitle = if (busy) "处理中…" else "选择之前导出的 .json 文件",
                    chevron = true,
                    onClick = { if (!busy) openLauncher.launch(arrayOf("*/*")) },
                )
            }
            GroupFooter("默认合并且不重复登记余额。新版覆盖会替换账单、历史及备份包含的资产；旧备份缺少资产时保留当前余额和流水，导入账单不自动联动，请恢复后对账。资产冲突会拒绝恢复，请核对后重试。")

            if (message != null) {
                Spacer(Modifier.height(AbSpacing.s))
                Text(
                    message!!,
                    style = AbType.footnote,
                    color = p.secondaryLabel,
                    modifier = Modifier.padding(start = AbSpacing.xs),
                )
            }
            Spacer(Modifier.height(AbSpacing.xl))
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
                            "如备份含分类账户和设置，也会恢复这些配置。建议先导出当前备份；此操作不可撤销，确定吗？"
                    } else {
                        "备份时间：${whenText}\n账单：${snap.bills.size} 条\n" +
                            "识别历史：${snap.history?.let { "${it.size} 条" } ?: "旧格式未包含，保留本机历史"}\n" +
                            "资产：${snap.assets?.let { "${it.accounts.size} 个账户、${it.entries.size} 条分录" } ?: "旧格式未包含，保留本机资产；导入后请对账"}\n" +
                            "分类账户清单：${if (snap.ledger != null) "有" else "无"}\n" +
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
    onBack: () -> Unit, onDebug: () -> Unit, onMode: () -> Unit,
    onPermissions: () -> Unit, onApps: () -> Unit, onPending: () -> Unit,
) {
    val p = LocalAbPalette.current
    SubPageScaffold(title = "帮助", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
            GroupHeader("识别排查")
            InsetGroup {
                ListRow(title = "识别排查", subtitle = "查看最近结论、页面文字与诊断样本",
                    leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Search, p.accent) },
                    chevron = true, onClick = onDebug)
                InsetDivider(inset = 62.dp)
                ListRow(title = "识别模式", leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Accessibility, p.accent) }, chevron = true, onClick = onMode)
                InsetDivider(inset = 62.dp)
                ListRow(title = "权限", leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Shield, p.accent) }, chevron = true, onClick = onPermissions)
                InsetDivider(inset = 62.dp)
                ListRow(title = "支持的应用", leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Store, p.accent) }, chevron = true, onClick = onApps)
                InsetDivider(inset = 62.dp)
                ListRow(title = "待确认", leading = { com.example.autobook.ui.design.IconBadge(AbIcons.Clock, p.accent) }, chevron = true, onClick = onPending)
            }
            GroupHeader("使用说明")
            InsetGroup {
                ListRow(title = "识别后需要确认", subtitle = "确认保存后才写入账本；关闭弹卡后可在待确认中处理。")
                InsetDivider()
                ListRow(title = "页面没有识别到？", subtitle = "先检查模式、支持的应用和权限，再打开支付结果或账单详情。仍有问题时先保存该页诊断样本，再切换页面。")
                InsetDivider()
                ListRow(title = "截图识别", subtitle = "仅 Root 模式支持，页面文字不足时使用；无障碍模式读取页面文字。")
                InsetDivider()
                ListRow(title = "诊断样本分享前请检查", subtitle = "页面文字可能含商户、订单等信息，请先脱敏。")
            }
            Spacer(Modifier.height(AbSpacing.xl))
        }
    }
}

@Composable
private fun DebugPage(onBack: () -> Unit) {
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

    SubPageScaffold(title = "识别排查", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
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
            GroupFooter("上方只显示最近一次通知事件，样本列表与事件诊断独立。监听已连接或收到通知不代表交易模板已验证；未验证模板不会自动记账或弹确认框。")
            if (notification?.outcome?.contains("group-summary") == true) {
                GroupFooter("group-summary 表示这条汇总通知被跳过，不代表子通知没有到达。可展开最近事件，查看各条子通知的处理结果。")
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
            GroupFooter("此按钮仅诊断当前仍在通知栏里的所选支付应用通知，不会触发交易识别或自动弹框，也不需要再次付款。")
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
            GroupFooter("样本最多暂存 10 条、保留 5 分钟；后到汇总或无正文通知不会清空已有有效样本。配置变动或撤销通知使用权后失效。最近有效样本不一定对应最上方的最近事件。")
            GroupFooter("选择样本后先预览，确认保存才写入你选择的文件位置，不自动分享。金额和格式词可能保留，请检查预览；手机号与长订单等由采样器脱敏。")
            notificationSaved?.let { GroupFooter(it) }
            Spacer(Modifier.height(AbSpacing.l))
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
            Spacer(Modifier.height(AbSpacing.xl))
        }
    }
    notificationPreview?.let { preview ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!notificationSaving) notificationPreview = null },
            title = { Text("通知脱敏样本预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    Text("仅保存当前预览，不自动分享。监听输入样本不代表真实付款或交易模板已验证。", style = AbType.footnote)
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
