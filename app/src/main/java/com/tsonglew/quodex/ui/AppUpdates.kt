package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
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
    val texts = localTextResources()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(texts(R.string.version_value_is_available, update.versionName), fontWeight = FontWeight.SemiBold)
            Text(texts(R.string.download_in_the_app_then_follow_android), fontSize = 13.sp)
            UpdateAction(update)
        }
    }
    Spacer(Modifier.height(20.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppUpdatesCard(state: UpdateState, onCheck: () -> Unit, onEnabled: (Boolean) -> Unit, onPermissionChanged: () -> Unit) {
    val texts = localTextResources()
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
            Text(texts(R.string.app_updates), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(texts(R.string.current_version_value, BuildConfig.VERSION_NAME), fontSize = 12.sp, lineHeight = 20.sp,
                color = colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            PreferenceToggle(
                title = texts(R.string.automatic_checks_and_alerts),
                checked = state.saved.enabled,
                onCheckedChange = onEnabled,
                enabled = state.initialized,
                modifier = Modifier.testTag("automatic-app-updates"),
                supportingText = texts(R.string.target_background_interval_12_hours),
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
            Spacer(Modifier.height(14.dp))
            when {
                state.checking -> Text(texts(R.string.checking_for_updates), fontSize = 13.sp, lineHeight = 20.sp)
                state.error != null -> Text(state.error.resolve(texts), fontSize = 13.sp, lineHeight = 20.sp, color = colors.error)
                state.saved.available != null -> Text(texts(R.string.update_available_value, state.saved.available.versionName),
                    fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                state.saved.lastCheckedAt > 0 -> Text(texts(R.string.no_newer_compatible_update_was_found), fontSize = 13.sp, lineHeight = 20.sp)
            }
            if (state.saved.lastCheckedAt > 0) {
                Spacer(Modifier.height(4.dp))
                Text(texts(R.string.last_checked_value, absoluteTime(state.saved.lastCheckedAt, true)), fontSize = 12.sp,
                    lineHeight = 20.sp, color = colors.onSurfaceVariant)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onCheck, enabled = !state.checking,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("check-app-update"),
                    contentPadding = PaddingValues(vertical = 10.dp)) { Text(texts(R.string.check_for_updates), fontSize = 14.sp) }
                TextButton(onClick = { showDetails = !showDetails },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("app-update-details"),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Text(if (showDetails) texts(R.string.hide_details) else texts(R.string.update_details), fontSize = 13.sp)
                }
            }
            state.saved.available?.let { UpdateAction(it) }
            if (BuildConfig.DEBUG) Text(texts(R.string.debug_build_only_checks_updates_with_the), fontSize = 12.sp,
                lineHeight = 20.sp, color = colors.onSurfaceVariant)
            if (state.saved.enabled && !notificationsEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(texts(R.string.notifications_are_disabled_updates_are_still_visible), fontSize = 12.sp,
                    lineHeight = 20.sp, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        TextButton(onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(texts(R.string.allow_notifications), fontSize = 13.sp) }
                    }
                    TextButton(onClick = {
                        openingFailed = !openUpdateIntent(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(texts(R.string.notification_settings), fontSize = 13.sp) }
                }
            }
            if (showDetails) {
                Spacer(Modifier.height(12.dp))
                Text(texts(R.string.checks_github_releases_and_prereleases_about_every),
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(10.dp))
                    Text(texts(R.string.this_is_a_debug_build_and_only),
                        fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                }
            }
            if (openingFailed) Text(texts(R.string.could_not_open_notification_settings_open_quodex),
                fontSize = 12.sp, lineHeight = 20.sp, color = colors.error)
        }
    }
}

@Composable
private fun UpdateAction(update: AppUpdate) {
    val texts = localTextResources()
    val context = LocalContext.current
    FilledTonalButton(onClick = { context.quotaApp.inAppUpdate.start(update) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("open-app-update")) {
        Text(texts(R.string.download_and_install_update), fontSize = 14.sp, lineHeight = 21.sp)
    }
}

private fun openUpdateIntent(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: android.content.ActivityNotFoundException) { false }
catch (_: SecurityException) { false }
