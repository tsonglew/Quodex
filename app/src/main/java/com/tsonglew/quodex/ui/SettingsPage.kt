package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quodex.BuildConfig
import com.tsonglew.quodex.MainViewModel
import com.tsonglew.quodex.data.AppState
import com.tsonglew.quodex.data.PreferencesState
import com.tsonglew.quodex.data.userMessage

/** A short index; each detailed setting has one destination. */
@Composable
internal fun SettingsPage(prefs: PreferencesState, model: MainViewModel, onOpen: (String) -> Unit) {
    val texts = localTextResources()
    val selectedLanguage = AppLanguages.selectedTag(LocalContext.current)
    val language = when (selectedLanguage.substringBefore('-')) {
        "zh" -> "简体中文"; "en" -> "English"; "ja" -> "日本語"
        else -> texts(R.string.follow_system)
    }
    val theme = texts(when (prefs.theme) {
        "light" -> R.string.light; "dark" -> R.string.dark; else -> R.string.follow_system
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(texts(R.string.settings), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text(texts(R.string.settings_preferences), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        SettingsGroup {
            NavigationRow(texts(R.string.appearance), theme, { onOpen("appearance") },
                Modifier.testTag("settings-appearance"), Icons.Outlined.Palette)
            SettingsDivider()
            NavigationRow(texts(R.string.app_language), language, { onOpen("language") },
                Modifier.testTag("settings-language"), Icons.Outlined.Language)
            SettingsDivider()
            PreferenceToggle(texts(R.string.show_remaining_quota), prefs.showRemaining, model::showRemaining,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("show-remaining-toggle"),
                supportingText = texts(R.string.settings_remaining_scope))
            SettingsDivider()
            NavigationRow(texts(R.string.settings_refresh), "Codex · " + texts(R.string.value_min, prefs.refreshMinutes),
                { onOpen("refresh") }, Modifier.testTag("settings-refresh"), Icons.Outlined.Schedule)
        }
        Text(texts(R.string.settings_tools), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        SettingsGroup {
            NavigationRow(texts(R.string.settings_desktop), texts(R.string.settings_desktop_summary), { onOpen("desktop") },
                Modifier.testTag("settings-desktop"), Icons.Outlined.Widgets)
            SettingsDivider()
            val updates by model.updates.collectAsStateWithLifecycle()
            NavigationRow(texts(R.string.app_updates), updates.saved.available?.let {
                texts(R.string.update_available_value, it.versionName)
            } ?: BuildConfig.VERSION_NAME, { onOpen("updates") },
                Modifier.testTag("settings-updates"), Icons.Outlined.SystemUpdate)
            SettingsDivider()
            NavigationRow(texts(R.string.settings_support), texts(R.string.settings_support_summary), { onOpen("support") },
                Modifier.testTag("settings-support"), Icons.AutoMirrored.Outlined.HelpOutline)
        }
    }
}

@Composable
internal fun SettingsDetail(route: String, state: AppState, prefs: PreferencesState, model: MainViewModel,
    now: Long, onGlassPreview: (com.tsonglew.quodex.data.GlassPreferences?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (route) {
            "appearance" -> AppearanceSettings(prefs, model, onGlassPreview)
            "language" -> SettingsPanel { LanguageSettings() }
            "refresh" -> RefreshSettings(prefs, model)
            "desktop" -> {
                WidgetPage(state, prefs)
                DesktopIconSettings(state, prefs, model, now)
            }
            "updates" -> {
                val updates by model.updates.collectAsStateWithLifecycle()
                AppUpdatesCard(updates, model::checkForUpdates, model::automaticUpdates,
                    model::updateNotificationPermissionChanged, now)
            }
            "support" -> {
                SettingsPanel { DiagnosticEntry(compact = true); PrivacyNoticeEntry() }
                Text(localTextResources()(R.string.quodex_value_nindependent_third_party_tool_experimental, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettings(prefs: PreferencesState, model: MainViewModel,
    onGlassPreview: (com.tsonglew.quodex.data.GlassPreferences?) -> Unit) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    SettingsPanel {
        SettingsLabel(texts(R.string.appearance), Icons.Outlined.Palette)
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to texts(R.string.follow_system), "light" to texts(R.string.light), "dark" to texts(R.string.dark)).forEach { (id, label) ->
                FilterChip(shape = RoundedCornerShape(50), selected = prefs.theme == id, onClick = { model.theme(id) },
                    label = { Text(label, fontSize = 14.sp, lineHeight = 20.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.primaryContainer,
                        selectedLabelColor = colors.onPrimaryContainer),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("theme-$id"))
            }
        }
        GlassSettings(prefs.glass, onGlassPreview, model::glassTint, model::reduceTransparency)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefreshSettings(prefs: PreferencesState, model: MainViewModel) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    var showRefreshInfo by rememberSaveable { mutableStateOf(false) }
    SettingsPanel {
        SettingsLabel(texts(R.string.target_background_interval), Icons.Outlined.Schedule)
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15L, 30L, 60L).forEach { minutes ->
                FilterChip(shape = RoundedCornerShape(50), selected = prefs.refreshMinutes == minutes, onClick = { model.refreshMinutes(minutes) },
                    label = { Text(texts(R.string.value_min, minutes), fontSize = 14.sp, lineHeight = 20.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.primaryContainer,
                        selectedLabelColor = colors.onPrimaryContainer),
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(texts(R.string.battery_saving_or_lost_connectivity_may_delay), fontSize = 12.sp, lineHeight = 20.sp,
            color = colors.onSurfaceVariant)
        TextButton(onClick = { showRefreshInfo = true }, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("background-refresh-info"), contentPadding = PaddingValues(vertical = 10.dp)) {
            Text(texts(R.string.about_refresh_timing), fontSize = 13.sp)
        }
    }

    Text(texts(R.string.settings_refresh_scope), style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant)
    BackgroundGuide()
    if (showRefreshInfo) GlassAlertDialog(surfaceId = "refresh-info", onDismissRequest = { showRefreshInfo = false },
        title = { Text(texts(R.string.background_refresh_details)) },
        text = {
            Text(texts(R.string.battery_and_network_conditions_may_delay_background),
                modifier = Modifier.verticalScroll(rememberScrollState()), lineHeight = 24.sp)
        }, confirmButton = { TextButton(onClick = { showRefreshInfo = false }) { Text(texts(R.string.got_it)) } })

}

@Composable
internal fun CodexAccountSettings(state: AppState, model: MainViewModel) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    SettingsPanel {
        SettingsLabel(texts(R.string.codex_connection), Icons.Outlined.AccountCircle)
        Spacer(Modifier.height(10.dp))
        Text(if (state.demo) texts(R.string.demo_mode) else if (state.connected) texts(R.string.connected_credentials_encrypted_on_device) else texts(R.string.not_connected),
            fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
        state.failure?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.userMessage().resolve(texts), fontSize = 12.sp, lineHeight = 20.sp, color = colors.error)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = model::startLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentPadding = PaddingValues(vertical = 10.dp)) {
            Text(if (state.connected) texts(R.string.reconnect) else texts(R.string.connect_account), Modifier.fillMaxWidth(), fontSize = 14.sp)
        }
        if (state.connected || state.demo) TextButton(onClick = { confirmLogout = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("logout-button"),
            contentPadding = PaddingValues(vertical = 10.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
            Text(if (state.demo) texts(R.string.exit_demo_mode) else texts(R.string.sign_out_and_clear_local_data), Modifier.fillMaxWidth(),
                fontSize = 14.sp, lineHeight = 21.sp)
        }
    }

    if (confirmLogout) GlassAlertDialog(surfaceId = "logout", onDismissRequest = { confirmLogout = false },
        title = { Text(texts(R.string.clear_local_connection)) },
        text = {
            Text(texts(R.string.local_credentials_quota_cache_and_background_quota),
                modifier = Modifier.verticalScroll(rememberScrollState()))
        }, confirmButton = {
            TextButton(onClick = { confirmLogout = false; model.logout() }, modifier = Modifier.testTag("confirm-logout")) { Text(texts(R.string.clear)) }
        }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(texts(R.string.cancel)) } })
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

@Composable
private fun SettingsLabel(text: String, icon: ImageVector) { PanelHeading(text, icon) }
