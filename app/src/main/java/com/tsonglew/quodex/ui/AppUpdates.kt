package com.tsonglew.quodex.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tsonglew.quodex.BuildConfig
import com.tsonglew.quodex.quotaApp
import com.tsonglew.quodex.data.absoluteTime
import com.tsonglew.quodex.update.AndroidUpdateNotifier
import com.tsonglew.quodex.update.AppUpdate
import com.tsonglew.quodex.update.UpdateState

@Composable
internal fun AvailableUpdateNotice(update: AppUpdate) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("新版本 ${update.versionName} 已发布", fontWeight = FontWeight.SemiBold)
            Text("在 App 内下载，完成后按系统提示安装。", fontSize = 13.sp)
            UpdateAction(update)
        }
    }
    Spacer(Modifier.height(20.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppUpdatesCard(state: UpdateState, onCheck: () -> Unit, onEnabled: (Boolean) -> Unit, onPermissionChanged: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationsEnabled by remember { mutableStateOf(AndroidUpdateNotifier(context).enabled()) }
    var openingFailed by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
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
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("应用更新", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text("当前版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, lineHeight = 20.sp,
                color = colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            PreferenceToggle(
                title = "自动检查与提醒",
                checked = state.saved.enabled,
                onCheckedChange = onEnabled,
                enabled = state.initialized,
                modifier = Modifier.testTag("automatic-app-updates"),
                supportingText = "后台目标间隔 12 小时。",
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
            Spacer(Modifier.height(14.dp))
            when {
                state.checking -> Text("正在检查更新…", fontSize = 13.sp, lineHeight = 20.sp)
                state.error != null -> Text(state.error, fontSize = 13.sp, lineHeight = 20.sp, color = colors.error)
                state.saved.available != null -> Text("可更新到 ${state.saved.available.versionName}",
                    fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                state.saved.lastCheckedAt > 0 -> Text("未发现可覆盖安装的更高版本。", fontSize = 13.sp, lineHeight = 20.sp)
            }
            if (state.saved.lastCheckedAt > 0) {
                Spacer(Modifier.height(4.dp))
                Text("上次检查 ${absoluteTime(state.saved.lastCheckedAt, true)}", fontSize = 12.sp,
                    lineHeight = 20.sp, color = colors.onSurfaceVariant)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onCheck, enabled = !state.checking,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("check-app-update"),
                    contentPadding = PaddingValues(vertical = 10.dp)) { Text("检查更新", fontSize = 14.sp) }
                TextButton(onClick = { showDetails = !showDetails },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("app-update-details"),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Text(if (showDetails) "收起说明" else "更新说明", fontSize = 13.sp)
                }
            }
            state.saved.available?.let { UpdateAction(it) }
            if (BuildConfig.DEBUG) Text("调试安装包 · 仅检查相同签名的更新", fontSize = 12.sp,
                lineHeight = 20.sp, color = colors.onSurfaceVariant)
            if (state.saved.enabled && !notificationsEnabled) {
                Spacer(Modifier.height(10.dp))
                Text("系统通知未开启，仍可在 App 内查看更新。", fontSize = 12.sp,
                    lineHeight = 20.sp, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        TextButton(onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("允许通知", fontSize = 13.sp) }
                    }
                    TextButton(onClick = {
                        openingFailed = !openUpdateIntent(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("通知设置", fontSize = 13.sp) }
                }
            }
            if (showDetails) {
                Spacer(Modifier.height(12.dp))
                Text("后台目标间隔 12 小时，检查 GitHub 的正式版和预发布版，发现可覆盖安装的新版本时提醒一次。系统调度、省电或断网可能延迟检查。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(10.dp))
                    Text("当前是调试安装包，只检查相同签名的更新。使用正式发布包后可接收正式渠道的升级提醒。",
                        fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                }
            }
            if (openingFailed) Text("无法打开系统通知设置，请从手机设置中进入 Quodex。",
                fontSize = 12.sp, lineHeight = 20.sp, color = colors.error)
        }
    }
}

@Composable
private fun UpdateAction(update: AppUpdate) {
    val context = LocalContext.current
    FilledTonalButton(onClick = { context.quotaApp.inAppUpdate.start(update) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("open-app-update")) {
        Text("下载并安装更新", fontSize = 14.sp, lineHeight = 21.sp)
    }
}

private fun openUpdateIntent(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: android.content.ActivityNotFoundException) { false }
catch (_: SecurityException) { false }
