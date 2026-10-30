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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.PrimaryButton

/**
 * 首次启动的引导页。**只问一件事：要不要开始自动记账。**
 *
 * 以前这一页让用户在「普通模式 / Root 模式」里二选一，等于开局就要他回答一个自己无从判断的问题，
 * 而且选错就要在设置里改回来。现在没有这个选择了：一键把通知与页面两条通道打开，再按步骤补授权。
 * 需要 Root 才有意义的东西（凭证采集、Root 读通知兜底）在设备真有 root 时自己会出现在设置里，
 * 不需要用户在这一页做决定。
 */
@Composable
fun CaptureModeScreen(onOrdinarySetup: () -> Unit) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    Column(Modifier.fillMaxSize().background(p.pageBg).systemBarsPadding()) {
        TextButton(onClick = {
            // 什么都不开，只落一份暂停态配置：用户下次进设置能看到入口，而不是一片空白。
            CaptureModeManager.prepareOrdinarySetup(context)
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xxl)) {
            Text("稍后设置")
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(AbSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xl),
        ) {
            Text("开启自动记账", style = AbType.largeTitle, color = p.label)
            Text(
                "麦米从微信、支付宝的交易通知里读出金额和商户，生成草稿等你确认后再记账。" +
                    "没有通知时，页面辅助可以补上。",
                style = AbType.subhead, color = p.secondaryLabel,
            )
            Text(
                "接下来会依次帮你打开通知使用权和页面辅助所需的系统权限；任何一步都可以先跳过，" +
                    "之后在设置里补。",
                style = AbType.footnote, color = p.secondaryLabel,
            )
            PrimaryButton("一键开启", {
                // 只是把两条通道打开，不是选模式：写完谁在跑仍由各自的可用性决定。
                CaptureModeManager.applyPreset(context, CaptureMode.ACCESSIBILITY, lspInstalled = false)
                onOrdinarySetup()
            }, Modifier.fillMaxWidth())
        }
    }
}
