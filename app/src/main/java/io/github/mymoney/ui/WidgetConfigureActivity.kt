package io.github.mymoney.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mymoney.R
import io.github.mymoney.systems.AssetReimburseWidget
import io.github.mymoney.systems.AssetWidgetMode
import io.github.mymoney.systems.WidgetPrefs
import io.github.mymoney.systems.WidgetSurfaceStyle
import io.github.mymoney.systems.WidgetThemeTokens
import io.github.mymoney.ui.design.*

/**
 * 资产 & 报销桌面小组件实例配置页：
 * 支持实时预览、模式切换、实底/半透磨砂与默认隐藏金额，保存后即刻更新对应 appWidgetId。
 */
class WidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val prefs = WidgetPrefs.prefs(this)
        val initialMode = WidgetPrefs.mode(prefs, appWidgetId)
        val initialSurface = WidgetPrefs.surface(prefs, appWidgetId)
        val initialMasked = WidgetPrefs.isMasked(prefs, appWidgetId)

        setContent {
            MyMoneyTheme {
                WidgetConfigureScreen(
                    initialMode = initialMode,
                    initialSurface = initialSurface,
                    initialMasked = initialMasked,
                    onCancel = { finish() },
                    onConfirm = { mode, surface, masked ->
                        WidgetPrefs.setMode(prefs, appWidgetId, mode)
                        WidgetPrefs.setSurface(prefs, appWidgetId, surface)
                        WidgetPrefs.setMasked(prefs, appWidgetId, masked)
                        AssetReimburseWidget.refreshIds(applicationContext, intArrayOf(appWidgetId))
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigureScreen(
    initialMode: AssetWidgetMode,
    initialSurface: WidgetSurfaceStyle,
    initialMasked: Boolean,
    onCancel: () -> Unit,
    onConfirm: (AssetWidgetMode, WidgetSurfaceStyle, Boolean) -> Unit,
) {
    val p = LocalAbPalette.current
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    val isDark = p.pageBg.luminance() < 0.1f
    var mode by remember { mutableStateOf(initialMode) }
    var surface by remember { mutableStateOf(initialSurface) }
    var masked by remember { mutableStateOf(initialMasked) }

    SubPageScaffold(title = "配置资产看板", onBack = onCancel, reserveDock = false) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(p.pageBg)
                .padding(padding)
                .padding(horizontal = AbSpacing.page),
        ) {
            SectionColumn(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                GroupHeader("实时预览")
                val widgetPalette = remember(context, surface, p.accent) { WidgetThemeTokens.palette(context, surface) }
                val colorSpec = remember(reduceMotion) { tween<Color>(if (reduceMotion) 110 else AbMotion.selection, easing = AbEasing.EaseOut) }
                val cardBg by animateColorAsState(
                    when (surface) {
                        WidgetSurfaceStyle.GLASS_DARK -> Color(0xB3161618)
                        WidgetSurfaceStyle.GLASS_LIGHT -> Color(0xC7FFFFFF)
                        WidgetSurfaceStyle.SOLID -> p.cardBg
                    },
                    colorSpec,
                    label = "preview-card-bg",
                )
                val borderColor by animateColorAsState(
                    when (surface) {
                        WidgetSurfaceStyle.GLASS_DARK -> Color(0x30FFFFFF)
                        WidgetSurfaceStyle.GLASS_LIGHT -> Color(0xB8FFFFFF)
                        WidgetSurfaceStyle.SOLID -> p.separator
                    },
                    colorSpec,
                    label = "preview-border",
                )
                val previewPrimaryText = Color(widgetPalette.primaryText)
                val previewSecondaryText = Color(widgetPalette.secondaryText)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AbRadius.group))
                        .background(
                            if (surface == WidgetSurfaceStyle.SOLID) p.fill
                            else p.accent.copy(alpha = 0.22f),
                        )
                        .padding(AbSpacing.l),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .width(176.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(cardBg)
                            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_brand_color),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (mode == AssetWidgetMode.REIMBURSE_ONLY) "待报销" else "净资产",
                                style = AbType.caption1,
                                color = previewSecondaryText,
                            )
                        }
                        Text(
                            text = when {
                                masked -> "¥****"
                                mode == AssetWidgetMode.REIMBURSE_ONLY -> "¥640.00"
                                else -> "¥42,850.00"
                            },
                            style = AbType.title2.copy(fontWeight = FontWeight.Bold).tabular(),
                            color = previewPrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val pillBg by animateColorAsState(
                            when {
                                surface == WidgetSurfaceStyle.GLASS_DARK && mode == AssetWidgetMode.MIXED ->
                                    Color(widgetPalette.accent).copy(alpha = 168f / 255f)
                                surface == WidgetSurfaceStyle.GLASS_DARK -> Color(0x28FFFFFF)
                                mode == AssetWidgetMode.MIXED -> Color(widgetPalette.accentContainer)
                                surface == WidgetSurfaceStyle.GLASS_LIGHT -> Color(0x14000000)
                                else -> Color(0x18000000).takeIf { !isDark } ?: p.fill
                            },
                            colorSpec,
                            label = "preview-pill-bg",
                        )
                        val pillFg = when {
                            surface == WidgetSurfaceStyle.GLASS_DARK -> Color.White
                            mode == AssetWidgetMode.MIXED -> Color(widgetPalette.onAccentContainer)
                            else -> previewPrimaryText
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(pillBg)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = when (mode) {
                                    AssetWidgetMode.MIXED -> if (masked) "待报销 2 笔 · ¥****" else "待报销 2 笔 · ¥640.00"
                                    AssetWidgetMode.ASSET_ONLY -> if (masked) "资产 ¥**** · 负债 ¥****" else "资产 ¥45,000 · 负债 ¥2,150"
                                    AssetWidgetMode.REIMBURSE_ONLY -> if (masked) "2 笔待核 · 本月 ¥****" else "2 笔待核 · 本月 ¥3,120"
                                },
                                style = AbType.caption1.copy(fontWeight = FontWeight.SemiBold).tabular(),
                                color = pillFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                GroupHeader("展示内容")
                Segmented(
                    options = AssetWidgetMode.entries.map { it.title },
                    selected = AssetWidgetMode.entries.indexOf(mode),
                    onSelect = { mode = AssetWidgetMode.entries[it] },
                )
                Text(
                    text = mode.subtitle,
                    style = AbType.footnote,
                    color = p.secondaryLabel,
                )

                GroupHeader("材质与隐私")
                Segmented(
                    options = WidgetSurfaceStyle.entries.map { it.title },
                    selected = WidgetSurfaceStyle.entries.indexOf(surface),
                    onSelect = { surface = WidgetSurfaceStyle.entries[it] },
                )
                InsetGroup {
                    ListRow(
                        title = "默认隐藏金额",
                        subtitle = "开启后显示为 ¥****，仍可在桌面点小眼睛临时切换",
                        trailing = {
                            AbSwitch(masked) { masked = it }
                        },
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = AbSpacing.m),
                horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
            ) {
                SecondaryButton("取消", onClick = onCancel, modifier = Modifier.weight(1f))
                PrimaryButton("完成", onClick = { onConfirm(mode, surface, masked) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
