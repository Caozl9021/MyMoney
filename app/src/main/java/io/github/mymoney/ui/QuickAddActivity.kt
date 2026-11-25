package io.github.mymoney.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mymoney.capture.CaptureModeManager
import io.github.mymoney.data.AssetStore

/**
 * 桌面速记小组件入口：透明景深糊底的半屏手动确认卡，保存或取消后直接退回桌面。
 */
class QuickAddActivity : ComponentActivity() {
    private val foregroundRefreshRate by lazy { ForegroundRefreshRate(window) }
    private var launchTime by mutableLongStateOf(System.currentTimeMillis())
    private var launchSource by mutableStateOf("widget:quick:${launchTime}")

    override fun onResume() {
        super.onResume()
        foregroundRefreshRate.resume()
    }

    override fun onPause() {
        foregroundRefreshRate.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        foregroundRefreshRate.focusChanged(hasFocus)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val now = System.currentTimeMillis()
        launchTime = now
        launchSource = "widget:quick:$now"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureConfirmationWindow(blurBehind = true)
        super.onCreate(savedInstanceState)
        CaptureModeManager.init(this)
        AssetStore.init(this)
        setContent {
            MyMoneyTheme {
                Box(Modifier.fillMaxSize()) {
                    BillEditorDialog(
                        original = null,
                        newSource = launchSource,
                        newTime = launchTime,
                        onClose = { finish() },
                    )
                    ReimburseNoticeBar(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}
