package io.github.mymoney.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.mymoney.ui.design.AppAlertDialog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全屏扫码页：CameraX 实时预览 + 本地 ZXing 解码。
 *
 * 只负责「把相机里的一帧二维码读成字符串」，读到一次就回调 [onDecoded] 并停止分析；
 * 是否合法连接码、要不要确认、连不联网全部交给上层（扫码确认前绝不联网/不改配置）。
 * 相机在 composable 离开时 release，单帧去重避免重复回调。
 */
@Composable
internal fun QrScannerScreen(
    onDecoded: (String) -> Unit,
    onCancel: () -> Unit,
    onPermissionDenied: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) onPermissionDenied()
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        CameraPreview(onDecoded = onDecoded, lifecycleOwner = lifecycleOwner)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) { Text("把服务器管理页的二维码对准取景框", color = Color.White, textAlign = TextAlign.Center) }
            TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
        }
    }
}

@Composable
private fun CameraPreview(
    onDecoded: (String) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // 一旦解出就不再重复回调，直到本页销毁。
    val delivered = remember { AtomicBoolean(false) }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var bound = false
        providerFuture.addListener({
            provider = providerFuture.get()
            try {
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                analysis.setAnalyzer(executor) { imageProxy ->
                    if (delivered.get()) { imageProxy.close(); return@setAnalyzer }
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val text = QrImageDecoder.decodeYuv(bytes, imageProxy.width, imageProxy.height)
                    if (text != null && delivered.compareAndSet(false, true)) onDecoded(text)
                    imageProxy.close()
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                bound = true
            } catch (_: Exception) {
                // 相机不可用/无镜头时保持预览黑屏，用户可取消返回用粘贴方式。
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { if (bound) provider?.unbindAll() }
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** 权限被拒时的轻提示对话框：引导用户改用粘贴连接码或去系统设置授权。 */
@Composable
internal fun CameraPermissionDialog(onDismiss: () -> Unit) {
    AppAlertDialog(
        title = "需要相机权限",
        message = "扫码连接需要相机权限。若不方便授权，可返回改用「粘贴连接码」。",
        onDismiss = onDismiss,
        confirmLabel = "知道了",
        dismissLabel = null,
        onConfirm = onDismiss,
    )
}
