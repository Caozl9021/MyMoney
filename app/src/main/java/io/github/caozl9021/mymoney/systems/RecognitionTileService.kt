package io.github.caozl9021.mymoney.systems

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import io.github.caozl9021.mymoney.R
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.caozl9021.mymoney.capture.CaptureModeManager
import io.github.caozl9021.mymoney.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The tile toggles only the persisted recognition switch through its coordinator. */
class RecognitionTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        CaptureModeManager.init(this)
        observing?.cancel()
        observing = scope.launch {
            CaptureModeManager.state.collectLatest { updateTile() }
        }
        updateTile()
    }

    override fun onStopListening() {
        observing?.cancel()
        observing = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // A locked notification shade must never change recognition behind the lock screen.
        if (isLocked) unlockAndRun { performClick() } else performClick()
    }

    private fun performClick() {
        CaptureModeManager.init(this)
        val state = CaptureModeManager.state.value
        if (state.mode == null) {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 9102, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } else {
            CaptureModeManager.setEnabled(this, !state.enabled)
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val presentation = SystemEntryPolicy.tile(CaptureModeManager.state.value)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_brand_small)
        tile.label = getString(R.string.recognition_tile_label)
        tile.state = if (presentation.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = presentation.subtitle
        tile.contentDescription = "${getString(R.string.recognition_tile_label)}，${presentation.subtitle}"
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
