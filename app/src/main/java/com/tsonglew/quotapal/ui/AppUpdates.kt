package com.tsonglew.quotapal.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tsonglew.quotapal.BuildConfig
import com.tsonglew.quotapal.data.absoluteTime
import com.tsonglew.quotapal.update.AndroidUpdateNotifier
import com.tsonglew.quotapal.update.AppUpdate
import com.tsonglew.quotapal.update.UpdateState

@Composable
internal fun AvailableUpdateNotice(update: AppUpdate) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("新版本 ${update.versionName} 已发布", fontWeight = FontWeight.SemiBold)
            Text("查看更新说明，下载后按系统提示安装。", fontSize = 13.sp)
            ReleaseLink(update)
        }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun AppUpdatesCard(state: UpdateState, onCheck: () -> Unit, onEnabled: (Boolean) -> Unit, onPermissionChanged: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationsEnabled by remember { mutableStateOf(AndroidUpdateNotifier(context).enabled()) }
    var openingFailed by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = AndroidUpdateNotifier(context).enabled()
        onPermissionChanged()
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = AndroidUpdateNotifier(context).enabled()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("应用更新", fontWeight = FontWeight.Medium)
            Text("当前版本 ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PreferenceToggle(
                title = "自动检查与提醒",
                checked = state.saved.enabled,
                onCheckedChange = onEnabled,
                enabled = state.initialized,
                modifier = Modifier.testTag("automatic-app-updates"),
            )
            Text("后台目标间隔 12 小时，检查 GitHub 的正式版和预发布版，发现可覆盖安装的新版本时提醒一次。系统调度、省电或断网可能延迟检查。", fontSize = 12.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.saved.enabled && !notificationsEnabled) {
                Text("系统通知未开启，仍可在 App 内查看更新。", fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        TextButton(onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知") }
                    }
                    TextButton(onClick = {
                        openingFailed = !openUpdateIntent(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("通知设置") }
                }
            }
            if (BuildConfig.DEBUG) Text("当前是调试安装包，只检查相同签名的更新。使用正式发布包后可接收正式渠道的升级提醒。", fontSize = 12.sp, lineHeight = 20.sp)
            when {
                state.checking -> Text("正在检查更新…", fontSize = 13.sp)
                state.error != null -> Text(state.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                state.saved.available != null -> Text("可更新到 ${state.saved.available.versionName}", fontSize = 13.sp)
                state.saved.lastCheckedAt > 0 -> Text("未发现可覆盖安装的更高版本。", fontSize = 13.sp)
            }
            if (state.saved.lastCheckedAt > 0) Text("上次检查 ${absoluteTime(state.saved.lastCheckedAt, true)}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onCheck, enabled = !state.checking, modifier = Modifier.testTag("check-app-update")) { Text("检查更新") }
            state.saved.available?.let { ReleaseLink(it) }
            if (openingFailed) Text("无法打开系统通知设置，请从手机设置中进入 QuotaPal。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReleaseLink(update: AppUpdate) {
    val context = LocalContext.current
    var failed by remember(update.releaseUrl) { mutableStateOf(false) }
    TextButton(onClick = { failed = !openUpdateIntent(context, Intent(Intent.ACTION_VIEW, update.releaseUrl.toUri())) },
        modifier = Modifier.testTag("open-app-update")) { Text("查看更新并下载") }
    if (failed) Text("无法打开下载页，请安装或启用浏览器后重试。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
}

private fun openUpdateIntent(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: android.content.ActivityNotFoundException) { false }
catch (_: SecurityException) { false }
