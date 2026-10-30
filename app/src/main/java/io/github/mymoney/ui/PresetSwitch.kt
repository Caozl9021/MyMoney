package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.ui.design.*

/**
 * 「推荐配置」：一键把几条通道的开关设成一组常见组合。
 *
 * 它**不是**模式选择。阶段 4 之前这里叫「使用方式」，选的是一组互斥能力——选了 Root 就没有
 * 页面辅助，选了普通就用不了凭证采集。现在它只写开关，写完谁在跑仍由各自可用性决定；
 * 用户随时可以在下面的通道行里单独改，预设不会把他锁住。
 *
 * 只在设备真有 root 时才渲染（见 [DeviceCapabilities]）——没有 root 的机器上「Root 增强」
 * 是个永远点不动的选项，不如不显示。
 */
@Composable
internal fun PresetSwitch(
    mode: CaptureMode?,
    lspInstalled: Boolean,
    onApply: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAbPalette.current
    InsetGroup(modifier) {
        Column(Modifier.fillMaxWidth().padding(AbSpacing.l), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            Segmented(
                listOf("普通记账", "Root 增强"),
                if (mode == CaptureMode.ROOT) 1 else 0,
                onSelect = { index -> onApply(if (index == 0) CaptureMode.ACCESSIBILITY else CaptureMode.ROOT) },
            )
            Text(
                if (mode == CaptureMode.ROOT) {
                    if (lspInstalled) "通知 + 凭证采集；页面辅助在通道里单独开。"
                    else "通知 + Root 读通知兜底；装了 LSPosed 后还能开凭证采集。"
                } else {
                    "通知 + 页面辅助。"
                },
                style = AbType.footnote, color = p.secondaryLabel,
            )
        }
    }
}
