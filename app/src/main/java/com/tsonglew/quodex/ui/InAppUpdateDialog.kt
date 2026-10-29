package com.tsonglew.quodex.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quodex.quotaApp
import com.tsonglew.quodex.update.InstallStage
import com.tsonglew.quodex.update.InAppUpdate
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun InAppUpdateDialog(updater: InAppUpdate = LocalContext.current.quotaApp.inAppUpdate) {
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updater.report("若未完成安装，可再次点击安装。")
    }
    fun install() {
        if (preparing) return
        preparing = true
        scope.launch {
            try {
                updater.installIntent()?.let { intent ->
                    try { installer.launch(intent) }
                    catch (_: Exception) { updater.report("无法打开系统安装界面，请稍后重试。") }
                }
            } finally { preparing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else updater.report("尚未允许安装。点击安装可重新授权，已下载的安装包会保留。")
    }
    if (!state.visible) return
    AlertDialog(onDismissRequest = updater::dismiss,
        title = { Text("更新 Quodex") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("in-app-update-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.update?.let { Text("新版本 ${it.versionName}") }
                when (state.stage) {
                    InstallStage.DOWNLOADING -> {
                        if (state.total > 0) {
                            val fraction = (state.received.toFloat() / state.total).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("update-download-progress"))
                            Text("正在下载 ${(fraction * 100).toInt()}% · ${megabytes(state.received)} / ${megabytes(state.total)} MB")
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在准备下载…")
                        }
                        Text("可先关闭此窗口，稍后从更新入口查看进度。", fontSize = 12.sp)
                    }
                    InstallStage.VERIFYING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在校验安装包…") }
                    InstallStage.READY -> {
                        Text("安装包已下载并通过校验。")
                        Text("点击安装后由 Android 确认更新，应用数据会保留。首次使用可能需要允许 Quodex 安装应用。", fontSize = 13.sp, lineHeight = 21.sp)
                    }
                    else -> Unit
                }
                state.message?.let {
                    Text(it, color = if (state.stage == InstallStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            when {
                state.busy -> TextButton(onClick = updater::dismiss) { Text("稍后查看") }
                state.stage == InstallStage.READY -> TextButton(enabled = !preparing,
                    modifier = Modifier.testTag("install-app-update"), onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) install()
                        else try {
                            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                        } catch (_: Exception) { updater.report("无法打开授权页面，请在系统设置中允许 Quodex 安装应用后重试。") }
                    }) { Text(if (preparing) "正在准备…" else "立即安装") }
                else -> TextButton(onClick = { state.update?.let(updater::start) }, modifier = Modifier.testTag("retry-app-update")) { Text("重新下载") }
            }
        },
        dismissButton = {
            if (state.busy) TextButton(onClick = updater::cancel, modifier = Modifier.testTag("cancel-app-update")) { Text("取消下载") }
            else TextButton(onClick = updater::dismiss) { Text("稍后") }
        })
}

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))
