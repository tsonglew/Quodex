package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
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
    val texts = localTextResources()
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updater.report(UiText(R.string.if_installation_was_not_completed_tap_install))
    }
    fun install() {
        if (preparing) return
        preparing = true
        scope.launch {
            try {
                updater.installIntent()?.let { intent ->
                    try { installer.launch(intent) }
                    catch (_: Exception) { updater.report(UiText(R.string.could_not_open_the_system_installer_try)) }
                }
            } finally { preparing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else updater.report(UiText(R.string.installation_is_not_yet_allowed_tap_install))
    }
    if (!state.visible) return
    AlertDialog(onDismissRequest = updater::dismiss,
        title = { Text(texts(R.string.update_quodex)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("in-app-update-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.update?.let { Text(texts(R.string.new_version_value, it.versionName)) }
                when (state.stage) {
                    InstallStage.DOWNLOADING -> {
                        if (state.total > 0) {
                            val fraction = (state.received.toFloat() / state.total).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("update-download-progress"))
                            Text(texts(R.string.downloading_value_value_value_mb, (fraction * 100).toInt(), megabytes(state.received), megabytes(state.total)))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(texts(R.string.preparing_download))
                        }
                        Text(texts(R.string.you_can_close_this_dialog_and_check), fontSize = 12.sp)
                    }
                    InstallStage.VERIFYING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(texts(R.string.verifying_apk)) }
                    InstallStage.READY -> {
                        Text(texts(R.string.the_apk_has_been_downloaded_and_verified))
                        Text(texts(R.string.android_will_ask_you_to_confirm_the), fontSize = 13.sp, lineHeight = 21.sp)
                    }
                    else -> Unit
                }
                state.message?.let {
                    Text(it.resolve(texts), color = if (state.stage == InstallStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            when {
                state.busy -> TextButton(onClick = updater::dismiss) { Text(texts(R.string.check_later)) }
                state.stage == InstallStage.READY -> TextButton(enabled = !preparing,
                    modifier = Modifier.testTag("install-app-update"), onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) install()
                        else try {
                            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                        } catch (_: Exception) { updater.report(UiText(R.string.could_not_open_permission_settings_allow_quodex)) }
                    }) { Text(if (preparing) texts(R.string.preparing) else texts(R.string.install_now)) }
                else -> TextButton(onClick = { state.update?.let(updater::start) }, modifier = Modifier.testTag("retry-app-update")) { Text(texts(R.string.download_again)) }
            }
        },
        dismissButton = {
            if (state.busy) TextButton(onClick = updater::cancel, modifier = Modifier.testTag("cancel-app-update")) { Text(texts(R.string.cancel_download)) }
            else TextButton(onClick = updater::dismiss) { Text(texts(R.string.later)) }
        })
}

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))
