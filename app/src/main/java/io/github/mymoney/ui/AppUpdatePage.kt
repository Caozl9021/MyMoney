package io.github.mymoney.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.update.AppUpdateStore
import io.github.mymoney.data.update.GitHubAppUpdates
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 关于页：版本信息与 GitHub Release App 更新。 */
@Composable
fun AppUpdatePage(onBack: () -> Unit, onOpenDevelopment: () -> Unit = {}) {
    val context = LocalContext.current
    val state by AppUpdateStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = info.versionName ?: "未知"
    val versionCode = PackageInfoCompat.getLongVersionCode(info)

    LaunchedEffect(Unit) {
        AppUpdateStore.init(context.applicationContext)
    }

    SettingsContentPage("关于", onBack) {
        InsetGroup {
            ListRow(title = "麦米 My Money", subtitle = "版本 $versionName（$versionCode）")
            ListRow(
                title = "App 更新",
                subtitle = statusText(state, versionName),
                dividerAbove = true,
            )
        }

        state.candidate?.let { candidate ->
            InsetGroup {
                ListRow(
                    title = "新版本 ${candidate.manifest.versionName}（${candidate.manifest.versionCode}）",
                    subtitle = "需要 Android ${candidate.manifest.minSdk} 及以上 · ${formatSize(candidate.manifest.sizeBytes)}",
                )
                ListRow(
                    title = "更新说明",
                    subtitle = candidate.manifest.notes.ifBlank { "暂无说明" },
                    subtitleMaxLines = 20,
                    dividerAbove = true,
                )
            }
        }

        when (state.status) {
            AppUpdateStore.Status.CHECKING -> PrimaryButton(
                text = "正在检查…", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false, busy = true,
            )
            AppUpdateStore.Status.DOWNLOADING -> {
                PrimaryButton(
                    text = "下载中 ${state.progress}%", onClick = {}, modifier = Modifier.fillMaxWidth(),
                    enabled = false, busy = true, busyLabel = "下载中 ${state.progress}%",
                )
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
                    text = "删除已下载安装包", onClick = { AppUpdateStore.clearDownload() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AppUpdateStore.Status.NEED_PERMISSION -> PrimaryButton(
                text = "去允许安装", onClick = { AppUpdateStore.openInstallPermissionSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppUpdateStore.Status.INSTALL_LAUNCHED -> SecondaryButton(
                text = "重新打开安装器", onClick = { AppUpdateStore.install(context) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppUpdateStore.Status.INCOMPATIBLE -> {
                PrimaryButton(
                    text = "重新检查", onClick = {
                        scope.launch(Dispatchers.IO) { AppUpdateStore.checkForUpdates() }
                    }, modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> PrimaryButton(
                text = "检查更新", onClick = {
                    scope.launch(Dispatchers.IO) { AppUpdateStore.checkForUpdates() }
                }, modifier = Modifier.fillMaxWidth(),
            )
        }
        InsetGroup {
            ListRow(title = "开发调试", subtitle = "识别帮助、通知与页面诊断",
                leading = { io.github.mymoney.ui.design.SettingsIconBadge("开发调试", io.github.mymoney.ui.design.AbIcons.Terminal) },
                chevron = true, onClick = onOpenDevelopment)
        }
        if (state.status != AppUpdateStore.Status.CHECKING &&
            state.status != AppUpdateStore.Status.DOWNLOADING
        ) {
            SecondaryButton(
                text = "打开 GitHub Releases",
                onClick = { openReleases(context) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun statusText(state: AppUpdateStore.State, currentVersion: String): String = when (state.status) {
    AppUpdateStore.Status.IDLE -> state.message ?: "尚未检查。点击下方按钮从 GitHub Releases 检查更新。"
    AppUpdateStore.Status.CHECKING -> "正在检查更新…"
    AppUpdateStore.Status.UP_TO_DATE -> "当前已是最新版本（$currentVersion）。"
    AppUpdateStore.Status.AVAILABLE -> state.message ?: "发现可用更新 ${state.candidate?.manifest?.versionName.orEmpty()}。"
    AppUpdateStore.Status.INCOMPATIBLE -> {
        val manifest = state.incompatible?.manifest
        if (manifest == null) "当前系统没有可用的更新版本。"
        else "最新版本需要 Android ${manifest.minSdk} 及以上；当前系统为 API ${Build.VERSION.SDK_INT}。"
    }
    AppUpdateStore.Status.DOWNLOADING -> "正在下载 ${state.progress}%…"
    AppUpdateStore.Status.READY -> "已下载 ${state.downloaded?.manifest?.versionName.orEmpty()}，可交给系统安装器。"
    AppUpdateStore.Status.NEED_PERMISSION -> state.message ?: "请允许麦米安装应用。"
    AppUpdateStore.Status.INSTALL_LAUNCHED -> state.message ?: "已打开系统安装器。"
    AppUpdateStore.Status.ERROR -> state.message ?: "检查更新失败，请稍后重试。"
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else "%.0f KB".format(bytes / 1024.0)

private fun openReleases(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GitHubAppUpdates.RELEASES_URL)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
