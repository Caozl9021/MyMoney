package io.github.mymoney.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.PrimaryButton

/** 首次启动引导页。 */
@Composable
fun CaptureModeScreen(onOrdinarySetup: () -> Unit) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    Column(Modifier.fillMaxSize().background(p.pageBg).systemBarsPadding()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(AbSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xl),
        ) {
            Text("自动记账", style = AbType.largeTitle, color = p.label)
            Text(
                "打开微信、支付宝交易详情页时自动弹出确认卡。",
                style = AbType.subhead,
                color = p.secondaryLabel,
            )
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
