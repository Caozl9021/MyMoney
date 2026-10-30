package io.github.caozl9021.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.ui.design.*

/** Shares the app's tested segmented control; mode selection does not toggle capture. */
@Composable
internal fun CaptureModeSwitch(
    mode: CaptureMode?,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
    gate: ModeGate = ModeGate.READY,
    denied: ModeGate.Denial? = null,
    onGrant: (CaptureAction) -> Unit = {},
    onRootBlocked: () -> Unit = {},
) {
    val p = LocalAbPalette.current
    InsetGroup(modifier) {
        Column(Modifier.fillMaxWidth().padding(AbSpacing.l), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            Segmented(listOf("普通模式", "Root 模式"), if (mode == CaptureMode.ROOT) 1 else 0,
                onSelect = { index ->
                    android.util.Log.i("MyMoneyMode", "使用方式选择: $index")
                    if (index == 0) onSelect(CaptureMode.ACCESSIBILITY)
                    else if (gate == ModeGate.READY) onSelect(CaptureMode.ROOT) else onRootBlocked()
                })
            if (denied != null) {
                Text(denied.detail, style = AbType.footnote, color = p.red)
                denied.action?.let { action -> PrimaryButton(action.label, { onGrant(action) }, Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** Root mode requires su; LSPosed is not a substitute for the app's su grant. */
internal enum class ModeGate {
    READY,

    /** 没有 Root。LSPosed 也属于 Root 生态，所以这里只给一条路：去给 Root。 */
    NO_ROOT,
    ;

    data class Denial(val detail: String, val action: CaptureAction?)
}

/**
 * 把探测结果收成一句人话。
 *
 * @param hasRoot su 是否可用（[com.example.billkit.root.RootShell] 的成功探测结果）
 * @param mode 当前已保存的模式
 *
 * 规则：已经处在 Root 模式时**不再拦**——用户当前正在用，切回来重存没有意义，
 * 而且真机上 Root 授权偶尔会因为 ROM 更新短暂读不到，此时拦下来会把正常工作的人挡在外面。
 */
internal fun modeGate(
    hasRoot: Boolean,
    mode: CaptureMode?,
): ModeGate = when {
    hasRoot -> ModeGate.READY
    mode == CaptureMode.ROOT -> ModeGate.READY
    else -> ModeGate.NO_ROOT
}

/** 选中 Root 但缺能力时的说明与去处。 */
internal fun ModeGate.denial(): ModeGate.Denial? = when (this) {
    ModeGate.READY -> null
    ModeGate.NO_ROOT -> ModeGate.Denial(
        "没有检测到 Root 权限，Root 模式用不了。",
        CaptureAction.ROOT,
    )
}

