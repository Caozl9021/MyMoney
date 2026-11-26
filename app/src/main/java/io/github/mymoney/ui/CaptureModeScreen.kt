package io.github.mymoney.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.PrimaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 首次启动引导页。 */
@Composable
fun CaptureModeScreen(onOrdinarySetup: () -> Unit) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val slideOffsetPx = remember(density) { with(density) { 10.dp.toPx() } }
    val p0 = remember { Animatable(0f) }
    val p1 = remember { Animatable(0f) }
    val p2 = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            listOf(p0, p1, p2).forEach { it.snapTo(0f) }
            val spec = tween<Float>(180, easing = AbEasing.EaseOut)
            launch { p0.animateTo(1f, spec) }
            launch { p1.animateTo(1f, spec) }
            launch { p2.animateTo(1f, spec) }
        } else {
            val spec = tween<Float>(240, easing = AbEasing.EaseOut)
            launch { p0.animateTo(1f, spec) }
            launch { delay(50); p1.animateTo(1f, spec) }
            launch { delay(90); p2.animateTo(1f, spec) }
        }
    }
    Column(Modifier.fillMaxSize().background(p.pageBg).systemBarsPadding()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(AbSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xl),
        ) {
            Text(
                "自动记账",
                style = AbType.largeTitle,
                color = p.label,
                modifier = Modifier.graphicsLayer {
                    alpha = p0.value
                    if (!reduceMotion) translationY = (1f - p0.value) * slideOffsetPx
                },
            )
            Text(
                "打开微信、支付宝交易详情页时自动弹出确认卡。",
                style = AbType.subhead,
                color = p.secondaryLabel,
                modifier = Modifier.graphicsLayer {
                    alpha = p1.value
                    if (!reduceMotion) translationY = (1f - p1.value) * slideOffsetPx
                },
            )
            Column(
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = p2.value
                    if (!reduceMotion) translationY = (1f - p2.value) * slideOffsetPx
                },
                verticalArrangement = Arrangement.spacedBy(AbSpacing.xl),
            ) {
                PrimaryButton(
                    "前往设置",
                    {
                        CaptureModeManager.prepareOrdinarySetup(context)
                        onOrdinarySetup()
                    },
                    Modifier.fillMaxWidth(),
                )
                AbTextButton(
                    text = "稍后",
                    onClick = { CaptureModeManager.prepareOrdinarySetup(context) },
                    modifier = Modifier.fillMaxWidth(),
                    color = p.secondaryLabel,
                )
            }
        }
    }
}
