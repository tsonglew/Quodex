package com.tsonglew.quodex.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tsonglew.quodex.R
import com.tsonglew.quodex.i18n.AndroidTextResources
import com.tsonglew.quodex.i18n.localTextResources

internal enum class HomePinTarget { ICON, STANDARD_WIDGET, COMPACT_WIDGET, TOKEN_HEATMAP }

internal val LocalHomePinGuideRequired = staticCompositionLocalOf {
    BackgroundBrand.detect(Build.MANUFACTURER, Build.BRAND) == BackgroundBrand.XIAOMI
}

/** Xiaomi can silently reject either pin API even when it returns true. */
@Composable
internal fun rememberHomePinRequest(onRequest: (HomePinTarget) -> Unit): (HomePinTarget) -> Unit {
    val guideRequired = LocalHomePinGuideRequired.current
    val context = LocalContext.current
    val texts = localTextResources()
    val currentRequest by rememberUpdatedState(onRequest)
    // Keep the selected widget across settings, configuration changes and process recreation.
    var pendingTarget by rememberSaveable { mutableStateOf<String?>(null) }

    pendingTarget?.let { target ->
        AlertDialog(
            modifier = Modifier.testTag("home-pin-permission-dialog"),
            onDismissRequest = { pendingTarget = null },
            title = { Text(texts(R.string.home_pin_permission_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(texts(R.string.home_pin_permission_explanation))
                    Text(texts(R.string.home_pin_permission_steps))
                    Text(texts(R.string.home_pin_permission_return))
                    OutlinedButton(
                        onClick = { openHomePinSettings(context) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("home-pin-open-settings")
                    ) { Text(texts(R.string.home_pin_permission_settings)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingTarget = null
                    currentRequest(HomePinTarget.valueOf(target))
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("home-pin-continue")) {
                    Text(texts(R.string.home_pin_permission_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(texts(R.string.cancel))
                }
            }
        )
    }

    return remember(guideRequired) {
        { target ->
            // No supported Android API exposes this Xiaomi permission. Do not claim
            // it is denied or persist an unverified "granted" flag after visiting settings.
            if (guideRequired) pendingTarget = target.name else currentRequest(target)
        }
    }
}

private fun openHomePinSettings(context: Context) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    for (intent in listOf(details, Intent(Settings.ACTION_SETTINGS))) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Older/custom settings apps may omit the per-app details page.
        } catch (_: SecurityException) {
            // Keep the instructions available if a vendor denies this entry point.
        }
    }
    Toast.makeText(context, AndroidTextResources(context)(R.string.could_not_open_system_settings_search_settings), Toast.LENGTH_LONG).show()
}
