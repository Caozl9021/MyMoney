package io.github.mymoney.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mymoney.ui.design.LocalAbPalette

/** ID-only internal navigation; the shared host observes the current entity instead of a draft copy. */
class BillResultActivity : ComponentActivity() {
    private val foregroundRefreshRate by lazy { ForegroundRefreshRate(window) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        configureConfirmationWindow()
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, 0)
        if (id <= 0) { finish(); return }
        setContent {
            MyMoneyTheme {
                Surface(color = LocalAbPalette.current.pageBg, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        BillActionsHost(billId = id, onClose = { finish() })
                        Box(Modifier.align(Alignment.BottomCenter)) { BillUndoStack() }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "bill_id"
        fun open(context: Context, id: Long): Boolean = runCatching {
            context.startActivity(Intent(context, BillResultActivity::class.java).putExtra(EXTRA_ID, id).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrElse {
            Toast.makeText(context, "打开账单失败，可在明细中查看", Toast.LENGTH_LONG).show()
            false
        }
    }
}
