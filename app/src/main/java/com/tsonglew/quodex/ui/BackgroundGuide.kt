package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal enum class BackgroundBrand(val label: Int, val steps: List<Int>) {
    XIAOMI(R.string.xiaomi_redmi_poco, listOf(
        R.string.allow_autostart_settings_apps_manage_apps_permissions,
        R.string.relax_battery_restrictions_app_info_battery_saver,
        R.string.keep_in_background_open_recent_apps_press)),
    OPPO(R.string.brand_oppo, listOf(
        R.string.allow_startup_search_settings_for_autostart_or,
        R.string.allow_background_activity_app_info_battery_usage,
        R.string.keep_in_background_open_the_quodex_card)),
    VIVO(R.string.brand_vivo, listOf(
        R.string.allow_autostart_settings_apps_and_permissions_permission,
        R.string.allow_background_activity_settings_battery_background_power,
        R.string.keep_in_background_open_the_quodex_card_16)),
    HUAWEI(R.string.huawei_honor_android, listOf(
        R.string.manage_startup_manually_search_settings_for_app,
        R.string.allow_activity_in_manual_management_enable_autostart,
        R.string.keep_in_background_swipe_down_on_the)),
    SAMSUNG(R.string.brand_samsung, listOf(
        R.string.exclude_from_sleep_settings_battery_device_care,
        R.string.allow_background_activity_app_info_battery_select,
        R.string.recent_apps_enable_keep_open_if_the)),
    GENERIC(R.string.pixel_stock_android_other, listOf(
        R.string.allow_background_activity_app_info_app_battery,
        R.string.check_network_access_app_info_mobile_data,
        R.string.autostart_and_locking_stock_android_usually_has));

    companion object {
        fun detect(manufacturer: String, brand: String): BackgroundBrand {
            val names = listOf(manufacturer, brand).map { it.trim().lowercase(java.util.Locale.ROOT) }
            return when {
                names.any { it in setOf("xiaomi", "redmi", "poco") } -> XIAOMI
                names.any { it in setOf("oppo", "oneplus", "realme") } -> OPPO
                names.any { it in setOf("vivo", "iqoo") } -> VIVO
                names.any { it in setOf("huawei", "honor") } -> HUAWEI
                "samsung" in names -> SAMSUNG
                else -> GENERIC
            }
        }
    }
}

@Composable
internal fun BackgroundGuide() {
    val texts = localTextResources()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(BackgroundBrand.detect(Build.MANUFACTURER, Build.BRAND).name) }
    var menu by remember { mutableStateOf(false) }
    fun batteryExempt() = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    var exempt by remember { mutableStateOf(batteryExempt()) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) exempt = batteryExempt() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val guide = BackgroundBrand.valueOf(selected)
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(texts(R.string.background_updates), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(texts(R.string.updates_stop_after_clearing_recent_apps_check),
                fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).testTag("background-guide-toggle"),
                contentPadding = PaddingValues(vertical = 10.dp)) {
                Text(if (expanded) texts(R.string.hide_guide) else texts(R.string.show_setup_guide), fontSize = 13.sp)
            }
            if (expanded) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                Spacer(Modifier.height(14.dp))
                Box {
                    OutlinedButton(onClick = { menu = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background-brand-picker"),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(texts(guide.label), fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        BackgroundBrand.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(texts(item.label), fontSize = 13.sp, lineHeight = 20.sp) },
                                onClick = { selected = item.name; menu = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(texts(R.string.selected_for_your_device_brand_you_can),
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                guide.steps.forEachIndexed { index, step ->
                    Spacer(Modifier.height(12.dp))
                    Text("${index + 1}. ${texts(step)}", fontSize = 13.sp, lineHeight = 22.sp)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { openBackgroundSettings(context, false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background-app-settings"),
                    contentPadding = PaddingValues(vertical = 10.dp)) {
                    Text(texts(R.string.open_quodex_app_info), Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 20.sp)
                }
                TextButton(onClick = { openBackgroundSettings(context, true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)) {
                    Text(texts(R.string.open_battery_optimization_settings), Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(if (exempt) texts(R.string.android_battery_optimization_exempt) else texts(R.string.android_battery_optimization_not_exempt),
                    fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(texts(R.string.this_status_only_reflects_android_battery_optimization),
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(texts(R.string.after_setup_return_to_the_app_and),
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(texts(R.string.if_you_used_force_stop_in_app),
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun openBackgroundSettings(context: Context, battery: Boolean) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    val intents = if (battery) listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details, Intent(Settings.ACTION_SETTINGS))
        else listOf(details, Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        try { context.startActivity(intent); return } catch (_: android.content.ActivityNotFoundException) {
            // Some vendor ROMs omit standard settings activities; use the next safe entry point.
        } catch (_: SecurityException) {
            // Vendor settings may deny access even when the activity is present.
        }
    }
    Toast.makeText(context, AndroidTextResources(context)(R.string.could_not_open_system_settings_search_settings), Toast.LENGTH_LONG).show()
}
