package io.github.mymoney.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.update.AppUpdateStore
import io.github.mymoney.data.update.GitHubAppUpdates
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.GroupFooter
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.SecondaryButton
import io.github.mymoney.ui.design.SettingsIconBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 关于页：版本信息与 App 更新；连点版本号 5 次显示页面诊断。 */
@Composable
fun AppUpdatePage(onBack: () -> Unit, onOpenDebug: () -> Unit = {}) {
    val context = LocalContext.current
    val state by AppUpdateStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = info.versionName ?: "未知"
    val versionCode = PackageInfoCompat.getLongVersionCode(info)
    val prefs = remember(context) { context.getSharedPreferences("about_page", Context.MODE_PRIVATE) }
    var devUnlocked by remember { mutableStateOf(prefs.getBoolean(KEY_DEV_UNLOCKED, false)) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        AppUpdateStore.init(context.applicationContext)
    }

    SettingsContentPage("关于", onBack) {
        InsetGroup {
            ListRow(
                title = "麦米",
                subtitle = "$versionName ($versionCode)",
                onClick = {
                    // 连点 5 次来回切换：未解锁则开启，已解锁则关闭。
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTapAt > TAP_WINDOW_MS) 1 else taps + 1
                    lastTapAt = now
                    if (taps >= TAP_COUNT) {
                        taps = 0
                        devUnlocked = !devUnlocked
                        prefs.edit().putBoolean(KEY_DEV_UNLOCKED, devUnlocked).apply()
                        feedback = if (devUnlocked) "已开启开发者选项" else "已关闭开发者选项"
                    }
                },
            )
            statusText(state, versionName)?.let { status ->
                ListRow(
                    title = "更新",
                    subtitle = status,
                )
            }
        }
        feedback?.let { GroupFooter(it) }

        state.candidate?.let { candidate ->
            InsetGroup {
                ListRow(
                    title = "新版本 ${candidate.manifest.versionName} (${candidate.manifest.versionCode})",
                    subtitle = "Android ${candidate.manifest.minSdk}+ · ${formatSize(candidate.manifest.sizeBytes)}",
                )
                ListRow(
                    title = "更新说明",
                    subtitle = candidate.manifest.notes.ifBlank { "无" },
                    subtitleMaxLines = 20,
                )
            }
        }

        when (state.status) {
            AppUpdateStore.Status.CHECKING -> PrimaryButton(
                text = "检查中…", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false, busy = true,
            )
            AppUpdateStore.Status.DOWNLOADING -> {
                val animatedProgress by animateFloatAsState(
                    targetValue = state.progress.coerceIn(0, 100) / 100f,
                    animationSpec = if (LocalReduceMotion.current) tween(90, easing = AbEasing.EaseOut)
                    else tween(180, easing = AbEasing.EaseOut),
                    label = "update-download-progress",
                )
                PrimaryButton(
                    text = "下载中 ${state.progress}%", onClick = {}, modifier = Modifier.fillMaxWidth(),
                    enabled = false, busy = true, busyLabel = "下载中 ${state.progress}%",
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(LocalAbPalette.current.fill),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .background(LocalAbPalette.current.accent, RoundedCornerShape(100.dp)),
                    )
                }
                SecondaryButton(
                    text = "取消下载", onClick = { AppUpdateStore.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AppUpdateStore.Status.AVAILABLE -> PrimaryButton(
                text = "下载更新", onClick = {
                    scope.launch(Dispatchers.IO) { AppUpdateStore.download() }
                }, modifier = Modifier.fillMaxWidth(),
            )
            AppUpdateStore.Status.READY -> {
                PrimaryButton(
                    text = "安装更新", onClick = { AppUpdateStore.install(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = "删除安装包", onClick = { AppUpdateStore.clearDownload() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AppUpdateStore.Status.NEED_PERMISSION -> PrimaryButton(
                text = "允许安装", onClick = { AppUpdateStore.openInstallPermissionSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppUpdateStore.Status.INSTALL_LAUNCHED -> SecondaryButton(
                text = "重新打开安装器", onClick = { AppUpdateStore.install(context) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppUpdateStore.Status.INCOMPATIBLE -> PrimaryButton(
                text = "重新检查", onClick = {
                    scope.launch(Dispatchers.IO) { AppUpdateStore.checkForUpdates() }
                }, modifier = Modifier.fillMaxWidth(),
            )
            else -> PrimaryButton(
                text = "检查更新", onClick = {
                    scope.launch(Dispatchers.IO) { AppUpdateStore.checkForUpdates() }
                }, modifier = Modifier.fillMaxWidth(),
            )
        }

        if (devUnlocked) {
            InsetGroup {
                ListRow(
                    title = "页面诊断",
                    leading = { SettingsIconBadge("页面诊断", AbIcons.Ocr) },
                    chevron = true,
                    onClick = onOpenDebug,
                )
            }
        }

        if (state.status != AppUpdateStore.Status.CHECKING &&
            state.status != AppUpdateStore.Status.DOWNLOADING
        ) {
            SecondaryButton(
                text = "GitHub Releases",
                onClick = { openReleases(context) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 连点版本号解锁开发者入口；解锁后常驻，不展示给普通用户。 */
private const val TAP_COUNT = 5
private const val TAP_WINDOW_MS = 2_000L
private const val KEY_DEV_UNLOCKED = "dev_unlocked"

private fun statusText(state: AppUpdateStore.State, currentVersion: String): String? = when (state.status) {
    AppUpdateStore.Status.IDLE -> state.message
    AppUpdateStore.Status.CHECKING -> "检查中…"
    AppUpdateStore.Status.UP_TO_DATE -> "已是最新（$currentVersion）"
    AppUpdateStore.Status.AVAILABLE -> state.message ?: "有新版本 ${state.candidate?.manifest?.versionName.orEmpty()}"
    AppUpdateStore.Status.INCOMPATIBLE -> {
        val manifest = state.incompatible?.manifest
        if (manifest == null) "暂无兼容版本"
        else "需要 Android ${manifest.minSdk}+（当前 API ${Build.VERSION.SDK_INT}）"
    }
    AppUpdateStore.Status.DOWNLOADING -> "下载中 ${state.progress}%"
    AppUpdateStore.Status.READY -> "已下载 ${state.downloaded?.manifest?.versionName.orEmpty()}"
    AppUpdateStore.Status.NEED_PERMISSION -> state.message ?: "请允许安装应用"
    AppUpdateStore.Status.INSTALL_LAUNCHED -> state.message ?: "已打开安装器"
    AppUpdateStore.Status.ERROR -> state.message ?: "检查失败"
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else "%.0f KB".format(bytes / 1024.0)

private fun openReleases(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GitHubAppUpdates.RELEASES_URL)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
