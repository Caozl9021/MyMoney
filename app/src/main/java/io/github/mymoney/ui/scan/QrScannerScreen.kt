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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.mymoney.ui.ActiveBackHandler
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AppAlertDialog
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.SecondaryButton
import io.github.mymoney.ui.design.rememberAbHaptic
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
    val haptic = rememberAbHaptic()
    val p = LocalAbPalette.current
    var cameraFailed by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    ActiveBackHandler(onBack = onCancel)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) onPermissionDenied()
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission && !cameraFailed) {
            CameraPreview(
                onDecoded = { code ->
                    haptic.confirm()
                    onDecoded(code)
                },
                onError = { cameraFailed = true },
                lifecycleOwner = lifecycleOwner,
            )
            // Centered corner reticle so "对准取景框" matches what is drawn on screen.
            Canvas(Modifier.fillMaxSize()) {
                val frameSide = minOf(size.width, size.height) * 0.64f
                val left = (size.width - frameSide) * 0.5f
                val top = (size.height - frameSide) * 0.44f
                val right = left + frameSide
                val bottom = top + frameSide
                val corner = 24.dp.toPx()
                val strokeWidth = 3.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.25f),
                    topLeft = Offset(left, top),
                    size = Size(frameSide, frameSide),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
                val brackets = Path().apply {
                    moveTo(left, top + corner); lineTo(left, top); lineTo(left + corner, top)
                    moveTo(right - corner, top); lineTo(right, top); lineTo(right, top + corner)
                    moveTo(left, bottom - corner); lineTo(left, bottom); lineTo(left + corner, bottom)
                    moveTo(right - corner, bottom); lineTo(right, bottom); lineTo(right, bottom - corner)
                }
                drawPath(
                    brackets,
                    color = p.accent,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        } else if (cameraFailed) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = AbSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
            ) {
                Text("无法启动相机预览", style = AbType.headline, color = Color.White)
                Text(
                    "请检查相机权限或改用粘贴连接码连接电脑端。",
                    style = AbType.footnote,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                )
                SecondaryButton("改用粘贴连接码", onClick = onCancel)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 88.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!cameraFailed) {
                Box(
                    Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(12.dp),
                ) { Text("把服务器管理页的二维码对准取景框", color = Color.White, textAlign = TextAlign.Center) }
            }
            AbTextButton("取消", onClick = onCancel, color = Color.White)
        }
    }
}

@Composable
private fun CameraPreview(
    onDecoded: (String) -> Unit,
    onError: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
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
            try {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                analysis.setAnalyzer(executor) { imageProxy ->
                    if (delivered.get()) { imageProxy.close(); return@setAnalyzer }
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val text = QrImageDecoder.decodeYuv(bytes, imageProxy.width, imageProxy.height)
                    if (text != null && delivered.compareAndSet(false, true)) {
                        mainExecutor.execute { onDecoded(text) }
                    }
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
                onError()
            }
        }, mainExecutor)

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
